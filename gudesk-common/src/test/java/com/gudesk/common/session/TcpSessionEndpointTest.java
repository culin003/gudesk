package com.gudesk.common.session;

import com.gudesk.common.crypto.SessionCipher;
import com.gudesk.common.proto.GuDeskProto.KeyEvent;
import com.gudesk.common.proto.GuDeskProto.SessionClose;
import com.gudesk.common.proto.GuDeskProto.SessionMessage;
import com.gudesk.common.proto.GuDeskProto.VideoFrame;
import com.google.protobuf.ByteString;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.InetSocketAddress;
import java.security.KeyPair;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TcpSessionEndpoint} 回环集成测试：真实 TCP loopback + Netty 通道，
 * 覆盖明文→密文管线切换、双向消息收发、心跳回显延迟采样、心跳超时断线与关闭传播。
 */
class TcpSessionEndpointTest {

    /** 测试用短心跳周期 */
    private static final long INTERVAL_MS = 150;
    private static final long TIMEOUT_MS = 600;

    private static EventLoopGroup group;

    @BeforeAll
    static void startGroup() {
        group = new NioEventLoopGroup(2, r -> {
            Thread t = new Thread(r, "endpoint-test-io");
            t.setDaemon(true);
            return t;
        });
    }

    @AfterAll
    static void stopGroup() {
        if (group != null) {
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS);
        }
    }

    // ------------------------------------------------------------------
    // 测试脚手架
    // ------------------------------------------------------------------

    /** 服务端连接消费者 */
    private interface ConnectionConsumer {
        void accept(SocketChannel channel) throws Exception;
    }

    /** 在回环地址监听临时端口，返回端口号 */
    private static int startServer(ConnectionConsumer consumer) throws Exception {
        ServerBootstrap bootstrap = new ServerBootstrap()
                .group(group)
                .channel(NioServerSocketChannel.class)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) throws Exception {
                        consumer.accept(ch);
                    }
                });
        Channel serverChannel = bootstrap.bind("127.0.0.1", 0).sync().channel();
        return ((InetSocketAddress) serverChannel.localAddress()).getPort();
    }

    private static SocketChannel connectClient(int port) throws Exception {
        Bootstrap bootstrap = new Bootstrap()
                .group(group)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.TCP_NODELAY, true)
                .handler(new io.netty.channel.ChannelInboundHandlerAdapter());
        return (SocketChannel) bootstrap.connect("127.0.0.1", port).sync().channel();
    }

    /** 双端握手材料：按主控/被控视角派生互通的 SessionCipher */
    private record Handshake(SessionCipher viewerCipher, SessionCipher hostCipher) {
    }

    private static Handshake deriveHandshake() throws Exception {
        KeyPair viewerKeys = SessionHandshake.generateKeyPair();
        KeyPair hostKeys = SessionHandshake.generateKeyPair();
        byte[] viewerPub = SessionHandshake.encodePublicKey(viewerKeys.getPublic());
        byte[] hostPub = SessionHandshake.encodePublicKey(hostKeys.getPublic());
        byte[] shared = SessionHandshake.ecdh(viewerKeys.getPrivate(),
                SessionHandshake.parsePublicKey(hostPub));
        byte[] salt = SessionHandshake.cipherSalt(viewerPub, hostPub);
        return new Handshake(SessionCipher.init(shared, salt, true),
                SessionCipher.init(shared, salt, false));
    }

    /** 记录型监听器 */
    private static final class RecordingListener implements SessionEventListener {
        final List<SessionState> states = new CopyOnWriteArrayList<>();
        final List<VideoFrame> frames = new CopyOnWriteArrayList<>();
        final List<Long> latencySamples = new CopyOnWriteArrayList<>();
        final List<String> errors = new CopyOnWriteArrayList<>();
        volatile String closeReason;

        @Override
        public void onStateChange(SessionState newState) {
            states.add(newState);
        }

        @Override
        public void onVideoFrame(VideoFrame frame) {
            frames.add(frame);
        }

        @Override
        public void onLatencySample(long oneWayMs) {
            latencySamples.add(oneWayMs);
        }

        @Override
        public void onSessionError(String message) {
            errors.add(message);
        }

        @Override
        public void onClose(String reason) {
            closeReason = reason;
        }
    }

    /**
     * 建立一对已激活密钥的端点（假主控端 + 假被控端）。
     * serverEstablished=true 时双端进入 ESTABLISHED（互发心跳）；false 时均不进入
     * （由用例自行控制状态，用于心跳超时等场景）。
     *
     * @return [0]=主控端端点，[1]=被控端端点
     */
    private static TcpSessionEndpoint[] establishPair(RecordingListener viewerListener,
                                                      RecordingListener hostListener,
                                                      Consumer<SessionMessage> viewerHandler,
                                                      Consumer<SessionMessage> hostHandler,
                                                      boolean serverEstablished) throws Exception {
        Handshake handshake = deriveHandshake();
        AtomicReference<TcpSessionEndpoint> serverRef = new AtomicReference<>();
        int port = startServer(ch -> {
            TcpSessionEndpoint serverEndpoint = new TcpSessionEndpoint(hostListener, hostHandler,
                    INTERVAL_MS, TIMEOUT_MS);
            serverEndpoint.attach(ch);
            serverEndpoint.activateCipher(handshake.hostCipher());
            if (serverEstablished) {
                serverEndpoint.setState(SessionState.ESTABLISHED);
            }
            serverRef.set(serverEndpoint);
        });

        SocketChannel clientChannel = connectClient(port);
        TcpSessionEndpoint viewerEndpoint = new TcpSessionEndpoint(viewerListener, viewerHandler,
                INTERVAL_MS, TIMEOUT_MS);
        viewerEndpoint.attach(clientChannel);
        viewerEndpoint.activateCipher(handshake.viewerCipher());
        if (serverEstablished) {
            viewerEndpoint.setState(SessionState.ESTABLISHED);
        }

        // 等待服务端 endpoint 就绪（initChannel 在 accept 后异步执行）
        long deadline = System.currentTimeMillis() + 5_000;
        while (serverRef.get() == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertNotNull(serverRef.get(), "服务端端点未就绪");
        return new TcpSessionEndpoint[]{viewerEndpoint, serverRef.get()};
    }

    // ------------------------------------------------------------------
    // 用例
    // ------------------------------------------------------------------

    @Test
    @Timeout(15)
    void 双向消息收发与视频帧路由() throws Exception {
        RecordingListener viewerListener = new RecordingListener();
        RecordingListener hostListener = new RecordingListener();
        CountDownLatch viewerGotMessage = new CountDownLatch(1);
        CountDownLatch hostGotMessage = new CountDownLatch(1);
        AtomicReference<SessionMessage> viewerReceived = new AtomicReference<>();
        AtomicReference<SessionMessage> hostReceived = new AtomicReference<>();

        TcpSessionEndpoint[] pair = establishPair(viewerListener, hostListener,
                msg -> {
                    viewerReceived.set(msg);
                    viewerGotMessage.countDown();
                },
                msg -> {
                    hostReceived.set(msg);
                    hostGotMessage.countDown();
                },
                true);
        TcpSessionEndpoint viewer = pair[0];
        TcpSessionEndpoint host = pair[1];

        // 主控 → 被控：输入事件（密文管线）
        assertTrue(viewer.send(SessionMessage.newBuilder()
                .setKey(KeyEvent.newBuilder().setKeyCode(65).setKeyChar("a").setPressed(true))
                .build()));
        assertTrue(hostGotMessage.await(5, TimeUnit.SECONDS));
        assertEquals(SessionMessage.PayloadCase.KEY, hostReceived.get().getPayloadCase());
        assertEquals(65, hostReceived.get().getKey().getKeyCode());

        // 被控 → 主控：视频帧（路由到 onVideoFrame，不进 messageHandler）
        assertTrue(host.send(SessionMessage.newBuilder()
                .setVideoFrame(VideoFrame.newBuilder()
                        .setH264Data(ByteString.copyFrom(new byte[256]))
                        .setFrameIndex(1).setKeyframe(true).setWidth(640).setHeight(480)
                        .setCaptureNs(System.nanoTime()))
                .build()));
        assertTrue(awaitCondition(5_000, () -> !viewerListener.frames.isEmpty()));
        assertEquals(640, viewerListener.frames.get(0).getWidth());
        assertEquals(480, viewerListener.frames.get(0).getHeight());
        // latch 初始计数 1，messageHandler 被调用才会归零；保持 1 说明视频帧未进入 messageHandler
        assertEquals(1, viewerGotMessage.getCount(), "视频帧不应进入 messageHandler");

        viewer.close("测试结束");
        host.close("测试结束");
    }

    @Test
    @Timeout(15)
    void 心跳回显产生延迟采样() throws Exception {
        RecordingListener viewerListener = new RecordingListener();
        RecordingListener hostListener = new RecordingListener();

        establishPair(viewerListener, hostListener, msg -> { }, msg -> { }, true);

        // 双端进入 ESTABLISHED 后按 INTERVAL_MS 互发心跳并回显，两端都应收到延迟采样
        assertTrue(awaitCondition(5_000, () -> !viewerListener.latencySamples.isEmpty()
                && !hostListener.latencySamples.isEmpty()));
        Long viewerSample = viewerListener.latencySamples.get(0);
        assertTrue(viewerSample >= 0 && viewerSample <= 1_000,
                "回环单向延迟应在 0~1000ms 内: " + viewerSample);
    }

    @Test
    @Timeout(15)
    void 心跳超时判定断线() throws Exception {
        RecordingListener viewerListener = new RecordingListener();
        RecordingListener hostListener = new RecordingListener();

        // 服务端不进入 ESTABLISHED：不发心跳、永不回显 → 客户端应在超时窗口后被判离线
        TcpSessionEndpoint[] pair = establishPair(viewerListener, hostListener,
                msg -> { }, msg -> { }, false);
        TcpSessionEndpoint viewer = pair[0];

        viewer.setState(SessionState.ESTABLISHED);
        assertTrue(awaitCondition(5_000, () -> viewer.isClosed()),
                "客户端应在心跳超时后关闭");
        assertNotNull(viewerListener.closeReason);
        assertTrue(viewerListener.closeReason.contains("心跳超时"),
                "关闭原因应为心跳超时: " + viewerListener.closeReason);
        // 心跳超时（600ms）应在 5s 内检出（验收要求 5~10s 内，本例远快于上限）
    }

    @Test
    @Timeout(15)
    void 会话关闭消息传播() throws Exception {
        RecordingListener viewerListener = new RecordingListener();
        RecordingListener hostListener = new RecordingListener();

        TcpSessionEndpoint[] pair = establishPair(viewerListener, hostListener,
                msg -> { }, msg -> { }, true);
        TcpSessionEndpoint viewer = pair[0];
        TcpSessionEndpoint host = pair[1];

        // 主控发送 SessionClose → 被控端 onClose 收到原因并关闭
        assertTrue(viewer.send(SessionMessage.newBuilder()
                .setSessionClose(SessionClose.newBuilder().setReason("主控端退出"))
                .build()));
        assertTrue(awaitCondition(5_000, () -> host.isClosed()));
        assertEquals("主控端退出", hostListener.closeReason);

        // 被控端关闭后 TCP FIN → 主控端 channelInactive → onClose
        assertTrue(awaitCondition(5_000, () -> viewer.isClosed()));
        assertNotNull(viewerListener.closeReason);
    }

    // ------------------------------------------------------------------

    /** 简易轮询等待（10ms 步进） */
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
