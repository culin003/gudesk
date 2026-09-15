package com.gudesk.common.codec;

import com.gudesk.common.proto.GuDeskProto.SignalingEnvelope;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.MessageToByteEncoder;

/**
 * 信令通道 TCP 编解码器（信令内容不加密，依赖 TLS 或后续加固）。
 *
 * <p>线帧格式：4 字节大端长度前缀 + Protobuf {@link SignalingEnvelope} 字节负载，
 * 单帧上限 1MB，超过即抛 {@link io.netty.handler.codec.TooLongFrameException} 并关闭连接。
 *
 * <p>使用方式：pipeline 中依次注册 {@link Decoder} 与 {@link Encoder}。
 */
public final class SignalingFrameCodec {

    /** 单帧最大长度：1MB */
    public static final int MAX_FRAME_LENGTH = 1024 * 1024;
    /** 长度字段字节数 */
    public static final int LENGTH_FIELD_LENGTH = 4;

    private SignalingFrameCodec() {
    }

    /**
     * 信令解码器：等价于 LengthFieldBasedFrameDecoder(1MB, 0, 4, 0, 4) + Protobuf 反序列化，
     * 出站消息类型为 {@link SignalingEnvelope}。
     */
    public static final class Decoder extends LengthFieldBasedFrameDecoder {

        public Decoder() {
            // maxFrameLength=1MB, lengthFieldOffset=0, lengthFieldLength=4, lengthAdjustment=0, initialBytesToStrip=4
            super(MAX_FRAME_LENGTH, 0, LENGTH_FIELD_LENGTH, 0, LENGTH_FIELD_LENGTH);
        }

        @Override
        protected Object decode(ChannelHandlerContext ctx, ByteBuf in) throws Exception {
            ByteBuf frame = (ByteBuf) super.decode(ctx, in);
            if (frame == null) {
                // 字节尚不足一帧
                return null;
            }
            try {
                byte[] body = new byte[frame.readableBytes()];
                frame.readBytes(body);
                return SignalingEnvelope.parseFrom(body);
            } finally {
                frame.release();
            }
        }
    }

    /**
     * 信令编码器：写出 4 字节大端长度前缀 + Protobuf 字节负载。
     */
    public static final class Encoder extends MessageToByteEncoder<SignalingEnvelope> {

        @Override
        protected void encode(ChannelHandlerContext ctx, SignalingEnvelope msg, ByteBuf out) {
            byte[] body = msg.toByteArray();
            out.writeInt(body.length);
            out.writeBytes(body);
        }
    }
}
