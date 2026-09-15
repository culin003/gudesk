package com.gudesk.host.portal;

/**
 * xdg-desktop-portal 会话总线抽象：RemoteDesktop + ScreenCast 捆绑会话的
 * D-Bus 调用封装（spec 定义的方法集）。
 *
 * <p>实现须知：
 * <ul>
 *   <li>所有方法失败统一抛 {@link PortalException}，消息需区分「用户拒绝」与「超时」；</li>
 *   <li>带 Request 句柄的方法（createSession/selectDevices/selectSources/start）需等待
 *       org.freedesktop.portal.Request 的 Response 信号，实现须自带超时
 *       （CompletableFuture + timeout），response!=0 视为失败；</li>
 *   <li>Notify* 输入方法为 fire-and-forget（无 Request 句柄、无返回值）；</li>
 *   <li>本接口是单测的 mock 点（内存 FakePortalBus），真实实现见
 *       {@link com.gudesk.host.portal.dbus.DbusPortalBus}。</li>
 * </ul>
 */
public interface PortalBus extends AutoCloseable {

    /** 光标模式：不包含在流画面中（org.freedesktop.portal.ScreenCast:AvailableCursorModes） */
    int CURSOR_MODE_HIDDEN = 1;
    /** 光标模式：内嵌到流画面中 */
    int CURSOR_MODE_EMBEDDED = 2;
    /** 光标模式：以 PipeWire 流元数据形式单独发送 */
    int CURSOR_MODE_METADATA = 4;

    /** 持久化模式：不持久化（SelectDevices 的 persist_mode，默认值） */
    int PERSIST_MODE_NONE = 0;
    /** 持久化模式：应用运行期间保持权限 */
    int PERSIST_MODE_WHILE_RUNNING = 1;
    /** 持久化模式：保持到用户显式撤销（无人值守场景） */
    int PERSIST_MODE_UNTIL_REVOKED = 2;

    /**
     * RemoteDesktop.CreateSession：创建远程桌面会话（捆绑 ScreenCast 无需额外选项——
     * 对同一会话调 ScreenCast.SelectSources 即组合成 RemoteDesktop+ScreenCast 会话，
     * 授权弹窗合一）。
     *
     * @return 会话对象路径（session_handle，类型为字符串形式的对象路径）
     * @throws PortalException 用户拒绝、超时或 D-Bus 通信失败
     */
    String createSession() throws PortalException;

    /**
     * RemoteDesktop.SelectDevices：选择要远程控制的输入设备类型，并携带持久化授权
     * 选项（persist_mode/restore_token 按 spec 属于本方法的 options，而非 CreateSession；
     * 组合会话的持久化仅经 RemoteDesktop 接口管理）。
     *
     * @param sessionHandle createSession 返回的会话句柄
     * @param deviceTypes   {@link PortalDevice} 常量的位掩码组合
     * @param persistMode   持久化模式（本接口的 PERSIST_MODE_* 常量；restoreToken 非空时建议非 0）
     * @param restoreToken  恢复上次授权的令牌（首次授权传 null；单次有效，恢复后须用新令牌）
     * @throws PortalException 用户拒绝、超时或 D-Bus 通信失败
     */
    void selectDevices(String sessionHandle, int deviceTypes, int persistMode, String restoreToken)
            throws PortalException;

    /**
     * ScreenCast.OpenPipeWireRemote：取得当前会话的 PipeWire 连接文件描述符
     * （现代 spec 直接经方法返回值返回 fd，不经 Response 信号）。
     *
     * <p>fd 所有权移交调用方：经 SCM_RIGHTS 转发给 gudesk-portal-helper 后，
     * 本进程内的副本由 JVM 持有（java.io.FileDescriptor.close() 无公开 API，
     * 每会话泄漏 1 个 fd，可接受）。
     *
     * @param sessionHandle createSession 返回的会话句柄（须已 Start）
     * @return PipeWire 连接的原始 fd 编号
     * @throws PortalException 会话无效或 D-Bus 通信失败
     */
    int openPipeWireRemote(String sessionHandle) throws PortalException;

    /**
     * ScreenCast.SelectSources：配置屏幕捕获来源（仅捆绑 ScreenCast 的会话可调，
     * 每个会话只能调一次）。来源类型固定为 MONITOR（整屏）。
     *
     * @param sessionHandle 会话句柄
     * @param multiple      是否允许多来源（GuDesk 传 false）
     * @param cursorMode    光标模式（本接口的 CURSOR_MODE_* 常量）
     * @throws PortalException 用户拒绝、超时或 D-Bus 通信失败
     */
    void selectSources(String sessionHandle, boolean multiple, int cursorMode) throws PortalException;

    /**
     * RemoteDesktop.Start：弹出授权对话框并启动会话，返回用户授予的设备与流。
     *
     * @param sessionHandle 会话句柄
     * @param parentWindow  父窗口标识（无窗口时传 ""）
     * @return 含授予设备、PipeWire 流与 restoreToken（如有）的会话快照
     * @throws PortalException 用户拒绝、超时或 D-Bus 通信失败
     */
    PortalSession start(String sessionHandle, String parentWindow) throws PortalException;

    /**
     * RemoteDesktop.NotifyPointerMotion：注入相对指针移动（fire-and-forget）。
     *
     * @param dx X 轴相对位移
     * @param dy Y 轴相对位移
     */
    void notifyPointerMotion(String sessionHandle, double dx, double dy) throws PortalException;

    /**
     * RemoteDesktop.NotifyPointerMotionAbsolute：注入绝对指针移动（fire-and-forget）。
     *
     * @param streamNodeId 坐标所属流的节点 ID
     * @param x            归一化 X 坐标（0~1）
     * @param y            归一化 Y 坐标（0~1）
     */
    void notifyPointerMotionAbsolute(String sessionHandle, int streamNodeId, double x, double y)
            throws PortalException;

    /**
     * RemoteDesktop.NotifyPointerButton：注入指针按钮按下/释放（fire-and-forget）。
     *
     * @param button  Linux Evdev 按钮码
     * @param pressed true=按下 false=释放
     */
    void notifyPointerButton(String sessionHandle, int button, boolean pressed) throws PortalException;

    /**
     * RemoteDesktop.NotifyPointerAxis：注入平滑滚动轴事件（fire-and-forget）。
     *
     * @param dx X 轴滚动量（触摸板风格）
     * @param dy Y 轴滚动量
     */
    void notifyPointerAxis(String sessionHandle, double dx, double dy) throws PortalException;

    /**
     * RemoteDesktop.NotifyKeyboardKeycode：注入按键按下/释放（fire-and-forget）。
     *
     * @param keycode Linux Evdev 键码
     * @param pressed true=按下 false=释放
     */
    void notifyKeyboardKeycode(String sessionHandle, int keycode, boolean pressed) throws PortalException;

    /**
     * org.freedesktop.portal.Session.Close：主动关闭会话（幂等，会话已关闭时不报错）。
     *
     * @param sessionHandle 会话句柄
     */
    void closeSession(String sessionHandle) throws PortalException;

    /** 释放底层资源（D-Bus 连接等），不负责关闭活动会话 */
    @Override
    void close();
}
