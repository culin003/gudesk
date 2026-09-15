package com.gudesk.host.portal.dbus;

import com.gudesk.host.portal.PortalBus;
import com.gudesk.host.portal.PortalException;
import com.gudesk.host.portal.PortalSession;
import com.gudesk.host.portal.PortalStream;
import org.freedesktop.dbus.DBusMatchRule;
import org.freedesktop.dbus.DBusPath;
import org.freedesktop.dbus.connections.impl.DBusConnection;
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder;
import org.freedesktop.dbus.exceptions.DBusException;
import org.freedesktop.dbus.interfaces.DBusSigHandler;
import org.freedesktop.dbus.types.UInt32;
import org.freedesktop.dbus.types.Variant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@link PortalBus} 的 dbus-java 实现：连接用户会话总线，封装
 * org.freedesktop.portal.RemoteDesktop / ScreenCast 捆绑会话的完整流程。
 *
 * <p>实现要点：
 * <ul>
 *   <li>连接会话总线：优先 DBUS_SESSION_BUS_ADDRESS，缺省
 *       $XDG_RUNTIME_DIR/bus（见 {@link #resolveSessionBusAddress}）；</li>
 *   <li>竞态规避（libportal 同款做法）：由 sender 唯一名 + handle_token 预推导
 *       request 对象路径，<b>先订阅</b> org.freedesktop.portal.Request 的
 *       Response 信号再发出方法调用；若返回的 request 路径与预推导不一致
 *       （旧版 portal），按实际路径重新订阅；</li>
 *   <li>Response 信号经 CompletableFuture 等待，超时时间构造时传入；
 *       response=1（用户取消）抛「用户拒绝」异常，超时抛「超时」异常，消息可区分；</li>
 *   <li>捆绑会话：RemoteDesktop.CreateSession 创建的会话再调 ScreenCast.SelectSources
 *       即组合为 RemoteDesktop+ScreenCast 会话（授权弹窗合一）；持久化授权
 *       （persist_mode/restore_token）按 spec 经 RemoteDesktop.SelectDevices 携带，
 *       组合会话的持久化仅由 RemoteDesktop 接口管理；</li>
 *   <li>Notify* 输入方法为无返回值的 D-Bus 调用（方法回复本身有 dbus-java 内建
 *       20s 超时兜底），无需等待 Response 信号；</li>
 *   <li>start 成功后订阅 Session 的 Closed 信号，portal 主动关闭会话（用户撤销
 *       授权等）时清理内部跟踪状态。</li>
 * </ul>
 */
public final class DbusPortalBus implements PortalBus {

    private static final Logger LOG = LoggerFactory.getLogger(DbusPortalBus.class);

    /** portal 前端服务的总线名与对象路径 */
    public static final String PORTAL_BUS_NAME = "org.freedesktop.portal.Desktop";
    public static final String PORTAL_DESKTOP_PATH = "/org/freedesktop/portal/desktop";
    /** request 对象路径前缀（sender 为去冒号且点换下划线的唯一名） */
    private static final String REQUEST_PATH_PREFIX = "/org/freedesktop/portal/desktop/request/";
    /** ScreenCast.SelectSources 的来源类型：MONITOR（整屏捕获） */
    private static final int SOURCE_TYPE_MONITOR = 1;

    private final DBusConnection connection;
    private final RemoteDesktopPortal remoteDesktop;
    private final ScreenCastPortal screenCast;
    private final Duration responseTimeout;
    private final AtomicLong tokenCounter = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();

    /** 活动会话句柄与 Closed 信号订阅（会话被 portal 关闭时清理） */
    private volatile String activeSessionHandle;
    private volatile AutoCloseable closedSubscription;
    /** portal 主动关闭会话（Closed 信号）时的回调 */
    private volatile Runnable sessionClosedListener;

    /**
     * 连接当前环境的会话总线（DBUS_SESSION_BUS_ADDRESS，缺省 $XDG_RUNTIME_DIR/bus）。
     *
     * @param responseTimeout 等待 Response 信号的超时（授权弹窗的等待窗口）
     */
    public DbusPortalBus(Duration responseTimeout) throws PortalException {
        this(resolveSessionBusAddress(System.getenv()), responseTimeout);
    }

    /**
     * 连接指定地址的会话总线。
     *
     * @param busAddress      D-Bus 地址（如 unix:path=/run/user/1000/bus）
     * @param responseTimeout 等待 Response 信号的超时
     */
    public DbusPortalBus(String busAddress, Duration responseTimeout) throws PortalException {
        this.responseTimeout = Objects.requireNonNull(responseTimeout, "responseTimeout");
        if (responseTimeout.isZero() || responseTimeout.isNegative()) {
            throw new PortalException("Response 超时时间非法: " + responseTimeout);
        }
        DBusConnection conn = null;
        try {
            conn = DBusConnectionBuilder.forAddress(Objects.requireNonNull(busAddress, "busAddress"))
                    .withShared(false)
                    .build();
            this.connection = conn;
            this.remoteDesktop = conn.getRemoteObject(PORTAL_BUS_NAME, PORTAL_DESKTOP_PATH, RemoteDesktopPortal.class);
            this.screenCast = conn.getRemoteObject(PORTAL_BUS_NAME, PORTAL_DESKTOP_PATH, ScreenCastPortal.class);
        } catch (DBusException | RuntimeException e) {
            if (conn != null) {
                conn.disconnect();
            }
            throw new PortalException("连接 D-Bus 会话总线失败: " + busAddress, e);
        }
        LOG.info("已连接 D-Bus 会话总线（唯一名 {}），Portal Response 超时 {}",
                conn.getUniqueName(), responseTimeout);
    }

    /**
     * 解析会话总线地址（纯函数，注入 env 便于测试）：DBUS_SESSION_BUS_ADDRESS 优先，
     * 缺省回退 $XDG_RUNTIME_DIR/bus。
     *
     * @throws PortalException 两者均未设置时
     */
    static String resolveSessionBusAddress(Map<String, String> env) throws PortalException {
        String address = env.get("DBUS_SESSION_BUS_ADDRESS");
        if (address != null && !address.isBlank()) {
            return address.trim();
        }
        String runtimeDir = env.get("XDG_RUNTIME_DIR");
        if (runtimeDir != null && !runtimeDir.isBlank()) {
            return "unix:path=" + runtimeDir.trim() + "/bus";
        }
        throw new PortalException("无法定位 D-Bus 会话总线：DBUS_SESSION_BUS_ADDRESS 与 XDG_RUNTIME_DIR 均未设置");
    }

    // ------------------------------------------------------------------
    // 会话编排（带 Request/Response 的方法）
    // ------------------------------------------------------------------

    @Override
    public String createSession() throws PortalException {
        ensureOpen();
        String handleToken = newHandleToken();
        Map<String, Variant<?>> options = new HashMap<>();
        options.put("handle_token", new Variant<>(handleToken));
        options.put("session_handle_token", new Variant<>("gudesk_s" + handleToken));
        PendingResponse pending = beginRequest(handleToken,
                () -> remoteDesktop.CreateSession(options));
        PortalRequest.Response response = awaitResponse(pending, "CreateSession");
        checkResponse(response, "CreateSession");
        // session_handle 在协议中为 s 类型（对象路径的历史遗留实现），防御性兼容 o 类型
        String sessionHandle = stringResult(response.results, "session_handle");
        if (sessionHandle == null || sessionHandle.isBlank()) {
            throw new PortalException("Portal CreateSession 响应缺少 session_handle");
        }
        LOG.info("Portal 会话已创建: {}", sessionHandle);
        return sessionHandle;
    }

    @Override
    public void selectDevices(String sessionHandle, int deviceTypes, int persistMode, String restoreToken)
            throws PortalException {
        ensureOpen();
        String handleToken = newHandleToken();
        Map<String, Variant<?>> options = new HashMap<>();
        options.put("handle_token", new Variant<>(handleToken));
        options.put("types", new Variant<>(new UInt32(deviceTypes)));
        if (persistMode != 0) {
            options.put("persist_mode", new Variant<>(new UInt32(persistMode)));
        }
        if (restoreToken != null) {
            options.put("restore_token", new Variant<>(restoreToken));
        }
        PendingResponse pending = beginRequest(handleToken,
                () -> remoteDesktop.SelectDevices(new DBusPath(sessionHandle), options));
        checkResponse(awaitResponse(pending, "SelectDevices"), "SelectDevices");
    }

    @Override
    public void selectSources(String sessionHandle, boolean multiple, int cursorMode)
            throws PortalException {
        ensureOpen();
        String handleToken = newHandleToken();
        Map<String, Variant<?>> options = new HashMap<>();
        options.put("handle_token", new Variant<>(handleToken));
        options.put("types", new Variant<>(new UInt32(SOURCE_TYPE_MONITOR)));
        options.put("multiple", new Variant<>(multiple));
        options.put("cursor_mode", new Variant<>(new UInt32(cursorMode)));
        PendingResponse pending = beginRequest(handleToken,
                () -> screenCast.SelectSources(new DBusPath(sessionHandle), options));
        checkResponse(awaitResponse(pending, "SelectSources"), "SelectSources");
    }

    @Override
    public int openPipeWireRemote(String sessionHandle) throws PortalException {
        ensureOpen();
        Map<String, Variant<?>> options = new HashMap<>();
        options.put("handle_token", new Variant<>(newHandleToken()));
        try {
            org.freedesktop.dbus.FileDescriptor fd =
                    screenCast.OpenPipeWireRemote(new DBusPath(sessionHandle), options);
            if (fd == null || fd.getIntFileDescriptor() < 0) {
                throw new PortalException("OpenPipeWireRemote 返回了无效的文件描述符");
            }
            LOG.debug("OpenPipeWireRemote 成功: fd={}", fd.getIntFileDescriptor());
            return fd.getIntFileDescriptor();
        } catch (RuntimeException e) {
            throw new PortalException("OpenPipeWireRemote 调用失败: " + e.getMessage(), e);
        }
    }

    @Override
    public PortalSession start(String sessionHandle, String parentWindow) throws PortalException {
        ensureOpen();
        String handleToken = newHandleToken();
        Map<String, Variant<?>> options = new HashMap<>();
        options.put("handle_token", new Variant<>(handleToken));
        PendingResponse pending = beginRequest(handleToken,
                () -> remoteDesktop.Start(new DBusPath(sessionHandle), parentWindow, options));
        PortalRequest.Response response = awaitResponse(pending, "Start");
        checkResponse(response, "Start");
        int devices = intResult(response.results, "devices", 0);
        List<PortalStream> streams = parseStreams(response.results.get("streams"));
        String restoreToken = stringResult(response.results, "restore_token");
        watchSessionClosed(sessionHandle);
        return new PortalSession(sessionHandle, devices, streams, restoreToken);
    }

    // ------------------------------------------------------------------
    // 输入注入（fire-and-forget）
    // ------------------------------------------------------------------

    @Override
    public void notifyPointerMotion(String sessionHandle, double dx, double dy) throws PortalException {
        ensureOpen();
        invokeNotify("NotifyPointerMotion",
                () -> remoteDesktop.NotifyPointerMotion(new DBusPath(sessionHandle), Map.of(), dx, dy));
    }

    @Override
    public void notifyPointerMotionAbsolute(String sessionHandle, int streamNodeId, double x, double y)
            throws PortalException {
        ensureOpen();
        invokeNotify("NotifyPointerMotionAbsolute",
                () -> remoteDesktop.NotifyPointerMotionAbsolute(new DBusPath(sessionHandle), Map.of(),
                        new UInt32(streamNodeId), x, y));
    }

    @Override
    public void notifyPointerButton(String sessionHandle, int button, boolean pressed) throws PortalException {
        ensureOpen();
        invokeNotify("NotifyPointerButton",
                () -> remoteDesktop.NotifyPointerButton(new DBusPath(sessionHandle), Map.of(),
                        button, new UInt32(pressed ? 1 : 0)));
    }

    @Override
    public void notifyPointerAxis(String sessionHandle, double dx, double dy) throws PortalException {
        ensureOpen();
        invokeNotify("NotifyPointerAxis",
                () -> remoteDesktop.NotifyPointerAxis(new DBusPath(sessionHandle), Map.of(), dx, dy));
    }

    @Override
    public void notifyKeyboardKeycode(String sessionHandle, int keycode, boolean pressed)
            throws PortalException {
        ensureOpen();
        invokeNotify("NotifyKeyboardKeycode",
                () -> remoteDesktop.NotifyKeyboardKeycode(new DBusPath(sessionHandle), Map.of(),
                        keycode, new UInt32(pressed ? 1 : 0)));
    }

    // ------------------------------------------------------------------
    // 会话关闭与资源释放
    // ------------------------------------------------------------------

    @Override
    public void setSessionClosedListener(Runnable listener) {
        this.sessionClosedListener = listener;
    }

    @Override
    public void closeSession(String sessionHandle) throws PortalException {
        ensureOpen();
        AutoCloseable subscription = this.closedSubscription;
        if (subscription != null) {
            closeQuietly(subscription);
            this.closedSubscription = null;
        }
        try {
            PortalSessionObject session = connection.getRemoteObject(
                    PORTAL_BUS_NAME, sessionHandle, PortalSessionObject.class);
            session.Close();
        } catch (DBusException e) {
            throw new PortalException("获取 portal 会话对象失败: " + sessionHandle, e);
        } catch (RuntimeException e) {
            // 幂等语义：会话已被 portal 关闭时后端返回 UnknownObject 等错误，仅记日志
            LOG.debug("关闭 Portal 会话 {} 时后端返回错误（按幂等处理）: {}", sessionHandle, e.getMessage());
        }
        if (sessionHandle.equals(activeSessionHandle)) {
            activeSessionHandle = null;
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        AutoCloseable subscription = this.closedSubscription;
        if (subscription != null) {
            closeQuietly(subscription);
            this.closedSubscription = null;
        }
        connection.disconnect();
    }

    // ------------------------------------------------------------------
    // Request/Response 信号等待（竞态规避）
    // ------------------------------------------------------------------

    /** 一次 portal 请求的等待句柄：信号订阅 + 结果 future */
    private record PendingResponse(AutoCloseable subscription, CompletableFuture<PortalRequest.Response> future) {
    }

    /** 一次返回 request 句柄的 portal 方法调用 */
    @FunctionalInterface
    private interface PortalInvocation {
        DBusPath invoke();
    }

    /**
     * 预推导 request 路径并先订阅 Response 信号，随后发出方法调用。
     * 返回的 request 路径与预推导不一致时（旧版 portal）按实际路径重新订阅。
     */
    private PendingResponse beginRequest(String handleToken, PortalInvocation invocation)
            throws PortalException {
        String predictedPath = REQUEST_PATH_PREFIX + senderToken() + '/' + handleToken;
        CompletableFuture<PortalRequest.Response> future = new CompletableFuture<>();
        AutoCloseable subscription = subscribeResponse(predictedPath, future);
        DBusPath returnedHandle;
        try {
            returnedHandle = invocation.invoke();
        } catch (RuntimeException e) {
            closeQuietly(subscription);
            throw new PortalException("Portal D-Bus 方法调用失败: " + e.getMessage(), e);
        }
        if (returnedHandle != null && !predictedPath.equals(returnedHandle.getPath())) {
            LOG.warn("Portal 返回的 request 路径与预推导不一致（旧版 portal 兼容）: 预期 {}, 实际 {}",
                    predictedPath, returnedHandle.getPath());
            closeQuietly(subscription);
            subscription = subscribeResponse(returnedHandle.getPath(), future);
        }
        return new PendingResponse(subscription, future);
    }

    /** 订阅指定 request 对象路径上的 Response 信号，送达时完成 future */
    private AutoCloseable subscribeResponse(String requestPath, CompletableFuture<PortalRequest.Response> future)
            throws PortalException {
        try {
            return connection.addSigHandler(
                    new DBusMatchRule(PortalRequest.Response.class, null, requestPath),
                    (DBusSigHandler<PortalRequest.Response>) future::complete);
        } catch (DBusException e) {
            throw new PortalException("订阅 portal Response 信号失败: " + requestPath, e);
        }
    }

    /** 订阅活动会话的 Closed 信号：portal 主动关闭会话时清理跟踪状态并通知监听器 */
    private void watchSessionClosed(String sessionHandle) throws PortalException {
        AutoCloseable previous = this.closedSubscription;
        if (previous != null) {
            closeQuietly(previous);
        }
        try {
            this.activeSessionHandle = sessionHandle;
            this.closedSubscription = connection.addSigHandler(
                    new DBusMatchRule(PortalSessionObject.Closed.class, null, sessionHandle),
                    (DBusSigHandler<PortalSessionObject.Closed>) signal -> {
                        LOG.info("Portal 会话已被 portal 关闭: {}", sessionHandle);
                        if (sessionHandle.equals(activeSessionHandle)) {
                            activeSessionHandle = null;
                        }
                        Runnable listener = sessionClosedListener;
                        if (listener != null) {
                            try {
                                listener.run();
                            } catch (Throwable t) {
                                LOG.warn("Portal Closed 信号回调异常（忽略）", t);
                            }
                        }
                    });
        } catch (DBusException e) {
            this.activeSessionHandle = null;
            throw new PortalException("订阅 portal Closed 信号失败: " + sessionHandle, e);
        }
    }

    /** 等待 Response 信号（带超时），超时与用户拒绝抛出可区分的 PortalException */
    private PortalRequest.Response awaitResponse(PendingResponse pending, String operation)
            throws PortalException {
        try {
            return pending.future().get(responseTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new PortalException("等待 Portal " + operation + " 响应超时（" + responseTimeout + "），"
                    + "用户可能未处理授权弹窗");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PortalException("等待 Portal " + operation + " 响应被中断", e);
        } catch (ExecutionException e) {
            throw new PortalException("处理 Portal " + operation + " 响应信号失败", e.getCause());
        } finally {
            closeQuietly(pending.subscription());
        }
    }

    /** 校验 Response 结果：response=1 为用户取消（拒绝），其他非零为其他方式结束 */
    private static void checkResponse(PortalRequest.Response response, String operation)
            throws PortalException {
        int code = response.response.intValue();
        if (code == 0) {
            return;
        }
        if (code == 1) {
            throw new PortalException("用户拒绝了 Portal " + operation + " 授权请求（response=1）");
        }
        throw new PortalException("Portal " + operation + " 请求以其他方式结束（response=" + code + "）");
    }

    // ------------------------------------------------------------------
    // 结果解析（对 a{sv}/a(ua{sv}) 做防御性转换）
    // ------------------------------------------------------------------

    /** results 中的字符串项（session_handle/restore_token），兼容 s/o 两种到达类型 */
    private static String stringResult(Map<String, Variant<?>> results, String key) {
        Variant<?> variant = results.get(key);
        if (variant == null || variant.getValue() == null) {
            return null;
        }
        Object value = variant.getValue();
        if (value instanceof String s) {
            return s;
        }
        if (value instanceof DBusPath path) {
            return path.getPath();
        }
        return String.valueOf(value);
    }

    /** results 中的整数项（devices 等），缺失或类型不符时返回默认值 */
    private static int intResult(Map<String, Variant<?>> results, String key, int defaultValue) {
        Variant<?> variant = results.get(key);
        return variant != null && variant.getValue() instanceof Number n ? n.intValue() : defaultValue;
    }

    /**
     * 解析 Start 返回的 streams（a(ua{sv})）。
     * dbus-java 将未类型化 struct 反序列化为 {@code Object[]}，故每条流记录按
     * [nodeId, props] 数组防御性解析，无法识别的记录跳过。
     */
    private static List<PortalStream> parseStreams(Variant<?> streamsVariant) {
        if (streamsVariant == null || streamsVariant.getValue() == null) {
            return List.of();
        }
        Object value = streamsVariant.getValue();
        List<?> rows;
        if (value instanceof Collection<?> collection) {
            rows = List.copyOf(collection);
        } else if (value instanceof Object[] array) {
            rows = Arrays.asList(array);
        } else {
            LOG.warn("无法解析的 portal streams 结构: {}", value.getClass().getName());
            return List.of();
        }
        List<PortalStream> streams = new ArrayList<>(rows.size());
        for (Object row : rows) {
            PortalStream stream = parseStreamRow(row);
            if (stream != null) {
                streams.add(stream);
            }
        }
        return List.copyOf(streams);
    }

    /** 单条流记录：nodeId（u）+ 属性表（position/size 为 (ii) 元组） */
    private static PortalStream parseStreamRow(Object row) {
        if (row instanceof Object[] elements && elements.length >= 2 && elements[1] instanceof Map<?, ?> props) {
            int nodeId = toInt(elements[0], 0);
            int[] position = intPair(props.get("position"));
            int[] size = intPair(props.get("size"));
            return new PortalStream(nodeId, position[0], position[1], size[0], size[1]);
        }
        LOG.warn("无法解析的 portal 流记录结构: {}", row == null ? "null" : row.getClass().getName());
        return null;
    }

    /** (ii) 元组属性 → [x, y]，无法解析时返回 [0, 0] */
    private static int[] intPair(Object property) {
        if (property instanceof Variant<?> variant && variant.getValue() instanceof Object[] pair
                && pair.length >= 2) {
            return new int[]{toInt(pair[0], 0), toInt(pair[1], 0)};
        }
        return new int[]{0, 0};
    }

    private static int toInt(Object value, int defaultValue) {
        return value instanceof Number n ? n.intValue() : defaultValue;
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    /** sender 唯一名（:1.42）转 request 路径片段（1_42）：去冒号且点换下划线 */
    private String senderToken() throws PortalException {
        String uniqueName = connection.getUniqueName();
        if (uniqueName == null || uniqueName.length() < 2 || uniqueName.charAt(0) != ':') {
            throw new PortalException("D-Bus 连接唯一名不可用: " + uniqueName);
        }
        return uniqueName.substring(1).replace('.', '_');
    }

    /** 生成唯一且不可预测的 handle_token（合法对象路径元素：字母/数字/下划线） */
    private String newHandleToken() {
        long counter = tokenCounter.incrementAndGet();
        long nanos = System.nanoTime();
        return "gudesk" + Long.toHexString(nanos ^ (counter << 32 | counter >>> 32));
    }

    private void ensureOpen() throws PortalException {
        if (closed.get()) {
            throw new PortalException("DbusPortalBus 已关闭");
        }
    }

    /** Notify* 注入调用：D-Bus 错误统一包装为 PortalException */
    private void invokeNotify(String method, NotifyCall call) throws PortalException {
        try {
            call.invoke();
        } catch (RuntimeException e) {
            throw new PortalException("Portal " + method + " 输入注入失败: " + e.getMessage(), e);
        }
    }

    @FunctionalInterface
    private interface NotifyCall {
        void invoke();
    }

    private static void closeQuietly(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception e) {
            LOG.debug("注销 portal 信号订阅失败（忽略）: {}", e.getMessage());
        }
    }
}
