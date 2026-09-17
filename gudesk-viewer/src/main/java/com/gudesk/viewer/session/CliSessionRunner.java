package com.gudesk.viewer.session;

import com.gudesk.common.session.SessionTransport;
import com.gudesk.common.spi.AdapterConfig;
import com.gudesk.common.spi.AdapterException;
import com.gudesk.common.spi.SpiLoader;
import com.gudesk.common.spi.VideoDecoder;
import com.gudesk.viewer.decode.DecoderSelector;
import com.gudesk.viewer.decode.JavaCvVideoDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * CLI 连接连调模式：无 UI（renderer=null）自动连接被控端，收流统计
 * {@code autoSeconds} 秒后主动断开退出。端到端验证链路：
 * 协商 → 密码验证 → 授权 → 视频收流解码 → 输入通道（周期发送鼠标移动事件）→ 断开。
 *
 * <p>连接目标支持两类（经 {@link ViewerConnectionOrchestrator} 编排）：
 * 纯数字 ID（信令 → UDP 打洞/TCP 直连并发 → 中继回落）与 {@code ip:port}（TCP 直连）。
 *
 * <p>打印统计：连接模式、收帧数、平均 fps、帧延迟（capture_ns→本机解码完成）、
 * 分辨率、心跳单向延迟（RTT/2）。
 *
 * <p>退出码：0=收流成功断开；2=连接/协商/授权失败或零帧。
 */
public final class CliSessionRunner {

    private static final Logger LOG = LoggerFactory.getLogger(CliSessionRunner.class);

    /** 输入通道验证：鼠标移动事件发送间隔（毫秒） */
    private static final long MOVE_INTERVAL_MS = 200;
    /** 输入通道验证：鼠标移动事件总数 */
    private static final int MOVE_EVENTS = 10;

    private CliSessionRunner() {
    }

    /** 收流统计（写入在 IO 线程的回调；await 返回后主线程读取，靠 CountDownLatch 建立 happens-before） */
    private static final class Stats {
        long frames;
        long captureDecodeTotalMs;
        long captureDecodeMinMs = Long.MAX_VALUE;
        long captureDecodeMaxMs = Long.MIN_VALUE;
        long firstFrameNs = -1;
        long lastFrameNs = -1;
        int width;
        int height;
        long latencySamples;
        long latencyTotalMs;
        String connectMode;
    }

    /**
     * 运行 CLI 连接。
     *
     * @param target      连接目标（纯数字 ID 或 ip:port）
     * @param password    被控端密码
     * @param autoSeconds 收流时长（秒）后自动断开
     * @param server      信令服务器地址（{@code host[:port]}；null/空=默认 127.0.0.1:48900，ID 连接用）
     * @param preferRelay true=跳过打洞与 TCP 直连直接中继（测试中继回落）
     * @return 进程退出码（0=成功，2=失败）
     */
    public static int run(String target, String password, int autoSeconds,
                          String server, boolean preferRelay) {
        System.out.println("=== GuDesk Viewer CLI 连接连调 ===");
        System.out.println("目标: " + target + "  时长: " + autoSeconds + "s"
                + (preferRelay ? "  [--prefer-relay]" : ""));
        if (target == null || target.isBlank()) {
            System.out.println("[错误] 缺少 --connect <ID 或 ip:port> 参数");
            return 2;
        }
        if (password == null || password.isBlank()) {
            System.out.println("[错误] 缺少 --password <pwd> 参数");
            return 2;
        }
        String[] parsed = SessionUiConnector.parseTarget(target);
        if (parsed == null) {
            System.out.println("[错误] 连接目标格式非法（支持 ID 或 ip:port）: " + target);
            return 2;
        }
        InetSocketAddress signalingServer;
        try {
            signalingServer = ViewerConnectionOrchestrator.parseServerAddress(
                    server == null || server.isBlank() ? "127.0.0.1" : server);
        } catch (IllegalArgumentException e) {
            System.out.println("[错误] " + e.getMessage());
            return 2;
        }

        VideoDecoder decoder;
        try {
            DecoderSelector.selectPlatformDefault();
            decoder = SpiLoader.load(VideoDecoder.class, "decoder",
                    JavaCvVideoDecoder::defaultDecoder);
            decoder.init(AdapterConfig.builder()
                    .width(1280).height(720).fps(30)
                    .pixelFormat(JavaCvVideoDecoder.OUTPUT_PIXEL_FORMAT)
                    .build());
            decoder.start();
        } catch (AdapterException e) {
            System.out.println("[错误] 解码器初始化失败: " + e.getMessage());
            return 2;
        }

        CountDownLatch done = new CountDownLatch(1);
        AtomicInteger exitCode = new AtomicInteger(2);
        Stats stats = new Stats();
        // 匿名 Listener 回调中引用客户端（创建完成后 holder[0] 赋值）
        final ViewerSessionClient[] holder = new ViewerSessionClient[1];

        ViewerSessionClient client = new ViewerSessionClient(decoder, null,
                new ViewerSessionClient.Listener() {
                    @Override
                    public void onConnected() {
                        System.out.println("[会话] ESTABLISHED：协商+密码验证+授权全部通过（"
                                + (stats.connectMode == null ? "直连" : stats.connectMode) + "）");
                        startInputProbe(holder[0]);
                        scheduleDisconnect(holder[0], autoSeconds);
                    }

                    @Override
                    public void onConnectFailed(String reason) {
                        System.out.println("[失败] 连接失败: " + reason);
                        exitCode.set(2);
                        done.countDown();
                    }

                    @Override
                    public void onFrameDecoded(long captureToDecodeMs, int width, int height) {
                        stats.frames++;
                        stats.captureDecodeTotalMs += captureToDecodeMs;
                        if (captureToDecodeMs < stats.captureDecodeMinMs) {
                            stats.captureDecodeMinMs = captureToDecodeMs;
                        }
                        if (captureToDecodeMs > stats.captureDecodeMaxMs) {
                            stats.captureDecodeMaxMs = captureToDecodeMs;
                        }
                        long now = System.nanoTime();
                        if (stats.firstFrameNs < 0) {
                            stats.firstFrameNs = now;
                        }
                        stats.lastFrameNs = now;
                        if (stats.frames == 1) {
                            System.out.println("[视频] 首帧 " + width + "x" + height
                                    + "（capture→解码 " + captureToDecodeMs + " ms）");
                        }
                        stats.width = width;
                        stats.height = height;
                    }

                    @Override
                    public void onLatency(long oneWayMs) {
                        stats.latencySamples++;
                        stats.latencyTotalMs += oneWayMs;
                    }

                    @Override
                    public void onClosed(String reason) {
                        System.out.println("[会话] 已关闭: " + reason);
                        exitCode.set(stats.frames > 0 ? 0 : 2);
                        done.countDown();
                    }
                });

        System.out.println("本机身份指纹: " + client.identityFingerprint());
        holder[0] = client;
        try (ViewerConnectionOrchestrator orchestrator =
                     new ViewerConnectionOrchestrator(signalingServer, preferRelay)) {
            System.out.println("[连接] " + target + "（信令 " + signalingServer + "）...");
            orchestrator.connect(target, client, client::onMessage,
                    new ViewerConnectionOrchestrator.Listener() {
                        @Override
                        public void onTransportReady(SessionTransport transport,
                                                     ViewerConnectionOrchestrator.ConnectMode mode) {
                            stats.connectMode = mode.label();
                            System.out.println("[连接路径] " + mode.label() + "，正在协商认证 ...");
                            client.attachTransport(transport, password);
                        }

                        @Override
                        public void onFailed(String reason) {
                            System.out.println("[失败] 连接编排失败: " + reason);
                            client.abort();
                            exitCode.set(2);
                            done.countDown();
                        }
                    });

            try {
                // 收流时长 + 10s 余量（信令/打洞/中继回落 + 握手/授权/断开检测）
                if (!done.await(autoSeconds + 15L, TimeUnit.SECONDS)) {
                    System.out.println("[失败] 等待会话结束超时");
                    orchestrator.cancel();
                    client.disconnect();
                    return 2;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                orchestrator.cancel();
                client.disconnect();
                return 2;
            }
        }
        printStats(stats);
        return exitCode.get();
    }

    /** 输入通道验证：周期发送鼠标移动事件（归一化坐标小幅往返，同机注入安全无害） */
    private static void startInputProbe(ViewerSessionClient client) {
        ScheduledExecutorService probe = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "cli-input-probe");
            t.setDaemon(true);
            return t;
        });
        AtomicInteger sent = new AtomicInteger();
        probe.scheduleAtFixedRate(() -> {
            int i = sent.incrementAndGet();
            if (i > MOVE_EVENTS) {
                probe.shutdownNow();
                return;
            }
            double nx = 0.5 + 0.1 * Math.sin(i * 0.7);
            double ny = 0.5 + 0.1 * Math.cos(i * 0.7);
            client.onMouseMove(nx, ny);
        }, MOVE_INTERVAL_MS, MOVE_INTERVAL_MS, TimeUnit.MILLISECONDS);
        LOG.debug("输入通道探活已启动（{} 次鼠标移动事件）", MOVE_EVENTS);
    }

    private static void scheduleDisconnect(ViewerSessionClient client, int autoSeconds) {
        Thread killer = new Thread(() -> {
            try {
                Thread.sleep(autoSeconds * 1000L);
            } catch (InterruptedException e) {
                return;
            }
            System.out.println("[自动断开] 收流 " + autoSeconds + "s 到时，主动断开");
            client.disconnect();
        }, "cli-auto-disconnect");
        killer.setDaemon(true);
        killer.start();
    }

    private static void printStats(Stats stats) {
        System.out.println("=== 收流统计 ===");
        System.out.println("收帧数      : " + stats.frames);
        if (stats.frames > 0) {
            double elapsedS = Math.max(1, stats.lastFrameNs - stats.firstFrameNs) / 1_000_000_000.0;
            System.out.printf(Locale.ROOT, "平均 fps    : %.1f%n", stats.frames / elapsedS);
            System.out.println("帧延迟(avg) : " + (stats.captureDecodeTotalMs / stats.frames) + " ms");
            System.out.println("帧延迟(min/max): " + stats.captureDecodeMinMs + " / "
                    + stats.captureDecodeMaxMs + " ms");
            System.out.println("分辨率      : " + stats.width + "x" + stats.height);
        }
        if (stats.latencySamples > 0) {
            System.out.println("心跳单向延迟(avg): " + (stats.latencyTotalMs / stats.latencySamples)
                    + " ms（" + stats.latencySamples + " 个样本）");
        } else {
            System.out.println("心跳单向延迟: 无样本");
        }
        System.out.println("=== 统计结束 ===");
    }
}
