package com.gudesk.host.portal;

import java.util.List;

/**
 * RemoteDesktop.Start 成功后的活动会话快照。
 *
 * @param handle      会话对象路径（org.freedesktop.portal.Session 对象，如
 *                    /org/freedesktop/portal/desktop/session/SENDER/TOKEN）
 * @param devices     用户实际授予的设备位掩码（{@link PortalDevice} 常量的组合）
 * @param streams     授权捕获的 PipeWire 流列表（捆绑 ScreenCast 时非空）
 * @param restoreToken 持久化授权的恢复令牌（未请求持久化或后端不支持时为 null；
 *                    单次有效，下次会话须使用新返回的令牌）
 */
public record PortalSession(String handle, int devices, List<PortalStream> streams, String restoreToken) {

    public PortalSession {
        streams = streams == null ? List.of() : List.copyOf(streams);
    }

    /** 是否授予了指定设备（位掩码交集非零） */
    public boolean hasDevice(int deviceType) {
        return (devices & deviceType) != 0;
    }
}
