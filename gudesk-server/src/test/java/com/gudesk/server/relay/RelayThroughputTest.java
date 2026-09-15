package com.gudesk.server.relay;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * 中继吞吐压测（perf，默认构建不跑）：
 * 手动执行：mvn test -pl gudesk-server -Dtest=RelayThroughputTest -DexcludedGroups=
 * loopback 单向打 100MB，统计 MB/s；&gt;50MB/s 记为通过。
 */
@Tag("perf")
class RelayThroughputTest {

    private static final int TOTAL_BYTES = 100 * 1024 * 1024; // 100MB
    private static final int CHUNK = 1024 * 1024;             // 1MB
    private static final double PASS_MBPS = 50.0;

    private RelayServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.close();
        }
    }

    @Test
    void loopback单向100MB吞吐() throws IOException, InterruptedException {
        server = new RelayServer(0);
        server.start();

        try (Socket a = new Socket("127.0.0.1", server.getPort());
             Socket b = new Socket("127.0.0.1", server.getPort())) {
            a.setTcpNoDelay(true);
            b.setTcpNoDelay(true);
            a.setSendBufferSize(4 * 1024 * 1024);
            b.setReceiveBufferSize(4 * 1024 * 1024);
            OutputStream outA = a.getOutputStream();
            OutputStream outB = b.getOutputStream();
            InputStream inA = a.getInputStream();
            InputStream inB = b.getInputStream();

            // 配对
            outA.write("tok-throughput\n".getBytes(StandardCharsets.UTF_8));
            outA.flush();
            outB.write("tok-throughput\n".getBytes(StandardCharsets.UTF_8));
            outB.flush();

            byte[] chunk = new byte[CHUNK];
            for (int i = 0; i < chunk.length; i++) {
                chunk[i] = (byte) (i & 0xFF);
            }

            // 接收线程：B 读满 TOTAL_BYTES，校验内容顺序
            final long[] received = {0};
            final byte[] mismatch = {0};
            Thread receiver = Thread.ofPlatform().start(() -> {
                try {
                    byte[] expect = new byte[CHUNK];
                    for (int i = 0; i < expect.length; i++) {
                        expect[i] = (byte) (i & 0xFF);
                    }
                    byte[] buf = new byte[64 * 1024];
                    int offsetInChunk = 0;
                    while (received[0] < TOTAL_BYTES) {
                        int r = inB.read(buf);
                        if (r < 0) {
                            break;
                        }
                        // 校验内容
                        for (int i = 0; i < r; i++) {
                            if (buf[i] != expect[offsetInChunk]) {
                                mismatch[0]++;
                                break;
                            }
                            offsetInChunk = (offsetInChunk + 1) % CHUNK;
                        }
                        received[0] += r;
                    }
                } catch (IOException ignored) {
                    // 服务端断开时退出
                }
            });

            // 发送端：A 写 100MB
            long start = System.nanoTime();
            for (int i = 0; i < TOTAL_BYTES / CHUNK; i++) {
                outA.write(chunk);
            }
            outA.flush();
            receiver.join(60_000);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            assertEquals(TOTAL_BYTES, received[0], "B 端应收满 100MB");
            assertEquals(0, mismatch[0], "转发内容应与发送一致");
            double mbps = TOTAL_BYTES / 1024.0 / 1024.0 / (elapsedMs / 1000.0);
            System.out.printf("[中继压测] %d MB, 耗时 %d ms, 吞吐 %.1f MB/s%n",
                    TOTAL_BYTES / 1024 / 1024, elapsedMs, mbps);
            assertTrue(mbps > PASS_MBPS, String.format("吞吐 %.1f MB/s 低于 %s MB/s", mbps, PASS_MBPS));
        }
    }

    private static void assertEquals(long expected, long actual, String message) {
        org.junit.jupiter.api.Assertions.assertEquals(expected, actual, message);
    }

    private static void assertTrue(boolean condition, String message) {
        org.junit.jupiter.api.Assertions.assertTrue(condition, message);
    }
}
