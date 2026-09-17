package com.gudesk.host.session;

import com.gudesk.common.crypto.CryptoUtil;
import com.gudesk.common.crypto.SessionCipher;
import com.gudesk.common.proto.GuDeskProto.AuthorizeRequest;
import com.gudesk.common.proto.GuDeskProto.AuthorizeResponse;
import com.gudesk.common.proto.GuDeskProto.HostCapabilities;
import com.gudesk.common.proto.GuDeskProto.KeyFrameRequest;
import com.gudesk.common.proto.GuDeskProto.KeyEvent;
import com.gudesk.common.proto.GuDeskProto.MouseButtonEvent;
import com.gudesk.common.proto.GuDeskProto.MouseMoveEvent;
import com.gudesk.common.proto.GuDeskProto.SessionClose;
import com.gudesk.common.proto.GuDeskProto.SessionMessage;
import com.gudesk.common.proto.GuDeskProto.SessionNegotiate;
import com.gudesk.common.proto.GuDeskProto.SessionNegotiateAck;
import com.gudesk.common.proto.GuDeskProto.VideoFrame;
import com.gudesk.common.proto.GuDeskProto.WheelEvent;
import com.gudesk.common.session.SessionEventListener;
import com.gudesk.common.session.SessionHandshake;
import com.gudesk.common.session.SessionState;
import com.gudesk.common.session.SessionTransport;
import com.gudesk.common.spi.AdapterCapabilities;
import com.gudesk.common.spi.AdapterConfig;
import com.gudesk.common.spi.InputInjector;
import com.gudesk.common.spi.NativeFrame;
import com.gudesk.common.spi.ScreenCapturer;
import com.gudesk.common.spi.SpiLoader;
import com.gudesk.common.spi.VideoEncoder;
import com.gudesk.host.capture.PortalScreenCapturer;
import com.gudesk.host.capture.RobotScreenCapturer;
import com.gudesk.host.encode.JavaCvVideoEncoder;
import com.gudesk.host.input.PortalInputInjector;
import com.gudesk.host.input.RobotInputInjector;
import com.gudesk.host.portal.PortalBackendDetector;
import com.gudesk.host.portal.PortalBackendInfo;
import com.google.protobuf.ByteString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 被控端会话状态机（每 TCP 连接一个实例）。
 *
 * <p>握手协议（SSH 密码认证等价的两轮协商，协议消息复用 SessionNegotiate/Ack）：
 * <ol>
 *   <li>主控端发 SessionNegotiate{V, password_proof=""}（明文，传临时公钥
 *       + 可选长期身份公钥 viewer_identity_public_key）；</li>
 *   <li>被控端生成临时密钥对 H，ECDH 派生会话密钥，<b>先以明文回</b>
 *       SessionNegotiateAck{H, authorized=false, reason="CHALLENGE"}，随后激活
 *       AES-GCM 密文管线；对身份公钥计算指纹（信任列表依据）；</li>
 *   <li>主控端激活密文后在密文通道提交明文密码与身份持有证明
 *       identity_proof = SHA256(ECDH(身份私钥, H公钥))——防止冒用他人身份公钥
 *       骗取信任列表免确认授权；</li>
 *   <li>被控端以 PBKDF2 常量时间比对本地哈希（{@link HostPasswordStore#verify}），
 *       失败回 Ack{authorized=false, reason="密码错误"} 并关闭，结果经
 *       passwordResultReporter 上报（连续 5 次锁定来源 IP 60s，见
 *       {@link com.gudesk.common.session.ConnectionGatekeeper}）；
 *       随后验证身份证明，失败回 Ack{reason="主控端身份证明失败"} 并关闭；</li>
 *   <li>授权确认：指纹命中 {@link TrustStore} 则跳过弹窗直接授权；
 *       未命中走 {@link Authorizer}（30s 超时视为拒绝，无头环境默认拒绝）；
 *       拒绝回 Ack{authorized=false, reason=...} 并关闭；响应带 always_trust=true
 *       时指纹写入信任列表持久化；</li>
 *   <li>接受则回 Ack{authorized=true}（密文），进入 ESTABLISHED，启动
 *       capturer/encoder/injector 媒体管线。</li>
 * </ol>
 *
 * <p>ESTABLISHED 后：捕获帧经编码以 VideoFrame 密文下发（出口不可写时丢帧防积压）；
 * 主控端输入事件同步注入本机（Robot 调用快且需保序，直接在 IO 线程执行）；
 * KeyFrameRequest 触发 {@code encoder.requestKeyframe()}。
 */
public final class HostSession implements SessionEventListener {

    private static final Logger LOG = LoggerFactory.getLogger(HostSession.class);

    /** 第一轮 Ack 的挑战标记（reason 值）：指示主控端激活密文并提交密码 */
    public static final String REASON_CHALLENGE = "CHALLENGE";
    /** 授权确认超时（毫秒）：超时视为拒绝（Authorizer 自身超时 + 此处兜底） */
    public static final long AUTHORIZE_TIMEOUT_MS = 30_000;
    /** 协商启动超时（毫秒）：start 后未收到第一轮协商即关闭（释放打洞/中继预建连接占用的会话槽） */
    public static final long NEGOTIATE_START_TIMEOUT_MS = 15_000;

    /** 授权结果处理执行器：虚拟线程（媒体管线启动等耗时操作不占 IO 事件循环与 EDT） */
    private static final ExecutorService AUTHORIZE_EXECUTOR =
            Executors.newVirtualThreadPerTaskExecutor();

    private final HostPasswordStore.PasswordRecord passwordRecord;
    private final Authorizer authorizer;
    private final TrustStore trustStore;
    private final Consumer<Boolean> passwordResultReporter;
    private final Consumer<HostSession> releaseSlot;
    private final Runnable establishedReporter;

    private final SessionTransport transport;
    private final AtomicBoolean cipherActive = new AtomicBoolean();
    private final AtomicBoolean passwordVerified = new AtomicBoolean();
    private final AtomicBoolean authorizationResolved = new AtomicBoolean();
    private final AtomicBoolean established = new AtomicBoolean();
    private final AtomicBoolean terminated = new AtomicBoolean();
    private final AtomicBoolean negotiationStarted = new AtomicBoolean();
    private final AtomicLong frameIndex = new AtomicLong();

    // 第一轮协商捕获：对端长期身份（公钥 + 指纹）与本端临时密钥对（供第二轮身份证明验证）
    private KeyPair hostEphemeralKeys;
    private PublicKey viewerIdentityKey;
    private String viewerFingerprint = "";

    // 媒体适配器（ESTABLISHED 后加载；SPI 适配器不持有会话状态）
    private volatile ScreenCapturer capturer;
    private volatile VideoEncoder encoder;
    private volatile InputInjector injector;
    private final AtomicBoolean mediaRunning = new AtomicBoolean();

    /**
     * @param transport             已就绪的会话传输（TCP 直连/中继已 attach 通道；UDP 已 start）
     * @param passwordRecord        密码验证记录（盐 + PBKDF2 哈希）
     * @param authorizer            授权确认实现（Swing 弹窗 / 自动；不可为 null）
     * @param trustStore            信任列表（指纹命中免弹窗）
     * @param passwordResultReporter 密码验证结果上报（true=通过清零计数，false=失败计数，
     *                              由服务端 ConnectionGatekeeper 决定锁定；可为 null）
     * @param releaseSlot           会话终止时释放服务端单会话槽
     * @param establishedReporter   会话进入 ESTABLISHED 时的回调（"被控中"状态指示；不可为 null）
     */
    public HostSession(SessionTransport transport,
                       HostPasswordStore.PasswordRecord passwordRecord,
                       Authorizer authorizer,
                       TrustStore trustStore,
                       Consumer<Boolean> passwordResultReporter,
                       Consumer<HostSession> releaseSlot,
                       Runnable establishedReporter) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.passwordRecord = passwordRecord;
        this.authorizer = Objects.requireNonNull(authorizer, "authorizer");
        this.trustStore = Objects.requireNonNull(trustStore, "trustStore");
        this.passwordResultReporter = passwordResultReporter;
        this.releaseSlot = releaseSlot;
        this.establishedReporter = Objects.requireNonNull(establishedReporter, "establishedReporter");
    }

    /** 开始等待协商（进入 NEGOTIATING，等待主控端 SessionNegotiate） */
    public void start() {
        LOG.info("主控端接入: {}，等待协商", transport.remoteDescription());
        // 协商启动看门狗：打洞/中继路径的预建连接若一直无流量（对端走了别的路径），
        // 超时关闭以释放单会话槽
        Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(NEGOTIATE_START_TIMEOUT_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (!terminated.get() && !cipherActive.get()) {
                LOG.info("协商启动超时（{} ms 无第一轮协商），关闭: {}",
                        NEGOTIATE_START_TIMEOUT_MS, transport.remoteDescription());
                shutdown("协商超时：主控端未发起协商");
            }
        });
    }

    /** 会话是否已终止（单会话槽位检查用） */
    public boolean isTerminated() {
        return terminated.get();
    }

    /** 会话是否已进入 ESTABLISHED（"被控中"状态指示用） */
    public boolean isEstablished() {
        return established.get();
    }

    /** 是否已收到首条会话消息（false=预建连接尚未协商，可被并发竞争中的 UDP 直连路径抢占） */
    public boolean hasNegotiationStarted() {
        return negotiationStarted.get();
    }

    /** 主动关闭（服务停止等） */
    public void shutdown(String reason) {
        transport.close(reason);
    }

    // ------------------------------------------------------------------
    // 入站消息分发（Netty IO 线程）
    // ------------------------------------------------------------------

    void onMessage(SessionMessage msg) {
        negotiationStarted.set(true);
        switch (msg.getPayloadCase()) {
            case NEGOTIATE -> onNegotiate(msg.getNegotiate());
            case MOUSE_MOVE -> {
                MouseMoveEvent m = msg.getMouseMove();
                if (established.get()) {
                    injector.injectMouse(m.getX(), m.getY());
                }
            }
            case MOUSE_BUTTON -> {
                MouseButtonEvent m = msg.getMouseButton();
                if (established.get()) {
                    injector.injectMouseButton(m.getButton().getNumber(), m.getPressed(),
                            m.getX(), m.getY());
                }
            }
            case WHEEL -> {
                WheelEvent w = msg.getWheel();
                if (established.get()) {
                    injector.injectWheel(w.getDeltaX(), w.getDeltaY());
                }
            }
            case KEY -> {
                KeyEvent k = msg.getKey();
                if (established.get()) {
                    injector.injectKey(k.getKeyCode(), k.getKeyChar(), k.getPressed());
                }
            }
            case KEYFRAME_REQUEST -> {
                if (established.get()) {
                    VideoEncoder enc = encoder;
                    if (enc != null) {
                        enc.requestKeyframe();
                    }
                }
            }
            case SESSION_CLOSE, VIDEO_FRAME, SESSION_HEARTBEAT, NEGOTIATE_ACK, AUTHORIZE_REQUEST,
                    AUTHORIZE_RESPONSE, RELIABILITY_ACK -> {
                // 心跳/关闭由 endpoint 内部处理；其余类型被控端不关心
                if (msg.getPayloadCase() == SessionMessage.PayloadCase.SESSION_CLOSE) {
                    LOG.debug("收到主控端断开: {}", msg.getSessionClose().getReason());
                }
            }
            default -> LOG.debug("忽略未知消息类型: {}", msg.getPayloadCase());
        }
    }

    // ------------------------------------------------------------------
    // 握手状态机
    // ------------------------------------------------------------------

    private void onNegotiate(SessionNegotiate negotiate) {
        if (negotiate.getPasswordProof().isEmpty()) {
            onNegotiateRound1(negotiate);
        } else {
            onNegotiateRound2(negotiate);
        }
    }

    /** 第一轮：交换临时公钥（+ 可选身份公钥）、建立会话密钥、切换密文管线 */
    private void onNegotiateRound1(SessionNegotiate negotiate) {
        if (cipherActive.get()) {
            LOG.warn("重复的第一轮协商，忽略");
            return;
        }
        try {
            byte[] viewerPub = negotiate.getEphemeralPublicKey().toByteArray();
            PublicKey viewerKey = SessionHandshake.parsePublicKey(viewerPub);
            KeyPair hostKeys = SessionHandshake.generateKeyPair();
            byte[] hostPub = SessionHandshake.encodePublicKey(hostKeys.getPublic());
            byte[] shared = SessionHandshake.ecdh(hostKeys.getPrivate(), viewerKey);
            byte[] salt = SessionHandshake.cipherSalt(viewerPub, hostPub);
            SessionCipher cipher = SessionCipher.init(shared, salt, false);

            // 长期身份公钥（可选）：计算指纹用于信任列表；旧版主控端不携带
            byte[] identityPub = negotiate.getViewerIdentityPublicKey().toByteArray();
            if (identityPub.length > 0) {
                viewerIdentityKey = SessionHandshake.parsePublicKey(identityPub);
                viewerFingerprint = CryptoUtil.fingerprint(identityPub);
                LOG.info("主控端身份指纹: {}", viewerFingerprint);
            }
            hostEphemeralKeys = hostKeys;

            // 先明文回挑战（主控端需从 Ack 获得被控端公钥才能派生密钥），再激活密文
            transport.send(SessionMessage.newBuilder()
                    .setNegotiateAck(SessionNegotiateAck.newBuilder()
                            .setEphemeralPublicKey(ByteString.copyFrom(hostPub))
                            .setAuthorized(false)
                            .setReason(REASON_CHALLENGE))
                    .build());
            transport.activateCipher(cipher);
            cipherActive.set(true);
            LOG.info("会话密钥已建立（{}），等待主控端提交密码", transport.remoteDescription());
        } catch (Exception e) {
            LOG.warn("协商失败: {}", String.valueOf(e));
            fail("协商失败: " + e.getMessage());
        }
    }

    /** 第二轮：密文通道验证密码 → 身份持有证明 → 授权确认 → ESTABLISHED */
    private void onNegotiateRound2(SessionNegotiate negotiate) {
        if (!cipherActive.get() || passwordVerified.get()) {
            LOG.warn("非法的协商轮次，忽略");
            return;
        }
        if (!HostPasswordStore.verify(negotiate.getPasswordProof(), passwordRecord)) {
            LOG.warn("主控端密码验证失败: {}", transport.remoteDescription());
            Consumer<Boolean> reporter = passwordResultReporter;
            if (reporter != null) {
                reporter.accept(false); // 来源 IP 失败计数（连续 5 次锁定 60s）
            }
            transport.send(SessionMessage.newBuilder()
                    .setNegotiateAck(SessionNegotiateAck.newBuilder()
                            .setAuthorized(false)
                            .setReason("密码错误"))
                    .build());
            fail("密码验证失败");
            return;
        }
        passwordVerified.set(true);
        Consumer<Boolean> reporter = passwordResultReporter;
        if (reporter != null) {
            reporter.accept(true); // 成功清零该来源失败计数
        }
        transport.setState(SessionState.AUTHENTICATING);

        // 身份持有证明验证（携带身份公钥时必须提供有效证明，防冒用他人身份骗取免确认）
        if (viewerIdentityKey != null && !verifyIdentityProof(negotiate)) {
            LOG.warn("主控端身份证明验证失败: {}", transport.remoteDescription());
            transport.send(SessionMessage.newBuilder()
                    .setNegotiateAck(SessionNegotiateAck.newBuilder()
                            .setAuthorized(false)
                            .setReason("主控端身份证明失败"))
                    .build());
            fail("主控端身份证明失败");
            return;
        }
        LOG.info("密码验证通过，等待授权确认: {}（主控端指纹: {}）",
                transport.remoteDescription(), viewerFingerprint.isEmpty() ? "未提供" : viewerFingerprint);
        proceedToAuthorization();
    }

    /** 身份证明比对：SHA256(ECDH(本端临时私钥, 主控端身份公钥)) 常量时间比较 */
    private boolean verifyIdentityProof(SessionNegotiate negotiate) {
        try {
            byte[] expected = CryptoUtil.sha256(
                    SessionHandshake.ecdh(hostEphemeralKeys.getPrivate(), viewerIdentityKey));
            byte[] proof = negotiate.getIdentityProof().toByteArray();
            return proof.length > 0 && MessageDigest.isEqual(expected, proof);
        } catch (Exception e) {
            LOG.warn("身份证明计算失败: {}", String.valueOf(e));
            return false;
        }
    }

    // ------------------------------------------------------------------
    // 授权确认（信任列表优先，未命中走 Authorizer）
    // ------------------------------------------------------------------

    private void proceedToAuthorization() {
        if (!viewerFingerprint.isEmpty() && trustStore.isTrusted(viewerFingerprint)) {
            LOG.info("主控端指纹在信任列表中，跳过授权确认: {}", viewerFingerprint);
            completeAuthorization(true, false);
            return;
        }
        AuthorizeRequest request = AuthorizeRequest.newBuilder()
                .setViewerId(transport.remoteDescription())
                .setViewerFingerprint(viewerFingerprint)
                .build();
        authorizer.requestAuthorization(request)
                .orTimeout(AUTHORIZE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .whenComplete((response, error) -> CompletableFuture.runAsync(
                        () -> handleAuthorizeResult(response, error), AUTHORIZE_EXECUTOR));
    }

    /** Authorizer 结果处理（虚拟线程上）：超时/异常均视为拒绝 */
    private void handleAuthorizeResult(AuthorizeResponse response, Throwable error) {
        if (terminated.get()) {
            return;
        }
        if (error != null) {
            rejectAuthorization(isTimeout(error) ? "授权确认超时，已自动拒绝" : "授权确认异常，已拒绝");
            return;
        }
        if (response == null || !response.getAccepted()) {
            rejectAuthorization("用户拒绝授权");
            return;
        }
        completeAuthorization(true, response.getAlwaysTrust());
    }

    private static boolean isTimeout(Throwable error) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null
                ? error.getCause() : error;
        return cause instanceof TimeoutException;
    }

    /** 授权通过（或信任命中）：持久化"始终信任"、回 Ack、进入 ESTABLISHED */
    private void completeAuthorization(boolean accepted, boolean alwaysTrust) {
        if (!authorizationResolved.compareAndSet(false, true)) {
            return;
        }
        if (accepted && alwaysTrust && !viewerFingerprint.isEmpty()) {
            try {
                trustStore.trust(viewerFingerprint, transport.remoteDescription());
                LOG.info("主控端指纹已加入信任列表: {}", viewerFingerprint);
            } catch (IOException e) {
                LOG.warn("信任列表写入失败: {}", String.valueOf(e));
            }
        }
        transport.send(SessionMessage.newBuilder()
                .setNegotiateAck(SessionNegotiateAck.newBuilder()
                        .setAuthorized(true)
                        .setCapabilities(detectHostCapabilities()))
                .build());
        established.set(true);
        establishedReporter.run(); // 通知"被控中"状态（HostSessionManager → UI）
        transport.setState(SessionState.ESTABLISHED); // 启动会话内心跳
        LOG.info("会话已建立: {}", transport.remoteDescription());
        startMediaPipeline();
    }

    /** 探测被控端能力（下发给主控端 UI 展示）：Wayland 走 portal 后端探测，其余走 Robot 固定能力 */
    private HostCapabilities detectHostCapabilities() {
        boolean wayland = RobotScreenCapturer.isWaylandSession(System.getenv());
        PortalBackendInfo backend = wayland ? PortalBackendDetector.detectCurrent() : null;
        if (wayland && backend != null && backend.isKnown() && !backend.persistentSupported()) {
            LOG.warn("当前 portal 后端（{}）不支持持久化授权，每次连接均需用户确认（无人值守不可用）",
                    backend.backendKind());
        }
        return hostCapabilities(wayland, backend);
    }

    /** 能力位映射（纯函数，注入会话类型与后端探测结果，便于单测） */
    static HostCapabilities hostCapabilities(boolean waylandSession, PortalBackendInfo backend) {
        if (waylandSession && backend != null) {
            return HostCapabilities.newBuilder()
                    .setPersistentConsent(backend.persistentSupported())
                    .setAbsolutePointer(backend.absolutePointerSupported())
                    .build();
        }
        // X11/Windows：Robot 注入支持绝对坐标（mouseMove），无持久化授权
        return HostCapabilities.newBuilder()
                .setPersistentConsent(false)
                .setAbsolutePointer(true)
                .build();
    }

    /** 授权拒绝：回 Ack{authorized=false, reason} 并关闭 */
    private void rejectAuthorization(String reason) {
        if (!authorizationResolved.compareAndSet(false, true)) {
            return;
        }
        LOG.warn("授权确认拒绝: {}（原因: {}）", transport.remoteDescription(), reason);
        transport.send(SessionMessage.newBuilder()
                .setNegotiateAck(SessionNegotiateAck.newBuilder()
                        .setAuthorized(false)
                        .setReason(reason))
                .build());
        fail(reason);
    }

    // ------------------------------------------------------------------
    // 媒体管线（ESTABLISHED 后）
    // ------------------------------------------------------------------

    private void startMediaPipeline() {
        try {
            // 按运行环境显式选择默认捕获/注入实现（用户显式配置 SPI 偏好时不干预）
            PortalScreenCapturer.selectPlatformDefault(System.getenv());
            PortalInputInjector.selectPlatformDefault(System.getenv());
            capturer = SpiLoader.load(ScreenCapturer.class, "capturer",
                    RobotScreenCapturer::defaultCapturer);
            encoder = SpiLoader.load(VideoEncoder.class, "encoder",
                    JavaCvVideoEncoder::defaultEncoder);
            injector = SpiLoader.load(InputInjector.class, "injector",
                    RobotInputInjector::defaultInjector);

            AdapterCapabilities caps = capturer.capabilities();
            int width = Math.max(2, caps.maxWidth());
            int height = Math.max(2, caps.maxHeight());
            int fps = Math.min(30, caps.maxFps());
            AdapterConfig config = AdapterConfig.builder()
                    .width(width).height(height).fps(fps)
                    .pixelFormat(RobotScreenCapturer.PIXEL_FORMAT)
                    .putExtra("bitrate", "4000000")
                    .build();

            capturer.setCaptureListener(this::onCapturedFrame);
            encoder.init(config);
            capturer.init(config);
            injector.init(config);
            encoder.start();
            capturer.start();
            mediaRunning.set(true);
            LOG.info("媒体管线已启动: {}x{}@{}fps（capturer={}, encoder={}, injector={})",
                    width, height, fps,
                    capturer.getClass().getSimpleName(),
                    encoder.getClass().getSimpleName(),
                    injector.getClass().getSimpleName());
        } catch (Throwable t) {
            LOG.error("媒体管线启动失败", t);
            fail("媒体管线启动失败: " + t.getMessage());
        }
    }

    /** 捕获线程回调：编码并密文下发（出口不可写时丢帧防积压） */
    private void onCapturedFrame(NativeFrame frame) {
        VideoEncoder enc = encoder;
        if (enc == null || !established.get()) {
            frame.close();
            return;
        }
        try {
            long captureNs = frame.timestampNs();
            enc.encode(frame, encoded -> {
                try {
                    if (transport.isWritable()) {
                        boolean keyframe = JavaCvVideoEncoder.containsSps(encoded.buffer());
                        // 视频帧走尽力而为通道（UDP 不重传，TCP 忽略该标志）
                        transport.send(SessionMessage.newBuilder()
                                .setVideoFrame(VideoFrame.newBuilder()
                                        .setH264Data(ByteString.copyFrom(encoded.buffer()))
                                        .setFrameIndex(frameIndex.incrementAndGet())
                                        .setKeyframe(keyframe)
                                        .setWidth(encoded.width())
                                        .setHeight(encoded.height())
                                        .setCaptureNs(captureNs))
                                .build(), false);
                    } else {
                        LOG.debug("通道不可写，丢弃视频帧 #{}", frameIndex.get() + 1);
                    }
                } finally {
                    encoded.close();
                }
            });
        } catch (Throwable t) {
            LOG.warn("视频编码失败", t);
        } finally {
            frame.close();
        }
    }

    private void stopMedia() {
        if (!mediaRunning.compareAndSet(true, false)) {
            return;
        }
        closeQuietly(capturer);
        closeQuietly(encoder);
        releaseQuietly(injector);
        capturer = null;
        encoder = null;
        injector = null;
        LOG.info("媒体管线已停止（累计下发 {} 帧）", frameIndex.get());
    }

    // ------------------------------------------------------------------
    // 会话事件（SessionEventListener，endpoint 在 IO 线程回调）
    // ------------------------------------------------------------------

    @Override
    public void onStateChange(SessionState newState) {
        LOG.debug("会话状态: {} → {}", transport.remoteDescription(), newState);
    }

    @Override
    public void onVideoFrame(VideoFrame frame) {
        // 被控端不接收视频帧
    }

    @Override
    public void onLatencySample(long oneWayMs) {
        LOG.debug("主控端单向延迟采样: {} ms", oneWayMs);
    }

    @Override
    public void onSessionError(String message) {
        LOG.warn("会话错误: {}", message);
    }

    @Override
    public void onClose(String reason) {
        if (!terminated.compareAndSet(false, true)) {
            return;
        }
        LOG.info("会话关闭: {}（原因: {}）", transport.remoteDescription(), reason);
        stopMedia();
        Consumer<HostSession> release = releaseSlot;
        if (release != null) {
            release.accept(this);
        }
    }

    /** 以指定原因失败并关闭会话 */
    private void fail(String reason) {
        transport.close(reason);
    }

    // ------------------------------------------------------------------

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception e) {
            LOG.debug("适配器关闭失败: {}", String.valueOf(e));
        }
    }

    private static void releaseQuietly(com.gudesk.common.spi.Adapter adapter) {
        if (adapter == null) {
            return;
        }
        try {
            adapter.release();
        } catch (Exception e) {
            LOG.debug("适配器释放失败: {}", String.valueOf(e));
        }
    }
}
