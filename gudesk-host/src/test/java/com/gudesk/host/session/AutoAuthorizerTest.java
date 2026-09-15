package com.gudesk.host.session;

import com.gudesk.common.proto.GuDeskProto.AuthorizeRequest;
import com.gudesk.common.proto.GuDeskProto.AuthorizeResponse;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AutoAuthorizer} 单元测试：接受/拒绝/模拟超时三决策与咨询计数。
 */
class AutoAuthorizerTest {

    private static AuthorizeRequest request() {
        return AuthorizeRequest.newBuilder()
                .setViewerId("/127.0.0.1:52344")
                .setViewerFingerprint("AB:CD:EF")
                .build();
    }

    @Test
    void 自动接受() throws Exception {
        AutoAuthorizer authorizer = new AutoAuthorizer(AutoAuthorizer.Decision.ACCEPT);
        AuthorizeResponse response = authorizer.requestAuthorization(request()).get(1, TimeUnit.SECONDS);
        assertTrue(response.getAccepted());
        assertFalse(response.getAlwaysTrust(), "默认不标记始终信任");
        assertEquals(1, authorizer.requestCount());
        assertTrue(authorizer.wasConsulted());
    }

    @Test
    void 自动接受并始终信任() throws Exception {
        AutoAuthorizer authorizer = new AutoAuthorizer(AutoAuthorizer.Decision.ACCEPT, true, 0);
        AuthorizeResponse response = authorizer.requestAuthorization(request()).get(1, TimeUnit.SECONDS);
        assertTrue(response.getAccepted());
        assertTrue(response.getAlwaysTrust());
    }

    @Test
    void 自动拒绝() throws Exception {
        AutoAuthorizer authorizer = new AutoAuthorizer(AutoAuthorizer.Decision.REJECT);
        AuthorizeResponse response = authorizer.requestAuthorization(request()).get(1, TimeUnit.SECONDS);
        assertFalse(response.getAccepted());
        assertFalse(response.getAlwaysTrust());
    }

    @Test
    void 模拟超时异常完成() {
        AutoAuthorizer authorizer = new AutoAuthorizer(AutoAuthorizer.Decision.TIMEOUT, false, 50);
        CompletableFuture<AuthorizeResponse> future = authorizer.requestAuthorization(request());
        ExecutionException e = assertThrows(ExecutionException.class,
                () -> future.get(3, TimeUnit.SECONDS), "延迟后应以 TimeoutException 异常完成");
        assertInstanceOf(TimeoutException.class, e.getCause());
    }

    @Test
    void 未咨询时计数为零() {
        AutoAuthorizer authorizer = new AutoAuthorizer(AutoAuthorizer.Decision.ACCEPT);
        assertEquals(0, authorizer.requestCount());
        assertFalse(authorizer.wasConsulted());
    }
}
