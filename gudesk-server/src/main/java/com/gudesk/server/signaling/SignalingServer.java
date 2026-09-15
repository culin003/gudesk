package com.gudesk.server.signaling;

import com.gudesk.common.proto.GuDeskProto.ConnectAccept;
import com.gudesk.common.proto.GuDeskProto.ConnectForward;
import com.gudesk.common.proto.GuDeskProto.ConnectReject;
import com.gudesk.common.proto.GuDeskProto.ConnectRequest;
import com.gudesk.common.proto.GuDeskProto.HeartbeatAck;
import com.gudesk.common.proto.GuDeskProto.RegisterRequest;
import com.gudesk.common.proto.GuDeskProto.RegisterResponse;
import com.gudesk.common.proto.GuDeskProto.SignalingEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * GuDesk 信令服务器：被控端/主控端 ID 注册、信令心跳、在线表管理与连接请求路由。
 *
 * <p>线程模型（spec 硬约束）：阻塞 IO + 虚拟线程 thread-per-connection，不使用 Netty。
 * accept 循环与每个连接的处理、后台清扫均为虚拟线程；空闲连接本身不占平台线程。
 *
 * <p>主控端连接模型：主控端连接后同样发送 {@code RegisterRequest{id 为空}} 注册获得临时 ID，
 * 与被控端复用同一注册机制；区别仅在于主控端会后续发送 {@code ConnectRequest}。
 *
 * <p>连接请求路由：主控端 {@code ConnectRequest{target_id}} →
 * 目标不在线回 {@code ConnectReject{reason="对方不在线"}}（本机即时，满足 5s 要求）；
 * 在线则向被控端写 {@code ConnectForward}，被控端回 {@code ConnectAccept}（服务器在转发前
 * 生成 UUID relay_token 注入）或 {@code ConnectReject}，均转发回主控端；被控端
 * {@code connectTimeoutMs} 内未响应则主控端收 {@code ConnectReject{reason="被控端无响应"}}。
 */
public final class SignalingServer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SignalingServer.class);

    public static final int DEFAULT_PORT = 48900;
    /** 信令心跳超时：超时判定离线（spec：30s） */
    public static final long DEFAULT_HEARTBEAT_TIMEOUT_MS = 30_000;
    /** 在线表后台清扫周期（spec：5s） */
    public static final long DEFAULT_SWEEP_INTERVAL_MS = 5_000;
    /** 被控端响应 ConnectForward 的超时（任务：10s） */
    public static final long DEFAULT_CONNECT_TIMEOUT_MS = 10_000;

    private final int port;
    private final long heartbeatTimeoutMs;
    private final long sweepIntervalMs;
    private final long connectTimeoutMs;

    private final ConcurrentHashMap<String, Connection> online = new ConcurrentHashMap<>();

    private ServerSocketChannel serverChannel;
    private volatile boolean running;
    private Thread acceptThread;
    private Thread sweeperThread;

    public SignalingServer(int port) {
        this(port, DEFAULT_HEARTBEAT_TIMEOUT_MS, DEFAULT_SWEEP_INTERVAL_MS, DEFAULT_CONNECT_TIMEOUT_MS);
    }

    /**
     * 全参构造（测试可注入缩短的心跳超时与清扫周期）。
     *
     * @param port              监听端口，0 表示系统分配
     * @param heartbeatTimeoutMs 心跳超时判定离线的毫秒数
     * @param sweepIntervalMs    在线表后台清扫周期毫秒数
     * @param connectTimeoutMs   被控端响应连接请求的超时毫秒数
     */
    public SignalingServer(int port, long heartbeatTimeoutMs, long sweepIntervalMs, long connectTimeoutMs) {
        this.port = port;
        this.heartbeatTimeoutMs = heartbeatTimeoutMs;
        this.sweepIntervalMs = sweepIntervalMs;
        this.connectTimeoutMs = connectTimeoutMs;
    }

    /** 启动 accept 循环与后台清扫（均为虚拟线程）。 */
    public synchronized void start() throws IOException {
        if (running) {
            return;
        }
        serverChannel = ServerSocketChannel.open();
        serverChannel.bind(new InetSocketAddress(port));
        running = true;
        acceptThread = Thread.ofVirtual().name("signaling-accept").start(this::acceptLoop);
        sweeperThread = Thread.ofVirtual().name("signaling-sweeper").start(this::sweepLoop);
        log.info("信令服务器已启动: TCP {}:{}", serverChannel.socket().getInetAddress().getHostAddress(), getPort());
    }

    /** 实际监听端口（port=0 时为系统分配的端口）。 */
    public int getPort() {
        return serverChannel.socket().getLocalPort();
    }

    /** 当前在线（已注册）连接数，测试用。 */
    public int onlineCount() {
        return online.size();
    }

    /** 指定 ID 是否在线，测试用。 */
    public boolean isOnline(String id) {
        return online.containsKey(id);
    }

    @Override
    public synchronized void close() {
        if (!running) {
            return;
        }
        running = false;
        try {
            serverChannel.close();
        } catch (IOException ignored) {
            // 关闭失败忽略
        }
        for (Connection conn : online.values()) {
            closeConnection(conn);
        }
        log.info("信令服务器已停止");
    }

    private void acceptLoop() {
        while (running) {
            try {
                SocketChannel socket = serverChannel.accept();
                Thread.ofVirtual().name("signaling-conn").start(() -> handleConnection(socket));
            } catch (IOException e) {
                if (running) {
                    log.warn("accept 失败: {}", e.getMessage());
                }
                break;
            }
        }
    }

    private void handleConnection(SocketChannel socket) {
        Connection conn = new Connection(socket);
        try {
            socket.socket().setTcpNoDelay(true);
            conn.lastHeartbeat = System.currentTimeMillis();
            InputStream in = socket.socket().getInputStream();
            while (running) {
                SignalingEnvelope envelope = BlockingFrame.readEnvelope(in);
                if (envelope == null) {
                    break;
                }
                dispatch(conn, envelope);
            }
        } catch (IOException e) {
            log.debug("连接 {} 断开: {}", conn.id, e.getMessage());
        } catch (Exception e) {
            log.warn("连接 {} 处理异常: {}", conn.id, e.toString());
        } finally {
            closeConnection(conn);
        }
    }

    private void dispatch(Connection conn, SignalingEnvelope envelope) {
        switch (envelope.getPayloadCase()) {
            case REGISTER_REQUEST -> handleRegister(conn, envelope.getRegisterRequest());
            case HEARTBEAT -> {
                conn.lastHeartbeat = System.currentTimeMillis();
                write(conn, SignalingEnvelope.newBuilder()
                        .setHeartbeatAck(HeartbeatAck.newBuilder().setTimestamp(System.currentTimeMillis()))
                        .build());
            }
            case CONNECT_REQUEST -> handleConnectRequest(conn, envelope.getConnectRequest());
            case CONNECT_ACCEPT -> handleConnectAccept(conn, envelope.getConnectAccept());
            case CONNECT_REJECT -> handleConnectReject(conn, envelope.getConnectReject());
            default -> log.debug("忽略未知信令: {}", envelope.getPayloadCase());
        }
    }

    // ------------------------------------------------------------------
    // 注册：服务器始终分配新 ID（9 位随机数字），覆盖连接旧注册
    // ------------------------------------------------------------------
    private void handleRegister(Connection conn, RegisterRequest request) {
        if (conn.id != null) {
            online.remove(conn.id, conn);
        }
        String newId = allocateId(conn);
        conn.id = newId;
        conn.publicKey = request.getPublicKey().toByteArray();
        log.info("连接注册: id={}, publicKey={} 字节, remote={}",
                newId, conn.publicKey.length, conn.remoteAddress());
        write(conn, SignalingEnvelope.newBuilder()
                .setRegisterResponse(RegisterResponse.newBuilder()
                        .setOk(true)
                        .setAssignedId(newId))
                .build());
    }

    /** 生成 9 位随机数字 ID，putIfAbsent 原子占位防止并发撞号。 */
    private String allocateId(Connection conn) {
        while (true) {
            String candidate = String.valueOf(100_000_000 + ThreadLocalRandom.current().nextLong(900_000_000));
            if (online.putIfAbsent(candidate, conn) == null) {
                return candidate;
            }
        }
    }

    // ------------------------------------------------------------------
    // 连接请求路由（主控端 -> 被控端）
    // ------------------------------------------------------------------
    private void handleConnectRequest(Connection viewer, ConnectRequest request) {
        if (viewer.id == null) {
            reject(viewer, "主控端未注册");
            return;
        }
        Connection host = online.get(request.getTargetId());
        if (host == null) {
            reject(viewer, "对方不在线");
            return;
        }
        PendingConnect pending = new PendingConnect(viewer);
        synchronized (host) {
            if (host.pending != null && !host.pending.done.get()) {
                reject(viewer, "被控中");
                return;
            }
            host.pending = pending;
        }
        log.info("连接请求: viewer={} -> host={}", viewer.id, request.getTargetId());
        ConnectForward forward = ConnectForward.newBuilder()
                .setFromId(viewer.id)
                .setViewerPublicKey(request.getViewerPublicKey())
                .addAllCandidates(request.getCandidatesList())
                .build();
        try {
            write(host, SignalingEnvelope.newBuilder().setConnectForward(forward).build());
        } catch (Exception e) {
            if (host.pending == pending) {
                host.pending = null;
            }
            reject(viewer, "对方不在线");
            return;
        }
        // 看门狗：被控端超时未响应 → 主控端收"被控端无响应"。
        // done 的 CAS 保证与被控端响应至多一方生效；清空 pending 前校验仍是本请求，避免误清新请求。
        Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(connectTimeoutMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (pending.done.compareAndSet(false, true)) {
                if (host.pending == pending) {
                    host.pending = null;
                }
                reject(viewer, "被控端无响应");
            }
        });
    }

    // ------------------------------------------------------------------
    // 被控端响应转发（ConnectAccept / ConnectReject -> 主控端）
    // ------------------------------------------------------------------
    private void handleConnectAccept(Connection host, ConnectAccept accept) {
        PendingConnect pending = host.pending;
        if (pending == null || !pending.done.compareAndSet(false, true)) {
            log.debug("被控端 {} 的 ConnectAccept 无对应等待中的主控端，丢弃", host.id);
            return;
        }
        if (host.pending == pending) {
            host.pending = null;
        }
        // relay_token 注入（UUID）：被控端已预生成 token 时不覆盖（被控端需预知 token
        // 以便打洞失败时提前连接中继等待配对），旧版/未携带时由服务器生成
        ConnectAccept.Builder builder = ConnectAccept.newBuilder(accept);
        if (builder.getRelayToken().isEmpty()) {
            builder.setRelayToken(UUID.randomUUID().toString());
        }
        ConnectAccept injected = builder.build();
        log.info("被控端 {} 接受连接，已注入 relay_token 转发给主控端 {}", host.id, pending.viewer.id);
        write(pending.viewer, SignalingEnvelope.newBuilder().setConnectAccept(injected).build());
    }

    private void handleConnectReject(Connection host, ConnectReject reject) {
        PendingConnect pending = host.pending;
        if (pending == null || !pending.done.compareAndSet(false, true)) {
            log.debug("被控端 {} 的 ConnectReject 无对应等待中的主控端，丢弃", host.id);
            return;
        }
        if (host.pending == pending) {
            host.pending = null;
        }
        log.info("被控端 {} 拒绝连接，转发拒绝给主控端 {}", host.id, pending.viewer.id);
        write(pending.viewer, SignalingEnvelope.newBuilder().setConnectReject(reject).build());
    }

    private void reject(Connection conn, String reason) {
        write(conn, SignalingEnvelope.newBuilder()
                .setConnectReject(ConnectReject.newBuilder().setReason(reason))
                .build());
    }

    // ------------------------------------------------------------------
    // 连接写出（跨线程写同一连接需持锁）与关闭清理
    // ------------------------------------------------------------------
    private void write(Connection conn, SignalingEnvelope envelope) {
        synchronized (conn.writeLock) {
            try {
                BlockingFrame.writeEnvelope(conn.out, envelope);
                conn.out.flush();
            } catch (IOException e) {
                log.debug("写连接 {} 失败: {}", conn.id, e.getMessage());
                closeConnection(conn);
            }
        }
    }

    private void closeConnection(Connection conn) {
        if (conn.id != null) {
            online.remove(conn.id, conn);
        }
        // 本连接作为被控端断开时，立即拒绝等待中的主控端（而非等看门狗超时）
        PendingConnect pending = conn.pending;
        if (pending != null && pending.done.compareAndSet(false, true)) {
            reject(pending.viewer, "对方不在线");
        }
        conn.pending = null;
        try {
            if (conn.socket != null && conn.socket.isOpen()) {
                conn.socket.close();
            }
        } catch (IOException ignored) {
            // 关闭失败忽略
        }
    }

    // ------------------------------------------------------------------
    // 后台清扫：心跳超时判定离线
    // ------------------------------------------------------------------
    private void sweepLoop() {
        while (running) {
            try {
                Thread.sleep(sweepIntervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            long now = System.currentTimeMillis();
            for (Connection conn : online.values()) {
                if (now - conn.lastHeartbeat > heartbeatTimeoutMs) {
                    log.info("连接 {} 心跳超时（{}ms 无心跳），判定离线", conn.id, heartbeatTimeoutMs);
                    closeConnection(conn);
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 内部结构
    // ------------------------------------------------------------------

    /** 在线连接：socket + 输出流 + 心跳时间 + 公钥 + 待响应连接请求。 */
    static final class Connection {
        final SocketChannel socket;
        final OutputStream out;
        final Object writeLock = new Object();
        volatile String id;
        volatile byte[] publicKey;
        volatile long lastHeartbeat;
        /** 本连接作为被控端时，等待其响应的主控端请求 */
        volatile PendingConnect pending;

        Connection(SocketChannel socket) {
            this.socket = socket;
            OutputStream out = null;
            try {
                out = socket != null ? socket.socket().getOutputStream() : null;
            } catch (IOException e) {
                // socket 已关闭，后续写失败会触发清理
            }
            this.out = out;
        }

        String remoteAddress() {
            try {
                return socket != null ? socket.getRemoteAddress().toString() : "unknown";
            } catch (IOException e) {
                return "unknown";
            }
        }
    }

    /** 一次连接请求的等待状态：done 原子标记防止看门狗与被控端响应双重回复。 */
    static final class PendingConnect {
        final Connection viewer;
        final AtomicBoolean done = new AtomicBoolean(false);

        PendingConnect(Connection viewer) {
            this.viewer = viewer;
        }
    }
}
