package com.gudesk.viewer.ui;

/**
 * 主控端连接状态。
 */
public enum ConnectionState {

    /** 未连接 */
    DISCONNECTED("未连接"),
    /** 连接中（信令/打洞/直连尝试） */
    CONNECTING("连接中"),
    /** 已连接-直连（TCP 直连或 P2P UDP） */
    CONNECTED_DIRECT("已连接-直连"),
    /** 已连接-中继（经服务器中继转发） */
    CONNECTED_RELAY("已连接-中继");

    private final String displayText;

    ConnectionState(String displayText) {
        this.displayText = displayText;
    }

    /** 状态栏展示文案 */
    public String displayText() {
        return displayText;
    }

    /** 是否为任一已连接态 */
    public boolean isConnected() {
        return this == CONNECTED_DIRECT || this == CONNECTED_RELAY;
    }
}
