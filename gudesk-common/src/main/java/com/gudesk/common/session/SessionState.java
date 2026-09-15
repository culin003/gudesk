package com.gudesk.common.session;

/**
 * TCP 直连会话状态（主控端/被控端共用）。
 *
 * <p>状态流转：
 * <ul>
 *   <li>主控端：NEGOTIATING（发送 SessionNegotiate，等待 Ack）→ ESTABLISHED / CLOSED；</li>
 *   <li>被控端：NEGOTIATING（等待 SessionNegotiate）→ AUTHENTICATING（密码已过、等待
 *       用户授权确认）→ ESTABLISHED → CLOSED；密码错误/授权拒绝时直接 CLOSED。</li>
 * </ul>
 */
public enum SessionState {

    /** 协商中：交换 X25519 临时公钥、验证密码证明 */
    NEGOTIATING("协商中"),
    /** 等待授权确认：密码已通过，等待被控端用户确认（30s 超时视为拒绝） */
    AUTHENTICATING("等待授权确认"),
    /** 已建立：密钥激活，视频传输与输入控制进行中 */
    ESTABLISHED("已建立"),
    /** 已关闭 */
    CLOSED("已关闭");

    private final String displayText;

    SessionState(String displayText) {
        this.displayText = displayText;
    }

    /** 展示文案 */
    public String displayText() {
        return displayText;
    }
}
