package com.gudesk.common.codec;

import com.gudesk.common.crypto.CryptoUtil;
import com.gudesk.common.crypto.SessionCipher;
import com.gudesk.common.proto.GuDeskProto.KeyEvent;
import com.gudesk.common.proto.GuDeskProto.MouseMoveEvent;
import com.gudesk.common.proto.GuDeskProto.SessionMessage;
import com.gudesk.common.proto.GuDeskProto.VideoFrame;
import com.google.protobuf.ByteString;
import io.netty.buffer.ByteBuf;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.TooLongFrameException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.crypto.AEADBadTagException;
import java.security.KeyPair;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionFrameCodecTest {

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

    private static SessionMessage sampleMessage() {
        return SessionMessage.newBuilder()
                .setMouseMove(MouseMoveEvent.newBuilder().setX(0.5).setY(0.25))
                .build();
    }

    @Test
    void 会话编解码加解密往返() throws Exception {
        // 模拟一条主控→被控链路：出站用主控编码器加密，入站由被控解码器解密
        EmbeddedChannel channel = new EmbeddedChannel(
                new SessionFrameCodec.Decoder(hostCipher),
                new SessionFrameCodec.Encoder(viewerCipher));

        SessionMessage message = sampleMessage();
        assertTrue(channel.writeOutbound(message));
        ByteBuf wire = channel.readOutbound();
        assertNotNull(wire);

        // 校验线帧格式：int32 长度（覆盖 flag+密文） + 1 字节标志（TCP 恒 0） + 密文
        int length = wire.readInt();
        assertTrue(length >= 1);
        assertEquals(length, wire.readableBytes());
        assertEquals(SessionFrameCodec.FLAG_TCP, wire.readByte());

        wire.readerIndex(0);
        assertTrue(channel.writeInbound(wire));
        SessionMessage decoded = channel.readInbound();
        assertEquals(message, decoded);

        // 队列已读空：finishAndReleaseAll 返回 true 表示仍有残留消息
        assertFalse(channel.finishAndReleaseAll(), "不应有残留未消费消息");
    }

    @Test
    void 反向往返_被控发主控收() throws Exception {
        // 被控端编码（hostCipher），主控端解码（viewerCipher）
        EmbeddedChannel channel = new EmbeddedChannel(
                new SessionFrameCodec.Decoder(viewerCipher),
                new SessionFrameCodec.Encoder(hostCipher));

        SessionMessage message = SessionMessage.newBuilder()
                .setVideoFrame(VideoFrame.newBuilder()
                        .setH264Data(ByteString.copyFrom(new byte[200])) // 稍大的负载
                        .setFrameIndex(100)
                        .setKeyframe(true)
                        .setWidth(1280)
                        .setHeight(720))
                .build();
        assertTrue(channel.writeOutbound(message));
        ByteBuf wire = channel.readOutbound();
        assertNotNull(wire);
        assertTrue(channel.writeInbound(wire));
        assertEquals(message, channel.readInbound());

        assertFalse(channel.finishAndReleaseAll(), "不应有残留未消费消息");
    }

    @Test
    void 密文篡改导致解码失败() throws Exception {
        EmbeddedChannel channel = new EmbeddedChannel(
                new SessionFrameCodec.Decoder(hostCipher),
                new SessionFrameCodec.Encoder(viewerCipher));

        SessionMessage message = SessionMessage.newBuilder()
                .setKey(KeyEvent.newBuilder().setKeyCode(65).setKeyChar("a").setPressed(true))
                .build();
        assertTrue(channel.writeOutbound(message));
        ByteBuf wire = channel.readOutbound();
        assertNotNull(wire);

        // 篡改密文区域最后一个字节
        int lastIdx = wire.writerIndex() - 1;
        wire.setByte(lastIdx, wire.getByte(lastIdx) ^ 0xFF);

        DecoderException ex = assertThrows(DecoderException.class, () -> channel.writeInbound(wire));
        assertInstanceOf(AEADBadTagException.class, ex.getCause());
        // 解密失败不应产出任何消息
        assertNull(channel.readInbound());
        assertFalse(channel.finishAndReleaseAll(), "不应有残留未消费消息");
    }

    @Test
    void 超长帧拒绝() {
        EmbeddedChannel channel = new EmbeddedChannel(new SessionFrameCodec.Decoder(hostCipher));
        ByteBuf header = io.netty.buffer.Unpooled.buffer();
        header.writeInt(2 * 1024 * 1024); // > 1MB
        assertThrows(TooLongFrameException.class, () -> channel.writeInbound(header));
        assertNull(channel.readInbound());
        assertFalse(channel.finishAndReleaseAll(), "不应有残留未消费消息");
    }
}
