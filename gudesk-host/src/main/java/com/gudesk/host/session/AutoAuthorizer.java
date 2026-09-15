package com.gudesk.host.session;

import com.gudesk.common.proto.GuDeskProto.AuthorizeRequest;
import com.gudesk.common.proto.GuDeskProto.AuthorizeResponse;
import com.google.protobuf.ByteString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 自动授权实现（联调/测试/无人值守用）：可配置自动接受/拒绝/模拟超时。
 *
 * <p>提供 {@link #requestCount()} 咨询计数，供测试验证信任列表命中时未被咨询。
 */
public final class AutoAuthorizer implements Authorizer {

    private static final Logger LOG = LoggerFactory.getLogger(AutoAuthorizer.class);

    /** 决策类型 */
    public enum Decision {
        /** 自动接受 */
        ACCEPT,
        /** 自动拒绝 */
        REJECT,
        /** 模拟超时：延迟后以 TimeoutException 异常完成（HostSession 视为超时拒绝） */
        TIMEOUT
    }

    /** 模拟超时的延迟调度线程（守护） */
    private static final ScheduledExecutorService TIMEOUT_SCHEDULER =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "gudesk-auto-authorizer-timeout");
                t.setDaemon(true);
                return t;
            });

    private final Decision decision;
    private final boolean alwaysTrust;
    private final long timeoutDelayMs;
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicReference<String> lastFingerprint = new AtomicReference<>("");

    public AutoAuthorizer(Decision decision) {
        this(decision, false, 0);
    }

    /**
     * @param decision       决策
     * @param alwaysTrust    接受时是否同时标记"始终信任"
     * @param timeoutDelayMs TIMEOUT 决策的模拟延迟（毫秒）
     */
    public AutoAuthorizer(Decision decision, boolean alwaysTrust, long timeoutDelayMs) {
        this.decision = decision;
        this.alwaysTrust = alwaysTrust;
        this.timeoutDelayMs = timeoutDelayMs;
        LOG.warn("AutoAuthorizer 启用（决策={}，alwaysTrust={}，超时延迟={}ms）——仅供联调测试",
                decision, alwaysTrust, timeoutDelayMs);
    }

    @Override
    public CompletableFuture<AuthorizeResponse> requestAuthorization(AuthorizeRequest request) {
        requests.incrementAndGet();
        lastFingerprint.set(request.getViewerFingerprint());
        return switch (decision) {
            case ACCEPT -> CompletableFuture.completedFuture(response(request, true, alwaysTrust));
            case REJECT -> CompletableFuture.completedFuture(response(request, false, false));
            case TIMEOUT -> {
                CompletableFuture<AuthorizeResponse> future = new CompletableFuture<>();
                TIMEOUT_SCHEDULER.schedule(
                        () -> future.completeExceptionally(new TimeoutException("授权确认超时（模拟）")),
                        Math.max(0, timeoutDelayMs), TimeUnit.MILLISECONDS);
                yield future;
            }
        };
    }

    /** 是否被咨询过（信任列表命中免确认时为 false） */
    public boolean wasConsulted() {
        return requests.get() > 0;
    }

    /** 被咨询次数 */
    public int requestCount() {
        return requests.get();
    }

    /** 最近一次咨询收到的主控端指纹（验证授权请求信息传递） */
    public String lastFingerprint() {
        return lastFingerprint.get();
    }

    private static AuthorizeResponse response(AuthorizeRequest request, boolean accepted, boolean alwaysTrust) {
        return AuthorizeResponse.newBuilder()
                .setAccepted(accepted)
                .setAlwaysTrust(alwaysTrust)
                .setViewerFingerprint(ByteString.copyFrom(
                        request.getViewerFingerprint().getBytes(StandardCharsets.UTF_8)))
                .build();
    }
}
