package com.gudesk.host.session;

import com.gudesk.common.net.SignalingClient;
import com.gudesk.common.net.TransportCandidates;
import com.gudesk.common.net.UdpHolePuncher;
import com.gudesk.common.proto.GuDeskProto.ConnectAccept;
import com.gudesk.common.proto.GuDeskProto.ConnectForward;
import com.gudesk.common.proto.GuDeskProto.ConnectReject;
import com.gudesk.common.proto.GuDeskProto.PunchCandidate;
import com.google.protobuf.ByteString;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntSupplier;

import static com.gudesk.host.session.HostSessionManager.REASON_BUSY;

/**
 * 被控端信令服务：注册信令服务器获得被控 ID，接收主控端连接请求（ConnectForward）
 * 并编排 P2P 接入路径：
 * <ol>
 *   <li>回 ConnectAccept（自身候选：内网 TCP/UDP 地址 + STUN 公网 UDP 映射，
 *       携带预生成的 relay_token，服务器不覆盖）；</li>
 *   <li>启动 UDP 打洞（复用打洞 socket，收到 PUNCH 即成功，交
 *       {@link HostSessionManager#tryCreateUdp} 建会话）；</li>
 *   <li>中继兜底：打洞窗口（{@link #PUNCH_TIMEOUT_MS} + 1s）结束后仍无会话，
 *       预连接中继服务器等待主控端配对接入。</li>
 * </ol>
 *
 * <p>{@code --no-udp} 测试模式：跳过打洞，收到连接请求后立即预连接中继
 * （配合主控端 {@code --prefer-relay} 验证中继回落路径）。
 *
 * <p>打洞 socket 生命周期：绑定（+ STUN 查询）→ 打洞 → 成功被会话接管（置空待重绑）/
 * 失败关闭重绑（防对端陈旧 PUNCH 残留）。
 */
public final class HostSignalingService implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(HostSignalingService.class);

    /** 服务器默认端口（与 gudesk-server 三件套一致） */
    public static final int DEFAULT_SIGNALING_PORT = 48900;
    public static final int DEFAULT_STUN_PORT = 3478;
    public static final int DEFAULT_RELAY_PORT = 48910;
    /** UDP 打洞总超时（毫秒） */
    public static final long PUNCH_TIMEOUT_MS = 5_000;
    /** 中继兜底延迟：打洞超时后再等 1s（覆盖主控端 TCP 直连窗口）仍无会话才连中继 */
    public static final long RELAY_FALLBACK_DELAY_MS = PUNCH_TIMEOUT_MS + 1_000;

    private final HostSessionManager sessionManager;
    private final IntSupplier tcpSessionPort;
    private final InetSocketAddress signalingAddress;
    private final InetSocketAddress stunAddress;
    private final InetSocketAddress relayAddress;
    private final boolean noUdp;
    /** 中继预连接用的 Netty IO 线程组（守护线程） */
    private final EventLoopGroup relayGroup = new NioEventLoopGroup(1, r -> {
        Thread t = new Thread(r, "gudesk-host-relay-io");
        t.setDaemon(true);
        return t;
    });
    /** 打洞互斥：同一时刻仅一个打洞流程使用打洞 socket */
    private final AtomicBoolean punchBusy = new AtomicBoolean();

    private volatile SignalingClient signaling;
    private volatile DatagramSocket punchSocket;
    private volatile InetSocketAddress stunMapped;
    private volatile String assignedId;
    private volatile boolean stopped;

    /**
     * @param sessionManager  共享会话管理器（TCP/UDP/中继三路径单会话槽）
     * @param tcpSessionPort  TCP 会话监听端口（候选收集用，端口 0 时取实际绑定端口）
     * @param signalingServer 信令服务器地址（STUN/中继地址按同主机默认端口推导）
     * @param noUdp           true=跳过 UDP 打洞，直接预连接中继（测试中继回落）
     */
    public HostSignalingService(HostSessionManager sessionManager, IntSupplier tcpSessionPort,
                                InetSocketAddress signalingServer, boolean noUdp) {
        this.sessionManager = sessionManager;
        this.tcpSessionPort = tcpSessionPort;
        this.signalingAddress = signalingServer;
        String host = signalingServer.getHostString();
        this.stunAddress = new InetSocketAddress(host, DEFAULT_STUN_PORT);
        this.relayAddress = new InetSocketAddress(host, DEFAULT_RELAY_PORT);
        this.noUdp = noUdp;
    }

    /** 启动：连接信令服务器、注册被控 ID；非 noUdp 模式预绑定打洞 socket 并 STUN 查询 */
    public void start() throws IOException {
        signaling = new SignalingClient(signalingAddress, new SignalingClient.Listener() {
            @Override
            public void onConnectForward(ConnectForward forward) {
                handleConnectForward(forward);
            }
        });
        assignedId = signaling.register(new byte[0]);
        if (!noUdp) {
            ensurePunchSocket();
        }
        LOG.info("信令注册成功: 被控 ID = {}（信令 {}，STUN {}，中继 {}）",
                assignedId, signalingAddress, stunAddress, relayAddress);
    }

    /** 服务器分配的被控 ID（未注册时为 null） */
    public String assignedId() {
        return assignedId;
    }

    // ------------------------------------------------------------------
    // 连接请求编排
    // ------------------------------------------------------------------

    private void handleConnectForward(ConnectForward forward) {
        if (stopped) {
            return;
        }
        if (sessionManager.hasActiveSession()) {
            LOG.info("已有进行中的会话，拒绝连接请求（来源 {}）", forward.getFromId());
            replyReject(REASON_BUSY);
            return;
        }
        if (!noUdp && !punchBusy.compareAndSet(false, true)) {
            LOG.info("上一次打洞仍在进行，拒绝连接请求（来源 {}）", forward.getFromId());
            replyReject(REASON_BUSY);
            return;
        }
        String relayToken = UUID.randomUUID().toString();
        try {
            signaling.sendConnectAccept(buildAccept(relayToken));
        } catch (IOException e) {
            LOG.warn("ConnectAccept 发送失败: {}", String.valueOf(e));
            punchBusy.set(false);
            return;
        }
        LOG.info("已接受连接请求（来源 {}，relay_token={}..），候选已交换",
                forward.getFromId(), relayToken.substring(0, 8));
        if (noUdp) {
            // 测试模式：跳过打洞与 TCP 直连竞争，直接预连接中继等待主控端
            sessionManager.connectRelay(relayGroup, relayAddress, relayToken);
            return;
        }
        startPunch(forward, relayToken);
    }

    /** 组装 ConnectAccept：内网 TCP/UDP 候选 + STUN 公网 UDP 候选 + 预生成中继令牌 */
    private ConnectAccept buildAccept(String relayToken) {
        List<PunchCandidate> candidates = new ArrayList<>();
        candidates.addAll(TransportCandidates.encodeAllCandidates(TransportCandidates.SCHEME_TCP,
                TransportCandidates.localAddresses(tcpSessionPort.getAsInt())));
        ensurePunchSocket();
        DatagramSocket socket = punchSocket;
        if (socket != null && !socket.isClosed()) {
            candidates.addAll(TransportCandidates.encodeAllCandidates(TransportCandidates.SCHEME_UDP,
                    TransportCandidates.localAddresses(socket.getLocalPort())));
            InetSocketAddress mapped = stunMapped;
            if (mapped != null) {
                candidates.add(PunchCandidate.newBuilder()
                        .setAddress(TransportCandidates.encode(TransportCandidates.SCHEME_UDP, mapped))
                        .build());
            }
        }
        return ConnectAccept.newBuilder()
                .setHostPublicKey(ByteString.EMPTY) // MVP：会话密钥经临时 ECDH 交换，此处占位
                .addAllCandidates(candidates)
                .setRelayToken(relayToken)
                .build();
    }

    private void startPunch(ConnectForward forward, String relayToken) {
        DatagramSocket socket = punchSocket;
        if (socket == null || socket.isClosed()) {
            punchBusy.set(false);
            replyReject("被控端 UDP 端口不可用");
            return;
        }
        List<InetSocketAddress> viewerUdp = TransportCandidates.decodeAllCandidates(
                forward.getCandidatesList(), TransportCandidates.SCHEME_UDP);
        CompletableFuture<UdpHolePuncher.PunchOutcome> punch =
                UdpHolePuncher.punch(socket, viewerUdp, PUNCH_TIMEOUT_MS);
        punch.whenComplete((outcome, error) -> {
            punchBusy.set(false);
            if (stopped) {
                return;
            }
            if (error != null) {
                LOG.info("UDP 打洞未成功（{}），等待 TCP 直连/中继回落", String.valueOf(error));
                closePunchSocket(); // 防陈旧 PUNCH 残留，下次连接重绑
                return;
            }
            DatagramSocket sock = punchSocket;
            if (sock == null || sock.isClosed()) {
                return;
            }
            HostSession session = sessionManager.tryCreateUdp(sock, outcome.peerAddress());
            if (session == null) {
                LOG.info("UDP 会话未建立（会话槽被占用），关闭打洞 socket");
                sock.close();
            } else {
                punchSocket = null; // socket 已被会话接管，下次连接重绑
                stunMapped = null;
                LOG.info("UDP 打洞成功，被控会话已就绪: {}", outcome.peerAddress());
            }
        });
        // 中继兜底：打洞/TCP 直连窗口结束后仍无已协商会话 → 预连接中继等待主控端配对
        // （空闲预建会话——如打洞成功但主控端走了中继——可被中继路径抢占，见
        // HostSessionManager#tryCreateTcp 的空闲抢占逻辑）
        Thread.ofVirtual().name("gudesk-host-relay-fallback").start(() -> {
            try {
                Thread.sleep(RELAY_FALLBACK_DELAY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (stopped || noUdp) {
                return;
            }
            if (!sessionManager.hasNegotiatedSession()) {
                LOG.info("UDP 打洞与 TCP 直连均未协商（{} ms），预连接中继等待主控端",
                        RELAY_FALLBACK_DELAY_MS);
                sessionManager.connectRelay(relayGroup, relayAddress, relayToken);
            }
        });
    }

    // ------------------------------------------------------------------
    // 打洞 socket 管理
    // ------------------------------------------------------------------

    /** 确保打洞 socket 可用（已关闭/未绑定则重绑并重新 STUN 查询，阻塞至多 2s） */
    private void ensurePunchSocket() {
        DatagramSocket socket = punchSocket;
        if (socket != null && !socket.isClosed()) {
            return;
        }
        try {
            socket = new DatagramSocket(); // 全网卡临时端口
            punchSocket = socket;
            stunMapped = TransportCandidates.stunLookup(socket, stunAddress);
            LOG.info("UDP 打洞端口已绑定: {}（STUN 公网映射: {}）",
                    socket.getLocalPort(), stunMapped == null ? "不可达" : stunMapped);
        } catch (IOException e) {
            LOG.warn("UDP 打洞 socket 绑定失败（仅 TCP 直连/中继可用）: {}", e.getMessage());
            punchSocket = null;
        }
    }

    private void closePunchSocket() {
        DatagramSocket socket = punchSocket;
        punchSocket = null;
        if (socket != null) {
            socket.close();
        }
    }

    private void replyReject(String reason) {
        try {
            SignalingClient s = signaling;
            if (s != null) {
                s.sendConnectReject(ConnectReject.newBuilder().setReason(reason).build());
            }
        } catch (IOException e) {
            LOG.warn("ConnectReject 发送失败: {}", e.getMessage());
        }
    }

    @Override
    public void close() {
        stopped = true;
        SignalingClient s = signaling;
        if (s != null) {
            s.close();
        }
        closePunchSocket();
        relayGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS);
        LOG.info("被控端信令服务已停止");
    }
}
