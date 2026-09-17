package com.gudesk.host.session;

import com.gudesk.common.session.ConnectionGatekeeper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 被控端服务生命周期封装（非阻塞，可复用）：密码加载 → 会话管理器 + TCP 会话服务
 * → 信令注册（失败降级为仅 ip:port 直连）。
 *
 * <p>与 {@link HostApp} 的关系：{@link HostApp} 面向 CLI/独立 {@code --host-only}
 * 模式，负责参数解析、打印与阻塞等待，并默认注入 {@link SwingAuthorizer}；本类
 * 面向合并 UI（gudesk-launcher），由组合根注入 JavaFX 版 {@link Authorizer}（JavaFX
 * 实现不在此模块，避免 host 依赖 JavaFX）。
 *
 * <p>授权确认实现由构造器注入 {@link Authorizer}（不可为 null），
 * {@code --auto-accept} 对应 {@link AutoAuthorizer}{@code (ACCEPT)}。
 */
public final class HostService implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(HostService.class);

    private final Authorizer authorizer;
    private final int tcpPort;
    private final InetSocketAddress signalingAddress;
    private final boolean noUdp;

    private volatile HostPasswordStore.PasswordRecord passwordRecord;
    private volatile HostSessionManager sessionManager;
    private volatile TcpSessionServer server;
    private volatile HostSignalingService signalingService;
    /** 首次生成密码时的明文（仅本次进程可见，供 UI 一次性告知；已存在密码时为 null） */
    private volatile String newlyGeneratedPassword;
    private volatile boolean started;

    /** 会话生命周期监听器缓冲（start 前注册的监听在会话管理器创建后转发） */
    private final List<HostSessionManager.SessionListener> sessionListeners =
            new CopyOnWriteArrayList<>();

    /**
     * @param authorizer       授权确认实现（Swing / JavaFX / Auto），不可为 null
     * @param tcpPort          TCP 会话监听端口
     * @param signalingAddress 信令服务器地址（null=未配置，仅 ip:port 直连）
     * @param noUdp            true=跳过 UDP 打洞，仅 TCP 直连/中继（测试）
     */
    public HostService(Authorizer authorizer, int tcpPort,
                       InetSocketAddress signalingAddress, boolean noUdp) {
        this.authorizer = Objects.requireNonNull(authorizer, "authorizer");
        this.tcpPort = tcpPort;
        this.signalingAddress = signalingAddress;
        this.noUdp = noUdp;
    }

    /**
     * 启动被控服务（非阻塞返回）。
     *
     * <p>密码加载失败或 TCP 会话服务启动失败时抛 {@link IOException}（调用方决定退出码）；
     * 信令注册失败降级为仅 ip:port 直连（{@link #assignedId()} 返回 null），不抛出。
     */
    public void start() throws IOException {
        HostPasswordStore.LoadResult loaded = HostPasswordStore.loadOrGenerate();
        this.passwordRecord = loaded.record();
        this.newlyGeneratedPassword = loaded.generatedPlainPassword();

        this.sessionManager = new HostSessionManager(passwordRecord, authorizer,
                new TrustStore(TrustStore.DEFAULT_FILE),
                new ConnectionGatekeeper(ConnectionGatekeeper.DEFAULT_MAX_FAILS,
                        ConnectionGatekeeper.DEFAULT_LOCK_DURATION));
        for (HostSessionManager.SessionListener listener : sessionListeners) {
            sessionManager.addSessionListener(listener);
        }
        this.server = new TcpSessionServer(tcpPort, sessionManager);
        try {
            server.start();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("会话服务启动被中断", e);
        } catch (Exception e) {
            throw new IOException("会话服务启动失败", e);
        }

        HostSignalingService signaling = null;
        if (signalingAddress != null) {
            signaling = new HostSignalingService(sessionManager,
                    server::boundPort, signalingAddress, noUdp);
            try {
                signaling.start();
            } catch (IOException e) {
                LOG.warn("信令注册失败（纯 ID 连接不可用，仅支持 ip:port 直连）: {}", String.valueOf(e));
                signaling.close();
                signaling = null;
            }
        }
        this.signalingService = signaling;
        this.started = true;
    }

    /** 服务器分配的被控 ID（信令未注册时为 null，仅支持 ip:port 直连） */
    public String assignedId() {
        HostSignalingService s = signalingService;
        return s == null ? null : s.assignedId();
    }

    /** 是否有进行中的会话（"被控中"状态指示） */
    public boolean hasActiveSession() {
        HostSessionManager m = sessionManager;
        return m != null && m.hasActiveSession();
    }

    /** 注册会话生命周期监听器（"被控中"状态指示；start 前注册亦会在会话管理器创建后转发） */
    public void addSessionListener(HostSessionManager.SessionListener listener) {
        Objects.requireNonNull(listener, "listener");
        sessionListeners.add(listener);
        HostSessionManager m = sessionManager;
        if (m != null) {
            m.addSessionListener(listener);
        }
    }

    /** 移除会话生命周期监听器 */
    public void removeSessionListener(HostSessionManager.SessionListener listener) {
        sessionListeners.remove(listener);
        HostSessionManager m = sessionManager;
        if (m != null) {
            m.removeSessionListener(listener);
        }
    }

    /** TCP 会话实际监听端口 */
    public int boundPort() {
        TcpSessionServer s = server;
        return s == null ? tcpPort : s.boundPort();
    }

    /** 首次生成密码时的明文（未生成过时为 null，仅本次进程可见） */
    public String newlyGeneratedPassword() {
        return newlyGeneratedPassword;
    }

    /** 修改被控密码：写盘 + 热更新到运行中的会话管理器（仅影响后续新建会话） */
    public void setPassword(String plainPassword) throws IOException {
        HostPasswordStore.PasswordRecord record = HostPasswordStore.setPassword(plainPassword);
        this.passwordRecord = record;
        HostSessionManager m = sessionManager;
        if (m != null) {
            m.updatePassword(record);
        }
    }

    /** 运行时重新配置信令服务器：关闭旧信令并重新注册（null=清空，仅 ip:port 直连） */
    public void reconfigureServer(InetSocketAddress newServer) {
        HostSignalingService old = signalingService;
        if (old != null) {
            signalingService = null;
            old.close();
        }
        HostSessionManager m = sessionManager;
        if (m == null || newServer == null) {
            return; // 尚未启动 / 未配置服务器
        }
        HostSignalingService sig = new HostSignalingService(m, server::boundPort, newServer, noUdp);
        try {
            sig.start();
            this.signalingService = sig;
        } catch (IOException e) {
            LOG.warn("信令服务器重配置失败: {}", String.valueOf(e));
            sig.close();
        }
    }

    /** 是否已启动 */
    public boolean isStarted() {
        return started;
    }

    /** 停止被控服务（幂等；重复调用安全） */
    public void stop() {
        HostSignalingService s = signalingService;
        if (s != null) {
            signalingService = null;
            s.close();
        }
        TcpSessionServer srv = server;
        if (srv != null) {
            server = null;
            srv.stop();
        }
        started = false;
    }

    @Override
    public void close() {
        stop();
    }
}
