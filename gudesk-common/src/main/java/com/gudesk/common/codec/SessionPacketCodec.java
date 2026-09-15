package com.gudesk.common.codec;

import com.gudesk.common.crypto.SessionCipher;
import com.gudesk.common.proto.GuDeskProto.SessionMessage;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.socket.DatagramPacket;

import java.net.InetSocketAddress;
import java.security.GeneralSecurityException;

/**
 * 会话数据通道 UDP 包编解码器：{@link DatagramPacket} ↔（可靠性标志 + AES-GCM 密文）。
 *
 * <p>包格式：1 字节可靠性标志 + AES-GCM 密文（{@link SessionMessage} 由
 * {@link SessionCipher} 加解密）。序列号字段由上层 ReliabilityLayer 负责管理，
 * 本类只透传标志与密文，不做任何可靠性处理。
 *
 * <p>可靠性标志取值：0=普通消息（可容忍丢弃，如视频帧），1=可靠消息（需要 ACK，
 * 如输入事件）。UDP 单包大小建议不超过 MTU，超长帧的拆分由上层负责。
 */
public final class SessionPacketCodec {

    /** 可靠性标志：普通消息，可容忍丢弃 */
    public static final byte FLAG_BEST_EFFORT = 0;
    /** 可靠性标志：可靠消息，需要 ACK */
    public static final byte FLAG_RELIABLE = 1;

    private SessionPacketCodec() {
    }

    /**
     * 解码结果：可靠性标志 + 解密后的会话消息。
     */
    public record DecodedPacket(byte flag, SessionMessage message) {

        /** 是否为需要 ACK 的可靠消息 */
        public boolean reliable() {
            return flag == FLAG_RELIABLE;
        }
    }

    /**
     * 加密并封装为待发送的 {@link DatagramPacket}（内容 = 标志位 + 密文）。
     *
     * @param reliable true=可靠消息（需 ACK），false=普通消息（可容忍丢弃）
     * @param recipient 目的对端地址
     */
    public static DatagramPacket encode(SessionMessage message, SessionCipher cipher, boolean reliable,
                                        InetSocketAddress recipient) throws GeneralSecurityException {
        byte[] ciphertext = cipher.encrypt(message);
        ByteBuf content = Unpooled.buffer(1 + ciphertext.length);
        content.writeByte(reliable ? FLAG_RELIABLE : FLAG_BEST_EFFORT);
        content.writeBytes(ciphertext);
        return new DatagramPacket(content, recipient);
    }

    /**
     * 解密 {@link DatagramPacket} 并还原会话消息与可靠性标志。
     *
     * <p>本方法消费并释放入参 packet（Netty 解码器语义）；密文被篡改时抛出
     * {@link javax.crypto.AEADBadTagException}。
     */
    public static DecodedPacket decode(DatagramPacket packet, SessionCipher cipher)
            throws GeneralSecurityException {
        try {
            ByteBuf content = packet.content();
            byte flag = content.readByte();
            byte[] ciphertext = new byte[content.readableBytes()];
            content.readBytes(ciphertext);
            return new DecodedPacket(flag, cipher.decrypt(ciphertext));
        } finally {
            packet.release();
        }
    }
}
