package com.gudesk.host.portal;

import com.gudesk.common.spi.AdapterException;

/**
 * xdg-desktop-portal 封装层运行异常：会话编排失败、用户拒绝授权、响应超时、
 * D-Bus 通信错误等场景统一抛出。
 *
 * <p>异常消息需区分「用户拒绝」与「超时」两类失败，供上层决定是否重试或提示用户。
 * 另有 {@link #isSessionInvalid()} 标记「会话已失效」类错误（如 portal 守护进程
 * 重启后所有会话句柄作废，Notify* 返回 Invalid session）——此类失败不可重试，
 * 上层应立即触发会话失效传播（invalidate）并断开。
 */
public class PortalException extends AdapterException {

    private final boolean sessionInvalid;

    public PortalException(String message) {
        this(message, null, false);
    }

    public PortalException(String message, Throwable cause) {
        this(message, cause, false);
    }

    private PortalException(String message, Throwable cause, boolean sessionInvalid) {
        super(message, cause);
        this.sessionInvalid = sessionInvalid;
    }

    /** 构造「会话已失效」类异常（不可重试，上层应触发 invalidate 断开） */
    public static PortalException sessionInvalid(String message, Throwable cause) {
        return new PortalException(message, cause, true);
    }

    /** 是否为会话失效类错误（portal 重启/会话句柄作废，重试无意义） */
    public boolean isSessionInvalid() {
        return sessionInvalid;
    }
}
