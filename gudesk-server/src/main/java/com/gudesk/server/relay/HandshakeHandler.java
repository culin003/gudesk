package com.gudesk.server.relay;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

import java.nio.charset.StandardCharsets;

/**
 * 中继握手处理器：读取连接首行 token（UTF-8，以 '\n' 结尾），
 * 交给 {@link RelayServer#onToken} 配对；A 端等待期间缓存 token 行之后的提前数据。
 */
final class HandshakeHandler extends ChannelInboundHandlerAdapter {

    /** token 行最大长度（含换行），超过视为非法客户端直接断开 */
    static final int MAX_TOKEN_LINE = 1024;

    private final RelayServer server;
    private ChannelHandlerContext ctx;
    /** token 行未拼完的累积缓冲（多读到达时） */
    private ByteBuf acc;
    private boolean tokenDone;
    private RelaySession session;

    HandshakeHandler(RelayServer server) {
        this.server = server;
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        ByteBuf buf = (ByteBuf) msg;
        if (!tokenDone) {
            ByteBuf merged = acc == null ? buf : Unpooled.wrappedBuffer(acc, buf);
            acc = null;
            int nl = merged.indexOf(merged.readerIndex(), merged.writerIndex(), (byte) '\n');
            if (nl < 0) {
                if (merged.readableBytes() > MAX_TOKEN_LINE) {
                    merged.release();
                    ctx.close();
                    return;
                }
                acc = merged;
                return;
            }
            byte[] tokenBytes = new byte[nl - merged.readerIndex()];
            merged.readBytes(tokenBytes);
            merged.readByte(); // 消费 '\n'
            tokenDone = true;
            String token = new String(tokenBytes, StandardCharsets.UTF_8).stripTrailing();
            if (token.isEmpty()) {
                merged.release();
                ctx.close();
                return;
            }
            server.onToken(ctx, this, token, merged);
            return;
        }
        // token 已解析：A 等待 B 期间的提前数据，仅 A 的 event loop 执行到此（B 解析 token 即同步配对）
        if (session != null) {
            session.pendingFromA.add(buf);
        } else {
            buf.release();
        }
    }

    /** token 解析后由服务器回填所属会话。 */
    void assignedTo(RelaySession session) {
        this.session = session;
    }

    /**
     * B 端配对完成（在自身 event loop 内调用）：移除握手处理器，切换为纯转发到 A。
     */
    void switchToForwarding(Channel peer) {
        ctx.pipeline().remove(this);
        ctx.pipeline().addLast(new RelayForwardHandler(session, peer));
    }

    /**
     * A 端配对完成（由 B 端调度到 A 的 event loop 执行）：清空提前数据到 B，
     * 切换为纯转发；若期间会话已关闭则释放缓存并断开 B。
     */
    void completePairing(Channel peerB) {
        if (session.closed) {
            session.releasePending();
            peerB.close();
            return;
        }
        for (ByteBuf pending : session.pendingFromA) {
            peerB.write(pending, peerB.voidPromise());
        }
        peerB.flush();
        session.pendingFromA.clear();
        ctx.pipeline().remove(this);
        ctx.pipeline().addLast(new RelayForwardHandler(session, peerB));
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        if (session != null) {
            server.onHandshakeClosed(session, this);
        }
        ctx.fireChannelInactive();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        ctx.close();
    }
}
