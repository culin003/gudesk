package com.gudesk.viewer.session;

import com.gudesk.common.net.RelayConnector;
import com.gudesk.common.net.SignalingClient;
import com.gudesk.common.net.TransportCandidates;
import com.gudesk.common.net.UdpHolePuncher;
import com.gudesk.common.proto.GuDeskProto.ConnectAccept;
import com.gudesk.common.proto.GuDeskProto.ConnectRequest;
import com.gudesk.common.proto.GuDeskProto.PunchCandidate;
import com.gudesk.common.session.SessionEventListener;
import com.gudesk.common.session.SessionTransport;
import com.gudesk.common.session.TcpSessionEndpoint;
import com.gudesk.common.session.UdpSessionEndpoint;
import com.google.protobuf.ByteString;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 主控端连接编排器：统一处理两类连接目标——
 * <ul>
 *   <li>{@code ip:port}：TCP 直连（单候选，{@link #TCP_CONNECT_TIMEOUT_MS} 超时）；</li>
 *   <li>纯数字 ID：信令编排（与被控端 {@code HostSignalingService} 对偶）：
 *     <ol>
 *       <li>注册信令获得临时 ID；预绑定 UDP 打洞 socket 并 STUN 查询公网映射；</li>
 *       <li>ConnectRequest(自身 UDP 候选) → 等 ConnectAccept（被控端候选 + relay_token）；</li>
 *       <li><b>并发尝试</b>：UDP 打洞（{@link #PUNCH_TIMEOUT_MS}）与 TCP 直连（被控端
 *           tcp/ 候选逐个试，{@link #TCP_CONNECT_TIMEOUT_MS}）并行；</li>
 *       <li><b>UDP 优先</b>：TCP 连接成功后仍等待 {@link #UDP_PREFERENCE_GRACE_MS}
 *           打洞结果（打洞往返通常远小于该窗口），窗口内打洞成功则 UDP 胜出——
 *           被控端侧以"UDP 抢占未协商空闲连接"配合（见
 *           {@code HostSessionManager#tryCreateUdp}）；</li>
 *       <li>都失败 → 中继回落：连中继服务器发 relay_token 首行，配对后承载 TCP 会话协议。</li>
 *     </ol>
 *   </li>
 * </ul>
 *
 * <p>传输就绪后经 {@link Listener#onTransportReady} 交付（已接线回调并启动接收），
 * 调用方（{@link ViewerSessionClient#attachTransport}）随即发送第一轮协商。
 *
 * <p>{@code --prefer-relay}/{@code --no-udp} 测试模式：跳过打洞与 TCP 直连，
 * 收到 ConnectAccept 后直接中继（配合被控端 {@code --no-udp} 验证中继回落）。
 *
 * <p>线程模型：编排逻辑在单个虚拟线程上阻塞执行；打洞/TCP 连接为内部 Future；
 * {@link #cancel()} 从任意线程关闭在途资源（socket/通道/信令），编排线程感知后静默退出。
 */
public final class ViewerConnectionOrchestrator implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(ViewerConnectionOrchestrator.class);

    /** 服务器默认端口（与 gudesk-server 三件套一致） */
    public static final int DEFAULT_SIGNALING_PORT = 48900;
    public static final int DEFAULT_STUN_PORT = 3478;
    public static final int DEFAULT_RELAY_PORT = 48910;

    /** UDP 打洞总超时（毫秒），与被控端一致 */
    public static final long PUNCH_TIMEOUT_MS = 5_000;
    /** TCP 直连候选尝试总超时（毫秒） */
    public static final long TCP_CONNECT_TIMEOUT_MS = 3_000;
    /** UDP 优先窗口（毫秒）：TCP 连接成功后仍等待打洞结果 */
    public static final long UDP_PREFERENCE_GRACE_MS = 500;
    /** 信令 ConnectAccept/Reject 等待超时（毫秒，服务器看门狗 10s + 余量） */
    public static final long SIGNALING_RESPONSE_TIMEOUT_MS = 12_000;

    /** 连接模式（状态栏文案依据） */
    public enum ConnectMode {
        UDP_DIRECT("直连(UDP)"), TCP_DIRECT("直连(TCP)"), RELAY("中继");

        private final String label;

        ConnectMode(String label) {
            this.label = label;
        }

        /** 状态栏文案（如 "直连(UDP)"） */
        public String label() {
            return label;
        }
    }

    /** 编排结果回调（编排虚拟线程上调用） */
    public interface Listener {

        /**
         * 传输就绪（回调与接收已接线、已启动）：调用方随即发送第一轮协商。
         */
        void onTransportReady(SessionTransport transport, ConnectMode mode);

        /** 编排失败（信令拒绝/打洞与直连与中继全部失败） */
        void onFailed(String reason);
    }

    private final InetSocketAddress signalingAddress;
    private final InetSocketAddress stunAddress;
    private final InetSocketAddress relayAddress;
    private final boolean preferRelay;
    /** TCP 连接与中继共用 IO 线程组（守护线程） */
    private final EventLoopGroup ioGroup = new NioEventLoopGroup(1, r -> {
        Thread t = new Thread(r, "gudesk-viewer-orch-io");
        t.setDaemon(true);
        return t;
    });

    /** 当前进行中的编排（cancel 目标；connect 时替换） */
    private volatile Attempt currentAttempt;

    /**
     * {@code host[:port]} → 信令服务器地址（端口缺省 {@link #DEFAULT_SIGNALING_PORT}）。
     *
     * @throws IllegalArgumentException 格式非法/端口超范围
     */
    public static InetSocketAddress parseServerAddress(String text) {
        String trimmed = text == null ? "" : text.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("信令服务器地址为空");
        }
        int colon = trimmed.lastIndexOf(':');
        try {
            if (colon <= 0) {
                return new InetSocketAddress(trimmed, DEFAULT_SIGNALING_PORT);
            }
            int port = Integer.parseInt(trimmed.substring(colon + 1));
            if (port <= 0 || port > 65535) {
                throw new IllegalArgumentException("端口超范围: " + port);
            }
            return new InetSocketAddress(trimmed.substring(0, colon), port);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("信令服务器地址格式非法: " + text);
        }
    }

    /**
     * @param signalingServer 信令服务器地址（STUN/中继按同主机默认端口推导）
     * @param preferRelay     true=跳过打洞与 TCP 直连，直接走中继（测试中继回落）
     */
    public ViewerConnectionOrchestrator(InetSocketAddress signalingServer, boolean preferRelay) {
        this.signalingAddress = signalingServer;
        String host = signalingServer.getHostString();
        this.stunAddress = new InetSocketAddress(host, DEFAULT_STUN_PORT);
        this.relayAddress = new InetSocketAddress(host, DEFAULT_RELAY_PORT);
        this.preferRelay = preferRelay;
    }

    /**
     * 异步连接：结果经 listener 回调（编排虚拟线程）。
     *
     * @param target          被控 ID（纯数字）或 ip:port
     * @param sessionListener 就绪传输的会话事件监听器（ViewerSessionClient）
     * @param messageHandler  就绪传输的会话消息处理器（ViewerSessionClient::onMessage）
     * @param listener        编排结果回调
     */
    public void connect(String target, SessionEventListener sessionListener,
                        Consumer<com.gudesk.common.proto.GuDeskProto.SessionMessage> messageHandler,
                        Listener listener) {
        Attempt attempt = new Attempt(listener);
        currentAttempt = attempt;
        Thread.ofVirtual().name("gudesk-viewer-orchestrate").start(() ->
                attempt.run(target, sessionListener, messageHandler));
    }

    /** 取消当前编排（关闭在途资源；结果回调静默抑制；幂等） */
    public void cancel() {
        Attempt attempt = currentAttempt;
        if (attempt != null) {
            attempt.cancel();
        }
    }

    @Override
    public void close() {
        cancel();
        ioGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS);
    }

    // ------------------------------------------------------------------
    // 单次编排（虚拟线程）
    // ------------------------------------------------------------------

    private final class Attempt {
        private final Listener listener;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        /** 在途 TCP 通道（cancel 时全部关闭；交付后移除） */
        private final ConcurrentLinkedQueue<Channel> pendingChannels = new ConcurrentLinkedQueue<>();
        private volatile DatagramSocket punchSocket;
        private volatile SignalingClient signaling;

        Attempt(Listener listener) {
            this.listener = listener;
        }

        void run(String target, SessionEventListener sessionListener,
                 Consumer<com.gudesk.common.proto.GuDeskProto.SessionMessage> messageHandler) {
            String[] parsed = SessionUiConnector.parseTarget(target);
            if (parsed == null) {
                fail("连接目标格式非法: " + target + "（支持 ID 或 ip:port）");
                return;
            }
            try {
                if ("id".equals(parsed[0])) {
                    connectById(parsed[1], sessionListener, messageHandler);
                } else {
                    connectTcpDirect(parsed[0], Integer.parseInt(parsed[1]),
                            sessionListener, messageHandler);
                }
            } catch (CancellationException e) {
                cleanup();
            } catch (Exception e) {
                fail(describe(e));
            }
        }

        void cancel() {
            if (!cancelled.compareAndSet(false, true)) {
                return;
            }
            cleanup();
        }

        /** 关闭在途资源（cancel / 编排失败收尾共用；不触及已交付的传输） */
        private void cleanup() {
            SignalingClient s = signaling;
            signaling = null;
            if (s != null) {
                s.close();
            }
            DatagramSocket socket = punchSocket;
            punchSocket = null;
            if (socket != null) {
                socket.close(); // 打洞 Future 异常完成，接收/发送线程退出
            }
            Channel channel;
            while ((channel = pendingChannels.poll()) != null) {
                channel.close();
            }
        }

        private void fail(String reason) {
            cleanup();
            if (cancelled.get()) {
                return;
            }
            LOG.info("连接编排失败: {}", reason);
            listener.onFailed(reason);
        }

        private void checkCancelled() {
            if (cancelled.get()) {
                throw new CancellationException("连接已取消");
            }
        }

        // ------------------------------------------------------------------
        // 路径一：ip:port TCP 直连
        // ------------------------------------------------------------------

        private void connectTcpDirect(String host, int port, SessionEventListener sessionListener,
                                      Consumer<com.gudesk.common.proto.GuDeskProto.SessionMessage> messageHandler)
                throws Exception {
            LOG.info("TCP 直连: {}:{}", host, port);
            SocketChannel channel = tcpConnect(List.of(new InetSocketAddress(host, port)),
                    TCP_CONNECT_TIMEOUT_MS);
            deliverTcp(channel, sessionListener, messageHandler, ConnectMode.TCP_DIRECT);
        }

        // ------------------------------------------------------------------
        // 路径二：ID 信令编排
        // ------------------------------------------------------------------

        private void connectById(String targetId, SessionEventListener sessionListener,
                                 Consumer<com.gudesk.common.proto.GuDeskProto.SessionMessage> messageHandler)
                throws Exception {
            LOG.info("ID 连接: {}（信令 {}，STUN {}，中继 {}{}）",
                    targetId, signalingAddress, stunAddress, relayAddress,
                    preferRelay ? "，--prefer-relay 模式" : "");

            // 1. 预绑定打洞 socket + STUN 公网映射 + 组装候选
            List<PunchCandidate> candidates = new ArrayList<>();
            if (!preferRelay) {
                DatagramSocket socket = new DatagramSocket();
                punchSocket = socket;
                InetSocketAddress mapped = TransportCandidates.stunLookup(socket, stunAddress);
                for (String c : TransportCandidates.encodeAll(TransportCandidates.SCHEME_UDP,
                        TransportCandidates.localAddresses(socket.getLocalPort()))) {
                    candidates.add(PunchCandidate.newBuilder().setAddress(c).build());
                }
                if (mapped != null) {
                    candidates.add(PunchCandidate.newBuilder()
                            .setAddress(TransportCandidates.encode(TransportCandidates.SCHEME_UDP, mapped))
                            .build());
                }
                LOG.debug("UDP 候选: {}（STUN 映射: {}）",
                        candidates.size(), mapped == null ? "不可达" : mapped);
            }

            // 2. 信令注册 + ConnectRequest → ConnectAccept/Reject
            ConnectAccept accept = exchangeSignaling(targetId, candidates);

            // 3. 解析被控端候选
            List<InetSocketAddress> hostTcp = TransportCandidates.decodeAllCandidates(
                    accept.getCandidatesList(), TransportCandidates.SCHEME_TCP);
            List<InetSocketAddress> hostUdp = TransportCandidates.decodeAllCandidates(
                    accept.getCandidatesList(), TransportCandidates.SCHEME_UDP);
            String relayToken = accept.getRelayToken();
            LOG.info("被控端候选: tcp={} udp={} relay_token={}..",
                    hostTcp.size(), hostUdp.size(),
                    relayToken.isEmpty() ? "无" : relayToken.substring(0, 8));

            if (preferRelay) {
                // 测试模式：跳过打洞与 TCP 直连，直接中继
                deliverRelay(relayToken, sessionListener, messageHandler);
                return;
            }

            // 4. 并发尝试：UDP 打洞 与 TCP 直连（UDP 优先窗口）；均失败回落中继
            CompletableFuture<UdpHolePuncher.PunchOutcome> punch =
                    (!hostUdp.isEmpty() && punchSocket != null)
                            ? UdpHolePuncher.punch(punchSocket, hostUdp, PUNCH_TIMEOUT_MS)
                            : null;
            CompletableFuture<SocketChannel> tcp = hostTcp.isEmpty()
                    ? null : tcpConnectAsync(hostTcp, TCP_CONNECT_TIMEOUT_MS);
            boolean delivered = false;
            try {
                delivered = racePunchVsTcp(punch, tcp, sessionListener, messageHandler);
            } finally {
                closeTcpFuture(tcp);
            }
            if (!delivered) {
                checkCancelled();
                deliverRelay(relayToken, sessionListener, messageHandler);
            }
        }

        /** 信令交换：注册 → ConnectRequest → 等 ConnectAccept（异常=拒绝/超时/断开） */
        private ConnectAccept exchangeSignaling(String targetId, List<PunchCandidate> candidates)
                throws Exception {
            CompletableFuture<ConnectAccept> result = new CompletableFuture<>();
            SignalingClient client = new SignalingClient(signalingAddress, new SignalingClient.Listener() {
                @Override
                public void onConnectAccept(ConnectAccept accept) {
                    result.complete(accept);
                }

                @Override
                public void onConnectReject(com.gudesk.common.proto.GuDeskProto.ConnectReject reject) {
                    result.completeExceptionally(new IOException(
                            "被控端拒绝: " + (reject.getReason().isEmpty() ? "未知原因" : reject.getReason())));
                }

                @Override
                public void onDisconnected(String reason) {
                    result.completeExceptionally(new IOException("信令断开: " + reason));
                }
            });
            signaling = client;
            try {
                client.register(new byte[0]); // 临时 ID（公钥占位：会话密钥走临时 ECDH）
                client.sendConnectRequest(ConnectRequest.newBuilder()
                        .setTargetId(targetId)
                        .setViewerPublicKey(ByteString.EMPTY)
                        .addAllCandidates(candidates)
                        .build());
                ConnectAccept accept = result.get(SIGNALING_RESPONSE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                checkCancelled();
                return accept;
            } finally {
                signaling = null;
                client.close(); // 交换完成（或失败），信令连接不再需要
            }
        }

        /**
         * 打洞与 TCP 直连并发竞争（UDP 优先）。
         *
         * @return true=已交付传输（UDP 或 TCP）；false=均失败（调用方回落中继）
         */
        private boolean racePunchVsTcp(CompletableFuture<UdpHolePuncher.PunchOutcome> punch,
                                       CompletableFuture<SocketChannel> tcp,
                                       SessionEventListener sessionListener,
                                       Consumer<com.gudesk.common.proto.GuDeskProto.SessionMessage> messageHandler)
                throws CancellationException {
            if (punch == null && tcp == null) {
                LOG.info("被控端无 UDP/TCP 候选，回落中继");
                return false;
            }
            // 首个事件：打洞成功（立即胜出）或 TCP 出结果（成功后再等打洞 grace 窗口）
            CompletableFuture<Object> first = new CompletableFuture<>();
            if (punch != null) {
                punch.whenComplete((outcome, error) -> {
                    if (error == null) {
                        first.complete(outcome);
                    }
                });
            }
            if (tcp != null) {
                tcp.whenComplete((channel, error) -> first.complete(Boolean.TRUE));
            }
            Object winner;
            try {
                winner = first.get(PUNCH_TIMEOUT_MS + TCP_CONNECT_TIMEOUT_MS
                        + UDP_PREFERENCE_GRACE_MS + 1_000, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                LOG.warn("打洞/TCP 直连竞争等待超时，回落中继");
                return false;
            } catch (Exception e) {
                LOG.info("打洞/TCP 直连竞争异常退出（{}），回落中继", describe(e));
                return false;
            }
            checkCancelled();

            if (winner instanceof UdpHolePuncher.PunchOutcome outcome) {
                // 打洞先成功：UDP 胜出，丢弃 TCP（调用方 finally 统一关闭）
                LOG.info("UDP 打洞成功: {}（TCP 尝试已放弃）", outcome.peerAddress());
                deliverUdp(outcome.peerAddress(), sessionListener, messageHandler);
                return true;
            }
            // TCP 出结果（成功/失败）
            SocketChannel channel;
            try {
                channel = tcp.join();
            } catch (CompletionException e) {
                // TCP 全部候选失败：打洞可能仍在进行，等它结束
                if (punch != null) {
                    try {
                        UdpHolePuncher.PunchOutcome outcome =
                                punch.get(PUNCH_TIMEOUT_MS + 1_000, TimeUnit.MILLISECONDS);
                        LOG.info("TCP 直连失败后 UDP 打洞成功: {}", outcome.peerAddress());
                        deliverUdp(outcome.peerAddress(), sessionListener, messageHandler);
                        return true;
                    } catch (TimeoutException | ExecutionException | CancellationException
                            | InterruptedException ignored) {
                        // 打洞也失败 → 回落中继
                    }
                }
                LOG.info("UDP 打洞与 TCP 直连均失败（{}），回落中继", describe(e));
                return false;
            }
            // TCP 已连接：UDP 优先窗口内打洞成功则仍选 UDP
            if (punch != null) {
                try {
                    UdpHolePuncher.PunchOutcome outcome =
                            punch.get(UDP_PREFERENCE_GRACE_MS, TimeUnit.MILLISECONDS);
                    LOG.info("UDP 优先：打洞在窗口内成功（{}），放弃已连接的 TCP", outcome.peerAddress());
                    closeChannel(channel);
                    deliverUdp(outcome.peerAddress(), sessionListener, messageHandler);
                    return true;
                } catch (TimeoutException | ExecutionException | InterruptedException e) {
                    // 窗口内打洞未成功 → 用 TCP
                }
            }
            cancelPunch(); // 关闭打洞 socket（其 Future 异常完成，线程退出）
            LOG.info("TCP 直连胜出: {}", channel.remoteAddress());
            deliverTcp(channel, sessionListener, messageHandler, ConnectMode.TCP_DIRECT);
            return true;
        }

        // ------------------------------------------------------------------
        // 传输交付（接线 + 启动接收 + 回调）
        // ------------------------------------------------------------------

        private void deliverUdp(InetSocketAddress peer, SessionEventListener sessionListener,
                                Consumer<com.gudesk.common.proto.GuDeskProto.SessionMessage> messageHandler) {
            DatagramSocket socket = punchSocket;
            punchSocket = null; // socket 被会话接管，cancel 不再关闭
            if (socket == null || socket.isClosed()) {
                fail("打洞 socket 已失效");
                return;
            }
            UdpSessionEndpoint endpoint = new UdpSessionEndpoint(sessionListener, messageHandler,
                    socket, peer);
            endpoint.start();
            ready(endpoint, ConnectMode.UDP_DIRECT);
        }

        private void deliverTcp(SocketChannel channel, SessionEventListener sessionListener,
                                Consumer<com.gudesk.common.proto.GuDeskProto.SessionMessage> messageHandler,
                                ConnectMode mode) {
            pendingChannels.remove(channel); // 交付：cancel 不再关闭
            TcpSessionEndpoint endpoint = new TcpSessionEndpoint(sessionListener, messageHandler);
            endpoint.attach(channel);
            ready(endpoint, mode);
        }

        private void deliverRelay(String relayToken, SessionEventListener sessionListener,
                                  Consumer<com.gudesk.common.proto.GuDeskProto.SessionMessage> messageHandler)
                throws Exception {
            if (relayToken == null || relayToken.isEmpty()) {
                throw new IOException("被控端未提供中继令牌，中继回落不可用");
            }
            LOG.info("中继回落: {}（token={}..）", relayAddress, relayToken.substring(0, 8));
            SocketChannel channel = RelayConnector.connect(ioGroup, relayAddress, relayToken);
            checkCancelled();
            deliverTcp(channel, sessionListener, messageHandler, ConnectMode.RELAY);
        }

        private void ready(SessionTransport transport, ConnectMode mode) {
            if (cancelled.get()) {
                transport.close("连接已取消");
                return;
            }
            LOG.info("传输就绪（模式: {}，对端: {}）", mode.label(), transport.remoteDescription());
            listener.onTransportReady(transport, mode);
        }

        // ------------------------------------------------------------------
        // TCP 候选连接
        // ------------------------------------------------------------------

        /** 异步逐候选 TCP 连接（阻塞在内部虚拟线程），全部失败异常完成 */
        private CompletableFuture<SocketChannel> tcpConnectAsync(List<InetSocketAddress> addresses,
                                                                 long timeoutMs) {
            CompletableFuture<SocketChannel> future = new CompletableFuture<>();
            Thread.ofVirtual().name("gudesk-viewer-tcp-connect").start(() -> {
                try {
                    future.complete(tcpConnect(addresses, timeoutMs));
                } catch (Throwable t) {
                    future.completeExceptionally(t);
                }
            });
            return future;
        }

        /** 逐候选阻塞连接（总超时），成功返回首个连通通道（登记 pendingChannels 供取消） */
        private SocketChannel tcpConnect(List<InetSocketAddress> addresses, long timeoutMs)
                throws IOException {
            long deadline = System.currentTimeMillis() + timeoutMs;
            IOException last = null;
            for (InetSocketAddress address : addresses) {
                checkCancelled();
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    break;
                }
                try {
                    Bootstrap bootstrap = new Bootstrap()
                            .group(ioGroup)
                            .channel(NioSocketChannel.class)
                            .option(ChannelOption.TCP_NODELAY, true)
                            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS,
                                    (int) Math.min(remaining, timeoutMs))
                            .handler(new ChannelInboundHandlerAdapter()); // 占位，attach 后由 endpoint 接管
                    ChannelFuture connect = bootstrap.connect(address).awaitUninterruptibly();
                    if (connect.isSuccess()) {
                        SocketChannel channel = (SocketChannel) connect.channel();
                        pendingChannels.add(channel);
                        return channel;
                    }
                    last = new IOException(address + ": "
                            + (connect.cause() == null ? "未知原因" : connect.cause().getMessage()));
                    LOG.debug("TCP 候选连接失败: {}", last.getMessage());
                } catch (CancellationException e) {
                    throw e;
                } catch (Exception e) {
                    last = new IOException(address + ": " + e.getMessage());
                    LOG.debug("TCP 候选连接异常: {}", e.toString());
                }
            }
            throw last != null ? last : new IOException("TCP 直连超时（" + timeoutMs + " ms）");
        }

        // ------------------------------------------------------------------

        private void cancelPunch() {
            DatagramSocket socket = punchSocket;
            punchSocket = null;
            if (socket != null) {
                socket.close();
            }
        }

        private void closeChannel(Channel channel) {
            pendingChannels.remove(channel);
            channel.close();
        }

        private void closeTcpFuture(CompletableFuture<SocketChannel> tcp) {
            if (tcp == null) {
                return;
            }
            Channel channel;
            while ((channel = pendingChannels.poll()) != null) {
                channel.close();
            }
            tcp.cancel(false);
        }
    }

    private static String describe(Throwable t) {
        Throwable cause = t instanceof CompletionException && t.getCause() != null
                ? t.getCause() : t;
        return cause.getMessage() == null ? cause.toString() : cause.getMessage();
    }
}
