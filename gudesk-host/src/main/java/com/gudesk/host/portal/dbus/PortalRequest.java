package com.gudesk.host.portal.dbus;

import org.freedesktop.dbus.annotations.DBusInterfaceName;
import org.freedesktop.dbus.exceptions.DBusException;
import org.freedesktop.dbus.interfaces.DBusInterface;
import org.freedesktop.dbus.messages.DBusSignal;
import org.freedesktop.dbus.types.UInt32;
import org.freedesktop.dbus.types.Variant;

import java.util.Map;

/**
 * org.freedesktop.portal.Request 的 D-Bus 接口定义：portal 请求对象的共享接口，
 * 结果经 {@link Response} 信号异步送达。
 *
 * <p>request 对象路径格式（自 xdg-desktop-portal 0.9 起）：
 * {@code /org/freedesktop/portal/desktop/request/<去冒号且点换下划线的sender>/<handle_token>}，
 * 调用方可据此在发出方法调用前预先订阅 Response 信号以规避竞态。
 */
@DBusInterfaceName("org.freedesktop.portal.Request")
public interface PortalRequest extends DBusInterface {

    /** Close()：取消请求并结束相关用户交互（此时不会发出 Response 信号） */
    void Close();

    /**
     * Response(response u, results a{sv}) 信号：请求结束。
     * response：0=成功，1=用户取消，2=以其他方式结束。
     */
    class Response extends DBusSignal {

        public final UInt32 response;
        public final Map<String, Variant<?>> results;

        public Response(String path, UInt32 response, Map<String, Variant<?>> results) throws DBusException {
            super(path, response, results);
            this.response = response;
            this.results = results;
        }
    }
}
