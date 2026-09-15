package com.gudesk.host.capture;

import com.gudesk.common.spi.AdapterCapabilities;
import com.gudesk.common.spi.AdapterConfig;
import com.gudesk.common.spi.AdapterException;
import com.gudesk.common.spi.NativeFrame;
import com.gudesk.common.spi.ScreenCapturer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.AWTException;
import java.awt.GraphicsEnvironment;
import java.awt.HeadlessException;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 基于 {@link java.awt.Robot} 的屏幕捕获默认实现（X11，仅 Java 标准库）。
 *
 * <p>实现要点：
 * <ul>
 *   <li>init 阶段做 Wayland 会话检测（Wayland 下 Robot 无法可靠捕获），并包装
 *       {@link HeadlessException} 为「无图形环境」提示；</li>
 *   <li>start 启动一个专用平台线程（普通 {@code new Thread}，命名 {@value #THREAD_NAME}，
 *       绝不使用虚拟线程）按目标帧率循环捕获；</li>
 *   <li>画面变化检测：64×36 网格降采样与上一帧比较（见 {@link FrameDiffDetector}），
 *       静止时输出帧间隔指数退避至 1000ms（1fps 心跳），退避期间每
 *       {@value #BACKOFF_CHECK_INTERVAL_MS}ms 检查一次画面，变化后立即恢复正常帧率；</li>
 *   <li>输出帧为 BGRA 像素写入复用 direct {@link ByteBuffer}（{@value #POOL_SIZE} 个缓冲轮转），
 *       监听器未及时消费（未 close 归还缓冲）则丢帧：交付队列容量为 1，
 *       {@code offer} 失败即丢弃旧帧；</li>
 *   <li>监听器在本捕获线程上同步回调（SPI 契约），消费方负责 {@link NativeFrame#close()}，
 *       跨线程传递前须 {@code retain()}。</li>
 * </ul>
 */
public class RobotScreenCapturer implements ScreenCapturer {

    private static final Logger LOG = LoggerFactory.getLogger(RobotScreenCapturer.class);

    /** 捕获专用平台线程名 */
    public static final String THREAD_NAME = "gudesk-capturer";
    /** 输出像素格式 */
    public static final String PIXEL_FORMAT = "BGRA";
    /** 帧缓冲复用池大小（2~3 个缓冲轮转） */
    public static final int POOL_SIZE = 3;
    /** 退避期间画面变化检查周期上限（毫秒），保证变化后 1s 内恢复正常帧率 */
    public static final long BACKOFF_CHECK_INTERVAL_MS = 100;

    private static final int MAX_FPS = 60;
    /** 无图形环境时 capabilities 的回退分辨率 */
    private static final int FALLBACK_WIDTH = 1920;
    private static final int FALLBACK_HEIGHT = 1080;

    private final ArrayBlockingQueue<ByteBuffer> bufferPool = new ArrayBlockingQueue<>(POOL_SIZE);
    /** 交付槽（容量 1）：监听器异常未消费时保留最新帧、丢弃旧帧 */
    private final ArrayBlockingQueue<NativeFrame> handoff = new ArrayBlockingQueue<>(1);

    private volatile Consumer<NativeFrame> listener;
    private volatile boolean running;
    private volatile Thread captureThread;

    private Robot robot;
    private Rectangle captureBounds;
    private int width;
    private int height;
    private FrameDiffDetector diffDetector;
    private long captureCount;
    private long deliverCount;
    private long dropCount;

    /**
     * 默认降级实现工厂，供
     * {@code SpiLoader.load(ScreenCapturer.class, "capturer", RobotScreenCapturer::defaultCapturer)}
     * 使用。
     */
    public static RobotScreenCapturer defaultCapturer() {
        return new RobotScreenCapturer();
    }

    // ------------------------------------------------------------------
    // Wayland 检测（纯函数，注入环境变量 Map 便于单测）
    // ------------------------------------------------------------------

    /**
     * 判断是否处于 Wayland 会话（此时 java.awt.Robot 无法可靠捕获屏幕）：
     * {@code XDG_SESSION_TYPE=wayland}，或 {@code WAYLAND_DISPLAY} 非空且 {@code DISPLAY} 为空。
     *
     * @param env 环境变量映射（键：XDG_SESSION_TYPE / WAYLAND_DISPLAY / DISPLAY）
     */
    public static boolean isWaylandSession(Map<String, String> env) {
        String sessionType = env.get("XDG_SESSION_TYPE");
        if (sessionType != null && sessionType.trim().equalsIgnoreCase("wayland")) {
            return true;
        }
        String waylandDisplay = env.get("WAYLAND_DISPLAY");
        String display = env.get("DISPLAY");
        return waylandDisplay != null && !waylandDisplay.isBlank()
                && (display == null || display.isBlank());
    }

    /** 当前进程环境变量快照 */
    public static Map<String, String> currentEnv() {
        return new HashMap<>(System.getenv());
    }

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    @Override
    public void init(AdapterConfig config) throws AdapterException {
        Objects.requireNonNull(config, "config");
        if (isWaylandSession(currentEnv())) {
            throw new AdapterException("Wayland 会话不支持屏幕捕获，当前仅支持 X11");
        }
        if (GraphicsEnvironment.isHeadless()) {
            throw new AdapterException("无图形环境：当前 JVM 处于 headless 模式，无法初始化屏幕捕获");
        }
        Rectangle screen = defaultScreenBounds();
        this.width = config.width() > 0 ? config.width() : screen.width;
        this.height = config.height() > 0 ? config.height() : screen.height;
        if (width <= 0 || height <= 0) {
            throw new AdapterException("捕获尺寸非法: " + width + "x" + height);
        }
        int fps = config.fps() > 0 ? config.fps() : 15;
        this.diffDetector = new FrameDiffDetector(1000L / Math.max(1, fps),
                FrameDiffDetector.DEFAULT_GRID_WIDTH * FrameDiffDetector.DEFAULT_GRID_HEIGHT,
                FrameDiffDetector.DEFAULT_STATIC_THRESHOLD_RATIO,
                FrameDiffDetector.DEFAULT_CHANNEL_TOLERANCE,
                FrameDiffDetector.DEFAULT_STATIC_FRAMES_BEFORE_BACKOFF,
                FrameDiffDetector.DEFAULT_MAX_BACKOFF_MS);
        try {
            this.robot = new Robot();
        } catch (HeadlessException e) {
            throw new AdapterException("无图形环境：无法创建 java.awt.Robot", e);
        } catch (AWTException e) {
            throw new AdapterException("初始化 java.awt.Robot 失败", e);
        }
        this.captureBounds = new Rectangle(width, height);
        LOG.info("RobotScreenCapturer 初始化完成: {}x{}, 目标帧率={}", width, height, fps);
    }

    @Override
    public void start() throws AdapterException {
        if (robot == null) {
            throw new AdapterException("尚未初始化，请先调用 init(config)");
        }
        if (running) {
            return;
        }
        running = true;
        diffDetector.reset();
        // 重建缓冲池（幂等：支持多次 start/stop 周期）
        bufferPool.clear();
        for (int i = 0; i < POOL_SIZE; i++) {
            bufferPool.offer(ByteBuffer.allocateDirect(width * height * 4));
        }
        captureCount = 0;
        deliverCount = 0;
        dropCount = 0;
        captureThread = new Thread(this::captureLoop, THREAD_NAME);
        captureThread.setDaemon(true);
        captureThread.start();
    }

    @Override
    public void stop() throws AdapterException {
        running = false;
        Thread thread = captureThread;
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            captureThread = null;
        }
    }

    @Override
    public void release() {
        stop();
        // 丢弃仍未被消费的交付帧，归还缓冲由 close 触发
        NativeFrame stale = handoff.poll();
        if (stale != null) {
            stale.close();
        }
        bufferPool.clear();
        robot = null;
    }

    @Override
    public AdapterCapabilities capabilities() {
        Rectangle screen = defaultScreenBounds();
        return AdapterCapabilities.builder()
                .maxWidth(screen.width)
                .maxHeight(screen.height)
                .maxFps(MAX_FPS)
                .hardwareAccelerated(false)
                .addSupportedPixelFormat(PIXEL_FORMAT)
                .build();
    }

    @Override
    public void setCaptureListener(Consumer<NativeFrame> listener) {
        this.listener = listener;
    }

    // ------------------------------------------------------------------
    // 捕获循环（专用平台线程）
    // ------------------------------------------------------------------

    private void captureLoop() {
        long lastOutputNs = 0;
        while (running) {
            long captureStartNs = System.nanoTime();
            BufferedImage image;
            try {
                image = robot.createScreenCapture(captureBounds);
            } catch (Throwable t) {
                LOG.warn("屏幕捕获失败，{}ms 后重试", BACKOFF_CHECK_INTERVAL_MS, t);
                if (!sleepQuietly(BACKOFF_CHECK_INTERVAL_MS)) {
                    break;
                }
                continue;
            }
            captureCount++;
            int[] pixels = imagePixels(image);
            boolean changed = diffDetector.update(sampleGrid(pixels, image.getWidth(), image.getHeight()));
            long now = System.nanoTime();
            long intervalMs = diffDetector.currentIntervalMs();
            boolean heartbeatDue = now - lastOutputNs >= intervalMs * 1_000_000L;
            if (changed || (diffDetector.isBackingOff() && heartbeatDue)) {
                deliver(pixels, image.getWidth(), image.getHeight());
                lastOutputNs = now;
            }
            // 睡眠至下一次捕获：正常按基础间隔（未退避时 currentIntervalMs() 即基础间隔）；
            // 退避期间最长每 100ms 检查一次画面变化，保证变化后 1s 内恢复
            long sleepMs = diffDetector.isBackingOff()
                    ? Math.min(intervalMs, BACKOFF_CHECK_INTERVAL_MS)
                    : intervalMs;
            long elapsedMs = (System.nanoTime() - captureStartNs) / 1_000_000L;
            long waitMs = Math.max(1, sleepMs - elapsedMs);
            if (!sleepQuietly(waitMs)) {
                break;
            }
        }
        LOG.info("捕获线程退出: 捕获 {} 帧, 交付 {} 帧, 丢弃 {} 帧", captureCount, deliverCount, dropCount);
    }

    /**
     * 交付一帧：从复用池取 BGRA 缓冲写入像素，经容量 1 的交付槽在捕获线程回调监听器；
     * 监听器未及时消费（旧帧滞留交付槽或缓冲未归还）时丢弃旧帧/当前帧。
     */
    private void deliver(int[] pixels, int imageWidth, int imageHeight) {
        Consumer<NativeFrame> consumer = listener;
        if (consumer == null) {
            return;
        }
        ByteBuffer buffer = bufferPool.poll();
        if (buffer == null) {
            // 消费方持有全部缓冲未归还：丢弃当前帧，保证捕获节奏不被拖垮
            dropCount++;
            return;
        }
        // BufferedImage 的 ARGB int[] 像素 → BGRA 字节：小端序写入保证字节序为 B,G,R,A
        buffer.clear();
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        buffer.asIntBuffer().put(pixels, 0, imageWidth * imageHeight);
        NativeFrame frame = new NativeFrame(buffer, imageWidth, imageHeight, PIXEL_FORMAT,
                System.nanoTime(), 0L, f -> {
                    ByteBuffer bb = f.buffer();
                    bb.clear();
                    bufferPool.offer(bb);
                });
        deliverCount++;
        // 交付槽容量 1：旧帧未被消费则丢弃旧帧，保留最新帧
        if (!handoff.offer(frame)) {
            NativeFrame stale = handoff.poll();
            if (stale != null) {
                stale.close();
                dropCount++;
            }
            handoff.offer(frame);
        }
        // 在捕获线程上回调监听器（SPI 契约）；监听器异常时旧帧滞留交付槽，下一帧被丢弃
        NativeFrame pending;
        while ((pending = handoff.poll()) != null) {
            try {
                consumer.accept(pending);
            } catch (Throwable t) {
                LOG.warn("捕获监听器异常", t);
            }
        }
    }

    // ------------------------------------------------------------------
    // 像素工具
    // ------------------------------------------------------------------

    /** 取图像 ARGB 像素（快路径直接引用内部 int[]，慢路径整帧 getRGB 拷贝一次） */
    private static int[] imagePixels(BufferedImage image) {
        int type = image.getType();
        if (type == BufferedImage.TYPE_INT_ARGB || type == BufferedImage.TYPE_INT_RGB
                || type == BufferedImage.TYPE_INT_ARGB_PRE || type == BufferedImage.TYPE_INT_BGR) {
            return ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
        }
        return image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
    }

    /** 64×36 网格降采样（等距采样，返回长度 gridW*gridH 的 ARGB 数组） */
    private static int[] sampleGrid(int[] pixels, int width, int height) {
        int gridW = FrameDiffDetector.DEFAULT_GRID_WIDTH;
        int gridH = FrameDiffDetector.DEFAULT_GRID_HEIGHT;
        int[] samples = new int[gridW * gridH];
        int i = 0;
        for (int gy = 0; gy < gridH; gy++) {
            int y = Math.min(height - 1, gy * (height - 1) / (gridH - 1));
            for (int gx = 0; gx < gridW; gx++) {
                int x = Math.min(width - 1, gx * (width - 1) / (gridW - 1));
                samples[i++] = pixels[y * width + x];
            }
        }
        return samples;
    }

    /** 默认屏幕区域（headless 等异常场景回退到 1920×1080） */
    private static Rectangle defaultScreenBounds() {
        try {
            if (!GraphicsEnvironment.isHeadless()) {
                return GraphicsEnvironment.getLocalGraphicsEnvironment()
                        .getDefaultScreenDevice()
                        .getDefaultConfiguration()
                        .getBounds();
            }
        } catch (Throwable t) {
            LOG.debug("读取默认屏幕尺寸失败，使用回退分辨率", t);
        }
        return new Rectangle(FALLBACK_WIDTH, FALLBACK_HEIGHT);
    }

    /** 可中断睡眠，返回 false 表示线程已被中断（应退出循环） */
    private static boolean sleepQuietly(long ms) {
        try {
            TimeUnit.MILLISECONDS.sleep(Math.max(1, ms));
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    // ------------------------------------------------------------------
    // 统计（诊断用）
    // ------------------------------------------------------------------

    /** 累计捕获帧数（含未输出的静止检查帧） */
    public long captureCount() {
        return captureCount;
    }

    /** 累计交付帧数 */
    public long deliverCount() {
        return deliverCount;
    }

    /** 累计丢弃帧数 */
    public long dropCount() {
        return dropCount;
    }
}
