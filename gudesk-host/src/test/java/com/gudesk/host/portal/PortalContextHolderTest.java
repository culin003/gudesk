package com.gudesk.host.portal;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PortalContextHolder} 单测：以内存 FakePortalBus 验证引用计数共享
 * （首建复用/末释才关）、restore token 记忆与失效降级重试、invalidate 的
 * 监听器通知与在途 Lease 失效、设备授权查询与失败传播。全部离线。
 */
class PortalContextHolderTest {

    // ------------------------------------------------------------------
    // 内存 FakePortalBus：按会话实例化（工厂语义），记录调用与参数
    // ------------------------------------------------------------------

    private static final class FakeBus implements PortalBus {
        final List<String> calls = new ArrayList<>();
        String restoreTokenReceived;
        /** selectDevices 收到非空 token 时注入的失败（模拟用户已撤销授权） */
        PortalException failOnRestoreToken;
        /** createSession 阶段注入的失败（模拟用户拒绝/超时） */
        PortalException failInCreate;
        /** start 返回的会话快照（devices 可配置以测试部分授权） */
        PortalSession startResult = new PortalSession(
                "/org/freedesktop/portal/desktop/session/1_5/gudesk_s" + SEQ.incrementAndGet(),
                PortalDevice.KEYBOARD | PortalDevice.POINTER,
                List.of(new PortalStream(42, 0, 0, 1920, 1080)),
                "token-" + SEQ.incrementAndGet());
        boolean busClosed;
        /** setSessionClosedListener 注册的回调（模拟 dbus 分发线程手动触发） */
        Runnable closedListener;

        static final AtomicInteger SEQ = new AtomicInteger();

        @Override
        public String createSession() {
            calls.add("createSession");
            if (failInCreate != null) {
                throw failInCreate;
            }
            return "/org/freedesktop/portal/desktop/session/1_5/h" + SEQ.incrementAndGet();
        }

        @Override
        public void selectDevices(String sessionHandle, int deviceTypes, int persistMode, String restoreToken) {
            calls.add("selectDevices");
            restoreTokenReceived = restoreToken;
            if (restoreToken != null && failOnRestoreToken != null) {
                throw failOnRestoreToken;
            }
        }

        @Override
        public void selectSources(String sessionHandle, boolean multiple, int cursorMode) {
            calls.add("selectSources");
        }

        @Override
        public PortalSession start(String sessionHandle, String parentWindow) {
            calls.add("start");
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
        }

        @Override
        public void notifyPointerAxis(String sessionHandle, double dx, double dy) {
            calls.add("notifyPointerAxis");
        }

        @Override
        public void notifyKeyboardKeycode(String sessionHandle, int keycode, boolean pressed) {
            calls.add("notifyKeyboardKeycode");
        }

        @Override
        public void closeSession(String sessionHandle) {
            calls.add("closeSession");
        }

        @Override
        public void setSessionClosedListener(Runnable listener) {
            closedListener = listener;
        }

        @Override
        public int openPipeWireRemote(String sessionHandle) {
            calls.add("openPipeWireRemote");
            return 7;
        }

        @Override
        public void close() {
            busClosed = true;
        }
    }

    private final List<FakeBus> createdBuses = new ArrayList<>();

    @AfterEach
    void reset() {
        PortalContextHolder.resetForTest();
    }

    /** 构造使用 FakeBus 工厂的 Holder 并安装为进程级实例 */
    private PortalContextHolder newHolder() {
        PortalContextHolder holder = new PortalContextHolder(() -> {
            FakeBus bus = new FakeBus();
            createdBuses.add(bus);
            return bus;
        }, Duration.ofSeconds(1));
        PortalContextHolder.installForTest(holder);
        return holder;
    }

    @Test
    void 首个acquire建会话_后续acquire复用不重建() throws Exception {
        PortalContextHolder holder = newHolder();
        try (PortalContextHolder.Lease first = holder.acquire();
             PortalContextHolder.Lease second = holder.acquire()) {
            assertEquals(1, createdBuses.size(), "两个使用者只应建立一个会话");
            assertNotNull(first.session());
            assertEquals(first.session(), second.session(), "复用同一会话快照");
            assertTrue(first.isPointerGranted());
            assertTrue(first.isKeyboardGranted());
            assertEquals(42, first.stream().nodeId());
        }
    }

    @Test
    void 部分释放不关闭_全部释放才关会话与总线() throws Exception {
        PortalContextHolder holder = newHolder();
        PortalContextHolder.Lease first = holder.acquire();
        PortalContextHolder.Lease second = holder.acquire();

        first.close();
        assertFalse(createdBuses.get(0).busClosed, "还有使用者时不关闭总线");
        try (PortalContextHolder.Lease third = holder.acquire()) {
            assertEquals(1, createdBuses.size(), "会话未关时 acquire 仍复用");
        }
        second.close();
        assertTrue(createdBuses.get(0).busClosed, "最后一个释放者应关闭总线");
    }

    @Test
    void leaseClose幂等_重复关闭不破坏引用计数() throws Exception {
        PortalContextHolder holder = newHolder();
        PortalContextHolder.Lease lease = holder.acquire();
        lease.close();
        lease.close();
        assertTrue(createdBuses.get(0).busClosed, "唯一释放即应关闭");
        // 重复 close 不应使 refcount 变负/错乱：后续 acquire 正常重建
        try (PortalContextHolder.Lease again = holder.acquire()) {
            assertEquals(2, createdBuses.size(), "关闭后 acquire 应重建会话");
        }
    }

    @Test
    void restoreToken记忆_会话重开时自动恢复() throws Exception {
        PortalContextHolder holder = newHolder();
        String firstToken;
        try (PortalContextHolder.Lease lease = holder.acquire()) {
            firstToken = lease.session().restoreToken();
            assertNotNull(firstToken);
        }
        try (PortalContextHolder.Lease lease = holder.acquire()) {
            FakeBus secondBus = createdBuses.get(1);
            assertEquals(firstToken, secondBus.restoreTokenReceived,
                    "第二次建会话应携带上次 token 恢复授权");
        }
    }

    @Test
    void token恢复失败_降级全新授权重试一次() throws Exception {
        // 第一个会话签发 token；第二个会话恢复时 token 已被用户撤销（带 token 即失败）
        AtomicInteger sessionIndex = new AtomicInteger();
        PortalContextHolder holder = new PortalContextHolder(() -> {
            FakeBus bus = new FakeBus();
            if (sessionIndex.incrementAndGet() == 2) {
                bus.failOnRestoreToken = new PortalException("恢复被拒绝");
            }
            createdBuses.add(bus);
            return bus;
        }, Duration.ofSeconds(1));
        PortalContextHolder.installForTest(holder);

        try (PortalContextHolder.Lease lease = holder.acquire()) {
            assertNotNull(lease.session().restoreToken());
        }
        try (PortalContextHolder.Lease lease = holder.acquire()) {
            assertNotNull(lease.session(), "token 失效后应降级为全新授权成功");
            FakeBus revoked = createdBuses.get(1);
            assertEquals(2, revoked.calls.stream().filter("selectDevices"::equals).count(),
                    "应重试一次 selectDevices");
            assertNull(revoked.restoreTokenReceived, "重试应以无 token 的全新授权进行");
        }
    }

    @Test
    void acquire失败原样传播_不残留半开会话可重试() {
        // 首次建会话即失败（用户拒绝）；工厂按会话实例化，第一次返回失败总线
        AtomicBoolean failNext = new AtomicBoolean(true);
        PortalContextHolder holder = new PortalContextHolder(() -> {
            FakeBus bus = new FakeBus();
            if (failNext.compareAndSet(true, false)) {
                bus.failInCreate = new PortalException("用户拒绝");
            }
            createdBuses.add(bus);
            return bus;
        }, Duration.ofSeconds(1));
        PortalContextHolder.installForTest(holder);

        assertThrows(PortalException.class, holder::acquire);
        // 失败不残留：下一次 acquire 正常建会话
        try (PortalContextHolder.Lease lease = holder.acquire()) {
            assertNotNull(lease.session());
        }
    }

    @Test
    void invalidate通知监听器_在途lease失效_下次acquire重建() throws Exception {
        PortalContextHolder holder = newHolder();
        AtomicReference<String> reasonRef = new AtomicReference<>();
        holder.addInvalidationListener(reasonRef::set);

        PortalContextHolder.Lease lease = holder.acquire();
        assertTrue(lease.isValid());
        holder.invalidate("用户撤销授权");
        assertFalse(lease.isValid(), "invalidate 后在途租约失效");
        assertNull(lease.client(), "失效租约不再暴露客户端");
        assertEquals("用户撤销授权", reasonRef.get(), "监听器收到失效原因");

        try (PortalContextHolder.Lease next = holder.acquire()) {
            assertEquals(2, createdBuses.size(), "失效后 acquire 应重建会话");
            assertTrue(next.isValid());
        }
    }

    @Test
    void 设备部分授权_能力查询正确() throws Exception {
        PortalContextHolder holder = newHolder();
        FakeBus bus = new FakeBus();
        // 用户在弹窗中只授权了键盘（未勾指针）
        bus.startResult = new PortalSession(
                "/org/freedesktop/portal/desktop/session/1_5/partial",
                PortalDevice.KEYBOARD,
                List.of(new PortalStream(42, 0, 0, 1920, 1080)),
                null);
        createdBuses.add(bus);
        PortalContextHolder partial = new PortalContextHolder(() -> bus, Duration.ofSeconds(1));
        PortalContextHolder.installForTest(partial);
        try (PortalContextHolder.Lease lease = partial.acquire()) {
            assertTrue(lease.isKeyboardGranted());
            assertFalse(lease.isPointerGranted(), "未授权的设备应报告 false");
        }
    }

    @Test
    void 失败监听器异常不阻断其他监听器与invalidate流程() throws Exception {
        PortalContextHolder holder = newHolder();
        AtomicReference<String> received = new AtomicReference<>();
        holder.addInvalidationListener(r -> {
            throw new IllegalStateException("监听器自身异常");
        });
        holder.addInvalidationListener(received::set);
        try (PortalContextHolder.Lease ignored = holder.acquire()) {
            holder.invalidate("portal 重启");
            assertEquals("portal 重启", received.get(), "第二个监听器仍应被调用");
        }
    }

    @Test
    void closed信号触发invalidate_异步通知在途lease失效() throws Exception {
        PortalContextHolder holder = newHolder();
        CountDownLatch invalidated = new CountDownLatch(1);
        holder.addInvalidationListener(r -> invalidated.countDown());

        PortalContextHolder.Lease lease = holder.acquire();
        assertTrue(lease.isValid());
        // 模拟 dbus 信号分发线程收到 Session.Closed 并回调
        createdBuses.get(0).closedListener.run();

        assertTrue(invalidated.await(2, TimeUnit.SECONDS), "Closed 信号应异步触发 invalidate");
        assertFalse(lease.isValid(), "在途租约随之失效");
        assertTrue(createdBuses.get(0).busClosed, "会话总线被关闭");
    }
}
