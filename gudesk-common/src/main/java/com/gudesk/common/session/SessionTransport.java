package com.gudesk.common.session;

import com.gudesk.common.crypto.SessionCipher;
import com.gudesk.common.proto.GuDeskProto.SessionMessage;

/**
 * 会话传输层抽象：将 {@code HostSession}/{@code ViewerSessionClient} 的会话状态机
 * 与底层传输（TCP 直连 / UDP 打洞直连 / TCP 中继）解耦。
 *
 * <p>实现类负责：线帧编解码（含明文→密文管线切换）、会话内心跳、消息分发
 * （心跳/关闭/视频帧路由到 {@link SessionEventListener}，其余回调 messageHandler）。
 * 业务层（两轮协商、密码验证、授权、媒体管线）只依赖本接口。
 *
 * <p>{@code reliable} 语义：UDP 实现中 true=可靠通道（序列号 + ACK + 500ms 重传，
 * 3 次失败断开，按 seq 有序投递），false=尽力而为（视频帧，容忍丢弃）；TCP 实现
 * 恒可靠，忽略该参数。
 */
public interface SessionTransport {

    /**
     * 发送会话消息。
     *
     * @param reliable true=需要确认与有序投递（协商/授权/心跳/输入事件/关闭）；
     *                 false=尽力而为（视频帧）
     * @return false=传输已关闭或发送失败（调用方按需丢弃）
     */
    boolean send(SessionMessage message, boolean reliable);

    /** 便捷重载：按可靠消息发送 */
    default boolean send(SessionMessage message) {
        return send(message, true);
    }

    /** 协商完成后激活 AES-GCM 密文通道（之前发送的均为明文协商消息） */
    void activateCipher(SessionCipher sessionCipher);

    /** 更新会话状态；进入 ESTABLISHED 启动会话内心跳 */
    void setState(SessionState newState);

    /** 出口是否可写（false 时应丢弃视频帧防积压；UDP 实现恒 !isClosed） */
    boolean isWritable();

    /** 对端地址描述（展示用） */
    String remoteDescription();

    boolean isClosed();

    /** 关闭会话（幂等）：停心跳/重传，回调 onStateChange(CLOSED) 与 onClose(reason) */
    void close(String reason);
}
