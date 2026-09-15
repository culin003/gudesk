package com.gudesk.host.capture;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link FrameDiffDetector} 纯逻辑单测：静止 / 变化 / 恢复三场景。
 */
class FrameDiffDetectorTest {

    /** 默认 64×36 网格的采样点数 */
    private static final int SAMPLES = FrameDiffDetector.DEFAULT_GRID_WIDTH * FrameDiffDetector.DEFAULT_GRID_HEIGHT;
    private static final long BASE_MS = 33;
    private static final int N = FrameDiffDetector.DEFAULT_STATIC_FRAMES_BEFORE_BACKOFF;

    private static FrameDiffDetector detector() {
        return new FrameDiffDetector(BASE_MS, SAMPLES,
                FrameDiffDetector.DEFAULT_STATIC_THRESHOLD_RATIO,
                FrameDiffDetector.DEFAULT_CHANNEL_TOLERANCE, N,
                FrameDiffDetector.DEFAULT_MAX_BACKOFF_MS);
    }

    private static int[] uniform(int argb) {
        int[] samples = new int[SAMPLES];
        java.util.Arrays.fill(samples, argb);
        return samples;
    }

    @Test
    void 首帧恒视为变化() {
        FrameDiffDetector d = detector();
        assertTrue(d.update(uniform(0xFF102030)));
        assertEquals(0, d.staticStreak());
        assertEquals(BASE_MS, d.currentIntervalMs());
    }

    @Test
    void 静止场景_连续静止帧指数退避至上限() {
        FrameDiffDetector d = detector();
        int[] samples = uniform(0xFF102030);
        d.update(samples); // 首帧变化

        // 完全相同的帧：静止，静止计数累积
        for (int i = 1; i <= N; i++) {
            assertFalse(d.update(samples.clone()), "相同画面应判定为静止");
            assertEquals(i, d.staticStreak());
            assertFalse(d.isBackingOff());
            assertEquals(BASE_MS, d.currentIntervalMs(), "退避触发前保持基础间隔");
        }
        // 触发退避后间隔翻倍：33 -> 66 -> 132 -> 264 -> 528 -> 1056(封顶 1000)
        long[] expected = {BASE_MS * 2, BASE_MS * 4, BASE_MS * 8, BASE_MS * 16, BASE_MS * 32};
        for (int i = 0; i < expected.length; i++) {
            assertFalse(d.update(samples.clone()));
            assertTrue(d.isBackingOff());
            assertEquals(Math.min(expected[i], FrameDiffDetector.DEFAULT_MAX_BACKOFF_MS),
                    d.currentIntervalMs(), "退避第 " + (i + 1) + " 步的间隔");
        }
        // 长时间静止稳定在 1000ms（1fps 心跳）
        for (int i = 0; i < 50; i++) {
            assertFalse(d.update(samples.clone()));
        }
        assertEquals(1000L, d.currentIntervalMs());
    }

    @Test
    void 静止场景_通道容差内的轻微噪声仍视为静止() {
        FrameDiffDetector d = detector();
        int[] base = uniform(0xFF102030);
        d.update(base);
        // 每个采样点每通道仅 ±8 以内的抖动
        int[] noisy = uniform(0xFF182838); // R/G/B 各 +8，在容差内
        assertFalse(d.update(noisy));
        assertFalse(d.update(base));
    }

    @Test
    void 变化场景_差异比例达到阈值判定为变化() {
        FrameDiffDetector d = detector();
        int[] base = uniform(0xFF000000);
        d.update(base);
        // 差异采样点比例刚好低于 0.5%（2304 个采样点中 11 个，约 0.477%）→ 静止
        int[] fewDiff = base.clone();
        for (int i = 0; i < 11; i++) {
            fewDiff[i] = 0xFFFFFFFF; // 通道差远超容差
        }
        assertFalse(d.update(fewDiff), "差异比例 11/2304 ≈ 0.477% 应视为静止");
        // 重置基线后对比：12 个差异点 ≈ 0.52% ≥ 0.5% → 变化
        d.reset();
        d.update(base);
        int[] enoughDiff = base.clone();
        for (int i = 0; i < 12; i++) {
            enoughDiff[i] = 0xFFFFFFFF;
        }
        assertTrue(d.update(enoughDiff), "差异比例 12/2304 ≈ 0.52% 应视为变化");
        assertEquals(0, d.staticStreak());
        assertEquals(BASE_MS, d.currentIntervalMs());
    }

    @Test
    void 恢复场景_退避后检测到变化立即恢复正常帧率() {
        FrameDiffDetector d = detector();
        int[] base = uniform(0xFF336699);
        d.update(base);
        // 持续静止直至完全退避
        for (int i = 0; i < 30; i++) {
            assertFalse(d.update(base.clone()));
        }
        assertEquals(1000L, d.currentIntervalMs());
        assertTrue(d.isBackingOff());
        // 画面变化：立即恢复
        assertTrue(d.update(uniform(0xFFCC0033)));
        assertEquals(0, d.staticStreak());
        assertFalse(d.isBackingOff());
        assertEquals(BASE_MS, d.currentIntervalMs(), "变化后帧间隔应立即回到基础值（保证 1s 内回升）");
    }

    @Test
    void 非法采样数组抛出异常() {
        FrameDiffDetector d = detector();
        assertThrows(IllegalArgumentException.class, () -> d.update(new int[SAMPLES - 1]));
        assertThrows(IllegalArgumentException.class, () -> d.update(null));
    }

    @Test
    void reset清空历史状态() {
        FrameDiffDetector d = detector();
        d.update(uniform(0xFF000000));
        assertFalse(d.update(uniform(0xFF000000)));
        assertEquals(1, d.staticStreak());
        d.reset();
        assertEquals(0, d.staticStreak());
        // reset 后下一帧如同首帧，视为变化
        assertTrue(d.update(uniform(0xFF000000)));
    }
}
