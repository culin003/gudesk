package com.gudesk.host.portal;

import com.gudesk.host.portal.dbus.DbusPortalBus;
import org.freedesktop.dbus.connections.impl.DBusConnection;
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder;
import org.freedesktop.dbus.exceptions.DBusException;
import org.freedesktop.dbus.interfaces.DBus;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * xdg-desktop-portal 后端类型探测（纯函数，注入 env 与 busNames 便于单测）。
 *
 * <p>探测规则：
 * <ul>
 *   <li>busNames 为用户会话总线上 org.freedesktop.impl.portal.desktop.* 后端名集合，
 *       按后缀（kde/gnome/gtk/wlr/hyprland）识别已知后端；</li>
 *   <li>注册了多个已知后端时（发行版常同时装 kde 与 gtk），结合 XDG_CURRENT_DESKTOP
 *       （冒号分隔的桌面标识列表，大小写不敏感）消歧，例如双注册 kde+gtk 且
 *       XDG_CURRENT_DESKTOP=KDE 时选 kde；</li>
 *   <li>仍无法消歧时按固定优先级选择：原生后端（KDE &gt; GNOME &gt; WLROOTS &gt;
 *       HYPRANDL）优先于通用兜底的 GTK；</li>
 *   <li>未注册任何已知后端（或入参为空）返回 UNKNOWN。</li>
 * </ul>
 */
public final class PortalBackendDetector {

    /** portal 后端在会话总线上的名字前缀 */
    public static final String BACKEND_PREFIX = "org.freedesktop.impl.portal.desktop.";

    /** 后端名后缀 → 后端类型（大小写不敏感；wlr/wlroots 均指 wlroots 后端） */
    private static final Map<String, PortalBackendKind> BACKEND_SUFFIXES = Map.of(
            "kde", PortalBackendKind.KDE,
            "gnome", PortalBackendKind.GNOME,
            "gtk", PortalBackendKind.GTK,
            "wlr", PortalBackendKind.WLROOTS,
            "wlroots", PortalBackendKind.WLROOTS,
            "hyprland", PortalBackendKind.HYPRANDL);

    /** XDG_CURRENT_DESKTOP 桌面标识 → 后端类型（大小写不敏感，仅用于在已注册后端中消歧） */
    private static final Map<String, PortalBackendKind> DESKTOP_HINTS = Map.ofEntries(
            Map.entry("kde", PortalBackendKind.KDE),
            Map.entry("plasma", PortalBackendKind.KDE),
            Map.entry("gnome", PortalBackendKind.GNOME),
            Map.entry("unity", PortalBackendKind.GNOME),
            Map.entry("ubuntu", PortalBackendKind.GNOME),
            // 以下桌面默认使用 gtk 通用后端
            Map.entry("gtk", PortalBackendKind.GTK),
            Map.entry("xfce", PortalBackendKind.GTK),
            Map.entry("mate", PortalBackendKind.GTK),
            Map.entry("lxqt", PortalBackendKind.GTK),
            Map.entry("cinnamon", PortalBackendKind.GTK),
            Map.entry("budgie", PortalBackendKind.GTK),
            Map.entry("wlroots", PortalBackendKind.WLROOTS),
            Map.entry("sway", PortalBackendKind.WLROOTS),
            Map.entry("river", PortalBackendKind.WLROOTS),
            Map.entry("hyprland", PortalBackendKind.HYPRANDL));

    /** 消歧失败时的兜底优先级：原生后端优先于通用兜底的 GTK */
    private static final List<PortalBackendKind> FALLBACK_PRIORITY = List.of(
            PortalBackendKind.KDE, PortalBackendKind.GNOME,
            PortalBackendKind.WLROOTS, PortalBackendKind.HYPRANDL, PortalBackendKind.GTK);

    private PortalBackendDetector() {
    }

    /** D-Bus 总线守护进程的名字与对象路径（ListNames 调用目标） */
    private static final String DBUS_BUS_NAME = "org.freedesktop.DBus";
    private static final String DBUS_OBJECT_PATH = "/org/freedesktop/DBus";

    /** 当前环境后端探测结果缓存（后端类型在进程生命周期内不变；失败缓存为 UNKNOWN） */
    private static volatile PortalBackendInfo cachedCurrent;

    /**
     * 探测当前环境后端（懒加载 + 进程内缓存）：枚举会话总线名后识别后端。
     * 枚举失败/无后端返回 UNKNOWN（能力位全 false），不抛异常——供 adapter
     * capabilities 与 HostSession 能力位下发复用。
     */
    public static PortalBackendInfo detectCurrent() {
        PortalBackendInfo cached = cachedCurrent;
        if (cached != null) {
            return cached;
        }
        synchronized (PortalBackendDetector.class) {
            if (cachedCurrent == null) {
                cachedCurrent = detectNow();
            }
            return cachedCurrent;
        }
    }

    private static PortalBackendInfo detectNow() {
        try {
            Set<String> busNames = listBusNames(System.getenv());
            return detect(System.getenv(), busNames);
        } catch (RuntimeException e) {
            return new PortalBackendInfo(PortalBackendKind.UNKNOWN, false, false);
        }
    }

    /** 枚举会话总线上的已注册名字（用于后端探测） */
    public static Set<String> listBusNames(Map<String, String> env) throws PortalException {
        String address = DbusPortalBus.resolveSessionBusAddress(env);
        DBusConnection conn = null;
        try {
            conn = DBusConnectionBuilder.forAddress(address).withShared(false).build();
            DBus dbus = conn.getRemoteObject(DBUS_BUS_NAME, DBUS_OBJECT_PATH, DBus.class);
            return new LinkedHashSet<>(Arrays.asList(dbus.ListNames()));
        } catch (DBusException e) {
            throw new PortalException("枚举 D-Bus 总线名失败", e);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /** 测试桩安装：固定 detectCurrent 的返回值（单测控制 adapter capabilities 能力位） */
    static void installForTest(PortalBackendInfo info) {
        cachedCurrent = info;
    }

    /** 测试桩清理：清除 detectCurrent 缓存 */
    static void resetForTest() {
        cachedCurrent = null;
    }

    /**
     * 探测当前环境的 portal 后端类型与能力。
     *
     * @param env      环境变量映射（键：XDG_CURRENT_DESKTOP；可为 null）
     * @param busNames 用户会话总线上的 org.freedesktop.impl.portal.desktop.* 后端名集合；可为 null
     * @return 后端信息（无法识别时为 UNKNOWN/false/false，不返回 null）
     */
    public static PortalBackendInfo detect(Map<String, String> env, Set<String> busNames) {
        Set<PortalBackendKind> registered = registeredKinds(busNames);
        PortalBackendKind kind = resolveKind(registered, env);
        return new PortalBackendInfo(kind,
                kind.persistentSupported(),
                kind.absolutePointerSupported());
    }

    /** 从总线名集合提取已知后端类型（保持注册顺序，便于确定性消歧） */
    private static Set<PortalBackendKind> registeredKinds(Set<String> busNames) {
        Set<PortalBackendKind> kinds = new LinkedHashSet<>();
        if (busNames == null) {
            return kinds;
        }
        for (String name : busNames) {
            if (name == null) {
                continue;
            }
            String suffix = name.trim().toLowerCase(Locale.ROOT);
            if (suffix.startsWith(BACKEND_PREFIX)) {
                suffix = suffix.substring(BACKEND_PREFIX.length());
            }
            PortalBackendKind kind = BACKEND_SUFFIXES.get(suffix);
            if (kind != null) {
                kinds.add(kind);
            }
        }
        return kinds;
    }

    /** 在已注册后端中消歧：无注册 → UNKNOWN；单一注册 → 直接选用；多注册 → 桌面提示 → 兜底优先级 */
    private static PortalBackendKind resolveKind(Set<PortalBackendKind> registered, Map<String, String> env) {
        if (registered.isEmpty()) {
            return PortalBackendKind.UNKNOWN;
        }
        if (registered.size() == 1) {
            return registered.iterator().next();
        }
        for (PortalBackendKind hinted : desktopHints(env)) {
            if (registered.contains(hinted)) {
                return hinted;
            }
        }
        for (PortalBackendKind preferred : FALLBACK_PRIORITY) {
            if (registered.contains(preferred)) {
                return preferred;
            }
        }
        return PortalBackendKind.UNKNOWN;
    }

    /** 解析 XDG_CURRENT_DESKTOP（冒号分隔、大小写不敏感）为后端提示列表 */
    private static List<PortalBackendKind> desktopHints(Map<String, String> env) {
        List<PortalBackendKind> hints = new ArrayList<>();
        String desktops = env == null ? null : env.get("XDG_CURRENT_DESKTOP");
        if (desktops == null || desktops.isBlank()) {
            return hints;
        }
        for (String part : desktops.split(":")) {
            PortalBackendKind hint = DESKTOP_HINTS.get(part.trim().toLowerCase(Locale.ROOT));
            if (hint != null) {
                hints.add(hint);
            }
        }
        return hints;
    }
}
