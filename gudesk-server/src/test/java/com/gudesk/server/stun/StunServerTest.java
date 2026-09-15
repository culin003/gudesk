package com.gudesk.server.stun;

import com.gudesk.common.net.StunClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StunServerTest {

    private StunServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.close();
        }
    }

    @Test
    void loopbackBinding请求返回正确映射() throws IOException {
        server = new StunServer(0);
        server.start();
        try (DatagramSocket socket = new DatagramSocket()) {
            InetSocketAddress mapped = StunClient.sendBindingRequest(
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), server.getPort()), socket, 3000);
            assertEquals(InetAddress.getLoopbackAddress(), mapped.getAddress(),
                    "loopback 请求应映射出 127.0.0.1");
            assertEquals(socket.getLocalPort(), mapped.getPort(), "映射端口应为本地 socket 端口");
        }
    }

    @Test
    void 坏包不响应且不影响后续请求() throws IOException {
        server = new StunServer(0);
        server.start();
        try (DatagramSocket socket = new DatagramSocket()) {
            // 发送非法类型报文（0x0002，非 Binding Request）
            byte[] junk = new byte[20];
            junk[0] = 0x00;
            junk[1] = 0x02;
            junk[4] = 0x21;
            junk[5] = 0x12;
            junk[6] = (byte) 0xA4;
            junk[7] = 0x42;
            socket.send(new DatagramPacket(junk, junk.length,
                    InetAddress.getLoopbackAddress(), server.getPort()));
            // 应超时无响应
            socket.setSoTimeout(300);
            byte[] buf = new byte[1024];
            assertThrows(SocketTimeoutException.class,
                    () -> socket.receive(new DatagramPacket(buf, buf.length)));

            // 同一 socket 随后正常请求仍得到正确响应
            InetSocketAddress mapped = StunClient.sendBindingRequest(
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), server.getPort()), socket, 3000);
            assertEquals(socket.getLocalPort(), mapped.getPort());
            assertEquals(InetAddress.getLoopbackAddress(), mapped.getAddress());
        }
    }

    @Test
    void 多客户端并发请求各自映射正确() throws IOException {
        server = new StunServer(0);
        server.start();
        try (DatagramSocket s1 = new DatagramSocket();
             DatagramSocket s2 = new DatagramSocket()) {
            InetSocketAddress m1 = StunClient.sendBindingRequest(
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), server.getPort()), s1, 3000);
            InetSocketAddress m2 = StunClient.sendBindingRequest(
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), server.getPort()), s2, 3000);
            assertEquals(s1.getLocalPort(), m1.getPort());
            assertEquals(s2.getLocalPort(), m2.getPort());
            assertTrue(!m1.equals(m2));
        }
    }
}
