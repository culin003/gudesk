package com.gudesk.host.input;

import com.gudesk.common.spi.AdapterCapabilities;
import com.gudesk.common.spi.AdapterConfig;
import com.gudesk.common.spi.AdapterException;
import com.gudesk.common.spi.InputInjector;
import com.gudesk.common.spi.SpiLoader;
import org.junit.jupiter.api.Test;

import java.awt.GraphicsEnvironment;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.Rectangle;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@link RobotInputInjector} 单测：Wayland 检测逻辑（注入环境变量 Map）+ 真实注入
 * （headless/Wayland 原生环境按 assume 优雅跳过；XWayland 下真实注入鼠标移动并验证指针位置）。
 */
class RobotInputInjectorTest {

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
    void Wayland检测_Wayland原生会话() {
        // XDG_SESSION_TYPE=wayland 且无 DISPLAY → 拒绝注入
        assertTrue(RobotInputInjector.isWaylandOnlySession(env("wayland", null, null)));
        assertTrue(RobotInputInjector.isWaylandOnlySession(env("wayland", null, "")));
        assertTrue(RobotInputInjector.isWaylandOnlySession(env(" Wayland ", null, null)), "大小写不敏感、容忍首尾空白");
        // WAYLAND_DISPLAY 非空且 DISPLAY 为空 → 拒绝注入
        assertTrue(RobotInputInjector.isWaylandOnlySession(env(null, "wayland-0", null)));
        assertTrue(RobotInputInjector.isWaylandOnlySession(env("x11", "wayland-0", "")));
        assertTrue(RobotInputInjector.isWaylandOnlySession(env("wayland", "wayland-0", " ")), "空白 DISPLAY 按缺失处理");
    }

    @Test
    void Wayland检测_XWayland场景允许() {
        // Wayland 会话 + XWayland（WAYLAND_DISPLAY 非空但 DISPLAY 存在）→ Robot 可用，允许
        assertFalse(RobotInputInjector.isWaylandOnlySession(env("wayland", "wayland-0", ":0")));
        assertFalse(RobotInputInjector.isWaylandOnlySession(env("Wayland", null, ":0")), "大小写不敏感");
        assertFalse(RobotInputInjector.isWaylandOnlySession(env("x11", "wayland-0", ":0")));
        assertFalse(RobotInputInjector.isWaylandOnlySession(env("tty", null, ":0")));
    }

    @Test
    void Wayland检测_X11环境不误判() {
        assertFalse(RobotInputInjector.isWaylandOnlySession(env("x11", null, ":0")));
        assertFalse(RobotInputInjector.isWaylandOnlySession(env(null, null, ":1")));
        assertFalse(RobotInputInjector.isWaylandOnlySession(env("tty", null, null)));
        assertFalse(RobotInputInjector.isWaylandOnlySession(env(" ", " ", " ")), "空白字符串按缺失处理，不误判为 Wayland");
        assertFalse(RobotInputInjector.isWaylandOnlySession(new HashMap<>()));
    }

    @Test
    void 能力描述_事件频率上限1000() {
        AdapterCapabilities caps = new RobotInputInjector().capabilities();
        assertNotNull(caps);
        assertTrue(caps.maxWidth() > 0);
        assertTrue(caps.maxHeight() > 0);
        assertEquals(RobotInputInjector.MAX_EVENT_RATE, caps.maxFps());
        assertFalse(caps.isHardwareAccelerated());
        assertTrue(caps.supportedPixelFormats().isEmpty(), "输入注入无像素格式概念");
    }

    @Test
    void 未初始化时注入抛出明确异常() {
        RobotInputInjector injector = new RobotInputInjector();
        assertThrows(AdapterException.class, () -> injector.injectMouse(0.5, 0.5));
        assertThrows(AdapterException.class, () -> injector.injectKey(65, "A", true));
        assertThrows(AdapterException.class, injector::start);
    }

    @Test
    void Wayland原生会话下init抛出明确异常() {
        // 本机若非 Wayland 原生（如 X11/XWayland），无法直接复现 init 的环境分支；
        // 此用例仅在 Wayland 原生环境下验证
        assumeTrue(RobotInputInjector.isWaylandOnlySession(System.getenv()), "当前会话非 Wayland 原生，跳过");
        RobotInputInjector injector = new RobotInputInjector();
        AdapterException e = assertThrows(AdapterException.class,
                () -> injector.init(AdapterConfig.builder().build()));
        assertEquals("Wayland 会话不支持输入注入，当前仅支持 X11", e.getMessage());
    }

    @Test
    void SPI无偏好时默认选择Robot注入器而非Portal() {
        // 兜底回归：即便绕过 selectPlatformDefault 直接 SpiLoader.load，无偏好时也应
        // 选中 ServiceLoader 注册顺序第一位的默认实现 RobotInputInjector，而非 Portal。
        String saved = System.getProperty("gudesk.adapter.injector");
        System.clearProperty("gudesk.adapter.injector");
        try {
            InputInjector injector = SpiLoader.load(InputInjector.class, "injector",
                    RobotInputInjector::defaultInjector);
            assertTrue(injector instanceof RobotInputInjector,
                    "X11 无偏好时应选择 RobotInputInjector，而非 " + injector.getClass().getName());
        } finally {
            if (saved != null) {
                System.setProperty("gudesk.adapter.injector", saved);
            } else {
                System.clearProperty("gudesk.adapter.injector");
            }
        }
    }

    @Test
    void 真实注入_鼠标移动到屏幕中心() {
        assumeTrue(!GraphicsEnvironment.isHeadless(), "无图形环境（headless），跳过真实注入测试");
        // Wayland 原生（无 DISPLAY）无注入通道；XWayland/X11（DISPLAY 存在）可注入
        assumeTrue(!RobotInputInjector.isWaylandOnlySession(System.getenv()),
                "Wayland 原生会话（无 XWayland/X11 DISPLAY），跳过真实注入测试");

        Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getDefaultScreenDevice().getDefaultConfiguration().getBounds();
        int targetX = CoordinateMapper.toPixel(0.5, screen.width);
        int targetY = CoordinateMapper.toPixel(0.5, screen.height);

        RobotInputInjector injector = new RobotInputInjector();
        injector.init(AdapterConfig.builder().build());
        injector.start();
        try {
            injector.injectMouse(0.5, 0.5);
        } finally {
            injector.stop();
            injector.release();
        }

        // 注入后读取系统指针位置，断言接近目标位置（容差 2px；
        // XWayland 下 XTEST 移动的指针可经 MouseInfo 读回）
        Point location = MouseInfo.getPointerInfo().getLocation();
        assertTrue(Math.abs(location.x - targetX) <= 2,
                () -> "指针 x=" + location.x + " 与目标 " + targetX + " 偏差超过 2px");
        assertTrue(Math.abs(location.y - targetY) <= 2,
                () -> "指针 y=" + location.y + " 与目标 " + targetY + " 偏差超过 2px");
    }
}
