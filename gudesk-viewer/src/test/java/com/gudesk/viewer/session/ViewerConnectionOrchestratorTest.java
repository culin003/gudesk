package com.gudesk.viewer.session;

import com.gudesk.common.net.SignalingClient;
import com.gudesk.common.net.TransportCandidates;
import com.gudesk.common.net.UdpHolePuncher;
import com.gudesk.common.proto.GuDeskProto.ConnectAccept;
import com.gudesk.common.proto.GuDeskProto.ConnectForward;
import com.gudesk.common.proto.GuDeskProto.PunchCandidate;
import com.gudesk.common.proto.GuDeskProto.SessionMessage;
import com.gudesk.common.proto.GuDeskProto.SessionNegotiate;
import com.gudesk.common.proto.GuDeskProto.SessionNegotiateAck;
import com.gudesk.common.session.ConnectionGatekeeper;
import com.gudesk.common.session.SessionHandshake;
import com.gudesk.common.session.SessionTransport;
import com.gudesk.common.session.TcpSessionEndpoint;
import com.gudesk.host.session.AutoAuthorizer;
import com.gudesk.host.session.HostPasswordStore;
import com.gudesk.host.session.HostSession;
import com.gudesk.host.session.HostSessionManager;
import com.gudesk.host.session.TcpSessionServer;
import com.gudesk.host.session.TrustStore;
import com.gudesk.server.relay.RelayServer;
import com.gudesk.server.signaling.SignalingServer;
import com.gudesk.server.stun.StunServer;
import com.google.protobuf.ByteString;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ViewerConnectionOrchestrator} 回落决策集成测试：真实服务器三件套
 * （信令/STUN/中继，随机端口）+ 真实被控端会话组件（TcpSessionServer/
 * HostSessionManager），假被控端仅替换信令侧的候选编排（SignalingClient +
 * 测试内 onConnectForward 处理），覆盖：
 *
 * <ul>
 *   <li>路径 A：UDP 打洞成功 → 选择 UDP_DIRECT；</li>
 *   <li>路径 B：仅 TCP 候选（无 UDP）→ 走 TCP_DIRECT，且真实 TcpSessionServer
 *       上建立会话、断开后槽位释放；</li>
 *   <li>路径 C：无 UDP/TCP 候选 → 中继回落配对成功（数据经中继往返）；</li>
 *   <li>失败路径：目标不在线 → onFailed 明确原因；</li>
 *   <li>并发取消：TCP 先连上（打洞延迟回包）后 UDP 在优先窗口内胜出——被放弃的
 *       TCP 尝试被关闭，TcpSessionServer 单会话槽最终只持有 UDP 会话且可释放复用。</li>
 * </ul>
 *
 * <p>验证深度到"传输通道建立 + 模式判定 + 第一轮协商 CHALLENGE 回包"（双向数据
 * 可达即证明通道/配对有效）；不提交密码，避免触发授权与媒体管线（真屏捕获）。
 */
class ViewerConnectionOrchestratorTest {

    private static final String PASSWORD = "135790";
    /** 被控端第一轮挑战标记（协议约定，见 HostSession.REASON_CHALLENGE） */
    private static final String REASON_CHALLENGE = "CHALLENGE";
    /** 并发测试的打洞回包延迟：保证 TCP 探测先连上，再验证 UDP 优先抢占 */
    private static final long PUNCH_REPLY_DELAY_MS = 250;

    /** 测试共享 IO 线程组（假被控端中继预连接 / MiniViewer 直连） */
    private static final EventLoopGroup GROUP = new NioEventLoopGroup(2, r -> {
        Thread t = new Thread(r, "viewer-orch-test-io");
        t.setDaemon(true);
        return t;
    });

    @TempDir
    Path tempDir;

    // 每个测试自起的服务器（随机端口；避开 48900/3478/48910 等被遗留进程占用的默认端口）
    private SignalingServer signaling;
    private StunServer stun;
    private RelayServer relay;

    // 编排结果收集
    private final List<ViewerConnectionOrchestrator> orchestrators = new ArrayList<>();
    private final List<Result> results = new ArrayList<>();
    private final List<SignalingClient> hostClients = new ArrayList<>();
    private final List<TcpSessionServer> tcpServers = new ArrayList<>();
    /** 假被控端处理器内异常（信令读线程），用于失败时定位 */
    private final AtomicReference<Throwable> hostError = new AtomicReference<>();
    /** 被控端第一轮挑战回包到达（messageHandler 在 IO 线程调用） */
    private final CountDownLatch challengeLatch = new CountDownLatch(1);

    @AfterAll
    static void stopGroup() {
        GROUP.shutdownGracefully(0, 1, TimeUnit.SECONDS);
    }

    @AfterEach
    void tearDown() {
        // 顺序：先关交付的传输（其通道挂在编排器 ioGroup 上），再关编排器与其余资源
        for (Result r : results) {
            if (r.transport != null) {
                r.transport.close("测试结束");
            }
        }
        for (ViewerConnectionOrchestrator orchestrator : orchestrators) {
            orchestrator.close();
        }
        for (SignalingClient client : hostClients) {
            client.close();
        }
        for (TcpSessionServer server : tcpServers) {
            server.stop();
        }
        if (relay != null) {
            relay.close();
        }
        if (signaling != null) {
            signaling.close();
        }
        if (stun != null) {
            stun.close();
        }
    }

    // ------------------------------------------------------------------
    // 路径 A：UDP 打洞成功 → UDP_DIRECT
    // ------------------------------------------------------------------

    @Test
    @Timeout(30)
    void 路径A_UDP打洞成功选择UDP直连() throws Exception {
        startServers(false);
        HostSessionManager manager = newSessionManager();

        // 假被控端：绑定打洞 UDP socket；ConnectForward → 只回 UDP 候选
        DatagramSocket udp = bindUdp();
        String hostId = registerHost((client, forward) -> sendAccept(client,
                List.of(udpCandidate(udp.getLocalPort())), UUID.randomUUID().toString()));

        // 打洞应答线程：收到 PUNCH 立即回发 → 交真实 HostSessionManager 建 UDP 会话
        AtomicReference<HostSession> hostSession = new AtomicReference<>();
        Thread.ofVirtual().start(() -> {
            try {
                hostSession.set(servePunch(udp, manager, 0));
            } catch (Throwable t) {
                hostError.compareAndSet(null, t);
            }
        });

        Result result = connect(hostId);
        awaitDone(result);
        assertNull(result.failure, "UDP 打洞路径不应失败: " + result.failure);
        assertEquals(ViewerConnectionOrchestrator.ConnectMode.UDP_DIRECT, result.mode,
                "打洞成功应选择 UDP 直连模式");

        // 通道数据验证：第一轮协商经 UDP 往返（收到挑战 Ack 即双向可达）
        result.transport.send(round1());
        awaitChallenge();
        assertNotNull(hostSession.get(), "被控端 UDP 会话应已建立");
        assertTrue(manager.hasActiveSession(), "被控端应报告活跃会话");
        assertNull(hostError.get(), () -> "假被控端异常: " + hostError.get());

        hostSession.get().shutdown("测试结束");
    }

    // ------------------------------------------------------------------
    // 路径 B：无 UDP 候选 → TCP 直连（真实 TcpSessionServer）
    // ------------------------------------------------------------------

    @Test
    @Timeout(30)
    void 路径B_无UDP候选时走TCP直连() throws Exception {
        startServers(false);
        HostSessionManager manager = newSessionManager();
        TcpSessionServer tcpServer = startTcpServer(manager);

        // 假被控端只回 TCP 候选（指向真实 TcpSessionServer 的回环端口）
        String hostId = registerHost((client, forward) -> sendAccept(client,
                List.of(tcpCandidate(tcpServer.boundPort())), UUID.randomUUID().toString()));

        Result result = connect(hostId);
        awaitDone(result);
        assertNull(result.failure, "TCP 直连路径不应失败: " + result.failure);
        assertEquals(ViewerConnectionOrchestrator.ConnectMode.TCP_DIRECT, result.mode,
                "无 UDP 候选时应选择 TCP 直连模式");

        // 数据经真实 TcpSessionServer 上的 HostSession 往返
        result.transport.send(round1());
        awaitChallenge();
        assertTrue(tcpServer.hasActiveSession(), "真实被控端会话服务应持有活跃会话");

        // 主控端断开 → 被控端会话终止、单会话槽释放
        result.transport.close("测试结束");
        assertTrue(awaitCondition(5_000, () -> !tcpServer.hasActiveSession()),
                "主控端断开后被控端应释放会话槽");
    }

    // ------------------------------------------------------------------
    // 路径 C：无 UDP/TCP 候选 → 中继回落并配对成功
    // ------------------------------------------------------------------

    @Test
    @Timeout(30)
    void 路径C_无直连候选时回落中继并配对成功() throws Exception {
        startServers(true);
        HostSessionManager manager = newSessionManager();

        // 假被控端：ConnectAccept 不携带任何候选（禁用 UDP 与 TCP），仅 relay_token；
        // 随后与生产 HostSignalingService 相同方式预连接中继等待配对
        String relayToken = UUID.randomUUID().toString();
        AtomicReference<HostSession> relaySession = new AtomicReference<>();
        String hostId = registerHost((client, forward) -> {
            sendAccept(client, List.of(), relayToken);
            relaySession.set(manager.connectRelay(GROUP, relayAddress(), relayToken));
        });

        Result result = connect(hostId);
        awaitDone(result);
        assertNull(result.failure, "中继回落路径不应失败: " + result.failure);
        assertEquals(ViewerConnectionOrchestrator.ConnectMode.RELAY, result.mode,
                "无直连候选时应回落中继模式");

        // 配对成功验证：第一轮协商经中继往返（主控端→中继→被控端→中继→主控端）
        result.transport.send(round1());
        awaitChallenge();
        assertNotNull(relaySession.get(), "被控端中继预连接会话应已建立");
        assertTrue(manager.hasActiveSession(), "被控端应持有中继路径的活跃会话");
        assertNull(hostError.get(), () -> "假被控端异常: " + hostError.get());

        if (relaySession.get() != null) {
            relaySession.get().shutdown("测试结束");
        }
    }

    // ------------------------------------------------------------------
    // 失败路径：目标不在线
    // ------------------------------------------------------------------

    @Test
    @Timeout(30)
    void 失败路径_目标不在线时收到明确失败回调() throws Exception {
        startServers(false);

        Result result = connect("999999999");
        awaitDone(result);
        assertNull(result.transport, "失败路径不应交付传输");
        assertNull(result.mode, "失败路径不应有模式判定");
        assertNotNull(result.failure, "目标不在线应触发 onFailed");
        assertTrue(result.failure.contains("对方不在线"),
                "失败原因应包含\"对方不在线\": " + result.failure);
    }

    // ------------------------------------------------------------------
    // 并发取消：TCP 先连上，UDP 在优先窗口内胜出 → TCP 尝试被取消、无泄漏
    // ------------------------------------------------------------------

    @Test
    @Timeout(60)
    void 并发竞争_UDP优先窗口胜出后TCP尝试被取消() throws Exception {
        startServers(false);
        HostSessionManager manager = newSessionManager();
        TcpSessionServer tcpServer = startTcpServer(manager);

        // 假被控端同时回 UDP + TCP 候选（真实 TcpSessionServer 的回环端口）
        DatagramSocket udp = bindUdp();
        String hostId = registerHost((client, forward) -> sendAccept(client,
                List.of(udpCandidate(udp.getLocalPort()), tcpCandidate(tcpServer.boundPort())),
                UUID.randomUUID().toString()));

        // 打洞应答延迟 250ms：确保编排器的 TCP 探测先连上（真实被控端先建空闲会话），
        // 随后打洞在 UDP_PREFERENCE_GRACE_MS 窗口内成功，验证"UDP 优先抢占 + 放弃 TCP"
        AtomicReference<HostSession> hostSession = new AtomicReference<>();
        Thread.ofVirtual().start(() -> {
            try {
                hostSession.set(servePunch(udp, manager, PUNCH_REPLY_DELAY_MS));
            } catch (Throwable t) {
                hostError.compareAndSet(null, t);
            }
        });

        Result result = connect(hostId);
        awaitDone(result);
        assertNull(result.failure, "并发竞争路径不应失败: " + result.failure);
        assertEquals(ViewerConnectionOrchestrator.ConnectMode.UDP_DIRECT, result.mode,
                "TCP 已连上时打洞在优先窗口内成功，仍应选择 UDP 直连");

        // 获胜会话可用：第一轮协商经 UDP 往返（此时被放弃的 TCP 会话已被抢占/关闭）
        result.transport.send(round1());
        awaitChallenge();
        assertNotNull(hostSession.get());
        assertNull(hostError.get(), () -> "假被控端异常: " + hostError.get());

        // 单会话槽只持有 UDP 会话：新 TCP 连接被"被控中"拒绝（无多余残留会话占槽）
        assertTrue(tcpServer.hasActiveSession(), "被控端应恰好持有一个活跃会话");
        try (MiniViewer probe = new MiniViewer(tcpServer.boundPort())) {
            SessionNegotiateAck ack = probe.awaitAck(5);
            assertFalse(ack.getAuthorized(), "会话占用期间新 TCP 连接应被拒绝");
            assertEquals(TcpSessionServer.REASON_BUSY, ack.getReason(),
                    "拒绝原因应为单会话占用提示");
        }

        // 关闭获胜会话 → 槽释放；再直连一次可建立新会话（无连接/槽泄漏）
        result.transport.close("测试结束");
        hostSession.get().shutdown("测试结束");
        assertTrue(awaitCondition(5_000, () -> !tcpServer.hasActiveSession()),
                "会话结束后单会话槽应释放");
        try (MiniViewer fresh = new MiniViewer(tcpServer.boundPort())) {
            fresh.sendRound1();
            assertEquals(REASON_CHALLENGE, fresh.awaitAck(5).getReason(),
                    "槽释放后新连接应可正常协商（无泄漏占槽）");
        }
    }

    // ------------------------------------------------------------------
    // 测试脚手架：服务器 / 被控端组件 / 假被控端 / 结果收集
    // ------------------------------------------------------------------

    /** 起真实服务器三件套（全部端口 0 随机分配；withRelay=true 时含中继服务器） */
    private void startServers(boolean withRelay) throws IOException {
        signaling = new SignalingServer(0);
        signaling.start();
        stun = new StunServer(0);
        stun.start();
        if (withRelay) {
            relay = new RelayServer(0);
            relay.start();
        }
    }

    private InetSocketAddress signalingAddress() {
        return new InetSocketAddress(InetAddress.getLoopbackAddress(), signaling.getPort());
    }

    private InetSocketAddress stunAddress() {
        return new InetSocketAddress(InetAddress.getLoopbackAddress(), stun.getPort());
    }

    private InetSocketAddress relayAddress() {
        return new InetSocketAddress(InetAddress.getLoopbackAddress(), relay.getPort());
    }

    /** 真实被控端会话管理器（单会话槽 + 自动授权 + 临时密码/信任文件） */
    private HostSessionManager newSessionManager() throws IOException {
        Path passwordFile = tempDir.resolve("password-" + System.nanoTime());
        HostPasswordStore.PasswordRecord record = HostPasswordStore.setPassword(passwordFile, PASSWORD);
        Path trustFile = tempDir.resolve("trusted-" + System.nanoTime());
        return new HostSessionManager(record, new AutoAuthorizer(AutoAuthorizer.Decision.ACCEPT),
                new TrustStore(trustFile), new ConnectionGatekeeper(5, Duration.ofSeconds(60)));
    }

    /** 起真实 TcpSessionServer（端口 0 随机分配） */
    private TcpSessionServer startTcpServer(HostSessionManager manager) throws InterruptedException {
        TcpSessionServer server = new TcpSessionServer(0, manager);
        server.start();
        tcpServers.add(server);
        return server;
    }

    /** 假被控端打洞 socket（回环随机端口） */
    private DatagramSocket bindUdp() throws IOException {
        return new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
    }

    /**
     * 假被控端打洞应答：阻塞等待首个 PUNCH 探测包 →（可延迟）回发 PUNCH →
     * 交真实 {@link HostSessionManager#tryCreateUdp} 建 UDP 会话（与生产
     * HostSignalingService 的打洞成功路径一致）。
     */
    private HostSession servePunch(DatagramSocket socket, HostSessionManager manager, long delayMs)
            throws IOException, InterruptedException {
        socket.setSoTimeout(10_000);
        byte[] buf = new byte[512];
        DatagramPacket packet = new DatagramPacket(buf, buf.length);
        socket.receive(packet);
        assertEquals(UdpHolePuncher.PUNCH_MAGIC.length, packet.getLength(), "首包应为 PUNCH 探测包");
        InetSocketAddress viewerAddress = (InetSocketAddress) packet.getSocketAddress();
        if (delayMs > 0) {
            Thread.sleep(delayMs);
        }
        socket.send(new DatagramPacket(UdpHolePuncher.PUNCH_MAGIC,
                UdpHolePuncher.PUNCH_MAGIC.length, viewerAddress));
        return manager.tryCreateUdp(socket, viewerAddress);
    }

    /** 注册假被控端（真实 SignalingClient），返回服务器分配的 9 位 ID */
    private String registerHost(BiConsumer<SignalingClient, ConnectForward> handler) throws IOException {
        SignalingClient[] holder = new SignalingClient[1];
        SignalingClient client = new SignalingClient(signalingAddress(), new SignalingClient.Listener() {
            @Override
            public void onConnectForward(ConnectForward forward) {
                try {
                    handler.accept(holder[0], forward);
                } catch (Throwable t) {
                    hostError.compareAndSet(null, t);
                }
            }
        });
        holder[0] = client; // ConnectForward 仅在注册完成后才可能到达，此处必已赋值
        hostClients.add(client);
        return client.register(new byte[0]);
    }

    /** 假被控端回 ConnectAccept（候选列表可空，携带中继令牌）；发送失败记录 hostError */
    private void sendAccept(SignalingClient client, List<PunchCandidate> candidates, String relayToken) {
        try {
            client.sendConnectAccept(ConnectAccept.newBuilder()
                    .setHostPublicKey(ByteString.EMPTY)
                    .addAllCandidates(candidates)
                    .setRelayToken(relayToken)
                    .build());
        } catch (IOException e) {
            hostError.compareAndSet(null, e);
        }
    }

    private static PunchCandidate udpCandidate(int port) {
        return candidate(TransportCandidates.SCHEME_UDP, port);
    }

    private static PunchCandidate tcpCandidate(int port) {
        return candidate(TransportCandidates.SCHEME_TCP, port);
    }

    private static PunchCandidate candidate(String scheme, int port) {
        return PunchCandidate.newBuilder()
                .setAddress(TransportCandidates.encode(scheme,
                        new InetSocketAddress(InetAddress.getLoopbackAddress(), port)))
                .build();
    }

    /** 发起编排连接（信令/STUN/中继地址注入为测试起的真实服务器随机端口） */
    private Result connect(String target) {
        ViewerConnectionOrchestrator orchestrator = relay != null
                ? new ViewerConnectionOrchestrator(signalingAddress(), stunAddress(), relayAddress(), false)
                : new ViewerConnectionOrchestrator(signalingAddress(), stunAddress(),
                        new InetSocketAddress(InetAddress.getLoopbackAddress(), 1), false);
        orchestrators.add(orchestrator);
        Result result = new Result();
        results.add(result);
        orchestrator.connect(target, null, this::onSessionMessage, result);
        return result;
    }

    /** messageHandler（IO 线程）：记录第一轮挑战 Ack */
    private void onSessionMessage(SessionMessage msg) {
        if (msg.getPayloadCase() == SessionMessage.PayloadCase.NEGOTIATE_ACK
                && REASON_CHALLENGE.equals(msg.getNegotiateAck().getReason())) {
            challengeLatch.countDown();
        }
    }

    /** 第一轮协商（明文临时公钥；不提交密码，避免触发授权与媒体管线） */
    private static SessionMessage round1() throws Exception {
        KeyPair keys = SessionHandshake.generateKeyPair();
        return SessionMessage.newBuilder()
                .setNegotiate(SessionNegotiate.newBuilder()
                        .setEphemeralPublicKey(
                                ByteString.copyFrom(SessionHandshake.encodePublicKey(keys.getPublic()))))
                .build();
    }

    private void awaitDone(Result result) throws InterruptedException {
        assertTrue(result.done.await(15, TimeUnit.SECONDS),
                "编排应在超时内出结果（failure=" + result.failure + "）");
    }

    private void awaitChallenge() throws InterruptedException {
        assertTrue(challengeLatch.await(10, TimeUnit.SECONDS),
                "应收到被控端第一轮挑战 Ack（通道双向数据未达）");
    }

    private static boolean awaitCondition(long timeoutMs, BooleanSupplier cond)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) {
                return true;
            }
            Thread.sleep(10);
        }
        return cond.getAsBoolean();
    }

    /** 编排结果收集（onTransportReady/onFailed 在编排虚拟线程上回调） */
    private static final class Result implements ViewerConnectionOrchestrator.Listener {

        final CountDownLatch done = new CountDownLatch(1);
        volatile SessionTransport transport;
        volatile ViewerConnectionOrchestrator.ConnectMode mode;
        volatile String failure;

        @Override
        public void onTransportReady(SessionTransport transport,
                                     ViewerConnectionOrchestrator.ConnectMode mode) {
            this.transport = transport;
            this.mode = mode;
            done.countDown();
        }

        @Override
        public void onFailed(String reason) {
            this.failure = reason;
            done.countDown();
        }
    }

    /** 直连被控端的最小主控端：可发第一轮协商、等待任意 Ack（含"被控中"拒绝） */
    private static final class MiniViewer implements AutoCloseable {

        final TcpSessionEndpoint endpoint;
        final KeyPair keys;
        final CountDownLatch ackLatch = new CountDownLatch(1);
        final AtomicReference<SessionNegotiateAck> ack = new AtomicReference<>();

        MiniViewer(int port) throws Exception {
            keys = SessionHandshake.generateKeyPair();
            endpoint = new TcpSessionEndpoint(null, this::onMessage);
            Bootstrap bootstrap = new Bootstrap()
                    .group(GROUP)
                    .channel(NioSocketChannel.class)
                    .option(ChannelOption.TCP_NODELAY, true)
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            endpoint.attach(ch); // 通道激活前挂载端点，服务端早到的 Ack 不丢
                        }
                    });
            bootstrap.connect("127.0.0.1", port).sync();
        }

        void sendRound1() {
            endpoint.send(SessionMessage.newBuilder()
                    .setNegotiate(SessionNegotiate.newBuilder()
                            .setEphemeralPublicKey(ByteString.copyFrom(
                                    SessionHandshake.encodePublicKey(keys.getPublic()))))
                    .build());
        }

        SessionNegotiateAck awaitAck(long seconds) throws InterruptedException {
            assertTrue(ackLatch.await(seconds, TimeUnit.SECONDS), "应收到被控端 Ack");
            return ack.get();
        }

        private void onMessage(SessionMessage msg) {
            if (msg.getPayloadCase() == SessionMessage.PayloadCase.NEGOTIATE_ACK) {
                ack.set(msg.getNegotiateAck());
                ackLatch.countDown();
            }
        }

        @Override
        public void close() {
            endpoint.close("测试结束");
        }
    }
}
