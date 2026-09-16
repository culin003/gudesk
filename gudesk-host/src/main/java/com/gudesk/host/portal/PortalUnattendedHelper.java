package com.gudesk.host.portal;

import com.gudesk.host.portal.dbus.DbusPortalBus;
import org.freedesktop.dbus.connections.impl.DBusConnection;
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder;
import org.freedesktop.dbus.exceptions.DBusException;
import org.freedesktop.dbus.interfaces.DBus;

import java.io.PrintStream;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * {@code --enable-unattended} 配置助手：探测 portal 后端 → 引导一次授权 →
 * 确认 restore token 落盘。与 {@link PortalTokenStore} 共用持久化，是无人值守
 * L1 的「首次配置」入口（运行时免弹窗恢复见 {@link PortalContextHolder}）。
 *
 * <p>流程：
 * <ol>
 *   <li>枚举会话总线名 → {@link PortalBackendDetector#detect} 识别后端；</li>
 *   <li>后端未识别 → 提示安装对应后端并退出（2）；</li>
 *   <li>后端不支持持久化授权（GNOME/GTK/UNKNOWN）→ 明确说明限制并退出（2）；</li>
 *   <li>支持（KDE/WLROOTS/HYPRANDL）→ {@link PortalContextHolder#acquire()} 建立捆绑
 *       会话（此处弹授权框，用户允许后 token 由 Holder 自动落盘）；</li>
 *   <li>确认 {@link PortalTokenStore#load()} 非空且后端标识匹配 → 成功（0）。</li>
 * </ol>
 */
public final class PortalUnattendedHelper {

    private static final String DBUS_BUS_NAME = "org.freedesktop.DBus";
    private static final String DBUS_OBJECT_PATH = "/org/freedesktop/DBus";

    private PortalUnattendedHelper() {
    }

    /** 真实入口：探测当前环境并执行配置流程，结果写到 {@code out} */
    public static int enableUnattended(PrintStream out) {
        Map<String, String> env = System.getenv();
        Set<String> busNames;
        try {
            busNames = listBusNames(env);
        } catch (PortalException e) {
            out.println("[错误] 枚举 D-Bus 总线名失败: " + e.getMessage());
            return 2;
        }
        PortalBackendInfo info = PortalBackendDetector.detect(env, busNames);
        return enableUnattended(info, PortalContextHolder.getInstance(), new PortalTokenStore(), out);
    }

    /** 可测核心：注入后端信息 / holder / store，执行配置流程 */
    static int enableUnattended(PortalBackendInfo info, PortalContextHolder holder,
                                PortalTokenStore store, PrintStream out) {
        out.println("探测到的 portal 后端: " + info.backendKind());
        if (!info.isKnown()) {
            out.println("[错误] 未检测到 xdg-desktop-portal 后端，无法配置无人值守。");
            out.println("请安装对应桌面的 portal 后端（如 xdg-desktop-portal-kde / -gnome / -wlr）。");
            return 2;
        }
        if (!info.persistentSupported()) {
            out.println("[不支持] " + info.backendKind()
                    + " 后端不支持持久化授权（restore token），无法实现无人值守免弹窗。");
            out.println("可改用支持的后端（KDE / WLROOTS / Hyprland）。");
            return 2;
        }
        out.println("即将弹出授权对话框，请在 90 秒内允许并确认（勾选键盘/鼠标）。");
        try (PortalContextHolder.Lease lease = holder.acquire()) {
            out.println("授权成功，会话已建立（设备=0x"
                    + Integer.toHexString(lease.session().devices()) + "）。");
        } catch (PortalException e) {
            out.println("[错误] 授权失败: " + e.getMessage());
            return 2;
        }

        PortalTokenStore.StoredToken token = store.load();
        if (token == null) {
            out.println("[错误] 授权完成但 restore token 未落盘（后端可能未签发持久化 token）。");
            return 2;
        }
        String current = PortalTokenStore.currentBackendId(System.getenv());
        if (!current.equals(token.backendId())) {
            out.println("[错误] restore token 后端标识不符（" + token.backendId()
                    + " vs " + current + "）。");
            return 2;
        }
        out.println("成功：restore token 已持久化到 " + store.file()
                + "，后续启动将免弹窗恢复。");
        return 0;
    }

    /** 枚举会话总线上的已注册名字（用于后端探测） */
    static Set<String> listBusNames(Map<String, String> env) throws PortalException {
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
}
