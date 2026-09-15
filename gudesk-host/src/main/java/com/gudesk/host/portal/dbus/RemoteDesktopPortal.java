package com.gudesk.host.portal.dbus;

import org.freedesktop.dbus.DBusPath;
import org.freedesktop.dbus.annotations.DBusInterfaceName;
import org.freedesktop.dbus.interfaces.DBusInterface;
import org.freedesktop.dbus.types.UInt32;
import org.freedesktop.dbus.types.Variant;

import java.util.Map;

/**
 * org.freedesktop.portal.RemoteDesktop 的 D-Bus 接口定义
 * （仅声明本封装层使用的成员，未含 EIS/触摸相关方法）。
 *
 * <p>类型映射：o → {@link DBusPath}，a{sv} → {@code Map<String, Variant<?>>}，
 * u → {@link UInt32}，d → double，i → int。
 */
@DBusInterfaceName("org.freedesktop.portal.RemoteDesktop")
public interface RemoteDesktopPortal extends DBusInterface {

    /** CreateSession(options a{sv}) → handle o：创建远程桌面会话 */
    DBusPath CreateSession(Map<String, Variant<?>> options);

    /** SelectDevices(session o, options a{sv}) → handle o：选择要控制的输入设备 */
    DBusPath SelectDevices(DBusPath sessionHandle, Map<String, Variant<?>> options);

    /** Start(session o, parent_window s, options a{sv}) → handle o：弹窗授权并启动会话 */
    DBusPath Start(DBusPath sessionHandle, String parentWindow, Map<String, Variant<?>> options);

    /** NotifyPointerMotion(session o, options a{sv}, dx d, dy d)：相对指针移动（无返回值） */
    void NotifyPointerMotion(DBusPath sessionHandle, Map<String, Variant<?>> options, double dx, double dy);

    /** NotifyPointerMotionAbsolute(session o, options a{sv}, stream u, x d, y d)：绝对指针移动（无返回值） */
    void NotifyPointerMotionAbsolute(DBusPath sessionHandle, Map<String, Variant<?>> options,
            UInt32 stream, double x, double y);

    /** NotifyPointerButton(session o, options a{sv}, button i, state u)：指针按钮（state 0=释放 1=按下，evdev 按钮码） */
    void NotifyPointerButton(DBusPath sessionHandle, Map<String, Variant<?>> options,
            int button, UInt32 state);

    /** NotifyPointerAxis(session o, options a{sv}, dx d, dy d)：平滑滚动轴事件（无返回值） */
    void NotifyPointerAxis(DBusPath sessionHandle, Map<String, Variant<?>> options, double dx, double dy);

    /** NotifyKeyboardKeycode(session o, options a{sv}, keycode i, state u)：按键（state 0=释放 1=按下，evdev 键码） */
    void NotifyKeyboardKeycode(DBusPath sessionHandle, Map<String, Variant<?>> options,
            int keycode, UInt32 state);
}
