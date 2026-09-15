package com.gudesk.host.portal;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PortalClient} 单测：以内存 FakePortalBus 验证会话编排顺序、参数透传、
 * 失败传播（用户拒绝/超时消息可区分）、restoreToken 透出与输入方法的会话校验。
 * 全部离线，不连真实 D-Bus 总线。
 */
class PortalClientTest {

    // ------------------------------------------------------------------
    // 内存 FakePortalBus：记录调用顺序与参数，可按步骤注入失败
    // ------------------------------------------------------------------

    private static final class FakePortalBus implements PortalBus {

        final List<String> calls = new ArrayList<>();
        int selectedDeviceTypes;
        int selectedPersistMode;
        String selectedRestoreToken;
        String selectedSessionHandle;
        boolean selectedMultiple;
        int selectedCursorMode;
        String startedSessionHandle;
        String startedParentWindow;
        String lastButtonSession;
        int lastButton;
        boolean lastButtonPressed;
        String lastKeySession;
        int lastKeycode;
        boolean lastKeyPressed;
        String closedSessionHandle;
        String lastOpenPipeWireRemoteSession;
        boolean busClosed;

        /** start 成功时返回的会话快照（含 restoreToken） */
        PortalSession startResult = new PortalSession(
                "/org/freedesktop/portal/desktop/session/1_5/gudesk_s1",
                PortalDevice.KEYBOARD | PortalDevice.POINTER,
                List.of(new PortalStream(42, 0, 0, 1920, 1080)),
                "restore-token-1");

        /** 注入 createSession 阶段的失败（如用户拒绝授权弹窗） */
        PortalException failInCreate;
        /** 注入 start 阶段的失败（如用户拒绝或等待超时） */
        PortalException failInStart;

        @Override
        public String createSession() {
            calls.add("createSession");
            if (failInCreate != null) {
                throw failInCreate;
            }
            return "/org/freedesktop/portal/desktop/session/1_5/gudesk_s1";
        }

        @Override
        public void selectDevices(String sessionHandle, int deviceTypes, int persistMode, String restoreToken) {
            calls.add("selectDevices");
            selectedSessionHandle = sessionHandle;
            selectedDeviceTypes = deviceTypes;
            selectedPersistMode = persistMode;
            selectedRestoreToken = restoreToken;
        }

        @Override
        public void selectSources(String sessionHandle, boolean multiple, int cursorMode) {
            calls.add("selectSources");
            selectedMultiple = multiple;
            selectedCursorMode = cursorMode;
        }

        @Override
        public PortalSession start(String sessionHandle, String parentWindow) {
            calls.add("start");
            startedSessionHandle = sessionHandle;
            startedParentWindow = parentWindow;
            if (failInStart != null) {
                throw failInStart;
            }
            return startResult;
        }

        @Override
        public void notifyPointerMotion(String sessionHandle, double dx, double dy) {
            calls.add("notifyPointerMotion");
        }

        @Override
        public void notifyPointerMotionAbsolute(String sessionHandle, int streamNodeId, double x, double y) {
            calls.add("notifyPointerMotionAbsolute");
        }

        @Override
        public void notifyPointerButton(String sessionHandle, int button, boolean pressed) {
            calls.add("notifyPointerButton");
            lastButtonSession = sessionHandle;
            lastButton = button;
            lastButtonPressed = pressed;
        }

        @Override
        public void notifyPointerAxis(String sessionHandle, double dx, double dy) {
            calls.add("notifyPointerAxis");
        }

        @Override
        public void notifyKeyboardKeycode(String sessionHandle, int keycode, boolean pressed) {
            calls.add("notifyKeyboardKeycode");
            lastKeySession = sessionHandle;
            lastKeycode = keycode;
            lastKeyPressed = pressed;
        }

        @Override
        public void closeSession(String sessionHandle) {
            calls.add("closeSession");
            closedSessionHandle = sessionHandle;
        }

        @Override
        public int openPipeWireRemote(String sessionHandle) {
            calls.add("openPipeWireRemote");
            lastOpenPipeWireRemoteSession = sessionHandle;
            return 42;
        }

        @Override
        public void close() {
            busClosed = true;
        }
    }

    // ------------------------------------------------------------------
    // 编排与参数透传
    // ------------------------------------------------------------------

    @Test
    void 建立会话_按创建选设备选源启动的顺序编排() {
        FakePortalBus bus = new FakePortalBus();
        PortalClient client = new PortalClient(bus);

        client.open(PortalDevice.KEYBOARD | PortalDevice.POINTER, true, "prev-token");

        assertEquals(List.of("createSession", "selectDevices", "selectSources", "start"), bus.calls);
        // 持久化选项经 selectDevices 透传（spec：persist_mode/restore_token 属 SelectDevices）
        assertEquals(PortalBus.PERSIST_MODE_UNTIL_REVOKED, bus.selectedPersistMode);
        assertEquals("prev-token", bus.selectedRestoreToken);
        // selectDevices/selectSources/start 的参数
        assertEquals(PortalDevice.KEYBOARD | PortalDevice.POINTER, bus.selectedDeviceTypes);
        assertEquals(bus.startedSessionHandle, bus.selectedSessionHandle);
        assertFalse(bus.selectedMultiple, "单来源（整屏）");
        assertEquals(PortalBus.CURSOR_MODE_EMBEDDED, bus.selectedCursorMode);
        assertEquals("", bus.startedParentWindow, "无窗口父标识");
    }

    @Test
    void 建立会话_不持久化时参数为false与null() {
        FakePortalBus bus = new FakePortalBus();
        PortalClient client = new PortalClient(bus);

        client.open(PortalDevice.POINTER, false, null);

        assertEquals(PortalBus.PERSIST_MODE_NONE, bus.selectedPersistMode);
        assertNull(bus.selectedRestoreToken);
        assertEquals(PortalDevice.POINTER, bus.selectedDeviceTypes);
    }

    @Test
    void 建立会话_返回会话快照并透出restoreToken() {
        FakePortalBus bus = new FakePortalBus();
        PortalClient client = new PortalClient(bus);

        PortalSession session = client.open(PortalDevice.KEYBOARD | PortalDevice.POINTER, false, null);

        assertSame(bus.startResult, session, "open 返回 bus.start 的会话快照");
        assertSame(session, client.currentSession());
        assertEquals("restore-token-1", client.restoreToken());
        assertEquals(session.handle(), client.sessionHandle());
        assertTrue(session.hasDevice(PortalDevice.KEYBOARD));
        assertTrue(session.hasDevice(PortalDevice.POINTER));
        assertFalse(session.hasDevice(PortalDevice.TOUCHSCREEN));
        assertEquals(1, session.streams().size());
        assertEquals(42, session.streams().get(0).nodeId());
    }

    @Test
    void 建立会话_已有活动会话时拒绝重复open() {
        PortalClient client = new PortalClient(new FakePortalBus());
        client.open(PortalDevice.KEYBOARD, false, null);

        PortalException e = assertThrows(PortalException.class,
                () -> client.open(PortalDevice.KEYBOARD, false, null));
        assertTrue(e.getMessage().contains("已存在活动会话"), "消息应指明已存在会话: " + e.getMessage());
    }

    // ------------------------------------------------------------------
    // 失败传播（用户拒绝 / 超时，消息可区分）
    // ------------------------------------------------------------------

    @Test
    void 用户拒绝时抛出异常且消息含拒绝() {
        FakePortalBus bus = new FakePortalBus();
        bus.failInStart = new PortalException("用户拒绝了 Portal Start 授权请求（response=1）");
        PortalClient client = new PortalClient(bus);

        PortalException e = assertThrows(PortalException.class,
                () -> client.open(PortalDevice.KEYBOARD | PortalDevice.POINTER, true, null));
        assertTrue(e.getMessage().contains("拒绝"), "用户拒绝消息应含「拒绝」: " + e.getMessage());
        assertNull(client.currentSession(), "失败后不应持有会话");
        // best-effort 清理半建会话
        assertEquals("closeSession", bus.calls.get(bus.calls.size() - 1));
        assertEquals(bus.startedSessionHandle, bus.closedSessionHandle);
    }

    @Test
    void 等待超时时抛出异常且消息与用户拒绝可区分() {
        FakePortalBus bus = new FakePortalBus();
        bus.failInStart = new PortalException("等待 Portal Start 响应超时（PT30S），用户可能未处理授权弹窗");
        PortalClient client = new PortalClient(bus);

        PortalException e = assertThrows(PortalException.class,
                () -> client.open(PortalDevice.KEYBOARD | PortalDevice.POINTER, true, null));
        assertTrue(e.getMessage().contains("超时"), "超时消息应含「超时」: " + e.getMessage());
        assertFalse(e.getMessage().contains("拒绝"), "超时与用户拒绝应可区分");
        assertNull(client.currentSession());
    }

    @Test
    void 创建阶段失败时同样传播且不持有会话() {
        FakePortalBus bus = new FakePortalBus();
        bus.failInCreate = new PortalException("用户拒绝了 Portal CreateSession 授权请求（response=1）");
        PortalClient client = new PortalClient(bus);

        PortalException e = assertThrows(PortalException.class,
                () -> client.open(PortalDevice.KEYBOARD, false, null));
        assertTrue(e.getMessage().contains("拒绝"));
        assertEquals(List.of("createSession"), bus.calls, "createSession 失败时后续步骤不应执行");
        assertNull(client.currentSession());
    }

    @Test
    void 编排失败后可重新建立会话() {
        FakePortalBus bus = new FakePortalBus();
        bus.failInStart = new PortalException("用户拒绝了 Portal Start 授权请求（response=1）");
        PortalClient client = new PortalClient(bus);

        assertThrows(PortalException.class, () -> client.open(PortalDevice.KEYBOARD, false, null));
        bus.failInStart = null;

        PortalSession session = client.open(PortalDevice.KEYBOARD, false, null);
        assertSame(bus.startResult, session, "失败恢复后可再次建立会话");
    }

    // ------------------------------------------------------------------
    // 输入方法（会话校验 + 委托）
    // ------------------------------------------------------------------

    @Test
    void 输入方法_无会话时抛异常且不触达总线() {
        FakePortalBus bus = new FakePortalBus();
        PortalClient client = new PortalClient(bus);

        assertThrows(PortalException.class, () -> client.notifyPointerMotion(1.0, 2.0));
        assertThrows(PortalException.class, () -> client.notifyPointerMotionAbsolute(42, 0.5, 0.5));
        assertThrows(PortalException.class, () -> client.notifyPointerButton(272, true));
        assertThrows(PortalException.class, () -> client.notifyPointerAxis(0.0, -1.0));
        assertThrows(PortalException.class, () -> client.notifyKeyboardKeycode(28, true));
        assertTrue(bus.calls.isEmpty(), "无会话时不应有任何总线调用");
    }

    @Test
    void 输入方法_有会话时校验后委托总线() {
        FakePortalBus bus = new FakePortalBus();
        PortalClient client = new PortalClient(bus);
        client.open(PortalDevice.KEYBOARD | PortalDevice.POINTER, false, null);

        client.notifyPointerMotion(1.0, 2.0);
        client.notifyPointerMotionAbsolute(42, 0.5, 0.5);
        client.notifyPointerButton(272, true);
        client.notifyPointerAxis(0.0, -1.0);
        client.notifyKeyboardKeycode(28, false);

        assertEquals(List.of("createSession", "selectDevices", "selectSources", "start",
                "notifyPointerMotion", "notifyPointerMotionAbsolute",
                "notifyPointerButton", "notifyPointerAxis", "notifyKeyboardKeycode"), bus.calls);
        assertEquals(bus.startedSessionHandle, bus.lastButtonSession, "注入应携带当前会话句柄");
        assertEquals(272, bus.lastButton);
        assertTrue(bus.lastButtonPressed);
        assertEquals(bus.startedSessionHandle, bus.lastKeySession);
        assertEquals(28, bus.lastKeycode);
        assertFalse(bus.lastKeyPressed);
    }

    // ------------------------------------------------------------------
    // 关闭
    // ------------------------------------------------------------------

    @Test
    void 关闭会话_委托总线并清空当前会话() {
        FakePortalBus bus = new FakePortalBus();
        PortalClient client = new PortalClient(bus);
        client.open(PortalDevice.KEYBOARD, false, null);

        client.closeSession();

        assertEquals("closeSession", bus.calls.get(bus.calls.size() - 1));
        assertEquals(bus.startedSessionHandle, bus.closedSessionHandle);
        assertNull(client.currentSession());
        assertFalse(bus.busClosed, "closeSession 不应释放总线");
    }

    @Test
    void 关闭会话_无会话时抛异常() {
        PortalClient client = new PortalClient(new FakePortalBus());

        PortalException e = assertThrows(PortalException.class, client::closeSession);
        assertTrue(e.getMessage().contains("无活动会话"), "消息应指明无活动会话: " + e.getMessage());
    }

    @Test
    void 关闭客户端_关闭活动会话并释放总线且幂等() {
        FakePortalBus bus = new FakePortalBus();
        PortalClient client = new PortalClient(bus);
        client.open(PortalDevice.KEYBOARD, false, null);

        client.close();
        client.close();

        assertTrue(bus.busClosed, "close 应释放底层总线");
        assertEquals("closeSession", bus.calls.get(bus.calls.size() - 1), "close 应先关闭活动会话再释放总线");
        assertNull(client.currentSession());
    }

    @Test
    void 取PipeWireFd_委托当前会话() throws Exception {
        FakePortalBus bus = new FakePortalBus();
        PortalClient client = new PortalClient(bus);
        client.open(PortalDevice.KEYBOARD | PortalDevice.POINTER, false, null);

        int fd = client.openPipeWireRemote();

        assertEquals(42, fd);
        assertEquals("openPipeWireRemote", bus.calls.get(bus.calls.size() - 1));
        assertEquals(bus.startedSessionHandle, bus.lastOpenPipeWireRemoteSession);
    }

    @Test
    void 取PipeWireFd_无会话时抛异常() {
        PortalClient client = new PortalClient(new FakePortalBus());

        PortalException e = assertThrows(PortalException.class, client::openPipeWireRemote);
        assertTrue(e.getMessage().contains("尚未建立"), "消息应指明未建立会话: " + e.getMessage());
    }
}
