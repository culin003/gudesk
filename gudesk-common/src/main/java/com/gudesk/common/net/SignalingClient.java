package com.gudesk.common.net;

import com.gudesk.common.proto.GuDeskProto.ConnectAccept;
import com.gudesk.common.proto.GuDeskProto.ConnectForward;
import com.gudesk.common.proto.GuDeskProto.ConnectReject;
import com.gudesk.common.proto.GuDeskProto.ConnectRequest;
import com.gudesk.common.proto.GuDeskProto.Heartbeat;
import com.gudesk.common.proto.GuDeskProto.HeartbeatAck;
import com.gudesk.common.proto.GuDeskProto.RegisterRequest;
import com.gudesk.common.proto.GuDeskProto.RegisterResponse;
import com.gudesk.common.proto.GuDeskProto.SignalingEnvelope;
import com.google.protobuf.ByteString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 阻塞 IO 信令客户端（host/viewer 共用）：连接信令服务器（TCP），注册 ID、
 * 维持心跳（10s 间隔，服务器 30s 超时）、收发连接协商信令。
 *
 * <p>线帧格式与服务器 {@code SignalingFrameCodec}/{@code BlockingFrame} 一致：
 * 4 字节大端长度前缀 + Protobuf {@link SignalingEnvelope}。
 *
 * <p>线程模型：读循环与心跳为虚拟线程；跨线程写持有写锁（服务器侧同型实现）。
 * 注册为同步调用（等 RegisterResponse），其余信令经 {@link Listener} 异步回调。
 */
public final class SignalingClient implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(SignalingClient.class);

    /** 信令心跳间隔（毫秒）：服务器超时 30s，10s 留足余量 */
    public static final long HEARTBEAT_INTERVAL_MS = 10_000;
    /** 注册响应等待超时（毫秒） */
    public static final long REGISTER_TIMEOUT_MS = 5_000;

    /** 信令事件回调（读线程/心跳线程上调用，实现方保证线程安全） */
    public interface Listener {

        /** 收到被控端转发来的连接请求（被控端视角） */
        default void onConnectForward(ConnectForward forward) {
        }

        /** 收到连接接受（主控端视角，服务器已注入 relay_token） */
        default void onConnectAccept(ConnectAccept accept) {
        }

        /** 收到连接拒绝（主控端视角） */
        default void onConnectReject(ConnectReject reject) {
        }

        /** 信令连接断开 */
        default void onDisconnected(String reason) {
        }
    }

    private static final int MAX_FRAME_LENGTH = 1024 * 1024;

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    private final Listener listener;
    private final Object writeLock = new Object();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final CompletableFuture<RegisterResponse> registerFuture = new CompletableFuture<>();
    /** 最近一次注册响应（register() 成功返回后可见，供调用方读取通告的 STUN/中继端口） */
    private volatile RegisterResponse lastRegisterResponse;

    public SignalingClient(InetSocketAddress serverAddress, Listener listener) throws IOException {
        this.listener = Objects.requireNonNull(listener, "listener");
        this.socket = new Socket();
        try {
            socket.connect(serverAddress, 5_000);
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(0);
            this.in = socket.getInputStream();
            this.out = socket.getOutputStream();
        } catch (IOException e) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // 关闭失败忽略
            }
            throw e;
        }
        Thread.ofVirtual().name("gudesk-signaling-read").start(this::readLoop);
        Thread.ofVirtual().name("gudesk-signaling-heartbeat").start(this::heartbeatLoop);
    }

    /**
     * 注册（阻塞至服务器响应）：服务器始终分配新 ID（被控端永久 ID / 主控端临时 ID）。
     *
     * @param publicKey 注册公钥（被控端可为空；主控端填身份公钥，仅作展示占位）
     * @return 服务器分配的 ID
     */
    public String register(byte[] publicKey) throws IOException {
        write(SignalingEnvelope.newBuilder()
                .setRegisterRequest(RegisterRequest.newBuilder()
                        .setPublicKey(ByteString.copyFrom(publicKey == null ? new byte[0] : publicKey)))
                .build());
        RegisterResponse response;
        try {
            response = registerFuture.get(REGISTER_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new IOException("注册响应超时");
        } catch (Exception e) {
            throw new IOException("注册失败: " + e.getMessage());
        }
        if (!response.getOk()) {
            throw new IOException("注册被拒绝: " + response.getMessage());
        }
        return response.getAssignedId();
    }

    /** 最近一次注册响应（未注册过则为 null），含服务器通告的 STUN/中继端口。 */
    public RegisterResponse registerResponse() {
        return lastRegisterResponse;
    }

    /** 主控端发送连接请求（目标被控端 ID + 主控端候选） */
    public void sendConnectRequest(ConnectRequest request) throws IOException {
        write(SignalingEnvelope.newBuilder().setConnectRequest(request).build());
    }

    /** 被控端回连接接受（自身候选 + 预生成的 relay_token） */
    public void sendConnectAccept(ConnectAccept accept) throws IOException {
        write(SignalingEnvelope.newBuilder().setConnectAccept(accept).build());
    }

    /** 被控端回连接拒绝 */
    public void sendConnectReject(ConnectReject reject) throws IOException {
        write(SignalingEnvelope.newBuilder().setConnectReject(reject).build());
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            socket.close();
        } catch (IOException ignored) {
            // 关闭失败忽略
        }
        registerFuture.completeExceptionally(new IOException("信令客户端已关闭"));
        listener.onDisconnected("本地关闭");
    }

    // ------------------------------------------------------------------
    // 内部：读循环 / 心跳 / 帧读写
    // ------------------------------------------------------------------

    private void readLoop() {
        try {
            while (!closed.get()) {
                SignalingEnvelope envelope = readEnvelope(in);
                if (envelope == null) {
                    break;
                }
                dispatch(envelope);
            }
        } catch (IOException e) {
            // 连接断开，退出
        }
        if (closed.compareAndSet(false, true)) {
            listener.onDisconnected("连接断开");
        }
        registerFuture.completeExceptionally(new IOException("信令连接断开"));
    }

    private void dispatch(SignalingEnvelope envelope) {
        switch (envelope.getPayloadCase()) {
            case REGISTER_RESPONSE -> {
                lastRegisterResponse = envelope.getRegisterResponse();
                registerFuture.complete(envelope.getRegisterResponse());
            }
            case HEARTBEAT_ACK -> {
                // 心跳回执，无需处理
            }
            case CONNECT_FORWARD -> listener.onConnectForward(envelope.getConnectForward());
            case CONNECT_ACCEPT -> listener.onConnectAccept(envelope.getConnectAccept());
            case CONNECT_REJECT -> listener.onConnectReject(envelope.getConnectReject());
            default -> LOG.debug("忽略信令: {}", envelope.getPayloadCase());
        }
    }

    private void heartbeatLoop() {
        while (!closed.get()) {
            try {
                Thread.sleep(HEARTBEAT_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (closed.get()) {
                return;
            }
            try {
                write(SignalingEnvelope.newBuilder()
                        .setHeartbeat(Heartbeat.newBuilder().setTimestamp(System.currentTimeMillis()))
                        .build());
            } catch (IOException e) {
                return; // 写失败由读循环触发断开回调
            }
        }
    }

    private void write(SignalingEnvelope envelope) throws IOException {
        synchronized (writeLock) {
            try {
                byte[] body = envelope.toByteArray();
                out.write((body.length >>> 24) & 0xFF);
                out.write((body.length >>> 16) & 0xFF);
                out.write((body.length >>> 8) & 0xFF);
                out.write(body.length & 0xFF);
                out.write(body);
                out.flush();
            } catch (IOException e) {
                if (!closed.get()) {
                    LOG.debug("信令写出失败: {}", e.getMessage());
                }
                throw e;
            }
        }
    }

    /** 与服务器 BlockingFrame 兼容的帧读取；对端干净关闭返回 null */
    private static SignalingEnvelope readEnvelope(InputStream in) throws IOException {
        int b1 = in.read();
        if (b1 < 0) {
            return null;
        }
        int b2 = in.read();
        int b3 = in.read();
        int b4 = in.read();
        if (b2 < 0 || b3 < 0 || b4 < 0) {
            throw new EOFException("连接在长度前缀中间关闭");
        }
        int length = (b1 << 24) | (b2 << 16) | (b3 << 8) | b4;
        if (length <= 0 || length > MAX_FRAME_LENGTH) {
            throw new IOException("帧长度非法: " + length);
        }
        byte[] body = new byte[length];
        int offset = 0;
        while (offset < body.length) {
            int n = in.read(body, offset, body.length - offset);
            if (n < 0) {
                throw new EOFException("连接在帧体中间关闭");
            }
            offset += n;
        }
        return SignalingEnvelope.parseFrom(body);
    }
}
