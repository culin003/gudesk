package com.gudesk.host.portal;

import com.gudesk.common.spi.AdapterException;

/**
 * xdg-desktop-portal 封装层运行异常：会话编排失败、用户拒绝授权、响应超时、
 * D-Bus 通信错误等场景统一抛出。
 *
 * <p>异常消息需区分「用户拒绝」与「超时」两类失败，供上层决定是否重试或提示用户。
 */
public class PortalException extends AdapterException {

    public PortalException(String message) {
        super(message);
    }

    public PortalException(String message, Throwable cause) {
        super(message, cause);
    }
}
