package com.gudesk.host.portal;

/**
 * xdg-desktop-portal 后端实现类型（按用户会话总线上注册的
 * org.freedesktop.impl.portal.desktop.* 后端名识别）。
 *
 * <p>各后端能力（用于会话建立前的能力协商）：
 * <ul>
 *   <li>持久化授权（restore token）：KDE/WLROOTS/HYPRANDL 支持；GNOME 受限
 *       （其 RemoteDesktop 实现不支持授权持久化）；GTK 不支持；</li>
 *   <li>绝对坐标指针注入（NotifyPointerMotionAbsolute）：KDE/WLROOTS/HYPRANDL 均实现，
 *       GNOME（mutter）亦支持；GTK 不支持。后端实现差异随版本演进，
 *       不确定时按支持处理并由运行期校验兜底。</li>
 * </ul>
 */
public enum PortalBackendKind {
    /** KDE Plasma（xdg-desktop-portal-kde） */
    KDE(true, true),
    /** GNOME（xdg-desktop-portal-gnome） */
    GNOME(false, true),
    /** GTK 通用后端（xdg-desktop-portal-gtk，XFCE/MATE 等桌面及兜底） */
    GTK(false, false),
    /** wlroots 后端（xdg-desktop-portal-wlr，总线名后缀 wlr，Sway 等使用） */
    WLROOTS(true, true),
    /** Hyprland 后端（xdg-desktop-portal-hyprland，总线名后缀 hyprland） */
    HYPRANDL(true, true),
    /** 未知后端（未注册任何已知后端，或无法消歧），能力一律按不支持处理 */
    UNKNOWN(false, false);

    private final boolean persistentSupported;
    private final boolean absolutePointerSupported;

    PortalBackendKind(boolean persistentSupported, boolean absolutePointerSupported) {
        this.persistentSupported = persistentSupported;
        this.absolutePointerSupported = absolutePointerSupported;
    }

    /** 授权是否可持久化（restore token 支持） */
    public boolean persistentSupported() {
        return persistentSupported;
    }

    /** 输入注入是否支持绝对坐标（NotifyPointerMotionAbsolute） */
    public boolean absolutePointerSupported() {
        return absolutePointerSupported;
    }
}
