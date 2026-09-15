package com.gudesk.host.capture;

import com.gudesk.common.spi.AdapterCapabilities;
import com.gudesk.common.spi.AdapterConfig;
import com.gudesk.common.spi.AdapterException;
import com.gudesk.common.spi.NativeFrame;
import org.junit.jupiter.api.Test;

import java.awt.GraphicsEnvironment;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@link RobotScreenCapturer} 单测：Wayland 检测逻辑（注入环境变量 Map）+ 真实捕获
 * （headless/Wayland 环境按 assume 优雅跳过）。
 */
class RobotScreenCapturerTest {

    private static Map<String, String> env(String sessionType, String waylandDisplay, String display) {
        Map<String, String> env = new HashMap<>();
        if (sessionType != null) {
            env.put("XDG_SESSION_TYPE", sessionType);
        }
        if (waylandDisplay != null) {
            env.put("WAYLAND_DISPLAY", waylandDisplay);
        }
        if (display != null) {
            env.put("DISPLAY", display);
        }
        return env;
    }

    @Test
    void Wayland检测_XDG_SESSION_TYPE为wayland() {
        assertTrue(RobotScreenCapturer.isWaylandSession(env("wayland", null, ":0")));
        assertTrue(RobotScreenCapturer.isWaylandSession(env("Wayland", "wayland-0", ":0")), "大小写不敏感");
        assertTrue(RobotScreenCapturer.isWaylandSession(env(" wayland ", null, null)), "容忍首尾空白");
    }

    @Test
    void Wayland检测_WAYLAND_DISPLAY非空且DISPLAY为空() {
        assertTrue(RobotScreenCapturer.isWaylandSession(env(null, "wayland-0", null)));
        assertTrue(RobotScreenCapturer.isWaylandSession(env(null, "wayland-0", "")));
        assertTrue(RobotScreenCapturer.isWaylandSession(env("x11", "wayland-0", "")));
    }

    @Test
    void Wayland检测_X11环境不误判() {
        // X11 会话：即使 WAYLAND_DISPLAY 存在，只要 DISPLAY 可用即视为 X11
        assertFalse(RobotScreenCapturer.isWaylandSession(env("x11", null, ":0")));
        assertFalse(RobotScreenCapturer.isWaylandSession(env("x11", "wayland-0", ":0")));
        assertFalse(RobotScreenCapturer.isWaylandSession(env(null, null, ":1")));
        assertFalse(RobotScreenCapturer.isWaylandSession(env("tty", null, null)));
        assertFalse(RobotScreenCapturer.isWaylandSession(new HashMap<>()));
    }

    @Test
    void Wayland检测_空白字符串按缺失处理() {
        assertFalse(RobotScreenCapturer.isWaylandSession(env(" ", " ", " ")));
        assertFalse(RobotScreenCapturer.isWaylandSession(env("", "", ":0")));
    }

    @Test
    void XWayland检测_WAYLAND_DISPLAY与DISPLAY同时非空() {
        assertTrue(RobotScreenCapturer.isXWaylandSession(env(null, "wayland-0", ":0")));
        assertTrue(RobotScreenCapturer.isXWaylandSession(env("wayland", "wayland-0", ":0")),
                "XDG_SESSION_TYPE=wayland 但 DISPLAY 可用仍属 XWayland（Robot 可工作但半捕获）");
    }

    @Test
    void XWayland检测_纯X11与纯Wayland不误判() {
        assertFalse(RobotScreenCapturer.isXWaylandSession(env("x11", null, ":0")), "纯 X11");
        assertFalse(RobotScreenCapturer.isXWaylandSession(env(null, "wayland-0", null)), "纯 Wayland");
        assertFalse(RobotScreenCapturer.isXWaylandSession(env(null, "wayland-0", "")), "DISPLAY 空白视为缺失");
    }

    @Test
    void XWayland检测_与Wayland检测覆盖三种环境组合() {
        // 纯 X11：两者皆假
        Map<String, String> x11 = env("x11", null, ":0");
        assertFalse(RobotScreenCapturer.isWaylandSession(x11));
        assertFalse(RobotScreenCapturer.isXWaylandSession(x11));

        // 纯 Wayland：仅 Wayland 判定为真
        Map<String, String> wayland = env(null, "wayland-0", null);
        assertTrue(RobotScreenCapturer.isWaylandSession(wayland));
        assertFalse(RobotScreenCapturer.isXWaylandSession(wayland));

        // XWayland（sessionType 未声明 wayland、双 display 并存）：仅 XWayland 判定为真，
        // init 放行 Robot 并输出半捕获警告
        Map<String, String> xwayland = env(null, "wayland-0", ":0");
        assertFalse(RobotScreenCapturer.isWaylandSession(xwayland));
        assertTrue(RobotScreenCapturer.isXWaylandSession(xwayland));

        // 典型 KDE/GNOME Wayland 会话（sessionType=wayland 且 XWayland 可用）：
        // Wayland 判定优先为真，init 直接拒绝（Robot 在此环境下不可靠），
        // XWayland 判定同为真仅作诊断参考
        Map<String, String> waylandWithX = env("wayland", "wayland-0", ":0");
        assertTrue(RobotScreenCapturer.isWaylandSession(waylandWithX));
        assertTrue(RobotScreenCapturer.isXWaylandSession(waylandWithX));
    }

    @Test
    void 能力描述_输出BGRA与最大帧率() {
        AdapterCapabilities caps = new RobotScreenCapturer().capabilities();
        assertNotNull(caps);
        assertTrue(caps.maxWidth() > 0);
        assertTrue(caps.maxHeight() > 0);
        assertEquals(60, caps.maxFps());
        assertFalse(caps.isHardwareAccelerated());
        assertEquals(java.util.List.of("BGRA"), caps.supportedPixelFormats());
        // Robot 的 mouseMove 为绝对坐标；无 restore token 持久化授权能力
        assertTrue(caps.isAbsolutePointer());
        assertFalse(caps.isPersistentConsent());
    }

    @Test
    void Wayland会话下init抛出明确异常() {
        // 本机若非 Wayland，无法直接复现 init 的环境分支；此用例仅在 Wayland 环境下验证
        assumeTrue(RobotScreenCapturer.isWaylandSession(System.getenv()), "当前会话非 Wayland，跳过");
        RobotScreenCapturer capturer = new RobotScreenCapturer();
        AdapterException e = assertThrows(AdapterException.class, () -> capturer.init(config(320, 240)));
        assertEquals("Wayland 会话不支持屏幕捕获，当前仅支持 X11", e.getMessage());
    }

    @Test
    void 真实捕获_输出BGRA帧并回调监听器() throws Exception {
        assumeTrue(!GraphicsEnvironment.isHeadless(), "无图形环境（headless），跳过真实捕获测试");
        assumeTrue(!RobotScreenCapturer.isWaylandSession(System.getenv()),
                "Wayland 会话（仅支持 X11），跳过真实捕获测试");

        RobotScreenCapturer capturer = new RobotScreenCapturer();
        AdapterCapabilities caps = capturer.capabilities();
        int width = Math.min(320, caps.maxWidth());
        int height = Math.min(240, caps.maxHeight());
        capturer.init(config(width, height));

        int frames = 5;
        CountDownLatch delivered = new CountDownLatch(frames);
        AtomicInteger received = new AtomicInteger();
        AtomicInteger closedBytes = new AtomicInteger();
        capturer.setCaptureListener(frame -> {
            try {
                assertEquals("BGRA", frame.pixelFormat());
                assertEquals(width, frame.width());
                assertEquals(height, frame.height());
                assertEquals(width * height * 4L, frame.buffer().remaining());
                received.incrementAndGet();
            } finally {
                // 采样校验后关闭，归还缓冲到复用池
                frame.close();
                closedBytes.incrementAndGet();
                delivered.countDown();
            }
        });
        capturer.start();
        boolean finished = delivered.await(10, TimeUnit.SECONDS);
        capturer.stop();
        capturer.release();

        assertTrue(finished, "10s 内未捕获到 " + frames + " 帧");
        assertEquals(frames, received.get());
        assertEquals(frames, closedBytes.get());
        assertTrue(capturer.deliverCount() >= frames);
        // 交付后缓冲归还池，可再次 start 复用
    }

    private static AdapterConfig config(int width, int height) {
        return AdapterConfig.builder()
                .width(width).height(height).fps(10)
                .pixelFormat(RobotScreenCapturer.PIXEL_FORMAT)
                .build();
    }
}
