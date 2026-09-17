package com.gudesk.viewer.decode;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link HardwareDecoderDetector} 单测：探测不抛异常、结果结构合法、缓存幂等，
 * 以及 OS 判定逻辑与 {@code os.name} 一致。无硬件环境返回 {@code null} 属预期，不视为失败。
 */
class HardwareDecoderDetectorTest {

    @Test
    void 探测不抛异常_返回null或有效结果且幂等() {
        HardwareDecoderDetector.Result result = HardwareDecoderDetector.detect();
        if (result != null) {
            assertFalse(result.accelName().isBlank(), "accelName 不应为空");
            assertFalse(result.decoderName().isBlank(), "decoderName 不应为空");
            assertTrue(result.deviceType() > 0, "deviceType 应为正数");
        }
        // 缓存幂等：重复调用返回同一实例（引用相等）
        assertSame(result, HardwareDecoderDetector.detect());
    }

    @Test
    void isWindows与osName一致() {
        boolean expected = System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT).contains("win");
        assertEquals(expected, HardwareDecoderDetector.isWindows());
    }
}
