package com.gudesk.host.portal.dbus;

import org.freedesktop.dbus.annotations.DBusInterfaceName;
import org.freedesktop.dbus.exceptions.DBusException;
import org.freedesktop.dbus.interfaces.DBusInterface;
import org.freedesktop.dbus.messages.DBusSignal;
import org.freedesktop.dbus.types.Variant;

import java.util.Map;

/**
 * org.freedesktop.portal.Session 的 D-Bus 接口定义：portal 会话对象的共享接口。
 *
 * <p>session 对象路径格式：
 * {@code /org/freedesktop/portal/desktop/session/<去冒号且点换下划线的sender>/<session_handle_token>}。
 */
@DBusInterfaceName("org.freedesktop.portal.Session")
public interface PortalSessionObject extends DBusInterface {

    /** Close()：主动关闭会话 */
    void Close();

    /** Closed(details a{sv}) 信号：会话被关闭（portal 主动关闭或用户撤销授权） */
    class Closed extends DBusSignal {

        public final Map<String, Variant<?>> details;

        public Closed(String path, Map<String, Variant<?>> details) throws DBusException {
            super(path, details);
            this.details = details;
        }
    }
}
