package com.gudesk.host.portal;

import com.gudesk.common.spi.AdapterConfig;
import com.gudesk.common.spi.AdapterException;
import com.gudesk.host.input.PortalInputInjector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.awt.event.KeyEvent;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PortalInputInjector} 单测：以内存 FakePortalBus（记录 Notify* 调用与参数）
 * 离线验证权限检查链——会话有效性（未启动/失效抛 AdapterException 断开会话）、
 * 设备部分授权（未授权设备事件丢弃不影响另一设备）、坐标 clamp、按钮/键码 evdev
 * 转换、stop 引用计数释放。不连接真实 D-Bus。
 */
class PortalInputInjectorTest {

    /** Linux evdev 标准常量（linux/input-event-codes.h） */
    private static final int KEY_A = 30;
    private static final int KEY_ENTER = 28;
    private static final int BTN_LEFT = 0x110;
    private static final int BTN_RIGHT = 0x111;

    private static final Map<String, String> WAYLAND_ENV =
            Map.of("XDG_SESSION_TYPE", "wayland", "WAYLAND_DISPLAY", "wayland-0");
    private static final Map<String, String> X11_ENV = Map.of("XDG_SESSION_TYPE", "x11", "DISPLAY", ":0");

    // ------------------------------------------------------------------
    // 内存 FakePortalBus：记录 Notify* 调用名与参数（单条记录 = 方法名 + 参数）
    // ------------------------------------------------------------------

    private static final class RecordingBus implements PortalBus {
        final List<String> calls = new ArrayList<>();
        boolean busClosed;
        /** start 返回的设备授权（默认全授权） */
        int grantedDevices = PortalDevice.KEYBOARD | PortalDevice.POINTER;
        /** 下一次 Notify* 调用注入瞬时失败（模拟单次 D-Bus 抖动，会话本身仍有效） */
        boolean failNextNotify;

        @Override
        public String createSession() {
            return "/org/freedesktop/portal/desktop/session/1_5/inj";
        }

        @Override
        public void selectDevices(String sessionHandle, int deviceTypes, int persistMode, String restoreToken) {
        }

        @Override
        public void selectSources(String sessionHandle, boolean multiple, int cursorMode) {
        }

        @Override
        public PortalSession start(String sessionHandle, String parentWindow) {
            return new PortalSession(sessionHandle, grantedDevices,
                    List.of(new PortalStream(42, 0, 0, 1920, 1080)), null);
        }

        @Override
        public void notifyPointerMotion(String sessionHandle, double dx, double dy) {
            calls.add("notifyPointerMotion");
        }

        @Override
        public void notifyPointerMotionAbsolute(String sessionHandle, int streamNodeId, double x, double y) {
            failOnce();
            calls.add("notifyPointerMotionAbsolute:" + streamNodeId + "," + x + "," + y);
        }

        @Override
        public void notifyPointerButton(String sessionHandle, int button, boolean pressed) {
            failOnce();
            calls.add("notifyPointerButton:" + button + "," + pressed);
        }

        @Override
        public void notifyPointerAxis(String sessionHandle, double dx, double dy) {
            failOnce();
            calls.add("notifyPointerAxis:" + dx + "," + dy);
        }

        @Override
        public void notifyKeyboardKeycode(String sessionHandle, int keycode, boolean pressed) {
            failOnce();
            calls.add("notifyKeyboardKeycode:" + keycode + "," + pressed);
        }

        private void failOnce() throws PortalException {
            if (failNextNotify) {
                failNextNotify = false;
                throw new PortalException("瞬时 D-Bus 抖动");
            }
        }

        @Override
        public void closeSession(String sessionHandle) {
        }

        @Override
        public int openPipeWireRemote(String sessionHandle) {
            return 7;
        }

        @Override
        public void close() {
            busClosed = true;
        }
    }

    private RecordingBus bus;
    private PortalContextHolder holder;

    @BeforeEach
    void setUp() {
        bus = new RecordingBus();
        holder = new PortalContextHolder(() -> bus, Duration.ofSeconds(1));
        PortalContextHolder.installForTest(holder);
    }

    @AfterEach
    void tearDown() {
        PortalContextHolder.resetForTest();
    }

    /** init（Wayland 环境）→ start，返回就绪的注入器 */
    private PortalInputInjector startedInjector() throws AdapterException {
        PortalInputInjector injector = new PortalInputInjector();
        injector.init(AdapterConfig.builder().build(), WAYLAND_ENV);
        injector.start();
        return injector;
    }

    // ------------------------------------------------------------------
    // 生命周期与检查链第 1 步（会话有效性）
    // ------------------------------------------------------------------

    @Test
    void 非Wayland环境init_抛AdapterException() {
        PortalInputInjector injector = new PortalInputInjector();
        assertThrows(AdapterException.class,
                () -> injector.init(AdapterConfig.builder().build(), X11_ENV));
    }

    @Test
    void 未start即注入_抛AdapterException断开会话() {
        PortalInputInjector injector = new PortalInputInjector();
        assertThrows(AdapterException.class, () -> injector.injectMouse(0.5, 0.5));
        assertThrows(AdapterException.class, () -> injector.injectKey(KeyEvent.VK_A, "a", true));
    }

    @Test
    void stop释放租约_引用归零关总线_后续注入抛异常() throws Exception {
        PortalInputInjector injector = startedInjector();
        assertFalse(bus.busClosed);
        injector.stop();
        assertTrue(bus.busClosed, "唯一使用者 stop 应关闭会话总线");
        assertThrows(AdapterException.class, () -> injector.injectMouse(0.5, 0.5));
    }

    @Test
    void 会话被invalidate_后续注入抛AdapterException() throws Exception {
        PortalInputInjector injector = startedInjector();
        holder.invalidate("用户撤销授权");
        assertThrows(AdapterException.class, () -> injector.injectMouse(0.5, 0.5));
        assertThrows(AdapterException.class, () -> injector.injectKey(KeyEvent.VK_A, "a", true));
    }

    @Test
    void 与捕获器共享会话_start零往返_各自stop后引用归零() throws Exception {
        PortalInputInjector injector = startedInjector();
        try (PortalContextHolder.Lease captureLease = holder.acquire()) {
            assertEquals(42, captureLease.stream().nodeId(), "注入器已建立的会话被捕获器直接复用");
            injector.injectMouse(0.5, 0.5); // 注入仍可用
        }
        assertFalse(bus.busClosed, "捕获器先释放，注入器仍持有引用");
        injector.stop();
        assertTrue(bus.busClosed, "最后一个使用者释放后关闭");
    }

    // ------------------------------------------------------------------
    // 检查链第 2~4 步：设备授权 / 坐标 / 键码转换
    // ------------------------------------------------------------------

    @Test
    void 全授权_注入调用正确的notify方法与参数() throws Exception {
        PortalInputInjector injector = startedInjector();

        injector.injectMouse(0.25, 0.5);
        assertEquals(List.of("notifyPointerMotionAbsolute:42,0.25,0.5"), bus.calls);

        injector.injectMouseButton(0, true, 0.1, 0.2);
        assertEquals("notifyPointerMotionAbsolute:42,0.1,0.2", bus.calls.get(1), "按键前先移动到目标位置");
        assertEquals("notifyPointerButton:" + BTN_LEFT + ",true", bus.calls.get(2));

        injector.injectMouseButton(2, false, 0.3, 0.4);
        assertEquals("notifyPointerButton:" + BTN_RIGHT + ",false", bus.calls.get(4));

        injector.injectWheel(3, -2);
        assertEquals("notifyPointerAxis:3.0,-2.0", bus.calls.get(5));

        injector.injectKey(KeyEvent.VK_A, "a", true);
        injector.injectKey(KeyEvent.VK_A, "a", false);
        assertEquals("notifyKeyboardKeycode:" + KEY_A + ",true", bus.calls.get(6));
        assertEquals("notifyKeyboardKeycode:" + KEY_A + ",false", bus.calls.get(7));

        assertEquals(0, injector.droppedEvents());
    }

    @Test
    void 只授权指针_键盘事件丢弃_鼠标事件不受影响() throws Exception {
        bus.grantedDevices = PortalDevice.POINTER;
        PortalInputInjector injector = startedInjector();

        injector.injectKey(KeyEvent.VK_A, "a", true);
        injector.injectMouse(0.5, 0.5);

        assertEquals(List.of("notifyPointerMotionAbsolute:42,0.5,0.5"), bus.calls,
                "未授权键盘的 Notify 调用不应发生");
        assertEquals(1, injector.droppedEvents());
    }

    @Test
    void 只授权键盘_鼠标事件丢弃_键盘事件不受影响() throws Exception {
        bus.grantedDevices = PortalDevice.KEYBOARD;
        PortalInputInjector injector = startedInjector();

        injector.injectMouse(0.5, 0.5);
        injector.injectMouseButton(0, true, 0.1, 0.2);
        injector.injectWheel(0, -1);
        injector.injectKey(KeyEvent.VK_A, "a", true);

        assertEquals(List.of("notifyKeyboardKeycode:" + KEY_A + ",true"), bus.calls,
                "未授权指针的 Notify 调用不应发生");
        assertEquals(3, injector.droppedEvents());
    }

    @Test
    void 坐标越界clamp到0到1() throws Exception {
        PortalInputInjector injector = startedInjector();
        injector.injectMouse(-0.5, 2.0);
        assertEquals("notifyPointerMotionAbsolute:42,0.0,1.0", bus.calls.get(0));
    }

    @Test
    void 非法按钮与无法解析键码_丢弃不调用notify() throws Exception {
        PortalInputInjector injector = startedInjector();
        injector.injectMouseButton(9, true, 0.5, 0.5); // 非法按钮
        injector.injectKey(0, "", true);               // 无法解析（VK_UNDEFINED + 无字符）
        assertTrue(bus.calls.isEmpty());
        assertEquals(2, injector.droppedEvents());
    }

    @Test
    void notify瞬时失败_会话仍有效时不抛继续服务() throws Exception {
        PortalInputInjector injector = startedInjector();
        bus.failNextNotify = true;
        injector.injectKey(KeyEvent.VK_ENTER, "\n", true); // 单次 D-Bus 抖动：不抛、不丢弃
        assertEquals(0, injector.droppedEvents());
        injector.injectKey(KeyEvent.VK_ENTER, "\n", false); // 会话仍有效：下次注入正常
        assertEquals("notifyKeyboardKeycode:" + KEY_ENTER + ",false", bus.calls.get(0));
    }
}
