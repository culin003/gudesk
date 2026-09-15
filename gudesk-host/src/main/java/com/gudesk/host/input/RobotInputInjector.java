package com.gudesk.host.input;

import com.gudesk.common.spi.AdapterCapabilities;
import com.gudesk.common.spi.AdapterConfig;
import com.gudesk.common.spi.AdapterException;
import com.gudesk.common.spi.InputInjector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.AWTException;
import java.awt.GraphicsEnvironment;
import java.awt.HeadlessException;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 基于 {@link java.awt.Robot} 的输入注入默认实现（X11/XWayland，仅 Java 标准库）。
 *
 * <p>实现要点：
 * <ul>
 *   <li>init 阶段做 Wayland 会话检测（检测方式与 RobotScreenCapturer 一致，环境变量
 *       Map 注入便于单测）：DISPLAY 存在即允许——X11 会话，或 Wayland 会话 + XWayland
 *       （WAYLAND_DISPLAY 非空但 DISPLAY 存在，Robot 经 XWayland 可用）；仅当
 *       XDG_SESSION_TYPE=wayland 且无 DISPLAY，或 WAYLAND_DISPLAY 非空且 DISPLAY 为空
 *       （Wayland 原生）时抛出 AdapterException；</li>
 *   <li>坐标为归一化值（0~1），经 {@link CoordinateMapper} 乘以默认屏幕宽/高换算为
 *       像素坐标（含 clamp 0~1 边界裁剪）；</li>
 *   <li>java.awt.Robot 各方法调用线程安全：不创建线程，注入在调用线程上同步执行；</li>
 *   <li>键码与 {@code java.awt.event.KeyEvent.VK_*} 常量值一致（common 协议
 *       KeyEvent.key_code 传输该值），直接用于 robot.keyPress/keyRelease；keyCode 无效
 *       且 keyChar 非空时回退 KeyEvent.getExtendedKeyCodeForChar（见
 *       {@link KeyEventCodes#resolveKeyCode(int, String)}）。</li>
 * </ul>
 *
 * <p>平台限制（Robot 本身能力所限，本层无法绕过）：
 * <ul>
 *   <li>水平滚动：java.awt.Robot 仅支持垂直滚轮，{@link #injectWheel} 的 deltaX 只记
 *       debug 日志后忽略；</li>
 *   <li>Win 键（VK_WINDOWS）：Robot 支持注入（X11/Windows 均可）；</li>
 *   <li>Ctrl+Alt+Del：Windows 上被操作系统安全机制（SAS）拦截，注入不生效；
 *       Linux X11 下作为普通键组合可注入；</li>
 *   <li>Wayland 原生会话（无 XWayland）完全无法注入输入，init 阶段检测并拒绝。</li>
 * </ul>
 */
public class RobotInputInjector implements InputInjector {

    private static final Logger LOG = LoggerFactory.getLogger(RobotInputInjector.class);

    /** 输入事件处理频率上限（capabilities().maxFps，单位为事件数/秒而非帧/秒） */
    public static final int MAX_EVENT_RATE = 1000;
    /** 无图形环境时 capabilities 的回退分辨率 */
    private static final int FALLBACK_WIDTH = 1920;
    private static final int FALLBACK_HEIGHT = 1080;

    private Robot robot;
    private int screenW;
    private int screenH;

    /**
     * 默认降级实现工厂，供
     * {@code SpiLoader.load(InputInjector.class, "injector", RobotInputInjector::defaultInjector)}
     * 使用。
     */
    public static RobotInputInjector defaultInjector() {
        return new RobotInputInjector();
    }

    // ------------------------------------------------------------------
    // Wayland 检测（纯函数，注入环境变量 Map 便于单测）
    // ------------------------------------------------------------------

    /**
     * 判断是否处于 Wayland 原生会话（此时 java.awt.Robot 无法注入输入）：
     * XDG_SESSION_TYPE=wayland 且 DISPLAY 为空，或 WAYLAND_DISPLAY 非空且 DISPLAY 为空。
     * DISPLAY 存在（X11 会话，或 Wayland 会话 + XWayland）不视为原生 Wayland——两种场景下
     * Robot 均可注入。
     *
     * @param env 环境变量映射（键：XDG_SESSION_TYPE / WAYLAND_DISPLAY / DISPLAY）
     */
    public static boolean isWaylandOnlySession(Map<String, String> env) {
        String display = env.get("DISPLAY");
        if (display != null && !display.isBlank()) {
            return false; // X11 或 XWayland（Wayland 会话 + XWayland）：Robot 可用
        }
        String sessionType = env.get("XDG_SESSION_TYPE");
        if (sessionType != null && sessionType.trim().equalsIgnoreCase("wayland")) {
            return true;
        }
        String waylandDisplay = env.get("WAYLAND_DISPLAY");
        return waylandDisplay != null && !waylandDisplay.isBlank();
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
        if (isWaylandOnlySession(currentEnv())) {
            throw new AdapterException("Wayland 会话不支持输入注入，当前仅支持 X11");
        }
        if (GraphicsEnvironment.isHeadless()) {
            throw new AdapterException("无图形环境：当前 JVM 处于 headless 模式，无法初始化输入注入");
        }
        Rectangle screen = defaultScreenBounds();
        this.screenW = screen.width;
        this.screenH = screen.height;
        if (screenW <= 0 || screenH <= 0) {
            throw new AdapterException("屏幕尺寸非法: " + screenW + "x" + screenH);
        }
        try {
            this.robot = new Robot();
        } catch (HeadlessException e) {
            throw new AdapterException("无图形环境：无法创建 java.awt.Robot", e);
        } catch (AWTException e) {
            throw new AdapterException("初始化 java.awt.Robot 失败", e);
        }
        LOG.info("RobotInputInjector 初始化完成: 屏幕 {}x{}", screenW, screenH);
    }

    @Override
    public void start() throws AdapterException {
        if (robot == null) {
            throw new AdapterException("尚未初始化，请先调用 init(config)");
        }
        // Robot 调用线程安全且注入为一次性事件模型：无需启动任何线程/循环
    }

    @Override
    public void stop() throws AdapterException {
        // 无后台线程与常驻资源：幂等空实现
    }

    @Override
    public void release() {
        robot = null;
    }

    @Override
    public AdapterCapabilities capabilities() {
        Rectangle screen = defaultScreenBounds();
        return AdapterCapabilities.builder()
                .maxWidth(screen.width)
                .maxHeight(screen.height)
                .maxFps(MAX_EVENT_RATE)
                .hardwareAccelerated(false)
                .build();
    }

    // ------------------------------------------------------------------
    // 输入注入（调用线程直接执行；java.awt.Robot 各方法线程安全）
    // ------------------------------------------------------------------

    @Override
    public void injectMouse(double normalizedX, double normalizedY) {
        Robot r = requireRobot();
        r.mouseMove(CoordinateMapper.toPixel(normalizedX, screenW),
                CoordinateMapper.toPixel(normalizedY, screenH));
    }

    @Override
    public void injectMouseButton(int button, boolean pressed, double x, double y) {
        Robot r = requireRobot();
        int mask = switch (button) {
            case 0 -> InputEvent.BUTTON1_DOWN_MASK; // 左键
            case 1 -> InputEvent.BUTTON2_DOWN_MASK; // 中键
            case 2 -> InputEvent.BUTTON3_DOWN_MASK; // 右键
            default -> 0;
        };
        if (mask == 0) {
            LOG.warn("忽略非法鼠标按键: {}（仅支持 0=左/1=中/2=右）", button);
            return;
        }
        r.mouseMove(CoordinateMapper.toPixel(x, screenW), CoordinateMapper.toPixel(y, screenH));
        if (pressed) {
            r.mousePress(mask);
        } else {
            r.mouseRelease(mask);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>java.awt.Robot 仅支持垂直滚轮：deltaY 逐格注入（正=向上/远离用户，负=向下，
     * 与 {@link Robot#mouseWheel(int)} 语义一致）；deltaX（水平滚动）不受 Robot API
     * 支持，仅记 debug 日志后忽略。
     */
    @Override
    public void injectWheel(double deltaX, double deltaY) {
        Robot r = requireRobot();
        if (deltaX != 0) {
            LOG.debug("java.awt.Robot 不支持水平滚动，忽略 deltaX={}", deltaX);
        }
        int notches = (int) deltaY;
        if (notches != 0) {
            r.mouseWheel(notches);
        }
    }

    @Override
    public void injectKey(int keyCode, String keyChar, boolean pressed) {
        Robot r = requireRobot();
        int code = KeyEventCodes.resolveKeyCode(keyCode, keyChar);
        if (code == KeyEvent.VK_UNDEFINED) {
            LOG.debug("无法解析键码，忽略按键事件: keyCode={}, keyChar={}", keyCode, keyChar);
            return;
        }
        if (pressed) {
            r.keyPress(code);
        } else {
            r.keyRelease(code);
        }
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    /** 确保已初始化（未 init 或已 release → AdapterException） */
    private Robot requireRobot() {
        Robot r = robot;
        if (r == null) {
            throw new AdapterException("输入注入器未初始化或已释放，请先调用 init(config)");
        }
        return r;
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
}
