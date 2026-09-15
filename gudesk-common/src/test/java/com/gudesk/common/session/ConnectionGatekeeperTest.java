package com.gudesk.common.session;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ConnectionGatekeeper} 单元测试：假时钟推进验证锁定/解除/清零/来源隔离。
 */
class ConnectionGatekeeperTest {

    private static final String IP_A = "192.168.1.10";
    private static final String IP_B = "192.168.1.11";

    /** 可推进的假时钟 */
    private static final class FakeClock extends Clock {
        volatile long currentMillis = 1_000_000;

        void advanceMillis(long ms) {
            currentMillis += ms;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(currentMillis);
        }
    }

    @Test
    void 四次失败未达阈值不锁定() {
        FakeClock clock = new FakeClock();
        ConnectionGatekeeper gatekeeper = new ConnectionGatekeeper(5, Duration.ofSeconds(60), clock);
        for (int i = 0; i < 4; i++) {
            gatekeeper.recordFailure(IP_A);
        }
        assertFalse(gatekeeper.isLocked(IP_A), "4 次失败未达 5 次阈值，不应锁定");
        assertEquals(4, gatekeeper.failCount(IP_A));
    }

    @Test
    void 连续五次失败后锁定() {
        FakeClock clock = new FakeClock();
        ConnectionGatekeeper gatekeeper = new ConnectionGatekeeper(5, Duration.ofSeconds(60), clock);
        for (int i = 0; i < 5; i++) {
            gatekeeper.recordFailure(IP_A);
        }
        assertTrue(gatekeeper.isLocked(IP_A), "连续 5 次失败应锁定");
        assertEquals(0, gatekeeper.failCount(IP_A), "进入锁定时计数清零");
    }

    @Test
    void 锁定期间持续拒绝且不延长() {
        FakeClock clock = new FakeClock();
        ConnectionGatekeeper gatekeeper = new ConnectionGatekeeper(5, Duration.ofSeconds(60), clock);
        for (int i = 0; i < 5; i++) {
            gatekeeper.recordFailure(IP_A);
        }
        clock.advanceMillis(59_999);
        assertTrue(gatekeeper.isLocked(IP_A), "59.999s 时仍应锁定");
        // 锁定期间的失败不计数、不延长锁定
        gatekeeper.recordFailure(IP_A);
        clock.advanceMillis(1);
        assertFalse(gatekeeper.isLocked(IP_A), "满 60s 后应自动解除（未被延长）");
    }

    @Test
    void 锁定六十秒后自动解除且重新计数() {
        FakeClock clock = new FakeClock();
        ConnectionGatekeeper gatekeeper = new ConnectionGatekeeper(5, Duration.ofSeconds(60), clock);
        for (int i = 0; i < 5; i++) {
            gatekeeper.recordFailure(IP_A);
        }
        assertTrue(gatekeeper.isLocked(IP_A));
        clock.advanceMillis(60_000);
        assertFalse(gatekeeper.isLocked(IP_A), "60s 后锁定应自动解除");
        assertEquals(0, gatekeeper.failCount(IP_A), "解除后计数应清零");
        // 解除后重新计数：再 4 次失败不锁定，第 5 次再次锁定
        for (int i = 0; i < 4; i++) {
            gatekeeper.recordFailure(IP_A);
        }
        assertFalse(gatekeeper.isLocked(IP_A), "解除后 4 次失败不应再次锁定");
        gatekeeper.recordFailure(IP_A);
        assertTrue(gatekeeper.isLocked(IP_A), "解除后再连续 5 次失败应再次锁定");
    }

    @Test
    void 成功验证清零计数() {
        FakeClock clock = new FakeClock();
        ConnectionGatekeeper gatekeeper = new ConnectionGatekeeper(5, Duration.ofSeconds(60), clock);
        for (int i = 0; i < 4; i++) {
            gatekeeper.recordFailure(IP_A);
        }
        gatekeeper.recordSuccess(IP_A);
        assertEquals(0, gatekeeper.failCount(IP_A), "成功后计数应清零");
        assertFalse(gatekeeper.isLocked(IP_A));
        // 清零后再 4 次失败仍不锁定（连续计数被打断）
        for (int i = 0; i < 4; i++) {
            gatekeeper.recordFailure(IP_A);
        }
        assertFalse(gatekeeper.isLocked(IP_A), "成功清零后 4 次失败不应锁定");
    }

    @Test
    void 不同来源独立计数互不影响() {
        FakeClock clock = new FakeClock();
        ConnectionGatekeeper gatekeeper = new ConnectionGatekeeper(5, Duration.ofSeconds(60), clock);
        for (int i = 0; i < 5; i++) {
            gatekeeper.recordFailure(IP_A);
        }
        assertTrue(gatekeeper.isLocked(IP_A), "来源 A 应锁定");
        assertFalse(gatekeeper.isLocked(IP_B), "来源 B 不应受 A 影响");
        assertEquals(0, gatekeeper.failCount(IP_B));
        gatekeeper.recordFailure(IP_B);
        assertEquals(1, gatekeeper.failCount(IP_B), "来源 B 独立计数");
    }

    @Test
    void 参数校验() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new ConnectionGatekeeper(0, Duration.ofSeconds(60)));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new ConnectionGatekeeper(5, Duration.ZERO));
    }
}
