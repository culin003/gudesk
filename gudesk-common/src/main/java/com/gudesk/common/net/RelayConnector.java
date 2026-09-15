package com.gudesk.common.net;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * 中继连接器：打洞失败时的兜底通道——连接中继服务器并完成 token 首行握手
 * （首行 {@code token + '\n'}），之后字节流即标准 TCP 会话协议（由调用方
 * {@code TcpSessionEndpoint} 接管，"连接到中继" 等价 "连接到对端"）。
 *
 * <p>中继服务器语义：第一个带 token 的连接等待，第二个同 token 连接到达即配对，
 * 之后双向透传（中继不可见明文）。
 */
public final class RelayConnector {

    /** 中继连接超时（毫秒） */
    public static final int CONNECT_TIMEOUT_MS = 5_000;

    private RelayConnector() {
    }

    /**
     * 连接中继并发送 token 首行。
     *
     * @param group        Netty IO 线程组（调用方持有生命周期）
     * @param relayAddress 中继服务器地址
     * @param token        配对令牌（ConnectAccept.relay_token）
     * @return 已连接且 token 已写出的通道（尚未安装任何会话 handler）
     */
    public static SocketChannel connect(EventLoopGroup group, InetSocketAddress relayAddress,
                                        String token) throws IOException {
        Bootstrap bootstrap = new Bootstrap()
                .group(group)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MS)
                .handler(new ChannelInboundHandlerAdapter()); // 占位，attach 后由 endpoint 接管
        ChannelFuture future = bootstrap.connect(relayAddress).awaitUninterruptibly();
        if (!future.isSuccess()) {
            throw new IOException("连接中继服务器失败: " + relayAddress + "（"
                    + future.cause().getMessage() + "）");
        }
        SocketChannel channel = (SocketChannel) future.channel();
        // 首行 token 握手；同一 channel 后续写出（会话消息）天然排在 token 之后
        channel.writeAndFlush(Unpooled.wrappedBuffer(
                (token + "\n").getBytes(StandardCharsets.UTF_8)));
        return channel;
    }
}
