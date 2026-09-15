package com.gudesk.common.codec;

import com.gudesk.common.crypto.CryptoUtil;
import com.gudesk.common.crypto.SessionCipher;
import com.gudesk.common.proto.GuDeskProto.KeyEvent;
import com.gudesk.common.proto.GuDeskProto.SessionMessage;
import com.gudesk.common.proto.GuDeskProto.VideoFrame;
import com.google.protobuf.ByteString;
import io.netty.channel.socket.DatagramPacket;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.security.KeyPair;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionPacketCodecTest {

    private static SessionCipher viewerCipher;
    private static SessionCipher hostCipher;

    @BeforeAll
    static void initCiphers() throws Exception {
        KeyPair viewer = CryptoUtil.generateX25519KeyPair();
        KeyPair host = CryptoUtil.generateX25519KeyPair();
        byte[] shared = CryptoUtil.ecdh(viewer.getPrivate(), host.getPublic());
        byte[] salt = CryptoUtil.sha256(concat(viewer.getPublic().getEncoded(), host.getPublic().getEncoded()));
        viewerCipher = SessionCipher.init(shared, salt, true);
        hostCipher = SessionCipher.init(shared, salt, false);
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    @Test
    void UDP包可靠消息往返() throws Exception {
        InetSocketAddress recipient = new InetSocketAddress(InetAddress.getLoopbackAddress(), 50000);
        // 输入事件属可靠消息
        SessionMessage message = SessionMessage.newBuilder()
                .setKey(KeyEvent.newBuilder().setKeyCode(65).setKeyChar("A").setPressed(true))
                .build();

        DatagramPacket packet = SessionPacketCodec.encode(message, viewerCipher, true, recipient);
        assertEquals(recipient, packet.recipient());

        SessionPacketCodec.DecodedPacket decoded = SessionPacketCodec.decode(packet, hostCipher);
        assertTrue(decoded.reliable());
        assertEquals(SessionPacketCodec.FLAG_RELIABLE, decoded.flag());
        assertEquals(message, decoded.message());
        // packet 已被消费释放
        assertEquals(0, packet.refCnt());
    }

    @Test
    void UDP包普通消息标志为0() throws Exception {
        InetSocketAddress recipient = new InetSocketAddress(InetAddress.getLoopbackAddress(), 50000);
        // 视频帧属可容忍丢弃的普通消息
        SessionMessage message = SessionMessage.newBuilder()
                .setVideoFrame(VideoFrame.newBuilder()
                        .setH264Data(ByteString.copyFrom(new byte[]{1, 2, 3}))
                        .setFrameIndex(9)
                        .setKeyframe(false)
                        .setWidth(640)
                        .setHeight(480))
                .build();

        DatagramPacket packet = SessionPacketCodec.encode(message, viewerCipher, false, recipient);
        SessionPacketCodec.DecodedPacket decoded = SessionPacketCodec.decode(packet, hostCipher);
        assertFalse(decoded.reliable());
        assertEquals(SessionPacketCodec.FLAG_BEST_EFFORT, decoded.flag());
        assertEquals(message, decoded.message());
    }

    @Test
    void 密文长度大于明文() throws Exception {
        InetSocketAddress recipient = new InetSocketAddress(InetAddress.getLoopbackAddress(), 50000);
        byte[] raw = new byte[64];
        new java.security.SecureRandom().nextBytes(raw);
        SessionMessage message = SessionMessage.newBuilder()
                .setVideoFrame(VideoFrame.newBuilder().setH264Data(ByteString.copyFrom(raw)))
                .build();

        DatagramPacket packet = SessionPacketCodec.encode(message, viewerCipher, true, recipient);
        // 密文 = flag(1) + nonce(12) + 明文 + tag(16)
        assertTrue(packet.content().readableBytes() > message.getSerializedSize());
        assertEquals(1 + 12 + message.getSerializedSize() + 16, packet.content().readableBytes());

        SessionPacketCodec.DecodedPacket decoded = SessionPacketCodec.decode(packet, hostCipher);
        assertArrayEquals(raw, decoded.message().getVideoFrame().getH264Data().toByteArray());
    }
}
