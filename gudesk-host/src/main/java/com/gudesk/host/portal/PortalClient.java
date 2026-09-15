package com.gudesk.host.portal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * Portal 会话编排客户端：包装 {@link PortalBus}，按固定顺序编排
 * createSession → selectDevices → selectSources → start 建立捆绑会话
 * （RemoteDesktop + ScreenCast，授权弹窗合一），并持有当前会话。
 *
 * <p>实现要点：
 * <ul>
 *   <li>open 前置校验：同一时刻只允许一个活动会话，重复 open 抛异常；</li>
 *   <li>编排中途失败（含用户拒绝/超时，由 bus 以 {@link PortalException} 抛出、
 *       消息区分「用户拒绝」与「超时」）时 best-effort 关闭半建会话后原样传播；</li>
 *   <li>输入方法（notify*）先校验会话已建立再委托 bus；</li>
 *   <li>restoreToken 经 {@link #restoreToken()} 透出，供下次 open 恢复授权；
 *       令牌单次有效，恢复后须使用新返回的令牌。</li>
 * </ul>
 */
public final class PortalClient implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(PortalClient.class);

    private final PortalBus bus;
    /** 当前活动会话（start 成功后非空；输入方法以它为准） */
    private volatile PortalSession session;

    public PortalClient(PortalBus bus) {
        this.bus = Objects.requireNonNull(bus, "bus");
    }

    /**
     * 编排建立捆绑会话（RemoteDesktop + ScreenCast，GuDesk 的被控端必然需要屏幕捕获，
     * 组合方式为对同一会话调 ScreenCast.SelectSources）。
     *
     * @param deviceTypes  请求的设备位掩码（{@link PortalDevice} 常量组合）
     * @param persistent   是否请求持久化授权（映射 persist_mode=2「保持到撤销」；
     *                     后端支持时 start 返回新 restoreToken）
     * @param restoreToken 上次会话返回的恢复令牌（首次传 null；单次有效）
     * @return 含用户实际授予的设备、流与 restoreToken 的会话快照
     * @throws PortalException 用户拒绝（消息含「拒绝」）、超时（消息含「超时」）或通信失败；
     *                         失败时半建会话已被关闭，可重新 open
     */
    public PortalSession open(int deviceTypes, boolean persistent, String restoreToken) throws PortalException {
        if (session != null) {
            throw new PortalException("已存在活动会话，请先 closeSession() 后再建立新会话");
        }
        String handle = bus.createSession();
        try {
            bus.selectDevices(handle, deviceTypes,
                    persistent ? PortalBus.PERSIST_MODE_UNTIL_REVOKED : PortalBus.PERSIST_MODE_NONE,
                    restoreToken);
            // 单来源（整屏 MONITOR）+ 内嵌光标，见 PortalBus.selectSources 说明
            bus.selectSources(handle, false, PortalBus.CURSOR_MODE_EMBEDDED);
            PortalSession started = bus.start(handle, "");
            this.session = started;
            LOG.info("Portal 会话已建立: 设备={}, 流数={}, restoreToken={}",
                    started.devices(), started.streams().size(),
                    started.restoreToken() == null ? "无" : "有");
            return started;
        } catch (PortalException e) {
            LOG.warn("Portal 会话编排失败，关闭半建会话: {}", e.getMessage());
            closeSessionQuietly(handle);
            throw e;
        }
    }

    /** 当前活动会话快照（未建立时为 null） */
    public PortalSession currentSession() {
        return session;
    }

    /** 当前会话的 restoreToken（未建立会话或未持久化时为 null） */
    public String restoreToken() {
        PortalSession current = session;
        return current == null ? null : current.restoreToken();
    }

    /**
     * ScreenCast.OpenPipeWireRemote：取得当前会话的 PipeWire 连接 fd
     * （所有权移交调用方；转发给 gudesk-portal-helper 消费）。
     *
     * @return PipeWire 连接的原始 fd 编号
     * @throws PortalException 无活动会话或调用失败
     */
    public int openPipeWireRemote() throws PortalException {
        requireSession();
        return bus.openPipeWireRemote(sessionHandle());
    }

    /** 当前会话句柄（未建立时为 null） */
    public String sessionHandle() {
        PortalSession current = session;
        return current == null ? null : current.handle();
    }

    /**
     * 主动关闭当前会话（org.freedesktop.portal.Session.Close）。
     *
     * @throws PortalException 无活动会话或关闭失败
     */
    public void closeSession() throws PortalException {
        PortalSession current = session;
        if (current == null) {
            throw new PortalException("无活动会话，无法关闭");
        }
        bus.closeSession(current.handle());
        session = null;
    }

    /** 释放客户端：关闭活动会话（尽力而为）并释放底层总线资源；幂等 */
    @Override
    public void close() {
        PortalSession current = session;
        if (current != null) {
            closeSessionQuietly(current.handle());
            session = null;
        }
        bus.close();
    }

    // ------------------------------------------------------------------
    // 输入注入（校验会话存在后委托；fire-and-forget）
    // ------------------------------------------------------------------

    /** 注入相对指针移动（dx/dy 为相对位移） */
    public void notifyPointerMotion(double dx, double dy) throws PortalException {
        requireSession();
        bus.notifyPointerMotion(sessionHandle(), dx, dy);
    }

    /** 注入绝对指针移动（x/y 为 0~1 归一化坐标，相对于指定流） */
    public void notifyPointerMotionAbsolute(int streamNodeId, double x, double y) throws PortalException {
        requireSession();
        bus.notifyPointerMotionAbsolute(sessionHandle(), streamNodeId, x, y);
    }

    /** 注入指针按钮按下/释放（button 为 Linux Evdev 按钮码） */
    public void notifyPointerButton(int button, boolean pressed) throws PortalException {
        requireSession();
        bus.notifyPointerButton(sessionHandle(), button, pressed);
    }

    /** 注入平滑滚动轴事件（触摸板风格） */
    public void notifyPointerAxis(double dx, double dy) throws PortalException {
        requireSession();
        bus.notifyPointerAxis(sessionHandle(), dx, dy);
    }

    /** 注入按键按下/释放（keycode 为 Linux Evdev 键码） */
    public void notifyKeyboardKeycode(int keycode, boolean pressed) throws PortalException {
        requireSession();
        bus.notifyKeyboardKeycode(sessionHandle(), keycode, pressed);
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    private void requireSession() {
        if (session == null) {
            throw new PortalException("尚未建立 Portal 会话，请先调用 open(...)");
        }
    }

    /** best-effort 关闭会话：失败仅记日志（用于编排失败后的清理，不掩盖原始异常） */
    private void closeSessionQuietly(String handle) {
        try {
            bus.closeSession(handle);
        } catch (Exception e) {
            LOG.debug("关闭 Portal 会话失败（忽略）: {}", e.getMessage());
        }
    }
}
