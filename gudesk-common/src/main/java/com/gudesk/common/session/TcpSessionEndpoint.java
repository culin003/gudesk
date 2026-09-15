package com.gudesk.common.session;

import com.gudesk.common.codec.SessionFrameCodec;
import com.gudesk.common.crypto.SessionCipher;
import com.gudesk.common.proto.GuDeskProto.SessionHeartbeat;
import com.gudesk.common.proto.GuDeskProto.SessionMessage;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.MessageToByteEncoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * TCP 直连会话端点：主控端/被控端共用的传输骨架（Netty），封装线帧编解码、
 * 明文→密文管线切换、会话内心跳与消息分发。
 *
 * <p><b>线帧格式</b>：与 {@link SessionFrameCodec} 一致（int32 长度 + 1 字节标志 + 负载），
 * 区别在于协商阶段（密钥建立前）负载为明文 protobuf，密钥建立后为 AES-GCM 密文——
 * 通过 {@link #activateCipher} 在事件循环线程上原子替换 pipeline 编解码器实现切换。
 *
 * <p><b>心跳协议</b>：进入 ESTABLISHED 后每 {@link #DEFAULT_HEARTBEAT_INTERVAL_MS} 发送
 * SessionHeartbeat{timestamp}：
 * <ul>
 *   <li>timestamp &gt; 0：发送方本地时钟的探活心跳（epoch 毫秒）；</li>
 *   <li>timestamp &lt;= 0：回显（值为对端心跳时间戳取负）——对端据此以同钟差计算
 *       RTT，单向延迟 = RTT / 2，经 {@link SessionEventListener#onLatencySample} 上报；</li>
 * </ul>
 * 连续 {@link #DEFAULT_HEARTBEAT_TIMEOUT_MS} 未收到对端任何消息（心跳/视频/输入均计入活跃）
 * 判定断线：触发 {@link SessionEventListener#onClose} 并关闭通道。
 *
 * <p><b>消息分发</b>：心跳/关闭消息内部处理；VideoFrame 路由到
 * {@link SessionEventListener#onVideoFrame}；其余（协商、输入事件、关键帧请求等）
 * 路由到构造时传入的 messageHandler（会话状态机逻辑）。
 */
public final class TcpSessionEndpoint implements SessionTransport {

    private static final Logger LOG = LoggerFactory.getLogger(TcpSessionEndpoint.class);

    /** 会话内心跳间隔（毫秒） */
    public static final long DEFAULT_HEARTBEAT_INTERVAL_MS = 2_000;
    /** 心跳超时（毫秒）：超过该时长未收到对端任何消息判定断线 */
    public static final long DEFAULT_HEARTBEAT_TIMEOUT_MS = 5_000;

    private static final String DECODER_PLAIN = "gudesk-plain-decoder";
    private static final String ENCODER_PLAIN = "gudesk-plain-encoder";
    private static final String DECODER_SECURE = "gudesk-frame-decoder";
    private static final String ENCODER_SECURE = "gudesk-frame-encoder";
    private static final String SESSION_HANDLER = "gudesk-session-handler";

    /** 共享心跳调度线程（守护线程，全部端点复用） */
    private static final ScheduledExecutorService HEARTBEAT_SCHEDULER =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "gudesk-session-heartbeat");
                t.setDaemon(true);
                return t;
            });

    /** 会话回调（非 final：支持"先建会话对象、attach 时再接线"的二阶段构造，见 {@link #attach(SocketChannel, SessionEventListener, Consumer)}） */
    private volatile SessionEventListener listener;
    private volatile Consumer<SessionMessage> messageHandler;
    private final long heartbeatIntervalMs;
    private final long heartbeatTimeoutMs;

    private volatile SocketChannel channel;
    private volatile SessionState state = SessionState.NEGOTIATING;
    private volatile long lastPeerActivityMs = System.currentTimeMillis();
    private final AtomicBoolean heartbeatStarted = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile ScheduledFuture<?> heartbeatTask;

    /**
     * 默认心跳参数构造。
     *
     * @param listener        会话事件监听器（可为 null：仅丢弃事件）
     * @param messageHandler  非心跳/关闭/视频消息的处理器（会话状态机逻辑，可为 null）
     */
    public TcpSessionEndpoint(SessionEventListener listener, Consumer<SessionMessage> messageHandler) {
        this(listener, messageHandler, DEFAULT_HEARTBEAT_INTERVAL_MS, DEFAULT_HEARTBEAT_TIMEOUT_MS);
    }

    /**
     * 自定义心跳参数构造（测试用短周期）。
     */
    public TcpSessionEndpoint(SessionEventListener listener, Consumer<SessionMessage> messageHandler,
                               long heartbeatIntervalMs, long heartbeatTimeoutMs) {
        this.listener = listener;
        this.messageHandler = messageHandler;
        this.heartbeatIntervalMs = heartbeatIntervalMs;
        this.heartbeatTimeoutMs = heartbeatTimeoutMs;
    }

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    /**
     * 绑定已连接通道，安装明文（协商阶段）编解码器与会话处理器。
     */
    public void attach(SocketChannel channel) {
        this.channel = Objects.requireNonNull(channel, "channel");
        ChannelPipeline pipeline = channel.pipeline();
        pipeline.addLast(DECODER_PLAIN, new PlainDecoder());
        pipeline.addLast(ENCODER_PLAIN, new PlainEncoder());
        pipeline.addLast(SESSION_HANDLER, new EndpointHandler());
    }

    /**
     * 二阶段接线：先以空回调构造端点、创建会话对象，attach 时再注入会话回调，
     * 随后安装 pipeline——回调在管道安装前生效，通道已缓冲的首条协商消息不会丢失
     * （被控端三种接入路径：TCP accept / 中继预连接 / UDP 打洞 共用）。
     */
    public void attach(SocketChannel channel, SessionEventListener sessionListener,
                       Consumer<SessionMessage> sessionHandler) {
        this.listener = sessionListener;
        this.messageHandler = sessionHandler;
        attach(channel);
    }

    /**
     * 协商完成后激活密文编解码：在通道事件循环上原子替换明文编解码器，保证
     * 入站字节流的处理顺序（当前消息处理完毕后才切换，不与后续密文交错）。
     */
    public void activateCipher(SessionCipher sessionCipher) {
        SocketChannel ch = requireChannel();
        Runnable swap = () -> {
            ChannelPipeline pipeline = ch.pipeline();
            if (pipeline.get(ENCODER_PLAIN) != null) {
                pipeline.replace(DECODER_PLAIN, DECODER_SECURE, new SessionFrameCodec.Decoder(sessionCipher));
                pipeline.replace(ENCODER_PLAIN, ENCODER_SECURE, new SessionFrameCodec.Encoder(sessionCipher));
                LOG.debug("会话通道切换为密文编解码");
            }
        };
        if (ch.eventLoop().inEventLoop()) {
            swap.run();
        } else {
            ch.eventLoop().execute(swap);
        }
    }

    /**
     * 发送会话消息：协商完成前走明文管线（仅限协商消息），之后走 AES-GCM 密文管线。
     * 通道已关闭/未就绪时静默丢弃并返回 false。
     */
    public boolean send(SessionMessage message) {
        SocketChannel ch = channel;
        if (ch == null || closed.get() || !ch.isActive()) {
            return false;
        }
        ch.writeAndFlush(message).addListener(future -> {
            if (!future.isSuccess()) {
                LOG.debug("会话消息发送失败: {}", future.cause().getMessage());
            }
        });
        return true;
    }

    /**
     * {@link SessionTransport} 实现：TCP 由传输层保证可靠与有序，忽略 reliable 参数。
     */
    @Override
    public boolean send(SessionMessage message, boolean reliable) {
        return send(message);
    }

    /**
     * 更新会话状态；进入 ESTABLISHED 时启动心跳（幂等，仅一次）。
     */
    public void setState(SessionState newState) {
        state = Objects.requireNonNull(newState, "newState");
        if (newState == SessionState.ESTABLISHED && heartbeatStarted.compareAndSet(false, true)) {
            lastPeerActivityMs = System.currentTimeMillis();
            heartbeatTask = HEARTBEAT_SCHEDULER.scheduleAtFixedRate(this::heartbeatTick,
                    heartbeatIntervalMs, heartbeatIntervalMs, TimeUnit.MILLISECONDS);
        }
        SessionEventListener l = listener;
        if (l != null) {
            l.onStateChange(newState);
        }
    }

    /**
     * 关闭会话（幂等）：停止心跳、关闭通道、回调 onStateChange(CLOSED) 与 onClose(reason)。
     */
    public void close(String reason) {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        ScheduledFuture<?> task = heartbeatTask;
        if (task != null) {
            task.cancel(false);
        }
        state = SessionState.CLOSED;
        SocketChannel ch = channel;
        if (ch != null) {
            ch.close();
        }
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
    }

    // ------------------------------------------------------------------
    // 状态查询
    // ------------------------------------------------------------------

    public SessionState state() {
        return state;
    }

    public boolean isClosed() {
        return closed.get();
    }

    /** 通道远端地址描述（如 /127.0.0.1:52344；未连接时为 "?"） */
    public String remoteDescription() {
        SocketChannel ch = channel;
        return ch != null && ch.remoteAddress() != null
                ? String.valueOf(ch.remoteAddress()) : "?";
    }

    /** 出站缓冲是否可写（false 时应丢弃视频帧防内存积压） */
    public boolean isWritable() {
        SocketChannel ch = channel;
        return ch != null && ch.isActive() && ch.isWritable();
    }

    // ------------------------------------------------------------------
    // 内部：消息分发 / 心跳
    // ------------------------------------------------------------------

    private SocketChannel requireChannel() {
        SocketChannel ch = channel;
        if (ch == null) {
            throw new IllegalStateException("会话端点尚未 attach 通道");
        }
        return ch;
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
                    .build());
        } catch (Throwable t) {
            LOG.warn("心跳任务异常", t);
        }
    }

    private void onHeartbeat(SessionHeartbeat heartbeat) {
        // 仅已进入 ESTABLISHED（启动心跳）的端点参与心跳/回显；
        // 未建立的端点保持静默，避免为僵死连接续命
        if (!heartbeatStarted.get()) {
            return;
        }
        long ts = heartbeat.getTimestamp();
        if (ts <= 0) {
            // 回显（值为本端心跳时间戳取负）：同钟差计算 RTT，单向延迟 = RTT / 2
            long oneWayMs = Math.max(0, (System.currentTimeMillis() + ts) / 2);
            SessionEventListener l = listener;
            if (l != null) {
                l.onLatencySample(oneWayMs);
            }
        } else {
            // 对端探活心跳：立即回显（取负），供对端计算 RTT
            send(SessionMessage.newBuilder()
                    .setSessionHeartbeat(SessionHeartbeat.newBuilder().setTimestamp(-ts))
                    .build());
        }
    }

    /** 会话入站处理器：所有解码后的 SessionMessage 在 IO 事件循环线程进入此处 */
    private final class EndpointHandler extends SimpleChannelInboundHandler<SessionMessage> {

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, SessionMessage msg) {
            lastPeerActivityMs = System.currentTimeMillis();
            switch (msg.getPayloadCase()) {
                case SESSION_HEARTBEAT -> onHeartbeat(msg.getSessionHeartbeat());
                case SESSION_CLOSE -> {
                    String reason = msg.getSessionClose().getReason();
                    close(reason == null || reason.isBlank() ? "对端主动断开" : reason);
                }
                case VIDEO_FRAME -> {
                    if (listener != null) {
                        listener.onVideoFrame(msg.getVideoFrame());
                    }
                }
                default -> {
                    Consumer<SessionMessage> handler = messageHandler;
                    if (handler != null) {
                        handler.accept(msg);
                    }
                }
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            close("连接已断开");
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            LOG.warn("会话通道异常: {}", String.valueOf(cause));
            SessionEventListener l = listener;
            if (l != null) {
                try {
                    l.onSessionError("会话通道异常: " + cause);
                } catch (Throwable t) {
                    LOG.warn("onSessionError 回调异常", t);
                }
            }
            close("会话通道异常");
        }
    }

    // ------------------------------------------------------------------
    // 协商阶段明文编解码（线帧格式与密文阶段一致，负载为未加密 protobuf）
    // ------------------------------------------------------------------

    /**
     * 明文解码器：int32 长度 + 1 字节标志（忽略）+ protobuf 负载。
     * 供协商阶段（密钥建立前）使用；密钥建立后由 {@link SessionFrameCodec.Decoder} 替换。
     */
    public static final class PlainDecoder extends LengthFieldBasedFrameDecoder {

        public PlainDecoder() {
            super(SessionFrameCodec.MAX_FRAME_LENGTH, 0,
                    SessionFrameCodec.LENGTH_FIELD_LENGTH, 0, SessionFrameCodec.LENGTH_FIELD_LENGTH);
        }

        @Override
        protected Object decode(ChannelHandlerContext ctx, ByteBuf in) throws Exception {
            ByteBuf frame = (ByteBuf) super.decode(ctx, in);
            if (frame == null) {
                return null;
            }
            try {
                frame.readByte(); // 可靠性标志：读取后忽略（与密文阶段格式对齐）
                byte[] payload = new byte[frame.readableBytes()];
                frame.readBytes(payload);
                return SessionMessage.parseFrom(payload);
            } finally {
                frame.release();
            }
        }
    }

    /**
     * 明文编码器：SessionMessage → int32 长度 + 1 字节标志（恒 0）+ protobuf 负载。
     */
    public static final class PlainEncoder extends MessageToByteEncoder<SessionMessage> {

        @Override
        protected void encode(ChannelHandlerContext ctx, SessionMessage msg, ByteBuf out) {
            byte[] payload = msg.toByteArray();
            out.writeInt(SessionFrameCodec.FLAG_LENGTH + payload.length);
            out.writeByte(SessionFrameCodec.FLAG_TCP);
            out.writeBytes(payload);
        }
    }
}
