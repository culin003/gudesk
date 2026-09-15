package com.gudesk.server.signaling;

import com.gudesk.common.proto.GuDeskProto.ConnectAccept;
import com.gudesk.common.proto.GuDeskProto.ConnectReject;
import com.gudesk.common.proto.GuDeskProto.ConnectRequest;
import com.gudesk.common.proto.GuDeskProto.Heartbeat;
import com.gudesk.common.proto.GuDeskProto.HeartbeatAck;
import com.gudesk.common.proto.GuDeskProto.PunchCandidate;
import com.gudesk.common.proto.GuDeskProto.RegisterRequest;
import com.gudesk.common.proto.GuDeskProto.SignalingEnvelope;
import com.google.protobuf.ByteString;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.Socket;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SignalingServerTest {

    private SignalingServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.close();
        }
    }

    // ------------------------------------------------------------------
    // 测试客户端：阻塞 socket + BlockingFrame
    // ------------------------------------------------------------------
    static final class TestClient implements AutoCloseable {
        final Socket socket;

        TestClient(int port) throws IOException {
            socket = new Socket("127.0.0.1", port);
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(5000);
        }

        void write(SignalingEnvelope envelope) throws IOException {
            BlockingFrame.writeEnvelope(socket.getOutputStream(), envelope);
            socket.getOutputStream().flush();
        }

        SignalingEnvelope read() throws IOException {
            SignalingEnvelope envelope = BlockingFrame.readEnvelope(socket.getInputStream());
            assertNotNull(envelope, "连接被意外关闭");
            return envelope;
        }

        /** 注册（id 留空，服务器分配），返回 assigned_id。 */
        String register(byte[] publicKey) throws IOException {
            write(SignalingEnvelope.newBuilder()
                    .setRegisterRequest(RegisterRequest.newBuilder()
                            .setPublicKey(ByteString.copyFrom(publicKey)))
                    .build());
            SignalingEnvelope response = read();
            assertEquals(SignalingEnvelope.PayloadCase.REGISTER_RESPONSE, response.getPayloadCase());
            assertTrue(response.getRegisterResponse().getOk());
            return response.getRegisterResponse().getAssignedId();
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

    private SignalingServer startServer() throws IOException {
        return startServer(SignalingServer.DEFAULT_HEARTBEAT_TIMEOUT_MS,
                SignalingServer.DEFAULT_SWEEP_INTERVAL_MS,
                SignalingServer.DEFAULT_CONNECT_TIMEOUT_MS);
    }

    private SignalingServer startServer(long heartbeatTimeoutMs, long sweepIntervalMs, long connectTimeoutMs)
            throws IOException {
        server = new SignalingServer(0, heartbeatTimeoutMs, sweepIntervalMs, connectTimeoutMs);
        server.start();
        return server;
    }

    // ------------------------------------------------------------------
    // 注册与心跳
    // ------------------------------------------------------------------

    @Test
    void 注册分配九位数字ID且始终分配新ID() throws IOException {
        startServer();
        try (TestClient client = new TestClient(server.getPort())) {
            byte[] key = new byte[32];
            String id1 = client.register(key);
            String id2 = client.register(key);
            assertTrue(id1.matches("\\d{9}"), "ID 应为 9 位数字: " + id1);
            assertTrue(id2.matches("\\d{9}"), "ID 应为 9 位数字: " + id2);
            assertNotEquals(id1, id2, "重复注册应分配新 ID");
            // 旧 ID 应已下线（被新注册覆盖）
            assertTrue(!server.isOnline(id1));
            assertTrue(server.isOnline(id2));
        }
    }

    @Test
    void 心跳保活并刷新超时() throws IOException, InterruptedException {
        // 超时 800ms、清扫 100ms：发心跳的连接应持续在线
        startServer(800, 100, 1000);
        try (TestClient client = new TestClient(server.getPort())) {
            String id = client.register(new byte[32]);
            for (int i = 0; i < 5; i++) {
                client.write(SignalingEnvelope.newBuilder()
                        .setHeartbeat(Heartbeat.newBuilder().setTimestamp(System.currentTimeMillis()))
                        .build());
                SignalingEnvelope ack = client.read();
                assertEquals(SignalingEnvelope.PayloadCase.HEARTBEAT_ACK, ack.getPayloadCase());
                assertTrue(ack.getHeartbeatAck().getTimestamp() > 0);
                Thread.sleep(300);
            }
            assertTrue(server.isOnline(id), "持续心跳的连接应保持在线");
        }
    }

    @Test
    void 无心跳超时被清扫下线() throws IOException, InterruptedException {
        startServer(400, 100, 1000);
        String id;
        try (TestClient client = new TestClient(server.getPort())) {
            id = client.register(new byte[32]);
            assertTrue(server.isOnline(id));
            // 不发心跳，等待清扫（400ms 超时 + 100ms 周期，2s 足够）
            long deadline = System.currentTimeMillis() + 2000;
            while (server.isOnline(id) && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
            assertTrue(!server.isOnline(id), "无心跳连接应被清扫下线");
            // 连接应被服务端关闭：读到 EOF（readEnvelope 返回 null）
            client.socket.setSoTimeout(2000);
            SignalingEnvelope eof = BlockingFrame.readEnvelope(client.socket.getInputStream());
            org.junit.jupiter.api.Assertions.assertNull(eof, "服务端应关闭超时连接");
        }
    }

    // ------------------------------------------------------------------
    // ConnectRequest 路由全路径
    // ------------------------------------------------------------------

    @Test
    void 连接请求_对方不在线() throws IOException {
        startServer();
        try (TestClient viewer = new TestClient(server.getPort())) {
            viewer.register(new byte[32]);
            viewer.write(SignalingEnvelope.newBuilder()
                    .setConnectRequest(ConnectRequest.newBuilder()
                            .setTargetId("999999999")
                            .setViewerPublicKey(ByteString.copyFromUtf8("viewer-pk")))
                    .build());
            SignalingEnvelope reject = viewer.read();
            assertEquals(SignalingEnvelope.PayloadCase.CONNECT_REJECT, reject.getPayloadCase());
            assertEquals("对方不在线", reject.getConnectReject().getReason());
        }
    }

    @Test
    void 连接请求_主控端未注册() throws IOException {
        startServer();
        try (TestClient viewer = new TestClient(server.getPort())) {
            viewer.write(SignalingEnvelope.newBuilder()
                    .setConnectRequest(ConnectRequest.newBuilder()
                            .setTargetId("999999999"))
                    .build());
            SignalingEnvelope reject = viewer.read();
            assertEquals(SignalingEnvelope.PayloadCase.CONNECT_REJECT, reject.getPayloadCase());
            assertEquals("主控端未注册", reject.getConnectReject().getReason());
        }
    }

    @Test
    void 连接请求_在线接受并透传或注入relay_token() throws IOException {
        startServer();
        try (TestClient host = new TestClient(server.getPort());
             TestClient viewer = new TestClient(server.getPort())) {
            String hostId = host.register(new byte[32]);
            String viewerId = viewer.register(new byte[32]);

            viewer.write(SignalingEnvelope.newBuilder()
                    .setConnectRequest(ConnectRequest.newBuilder()
                            .setTargetId(hostId)
                            .setViewerPublicKey(ByteString.copyFromUtf8("viewer-pk-test"))
                            .addCandidates(PunchCandidate.newBuilder().setAddress("10.1.2.3:4567"))
                            .addCandidates(PunchCandidate.newBuilder().setAddress("10.1.2.3:4568")))
                    .build());

            // 被控端收到 ConnectForward：from_id=viewer 临时 id + 公钥 + 候选
            SignalingEnvelope forward = host.read();
            assertEquals(SignalingEnvelope.PayloadCase.CONNECT_FORWARD, forward.getPayloadCase());
            assertEquals(viewerId, forward.getConnectForward().getFromId());
            assertEquals("viewer-pk-test", forward.getConnectForward().getViewerPublicKey().toStringUtf8());
            assertEquals(2, forward.getConnectForward().getCandidatesCount());
            assertEquals("10.1.2.3:4567", forward.getConnectForward().getCandidates(0).getAddress());

            // 被控端接受（携带预生成 relay_token：打洞失败时需预知 token 提前连中继等待
            // 配对，服务器透传不覆盖）
            host.write(SignalingEnvelope.newBuilder()
                    .setConnectAccept(ConnectAccept.newBuilder()
                            .setHostPublicKey(ByteString.copyFromUtf8("host-pk-test"))
                            .setRelayToken("pre-generated-by-host"))
                    .build());

            // 主控端收到 ConnectAccept：relay_token 为被控端预生成 token（服务器透传）
            SignalingEnvelope accept = viewer.read();
            assertEquals(SignalingEnvelope.PayloadCase.CONNECT_ACCEPT, accept.getPayloadCase());
            assertEquals("host-pk-test", accept.getConnectAccept().getHostPublicKey().toStringUtf8());
            assertEquals("pre-generated-by-host", accept.getConnectAccept().getRelayToken(),
                    "被控端预生成的 relay_token 应由服务器透传给主控端");

            // 第二轮：被控端未携带 relay_token（旧版/占位），服务器注入 UUID
            viewer.write(SignalingEnvelope.newBuilder()
                    .setConnectRequest(ConnectRequest.newBuilder()
                            .setTargetId(hostId)
                            .setViewerPublicKey(ByteString.copyFromUtf8("viewer-pk-test")))
                    .build());
            assertEquals(SignalingEnvelope.PayloadCase.CONNECT_FORWARD, host.read().getPayloadCase());
            host.write(SignalingEnvelope.newBuilder()
                    .setConnectAccept(ConnectAccept.newBuilder()
                            .setHostPublicKey(ByteString.copyFromUtf8("host-pk-test")))
                    .build());
            SignalingEnvelope injected = viewer.read();
            assertEquals(SignalingEnvelope.PayloadCase.CONNECT_ACCEPT, injected.getPayloadCase());
            assertNotEquals("", injected.getConnectAccept().getRelayToken(),
                    "被控端未携带 relay_token 时服务器应注入");
            assertDoesNotThrowUuid(injected.getConnectAccept().getRelayToken());
        }
    }

    @Test
    void 连接请求_在线拒绝() throws IOException {
        startServer();
        try (TestClient host = new TestClient(server.getPort());
             TestClient viewer = new TestClient(server.getPort())) {
            String hostId = host.register(new byte[32]);
            viewer.register(new byte[32]);

            viewer.write(SignalingEnvelope.newBuilder()
                    .setConnectRequest(ConnectRequest.newBuilder()
                            .setTargetId(hostId)
                            .setViewerPublicKey(ByteString.copyFromUtf8("viewer-pk-test")))
                    .build());
            assertEquals(SignalingEnvelope.PayloadCase.CONNECT_FORWARD, host.read().getPayloadCase());

            host.write(SignalingEnvelope.newBuilder()
                    .setConnectReject(ConnectReject.newBuilder().setReason("用户拒绝"))
                    .build());
            SignalingEnvelope reject = viewer.read();
            assertEquals(SignalingEnvelope.PayloadCase.CONNECT_REJECT, reject.getPayloadCase());
            assertEquals("用户拒绝", reject.getConnectReject().getReason());
        }
    }

    @Test
    void 连接请求_被控端无响应超时() throws IOException {
        // connectTimeout=600ms：被控端不回，主控端应收到"被控端无响应"
        startServer(30_000, 5_000, 600);
        try (TestClient host = new TestClient(server.getPort());
             TestClient viewer = new TestClient(server.getPort())) {
            String hostId = host.register(new byte[32]);
            viewer.register(new byte[32]);

            long start = System.currentTimeMillis();
            viewer.write(SignalingEnvelope.newBuilder()
                    .setConnectRequest(ConnectRequest.newBuilder()
                            .setTargetId(hostId)
                            .setViewerPublicKey(ByteString.copyFromUtf8("viewer-pk-test")))
                    .build());
            SignalingEnvelope reject = viewer.read();
            long elapsed = System.currentTimeMillis() - start;
            assertEquals(SignalingEnvelope.PayloadCase.CONNECT_REJECT, reject.getPayloadCase());
            assertEquals("被控端无响应", reject.getConnectReject().getReason());
            assertTrue(elapsed >= 500 && elapsed < 4500, "应在约 600ms 超时，实际 " + elapsed + "ms");
        }
    }

    @Test
    void 被控端等待期间断开_主控端立即收到不在线() throws IOException {
        startServer();
        try (TestClient host = new TestClient(server.getPort());
             TestClient viewer = new TestClient(server.getPort())) {
            String hostId = host.register(new byte[32]);
            viewer.register(new byte[32]);

            viewer.write(SignalingEnvelope.newBuilder()
                    .setConnectRequest(ConnectRequest.newBuilder()
                            .setTargetId(hostId))
                    .build());
            assertEquals(SignalingEnvelope.PayloadCase.CONNECT_FORWARD, host.read().getPayloadCase());

            // 被控端断开 → 主控端应立即收到"对方不在线"（而非等超时）
            host.close();
            SignalingEnvelope reject = viewer.read();
            assertEquals(SignalingEnvelope.PayloadCase.CONNECT_REJECT, reject.getPayloadCase());
            assertEquals("对方不在线", reject.getConnectReject().getReason());
        }
    }

    private static void assertDoesNotThrowUuid(String value) {
        try {
            UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new AssertionError("relay_token 应为 UUID: " + value, e);
        }
    }
}
