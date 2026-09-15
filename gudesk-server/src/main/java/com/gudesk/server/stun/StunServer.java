package com.gudesk.server.stun;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;

/**
 * 内建 STUN 服务器（RFC 5389 最小实现）：仅支持 Binding Request / Binding Response，
 * 用于 GuDesk 客户端探测自身公网映射地址（ip:port），供 UDP 打洞使用。
 *
 * <p>报文格式：20 字节头（type 2B + length 2B + magic cookie 4B + transactionId 12B）；
 * 响应头 20 字节原样回显 cookie 与 transactionId，仅改 type 为 0x0101、length 为 12，
 * 并附一个 XOR-MAPPED-ADDRESS 属性（type 0x0020，8 字节值：
 * 保留字节 + family 0x01 + port^0x2112 + ipv4^cookie）。其余属性不解析。
 *
 * <p>线程模型：阻塞 IO + 虚拟线程（与信令服务器一致，属控制面）。
 */
public final class StunServer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(StunServer.class);

    public static final int DEFAULT_PORT = 3478;
    /** Binding Request 报文类型 */
    public static final int TYPE_BINDING_REQUEST = 0x0001;
    /** Binding Response 报文类型 */
    public static final int TYPE_BINDING_RESPONSE = 0x0101;
    /** RFC 5389 magic cookie */
    public static final int MAGIC_COOKIE = 0x2112A442;
    /** XOR-MAPPED-ADDRESS 属性类型 */
    public static final int ATTR_XOR_MAPPED_ADDRESS = 0x0020;
    /** STUN 报文头长度 */
    public static final int HEADER_LENGTH = 20;
    /** 响应总长度：20 头 + 4 属性头 + 8 属性值 */
    private static final int RESPONSE_LENGTH = 32;

    private final int port;
    private DatagramSocket socket;
    private volatile boolean running;
    private Thread receiveThread;

    public StunServer(int port) {
        this.port = port;
    }

    /** 启动 UDP 监听与接收循环（虚拟线程）。 */
    public synchronized void start() throws IOException {
        if (running) {
            return;
        }
        socket = new DatagramSocket(new InetSocketAddress(port));
        running = true;
        receiveThread = Thread.ofVirtual().name("stun-receive").start(this::receiveLoop);
        log.info("STUN 服务器已启动: UDP {}:{}", socket.getLocalAddress().getHostAddress(), getPort());
    }

    /** 实际监听端口（port=0 时为系统分配的端口）。 */
    public int getPort() {
        return socket.getLocalPort();
    }

    @Override
    public synchronized void close() {
        if (!running) {
            return;
        }
        running = false;
        socket.close();
        log.info("STUN 服务器已停止");
    }

    private void receiveLoop() {
        byte[] buffer = new byte[1024];
        while (running) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                socket.receive(packet);
            } catch (IOException e) {
                if (running) {
                    log.warn("STUN 接收失败: {}", e.getMessage());
                }
                break;
            }
            try {
                handle(packet);
            } catch (IOException e) {
                log.warn("STUN 响应发送失败: {}", e.getMessage());
            }
        }
    }

    private void handle(DatagramPacket packet) throws IOException {
        if (packet.getLength() < HEADER_LENGTH) {
            return;
        }
        byte[] data = packet.getData();
        int off = packet.getOffset();
        int type = ((data[off] & 0xFF) << 8) | (data[off + 1] & 0xFF);
        int cookie = ((data[off + 4] & 0xFF) << 24) | ((data[off + 5] & 0xFF) << 16)
                | ((data[off + 6] & 0xFF) << 8) | (data[off + 7] & 0xFF);
        if (type != TYPE_BINDING_REQUEST || cookie != MAGIC_COOKIE) {
            // 仅处理合法 Binding Request，坏包静默丢弃（RFC 5389：非完整请求直接忽略）
            return;
        }
        byte[] address = packet.getAddress().getAddress();
        if (address.length != 4) {
            // MVP 仅支持 IPv4 映射
            return;
        }
        byte[] resp = buildResponse(packet, cookie, address);
        socket.send(new DatagramPacket(resp, resp.length, packet.getAddress(), packet.getPort()));
    }

    /** 构造 Binding Response：回显 cookie/transactionId + XOR-MAPPED-ADDRESS 属性。 */
    private static byte[] buildResponse(DatagramPacket packet, int cookie, byte[] address) {
        byte[] resp = new byte[RESPONSE_LENGTH];
        byte[] data = packet.getData();
        int off = packet.getOffset();
        // 头：type=0x0101, length=12, cookie 原样, transactionId 原样
        resp[0] = 0x01;
        resp[1] = 0x01;
        resp[2] = 0x00;
        resp[3] = 0x0C;
        resp[4] = data[off + 4];
        resp[5] = data[off + 5];
        resp[6] = data[off + 6];
        resp[7] = data[off + 7];
        System.arraycopy(data, off + 8, resp, 8, 12);
        // XOR-MAPPED-ADDRESS 属性：type=0x0020, length=8
        resp[20] = 0x00;
        resp[21] = 0x20;
        resp[22] = 0x00;
        resp[23] = 0x08;
        // 值：保留字节 0 + family 0x01(IPv4) + x-port + x-address
        resp[24] = 0x00;
        resp[25] = 0x01;
        int xPort = packet.getPort() ^ (MAGIC_COOKIE >>> 16);
        resp[26] = (byte) (xPort >>> 8);
        resp[27] = (byte) xPort;
        resp[28] = (byte) (address[0] ^ (cookie >>> 24));
        resp[29] = (byte) (address[1] ^ (cookie >>> 16));
        resp[30] = (byte) (address[2] ^ (cookie >>> 8));
        resp[31] = (byte) (address[3] ^ cookie);
        return resp;
    }
}
