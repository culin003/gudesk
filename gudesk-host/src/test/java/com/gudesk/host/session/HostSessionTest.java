package com.gudesk.host.session;

import com.gudesk.common.crypto.CryptoUtil;
import com.gudesk.common.crypto.SessionCipher;
import com.gudesk.common.proto.GuDeskProto.SessionMessage;
import com.gudesk.common.proto.GuDeskProto.SessionNegotiate;
import com.gudesk.common.proto.GuDeskProto.SessionNegotiateAck;
import com.gudesk.common.session.ConnectionGatekeeper;
import com.gudesk.common.session.SessionHandshake;
import com.gudesk.common.session.TcpSessionEndpoint;
import com.google.protobuf.ByteString;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link HostSession} 状态机集成测试：真实 TCP loopback（经 {@link TcpSessionServer}），
 * 假主控端驱动两轮协商，覆盖：协商成功、密码错误、拒绝授权、单会话"被控中"，
 * 以及 Task 9 新增：信任列表命中免弹窗 / 未命中弹窗接受并"始终信任"持久化 /
 * 授权超时拒绝 / 密码连续 5 次失败锁定来源 / 身份证明失败 / 旧版无身份主控端兼容。
 *
 * <p>媒体管线说明：授权通过后 HostSession 会尝试加载 Robot 捕获/编码适配器——
 * headless 环境失败即关闭会话（不影响已断言的 Ack），有图形环境时短暂启动后随
 * 会话关闭清理，两种环境测试均稳定。本测试类在 {@code @BeforeAll} 显式钉住
 * capturer 为 RobotScreenCapturer：Wayland 桌面上 HostSession 的
 * {@code preferOnWaylandSession} 否则会选择 Portal 捕获实现，进而连接真实
 * xdg-desktop-portal 拿到真实屏幕流，破坏单测封闭性。
 */
class HostSessionTest {

    private static final String PASSWORD = "654321";

    private static EventLoopGroup group;

    @TempDir
    Path tempDir;

    private TcpSessionServer server;
    private Path passwordFile;
    private Path trustFile;

    @BeforeAll
    static void startGroup() {
        // 钉住 capturer：避免 Wayland 桌面上 preferOnWaylandSession 触发真实 portal 会话
        System.setProperty("gudesk.adapter.capturer",
                com.gudesk.host.capture.RobotScreenCapturer.class.getName());
        group = new NioEventLoopGroup(2, r -> {
            Thread t = new Thread(r, "host-session-test-io");
            t.setDaemon(true);
            return t;
        });
    }

    @AfterAll
    static void stopGroup() {
        System.clearProperty("gudesk.adapter.capturer");
        if (group != null) {
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS);
        }
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop();
        }
    }

    private void startServer(Authorizer authorizer) throws Exception {
        startServer(authorizer, new TrustStore(newTrustFile()));
    }

    private void startServer(Authorizer authorizer, TrustStore trustStore) throws Exception {
        passwordFile = tempDir.resolve("password-" + System.nanoTime());
        HostPasswordStore.PasswordRecord record = HostPasswordStore.setPassword(passwordFile, PASSWORD);
        server = new TcpSessionServer(0, record, authorizer, trustStore,
                new ConnectionGatekeeper(5, Duration.ofSeconds(60)));
        server.start();
    }

    private Path newTrustFile() {
        trustFile = tempDir.resolve("trusted-" + System.nanoTime());
        return trustFile;
    }

    // ------------------------------------------------------------------
    // 基础路径
    // ------------------------------------------------------------------

    @Test
    @Timeout(30)
    void 协商成功收到授权Ack() throws Exception {
        startServer(new AutoAuthorizer(AutoAuthorizer.Decision.ACCEPT));
        try (FakeViewer viewer = new FakeViewer(server.boundPort(), PASSWORD, null, false)) {
            viewer.startNegotiation();
            SessionNegotiateAck ack = viewer.awaitFinalACK(15);
            assertTrue(ack.getAuthorized(), "正确密码+授权接受应返回 authorized=true");
            assertTrue(ack.getReason().isEmpty() || !ack.getReason().contains("密码错误"),
                    "成功 Ack 不应带失败原因: " + ack.getReason());
        }
    }

    @Test
    @Timeout(30)
    void 密码错误被拒绝并关闭() throws Exception {
        startServer(new AutoAuthorizer(AutoAuthorizer.Decision.ACCEPT));
        try (FakeViewer viewer = new FakeViewer(server.boundPort(), "000000", null, false)) {
            viewer.startNegotiation();
            SessionNegotiateAck ack = viewer.awaitFinalACK(15);
            assertFalse(ack.getAuthorized());
            assertEquals("密码错误", ack.getReason());
            assertTrue(awaitCondition(5_000, viewer.endpoint::isClosed), "被控端应关闭连接");
        }
    }

    @Test
    @Timeout(30)
    void 拒绝授权被关闭() throws Exception {
        startServer(new AutoAuthorizer(AutoAuthorizer.Decision.REJECT));
        try (FakeViewer viewer = new FakeViewer(server.boundPort(), PASSWORD, null, false)) {
            viewer.startNegotiation();
            SessionNegotiateAck ack = viewer.awaitFinalACK(15);
            assertFalse(ack.getAuthorized());
            assertEquals("用户拒绝授权", ack.getReason());
            assertTrue(awaitCondition(5_000, viewer.endpoint::isClosed), "被控端应关闭连接");
        }
    }

    @Test
    @Timeout(30)
    void 已有会话时新连接被拒绝() throws Exception {
        startServer(new AutoAuthorizer(AutoAuthorizer.Decision.ACCEPT));
        // 第一个主控端占住单会话槽（停在第一轮协商后，不提交密码）
        try (FakeViewer busy = new FakeViewer(server.boundPort(), PASSWORD, null, false)) {
            busy.startNegotiation();
            assertTrue(awaitCondition(5_000, server::hasActiveSession), "服务端应报告有活跃会话");

            // 第二个主控端：立即收到"被控中"拒绝
            try (FakeViewer second = new FakeViewer(server.boundPort(), PASSWORD, null, false)) {
                second.startNegotiation();
                SessionNegotiateAck ack = second.awaitFinalACK(15);
                assertFalse(ack.getAuthorized());
                assertTrue(ack.getReason().contains("被控中"),
                        "拒绝原因应含\"被控中\": " + ack.getReason());
            }
        }
    }

    // ------------------------------------------------------------------
    // Task 9：授权确认 + 信任列表
    // ------------------------------------------------------------------

    @Test
    @Timeout(30)
    void 信任列表命中免弹窗直接授权() throws Exception {
        KeyPair identity = SessionHandshake.generateKeyPair();
        TrustStore trustStore = new TrustStore(newTrustFile());
        // 预先信任该身份指纹
        trustStore.trust(fingerprintOf(identity), "预信任的主控");
        // AutoAuthorizer 配置为拒绝：若仍被咨询则必然失败，从而证明走了免弹窗路径
        AutoAuthorizer rejectAll = new AutoAuthorizer(AutoAuthorizer.Decision.REJECT);
        startServer(rejectAll, trustStore);

        try (FakeViewer viewer = new FakeViewer(server.boundPort(), PASSWORD, identity, false)) {
            viewer.startNegotiation();
            SessionNegotiateAck ack = viewer.awaitFinalACK(15);
            assertTrue(ack.getAuthorized(), "信任列表命中应跳过授权确认直接授权");
            assertEquals(0, rejectAll.requestCount(), "授权器不应被咨询（免弹窗）");
        }
    }

    @Test
    @Timeout(30)
    void 未命中弹窗接受且始终信任被持久化() throws Exception {
        KeyPair identity = SessionHandshake.generateKeyPair();
        AutoAuthorizer acceptAlways = new AutoAuthorizer(AutoAuthorizer.Decision.ACCEPT, true, 0);
        startServer(acceptAlways);

        try (FakeViewer viewer = new FakeViewer(server.boundPort(), PASSWORD, identity, false)) {
            viewer.startNegotiation();
            SessionNegotiateAck ack = viewer.awaitFinalACK(15);
            assertTrue(ack.getAuthorized(), "未命中信任列表时弹窗（授权器）接受应授权");
            assertEquals(1, acceptAlways.requestCount(), "授权器应被咨询一次");
            assertEquals(fingerprintOf(identity), acceptAlways.lastFingerprint(),
                    "授权请求应携带主控端指纹");
        }
        // always_trust=true 已持久化：新实例（重读文件）可命中
        TrustStore reloaded = new TrustStore(trustFile);
        assertTrue(reloaded.isTrusted(fingerprintOf(identity)),
                "授权响应 always_trust=true 应将指纹写入信任列表持久化");
    }

    @Test
    @Timeout(30)
    void 授权超时视为拒绝() throws Exception {
        startServer(new AutoAuthorizer(AutoAuthorizer.Decision.TIMEOUT, false, 200));
        try (FakeViewer viewer = new FakeViewer(server.boundPort(), PASSWORD, null, false)) {
            viewer.startNegotiation();
            SessionNegotiateAck ack = viewer.awaitFinalACK(15);
            assertFalse(ack.getAuthorized(), "授权超时应视为拒绝");
            assertEquals("授权确认超时，已自动拒绝", ack.getReason());
            assertTrue(awaitCondition(5_000, viewer.endpoint::isClosed), "被控端应关闭连接");
        }
    }

    @Test
    @Timeout(30)
    void 身份证明错误被拒绝() throws Exception {
        // 携带身份公钥但身份证明被篡改：不能仅凭"声称的公钥"获得信任
        startServer(new AutoAuthorizer(AutoAuthorizer.Decision.ACCEPT));
        try (FakeViewer viewer = new FakeViewer(server.boundPort(), PASSWORD,
                SessionHandshake.generateKeyPair(), true)) {
            viewer.startNegotiation();
            SessionNegotiateAck ack = viewer.awaitFinalACK(15);
            assertFalse(ack.getAuthorized(), "身份证明验证失败应拒绝");
            assertEquals("主控端身份证明失败", ack.getReason());
            assertTrue(awaitCondition(5_000, viewer.endpoint::isClosed), "被控端应关闭连接");
        }
    }

    @Test
    @Timeout(30)
    void 旧版无身份主控端仍可授权() throws Exception {
        // 不携带长期身份公钥（旧版客户端）：无指纹无信任命中，走弹窗路径
        startServer(new AutoAuthorizer(AutoAuthorizer.Decision.ACCEPT));
        try (FakeViewer viewer = new FakeViewer(server.boundPort(), PASSWORD, null, false)) {
            viewer.startNegotiation();
            SessionNegotiateAck ack = viewer.awaitFinalACK(15);
            assertTrue(ack.getAuthorized(), "无身份密钥的旧版主控端应仍可正常授权");
        }
    }

    // ------------------------------------------------------------------
    // Task 9.1：密码失败锁定（loopback 集成，与端到端联调等价）
    // ------------------------------------------------------------------

    @Test
    @Timeout(60)
    void 密码连续五次失败后来源被锁定() throws Exception {
        startServer(new AutoAuthorizer(AutoAuthorizer.Decision.ACCEPT));
        // 5 次错误密码：每次收到"密码错误"，等待会话释放后重连
        for (int i = 1; i <= 5; i++) {
            try (FakeViewer viewer = new FakeViewer(server.boundPort(), "wrong-" + i, null, false)) {
                viewer.startNegotiation();
                SessionNegotiateAck ack = viewer.awaitFinalACK(15);
                assertFalse(ack.getAuthorized(), "第 " + i + " 次错误密码应被拒绝");
                assertEquals("密码错误", ack.getReason(), "前 5 次应收到密码错误提示");
            }
            assertTrue(awaitCondition(5_000, () -> !server.hasActiveSession()),
                    "等待会话槽释放后再重连（第 " + i + " 次）");
        }
        // 第 6 次：来源 IP 已锁定，直接拒绝且不给密码验证机会
        try (FakeViewer viewer = new FakeViewer(server.boundPort(), PASSWORD, null, false)) {
            viewer.startNegotiation();
            SessionNegotiateAck ack = viewer.awaitFinalACK(15);
            assertFalse(ack.getAuthorized());
            assertEquals(TcpSessionServer.REASON_LOCKED, ack.getReason(),
                    "锁定期间应收到锁定提示（即使密码正确）");
            assertTrue(awaitCondition(5_000, viewer.endpoint::isClosed), "被控端应关闭连接");
        }
    }

    // ------------------------------------------------------------------
    // 假主控端（完整两轮协商协议实现，可携带长期身份密钥）
    // ------------------------------------------------------------------

    /**
     * 假主控端：连接被控端并执行两轮协商（X25519 交换 + AES-GCM 密文密码提交），
     * 记录最终 Ack（authorized/reason）。identity 非 null 时携带身份公钥与持有证明；
     * tamperProof=true 时提交篡改的身份证明（验证被控端拒绝冒用）。
     */
    private final class FakeViewer implements AutoCloseable {

        final TcpSessionEndpoint endpoint;
        private final KeyPair keys;
        private final byte[] pub;
        private final KeyPair identity;
        private final boolean tamperProof;
        private final String password;
        private final CountDownLatch finalAckLatch = new CountDownLatch(1);
        private final AtomicReference<SessionNegotiateAck> finalAck = new AtomicReference<>();
        private volatile Exception protocolError;

        FakeViewer(int port, String password, KeyPair identity, boolean tamperProof) throws Exception {
            this.password = password;
            this.identity = identity;
            this.tamperProof = tamperProof;
            this.keys = SessionHandshake.generateKeyPair();
            this.pub = SessionHandshake.encodePublicKey(keys.getPublic());
            Bootstrap bootstrap = new Bootstrap()
                    .group(group)
                    .channel(NioSocketChannel.class)
                    .option(ChannelOption.TCP_NODELAY, true)
                    .handler(new ChannelInboundHandlerAdapter());
            SocketChannel ch = (SocketChannel) bootstrap.connect("127.0.0.1", port).sync().channel();
            this.endpoint = new TcpSessionEndpoint(null, this::onMessage);
            this.endpoint.attach(ch);
        }

        /** 发送第一轮协商（明文临时公钥 + 可选身份公钥） */
        void startNegotiation() {
            SessionNegotiate.Builder builder = SessionNegotiate.newBuilder()
                    .setEphemeralPublicKey(ByteString.copyFrom(pub));
            if (identity != null) {
                builder.setViewerIdentityPublicKey(
                        ByteString.copyFrom(SessionHandshake.encodePublicKey(identity.getPublic())));
            }
            endpoint.send(SessionMessage.newBuilder().setNegotiate(builder).build());
        }

        SessionNegotiateAck awaitFinalACK(long seconds) throws Exception {
            assertTrue(finalAckLatch.await(seconds, TimeUnit.SECONDS),
                    "等待最终 Ack 超时" + (protocolError != null ? "（协议错误: " + protocolError + "）" : ""));
            return finalAck.get();
        }

        private void onMessage(SessionMessage msg) {
            if (msg.getPayloadCase() != SessionMessage.PayloadCase.NEGOTIATE_ACK) {
                return;
            }
            SessionNegotiateAck ack = msg.getNegotiateAck();
            if (HostSession.REASON_CHALLENGE.equals(ack.getReason())) {
                onChallenge(ack);
                return;
            }
            finalAck.set(ack);
            finalAckLatch.countDown();
        }

        /** 第一轮挑战：派生会话密钥、激活密文管线、提交密码与身份证明 */
        private void onChallenge(SessionNegotiateAck ack) {
            try {
                byte[] hostPub = ack.getEphemeralPublicKey().toByteArray();
                byte[] shared = SessionHandshake.ecdh(keys.getPrivate(),
                        SessionHandshake.parsePublicKey(hostPub));
                byte[] salt = SessionHandshake.cipherSalt(pub, hostPub);
                endpoint.activateCipher(SessionCipher.init(shared, salt, true));
                SessionNegotiate.Builder round2 = SessionNegotiate.newBuilder()
                        .setEphemeralPublicKey(ByteString.copyFrom(pub))
                        .setPasswordProof(password);
                if (identity != null) {
                    byte[] proof = CryptoUtil.sha256(SessionHandshake.ecdh(
                            identity.getPrivate(), SessionHandshake.parsePublicKey(hostPub)));
                    if (tamperProof) {
                        for (int i = 0; i < proof.length; i++) {
                            proof[i] ^= 0x5A; // 篡改证明
                        }
                    }
                    round2.setIdentityProof(ByteString.copyFrom(proof));
                }
                endpoint.send(SessionMessage.newBuilder().setNegotiate(round2).build());
            } catch (Exception e) {
                protocolError = e;
                finalAckLatch.countDown();
            }
        }

        @Override
        public void close() {
            endpoint.close("测试结束");
        }
    }

    // ------------------------------------------------------------------

    private static String fingerprintOf(KeyPair identity) {
        return CryptoUtil.fingerprint(SessionHandshake.encodePublicKey(identity.getPublic()));
    }

    private static boolean awaitCondition(long timeoutMs, java.util.function.BooleanSupplier cond)
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
}
