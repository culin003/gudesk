package com.gudesk.host.portal;

/**
 * 探测到的 xdg-desktop-portal 后端信息。
 *
 * @param backendKind              后端类型
 * @param persistentSupported      授权是否可持久化（restore token 支持）：
 *                                 KDE/WLROOTS/HYPRANDL 支持，GNOME 受限（不支持
 *                                 RemoteDesktop 持久化授权），GTK 不支持
 * @param absolutePointerSupported 输入注入是否支持绝对坐标
 *                                 （NotifyPointerMotionAbsolute）
 */
public record PortalBackendInfo(PortalBackendKind backendKind,
                                boolean persistentSupported,
                                boolean absolutePointerSupported) {

    /** 是否探测到了已知后端（非 UNKNOWN） */
    public boolean isKnown() {
        return backendKind != PortalBackendKind.UNKNOWN;
    }
}
