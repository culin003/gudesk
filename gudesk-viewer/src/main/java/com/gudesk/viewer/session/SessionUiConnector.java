package com.gudesk.viewer.session;

import com.gudesk.common.session.SessionTransport;
import com.gudesk.common.spi.AdapterConfig;
import com.gudesk.common.spi.FrameRenderer;
import com.gudesk.common.spi.SpiLoader;
import com.gudesk.common.spi.VideoDecoder;
import com.gudesk.viewer.decode.JavaCvVideoDecoder;
import com.gudesk.viewer.input.InputForwarder;
import com.gudesk.viewer.render.JavaFxFrameRenderer;
import com.gudesk.viewer.ui.ConnectionState;
import com.gudesk.viewer.ui.UiController;
import javafx.scene.canvas.Canvas;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 会话与 UI 的接线层：实现 {@link UiController.ConnectHandler}（连接/断开入口）与
 * {@link InputForwarder}（输入事件转发），持有连接编排器、当前会话客户端与统计聚合。
 *
 * <p>连接目标解析：纯数字 ID → 信令编排（UDP 打洞优先，失败回落中继）；
 * {@code ip:port} → TCP 直连。状态栏按连接模式区分文案
 * （"已连接-直连(UDP)" / "已连接-直连(TCP)" / "已连接-中继"）。
 *
 * <p>统计：收帧计数按 1s 窗口计算 fps，与最近一次心跳单向延迟一起经
 * {@link UiController#updateStats} 上报 UI。
 */
public final class SessionUiConnector implements UiController.ConnectHandler, InputForwarder {

    private static final Logger LOG = LoggerFactory.getLogger(SessionUiConnector.class);

    /** fps 统计窗口（毫秒） */
    private static final long STATS_INTERVAL_MS = 1_000;

    private final UiController controller;
    private final Canvas canvas;
    private final ViewerConnectionOrchestrator orchestrator;

    private volatile ViewerSessionClient client;
    /** 当前连接模式（onTransportReady 时记录，onConnected 时上报状态栏文案） */
    private volatile ViewerConnectionOrchestrator.ConnectMode connectMode;
    private final ScheduledExecutorService statsScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "viewer-stats");
        t.setDaemon(true);
        return t;
    });

    private final AtomicLong decodedCount = new AtomicLong();
    /** 当前统计任务（connect 成功时启动，onClosed 时取消） */
    private volatile ScheduledFuture<?> statsTask;
    private volatile long lastCount;
    private volatile long lastStatsNanos = System.nanoTime();
    private volatile long lastLatencyMs = -1;

    /**
     * @param controller       UI 控制器
     * @param canvas           视频画布（null=无渲染模式）
     * @param signalingServer  信令服务器地址（ID 连接用；ip:port 直连不经过）
     * @param preferRelay      true=跳过打洞与 TCP 直连直接中继（测试中继回落）
     */
    public SessionUiConnector(UiController controller, Canvas canvas,
                              InetSocketAddress signalingServer, boolean preferRelay) {
        this.controller = controller;
        this.canvas = canvas;
        this.orchestrator = new ViewerConnectionOrchestrator(signalingServer, preferRelay);
    }

    // ------------------------------------------------------------------
    // ConnectHandler
    // ------------------------------------------------------------------

    @Override
    public void connect(String target, String password) {
        String[] parsed = parseTarget(target);
        if (parsed == null) {
            controller.publishMessage("连接目标格式非法: " + target + "（支持 ID 或 ip:port）");
            controller.updateState(ConnectionState.DISCONNECTED);
            return;
        }
        if (password == null || password.isBlank()) {
            controller.publishMessage("请输入被控端密码");
            controller.updateState(ConnectionState.DISCONNECTED);
            return;
        }

        VideoDecoder decoder;
        try {
            decoder = SpiLoader.load(VideoDecoder.class, "decoder",
                    JavaCvVideoDecoder::defaultDecoder);
            decoder.init(AdapterConfig.builder()
                    .width(1280).height(720).fps(30)
                    .pixelFormat(JavaCvVideoDecoder.OUTPUT_PIXEL_FORMAT)
                    .build());
            decoder.start();
        } catch (Exception e) {
            controller.publishMessage("解码器初始化失败: " + e.getMessage());
            controller.updateState(ConnectionState.DISCONNECTED);
            return;
        }
        FrameRenderer renderer = canvas != null ? new JavaFxFrameRenderer(canvas) : null;
        try {
            if (renderer != null) {
                renderer.init(AdapterConfig.builder()
                        .width(1280).height(720).fps(30)
                        .pixelFormat(JavaFxFrameRenderer.PIXEL_FORMAT)
                        .build());
                renderer.start();
            }
        } catch (Exception e) {
            LOG.warn("渲染器初始化失败，按无渲染模式继续: {}", String.valueOf(e));
            renderer = null;
        }

        ViewerSessionClient newClient = new ViewerSessionClient(decoder, renderer,
                new ViewerSessionClient.Listener() {
                    @Override
                    public void onConnected() {
                        ViewerConnectionOrchestrator.ConnectMode mode = connectMode;
                        ConnectionState state = mode == ViewerConnectionOrchestrator.ConnectMode.RELAY
                                ? ConnectionState.CONNECTED_RELAY : ConnectionState.CONNECTED_DIRECT;
                        controller.updateState(state,
                                "已连接-" + (mode == null ? "直连" : mode.label()));
                        controller.publishMessage("会话已建立（"
                                + (mode == null ? "直连" : mode.label()) + "）: " + target);
                        startStatsLoop();
                    }

                    @Override
                    public void onConnectFailed(String reason) {
                        controller.updateState(ConnectionState.DISCONNECTED);
                        controller.publishMessage("连接失败: " + reason);
                    }

                    @Override
                    public void onFrameDecoded(long captureToDecodeMs, int width, int height) {
                        decodedCount.incrementAndGet();
                        controller.updateVideoSize(width, height);
                    }

                    @Override
                    public void onLatency(long oneWayMs) {
                        lastLatencyMs = oneWayMs;
                    }

                    @Override
                    public void onCapabilities(boolean persistentConsent, boolean absolutePointer) {
                        StringBuilder sb = new StringBuilder("被控端能力: ");
                        sb.append(absolutePointer ? "绝对坐标注入" : "相对坐标注入");
                        sb.append(persistentConsent ? "，支持免弹窗授权" : "，每次连接需确认");
                        controller.publishMessage(sb.toString());
                    }

                    @Override
                    public void onClosed(String reason) {
                        stopStatsLoop();
                        controller.updateState(ConnectionState.DISCONNECTED);
                        controller.publishMessage("连接已断开: " + reason);
                    }
                });
        client = newClient;
        connectMode = null;
        orchestrator.connect(target, newClient, newClient::onMessage,
                new ViewerConnectionOrchestrator.Listener() {
                    @Override
                    public void onTransportReady(SessionTransport transport,
                                                 ViewerConnectionOrchestrator.ConnectMode mode) {
                        connectMode = mode;
                        controller.publishMessage("连接路径: " + mode.label() + "，正在协商认证 ...");
                        newClient.attachTransport(transport, password);
                    }

                    @Override
                    public void onFailed(String reason) {
                        newClient.abort();
                        controller.updateState(ConnectionState.DISCONNECTED);
                        controller.publishMessage("连接失败: " + reason);
                    }
                });
    }

    @Override
    public void disconnect() {
        orchestrator.cancel();
        ViewerSessionClient current = client;
        if (current != null) {
            if (current.hasTransport()) {
                current.disconnect(); // 结果经 onClosed → updateState(DISCONNECTED)
            } else {
                current.abort(); // 编排未完成，直接收尾
                controller.updateState(ConnectionState.DISCONNECTED);
                controller.publishMessage("已取消连接");
            }
        } else {
            controller.updateState(ConnectionState.DISCONNECTED);
        }
    }

    // ------------------------------------------------------------------
    // InputForwarder（委托当前会话客户端）
    // ------------------------------------------------------------------

    @Override
    public void onMouseMove(double nx, double ny) {
        ViewerSessionClient current = client;
        if (current != null) {
            current.onMouseMove(nx, ny);
        }
    }

    @Override
    public void onMouseButton(int button, boolean pressed, double nx, double ny) {
        ViewerSessionClient current = client;
        if (current != null) {
            current.onMouseButton(button, pressed, nx, ny);
        }
    }

    @Override
    public void onWheel(double dx, double dy) {
        ViewerSessionClient current = client;
        if (current != null) {
            current.onWheel(dx, dy);
        }
    }

    @Override
    public void onKey(int keyCode, String keyChar, boolean pressed) {
        ViewerSessionClient current = client;
        if (current != null) {
            current.onKey(keyCode, keyChar, pressed);
        }
    }

    /** 释放资源（Application.stop 时调用） */
    public void shutdown() {
        statsScheduler.shutdownNow();
        ViewerSessionClient current = client;
        if (current != null) {
            current.disconnect();
        }
    }

    // ------------------------------------------------------------------

    private void startStatsLoop() {
        stopStatsLoop();
        lastCount = 0;
        decodedCount.set(0);
        lastStatsNanos = System.nanoTime();
        statsTask = statsScheduler.scheduleAtFixedRate(this::publishStats,
                STATS_INTERVAL_MS, STATS_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private void stopStatsLoop() {
        ScheduledFuture<?> task = statsTask;
        if (task != null) {
            task.cancel(false);
            statsTask = null;
        }
    }

    private void publishStats() {
        long now = System.nanoTime();
        long count = decodedCount.get();
        long elapsedMs = Math.max(1, (now - lastStatsNanos) / 1_000_000L);
        double fps = (count - lastCount) * 1000.0 / elapsedMs;
        lastCount = count;
        lastStatsNanos = now;
        if (fps < 0) {
            fps = 0;
        }
        controller.updateStats(lastLatencyMs, Math.round(fps * 10.0) / 10.0);
    }

    /**
     * 解析连接目标。
     *
     * @return [host, port]；纯数字 ID 返回 ["id", 原值]；非法格式返回 null
     */
    static String[] parseTarget(String target) {
        if (target == null || target.isBlank()) {
            return null;
        }
        String trimmed = target.trim();
        if (trimmed.matches("[0-9]{1,10}")) {
            return new String[]{"id", trimmed};
        }
        int colon = trimmed.lastIndexOf(':');
        if (colon <= 0 || colon == trimmed.length() - 1) {
            return null;
        }
        String host = trimmed.substring(0, colon);
        String portText = trimmed.substring(colon + 1);
        if (!portText.matches("[0-9]{1,5}")) {
            return null;
        }
        int port = Integer.parseInt(portText);
        if (port <= 0 || port > 65535) {
            return null;
        }
        if (!host.matches("[A-Za-z0-9.\\-]+")) {
            return null;
        }
        return new String[]{host, String.valueOf(port)};
    }
}
