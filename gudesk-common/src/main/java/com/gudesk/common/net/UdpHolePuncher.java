package com.gudesk.common.net;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * UDP 打洞（简化模型）：双方各自向对方所有候选地址交替发送 PUNCH 探测包
 * （{@link #PUNCH_MAGIC} magic bytes，间隔 {@link #PUNCH_INTERVAL_MS}，持续至超时），
 * 收到对端 PUNCH 包即视为打洞成功——返回对端真实地址，socket 交由
 * {@code UdpSessionEndpoint} 承载会话数据。
 *
 * <p>收到 PUNCH 后立即回发一个 PUNCH（保证对端也能判定成功），再完成 Future。
 * 非 PUNCH 包（STUN 串扰、对端早到的会话包）静默忽略。
 *
 * <p>线程模型：punch() 在两个虚拟线程上收发（发送线程 + 接收线程），socket 收发
 * 并发安全；完成后（成功/超时/取消）两线程自行退出，socket 保持打开（成功时由
 * 调用方接管；失败/取消时由调用方关闭）。
 */
public final class UdpHolePuncher {

    /** PUNCH 探测包内容（magic bytes；首字节与 UDP 会话包类型 0-4 无冲突） */
    public static final byte[] PUNCH_MAGIC = "GUDESKPUNCH".getBytes(StandardCharsets.US_ASCII);
    /** 打洞包发送间隔（毫秒） */
    public static final long PUNCH_INTERVAL_MS = 200;

    private UdpHolePuncher() {
    }

    /** 打洞结果：对端真实地址（探测包来源） */
    public record PunchOutcome(InetSocketAddress peerAddress) {
    }

    /**
     * 启动双向打洞。
     *
     * @param socket      已绑定的 UDP socket（打洞成功后复用为会话 socket）
     * @param candidates  对端候选地址（可为空：仅监听不发，等对端探测本端）
     * @param timeoutMs   总超时（毫秒），超时以 {@link java.util.concurrent.TimeoutException} 完成
     * @return Future：成功携带对端地址；被取消（对端路径已胜出）时由调用方 close socket
     */
    public static CompletableFuture<PunchOutcome> punch(DatagramSocket socket,
                                                        List<InetSocketAddress> candidates,
                                                        long timeoutMs) {
        CompletableFuture<PunchOutcome> future = new CompletableFuture<>();
        long deadline = System.currentTimeMillis() + timeoutMs;

        // 接收线程：等待对端 PUNCH（soTimeout 短轮询以感知取消/超时）
        Thread.ofVirtual().name("gudesk-punch-recv").start(() -> {
            byte[] buf = new byte[512];
            try {
                socket.setSoTimeout((int) PUNCH_INTERVAL_MS + 50);
            } catch (IOException ignored) {
                // socket 已关闭，循环退出
            }
            while (!future.isDone() && !socket.isClosed()) {
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                try {
                    socket.receive(packet);
                } catch (SocketTimeoutException e) {
                    continue;
                } catch (IOException e) {
                    break; // socket 关闭
                }
                if (packet.getLength() != PUNCH_MAGIC.length || !isPunch(buf, packet.getLength())) {
                    continue; // STUN 串扰 / 会话包早到，忽略
                }
                InetSocketAddress from = (InetSocketAddress) packet.getSocketAddress();
                // 回发 PUNCH 让对端也立即成功（对端已成功则忽略）
                try {
                    socket.send(new DatagramPacket(PUNCH_MAGIC, PUNCH_MAGIC.length, from));
                } catch (IOException ignored) {
                    // 回发失败不影响本端成功
                }
                future.complete(new PunchOutcome(from));
                return;
            }
            if (!future.isDone()) {
                future.completeExceptionally(
                        new IOException("打洞接收循环退出（socket 关闭）"));
            }
        });

        // 发送线程：向对端候选交替发送 PUNCH 直至完成/超时
        Thread.ofVirtual().name("gudesk-punch-send").start(() -> {
            while (!future.isDone() && System.currentTimeMillis() < deadline && !socket.isClosed()) {
                for (InetSocketAddress candidate : candidates) {
                    if (future.isDone()) {
                        return;
                    }
                    try {
                        socket.send(new DatagramPacket(PUNCH_MAGIC, PUNCH_MAGIC.length, candidate));
                    } catch (IOException ignored) {
                        // 单个候选发送失败继续（可能尚未就绪）
                    }
                }
                try {
                    Thread.sleep(PUNCH_INTERVAL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            if (!future.isDone()) {
                future.completeExceptionally(new SocketTimeoutException(
                        "UDP 打洞超时（" + timeoutMs + " ms）"));
            }
        });
        return future;
    }

    private static boolean isPunch(byte[] data, int length) {
        for (int i = 0; i < length; i++) {
            if (data[i] != PUNCH_MAGIC[i]) {
                return false;
            }
        }
        return true;
    }
}
