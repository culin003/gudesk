package com.gudesk.host.session;

import com.gudesk.common.net.RelayConnector;
import com.gudesk.common.proto.GuDeskProto.SessionMessage;
import com.gudesk.common.proto.GuDeskProto.SessionNegotiateAck;
import com.gudesk.common.session.ConnectionGatekeeper;
import com.gudesk.common.session.TcpSessionEndpoint;
import com.gudesk.common.session.UdpSessionEndpoint;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 被控端会话管理器：TCP 直连 / UDP 打洞 / 中继三种接入路径共享的单会话槽与
 * 门卫（密码失败锁定），会话建立统一走 {@link HostSession}（传输层解耦）。
 *
 * <p>主控端连接编排是"任一成功取消其他"，但并发路径可能短暂同时到达（UDP 打洞
 * 与 TCP 直连同时在途），此时先到者占槽，后到者按"被控中"拒绝/忽略；被放弃的
 * 路径无流量，由 {@link HostSession} 的协商启动超时释放槽位。
 */
public final class HostSessionManager {

    private static final Logger LOG = LoggerFactory.getLogger(HostSessionManager.class);

    /** 来源锁定提示（回给主控端的 reason） */
    public static final String REASON_LOCKED = "失败次数过多，已锁定";
    /** 单会话占用提示 */
    public static final String REASON_BUSY = "被控中：已有主控会话进行";

    private final HostPasswordStore.PasswordRecord password;
    private final Authorizer authorizer;
    private final TrustStore trustStore;
    private final ConnectionGatekeeper gatekeeper;

    /** 单会话槽：最多 1 个并发"被控中"会话 */
    private final AtomicReference<HostSession> activeSession = new AtomicReference<>();

    public HostSessionManager(HostPasswordStore.PasswordRecord password, Authorizer authorizer,
                              TrustStore trustStore, ConnectionGatekeeper gatekeeper) {
        this.password = password;
        this.authorizer = authorizer;
        this.trustStore = trustStore;
        this.gatekeeper = gatekeeper;
    }

    /** 是否有进行中的会话（"被控中"状态指示） */
    public boolean hasActiveSession() {
        HostSession session = activeSession.get();
        return session != null && !session.isTerminated();
    }

    /**
     * 是否有已开始协商的进行中会话（中继兜底判断：空闲预建连接——打洞成功的
     * UDP 会话 / TCP 直连 / 中继预连接，对端可能已改走其他路径，可被替换）。
     */
    public boolean hasNegotiatedSession() {
        HostSession session = activeSession.get();
        return session != null && !session.isTerminated() && session.hasNegotiationStarted();
    }

    /**
     * TCP/中继通道接入（已 accept/已连出、尚未安装任何 handler）：
     * 锁定/占用检查 → 建 {@link TcpSessionEndpoint} + {@link HostSession}，
     * <b>接线后再安装 pipeline</b>（通道已缓冲的首条协商消息不丢）。
     * 被拒时以明文 Ack 拒绝并延迟关闭。
     *
     * <p>与 {@link #tryCreateUdp} 对称的空闲抢占：主控端并发尝试（打洞/TCP/中继）
     * 可能多条路径同时到达，被放弃的路径无协商流量——若槽被"已接入但尚未协商"
     * 的空闲连接占用（如打洞成功但主控端最终走了中继），本路径抢占之；占用者
     * 已开始协商则不打扰（拒绝）。
     *
     * @return 建立的会话；被拒绝时返回 null（通道已自行处置）
     */
    public HostSession tryCreateTcp(SocketChannel channel) {
        String ip = remoteIp(channel);
        if (gatekeeper.isLocked(ip)) {
            LOG.warn("来源 {} 密码失败次数过多已锁定，直接拒绝（不给密码验证机会）", ip);
            rejectWithReason(channel, REASON_LOCKED);
            return null;
        }
        HostSession existing = activeSession.get();
        if (existing != null && !existing.isTerminated()) {
            if (existing.hasNegotiationStarted()) {
                rejectWithReason(channel, REASON_BUSY);
                return null;
            }
            LOG.info("TCP/中继路径抢占尚未协商的空闲连接，切换会话路径: {}", channel.remoteAddress());
            existing.shutdown("连接路径已切换为 TCP/中继");
        }
        TcpSessionEndpoint endpoint = new TcpSessionEndpoint(null, null);
        HostSession session = new HostSession(endpoint, password, authorizer, trustStore,
                ok -> onPasswordResult(ip, ok), this::releaseSlot);
        if (!activeSession.compareAndSet(null, session)) {
            rejectWithReason(channel, REASON_BUSY); // 并发接入竞态：仅一个赢家
            return null;
        }
        endpoint.attach(channel, session, session::onMessage);
        session.start();
        return session;
    }

    /**
     * 中继预连接：连中继 + token 首行 + 建 TCP 会话（打洞失败时主控端经中继接入）。
     *
     * @param group Netty IO 线程组（调用方持有生命周期）
     */
    public HostSession connectRelay(EventLoopGroup group, InetSocketAddress relayAddress,
                                    String relayToken) {
        try {
            SocketChannel channel = RelayConnector.connect(group, relayAddress, relayToken);
            HostSession session = tryCreateTcp(channel);
            if (session == null) {
                LOG.info("中继预连接被拒（被控中/锁定），关闭中继连接");
                return null;
            }
            LOG.info("中继预连接已建立（token={}..），等待主控端接入", relayToken.substring(0, 8));
            return session;
        } catch (Exception e) {
            LOG.warn("中继预连接失败: {}", String.valueOf(e));
            return null;
        }
    }

    /**
     * UDP 打洞成功接入：打洞 socket 承载会话（{@link UdpSessionEndpoint}）。
     *
     * <p>并发路径竞争（主控端 UDP 打洞与 TCP 直连同时在途）：若槽被"已接入但尚未
     * 协商"的空闲连接（TCP 直连/中继预连接）占用，UDP 直连优先抢占之（主控端编排
     * 以 UDP 优先，被抢占的连接是被放弃的路径）；若占用者已开始协商则不打扰，
     * 返回 null（主控端可靠通道重传耗尽后自行失败，回退其他路径）。
     *
     * @return 建立的会话；被占时返回 null（socket 由调用方关闭）
     */
    public HostSession tryCreateUdp(DatagramSocket socket, InetSocketAddress peer) {
        HostSession existing = activeSession.get();
        if (existing != null && !existing.isTerminated()) {
            if (existing.hasNegotiationStarted()) {
                LOG.info("UDP 打洞成功但会话槽被进行中的会话占用，忽略 UDP 路径: {}", peer);
                return null;
            }
            LOG.info("UDP 直连抢占尚未协商的空闲连接，切换会话路径: {}", peer);
            existing.shutdown("连接路径已切换为 UDP 直连");
        }
        UdpSessionEndpoint endpoint = new UdpSessionEndpoint(null, null, socket, peer);
        HostSession session = new HostSession(endpoint, password, authorizer, trustStore,
                ok -> onPasswordResult(addressOf(peer), ok), this::releaseSlot);
        if (!activeSession.compareAndSet(null, session)) {
            endpoint.close(REASON_BUSY); // 并发竞态：仅一个赢家（close 幂等关闭 socket）
            return null;
        }
        endpoint.start(session, session::onMessage); // 接线后再启动接收线程
        session.start();
        return session;
    }

    // ------------------------------------------------------------------

    /** 单会话槽是否被未终止的会话占用 */
    private boolean busy() {
        HostSession session = activeSession.get();
        return session != null && !session.isTerminated();
    }

    /** 密码验证结果上报（HostSession 回调）：成功清零 / 失败计数（达阈值锁定） */
    private void onPasswordResult(String ip, boolean success) {
        if (success) {
            gatekeeper.recordSuccess(ip);
            return;
        }
        gatekeeper.recordFailure(ip);
        LOG.warn("密码验证失败: 来源 {}，连续失败达 {} 次将锁定 {} 秒（当前计数 {}）",
                ip, ConnectionGatekeeper.DEFAULT_MAX_FAILS,
                ConnectionGatekeeper.DEFAULT_LOCK_DURATION.toSeconds(),
                gatekeeper.failCount(ip));
    }

    /** 以指定原因拒绝新连接（明文 Ack 后延迟关闭，留出发送窗口） */
    private void rejectWithReason(SocketChannel channel, String reason) {
        LOG.info("拒绝新连接（原因: {}）: {}", reason, channel.remoteAddress());
        try {
            TcpSessionEndpoint endpoint = new TcpSessionEndpoint(null, null);
            endpoint.attach(channel);
            endpoint.send(SessionMessage.newBuilder()
                    .setNegotiateAck(SessionNegotiateAck.newBuilder()
                            .setAuthorized(false)
                            .setReason(reason))
                    .build());
            channel.eventLoop().schedule((Runnable) channel::close, 300, TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
            LOG.warn("拒绝响应失败，直接关闭: {}", String.valueOf(t));
            channel.close();
        }
    }

    private void releaseSlot(HostSession session) {
        activeSession.compareAndSet(session, null);
    }

    /** 来源 IP 提取（剥离端口：锁定按 IP 维度） */
    private static String remoteIp(SocketChannel channel) {
        SocketAddress remote = channel.remoteAddress();
        if (remote instanceof InetSocketAddress isa && isa.getAddress() != null) {
            return isa.getAddress().getHostAddress();
        }
        return String.valueOf(remote);
    }

    private static String addressOf(InetSocketAddress address) {
        return address.getAddress() != null ? address.getAddress().getHostAddress()
                : address.getHostString();
    }
}
