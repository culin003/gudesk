package com.gudesk.host.portal;

/**
 * RemoteDesktop.SelectDevices 的设备类型位掩码常量
 * （org.freedesktop.portal.RemoteDesktop:AvailableDeviceTypes）。
 */
public final class PortalDevice {

    /** 键盘（NotifyKeyboardKeycode / NotifyKeyboardKeysym 可用） */
    public static final int KEYBOARD = 1;
    /** 指针（NotifyPointer* 系列方法可用） */
    public static final int POINTER = 2;
    /** 触摸屏（NotifyTouch* 系列方法可用） */
    public static final int TOUCHSCREEN = 4;

    private PortalDevice() {
    }
}
