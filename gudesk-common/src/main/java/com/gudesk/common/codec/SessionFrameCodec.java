package com.gudesk.common.codec;

import com.gudesk.common.crypto.SessionCipher;
import com.gudesk.common.proto.GuDeskProto.SessionMessage;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.MessageToByteEncoder;

/**
 * 会话数据通道 TCP 编解码器：负载为 {@link SessionMessage} 的 AES-GCM 密文（由
 * {@link SessionCipher} 完成加解密）。
 *
 * <p>线帧格式：int32 大端长度 + 1 字节可靠性标志 + AES-GCM 密文，其中长度字段覆盖
 * 标志位与密文（即长度 = 帧内全部字节数）。
 *
 * <p>可靠性标志取值：0=普通消息（可容忍丢弃，如视频帧），1=可靠消息（需要 ACK，
 * UDP 场景使用）。TCP 由传输层保证可靠，恒写 0，解码端读取后忽略。
 *
 * <p>单帧上限 1MB，超长帧抛 {@link io.netty.handler.codec.TooLongFrameException}；
 * 密文被篡改导致 GCM 认证失败时异常向上传播并由 Netty 关闭连接。
 *
 * <p>使用方式：本端 pipeline 依次注册 {@link Decoder} 与 {@link Encoder}，
 * 两者共享同一个 {@link SessionCipher} 实例（收发方向密钥已内含于其中）。
 */
public final class SessionFrameCodec {

    /** 单帧最大长度：1MB */
    public static final int MAX_FRAME_LENGTH = 1024 * 1024;
    /** 长度字段字节数 */
    public static final int LENGTH_FIELD_LENGTH = 4;
    /** 可靠性标志字节数 */
    public static final int FLAG_LENGTH = 1;
    /** TCP 链路可靠性标志恒为 0 */
    public static final byte FLAG_TCP = 0;

    private SessionFrameCodec() {
    }

    /**
     * 会话解码器：长度字段拆帧 + 标志位剥离 + AES-GCM 解密，出站消息类型为 {@link SessionMessage}。
     */
    public static final class Decoder extends LengthFieldBasedFrameDecoder {

        private final SessionCipher cipher;

        public Decoder(SessionCipher cipher) {
            // maxFrameLength=1MB, lengthFieldOffset=0, lengthFieldLength=4, lengthAdjustment=0, initialBytesToStrip=4
            super(MAX_FRAME_LENGTH, 0, LENGTH_FIELD_LENGTH, 0, LENGTH_FIELD_LENGTH);
            this.cipher = cipher;
        }

        @Override
        protected Object decode(ChannelHandlerContext ctx, ByteBuf in) throws Exception {
            ByteBuf frame = (ByteBuf) super.decode(ctx, in);
            if (frame == null) {
                // 字节尚不足一帧
                return null;
            }
            try {
                // 可靠性标志：TCP 恒为 0，读取后不使用（UDP 场景见 SessionPacketCodec）
                frame.readByte();
                byte[] ciphertext = new byte[frame.readableBytes()];
                frame.readBytes(ciphertext);
                return cipher.decrypt(ciphertext);
            } finally {
                frame.release();
            }
        }
    }

    /**
     * 会话编码器：AES-GCM 加密后写出 int32 长度 + 标志位（恒 0）+ 密文。
     */
    public static final class Encoder extends MessageToByteEncoder<SessionMessage> {

        private final SessionCipher cipher;

        public Encoder(SessionCipher cipher) {
            this.cipher = cipher;
        }

        @Override
        protected void encode(ChannelHandlerContext ctx, SessionMessage msg, ByteBuf out) throws Exception {
            byte[] ciphertext = cipher.encrypt(msg);
            out.writeInt(FLAG_LENGTH + ciphertext.length);
            out.writeByte(FLAG_TCP);
            out.writeBytes(ciphertext);
        }
    }
}
