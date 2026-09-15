package com.gudesk.host.session;

import com.gudesk.common.session.ConnectionGatekeeper;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * 被控端 TCP 会话服务：监听端口（默认 {@value #DEFAULT_PORT}），accept 后交
 * {@link HostSessionManager}（与 UDP 打洞/中继路径共享单会话槽与密码失败锁定）。
 *
 * <p>单会话约束：已有未终止会话时新连接直接以
 * SessionNegotiateAck{authorized=false, reason="被控中"} 拒绝（"被控中"状态指示）。
 *
 * <p>失败锁定：同一来源 IP 密码连续验证失败 5 次锁定 60s
 * （{@link ConnectionGatekeeper}），锁定期间该来源新连接直接以
 * SessionNegotiateAck{authorized=false, reason="失败次数过多，已锁定"} 拒绝，
 * 不给密码验证机会；成功验证清零计数，锁定到期自动解除。
 */
public final class TcpSessionServer {

    private static final Logger LOG = LoggerFactory.getLogger(TcpSessionServer.class);

    /** 默认监听端口 */
    public static final int DEFAULT_PORT = 48900;
    /** 来源锁定提示（回给主控端的 reason） */
    public static final String REASON_LOCKED = HostSessionManager.REASON_LOCKED;
    /** 单会话占用提示 */
    public static final String REASON_BUSY = HostSessionManager.REASON_BUSY;

    private final int port;
    private final HostSessionManager sessionManager;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private volatile Channel serverChannel;

    /**
     * 默认构造：信任列表 {@code ~/.gudesk/trusted_viewers}，失败锁定 5 次/60s。
     *
     * @param port       监听端口
     * @param password   密码验证记录
     * @param authorizer 授权确认实现（Swing 弹窗 / 自动接受）
     */
    public TcpSessionServer(int port, HostPasswordStore.PasswordRecord password,
                            Authorizer authorizer) {
        this(port, password, authorizer, new TrustStore(TrustStore.DEFAULT_FILE),
                new ConnectionGatekeeper(ConnectionGatekeeper.DEFAULT_MAX_FAILS,
                        ConnectionGatekeeper.DEFAULT_LOCK_DURATION));
    }

    /**
     * 全参构造：注入信任列表与门卫（测试用）。
     */
    public TcpSessionServer(int port, HostPasswordStore.PasswordRecord password,
                            Authorizer authorizer, TrustStore trustStore,
                            ConnectionGatekeeper gatekeeper) {
        this(port, new HostSessionManager(password, authorizer, trustStore, gatekeeper));
    }

    /**
     * 注入共享会话管理器构造（UDP 打洞/中继路径与 TCP accept 复用单会话槽）。
     */
    public TcpSessionServer(int port, HostSessionManager sessionManager) {
        this.port = port;
        this.sessionManager = sessionManager;
    }

    /** 启动监听（阻塞至绑定完成） */
    public void start() throws InterruptedException {
        bossGroup = new NioEventLoopGroup(1, threadFactory("gudesk-host-acceptor"));
        workerGroup = new NioEventLoopGroup(0, threadFactory("gudesk-host-io"));
        ServerBootstrap bootstrap = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        sessionManager.tryCreateTcp(ch);
                    }
                });
        serverChannel = bootstrap.bind(port).sync().channel();
        LOG.info("被控端会话服务已启动: 0.0.0.0:{}", port);
    }

    /** 实际监听端口（端口传 0 时由 OS 分配） */
    public int boundPort() {
        Channel ch = serverChannel;
        return ch != null ? ((InetSocketAddress) ch.localAddress()).getPort() : port;
    }

    /** 停止服务：关闭当前会话与监听 */
    public void stop() {
        Channel ch = serverChannel;
        if (ch != null) {
            ch.close();
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS);
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS);
        }
        LOG.info("被控端会话服务已停止");
    }

    /** 是否有进行中的会话（"被控中"状态指示） */
    public boolean hasActiveSession() {
        return sessionManager.hasActiveSession();
    }

    /** 守护线程工厂（显式 ThreadFactory，避免与 Executor 重载歧义） */
    private static ThreadFactory threadFactory(String name) {
        return r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        };
    }
}
