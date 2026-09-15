package com.gudesk.common.net;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link UdpHolePuncher} 回环测试：两个 UDP socket 互发 PUNCH（模拟无 NAT 场景
 * 直接成功），覆盖双向成功、对端真实地址探测、非 PUNCH 包忽略与超时失败。
 */
class UdpHolePuncherTest {

    @Test
    @Timeout(15)
    void 双socket互发探测包打洞成功() throws Exception {
        try (DatagramSocket a = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0));
             DatagramSocket b = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0))) {
            InetSocketAddress addrA = new InetSocketAddress("127.0.0.1", a.getLocalPort());
            InetSocketAddress addrB = new InetSocketAddress("127.0.0.1", b.getLocalPort());

            CompletableFuture<UdpHolePuncher.PunchOutcome> futureA =
                    UdpHolePuncher.punch(a, List.of(addrB), 5_000);
            CompletableFuture<UdpHolePuncher.PunchOutcome> futureB =
                    UdpHolePuncher.punch(b, List.of(addrA), 5_000);

            UdpHolePuncher.PunchOutcome outcomeA = futureA.get(6, TimeUnit.SECONDS);
            UdpHolePuncher.PunchOutcome outcomeB = futureB.get(6, TimeUnit.SECONDS);
            // 双方都应判定成功，且探测到的对端地址互为对方 socket 地址
            assertEquals(addrB, outcomeA.peerAddress(), "A 应探测到 B 的真实地址");
            assertEquals(addrA, outcomeB.peerAddress(), "B 应探测到 A 的真实地址");
            // 成功后 socket 保持打开（交由调用方承载会话）
            assertFalse(a.isClosed());
            assertFalse(b.isClosed());
        }
    }

    @Test
    @Timeout(15)
    void 非探测包被忽略不打洞成功() throws Exception {
        try (DatagramSocket a = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0));
             DatagramSocket b = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0));
             DatagramSocket intruder = new DatagramSocket()) {
            InetSocketAddress addrB = new InetSocketAddress("127.0.0.1", b.getLocalPort());

            CompletableFuture<UdpHolePuncher.PunchOutcome> futureB =
                    UdpHolePuncher.punch(b, List.of(), 5_000); // 仅监听不发

            // 干扰包：长度与 magic 均不匹配（会话包/STUN 串扰模拟）
            Thread.sleep(100); // 等接收线程就绪
            intruder.send(new DatagramPacket(new byte[]{9, 9, 9}, 3, addrB));
            Thread.sleep(200);
            assertFalse(futureB.isDone(), "非 PUNCH 包不应判定打洞成功");

            // 真 PUNCH 到达 → 成功，且对端地址为干扰方 socket（真实来源）
            intruder.send(new DatagramPacket(UdpHolePuncher.PUNCH_MAGIC,
                    UdpHolePuncher.PUNCH_MAGIC.length, addrB));
            UdpHolePuncher.PunchOutcome outcome = futureB.get(3, TimeUnit.SECONDS);
            assertEquals(intruder.getLocalPort(), outcome.peerAddress().getPort());
        }
    }

    @Test
    @Timeout(15)
    void 无对端响应时打洞超时() throws Exception {
        // 占用端口后关闭：模拟对端无人监听
        int ghostPort;
        try (DatagramSocket ghost = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0))) {
            ghostPort = ghost.getLocalPort();
        }
        try (DatagramSocket a = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0))) {
            CompletableFuture<UdpHolePuncher.PunchOutcome> future =
                    UdpHolePuncher.punch(a,
                            List.of(new InetSocketAddress("127.0.0.1", ghostPort)), 600);

            ExecutionException e = assertThrows(ExecutionException.class,
                    () -> future.get(3, TimeUnit.SECONDS));
            assertInstanceOf(java.net.SocketTimeoutException.class, e.getCause(),
                    "超时应以 SocketTimeoutException 异常完成");
            // 失败后 socket 仍由调用方持有（未关闭）
            assertFalse(a.isClosed());
        }
    }

    @Test
    @Timeout(15)
    void 打洞成功后socket可复用承载会话数据() throws Exception {
        // 验证打洞 socket 交接语义：成功后同一 socket 继续收发应用数据
        try (DatagramSocket a = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0));
             DatagramSocket b = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0))) {
            InetSocketAddress addrB = new InetSocketAddress("127.0.0.1", b.getLocalPort());

            // B 仅监听（无候选）负责接收探测并回发；A 主动探测 B
            CompletableFuture<UdpHolePuncher.PunchOutcome> futureB =
                    UdpHolePuncher.punch(b, List.of(), 5_000);
            UdpHolePuncher.PunchOutcome outcome = UdpHolePuncher
                    .punch(a, List.of(addrB), 5_000).get(6, TimeUnit.SECONDS);
            assertEquals(addrB, outcome.peerAddress());
            assertTrue(futureB.get(3, TimeUnit.SECONDS).peerAddress().getPort() > 0);

            // 打洞结束后 A → B 传应用字节（跳过 A 在途的残留 PUNCH 包——
            // 真实会话端点同样忽略残留打洞包，见 UdpSessionEndpoint.handlePacket 的 default 分支）
            byte[] payload = {1, 2, 3, 4};
            a.send(new DatagramPacket(payload, payload.length, outcome.peerAddress()));
            b.setSoTimeout(3_000);
            byte[] buf = new byte[64];
            DatagramPacket received = new DatagramPacket(buf, buf.length);
            do {
                b.receive(received);
            } while (received.getLength() == UdpHolePuncher.PUNCH_MAGIC.length);
            assertEquals(4, received.getLength());
            assertEquals(a.getLocalPort(), received.getPort(), "来源应为打洞 socket");
        }
    }
}
