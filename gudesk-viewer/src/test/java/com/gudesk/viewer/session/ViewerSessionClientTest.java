package com.gudesk.viewer.session;

import com.gudesk.common.crypto.SessionCipher;
import com.gudesk.common.proto.GuDeskProto.SessionMessage;
import com.gudesk.common.proto.GuDeskProto.SessionNegotiate;
import com.gudesk.common.proto.GuDeskProto.SessionNegotiateAck;
import com.gudesk.common.proto.GuDeskProto.VideoFrame;
import com.gudesk.common.session.SessionHandshake;
import com.gudesk.common.session.SessionState;
import com.gudesk.common.session.TcpSessionEndpoint;
import com.gudesk.common.spi.AdapterCapabilities;
import com.gudesk.common.spi.AdapterConfig;
import com.gudesk.common.spi.AdapterException;
import com.gudesk.common.spi.NativeFrame;
import com.gudesk.common.spi.VideoDecoder;
import com.google.protobuf.ByteString;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.security.KeyPair;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * {@link ViewerSessionClient} 集成测试：loopback 假被控端（完整两轮协商协议实现），
 * 覆盖：协商成功+视频帧接收解码、密码错误失败、主动断开、目标解析。
 */
class ViewerSessionClientTest {

    private static final String PASSWORD = "246810";

    private static final EventLoopGroup GROUP = new NioEventLoopGroup(2, r -> {
        Thread t = new Thread(r, "viewer-client-test-io");
        t.setDaemon(true);
        return t;
    });

    @AfterAll
    static void stopGroup() {
        GROUP.shutdownGracefully(0, 1, TimeUnit.SECONDS);
    }

    // ------------------------------------------------------------------
    // 用例
    // ------------------------------------------------------------------

    @Test
    @Timeout(30)
    void 协商成功并接收解码视频帧() throws Exception {
        try (FakeHost host = FakeHost.start(PASSWORD)) {
            CountDownLatch connected = new CountDownLatch(1);
            CountDownLatch frameDecoded = new CountDownLatch(2);
            AtomicReference<int[]> frameSize = new AtomicReference<>();
            AtomicReference<String> failure = new AtomicReference<>();
            ViewerSessionClient client = new ViewerSessionClient(new StubDecoder(), null,
                    new ViewerSessionClient.Listener() {
                        @Override
                        public void onConnected() {
                            connected.countDown();
                        }

                        @Override
                        public void onConnectFailed(String reason) {
                            failure.set(reason);
                            connected.countDown();
                        }

                        @Override
                        public void onFrameDecoded(long captureToDecodeMs, int width, int height) {
                            frameSize.set(new int[]{width, height});
                            frameDecoded.countDown();
                        }

                        @Override
                        public void onLatency(long oneWayMs) {
                        }

                        @Override
                        public void onClosed(String reason) {
                        }
                    });

            client.connect("127.0.0.1", host.port(), PASSWORD);
            assertTrue(connected.await(10, TimeUnit.SECONDS), "应收到 onConnected");
            assertNull(failure.get(), "不应连接失败: " + failure.get());

            // 假被控端 ESTABLISHED 后自动下发 2 帧 640x480 视频 → 解码直通回调
            assertTrue(frameDecoded.await(5, TimeUnit.SECONDS), "应收到并解码 2 帧视频");
            assertArrayEquals(new int[]{640, 480}, frameSize.get());

            client.disconnect();
            assertTrue(awaitCondition(5_000, host::isEndpointClosed), "被控端应收到断开");
        }
    }

    @Test
    @Timeout(30)
    void 密码错误连接失败() throws Exception {
        try (FakeHost host = FakeHost.start(PASSWORD)) {
            CountDownLatch failed = new CountDownLatch(1);
            AtomicReference<String> reason = new AtomicReference<>();
            ViewerSessionClient client = new ViewerSessionClient(new StubDecoder(), null,
                    new ViewerSessionClient.Listener() {
                        @Override
                        public void onConnected() {
                            fail("密码错误不应建立会话");
                        }

                        @Override
                        public void onConnectFailed(String r) {
                            reason.set(r);
                            failed.countDown();
                        }

                        @Override
                        public void onFrameDecoded(long captureToDecodeMs, int width, int height) {
                        }

                        @Override
                        public void onLatency(long oneWayMs) {
                        }

                        @Override
                        public void onClosed(String r) {
                        }
                    });

            client.connect("127.0.0.1", host.port(), "000000");
            assertTrue(failed.await(10, TimeUnit.SECONDS), "应收到 onConnectFailed");
            assertEquals("密码错误", reason.get());
        }
    }

    @Test
    @Timeout(30)
    void 主动断开触发onClosed() throws Exception {
        try (FakeHost host = FakeHost.start(PASSWORD)) {
            CountDownLatch connected = new CountDownLatch(1);
            CountDownLatch closed = new CountDownLatch(1);
            AtomicReference<String> closeReason = new AtomicReference<>();
            ViewerSessionClient client = new ViewerSessionClient(new StubDecoder(), null,
                    new ViewerSessionClient.Listener() {
                        @Override
                        public void onConnected() {
                            connected.countDown();
                        }

                        @Override
                        public void onConnectFailed(String r) {
                            fail("连接不应失败: " + r);
                        }

                        @Override
                        public void onFrameDecoded(long captureToDecodeMs, int width, int height) {
                        }

                        @Override
                        public void onLatency(long oneWayMs) {
                        }

                        @Override
                        public void onClosed(String r) {
                            closeReason.set(r);
                            closed.countDown();
                        }
                    });

            client.connect("127.0.0.1", host.port(), PASSWORD);
            assertTrue(connected.await(10, TimeUnit.SECONDS));

            client.disconnect();
            assertTrue(closed.await(5, TimeUnit.SECONDS), "主动断开应回调 onClosed");
            assertEquals("主控端主动断开", closeReason.get());
            assertFalse(client.isConnected());
            assertTrue(awaitCondition(5_000, host::isEndpointClosed), "被控端应收到断开");
        }
    }

    @Test
    void 连接目标解析() {
        assertArrayEquals(new String[]{"127.0.0.1", "48900"},
                SessionUiConnector.parseTarget("127.0.0.1:48900"));
        assertArrayEquals(new String[]{"host.example.com", "1234"},
                SessionUiConnector.parseTarget("host.example.com:1234"));
        assertArrayEquals(new String[]{"id", "123456789"},
                SessionUiConnector.parseTarget("123456789"));
        assertArrayEquals(new String[]{"id", "42"}, SessionUiConnector.parseTarget(" 42 "));
        assertNull(SessionUiConnector.parseTarget(null), "null 目标");
        assertNull(SessionUiConnector.parseTarget(""), "空目标");
        assertNull(SessionUiConnector.parseTarget("abc"), "无端口");
        assertNull(SessionUiConnector.parseTarget("host:abc"), "端口非数字");
        assertNull(SessionUiConnector.parseTarget("host:0"), "端口 0 非法");
        assertNull(SessionUiConnector.parseTarget("host:70000"), "端口超范围");
        assertNull(SessionUiConnector.parseTarget("host:1234:5678"), "多余冒号");
    }

    // ------------------------------------------------------------------
    // 假被控端：完整两轮协商 + ESTABLISHED 后下发 2 帧视频
    // ------------------------------------------------------------------

    /** 假被控端句柄：持有 server channel 与（首个连接的）endpoint 引用 */
    private static final class FakeHost implements AutoCloseable {

        private final Channel serverChannel;
        private final int port;
        private final AtomicReference<TcpSessionEndpoint> endpointRef;

        private FakeHost(Channel serverChannel, int port,
                         AtomicReference<TcpSessionEndpoint> endpointRef) {
            this.serverChannel = serverChannel;
            this.port = port;
            this.endpointRef = endpointRef;
        }

        /** 在回环地址监听临时端口，假被控端接受单个连接并执行两轮协商 */
        static FakeHost start(String password) throws Exception {
            KeyPair hostKeys = SessionHandshake.generateKeyPair();
            byte[] hostPub = SessionHandshake.encodePublicKey(hostKeys.getPublic());
            AtomicReference<TcpSessionEndpoint> endpointRef = new AtomicReference<>();
            ServerBootstrap bootstrap = new ServerBootstrap()
                    .group(GROUP)
                    .channel(NioServerSocketChannel.class)
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            TcpSessionEndpoint ep = new TcpSessionEndpoint(null,
                                    msg -> onMessage(msg, endpointRef, password, hostKeys, hostPub));
                            endpointRef.set(ep);
                            ep.attach(ch);
                        }
                    });
            Channel serverChannel = bootstrap.bind("127.0.0.1", 0).sync().channel();
            int port = ((InetSocketAddress) serverChannel.localAddress()).getPort();
            return new FakeHost(serverChannel, port, endpointRef);
        }

        int port() {
            return port;
        }

        boolean isEndpointClosed() {
            TcpSessionEndpoint ep = endpointRef.get();
            return ep == null || ep.isClosed();
        }

        /** 假被控端协议：第一轮交换公钥+挑战，第二轮验密码 */
        private static void onMessage(SessionMessage msg, AtomicReference<TcpSessionEndpoint> ref,
                                      String password, KeyPair hostKeys, byte[] hostPub) {
            if (msg.getPayloadCase() != SessionMessage.PayloadCase.NEGOTIATE) {
                return;
            }
            TcpSessionEndpoint ep = ref.get();
            SessionNegotiate negotiate = msg.getNegotiate();
            try {
                if (negotiate.getPasswordProof().isEmpty()) {
                    byte[] viewerPub = negotiate.getEphemeralPublicKey().toByteArray();
                    byte[] shared = SessionHandshake.ecdh(hostKeys.getPrivate(),
                            SessionHandshake.parsePublicKey(viewerPub));
                    byte[] salt = SessionHandshake.cipherSalt(viewerPub, hostPub);
                    ep.send(SessionMessage.newBuilder()
                            .setNegotiateAck(SessionNegotiateAck.newBuilder()
                                    .setEphemeralPublicKey(ByteString.copyFrom(hostPub))
                                    .setAuthorized(false)
                                    .setReason("CHALLENGE"))
                            .build());
                    ep.activateCipher(SessionCipher.init(shared, salt, false));
                } else if (password.equals(negotiate.getPasswordProof())) {
                    ep.send(SessionMessage.newBuilder()
                            .setNegotiateAck(SessionNegotiateAck.newBuilder().setAuthorized(true))
                            .build());
                    ep.setState(SessionState.ESTABLISHED);
                    // 下发 2 帧视频（假 H.264 负载，StubDecoder 直通）
                    for (int i = 0; i < 2; i++) {
                        ep.send(SessionMessage.newBuilder()
                                .setVideoFrame(VideoFrame.newBuilder()
                                        .setH264Data(ByteString.copyFrom(
                                                new byte[]{0, 0, 0, 1, 0x67, (byte) i}))
                                        .setFrameIndex(i + 1)
                                        .setKeyframe(i == 0)
                                        .setWidth(640).setHeight(480)
                                        .setCaptureNs(System.nanoTime()))
                                .build());
                    }
                } else {
                    ep.send(SessionMessage.newBuilder()
                            .setNegotiateAck(SessionNegotiateAck.newBuilder()
                                    .setAuthorized(false)
                                    .setReason("密码错误"))
                            .build());
                    ep.close("密码验证失败");
                }
            } catch (Exception ignored) {
                // 测试脚手架异常不处理
            }
        }

        @Override
        public void close() {
            TcpSessionEndpoint ep = endpointRef.get();
            if (ep != null) {
                ep.close("测试结束");
            }
            serverChannel.close();
        }
    }

    // ------------------------------------------------------------------

    /** 直通解码器：不真解码，按输入帧尺寸输出 BGRA 帧（测试协商与统计链路） */
    private static final class StubDecoder implements VideoDecoder {

        @Override
        public void decode(NativeFrame encoded, Consumer<NativeFrame> decodedOut) {
            int w = Math.max(2, encoded.width());
            int h = Math.max(2, encoded.height());
            ByteBuffer buffer = ByteBuffer.allocateDirect(w * h * 4);
            decodedOut.accept(new NativeFrame(buffer, w, h, "BGRA", System.nanoTime()));
        }

        @Override
        public void init(AdapterConfig config) throws AdapterException {
        }

        @Override
        public void start() throws AdapterException {
        }

        @Override
        public void stop() throws AdapterException {
        }

        @Override
        public AdapterCapabilities capabilities() {
            return AdapterCapabilities.builder()
                    .maxWidth(1920).maxHeight(1080).maxFps(60)
                    .addSupportedPixelFormat("H264")
                    .addSupportedPixelFormat("BGRA")
                    .build();
        }
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
