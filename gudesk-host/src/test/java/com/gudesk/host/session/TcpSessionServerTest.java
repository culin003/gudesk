package com.gudesk.host.session;

import com.gudesk.common.net.SignalingClient;
import com.gudesk.common.net.TransportCandidates;
import com.gudesk.common.proto.GuDeskProto.ConnectAccept;
import com.gudesk.common.proto.GuDeskProto.ConnectReject;
import com.gudesk.common.proto.GuDeskProto.ConnectRequest;
import com.gudesk.common.proto.GuDeskProto.PunchCandidate;
import com.gudesk.common.proto.GuDeskProto.SessionMessage;
import com.gudesk.common.proto.GuDeskProto.SessionNegotiate;
import com.gudesk.common.proto.GuDeskProto.SessionNegotiateAck;
import com.gudesk.common.session.ConnectionGatekeeper;
import com.gudesk.common.session.SessionHandshake;
import com.gudesk.common.session.TcpSessionEndpoint;
import com.gudesk.server.signaling.SignalingServer;
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
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TcpSessionServer} 单会话槽并发抢占集成测试（loopback 真实服务），覆盖：
 *
 * <ul>
 *   <li>两个连接先后到达：第一个建立会话并开始协商后，第二个收到
 *       "被控中：已有主控会话进行"（REASON_BUSY）拒绝并断开；</li>
 *   <li>会话结束后单会话槽释放，新连接可再次接入协商；</li>
 *   <li>HostSessionManager.hasActiveSession 与信令层联动：会话占用期间
 *       {@link HostSignalingService} 对 ConnectRequest 回 ConnectReject(REASON_BUSY)
 *       转发给主控端；会话结束后新 ConnectRequest 被接受（ConnectAccept 携带
 *       TCP 候选与 relay_token）。</li>
 * </ul>
 *
 * <p>主控端只驱动到第一轮协商（收到 CHALLENGE 即证明会话已建立且
 * hasNegotiationStarted=true，单会话槽占用判定依据），不提交密码，避免触发
 * 授权与媒体管线。
 */
class TcpSessionServerTest {

    private static final String PASSWORD = "246802";
    /** 被控端第一轮挑战标记（协议约定，见 HostSession.REASON_CHALLENGE） */
    private static final String REASON_CHALLENGE = "CHALLENGE";

    private static final EventLoopGroup GROUP = new NioEventLoopGroup(2, r -> {
        Thread t = new Thread(r, "tcp-session-server-test-io");
        t.setDaemon(true);
        return t;
    });

    @TempDir
    Path tempDir;

    private TcpSessionServer server;
    private SignalingServer signaling;
    private HostSignalingService signalingService;
    private final List<AutoCloseable> closeables = new ArrayList<>();

    @AfterAll
    static void stopGroup() {
        GROUP.shutdownGracefully(0, 1, TimeUnit.SECONDS);
    }

    @AfterEach
    void tearDown() {
        for (AutoCloseable closeable : closeables) {
            try {
                closeable.close();
            } catch (Exception ignored) {
                // 清理失败不影响测试结果
            }
        }
        closeables.clear();
        if (signalingService != null) {
            signalingService.close();
            signalingService = null;
        }
        if (server != null) {
            server.stop();
            server = null;
        }
        if (signaling != null) {
            signaling.close();
            signaling = null;
        }
    }

    // ------------------------------------------------------------------
    // 单会话槽：占用拒绝与释放复用
    // ------------------------------------------------------------------

    @Test
    @Timeout(30)
    void 并发接入_第一个协商中_第二个被控中拒绝() throws Exception {
        startServer();

        // 第一个主控端建立会话并发起协商（占用单会话槽）
        try (MiniViewer first = new MiniViewer(server.boundPort())) {
            first.sendRound1();
            assertEquals(REASON_CHALLENGE, first.awaitAck(10).getReason(),
                    "第一个主控端应正常进入协商");
            assertTrue(server.hasActiveSession(), "会话建立后被控端应报告活跃会话");

            // 第二个主控端并发接入：单会话槽被占用，收到 REASON_BUSY 拒绝
            try (MiniViewer second = new MiniViewer(server.boundPort())) {
                SessionNegotiateAck ack = second.awaitAck(10);
                assertFalse(ack.getAuthorized(), "已有主控会话进行时新连接应被拒绝");
                assertEquals(TcpSessionServer.REASON_BUSY, ack.getReason(),
                        "拒绝原因应为单会话占用提示");
                assertTrue(awaitCondition(5_000, second::isClosed),
                        "被拒连接应被被控端关闭");
            }

            // 第一个会话不受第二个连接影响
            assertTrue(server.hasActiveSession(), "被拒连接不应影响进行中的会话");
        }
    }

    @Test
    @Timeout(30)
    void 会话结束后槽释放_新连接可再次接入() throws Exception {
        startServer();

        // 第一个主控端建立会话后主动断开
        try (MiniViewer first = new MiniViewer(server.boundPort())) {
            first.sendRound1();
            assertEquals(REASON_CHALLENGE, first.awaitAck(10).getReason());
            assertTrue(server.hasActiveSession());
            first.close();
            assertTrue(awaitCondition(5_000, () -> !server.hasActiveSession()),
                    "会话断开后单会话槽应释放");
        }

        // 新连接可再次接入并正常协商（未被残留状态"被控中"拒绝）
        try (MiniViewer second = new MiniViewer(server.boundPort())) {
            second.sendRound1();
            assertEquals(REASON_CHALLENGE, second.awaitAck(10).getReason(),
                    "槽释放后新连接应可建立会话");
            assertTrue(server.hasActiveSession());
        }
    }

    // ------------------------------------------------------------------
    // 与信令层联动：HostSignalingService 的 ConnectReject/ConnectAccept 转发
    // ------------------------------------------------------------------

    @Test
    @Timeout(30)
    void 信令层_已有会话时连接请求被拒转发() throws Exception {
        HostSessionManager manager = startSignalingLinkedServer();

        // 被控端已有一个进行中的主控会话（已开始协商）
        try (MiniViewer occupier = new MiniViewer(server.boundPort());
             ViewerHandle viewer = registerViewer()) {
            occupier.sendRound1();
            assertEquals(REASON_CHALLENGE, occupier.awaitAck(10).getReason());
            assertTrue(manager.hasActiveSession());

            // 主控端经信令发起连接 → HostSignalingService 检测到会话占用，
            // 应回 ConnectReject(REASON_BUSY) 并转发给主控端
            viewer.requestConnect(signalingService.assignedId());
            ConnectReject reject = viewer.awaitReject(10);
            assertEquals(TcpSessionServer.REASON_BUSY, reject.getReason(),
                    "会话占用期间信令层应转发单会话占用拒绝");
        }
    }

    @Test
    @Timeout(30)
    void 信令层_会话结束后新连接请求被接受() throws Exception {
        HostSessionManager manager = startSignalingLinkedServer();

        try (MiniViewer occupier = new MiniViewer(server.boundPort());
             ViewerHandle viewer = registerViewer()) {
            // 先占用再释放（会话结束后）
            occupier.sendRound1();
            assertEquals(REASON_CHALLENGE, occupier.awaitAck(10).getReason());
            occupier.close();
            assertTrue(awaitCondition(5_000, () -> !manager.hasActiveSession()),
                    "会话断开后单会话槽应释放");

            // 新的 ConnectRequest 不再被"被控中"拒绝，收到 ConnectAccept
            // （候选含 TCP 会话端口，relay_token 非空）
            viewer.requestConnect(signalingService.assignedId());
            ConnectAccept accept = viewer.awaitAccept(10);
            assertTrue(accept.getRelayToken().isEmpty() == false, "接受响应应携带中继令牌");
            String expected = TransportCandidates.encode(TransportCandidates.SCHEME_TCP,
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), server.boundPort()));
            boolean hasTcpCandidate = false;
            for (PunchCandidate candidate : accept.getCandidatesList()) {
                hasTcpCandidate |= expected.equals(candidate.getAddress());
            }
            assertTrue(hasTcpCandidate, "接受响应应包含 TCP 会话候选: " + expected);
        }
    }

    // ------------------------------------------------------------------
    // 测试脚手架
    // ------------------------------------------------------------------

    /** 起真实 TcpSessionServer（随机端口）+ 自动授权 */
    private void startServer() throws Exception {
        server = new TcpSessionServer(0, newManager());
        server.start();
    }

    private HostSessionManager newManager() throws IOException {
        Path passwordFile = tempDir.resolve("password-" + System.nanoTime());
        HostPasswordStore.PasswordRecord record = HostPasswordStore.setPassword(passwordFile, PASSWORD);
        Path trustFile = tempDir.resolve("trusted-" + System.nanoTime());
        return new HostSessionManager(record, new AutoAuthorizer(AutoAuthorizer.Decision.ACCEPT),
                new TrustStore(trustFile), new ConnectionGatekeeper(5, Duration.ofSeconds(60)));
    }

    /**
     * 起信令联动的完整被控端：真实 SignalingServer（随机端口）+ TcpSessionServer
     * 与 {@link HostSignalingService}（noUdp 测试模式：跳过打洞）共享同一
     * HostSessionManager 单会话槽。
     *
     * @return 共享的会话管理器（hasActiveSession 联动断言用）
     */
    private HostSessionManager startSignalingLinkedServer() throws Exception {
        HostSessionManager manager = newManager();
        server = new TcpSessionServer(0, manager);
        server.start();
        signaling = new SignalingServer(0);
        signaling.start();
        InetSocketAddress signalingAddress =
                new InetSocketAddress(InetAddress.getLoopbackAddress(), signaling.getPort());
        signalingService = new HostSignalingService(manager, server::boundPort,
                signalingAddress, true);
        signalingService.start();
        return manager;
    }

    /**
     * 建立主控端信令连接并注册（真实 SignalingClient，随机端口信令服务器）。
     * ConnectRequest 的发送时机由各测试自行控制（先确定会话槽占用状态）。
     */
    private ViewerHandle registerViewer() throws IOException {
        ViewerHandle viewer = new ViewerHandle(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), signaling.getPort()));
        closeables.add(viewer);
        viewer.register();
        return viewer;
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

    /** 主控端信令句柄：包装真实 SignalingClient（final，组合不继承），携带 accept/reject Future 供断言等待 */
    private static final class ViewerHandle implements AutoCloseable {

        final SignalingClient client;
        final CompletableFuture<ConnectAccept> acceptFuture = new CompletableFuture<>();
        final CompletableFuture<ConnectReject> rejectFuture = new CompletableFuture<>();

        ViewerHandle(InetSocketAddress signalingAddress) throws IOException {
            client = new SignalingClient(signalingAddress, new SignalingClient.Listener() {
                @Override
                public void onConnectAccept(ConnectAccept accept) {
                    acceptFuture.complete(accept);
                }

                @Override
                public void onConnectReject(ConnectReject reject) {
                    rejectFuture.complete(reject);
                }
            });
        }

        /** 注册（服务器分配临时主控 ID） */
        void register() throws IOException {
            client.register(new byte[0]);
        }

        /** 向目标被控端发起 ConnectRequest */
        void requestConnect(String targetId) throws IOException {
            client.sendConnectRequest(ConnectRequest.newBuilder()
                    .setTargetId(targetId)
                    .setViewerPublicKey(ByteString.EMPTY)
                    .build());
        }

        /** 等待信令层拒绝响应 */
        ConnectReject awaitReject(int seconds) throws Exception {
            ConnectReject reject = rejectFuture.get(seconds, TimeUnit.SECONDS);
            assertNotNull(reject, "应收到 ConnectReject");
            return reject;
        }

        /** 等待信令层接受响应 */
        ConnectAccept awaitAccept(int seconds) throws Exception {
            return acceptFuture.get(seconds, TimeUnit.SECONDS);
        }

        @Override
        public void close() {
            client.close();
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

        boolean isClosed() {
            return endpoint.isClosed();
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
