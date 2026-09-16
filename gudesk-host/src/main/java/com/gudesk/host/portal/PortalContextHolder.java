package com.gudesk.host.portal;

import com.gudesk.host.portal.dbus.DbusPortalBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 进程级 Portal 会话共享持有器：被控端的屏幕捕获（PortalScreenCapturer）与输入注入
 * （PortalInputInjector）复用<b>同一个</b> RemoteDesktop+ScreenCast 捆绑会话——授权弹窗
 * 合一、设备授权与 restore token 统一管理。
 *
 * <p>生命周期（引用计数）：
 * <ul>
 *   <li>{@link #acquire()}：首个使用者建立捆绑会话（此处出现授权弹窗），返回
 *       {@link Lease}；后续使用者直接复用，不触发新的 portal 往返；</li>
 *   <li>{@link Lease#close()}：幂等；最后一个使用者释放时才真正关闭会话与总线；</li>
 *   <li>会话建立参数固定：设备请求 {@link PortalDevice#KEYBOARD}|{@link PortalDevice#POINTER}
 *       + persistent（无人值守前提），来源单显示器 + 内嵌光标（与 PortalClient.open 约定一致）。</li>
 * </ul>
 *
 * <p>restore token 记忆：每次会话建立后记住最新 token，下次 acquire 时用于恢复授权
 * （KDE 等后端即免弹窗）；token 失效（用户撤销）导致恢复失败时自动降级为全新授权
 * 重试一次。
 *
 * <p>失效传播（供 Closed 信号接线）：外部（如 D-Bus Closed 信号处理器）调用
 * {@link #invalidate(String)} 后，所有在途 Lease 失效（{@link Lease#isValid()} 为 false）、
 * 监听器被回调（捕获/注入侧据此触发媒体管线停止），后续 acquire 重新建会话。
 */
public final class PortalContextHolder {

    private static final Logger LOG = LoggerFactory.getLogger(PortalContextHolder.class);

    /** 授权弹窗等待窗口（用户可能不在屏幕前） */
    private static final Duration DEFAULT_CONSENT_TIMEOUT = Duration.ofSeconds(90);

    /** 进程级单例（测试可替换） */
    private static volatile PortalContextHolder instance;

    private final Supplier<PortalBus> busFactory;
    private final Duration consentTimeout;
    /** restore token 跨进程持久化（null = 禁用，测试默认） */
    private final PortalTokenStore tokenStore;
    /** 会话失效监听器（参数为失效原因） */
    private final List<Consumer<String>> invalidationListeners = new CopyOnWriteArrayList<>();

    private PortalClient client;
    private PortalSession session;
    private String lastRestoreToken;
    private int refCount;

    /** 包私有构造：生产经 {@link #getInstance()}，测试注入 FakeBus 工厂（token 持久化禁用） */
    PortalContextHolder(Supplier<PortalBus> busFactory, Duration consentTimeout) {
        this(busFactory, consentTimeout, null);
    }

    PortalContextHolder(Supplier<PortalBus> busFactory, Duration consentTimeout,
                        PortalTokenStore tokenStore) {
        this.busFactory = Objects.requireNonNull(busFactory, "busFactory");
        this.consentTimeout = Objects.requireNonNull(consentTimeout, "consentTimeout");
        this.tokenStore = tokenStore;
    }

    /** 进程级实例（懒加载；生产环境使用真实 D-Bus 总线 + token 持久化存储） */
    public static PortalContextHolder getInstance() {
        PortalContextHolder holder = instance;
        if (holder == null) {
            synchronized (PortalContextHolder.class) {
                if (instance == null) {
                    instance = new PortalContextHolder(
                            () -> new DbusPortalBus(DEFAULT_CONSENT_TIMEOUT),
                            DEFAULT_CONSENT_TIMEOUT,
                            new PortalTokenStore());
                }
                holder = instance;
            }
        }
        return holder;
    }

    /** 测试桩安装：替换进程级实例 */
    static void installForTest(PortalContextHolder holder) {
        instance = holder;
    }

    /** 测试桩清理：恢复懒加载默认实例 */
    static void resetForTest() {
        instance = null;
    }

    /** 授权弹窗等待窗口 */
    public Duration consentTimeout() {
        return consentTimeout;
    }

    /**
     * 注册会话失效监听器（在失效回调线程上执行，实现方须自行保证线程安全/快速返回）。
     */
    public void addInvalidationListener(Consumer<String> listener) {
        invalidationListeners.add(Objects.requireNonNull(listener, "listener"));
    }

    /**
     * 取得共享会话租约：无活动会话时建立捆绑会话（此处可能出现授权弹窗），
     * 有则直接复用。
     *
     * @throws PortalException 会话建立失败（用户拒绝/超时/通信失败，含 token 失效重试后仍失败）
     */
    public synchronized Lease acquire() throws PortalException {
        if (client == null || session == null) {
            openSession();
        }
        refCount++;
        return new Lease(this);
    }

    /** 建立捆绑会话：优先用记忆的 restore token 恢复，失败（token 被撤销）则全新授权重试一次 */
    private void openSession() throws PortalException {
        // 首次建立（进程内无记忆）时从持久化 store 恢复 token；换桌面环境（后端标识不符）
        // 时跳过，省一次注定失败的恢复往返
        if (lastRestoreToken == null && tokenStore != null) {
            restoreFromStore();
        }
        PortalClient newClient = new PortalClient(busFactory.get());
        PortalSession opened;
        try {
            LOG.info("Portal 会话启动: 建立 RemoteDesktop+ScreenCast 捆绑会话"
                            + "（若出现授权对话框请在 {}s 内允许）{}…",
                    consentTimeout.toSeconds(),
                    lastRestoreToken != null ? "，尝试恢复已保存授权（后端支持时免弹窗）" : "");
            long t0 = System.nanoTime();
            opened = newClient.open(PortalDevice.KEYBOARD | PortalDevice.POINTER,
                    true, lastRestoreToken);
            LOG.info("Portal 捆绑会话已建立（耗时 {} ms）: 设备=0x{}, 流数={}, restoreToken={}",
                    (System.nanoTime() - t0) / 1_000_000,
                    String.format("%x", opened.devices()), opened.streams().size(),
                    opened.restoreToken() != null ? "已签发" : "无");
        } catch (PortalException e) {
            if (lastRestoreToken != null) {
                LOG.warn("restore token 恢复失败（可能已被用户撤销），降级为全新授权重试: {}", e.getMessage());
                try {
                    opened = newClient.open(PortalDevice.KEYBOARD | PortalDevice.POINTER, true, null);
                } catch (PortalException retry) {
                    newClient.close();
                    throw retry;
                }
            } else {
                newClient.close();
                throw e;
            }
        }
        this.client = newClient;
        this.session = opened;
        this.lastRestoreToken = opened.restoreToken();
        // 签发了新 token（首次授权或降级重试后）→ 跨进程持久化，下次免弹窗
        if (opened.restoreToken() != null) {
            persistToken(opened.restoreToken());
        }
        // Closed 信号接线：portal 主动关闭会话（用户撤销授权/后端重启）→ 通知所有租约使用者。
        // 回调在 dbus 信号分发线程上执行，切虚拟线程做 invalidate（其中会关闭总线连接，
        // 不能阻塞在分发线程上）。
        newClient.setSessionClosedListener(() -> Thread.ofVirtual().start(
                () -> invalidate("portal 主动关闭会话（Closed 信号）")));
    }

    /** 从持久化 store 恢复上次的 restore token（后端标识匹配才采用） */
    private void restoreFromStore() {
        try {
            PortalTokenStore.StoredToken stored = tokenStore.load();
            if (stored == null) {
                return;
            }
            String current = PortalTokenStore.currentBackendId(System.getenv());
            if (!current.equals(stored.backendId())) {
                LOG.info("已存 restore token 的后端({})与当前({})不符，跳过恢复，改为全新授权",
                        stored.backendId(), current);
                return;
            }
            this.lastRestoreToken = stored.token();
            LOG.info("从持久化恢复 restore token（后端 {}，签发于 {}），下次 acquire 尝试免弹窗恢复",
                    stored.backendId(), stored.issuedAtMillis());
        } catch (RuntimeException e) {
            LOG.warn("restore token 恢复读取失败，按无已存 token 处理: {}", e.getMessage());
        }
    }

    /** 持久化新签发的 restore token（写失败仅 WARN，不影响会话） */
    private void persistToken(String token) {
        try {
            tokenStore.save(PortalTokenStore.currentBackendId(System.getenv()), token);
            LOG.info("restore token 已持久化，后续启动免弹窗恢复");
        } catch (Exception e) {
            LOG.warn("restore token 持久化失败（不影响当前会话）: {}", e.getMessage());
        }
    }

    /** Lease 释放（引用计数归零时关闭会话与总线，token 保留供下次恢复） */
    private synchronized void release() {
        refCount = Math.max(0, refCount - 1);
        if (refCount == 0 && client != null) {
            LOG.info("Portal 会话引用归零，关闭捆绑会话（restoreToken 已记忆，下次优先恢复）");
            client.close();
            client = null;
            session = null;
        }
    }

    /**
     * 外部触发会话失效（如 portal 主动关闭/用户撤销授权的 Closed 信号）：立即关闭会话、
     * 通知监听器；在途 Lease 的后续使用将得到失效指示，下一次 acquire 重新建会话。
     */
    public synchronized void invalidate(String reason) {
        if (client == null) {
            return;
        }
        LOG.warn("Portal 会话已失效: {}（关闭会话并通知 {} 个监听器）",
                reason != null ? reason : "未知原因", invalidationListeners.size());
        try {
            client.close();
        } catch (Exception e) {
            LOG.debug("关闭失效会话失败（忽略）: {}", e.getMessage());
        }
        client = null;
        session = null;
        List<Consumer<String>> listeners = new ArrayList<>(invalidationListeners);
        for (Consumer<String> listener : listeners) {
            try {
                listener.accept(reason != null ? reason : "未知原因");
            } catch (Throwable t) {
                LOG.warn("会话失效监听器异常", t);
            }
        }
    }

    // ------------------------------------------------------------------
    // 租约
    // ------------------------------------------------------------------

    /** 共享会话租约：close 幂等，最后一个释放者触发会话关闭 */
    public static final class Lease implements AutoCloseable {

        private final PortalContextHolder holder;
        private volatile boolean released;

        private Lease(PortalContextHolder holder) {
            this.holder = holder;
        }

        /** 会话客户端（输入注入/OpenPipeWireRemote 用）；租约已释放或会话已失效时为 null */
        public PortalClient client() {
            return isValid() ? holder.client : null;
        }

        /** 建立时的会话快照（设备授权/流信息）；已失效时为 null */
        public PortalSession session() {
            return isValid() ? holder.session : null;
        }

        /** 首个屏幕流（单显示器语义）；无流或已失效时为 null */
        public PortalStream stream() {
            PortalSession current = session();
            return current == null || current.streams().isEmpty() ? null : current.streams().get(0);
        }

        /** 用户是否授权了指针设备（弹窗中可只勾选部分设备） */
        public boolean isPointerGranted() {
            PortalSession current = session();
            return current != null && (current.devices() & PortalDevice.POINTER) != 0;
        }

        /** 用户是否授权了键盘设备 */
        public boolean isKeyboardGranted() {
            PortalSession current = session();
            return current != null && (current.devices() & PortalDevice.KEYBOARD) != 0;
        }

        /** 租约是否仍持有有效会话（未释放且未被 invalidate） */
        public boolean isValid() {
            return !released && holder.client != null && holder.session != null;
        }

        /** 释放租约（幂等；最后一个释放者关闭会话） */
        @Override
        public void close() {
            if (!released) {
                released = true;
                holder.release();
            }
        }
    }
}
