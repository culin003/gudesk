package com.gudesk.common.session;

import com.gudesk.common.codec.SessionPacketCodec;
import com.gudesk.common.crypto.SessionCipher;
import com.gudesk.common.proto.GuDeskProto.KeyFrameRequest;
import com.gudesk.common.proto.GuDeskProto.SessionHeartbeat;
import com.gudesk.common.proto.GuDeskProto.SessionMessage;
import com.gudesk.common.proto.GuDeskProto.VideoFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.security.GeneralSecurityException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * UDP 会话端点（{@link TcpSessionEndpoint} 的 UDP 对应物，实现 {@link SessionTransport}）：
 * 承载打洞成功后的会话数据，复用打洞 socket。
 *
 * <p><b>包格式</b>（首字节类型，低 2 位与 {@link SessionPacketCodec} 的可靠性标志兼容）：
 * <ul>
 *   <li>0 DATA：AES-GCM 密文（best-effort，视频帧整帧，密文 ≤ {@link #FRAGMENT_PAYLOAD_LIMIT}）；</li>
 *   <li>1 RELIABLE：seq(8B) + AES-GCM 密文（可靠通道，cipher 激活后）；</li>
 *   <li>2 PLAIN：seq(8B) + 明文 protobuf（可靠通道，协商阶段）；</li>
 *   <li>3 ACK：ack(8B)（累积确认，明文小包，丢失由后续 ACK/重传弥补）；</li>
 *   <li>4 FRAGMENT：frameSeq(4B) + total(1B) + index(1B) + 密文分片（视频大帧）。</li>
 * </ul>
 *
 * <p><b>可靠通道</b>（协商/授权/心跳/输入事件/SessionClose）：
 * <ul>
 *   <li>发送端：seq 递增（{@link AtomicLong}），500ms 无 ACK 重传，累计 {@link #MAX_RETRANSMITS}
 *       次重传失败判定会话断开；</li>
 *   <li>接收端：按 seq 去重 + 乱序缓冲（窗口 {@link #REORDER_WINDOW}）依序投递，保证协商
 *       消息有序；每收到可靠消息回 ACK（累积确认，值为已连续投递的最大 seq）；</li>
 *   <li>明文/密文切换：激活前发 PLAIN、激活后发 RELIABLE；切换点在业务层由
 *       "挑战 Ack → 激活 → 第二轮" 协议时序保证两端一致，对端激活前的重传明文包按
 *       seq 去重丢弃。</li>
 * </ul>
 *
 * <p><b>视频帧</b>（best-effort，不重传）：密文 ≤ {@link #FRAGMENT_PAYLOAD_LIMIT} 整帧发送；
 * 超长帧按密文切块分片（每块 ≤1100B，FRAGMENT 头带 frameSeq/total/index），接收端按
 * frameSeq 重组，{@link #FRAGMENT_ASSEMBLY_TIMEOUT_MS} 未收齐整帧丢弃（视频容忍丢帧）；
 * 帧丢失迹象（分片丢弃 / VideoFrame frame_index 跳变 / 乱序旧帧）自动回发
 * KeyFrameRequest（可靠通道，500ms 节流）。
 *
 * <p><b>心跳</b>：与 TCP 端点一致（ESTABLISHED 后每 2s 探活、5s 无任何消息断线、RTT/2
 * 采样），心跳消息走可靠通道。
 *
 * <p><b>线程模型</b>：单接收虚拟线程（单 socket 单线程解析，天然免锁）+ 共享调度线程
 * （重传/分片超时/心跳 tick）；发送可从任意线程调用（{@link DatagramSocket} 并发安全）。
 */
public final class UdpSessionEndpoint implements SessionTransport {

    private static final Logger LOG = LoggerFactory.getLogger(UdpSessionEndpoint.class);

    /** 包类型：密文 best-effort（视频整帧） */
    public static final byte TYPE_DATA = SessionPacketCodec.FLAG_BEST_EFFORT;
    /** 包类型：密文可靠消息 */
    public static final byte TYPE_RELIABLE = SessionPacketCodec.FLAG_RELIABLE;
    /** 包类型：明文可靠消息（协商阶段） */
    public static final byte TYPE_PLAIN = 2;
    /** 包类型：累积确认 */
    public static final byte TYPE_ACK = 3;
    /** 包类型：密文分片（视频大帧） */
    public static final byte TYPE_FRAGMENT = 4;

    /** 单包载荷上限（密文字节）：分片块大小，控制消息超出即拒绝（MVP 控制消息均远小于此） */
    public static final int FRAGMENT_PAYLOAD_LIMIT = 1100;
    /** 单帧最大分片数（1100×58 ≈ 63KB，超过直接丢帧） */
    public static final int MAX_FRAGMENTS_PER_FRAME = 58;
    /** 重传间隔（毫秒）：输入/控制等可靠消息丢包后最迟在此间隔重发 */
    public static final long RETRANSMIT_INTERVAL_MS = 200;
    /** 重传失败判定断开的次数上限 */
    public static final int MAX_RETRANSMITS = 3;
    /** 乱序缓冲窗口（可靠消息 seq 距期望值超过此距离即丢弃，靠对端重传恢复） */
    public static final int REORDER_WINDOW = 32;
    /** 分片重组超时（毫秒）：超时未收齐丢弃该帧并请求关键帧 */
    public static final long FRAGMENT_ASSEMBLY_TIMEOUT_MS = 2_000;
    /** KeyFrameRequest 发送节流（毫秒） */
    public static final long KEYFRAME_REQUEST_MIN_INTERVAL_MS = 500;

    /** 会话内心跳间隔（毫秒），与 TCP 端点一致 */
    public static final long DEFAULT_HEARTBEAT_INTERVAL_MS = 2_000;
    /** 心跳超时（毫秒） */
    public static final long DEFAULT_HEARTBEAT_TIMEOUT_MS = 5_000;

    private static final int RELIABLE_HEADER = 9;   // type 1 + seq 8
    private static final int FRAGMENT_HEADER = 7;   // type 1 + frameSeq 4 + total 1 + index 1
    private static final int ACK_PACKET_SIZE = 9;   // type 1 + ack 8
    private static final int RECEIVE_BUFFER_SIZE = 65_535;
    private static final long TICK_INTERVAL_MS = 200;

    /** 共享调度线程（守护线程，全部端点复用：重传/分片超时/心跳） */
    private static final ScheduledExecutorService SCHEDULER =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "gudesk-udp-session-tick");
                t.setDaemon(true);
                return t;
            });

    private final DatagramSocket socket;
    private final InetSocketAddress peer;
    /** 会话回调（非 final：支持"先建会话对象、start 时再接线"的二阶段构造，见 {@link #start(SessionEventListener, Consumer)}） */
    private volatile SessionEventListener listener;
    private volatile Consumer<SessionMessage> messageHandler;
    private final long heartbeatIntervalMs;
    private final long heartbeatTimeoutMs;

    private volatile SessionCipher cipher;
    private final AtomicLong sendSeq = new AtomicLong();
    private final AtomicLong frameSeqCounter = new AtomicLong();
    private final ConcurrentHashMap<Long, PendingSend> pendingSends = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, FragmentSet> fragmentSets = new ConcurrentHashMap<>();

    // 接收线程单线程访问（无需加锁）
    private final HashMap<Long, SessionMessage> reorderBuffer = new HashMap<>();
    private long lastDeliveredSeq;
    private long lastVideoFrameIndex = -1;
    /** 上次累积确认值（快速重传：重复 ACK 表明下一 seq 可能丢失） */
    private long lastAckSeq;
    /** 连续重复 ACK 计数 */
    private int dupAckCount;

    private volatile long lastPeerActivityMs = System.currentTimeMillis();
    private volatile long lastKeyframeRequestMs;
    private volatile SessionState state = SessionState.NEGOTIATING;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean heartbeatStarted = new AtomicBoolean();
    private volatile ScheduledFuture<?> tickFuture;
    private volatile ScheduledFuture<?> heartbeatFuture;
    private volatile Thread receiveThread;

    /**
     * 默认心跳参数构造。
     *
     * @param listener       会话事件监听器（可为 null）
     * @param messageHandler 非心跳/关闭/视频消息的处理器（会话状态机逻辑，可为 null）
     * @param socket         打洞成功的 UDP socket（本端点接管其收发）
     * @param peer           对端地址（打洞时探测到的真实地址）
     */
    public UdpSessionEndpoint(SessionEventListener listener, Consumer<SessionMessage> messageHandler,
                              DatagramSocket socket, InetSocketAddress peer) {
        this(listener, messageHandler, socket, peer,
                DEFAULT_HEARTBEAT_INTERVAL_MS, DEFAULT_HEARTBEAT_TIMEOUT_MS);
    }

    /** 自定义心跳参数构造（测试用短周期） */
    public UdpSessionEndpoint(SessionEventListener listener, Consumer<SessionMessage> messageHandler,
                              DatagramSocket socket, InetSocketAddress peer,
                              long heartbeatIntervalMs, long heartbeatTimeoutMs) {
        this.listener = listener;
        this.messageHandler = messageHandler;
        this.socket = Objects.requireNonNull(socket, "socket");
        this.peer = Objects.requireNonNull(peer, "peer");
        this.heartbeatIntervalMs = heartbeatIntervalMs;
        this.heartbeatTimeoutMs = heartbeatTimeoutMs;
    }

    /** 对端地址（打洞探测结果） */
    public InetSocketAddress peerAddress() {
        return peer;
    }

    /** 启动接收线程与调度任务（幂等） */
    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        try {
            // 清除打洞/STUN 阶段残留的 soTimeout（阻塞接收，超时仅由调度线程判定）
            socket.setSoTimeout(0);
        } catch (IOException ignored) {
            // socket 已关闭时接收线程立即退出
        }
        receiveThread = Thread.ofVirtual().name("gudesk-udp-session-recv").start(this::receiveLoop);
        tickFuture = SCHEDULER.scheduleAtFixedRate(this::tick,
                TICK_INTERVAL_MS, TICK_INTERVAL_MS, TimeUnit.MILLISECONDS);
        LOG.debug("UDP 会话端点已启动: {} <-> {}", socket.getLocalSocketAddress(), peer);
    }

    /**
     * 二阶段接线：先以空回调构造端点、创建会话对象，start 时再注入会话回调，
     * 随后启动接收线程——回调在接收循环开始前生效，打洞后立即到达的首条
     * 协商消息（500ms 后由可靠通道重传）不会因未接线而丢失。
     */
    public void start(SessionEventListener sessionListener, Consumer<SessionMessage> sessionHandler) {
        this.listener = sessionListener;
        this.messageHandler = sessionHandler;
        start();
    }

    // ------------------------------------------------------------------
    // SessionTransport
    // ------------------------------------------------------------------

    @Override
    public boolean send(SessionMessage message, boolean reliable) {
        if (closed.get()) {
            return false;
        }
        try {
            if (reliable) {
                return sendReliable(message);
            }
            sendBestEffort(message);
            return true;
        } catch (GeneralSecurityException e) {
            LOG.warn("UDP 会话消息加密失败: {}", String.valueOf(e));
            return false;
        }
    }

    @Override
    public void activateCipher(SessionCipher sessionCipher) {
        this.cipher = Objects.requireNonNull(sessionCipher, "sessionCipher");
        LOG.debug("UDP 会话通道切换为密文");
    }

    @Override
    public void setState(SessionState newState) {
        state = Objects.requireNonNull(newState, "newState");
        if (newState == SessionState.ESTABLISHED && heartbeatStarted.compareAndSet(false, true)) {
            lastPeerActivityMs = System.currentTimeMillis();
            heartbeatFuture = SCHEDULER.scheduleAtFixedRate(this::heartbeatTick,
                    heartbeatIntervalMs, heartbeatIntervalMs, TimeUnit.MILLISECONDS);
        }
        SessionEventListener l = listener;
        if (l != null) {
            l.onStateChange(newState);
        }
    }

    @Override
    public boolean isWritable() {
        return !closed.get() && !socket.isClosed();
    }

    @Override
    public String remoteDescription() {
        return String.valueOf(peer);
    }

    @Override
    public boolean isClosed() {
        return closed.get();
    }

    @Override
    public void close(String reason) {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        ScheduledFuture<?> tick = tickFuture;
        if (tick != null) {
            tick.cancel(false);
        }
        ScheduledFuture<?> hb = heartbeatFuture;
        if (hb != null) {
            hb.cancel(false);
        }
        state = SessionState.CLOSED;
        socket.close(); // 触发接收线程退出
        SessionEventListener l = listener;
        if (l != null) {
            try {
                l.onStateChange(SessionState.CLOSED);
            } catch (Throwable t) {
                LOG.warn("onStateChange 回调异常", t);
            }
            try {
                l.onClose(reason == null || reason.isBlank() ? "未知原因" : reason);
            } catch (Throwable t) {
                LOG.warn("onClose 回调异常", t);
            }
        }
        LOG.debug("UDP 会话端点已关闭: {}（原因: {}）", peer, reason);
    }

    // ------------------------------------------------------------------
    // 发送
    // ------------------------------------------------------------------

    /** 可靠消息：seq + 密文（或协商阶段明文），登记待确认 */
    private boolean sendReliable(SessionMessage message) throws GeneralSecurityException {
        byte[] payload = encodePayload(message);
        if (payload.length > FRAGMENT_PAYLOAD_LIMIT) {
            LOG.warn("可靠消息超过单包上限（{} > {} 字节），拒绝发送: {}",
                    payload.length, FRAGMENT_PAYLOAD_LIMIT, message.getPayloadCase());
            return false;
        }
        long seq = sendSeq.incrementAndGet();
        byte type = cipher != null ? TYPE_RELIABLE : TYPE_PLAIN;
        byte[] wire = new byte[RELIABLE_HEADER + payload.length];
        wire[0] = type;
        putLong(wire, 1, seq);
        System.arraycopy(payload, 0, wire, RELIABLE_HEADER, payload.length);
        rawSend(wire);
        pendingSends.put(seq, new PendingSend(wire));
        return true;
    }

    /** best-effort 视频帧：小帧整包，大帧分片（分片在密文层切块，收齐后整体解密） */
    private void sendBestEffort(SessionMessage message) throws GeneralSecurityException {
        SessionCipher c = cipher;
        if (c == null) {
            LOG.warn("best-effort 消息仅允许在密文激活后发送，丢弃: {}", message.getPayloadCase());
            return;
        }
        byte[] ciphertext = c.encrypt(message);
        if (ciphertext.length <= FRAGMENT_PAYLOAD_LIMIT) {
            byte[] wire = new byte[1 + ciphertext.length];
            wire[0] = TYPE_DATA;
            System.arraycopy(ciphertext, 0, wire, 1, ciphertext.length);
            rawSend(wire);
            return;
        }
        int total = (ciphertext.length + FRAGMENT_PAYLOAD_LIMIT - 1) / FRAGMENT_PAYLOAD_LIMIT;
        if (total > MAX_FRAGMENTS_PER_FRAME) {
            LOG.warn("视频帧密文 {} 字节超过分片上限（{} 块），丢弃帧", ciphertext.length, total);
            return;
        }
        int frameSeq = (int) frameSeqCounter.incrementAndGet();
        for (int index = 0; index < total; index++) {
            int from = index * FRAGMENT_PAYLOAD_LIMIT;
            int to = Math.min(from + FRAGMENT_PAYLOAD_LIMIT, ciphertext.length);
            byte[] wire = new byte[FRAGMENT_HEADER + (to - from)];
            wire[0] = TYPE_FRAGMENT;
            putInt(wire, 1, frameSeq);
            wire[5] = (byte) total;
            wire[6] = (byte) index;
            System.arraycopy(ciphertext, from, wire, FRAGMENT_HEADER, to - from);
            rawSend(wire);
        }
    }

    private byte[] encodePayload(SessionMessage message) throws GeneralSecurityException {
        SessionCipher c = cipher;
        return c != null ? c.encrypt(message) : message.toByteArray();
    }

    private void rawSend(byte[] wire) {
        try {
            socket.send(new DatagramPacket(wire, wire.length, peer));
        } catch (IOException e) {
            LOG.debug("UDP 包发送失败: {}", e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // 接收（单线程）
    // ------------------------------------------------------------------

    private void receiveLoop() {
        byte[] buf = new byte[RECEIVE_BUFFER_SIZE];
        while (!closed.get()) {
            DatagramPacket packet = new DatagramPacket(buf, RECEIVE_BUFFER_SIZE);
            try {
                socket.receive(packet);
            } catch (IOException e) {
                if (!closed.get()) {
                    LOG.debug("UDP 接收退出: {}", e.getMessage());
                }
                break;
            }
            if (packet.getLength() <= 0) {
                continue;
            }
            if (!peer.equals(packet.getSocketAddress())) {
                continue; // 陌生来源忽略
            }
            lastPeerActivityMs = System.currentTimeMillis();
            try {
                handlePacket(buf, packet.getLength());
            } catch (Throwable t) {
                LOG.warn("UDP 包处理异常: {}", String.valueOf(t));
            }
        }
    }

    private void handlePacket(byte[] buf, int len) {
        switch (buf[0]) {
            case TYPE_ACK -> {
                if (len >= ACK_PACKET_SIZE) {
                    onAck(getLong(buf, 1));
                }
            }
            case TYPE_PLAIN -> handleReliable(buf, len, true);
            case TYPE_RELIABLE -> handleReliable(buf, len, false);
            case TYPE_DATA -> handleVideoCiphertext(slice(buf, 1, len));
            case TYPE_FRAGMENT -> handleFragment(buf, len);
            default -> {
                // 打洞 PUNCH 残留或未知类型，忽略
            }
        }
    }

    /** 累积确认：移除 seq ≤ ack 的待确认消息；重复 ACK 触发快速重传 */
    private void onAck(long ack) {
        if (ack <= 0) {
            return;
        }
        pendingSends.keySet().removeIf(seq -> seq <= ack);
        if (ack == lastAckSeq && ack < sendSeq.get()) {
            // 重复 ACK：ack+1 的可靠消息可能丢失，连续 2 次重复后立即重传（不等定时器）
            if (++dupAckCount >= 2) {
                dupAckCount = 0;
                retransmitNow(ack + 1);
            }
        } else if (ack > lastAckSeq) {
            lastAckSeq = ack;
            dupAckCount = 0;
        }
    }

    /** 立即重传指定 seq（快速重传：对端重复 ACK 表明该 seq 丢失） */
    private void retransmitNow(long seq) {
        PendingSend pending = pendingSends.get(seq);
        if (pending == null) {
            return;
        }
        if (pending.retries >= MAX_RETRANSMITS) {
            LOG.warn("可靠消息 seq={} 重传 {} 次仍无确认，判定会话断开", seq, pending.retries);
            close("可靠消息重传超时：对端无响应");
            return;
        }
        pending.retries++;
        pending.lastSendMs = System.currentTimeMillis();
        rawSend(pending.wire);
        LOG.debug("可靠消息 seq={} 快速重传（第 {} 次）", seq, pending.retries);
    }

    /** 可靠消息：去重 + 乱序缓冲 + 依序投递 + 回 ACK */
    private void handleReliable(byte[] buf, int len, boolean plain) {
        if (len < RELIABLE_HEADER) {
            return;
        }
        long seq = getLong(buf, 1);
        if (seq <= lastDeliveredSeq) {
            sendAck(); // 重复（含对端激活前的明文重传），丢弃并回执
            return;
        }
        if (seq > lastDeliveredSeq + REORDER_WINDOW) {
            sendAck(); // 窗口外丢弃，靠对端重传恢复
            return;
        }
        SessionMessage message = decodeReliable(buf, len, plain);
        if (message == null) {
            return;
        }
        reorderBuffer.put(seq, message);
        // 依序投递：期望 seq = lastDelivered + 1
        SessionMessage next = reorderBuffer.remove(lastDeliveredSeq + 1);
        while (next != null) {
            lastDeliveredSeq++;
            dispatch(next);
            next = reorderBuffer.remove(lastDeliveredSeq + 1);
        }
        sendAck();
    }

    private SessionMessage decodeReliable(byte[] buf, int len, boolean plain) {
        byte[] payload = slice(buf, RELIABLE_HEADER, len);
        if (plain) {
            try {
                return SessionMessage.parseFrom(payload);
            } catch (Exception e) {
                LOG.debug("明文会话消息解析失败，丢弃");
                return null;
            }
        }
        SessionCipher c = cipher;
        if (c == null) {
            return null; // 未激活即收到密文（不应发生），丢弃
        }
        try {
            return c.decrypt(payload);
        } catch (GeneralSecurityException e) {
            LOG.debug("可靠消息解密失败，丢弃（等对端重传）: {}", e.getMessage());
            return null;
        }
    }

    private void sendAck() {
        byte[] wire = new byte[ACK_PACKET_SIZE];
        wire[0] = TYPE_ACK;
        putLong(wire, 1, lastDeliveredSeq);
        rawSend(wire);
    }

    /** 视频整帧密文：解密 → 帧序检测 → 投递 */
    private void handleVideoCiphertext(byte[] ciphertext) {
        SessionCipher c = cipher;
        if (c == null) {
            return;
        }
        try {
            dispatchVideo(c.decrypt(ciphertext));
        } catch (GeneralSecurityException e) {
            LOG.debug("视频帧解密失败，丢弃: {}", e.getMessage());
        }
    }

    /** 分片：按 frameSeq 收集，收齐拼接解密；超时清理由 tick 负责 */
    private void handleFragment(byte[] buf, int len) {
        if (len < FRAGMENT_HEADER) {
            return;
        }
        int frameSeq = getInt(buf, 1);
        int total = buf[5] & 0xFF;
        int index = buf[6] & 0xFF;
        if (total <= 0 || total > MAX_FRAGMENTS_PER_FRAME || index >= total) {
            return;
        }
        FragmentSet set = fragmentSets.computeIfAbsent(frameSeq,
                k -> new FragmentSet(total, System.currentTimeMillis()));
        if (!set.add(index, slice(buf, FRAGMENT_HEADER, len))) {
            return;
        }
        if (set.isComplete()) {
            fragmentSets.remove(frameSeq);
            handleVideoCiphertext(set.assemble());
        }
    }

    /** 视频帧投递：乱序旧帧丢弃、frame_index 跳变（丢帧迹象）触发关键帧请求 */
    private void dispatchVideo(SessionMessage message) {
        if (message.getPayloadCase() != SessionMessage.PayloadCase.VIDEO_FRAME) {
            dispatch(message); // 非视频帧（不应走 best-effort），按普通消息分发
            return;
        }
        VideoFrame frame = message.getVideoFrame();
        long index = frame.getFrameIndex();
        if (lastVideoFrameIndex >= 0) {
            if (index <= lastVideoFrameIndex) {
                return; // 乱序旧帧丢弃
            }
            if (index > lastVideoFrameIndex + 1) {
                requestKeyframeThrottled(); // 帧序跳变 = 中间帧丢失
            }
        }
        lastVideoFrameIndex = index;
        SessionEventListener l = listener;
        if (l != null) {
            l.onVideoFrame(frame);
        }
    }

    /** 请求关键帧（500ms 节流，可靠通道） */
    private void requestKeyframeThrottled() {
        long now = System.currentTimeMillis();
        synchronized (this) {
            if (now - lastKeyframeRequestMs < KEYFRAME_REQUEST_MIN_INTERVAL_MS) {
                return;
            }
            lastKeyframeRequestMs = now;
        }
        LOG.debug("检测到视频丢帧迹象，请求关键帧");
        send(SessionMessage.newBuilder().setKeyframeRequest(KeyFrameRequest.newBuilder()).build(), true);
    }

    /** 消息分发（与 TcpSessionEndpoint.EndpointHandler 一致） */
    private void dispatch(SessionMessage msg) {
        switch (msg.getPayloadCase()) {
            case SESSION_HEARTBEAT -> onHeartbeat(msg.getSessionHeartbeat());
            case SESSION_CLOSE -> {
                String reason = msg.getSessionClose().getReason();
                close(reason == null || reason.isBlank() ? "对端主动断开" : reason);
            }
            case VIDEO_FRAME -> dispatchVideo(msg);
            default -> {
                Consumer<SessionMessage> handler = messageHandler;
                if (handler != null) {
                    handler.accept(msg);
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 调度 tick（重传 / 分片超时）与心跳
    // ------------------------------------------------------------------

    private void tick() {
        try {
            if (closed.get()) {
                return;
            }
            long now = System.currentTimeMillis();
            retransmitScan(now);
            fragmentSweep(now);
        } catch (Throwable t) {
            LOG.warn("UDP 会话 tick 异常", t);
        }
    }

    private void retransmitScan(long now) {
        for (Map.Entry<Long, PendingSend> entry : pendingSends.entrySet()) {
            PendingSend p = entry.getValue();
            if (now - p.lastSendMs < RETRANSMIT_INTERVAL_MS) {
                continue;
            }
            if (p.retries >= MAX_RETRANSMITS) {
                LOG.warn("可靠消息 seq={} 重传 {} 次仍无确认，判定会话断开", entry.getKey(), p.retries);
                close("可靠消息重传超时：对端无响应");
                return;
            }
            p.retries++;
            p.lastSendMs = now;
            rawSend(p.wire);
            LOG.debug("可靠消息 seq={} 第 {} 次重传", entry.getKey(), p.retries);
        }
    }

    private void fragmentSweep(long now) {
        Iterator<Map.Entry<Integer, FragmentSet>> it = fragmentSets.entrySet().iterator();
        while (it.hasNext()) {
            FragmentSet set = it.next().getValue();
            if (now - set.createdMs > FRAGMENT_ASSEMBLY_TIMEOUT_MS) {
                it.remove();
                LOG.debug("视频帧分片重组超时（{}/{} 块），丢弃并请求关键帧", set.received(), set.total);
                requestKeyframeThrottled();
            }
        }
    }

    private void heartbeatTick() {
        try {
            long now = System.currentTimeMillis();
            if (now - lastPeerActivityMs > heartbeatTimeoutMs) {
                close("心跳超时：对端超过 " + heartbeatTimeoutMs + " ms 无任何消息");
                return;
            }
            send(SessionMessage.newBuilder()
                    .setSessionHeartbeat(SessionHeartbeat.newBuilder()
                            .setTimestamp(System.currentTimeMillis()))
                    .build(), true);
        } catch (Throwable t) {
            LOG.warn("心跳任务异常", t);
        }
    }

    /** 与 TCP 端点一致的心跳回显（timestamp>0 探活 / <0 回显算 RTT） */
    private void onHeartbeat(SessionHeartbeat heartbeat) {
        if (!heartbeatStarted.get()) {
            return;
        }
        long ts = heartbeat.getTimestamp();
        if (ts <= 0) {
            long oneWayMs = Math.max(0, (System.currentTimeMillis() + ts) / 2);
            SessionEventListener l = listener;
            if (l != null) {
                l.onLatencySample(oneWayMs);
            }
        } else {
            send(SessionMessage.newBuilder()
                    .setSessionHeartbeat(SessionHeartbeat.newBuilder().setTimestamp(-ts))
                    .build(), true);
        }
    }

    // ------------------------------------------------------------------
    // 内部结构
    // ------------------------------------------------------------------

    /** 待确认的可靠消息（保留完整线包，重传直接重发字节） */
    private static final class PendingSend {
        final byte[] wire;
        volatile long lastSendMs;
        volatile int retries;

        PendingSend(byte[] wire) {
            this.wire = wire;
            this.lastSendMs = System.currentTimeMillis();
        }
    }

    /** 分片重组集（接收线程 add，调度线程超时清理，CHM 保证可见性） */
    private static final class FragmentSet {
        final int total;
        final byte[][] chunks;
        final long createdMs;
        private int receivedCount;

        FragmentSet(int total, long createdMs) {
            this.total = total;
            this.chunks = new byte[total][];
            this.createdMs = createdMs;
        }

        /** 收集分片；重复分片返回 false */
        synchronized boolean add(int index, byte[] chunk) {
            if (index < 0 || index >= total || chunks[index] != null) {
                return false;
            }
            chunks[index] = chunk;
            receivedCount++;
            return true;
        }

        synchronized boolean isComplete() {
            return receivedCount == total;
        }

        synchronized int received() {
            return receivedCount;
        }

        synchronized byte[] assemble() {
            int length = 0;
            for (byte[] chunk : chunks) {
                length += chunk.length;
            }
            byte[] assembled = new byte[length];
            int offset = 0;
            for (byte[] chunk : chunks) {
                System.arraycopy(chunk, 0, assembled, offset, chunk.length);
                offset += chunk.length;
            }
            return assembled;
        }
    }

    // ------------------------------------------------------------------
    // 字节序工具
    // ------------------------------------------------------------------

    private static void putLong(byte[] buf, int off, long value) {
        for (int i = 0; i < 8; i++) {
            buf[off + i] = (byte) (value >>> (8 * (7 - i)));
        }
    }

    private static long getLong(byte[] buf, int off) {
        long value = 0;
        for (int i = 0; i < 8; i++) {
            value = (value << 8) | (buf[off + i] & 0xFF);
        }
        return value;
    }

    private static void putInt(byte[] buf, int off, int value) {
        buf[off] = (byte) (value >>> 24);
        buf[off + 1] = (byte) (value >>> 16);
        buf[off + 2] = (byte) (value >>> 8);
        buf[off + 3] = (byte) value;
    }

    private static int getInt(byte[] buf, int off) {
        return ((buf[off] & 0xFF) << 24) | ((buf[off + 1] & 0xFF) << 16)
                | ((buf[off + 2] & 0xFF) << 8) | (buf[off + 3] & 0xFF);
    }

    private static byte[] slice(byte[] buf, int from, int len) {
        byte[] out = new byte[len - from];
        System.arraycopy(buf, from, out, 0, out.length);
        return out;
    }
}
