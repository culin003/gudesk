package com.gudesk.server.relay;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class RelayServerTest {

    private RelayServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.close();
        }
    }

    private RelayServer startServer() throws IOException {
        server = new RelayServer(0);
        server.start();
        return server;
    }

    private Socket connect() throws IOException {
        Socket socket = new Socket("127.0.0.1", server.getPort());
        socket.setTcpNoDelay(true);
        socket.setSoTimeout(5000);
        return socket;
    }

    private void sendToken(Socket socket, String token) throws IOException {
        socket.getOutputStream().write((token + "\n").getBytes(StandardCharsets.UTF_8));
        socket.getOutputStream().flush();
    }

    private void write(Socket socket, byte[] data) throws IOException {
        socket.getOutputStream().write(data);
        socket.getOutputStream().flush();
    }

    private byte[] readExactly(InputStream in, int n) throws IOException {
        byte[] buf = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(buf, off, n - off);
            if (r < 0) {
                throw new IOException("连接提前关闭，已读 " + off + "/" + n);
            }
            off += r;
        }
        return buf;
    }

    /** 轮询等待条件成立（服务端事件循环处理 token 是异步的）。 */
    private void await(java.util.function.BooleanSupplier condition, String message)
            throws IOException {
        long deadline = System.currentTimeMillis() + 5000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new IOException("等待超时: " + message);
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("等待被打断", e);
            }
        }
    }

    @Test
    void token配对与双向透传字节一致() throws IOException {
        startServer();
        try (Socket a = connect();
             Socket b = connect()) {
            sendToken(a, "tok-pair-1");
            sendToken(b, "tok-pair-1");

            // A -> B
            write(a, "hello-from-a".getBytes(StandardCharsets.UTF_8));
            assertArrayEquals("hello-from-a".getBytes(StandardCharsets.UTF_8),
                    readExactly(b.getInputStream(), "hello-from-a".length()));
            // B -> A
            write(b, "hello-from-b".getBytes(StandardCharsets.UTF_8));
            assertArrayEquals("hello-from-b".getBytes(StandardCharsets.UTF_8),
                    readExactly(a.getInputStream(), "hello-from-b".length()));
            // 双向再一轮（含二进制字节）
            byte[] binary = {0x00, (byte) 0xFF, 0x7F, 0x01, 0x02, 0x03};
            write(a, binary);
            assertArrayEquals(binary, readExactly(b.getInputStream(), binary.length));
            write(b, binary);
            assertArrayEquals(binary, readExactly(a.getInputStream(), binary.length));
        }
    }

    @Test
    void A的提前数据在配对后送达B() throws IOException {
        startServer();
        try (Socket a = connect()) {
            // A 先到，token 行后立即发提前数据（B 尚未连接）
            sendToken(a, "tok-early");
            write(a, "early-data-".getBytes(StandardCharsets.UTF_8));

            await(() -> server.sessionCount() == 1, "A 连接后应有一个等待配对的会话");

            try (Socket b = connect()) {
                sendToken(b, "tok-early");
                // B 应收到 A 的提前数据
                assertArrayEquals("early-data-".getBytes(StandardCharsets.UTF_8),
                        readExactly(b.getInputStream(), "early-data-".length()));
                // 配对完成后 token 应出表
                await(() -> server.sessionCount() == 0, "配对完成后会话应出表");
            }
        }
    }

    @Test
    void 一端断开对端也断开() throws IOException {
        startServer();
        try (Socket a = connect();
             Socket b = connect()) {
            sendToken(a, "tok-close");
            sendToken(b, "tok-close");
            write(a, "ping".getBytes(StandardCharsets.UTF_8));
            readExactly(b.getInputStream(), 4); // 确认配对透传已生效

            a.close();
            // B 端应读到 EOF
            long deadline = System.currentTimeMillis() + 5000;
            int read;
            do {
                read = b.getInputStream().read();
            } while (read >= 0 && System.currentTimeMillis() < deadline);
            assertEquals(-1, read, "A 断开后 B 应被断开（EOF）");
        }
    }

    @Test
    void A未配对断开时会话清理() throws IOException {
        startServer();
        try (Socket a = connect()) {
            sendToken(a, "tok-abandon");
            await(() -> server.sessionCount() == 1, "A 的 token 应进入会话表");
            a.close();
            await(() -> server.sessionCount() == 0, "A 断开后未配对会话应被清理");
            // 同 token 新连接可重新进入等待（旧会话已清理）
            try (Socket a2 = connect()) {
                sendToken(a2, "tok-abandon");
                await(() -> server.sessionCount() >= 1, "同 token 新连接应重新等待");
            }
        }
    }

    @Test
    void 大块数据分段转发() throws IOException {
        startServer();
        try (Socket a = connect();
             Socket b = connect()) {
            sendToken(a, "tok-bulk");
            sendToken(b, "tok-bulk");

            // 256KB 分 64 段发送，B 端应收到完整数据
            int total = 256 * 1024;
            byte[] payload = new byte[total];
            new java.util.Random(42).nextBytes(payload);
            OutputStream out = a.getOutputStream();
            InputStream in = b.getInputStream();
            for (int off = 0; off < total; off += 4096) {
                out.write(payload, off, Math.min(4096, total - off));
            }
            out.flush();
            assertArrayEquals(payload, readExactly(in, total));
        }
    }
}
