package com.gudesk.common.session;

import com.gudesk.common.crypto.SessionCipher;
import com.gudesk.common.proto.GuDeskProto.KeyEvent;
import com.gudesk.common.proto.GuDeskProto.SessionMessage;
import com.gudesk.common.proto.GuDeskProto.SessionNegotiate;
import com.gudesk.common.proto.GuDeskProto.VideoFrame;
import com.google.protobuf.ByteString;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.security.KeyPair;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link UdpSessionEndpoint} 回环双端测试：真实 UDP loopback + 打洞式双 socket，
 * 覆盖明文可靠通道有序投递、乱序缓冲、重传与去重、密文切换、视频帧路由、
 * 大帧分片重组、帧序跳变触发 KeyFrameRequest、心跳延迟采样与重传失败断开。
 */
class UdpSessionEndpointTest {

    /** 测试用短心跳周期 */
    private static final long HEARTBEAT_INTERVAL_MS = 150;
    private static final long HEARTBEAT_TIMEOUT_MS = 800;

    /** 每用例结束统一关闭（含未 start 的端点，避免 socket 泄漏） */
    private final List<UdpSessionEndpoint> endpoints = new CopyOnWriteArrayList<>();

    @AfterEach
    void closeEndpoints() {
        for (UdpSessionEndpoint endpoint : endpoints) {
            endpoint.close("测试结束");
        }
    }

    // ------------------------------------------------------------------
    // 测试脚手架
    // ------------------------------------------------------------------

    /** 记录型监听器 */
    private static final class RecordingListener implements SessionEventListener {
        final List<SessionState> states = new CopyOnWriteArrayList<>();
        final List<VideoFrame> frames = new CopyOnWriteArrayList<>();
        final List<Long> latencySamples = new CopyOnWriteArrayList<>();
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
        }

        @Override
        public void onClose(String reason) {
            closeReason = reason;
        }
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

    /**
     * 建立一对 UDP 端点（回环双 socket 互指为对端，模拟打洞成功后的状态）。
     *
     * @param startHost true=双端都启动接收；false=被控端暂不启动（重传/断线用例）
     */
    private EndpointPair establishPair(RecordingListener viewerListener,
                                       Consumer<SessionMessage> viewerHandler,
                                       RecordingListener hostListener,
                                       Consumer<SessionMessage> hostHandler,
                                       boolean startHost) throws Exception {
        DatagramSocket viewerSocket = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0));
        DatagramSocket hostSocket = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0));
        InetSocketAddress viewerAddr = new InetSocketAddress("127.0.0.1", viewerSocket.getLocalPort());
        InetSocketAddress hostAddr = new InetSocketAddress("127.0.0.1", hostSocket.getLocalPort());
        UdpSessionEndpoint viewer = new UdpSessionEndpoint(viewerListener, viewerHandler,
                viewerSocket, hostAddr, HEARTBEAT_INTERVAL_MS, HEARTBEAT_TIMEOUT_MS);
        UdpSessionEndpoint host = new UdpSessionEndpoint(hostListener, hostHandler,
                hostSocket, viewerAddr, HEARTBEAT_INTERVAL_MS, HEARTBEAT_TIMEOUT_MS);
        endpoints.add(viewer);
        endpoints.add(host);
        viewer.start();
        if (startHost) {
            host.start();
        }
        return new EndpointPair(viewer, host, viewerSocket, hostSocket);
    }

    /** 双端点与双 socket（乱序注入需复用端点 socket 以匹配来源过滤） */
    private record EndpointPair(UdpSessionEndpoint viewer, UdpSessionEndpoint host,
                                DatagramSocket viewerSocket, DatagramSocket hostSocket) {
    }

    private static SessionMessage keyEvent(int keyCode) {
        return SessionMessage.newBuilder()
                .setKey(KeyEvent.newBuilder().setKeyCode(keyCode).setKeyChar("k").setPressed(true))
                .build();
    }

    private static SessionMessage videoFrame(int frameIndex, int payloadSize) {
        return SessionMessage.newBuilder()
                .setVideoFrame(VideoFrame.newBuilder()
                        .setH264Data(ByteString.copyFrom(new byte[payloadSize]))
                        .setFrameIndex(frameIndex).setKeyframe(frameIndex == 1)
                        .setWidth(640).setHeight(480)
                        .setCaptureNs(System.nanoTime()))
                .build();
    }

    // ------------------------------------------------------------------
    // 用例
    // ------------------------------------------------------------------

    @Test
    @Timeout(15)
    void 明文可靠消息按序到达() throws Exception {
        RecordingListener viewerListener = new RecordingListener();
        RecordingListener hostListener = new RecordingListener();
        List<Integer> hostReceived = new CopyOnWriteArrayList<>();
        List<Integer> viewerReceived = new CopyOnWriteArrayList<>();
        EndpointPair pair = establishPair(viewerListener,
                msg -> viewerReceived.add(msg.getKey().getKeyCode()),
                hostListener,
                msg -> hostReceived.add(msg.getKey().getKeyCode()),
                true);
        UdpSessionEndpoint viewer = pair.viewer();
        UdpSessionEndpoint host = pair.host();

        // 主控端连发 5 条（明文可靠通道，协商阶段形态）
        for (int i = 1; i <= 5; i++) {
            assertTrue(viewer.send(keyEvent(i), true));
        }
        assertTrue(awaitCondition(5_000, () -> hostReceived.size() == 5));
        for (int i = 0; i < 5; i++) {
            assertEquals(i + 1, hostReceived.get(i), "可靠消息应按 seq 有序投递");
        }

        // 反向一条
        assertTrue(host.send(keyEvent(99), true));
        assertTrue(awaitCondition(5_000, () -> !viewerReceived.isEmpty()));
        assertEquals(99, viewerReceived.get(0));

        // 可靠消息超单包上限应拒绝发送（构造 2KB 明文即超 1100 上限）
        assertFalse(viewer.send(SessionMessage.newBuilder()
                        .setNegotiate(SessionNegotiate.newBuilder()
                                .setEphemeralPublicKey(ByteString.copyFrom(new byte[2_000])))
                        .build(), true),
                "超限可靠消息应被拒绝");
    }

    @Test
    @Timeout(15)
    void 乱序包经缓冲后依序投递() throws Exception {
        RecordingListener viewerListener = new RecordingListener();
        RecordingListener hostListener = new RecordingListener();
        List<Integer> hostReceived = new CopyOnWriteArrayList<>();
        EndpointPair pair = establishPair(viewerListener, msg -> { },
                hostListener, msg -> hostReceived.add(msg.getKey().getKeyCode()), true);

        // 绕过端点 API，从主控端 socket 原始注入乱序可靠包：先 seq=2 再 seq=1
        // （目标 = viewer 端点的 peer，即 host 地址；来源 = viewer socket 以通过来源过滤）
        sendRawPlain(pair.viewerSocket(), pair.viewer().peerAddress(), 2, keyEvent(2));
        sendRawPlain(pair.viewerSocket(), pair.viewer().peerAddress(), 1, keyEvent(1));

        assertTrue(awaitCondition(5_000, () -> hostReceived.size() == 2));
        assertEquals(1, hostReceived.get(0), "seq=1 应先投递（缓冲 seq=2 等待补洞）");
        assertEquals(2, hostReceived.get(1), "seq=2 应随后依序投递");
    }

    @Test
    @Timeout(15)
    void 接收端延迟启动时重传生效且去重() throws Exception {
        RecordingListener viewerListener = new RecordingListener();
        RecordingListener hostListener = new RecordingListener();
        List<Integer> hostReceived = new CopyOnWriteArrayList<>();
        // 被控端暂不 start：无 ACK → 主控端 500ms 起重传
        EndpointPair pair = establishPair(viewerListener, msg -> { },
                hostListener, msg -> hostReceived.add(msg.getKey().getKeyCode()), false);
        UdpSessionEndpoint viewer = pair.viewer();
        UdpSessionEndpoint host = pair.host();

        assertTrue(viewer.send(keyEvent(7), true));
        // 等待至少一次重传发生后（500ms 重传间隔）再启动接收端
        Thread.sleep(700);
        host.start();

        assertTrue(awaitCondition(5_000, () -> !hostReceived.isEmpty()),
                "重传包应被延迟启动的接收端收到");
        // 多份重复 seq 包应被去重：恰投递一次
        Thread.sleep(600);
        assertEquals(1, hostReceived.size(), "重复包应被去重，恰投递一次");
        assertEquals(7, hostReceived.get(0));
    }

    @Test
    @Timeout(15)
    void 密文可靠消息与视频帧路由() throws Exception {
        RecordingListener viewerListener = new RecordingListener();
        RecordingListener hostListener = new RecordingListener();
        List<Integer> hostReceived = new CopyOnWriteArrayList<>();
        EndpointPair pair = establishPair(viewerListener, msg -> { },
                hostListener, msg -> hostReceived.add(msg.getKey().getKeyCode()), true);
        Handshake handshake = deriveHandshake();
        pair.viewer().activateCipher(handshake.viewerCipher());
        pair.host().activateCipher(handshake.hostCipher());

        // 主控 → 被控：密文可靠消息
        assertTrue(pair.viewer().send(keyEvent(42), true));
        assertTrue(awaitCondition(5_000, () -> !hostReceived.isEmpty()));
        assertEquals(42, hostReceived.get(0));

        // 被控 → 主控：小视频帧（整包 best-effort）路由到 onVideoFrame
        assertTrue(pair.host().send(videoFrame(1, 256), false));
        assertTrue(awaitCondition(5_000, () -> !viewerListener.frames.isEmpty()));
        assertEquals(640, viewerListener.frames.get(0).getWidth());
    }

    @Test
    @Timeout(15)
    void 大帧分片重组() throws Exception {
        RecordingListener viewerListener = new RecordingListener();
        RecordingListener hostListener = new RecordingListener();
        EndpointPair pair = establishPair(viewerListener, msg -> { },
                hostListener, msg -> { }, true);
        Handshake handshake = deriveHandshake();
        pair.viewer().activateCipher(handshake.viewerCipher());
        pair.host().activateCipher(handshake.hostCipher());

        // 3000B 帧 → 密文 >1100 → 3 片 → 接收端重组为完整帧
        assertTrue(pair.host().send(videoFrame(1, 3_000), false));
        assertTrue(awaitCondition(5_000, () -> !viewerListener.frames.isEmpty()),
                "分片应收齐重组并投递");
        VideoFrame frame = viewerListener.frames.get(0);
        assertEquals(3_000, frame.getH264Data().size(), "重组帧载荷应完整");
        assertEquals(1, frame.getFrameIndex());
    }

    @Test
    @Timeout(15)
    void 帧序跳变触发关键帧请求() throws Exception {
        RecordingListener viewerListener = new RecordingListener();
        RecordingListener hostListener = new RecordingListener();
        List<SessionMessage.PayloadCase> hostReceived = new CopyOnWriteArrayList<>();
        EndpointPair pair = establishPair(viewerListener, msg -> { },
                hostListener, msg -> hostReceived.add(msg.getPayloadCase()), true);
        Handshake handshake = deriveHandshake();
        pair.viewer().activateCipher(handshake.viewerCipher());
        pair.host().activateCipher(handshake.hostCipher());

        // 正常帧 1 → 帧序跳变到 5（中间帧丢失）→ 接收端应回发 KeyFrameRequest
        assertTrue(pair.host().send(videoFrame(1, 128), false));
        assertTrue(awaitCondition(5_000, () -> viewerListener.frames.size() == 1));
        assertTrue(pair.host().send(videoFrame(5, 128), false));
        assertTrue(awaitCondition(5_000, () -> viewerListener.frames.size() == 2));
        assertTrue(awaitCondition(5_000, () ->
                        hostReceived.contains(SessionMessage.PayloadCase.KEYFRAME_REQUEST)),
                "帧序跳变应触发接收端回发 KeyFrameRequest");
    }

    @Test
    @Timeout(15)
    void 心跳互发产生延迟采样() throws Exception {
        RecordingListener viewerListener = new RecordingListener();
        RecordingListener hostListener = new RecordingListener();
        EndpointPair pair = establishPair(viewerListener, msg -> { },
                hostListener, msg -> { }, true);

        pair.viewer().setState(SessionState.ESTABLISHED);
        pair.host().setState(SessionState.ESTABLISHED);
        assertTrue(awaitCondition(5_000, () -> !viewerListener.latencySamples.isEmpty()
                        && !hostListener.latencySamples.isEmpty()),
                "双端 ESTABLISHED 后应互发心跳并回显采样");
        long sample = viewerListener.latencySamples.get(0);
        assertTrue(sample >= 0 && sample <= 1_000, "回环单向延迟应在 0~1000ms 内: " + sample);
    }

    @Test
    @Timeout(15)
    void 重传穷尽判定会话断开() throws Exception {
        RecordingListener viewerListener = new RecordingListener();
        RecordingListener hostListener = new RecordingListener();
        // 被控端永不启动：主控端可靠消息 3 次重传后应判定断开
        EndpointPair pair = establishPair(viewerListener, msg -> { },
                hostListener, msg -> { }, false);
        UdpSessionEndpoint viewer = pair.viewer();

        assertTrue(viewer.send(keyEvent(1), true));
        assertTrue(awaitCondition(6_000, viewer::isClosed),
                "重传穷尽后应判定会话断开");
        assertNotNull(viewerListener.closeReason);
        assertTrue(viewerListener.closeReason.contains("重传"),
                "关闭原因应为重传超时: " + viewerListener.closeReason);
    }

    // ------------------------------------------------------------------

    /** 从指定 socket 原始注入明文可靠包（type=PLAIN + seq + protobuf），模拟乱序 */
    private static void sendRawPlain(DatagramSocket from, InetSocketAddress to, long seq,
                                     SessionMessage message) throws Exception {
        byte[] payload = message.toByteArray();
        byte[] wire = new byte[9 + payload.length];
        wire[0] = UdpSessionEndpoint.TYPE_PLAIN;
        for (int i = 0; i < 8; i++) {
            wire[1 + i] = (byte) (seq >>> (8 * (7 - i)));
        }
        System.arraycopy(payload, 0, wire, 9, payload.length);
        from.send(new DatagramPacket(wire, wire.length, to));
    }

    /** 简易轮询等待（10ms 步进） */
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
}
