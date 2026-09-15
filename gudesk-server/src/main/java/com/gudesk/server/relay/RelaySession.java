package com.gudesk.server.relay;

import io.netty.channel.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

import io.netty.buffer.ByteBuf;

/**
 * 中继会话：一个 relay_token 对应的一对连接。
 *
 * <p>配对协议：客户端连接中继服务器后，首先发送一行 UTF-8 文本 {@code token + '\n'}，
 * 之后进入纯字节双向透传（中继不可见明文，不解析不加密）。
 * 第一个带 token 的连接（A）进入等待，token 行之后到达的提前数据缓存在 {@link #pendingFromA}；
 * 第二个同 token 连接（B）到达即配对，此后双方字节流直通。
 *
 * <p>线程模型：pendingFromA 仅在 A 的 event loop 上访问（token 解析/后续读/配对切换均串行）；
 * a/b/closed 为 volatile 保证 B 端配对写入对 A 端的可见性。
 */
final class RelaySession {

    private static final Logger log = LoggerFactory.getLogger(RelaySession.class);

    final String token;
    /** A 端（首个到达的连接） */
    volatile Channel a;
    volatile HandshakeHandler aHandler;
    /** B 端（第二个到达的连接），非 null 表示已配对 */
    volatile Channel b;
    /** A 在等待 B 期间提前发送的数据（token 行之后、配对之前的字节），仅 A 的 event loop 访问 */
    final List<ByteBuf> pendingFromA = new ArrayList<>();
    volatile boolean closed;

    RelaySession(String token) {
        this.token = token;
    }

    /** 标记会话关闭并返回是否首次关闭（防重复清理）。 */
    boolean markClosed() {
        if (closed) {
            return false;
        }
        closed = true;
        log.debug("中继会话 {} 关闭", token);
        return true;
    }

    /** 释放未转发的提前数据，仅 A 的 event loop 调用。 */
    void releasePending() {
        for (ByteBuf buf : pendingFromA) {
            buf.release();
        }
        pendingFromA.clear();
    }
}
