package com.gudesk.server.relay;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

/**
 * 中继转发处理器：配对完成后挂在两端 pipeline 上，
 * 入站字节原样写给对端（纯 byte 直通，不解密不解析——中继不可见明文）；
 * 本端断开 → 关闭对端。
 */
final class RelayForwardHandler extends ChannelInboundHandlerAdapter {

    private final RelaySession session;
    private final Channel peer;

    RelayForwardHandler(RelaySession session, Channel peer) {
        this.session = session;
        this.peer = peer;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        ByteBuf buf = (ByteBuf) msg;
        if (peer.isActive()) {
            peer.writeAndFlush(buf);
        } else {
            buf.release();
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        // 客户端断开 → 对端也断开（会话在配对时已移出表，无额外清理）
        session.markClosed();
        peer.close();
        ctx.fireChannelInactive();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        ctx.close();
    }
}
