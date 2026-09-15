package com.gudesk.common.net;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.security.SecureRandom;

/**
 * STUN 客户端工具（RFC 5389 Binding 最小实现）：向 STUN 服务器发送 Binding Request，
 * 解析响应中的 XOR-MAPPED-ADDRESS 得到本 socket 的公网映射地址（ip:port）。
 *
 * <p>供 host/viewer 在 UDP 打洞前探测公网地址使用；常量与
 * {@code com.gudesk.server.stun.StunServer} 保持一致（RFC 5389 标准值）。
 * 原 server 模块实现已迁移至本类，server 侧测试与入口改用本类。
 */
public final class StunClient {

    /** Binding Response 报文类型（RFC 5389） */
    static final int TYPE_BINDING_RESPONSE = 0x0101;
    /** RFC 5389 magic cookie */
    static final int MAGIC_COOKIE = 0x2112A442;
    /** XOR-MAPPED-ADDRESS 属性类型 */
    static final int ATTR_XOR_MAPPED_ADDRESS = 0x0020;
    /** STUN 报文头长度 */
    static final int HEADER_LENGTH = 20;
    private static final int ATTR_HEADER_LENGTH = 4;

    private static final SecureRandom RANDOM = new SecureRandom();

    private StunClient() {
    }

    /**
     * 发送 Binding Request 并返回公网映射地址。
     *
     * @param serverAddr  STUN 服务器地址
     * @param localSocket 本地 UDP socket（须已绑定，映射地址即此 socket 的公网映射）
     * @param timeoutMs   等待响应超时（毫秒）
     * @return 映射地址（公网 ip:port）
     * @throws IOException             超时、响应非法或无 XOR-MAPPED-ADDRESS 属性
     * @throws SocketTimeoutException  超时（IOException 子类，单列便于区分重试）
     */
    public static InetSocketAddress sendBindingRequest(InetSocketAddress serverAddr,
                                                       DatagramSocket localSocket,
                                                       int timeoutMs) throws IOException {
        // 构造请求：type 0x0001 + len 0 + magic cookie + 随机 12 字节 transactionId
        byte[] transactionId = new byte[12];
        RANDOM.nextBytes(transactionId);
        byte[] request = new byte[HEADER_LENGTH];
        request[0] = 0x00;
        request[1] = 0x01;
        request[2] = 0x00;
        request[3] = 0x00;
        putInt(request, 4, MAGIC_COOKIE);
        System.arraycopy(transactionId, 0, request, 8, 12);

        localSocket.setSoTimeout(timeoutMs);
        localSocket.send(new DatagramPacket(request, request.length, serverAddr));

        byte[] buffer = new byte[1024];
        DatagramPacket response = new DatagramPacket(buffer, buffer.length);
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (true) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                throw new SocketTimeoutException("STUN 响应超时: " + serverAddr);
            }
            localSocket.setSoTimeout((int) remaining);
            localSocket.receive(response);
            InetSocketAddress mapped = parseResponse(buffer, response.getLength(), transactionId);
            if (mapped != null) {
                return mapped;
            }
            // 非本请求事务的包（串扰），继续等待
        }
    }

    /** 解析并校验 Binding Response；transactionId 不匹配或格式非法返回 null（按串扰丢弃），无属性抛 IOException。 */
    private static InetSocketAddress parseResponse(byte[] data, int length, byte[] transactionId) throws IOException {
        if (length < HEADER_LENGTH) {
            return null;
        }
        int type = ((data[0] & 0xFF) << 8) | (data[1] & 0xFF);
        int cookie = getInt(data, 4);
        if (type != TYPE_BINDING_RESPONSE || cookie != MAGIC_COOKIE) {
            return null;
        }
        for (int i = 0; i < 12; i++) {
            if (data[8 + i] != transactionId[i]) {
                return null;
            }
        }
        int messageLength = getInt(data, 2) & 0xFFFF;
        int end = Math.min(HEADER_LENGTH + messageLength, length);
        int offset = HEADER_LENGTH;
        while (offset + ATTR_HEADER_LENGTH <= end) {
            int attrType = ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
            int attrLength = ((data[offset + 2] & 0xFF) << 8) | (data[offset + 3] & 0xFF);
            int valueOffset = offset + ATTR_HEADER_LENGTH;
            if (attrType == ATTR_XOR_MAPPED_ADDRESS && attrLength >= 8
                    && valueOffset + 8 <= end) {
                int family = data[valueOffset + 1] & 0xFF;
                if (family != 0x01) {
                    throw new IOException("不支持的 XOR-MAPPED-ADDRESS family: " + family);
                }
                int xPort = ((data[valueOffset + 2] & 0xFF) << 8) | (data[valueOffset + 3] & 0xFF);
                int port = xPort ^ (MAGIC_COOKIE >>> 16);
                byte[] address = new byte[4];
                for (int i = 0; i < 4; i++) {
                    address[i] = (byte) ((data[valueOffset + 4 + i] & 0xFF)
                            ^ (MAGIC_COOKIE >>> (8 * (3 - i))));
                }
                return new InetSocketAddress(InetAddress.getByAddress(address), port);
            }
            offset = valueOffset + attrLength + (attrLength & 1); // 属性按 2 字节对齐填充
        }
        throw new IOException("STUN 响应缺少 XOR-MAPPED-ADDRESS 属性");
    }

    private static void putInt(byte[] buf, int off, int value) {
        buf[off] = (byte) (value >>> 24);
        buf[off + 1] = (byte) (value >>> 16);
        buf[off + 2] = (byte) (value >>> 8);
        buf[off + 3] = (byte) value;
    }

    private static int getInt(byte[] buf, int off) {
        return ((buf[off] & 0xFF) << 24) | ((buf[off + 1] & 0xFF) << 16)
                | ((buf[off + 2] & 0xFF) << 8) | (buf[off + 3] & 0xFF);
    }
}
