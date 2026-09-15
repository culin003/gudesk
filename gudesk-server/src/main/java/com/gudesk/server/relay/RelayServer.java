package com.gudesk.server.relay;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.util.concurrent.DefaultThreadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * GuDesk 中继服务器：打洞失败时的兜底转发通道。
 *
 * <p>数据面使用 Netty（spec：中继转发走 Netty event loop，2 线程），
 * 令牌配对机制：客户端连接后首行发送 {@code token + '\n'}；
 * 第一个带 token 的连接（A）等待，第二个同 token 连接（B）到达即配对，
 * 之后双方字节流双向透传（纯 byte 转发，不解密不解析，中继不可见明文）。
 * 客户端断开 → 对端断开、清理会话。
 */
public final class RelayServer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RelayServer.class);

    public static final int DEFAULT_PORT = 48910;

    private final int port;
    private final ConcurrentHashMap<String, RelaySession> sessions = new ConcurrentHashMap<>();
    private EventLoopGroup group;
    private Channel serverChannel;
    private volatile boolean running;

    public RelayServer(int port) {
        this.port = port;
    }

    /** 启动 Netty TCP 监听（event loop 2 线程）。 */
    public synchronized void start() throws IOException {
        if (running) {
            return;
        }
        group = new NioEventLoopGroup(2, new DefaultThreadFactory("relay-el"));
        ServerBootstrap bootstrap = new ServerBootstrap()
                .group(group)
                .channel(NioServerSocketChannel.class)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(new HandshakeHandler(RelayServer.this));
                    }
                });
        try {
            serverChannel = bootstrap.bind(port).syncUninterruptibly().channel();
        } catch (RuntimeException e) {
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS);
            throw new IOException("中继服务器绑定端口失败: " + port, e);
        }
        running = true;
        log.info("中继服务器已启动: TCP {}", serverChannel.localAddress());
    }

    /** 实际监听端口（port=0 时为系统分配的端口）。 */
    public int getPort() {
        return ((InetSocketAddress) serverChannel.localAddress()).getPort();
    }

    /** 等待配对中的会话数，测试用。 */
    public int sessionCount() {
        return sessions.size();
    }

    @Override
    public synchronized void close() {
        if (!running) {
            return;
        }
        running = false;
        serverChannel.close();
        for (RelaySession session : sessions.values()) {
            closeSession(session);
        }
        sessions.clear();
        group.shutdownGracefully(0, 1, TimeUnit.SECONDS);
        log.info("中继服务器已停止");
    }

    private void closeSession(RelaySession session) {
        if (session.markClosed()) {
            sessions.remove(session.token, session);
            if (session.a != null) {
                // pendingFromA 归 A 的 event loop 所有，释放也调度过去（与 A 的读串行）
                session.a.eventLoop().execute(session::releasePending);
                session.a.close();
            }
            if (session.b != null) {
                session.b.close();
            }
        }
    }

    // ------------------------------------------------------------------
    // 令牌配对（由 HandshakeHandler 在其所属 event loop 上调用）
    // ------------------------------------------------------------------
    void onToken(ChannelHandlerContext ctx, HandshakeHandler handler, String token, ByteBuf rest) {
        RelaySession session = sessions.get(token);
        if (session == null) {
            RelaySession created = new RelaySession(token);
            RelaySession raced = sessions.putIfAbsent(token, created);
            if (raced == null) {
                // 我是 A：登记并等待 B
                created.a = ctx.channel();
                created.aHandler = handler;
                handler.assignedTo(created);
                if (rest.isReadable()) {
                    created.pendingFromA.add(rest);
                } else {
                    rest.release();
                }
                log.info("中继会话 {} 等待配对（A={}）", token, ctx.channel());
                return;
            }
            session = raced;
        }
        // 我是 B：检查会话可配对
        synchronized (session) {
            if (session.b != null || session.closed) {
                rest.release();
                ctx.close();
                return;
            }
            session.b = ctx.channel();
        }
        handler.assignedTo(session);
        sessions.remove(token, session); // token 一次性，配对后不再接受
        Channel a = session.a;
        Channel b = ctx.channel();
        log.info("中继会话 {} 配对完成（A={} <-> B={}）", token, a, b);
        // B 端立即切换为转发
        handler.switchToForwarding(a);
        // A 端切换（调度到 A 的 event loop，与 A 的 channelRead 串行）
        HandshakeHandler aHandler = session.aHandler;
        a.eventLoop().execute(() -> aHandler.completePairing(b));
        // B 的 token 行后提前数据 → A
        if (rest.isReadable()) {
            a.writeAndFlush(rest);
        } else {
            rest.release();
        }
    }

    /** 握手阶段（未完成配对切换）连接断开：清理会话并断开对端。 */
    void onHandshakeClosed(RelaySession session, HandshakeHandler handler) {
        if (session.markClosed()) {
            sessions.remove(session.token, session);
            if (handler == session.aHandler) {
                session.releasePending();
            }
            Channel other = handler == session.aHandler ? session.b : session.a;
            if (other != null) {
                other.close();
            }
        }
    }
}
