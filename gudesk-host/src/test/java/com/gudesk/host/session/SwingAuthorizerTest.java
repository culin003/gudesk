package com.gudesk.host.session;

import com.gudesk.common.proto.GuDeskProto.AuthorizeRequest;
import com.gudesk.common.proto.GuDeskProto.AuthorizeResponse;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SwingAuthorizer} 逻辑测试：无头环境（注入 headless 判定）验证
 * 立即拒绝路径——不弹窗、不阻塞、future 快速完成。
 */
class SwingAuthorizerTest {

    private static AuthorizeRequest request() {
        return AuthorizeRequest.newBuilder()
                .setViewerId("/127.0.0.1:52344")
                .setViewerFingerprint("AB:CD:EF:01")
                .build();
    }

    @Test
    void 无头环境直接拒绝且立即完成() throws Exception {
        SwingAuthorizer authorizer = new SwingAuthorizer(30_000, () -> true);
        long start = System.nanoTime();
        CompletableFuture<AuthorizeResponse> future = authorizer.requestAuthorization(request());
        AuthorizeResponse response = future.get(1, TimeUnit.SECONDS);
        assertFalse(response.getAccepted(), "无头环境应默认拒绝");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertTrue(elapsedMs < 500, "无头拒绝应立即完成（实际 " + elapsedMs + "ms）");
    }

    @Test
    void 无头环境多次调用均拒绝() throws Exception {
        SwingAuthorizer authorizer = new SwingAuthorizer(30_000, () -> true);
        for (int i = 0; i < 3; i++) {
            AuthorizeResponse response = authorizer.requestAuthorization(request()).get(1, TimeUnit.SECONDS);
            assertFalse(response.getAccepted());
            assertFalse(response.getAlwaysTrust());
        }
    }
}
