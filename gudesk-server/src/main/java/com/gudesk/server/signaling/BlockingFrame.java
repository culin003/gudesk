package com.gudesk.server.signaling;

import com.gudesk.common.codec.SignalingFrameCodec;
import com.gudesk.common.proto.GuDeskProto.SignalingEnvelope;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 阻塞 IO 版信令帧编解码，线帧格式与 {@link SignalingFrameCodec} 完全一致：
 * 4 字节大端长度前缀 + Protobuf {@link SignalingEnvelope} 字节负载。
 *
 * <p>供虚拟线程 thread-per-connection 的 {@link SignalingServer} 在阻塞流上读写帧；
 * Netty 版编解码器用于数据面，阻塞版用于信令面。
 */
public final class BlockingFrame {

    private BlockingFrame() {
    }

    /**
     * 从阻塞输入流读取一帧并反序列化为信令信封。
     *
     * @return 信封；对端在帧边界处干净关闭时返回 {@code null}
     * @throws IOException 连接中断在帧中间、帧长度非法（负数或超过 1MB）
     */
    public static SignalingEnvelope readEnvelope(InputStream in) throws IOException {
        int length = readInt(in);
        if (length < 0) {
            // 干净 EOF：对端在帧边界处关闭
            return null;
        }
        if (length == 0) {
            throw new IOException("帧长度非法: 0");
        }
        if (length > SignalingFrameCodec.MAX_FRAME_LENGTH) {
            throw new IOException("帧长度超过上限 1MB: " + length);
        }
        byte[] body = new byte[length];
        readFully(in, body);
        return SignalingEnvelope.parseFrom(body);
    }

    /**
     * 将信令信封按 4 字节大端长度前缀 + 字节负载写出（不 flush，由调用方控制）。
     */
    public static void writeEnvelope(OutputStream out, SignalingEnvelope envelope) throws IOException {
        byte[] body = envelope.toByteArray();
        out.write((body.length >>> 24) & 0xFF);
        out.write((body.length >>> 16) & 0xFF);
        out.write((body.length >>> 8) & 0xFF);
        out.write(body.length & 0xFF);
        out.write(body);
    }

    /** 读取 4 字节大端 int；一个字节都没读到返回 -1 表示干净 EOF，读了一部分断开抛 EOFException */
    private static int readInt(InputStream in) throws IOException {
        int b1 = in.read();
        if (b1 < 0) {
            return -1;
        }
        int b2 = in.read();
        int b3 = in.read();
        int b4 = in.read();
        if (b2 < 0 || b3 < 0 || b4 < 0) {
            throw new EOFException("连接在长度前缀中间关闭");
        }
        return (b1 << 24) | (b2 << 16) | (b3 << 8) | b4;
    }

    private static void readFully(InputStream in, byte[] buffer) throws IOException {
        int offset = 0;
        while (offset < buffer.length) {
            int n = in.read(buffer, offset, buffer.length - offset);
            if (n < 0) {
                throw new EOFException("连接在帧体中间关闭，已读 " + offset + "/" + buffer.length);
            }
            offset += n;
        }
    }
}
