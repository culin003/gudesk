package com.gudesk.test;

import com.gudesk.common.spi.AdapterCapabilities;
import com.gudesk.common.spi.AdapterConfig;
import com.gudesk.common.spi.AdapterException;
import com.gudesk.common.spi.NativeFrame;
import com.gudesk.common.spi.ScreenCapturer;

import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

/**
 * 联调测试用彩条捕获器：程序生成每帧平移的竖向彩条画面（模拟持续变化的屏幕），
 * 供无法进行真实屏幕捕获的环境（Wayland/XWayland 抓屏阻塞）下做端到端视频链路验证。
 * 经 -Dgudesk.adapter.capturer=com.gudesk.test.TestPatternCapturer 注入。
 */
public final class TestPatternCapturer implements ScreenCapturer {

    private static final int WIDTH = 1280;
    private static final int HEIGHT = 720;
    private static final int FPS = 30;
    private static final String PIXEL_FORMAT = "BGRA";
    /** 彩条宽度（像素）与每帧平移量（像素），保证画面逐帧变化 */
    private static final int STRIPE_WIDTH = 64;
    private static final int STRIPE_SHIFT = 8;

    private final ConcurrentLinkedQueue<ByteBuffer> pool = new ConcurrentLinkedQueue<>();
    private volatile Consumer<NativeFrame> listener;
    private volatile boolean running;
    private volatile Thread captureThread;
    private int width;
    private int height;
    private long intervalMs;

    @Override
    public void init(AdapterConfig config) throws AdapterException {
        width = config.width() > 0 ? config.width() : WIDTH;
        height = config.height() > 0 ? config.height() : HEIGHT;
        intervalMs = 1000L / Math.max(1, Math.min(FPS, config.fps() > 0 ? config.fps() : FPS));
    }

    @Override
    public void start() throws AdapterException {
        if (running) {
            return;
        }
        running = true;
        captureThread = Thread.ofPlatform().name("test-pattern-capturer").start(this::captureLoop);
    }

    private void captureLoop() {
        long phase = 0;
        while (running) {
            long frameStart = System.nanoTime();
            Consumer<NativeFrame> callback = listener;
            if (callback != null) {
                ByteBuffer buffer = pool.poll();
                if (buffer == null) {
                    buffer = ByteBuffer.allocateDirect(width * height * 4);
                }
                fillPattern(buffer, phase);
                NativeFrame frame = new NativeFrame(buffer, width, height, PIXEL_FORMAT,
                        System.nanoTime(), 0L, f -> pool.offer(f.buffer()));
                try {
                    callback.accept(frame);
                } catch (Throwable t) {
                    frame.close();
                }
            }
            phase = (phase + STRIPE_SHIFT) % STRIPE_WIDTH;
            long elapsedMs = (System.nanoTime() - frameStart) / 1_000_000L;
            try {
                Thread.sleep(Math.max(1, intervalMs - elapsedMs));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** 逐行平铺预生成的彩条行（行内容按 phase 平移，每帧变化） */
    private void fillPattern(ByteBuffer buffer, long phase) {
        int rowBytes = width * 4;
        buffer.clear();
        // 生成第一行（按平移量环绕的竖向彩条）
        for (int x = 0; x < width; x++) {
            int stripe = ((x + (int) phase) / STRIPE_WIDTH) % 4;
            int base = x * 4;
            buffer.put(base, BLUE[stripe]).put(base + 1, GREEN[stripe])
                    .put(base + 2, RED[stripe]).put(base + 3, (byte) 0xFF);
        }
        buffer.position(0).limit(rowBytes);
        ByteBuffer row = buffer.slice();
        // 平铺到所有行
        for (int y = 1; y < height; y++) {
            int pos = y * rowBytes;
            buffer.position(pos).limit(pos + rowBytes);
            ByteBuffer target = buffer.slice();
            target.put(row);
            row.position(0);
        }
        buffer.clear();
    }

    private static final byte[] RED = {(byte) 0x3C, (byte) 0x00, (byte) 0x00, (byte) 0x3C, (byte) 0x3C};
    private static final byte[] GREEN = {(byte) 0x00, (byte) 0x7E, (byte) 0x00, (byte) 0x7E, (byte) 0x7E};
    private static final byte[] BLUE = {(byte) 0x00, (byte) 0x00, (byte) 0xF0, (byte) 0xF0, (byte) 0x00};

    @Override
    public void stop() throws AdapterException {
        running = false;
        Thread t = captureThread;
        if (t != null) {
            t.interrupt();
            try {
                t.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        pool.clear();
    }

    @Override
    public void setCaptureListener(Consumer<NativeFrame> listener) {
        this.listener = listener;
    }

    @Override
    public AdapterCapabilities capabilities() {
        return AdapterCapabilities.builder()
                .maxWidth(WIDTH).maxHeight(HEIGHT).maxFps(FPS)
                .addSupportedPixelFormat(PIXEL_FORMAT)
                .build();
    }
}
