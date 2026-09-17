package com.gudesk.viewer.session;

import com.gudesk.common.crypto.CryptoUtil;
import com.gudesk.common.crypto.SessionCipher;
import com.gudesk.common.proto.GuDeskProto.KeyEvent;
import com.gudesk.common.proto.GuDeskProto.KeyFrameRequest;
import com.gudesk.common.proto.GuDeskProto.MouseButtonEvent;
import com.gudesk.common.proto.GuDeskProto.MouseMoveEvent;
import com.gudesk.common.proto.GuDeskProto.SessionClose;
import com.gudesk.common.proto.GuDeskProto.SessionMessage;
import com.gudesk.common.proto.GuDeskProto.SessionNegotiate;
import com.gudesk.common.proto.GuDeskProto.SessionNegotiateAck;
import com.gudesk.common.proto.GuDeskProto.WheelEvent;
import com.gudesk.common.session.SessionEventListener;
import com.gudesk.common.session.SessionHandshake;
import com.gudesk.common.session.SessionState;
import com.gudesk.common.session.SessionTransport;
import com.gudesk.common.session.TcpSessionEndpoint;
import com.gudesk.common.spi.FrameRenderer;
import com.gudesk.common.spi.NativeFrame;
import com.gudesk.common.spi.VideoDecoder;
import com.gudesk.viewer.decode.JavaCvVideoDecoder;
import com.gudesk.viewer.input.InputForwarder;
import com.google.protobuf.ByteString;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 主控端会话客户端：完成两轮协商（见 {@code HostSession} 的 SSH 式密码认证），
 * ESTABLISHED 后接收视频流（IO 线程解码 → FX 线程渲染，pending 防积压）并转发输入事件。
 *
 * <p>传输层解耦：业务逻辑只依赖 {@link SessionTransport}——TCP 直连
 * （{@link #connect}）自行建 TCP 端点；UDP 打洞/中继路径由
 * {@link ViewerConnectionOrchestrator} 建好传输后经 {@link #attachTransport} 注入
 * （本类随即发送第一轮协商）。
 *
 * <p>身份：初始化时加载/生成 X25519 长期身份密钥对
 * （{@link ViewerIdentityStore}，{@code ~/.gudesk/viewer_identity}），协商时携带身份公钥
 * 与持有证明 SHA256(ECDH(身份私钥, 被控端临时公钥))——被控端按身份公钥指纹维护
 * "始终信任"列表（命中免授权确认弹窗），持有证明防止冒用他人身份公钥。
 *
 * <p>线程模型：解码在专用平台线程（解耦自 Netty IO 线程，满足 JavaCV JNI 约束）；
 * IO 线程只负责收包并投递到容量 1 的交付槽（解码线程忙时丢旧帧，避免 TCP 背压）；
 * 渲染经 {@code Platform.runLater} 提交 FX 线程（renderer 非 null 即 UI 模式）；
 * 统计回调在解码线程，实现方自行保证线程安全。
 *
 * <p>{@code renderer == null} 为无 UI 模式（CLI 联调统计），跳过渲染与 JavaFX 依赖。
 */
public final class ViewerSessionClient implements SessionEventListener, InputForwarder {

    private static final Logger LOG = LoggerFactory.getLogger(ViewerSessionClient.class);

    /** 被控端第一轮 Ack 的挑战标记（与 HostSession.REASON_CHALLENGE 协议约定一致） */
    static final String REASON_CHALLENGE = "CHALLENGE";

    /** 共享 IO 线程组（守护线程，全部客户端实例复用） */
    private static final EventLoopGroup IO_GROUP = new NioEventLoopGroup(1, threadFactory("gudesk-viewer-io"));

    /** 客户端事件回调（IO 线程上调用，实现方保证线程安全） */
    public interface Listener {

        /** 协商+授权完成，会话 ESTABLISHED */
        void onConnected();

        /** 协商/密码/授权失败（用户可读原因） */
        void onConnectFailed(String reason);

        /** 每帧解码完成：captureToDecodeMs 为被控端 capture_ns → 本机解码完成的毫秒差
         * （同机/时钟同步场景有效；跨机以 {@link #onLatency} 心跳单向延迟为准） */
        void onFrameDecoded(long captureToDecodeMs, int width, int height);

        /** 心跳单向延迟采样（RTT/2，毫秒） */
        void onLatency(long oneWayMs);

        /**
         * 被控端能力（authorized=true 时携带，紧随 {@link #onConnected} 回调）：
         * persistentConsent=授权可持久化（restore token 免弹窗）、
         * absolutePointer=输入注入支持绝对坐标。旧版被控端不携带时均为 false。
         */
        default void onCapabilities(boolean persistentConsent, boolean absolutePointer) {
        }

        /** 已建立会话关闭（对端断开/心跳超时/本端主动断开） */
        void onClosed(String reason);
    }

    private final VideoDecoder decoder;
    private final FrameRenderer renderer; // 可 null：无 UI 模式
    private final Listener listener;
    /** 长期身份密钥对（指纹稳定，被控端信任列表识别本机） */
    private final KeyPair identityKeys;
    private final byte[] identityPub;

    private volatile SessionTransport endpoint;
    private KeyPair viewerKeys;
    private byte[] viewerPub;
    private String password;

    private final AtomicBoolean connected = new AtomicBoolean();
    /** 握手结果是否已回调（区分"连接失败"与"建立后关闭"） */
    private final AtomicBoolean handshakeReported = new AtomicBoolean();
    /** 渲染积压保护：FX 队列最多挂起 1 帧 */
    private final AtomicBoolean pendingRender = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    /** 专用解码线程（平台线程，JavaCV JNI 约束）：软解码移出 Netty IO 线程 */
    private final Thread decodeThread;
    /** 待解码帧交付槽（容量 1）：解码线程忙时保留最新帧、丢弃旧帧 */
    private final ArrayBlockingQueue<NativeFrame> decodeQueue = new ArrayBlockingQueue<>(1);
    /** 上次请求关键帧时间（节流：避免丢帧密集时刷 KeyFrameRequest） */
    private volatile long lastKeyframeRequestMs;

    /**
     * 默认构造：加载/生成默认位置（{@code ~/.gudesk/viewer_identity}）的长期身份密钥。
     *
     * @param decoder  视频解码器（须已 init/start；会话关闭时由本客户端 stop/close）
     * @param renderer 渲染器（null = 无 UI 模式，跳过渲染）
     * @param listener 事件回调
     */
    public ViewerSessionClient(VideoDecoder decoder, FrameRenderer renderer, Listener listener) {
        this(decoder, renderer, listener, loadIdentity());
    }

    /**
     * @param identityKeys 长期身份密钥对（测试注入；不可为 null）
     */
    public ViewerSessionClient(VideoDecoder decoder, FrameRenderer renderer, Listener listener,
                               KeyPair identityKeys) {
        this.decoder = decoder;
        this.renderer = renderer;
        this.listener = listener;
        this.identityKeys = Objects.requireNonNull(identityKeys, "identityKeys");
        this.identityPub = SessionHandshake.encodePublicKey(identityKeys.getPublic());
        this.decodeThread = new Thread(this::decodeLoop, "gudesk-viewer-decode");
        this.decodeThread.setDaemon(true);
        this.decodeThread.start();
    }

    /** 身份密钥加载：失败时降级为进程内临时身份（本次连接指纹不持久） */
    private static KeyPair loadIdentity() {
        try {
            return ViewerIdentityStore.loadOrGenerate();
        } catch (IOException | RuntimeException e) {
            LOG.warn("身份密钥加载/生成失败，降级为进程内临时身份: {}", String.valueOf(e));
            try {
                return SessionHandshake.generateKeyPair();
            } catch (GeneralSecurityException g) {
                throw new IllegalStateException("生成临时身份密钥失败", g);
            }
        }
    }

    /** 本机长期身份公钥指纹（被控端信任列表的识别标识） */
    public String identityFingerprint() {
        return CryptoUtil.fingerprint(identityPub);
    }

    /** 异步 TCP 直连（ip:port）：结果经 listener.onConnected/onConnectFailed 回调 */
    public void connect(String host, int port, String password) {
        this.password = password == null ? "" : password;
        if (!prepareKeys()) {
            return;
        }
        Bootstrap bootstrap = new Bootstrap()
                .group(IO_GROUP)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.TCP_NODELAY, true)
                .handler(new ChannelInboundHandlerAdapter()); // 占位，attach 后由 endpoint 接管
        ChannelFuture future = bootstrap.connect(host, port);
        future.addListener((ChannelFutureListener) f -> {
            if (!f.isSuccess()) {
                listener.onConnectFailed("连接失败: "
                        + (f.cause() == null ? "未知原因" : f.cause().getMessage()));
                return;
            }
            SocketChannel ch = (SocketChannel) f.channel();
            TcpSessionEndpoint ep = new TcpSessionEndpoint(this, this::onMessage);
            endpoint = ep;
            ep.attach(ch);
            sendNegotiateRound1();
        });
    }

    /**
     * 接入编排器建好的传输（UDP 打洞成功 / 中继已配对，回调与接收已接线），
     * 立即发送第一轮协商（明文临时公钥 + 长期身份公钥）。
     */
    public void attachTransport(SessionTransport transport, String password) {
        this.password = password == null ? "" : password;
        if (!prepareKeys()) {
            return;
        }
        endpoint = transport;
        sendNegotiateRound1();
    }

    /** 是否已接入传输（false=编排未完成，取消/失败应收尾走 {@link #abort}） */
    public boolean hasTransport() {
        return endpoint != null;
    }

    /** 生成本次连接的临时密钥对（失败经 onConnectFailed 回调并返回 false） */
    private boolean prepareKeys() {
        try {
            viewerKeys = SessionHandshake.generateKeyPair();
            viewerPub = SessionHandshake.encodePublicKey(viewerKeys.getPublic());
            return true;
        } catch (Exception e) {
            listener.onConnectFailed("生成密钥对失败: " + e.getMessage());
            return false;
        }
    }

    /** 第一轮协商：明文发送临时公钥 + 长期身份公钥（password_proof 留空） */
    private void sendNegotiateRound1() {
        SessionTransport ep = endpoint;
        if (ep == null) {
            return;
        }
        ep.send(SessionMessage.newBuilder()
                .setNegotiate(SessionNegotiate.newBuilder()
                        .setEphemeralPublicKey(ByteString.copyFrom(viewerPub))
                        .setViewerIdentityPublicKey(ByteString.copyFrom(identityPub)))
                .build());
        LOG.debug("已发送第一轮协商（身份指纹 {}），等待被控端挑战", identityFingerprint());
    }

    /** 主动断开：发送 SessionClose 并关闭会话（结果经 listener.onClosed 回调） */
    public void disconnect() {
        SessionTransport ep = endpoint;
        if (ep != null) {
            if (connected.get()) {
                ep.send(SessionMessage.newBuilder()
                        .setSessionClose(SessionClose.newBuilder().setReason("主控端主动断开"))
                        .build());
            }
            ep.close("主控端主动断开");
        }
    }

    /**
     * 编排失败/取消时收尾（尚未接入传输，无会话回调可触发）：停止解码器。
     * 已接入传输的会话断开走 {@link #disconnect}（onClose 回调内自行收尾）。
     */
    public void abort() {
        closeQuietly("连接编排未完成");
    }

    public boolean isConnected() {
        return connected.get();
    }

    // ------------------------------------------------------------------
    // 入站消息（IO 线程）：协商 Ack 处理
    // ------------------------------------------------------------------

    /** 入站非心跳/关闭/视频消息处理（包内可见：编排器以此为 UDP 端点 messageHandler） */
    void onMessage(SessionMessage msg) {
        if (msg.getPayloadCase() != SessionMessage.PayloadCase.NEGOTIATE_ACK) {
            LOG.debug("忽略非协商消息: {}", msg.getPayloadCase());
            return;
        }
        SessionNegotiateAck ack = msg.getNegotiateAck();
        if (REASON_CHALLENGE.equals(ack.getReason())) {
            onChallenge(ack);
            return;
        }
        if (ack.getAuthorized()) {
            onAuthorized(ack);
        } else {
            String reason = ack.getReason() == null || ack.getReason().isBlank()
                    ? "被控端拒绝连接" : ack.getReason();
            if (handshakeReported.compareAndSet(false, true)) {
                listener.onConnectFailed(reason);
            }
            closeQuietly(reason);
        }
    }

    /** 第一轮 Ack（挑战）：派生会话密钥，切换密文管线后提交密码与身份持有证明 */
    private void onChallenge(SessionNegotiateAck ack) {
        try {
            byte[] hostPub = ack.getEphemeralPublicKey().toByteArray();
            byte[] shared = SessionHandshake.ecdh(viewerKeys.getPrivate(),
                    SessionHandshake.parsePublicKey(hostPub));
            byte[] salt = SessionHandshake.cipherSalt(viewerPub, hostPub);
            SessionTransport ep = endpoint;
            if (ep == null) {
                return;
            }
            ep.activateCipher(SessionCipher.init(shared, salt, true));
            // 身份持有证明：ECDH(身份私钥, 被控端临时公钥) 的 SHA-256
            // （被控端以对侧 ECDH 常量时间比对，防冒用他人身份公钥骗取信任列表免确认）
            byte[] identityProof = CryptoUtil.sha256(SessionHandshake.ecdh(
                    identityKeys.getPrivate(), SessionHandshake.parsePublicKey(hostPub)));
            // 密码与身份证明经 AES-GCM 密文通道提交（SSH 密码认证等价）
            ep.send(SessionMessage.newBuilder()
                    .setNegotiate(SessionNegotiate.newBuilder()
                            .setEphemeralPublicKey(ByteString.copyFrom(viewerPub))
                            .setPasswordProof(password)
                            .setIdentityProof(ByteString.copyFrom(identityProof)))
                    .build());
            LOG.debug("密钥已建立，已提交密码与身份证明（密文）");
        } catch (Exception e) {
            if (handshakeReported.compareAndSet(false, true)) {
                listener.onConnectFailed("协商失败: " + e.getMessage());
            }
            closeQuietly("协商失败");
        }
    }

    /** 最终 Ack（authorized=true）：会话建立，携带被控端能力位 */
    private void onAuthorized(SessionNegotiateAck ack) {
        SessionTransport ep = endpoint;
        if (ep == null) {
            return;
        }
        connected.set(true);
        handshakeReported.set(true);
        ep.setState(SessionState.ESTABLISHED); // 启动会话内心跳
        listener.onConnected();
        // 能力位（旧版被控端不携带 capabilities 时 hasCapabilities=false，默认 false）
        boolean persistentConsent = ack.hasCapabilities() && ack.getCapabilities().getPersistentConsent();
        boolean absolutePointer = ack.hasCapabilities() && ack.getCapabilities().getAbsolutePointer();
        listener.onCapabilities(persistentConsent, absolutePointer);
    }

    // ------------------------------------------------------------------
    // 视频链路：IO 线程解密投递 → 解码线程软解码 → FX 线程渲染
    // ------------------------------------------------------------------

    @Override
    public void onVideoFrame(com.gudesk.common.proto.GuDeskProto.VideoFrame protoFrame) {
        if (!connected.get()) {
            return;
        }
        ByteBuffer h264 = protoFrame.getH264Data().asReadOnlyByteBuffer();
        NativeFrame encoded = new NativeFrame(h264,
                (int) protoFrame.getWidth(), (int) protoFrame.getHeight(),
                JavaCvVideoDecoder.INPUT_PIXEL_FORMAT, protoFrame.getCaptureNs());
        // 解码已移出 IO 线程：容量 1 交付槽，解码线程忙时保留最新帧、丢弃旧帧
        // （与捕获端交付槽同款防积压策略，避免软解码阻塞 IO 线程造成 TCP 背压与延迟累积）
        if (!decodeQueue.offer(encoded)) {
            NativeFrame stale = decodeQueue.poll();
            if (stale != null) {
                stale.close();
            }
            decodeQueue.offer(encoded);
            // 丢帧 = H.264 帧间依赖断裂，主动请求关键帧快速重同步（不等周期性 IDR）
            requestKeyframeThrottled();
        }
    }

    /** 请求被控端立即输出关键帧（500ms 节流，可靠通道） */
    private void requestKeyframeThrottled() {
        long now = System.currentTimeMillis();
        if (now - lastKeyframeRequestMs < 500) {
            return;
        }
        lastKeyframeRequestMs = now;
        SessionTransport ep = endpoint;
        if (ep != null && connected.get()) {
            ep.send(SessionMessage.newBuilder()
                    .setKeyframeRequest(KeyFrameRequest.newBuilder())
                    .build());
        }
    }

    /** 专用解码循环（平台线程）：从交付槽取帧同步解码，解码结果走统计 + FX 渲染 */
    private void decodeLoop() {
        while (!closed.get()) {
            NativeFrame encoded;
            try {
                encoded = decodeQueue.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (closed.get()) {
                encoded.close();
                return;
            }
            long captureNs = encoded.timestampNs();
            try {
                decoder.decode(encoded, decoded -> onDecoded(decoded, captureNs));
            } catch (Throwable t) {
                LOG.warn("解码失败", t);
            } finally {
                encoded.close();
            }
        }
    }

    /** 解码完成回调（解码线程）：统计上报 + 经 Platform.runLater 提交 FX 渲染 */
    private void onDecoded(NativeFrame decoded, long captureNs) {
        long captureToDecodeMs = Math.max(0, (System.nanoTime() - captureNs) / 1_000_000L);
        listener.onFrameDecoded(captureToDecodeMs, decoded.width(), decoded.height());
        FrameRenderer r = renderer;
        boolean dispatched = false;
        if (r != null && pendingRender.compareAndSet(false, true)) {
            dispatched = true;
            Platform.runLater(() -> {
                pendingRender.set(false);
                try {
                    r.render(decoded);
                } catch (Throwable t) {
                    LOG.debug("渲染失败", t);
                } finally {
                    decoded.close(); // 归还解码输出池
                }
            });
        }
        if (!dispatched) {
            decoded.close();
        }
    }

    // ------------------------------------------------------------------
    // 输入转发（InputForwarder，FX 线程调用 → IO 线程写出）
    // ------------------------------------------------------------------

    @Override
    public void onMouseMove(double nx, double ny) {
        sendInput(SessionMessage.newBuilder()
                .setMouseMove(MouseMoveEvent.newBuilder().setX(nx).setY(ny)));
    }

    @Override
    public void onMouseButton(int button, boolean pressed, double nx, double ny) {
        // InputForwarder 编号(1=左,2=右,3=中) → 协议枚举编号(0=左,1=中,2=右)
        int protocolButton = switch (button) {
            case 1 -> 0;  // 左键
            case 2 -> 2;  // 右键
            case 3 -> 1;  // 中键
            default -> 0;
        };
        sendInput(SessionMessage.newBuilder()
                .setMouseButton(MouseButtonEvent.newBuilder()
                        .setButtonValue(protocolButton)
                        .setPressed(pressed)
                        .setX(nx).setY(ny)));
    }

    @Override
    public void onWheel(double dx, double dy) {
        sendInput(SessionMessage.newBuilder()
                .setWheel(WheelEvent.newBuilder().setDeltaX(dx).setDeltaY(dy)));
    }

    @Override
    public void onKey(int keyCode, String keyChar, boolean pressed) {
        sendInput(SessionMessage.newBuilder()
                .setKey(KeyEvent.newBuilder()
                        .setKeyCode(keyCode)
                        .setKeyChar(keyChar == null ? "" : keyChar)
                        .setPressed(pressed)));
    }

    private void sendInput(SessionMessage.Builder builder) {
        SessionTransport ep = endpoint;
        if (ep != null && connected.get()) {
            ep.send(builder.build());
        }
    }

    // ------------------------------------------------------------------
    // 会话事件（SessionEventListener）
    // ------------------------------------------------------------------

    @Override
    public void onStateChange(SessionState newState) {
        LOG.debug("会话状态: {}", newState);
    }

    @Override
    public void onLatencySample(long oneWayMs) {
        listener.onLatency(oneWayMs);
    }

    @Override
    public void onSessionError(String message) {
        LOG.warn("会话错误: {}", message);
    }

    @Override
    public void onClose(String reason) {
        connected.set(false);
        if (handshakeReported.compareAndSet(false, true)) {
            // 握手阶段连接断开 → 连接失败
            listener.onConnectFailed("连接中断: " + reason);
        } else {
            listener.onClosed(reason);
        }
        closeQuietly(reason);
    }

    /** 幂等收尾：停止解码器（渲染器生命周期由 UI 侧管理） */
    private void closeQuietly(String reason) {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        // 先停解码线程（中断 + 等待），确保 decoder.stop/close 不与解码竞争
        decodeThread.interrupt();
        try {
            decodeThread.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        NativeFrame stale;
        while ((stale = decodeQueue.poll()) != null) {
            stale.close();
        }
        try {
            decoder.stop();
        } catch (Exception e) {
            LOG.debug("解码器停止失败: {}", String.valueOf(e));
        }
        try {
            decoder.close();
        } catch (Exception e) {
            LOG.debug("解码器关闭失败: {}", String.valueOf(e));
        }
        LOG.info("主控端会话已关闭（原因: {}）", reason);
    }

    private static ThreadFactory threadFactory(String name) {
        return r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        };
    }
}
