package com.gudesk.common.codec;

import com.gudesk.common.proto.GuDeskProto.Heartbeat;
import com.gudesk.common.proto.GuDeskProto.RegisterRequest;
import com.gudesk.common.proto.GuDeskProto.SignalingEnvelope;
import com.google.protobuf.ByteString;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.TooLongFrameException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SignalingFrameCodecTest {

    @Test
    void 信令编解码往返() {
        EmbeddedChannel channel = new EmbeddedChannel(
                new SignalingFrameCodec.Decoder(), new SignalingFrameCodec.Encoder());

        SignalingEnvelope envelope = SignalingEnvelope.newBuilder()
                .setRegisterRequest(RegisterRequest.newBuilder()
                        .setId("host-001")
                        .setPublicKey(ByteString.copyFromUtf8("public-key-bytes")))
                .build();

        assertTrue(channel.writeOutbound(envelope));
        ByteBuf wire = channel.readOutbound();
        assertNotNull(wire);
        // 长度前缀应等于 protobuf 负载长度
        int length = wire.readInt();
        assertEquals(envelope.getSerializedSize(), length);

        // 写回入站通道解码，应得到等价对象
        wire.readerIndex(0);
        assertTrue(channel.writeInbound(wire));
        SignalingEnvelope decoded = channel.readInbound();
        assertEquals(envelope, decoded);

        // 多帧连续编解码
        SignalingEnvelope heartbeat = SignalingEnvelope.newBuilder()
                .setHeartbeat(Heartbeat.newBuilder().setTimestamp(System.currentTimeMillis()))
                .build();
        assertTrue(channel.writeOutbound(heartbeat));
        ByteBuf wire2 = channel.readOutbound();
        assertTrue(channel.writeInbound(wire2));
        assertEquals(heartbeat, channel.readInbound());

        // 队列已读空：finishAndReleaseAll 返回 true 表示仍有残留消息
        assertFalse(channel.finishAndReleaseAll(), "不应有残留未消费消息");
    }

    @Test
    void 半包粘包处理() {
        EmbeddedChannel channel = new EmbeddedChannel(new SignalingFrameCodec.Decoder());
        SignalingEnvelope envelope = SignalingEnvelope.newBuilder()
                .setRegisterRequest(RegisterRequest.newBuilder().setId("host-002").build())
                .build();

        ByteBuf frame = Unpooled.buffer();
        frame.writeInt(envelope.getSerializedSize());
        frame.writeBytes(envelope.toByteArray());

        // 分两次写入：前 3 字节 + 剩余部分
        ByteBuf part1 = frame.retainedSlice(0, 3);
        ByteBuf part2 = frame.retainedSlice(3, frame.readableBytes() - 3);
        assertFalse(channel.writeInbound(part1));
        assertTrue(channel.writeInbound(part2));
        assertEquals(envelope, channel.readInbound());

        frame.release();
        assertFalse(channel.finishAndReleaseAll(), "不应有残留未消费消息");
    }

    @Test
    void 超长帧拒绝() {
        EmbeddedChannel channel = new EmbeddedChannel(new SignalingFrameCodec.Decoder());
        // 声明超过 1MB 的帧长度
        ByteBuf header = Unpooled.buffer();
        header.writeInt(2 * 1024 * 1024);
        assertThrows(TooLongFrameException.class, () -> channel.writeInbound(header));
        // 解码失败不应产出任何消息
        assertNull(channel.readInbound());
        assertFalse(channel.finishAndReleaseAll(), "不应有残留未消费消息");
    }
}
