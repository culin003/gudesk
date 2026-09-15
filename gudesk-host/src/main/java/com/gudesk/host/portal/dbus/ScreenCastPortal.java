package com.gudesk.host.portal.dbus;

import org.freedesktop.dbus.DBusPath;
import org.freedesktop.dbus.FileDescriptor;
import org.freedesktop.dbus.annotations.DBusInterfaceName;
import org.freedesktop.dbus.interfaces.DBusInterface;
import org.freedesktop.dbus.types.UInt32;
import org.freedesktop.dbus.types.Variant;

import java.util.Map;

/**
 * org.freedesktop.portal.ScreenCast 的 D-Bus 接口定义
 * （仅声明捆绑 RemoteDesktop 会话所需的成员：SelectSources / OpenPipeWireRemote）。
 *
 * <p>捆绑会话的 SelectSources 选项约束：remote desktop 会话不允许使用
 * persist_mode/restore_token 选项（持久化由 RemoteDesktop.SelectDevices 携带，
 * 组合会话的持久化仅由 RemoteDesktop 接口管理，见 DbusPortalBus）。
 */
@DBusInterfaceName("org.freedesktop.portal.ScreenCast")
public interface ScreenCastPortal extends DBusInterface {

    /**
     * SelectSources(handle o, options a{sv}) → handle o：
     * 配置屏幕捕获来源（仅整屏 MONITOR，见 DbusPortalBus 构造的选项）。
     */
    DBusPath SelectSources(DBusPath sessionHandle, Map<String, Variant<?>> options);

    /**
     * OpenPipeWireRemote(session_handle o, options a{sv}) → fd h：
     * 取得 PipeWire 连接的文件描述符（现代 spec 直接返回 fd，不经 Response 信号），
     * 调用方用 pw_context_connect_fd 连接后仅能访问本会话的屏幕流节点。
     */
    FileDescriptor OpenPipeWireRemote(DBusPath sessionHandle, Map<String, Variant<?>> options);
}
