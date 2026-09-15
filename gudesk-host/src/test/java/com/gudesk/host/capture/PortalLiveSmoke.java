package com.gudesk.host.capture;

import com.gudesk.common.spi.AdapterConfig;
import com.gudesk.common.spi.NativeFrame;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Portal 捕获真实链路冒烟（联调用，不入常规测试）：
 * init → start（弹授权对话框）→ 收帧 12s → 统计 + 首帧存 /tmp/portal-frame.png。
 * 运行方式见 spec Task 3 SubTask 3.4。
 */
public class PortalLiveSmoke {

    public static void main(String[] args) throws Exception {
        int seconds = args.length > 0 ? Integer.parseInt(args[0]) : 12;
        PortalScreenCapturer capturer = new PortalScreenCapturer();
        capturer.init(AdapterConfig.builder().fps(30).build());

        AtomicInteger frames = new AtomicInteger();
        AtomicReference<NativeFrame> first = new AtomicReference<>();
        CountDownLatch gotFirst = new CountDownLatch(1);
        capturer.setCaptureListener(frame -> {
            if (frames.incrementAndGet() == 1) {
                first.set(frame);
                gotFirst.countDown();
                return; // 首帧保留用于存盘，不归还池
            }
            frame.close();
        });

        System.out.println(">>> start() 调用：授权对话框应已弹出，请允许…");
        capturer.start();
        System.out.println(">>> 捕获已启动，等待 " + seconds + " 秒…");

        boolean hasFirst = gotFirst.await(seconds, TimeUnit.SECONDS);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        long lastCount = 0;
        while (System.nanoTime() < deadline) {
            Thread.sleep(1000);
            long c = frames.get();
            System.out.printf(">>> 1s 窗口帧数: %d（累计 %d）%n", c - lastCount, c);
            lastCount = c;
        }

        capturer.stop();
        System.out.printf(">>> 结果: 共 %d 帧, 实际帧率约 %.1f fps%n",
                frames.get(), frames.get() / (double) seconds);
        System.out.printf(">>> 统计: 读取=%d 交付=%d 丢弃=%d%n",
                capturer.frameCount(), capturer.deliverCount(), capturer.dropCount());

        if (hasFirst) {
            NativeFrame f = first.get();
            System.out.printf(">>> 首帧: %dx%d %s，保存到 /tmp/portal-frame.png%n",
                    f.width(), f.height(), f.pixelFormat());
            savePng(f, "/tmp/portal-frame.png");
            f.close();
            System.out.println(">>> 冒烟通过 ✓");
        } else {
            System.out.println(">>> 冒烟失败：12 秒内未收到任何帧");
            System.exit(1);
        }
    }

    /** BGRA → PNG（仅冒烟诊断用） */
    private static void savePng(NativeFrame frame, String path) throws Exception {
        ByteBuffer buf = frame.buffer();
        int w = frame.width();
        int h = frame.height();
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        byte[] bgra = buf.array();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = (y * w + x) * 4;
                int b = bgra[i] & 0xFF;
                int g = bgra[i + 1] & 0xFF;
                int r = bgra[i + 2] & 0xFF;
                img.setRGB(x, y, (r << 16) | (g << 8) | b);
            }
        }
        ImageIO.write(img, "png", new java.io.File(path));
    }
}
