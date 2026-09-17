package com.gudesk.viewer.ui;

/**
 * 主控端连接策略：用户对「数据通道建立方式」的显式选择。
 *
 * <p>仅影响「纯数字 ID」连接（信令编排路径）；{@code ip:port} 连接始终为
 * TCP 直连，不经过此策略。策略由 UI 每次连接前选择并随连接请求传递，
 * 编排器据此决定是否跳过打洞/直连或中继回落。
 */
public enum ConnectPolicy {

    /** 自动（默认）：UDP 打洞 → TCP 直连 → 中继回落 */
    AUTO("自动"),

    /** 仅直连：只尝试 UDP 打洞与 TCP 直连，失败即终止（不回落中继） */
    DIRECT_ONLY("仅直连"),

    /** 仅中继：跳过打洞与直连，直接经中继转发 */
    RELAY_ONLY("仅中继");

    private final String label;

    ConnectPolicy(String label) {
        this.label = label;
    }

    /** 下拉框/状态栏展示文案（如 "仅直连"） */
    public String label() {
        return label;
    }
}
