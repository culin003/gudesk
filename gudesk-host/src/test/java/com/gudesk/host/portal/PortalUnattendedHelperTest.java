package com.gudesk.host.portal;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PortalUnattendedHelper} 单测：后端探测各分支（UNKNOWN/不支持持久化/支持）、
 * 引导授权成功/失败、token 落盘确认。以内存 FakeBus + @TempDir store 离线执行。
 */
class PortalUnattendedHelperTest {

    // ------------------------------------------------------------------
    // 内存 FakeBus：start 返回可配置会话快照（restoreToken 决定是否落盘）
    // ------------------------------------------------------------------

    private static final class FakeBus implements PortalBus {
        PortalException failInStart;
        PortalSession startResult = new PortalSession(
                "/org/freedesktop/portal/desktop/session/1_5/gudesk_s1",
                PortalDevice.KEYBOARD | PortalDevice.POINTER,
                List.of(new PortalStream(42, 0, 0, 1920, 1080)),
                "token-restore-1");
        boolean closed;

        @Override
        public String createSession() {
            return "/org/freedesktop/portal/desktop/session/1_5/gudesk_s1";
        }

        @Override
        public void selectDevices(String sessionHandle, int deviceTypes, int persistMode, String restoreToken) {
        }

        @Override
        public void selectSources(String sessionHandle, boolean multiple, int cursorMode) {
        }

        @Override
        public PortalSession start(String sessionHandle, String parentWindow) throws PortalException {
            if (failInStart != null) {
                throw failInStart;
            }
            return startResult;
        }

        @Override
        public int openPipeWireRemote(String sessionHandle) {
            return 7;
        }

        @Override
        public void notifyPointerMotion(String sessionHandle, double dx, double dy) {
        }

        @Override
        public void notifyPointerMotionAbsolute(String sessionHandle, int streamNodeId, double x, double y) {
        }

        @Override
        public void notifyPointerButton(String sessionHandle, int button, boolean pressed) {
        }

        @Override
        public void notifyPointerAxis(String sessionHandle, double dx, double dy) {
        }

        @Override
        public void notifyKeyboardKeycode(String sessionHandle, int keycode, boolean pressed) {
        }

        @Override
        public void closeSession(String sessionHandle) {
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    @AfterEach
    void reset() {
        PortalContextHolder.resetForTest();
    }

    private PortalContextHolder holderWith(FakeBus bus, PortalTokenStore store) {
        return new PortalContextHolder(() -> bus, Duration.ofSeconds(1), store);
    }

    private String run(PortalBackendInfo info, FakeBus bus, PortalTokenStore store) {
        PortalContextHolder holder = holderWith(bus, store);
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int code = PortalUnattendedHelper.enableUnattended(
                info, holder, store, new PrintStream(buf, true));
        return code + "|" + buf;
    }

    @Test
    void 后端UNKNOWN_返回2() {
        FakeBus bus = new FakeBus();
        String out = run(new PortalBackendInfo(PortalBackendKind.UNKNOWN, false, false),
                bus, null);
        assertTrue(out.startsWith("2|"), "UNKNOWN 后端应返回 2");
        assertTrue(out.contains("未检测到"), out);
    }

    @Test
    void 后端GNOME不支持持久化_返回2() {
        FakeBus bus = new FakeBus();
        String out = run(new PortalBackendInfo(PortalBackendKind.GNOME, false, true),
                bus, null);
        assertTrue(out.startsWith("2|"), "GNOME 不支持持久化应返回 2");
        assertTrue(out.contains("不支持持久化"), out);
    }

    @Test
    void 后端KDE_授权成功且token落盘_返回0(@TempDir Path dir) throws Exception {
        FakeBus bus = new FakeBus();
        PortalTokenStore store = new PortalTokenStore(dir.resolve("portal_token"));
        String out = run(new PortalBackendInfo(PortalBackendKind.KDE, true, true), bus, store);
        assertTrue(out.startsWith("0|"), out);
        assertNotNull(store.load(), "授权成功应落盘 token");
        assertEquals("token-restore-1", store.load().token());
        assertTrue(bus.closed, "引导完成后应释放会话（close 总线）");
    }

    @Test
    void 授权失败_返回2(@TempDir Path dir) {
        FakeBus bus = new FakeBus();
        bus.failInStart = new PortalException("用户拒绝");
        PortalTokenStore store = new PortalTokenStore(dir.resolve("portal_token"));
        String out = run(new PortalBackendInfo(PortalBackendKind.KDE, true, true), bus, store);
        assertTrue(out.startsWith("2|"), out);
        assertTrue(out.contains("授权失败"), out);
    }

    @Test
    void 后端未签发restoreToken_授权完成但无法落盘_返回2(@TempDir Path dir) {
        FakeBus bus = new FakeBus();
        bus.startResult = new PortalSession(
                "/org/freedesktop/portal/desktop/session/1_5/gudesk_s1",
                PortalDevice.KEYBOARD | PortalDevice.POINTER,
                List.of(new PortalStream(42, 0, 0, 1920, 1080)),
                null); // 后端未签发 token
        PortalTokenStore store = new PortalTokenStore(dir.resolve("portal_token"));
        String out = run(new PortalBackendInfo(PortalBackendKind.KDE, true, true), bus, store);
        assertTrue(out.startsWith("2|"), out);
        assertTrue(out.contains("未落盘"), out);
    }
}
