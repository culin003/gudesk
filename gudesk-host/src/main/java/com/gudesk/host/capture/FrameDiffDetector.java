package com.gudesk.host.capture;

/**
 * 帧变化检测与静止退避纯逻辑（无副作用、可独立单测）。
 *
 * <p>捕获线程对每帧画面做降采样网格（默认 64×36）比较：
 * <ul>
 *   <li>差异采样点比例 &ge; {@link #DEFAULT_STATIC_THRESHOLD_RATIO}（0.5%）视为「变化」，
 *       否则视为「静止」；单个采样点是否「不同」按 R/G/B 通道差绝对值
 *       &gt; {@link #DEFAULT_CHANNEL_TOLERANCE} 判定（忽略 alpha，抗轻微抖动/抗锯齿噪声）；</li>
 *   <li>连续静止 {@link #DEFAULT_STATIC_FRAMES_BEFORE_BACKOFF} 帧后，输出帧间隔指数退避
 *       （每次翻倍），上限 {@link #DEFAULT_MAX_BACKOFF_MS}（1000ms，即 1fps 关键帧心跳）；</li>
 *   <li>检测到变化立即清零静止计数，恢复正常帧率（调用方在退避期间以
 *       {@link RobotScreenCapturer#BACKOFF_CHECK_INTERVAL_MS} 为上限周期性调用
 *       {@link #update(int[])} 检查，保证变化后 1s 内回升）。</li>
 * </ul>
 *
 * <p>线程模型：仅由捕获线程独占访问，无需同步。
 */
public final class FrameDiffDetector {

    /** 默认降采样网格宽度 */
    public static final int DEFAULT_GRID_WIDTH = 64;
    /** 默认降采样网格高度 */
    public static final int DEFAULT_GRID_HEIGHT = 36;
    /** 静止判定阈值：差异采样点比例低于该值视为静止 */
    public static final double DEFAULT_STATIC_THRESHOLD_RATIO = 0.005;
    /** 单采样点通道差容差（RGB 各通道差绝对值超过该值才计为不同） */
    public static final int DEFAULT_CHANNEL_TOLERANCE = 8;
    /** 触发指数退避前允许的连续静止帧数 */
    public static final int DEFAULT_STATIC_FRAMES_BEFORE_BACKOFF = 3;
    /** 退避间隔上限（1fps 关键帧心跳） */
    public static final long DEFAULT_MAX_BACKOFF_MS = 1000;

    private final int sampleCount;
    private final double staticThresholdCount;
    private final int channelTolerance;
    private final int staticFramesBeforeBackoff;
    private final long baseIntervalMs;
    private final long maxIntervalMs;

    private int[] previousSamples;
    private int staticStreak;

    /**
     * 按目标帧率构造（基础帧间隔 = 1000/fps）。
     */
    public FrameDiffDetector(int fps) {
        this(Math.max(1, 1000L / Math.max(1, fps)));
    }

    /**
     * 按基础帧间隔（毫秒）构造，其余参数取默认值。
     */
    public FrameDiffDetector(long baseIntervalMs) {
        this(baseIntervalMs,
                DEFAULT_GRID_WIDTH * DEFAULT_GRID_HEIGHT,
                DEFAULT_STATIC_THRESHOLD_RATIO,
                DEFAULT_CHANNEL_TOLERANCE,
                DEFAULT_STATIC_FRAMES_BEFORE_BACKOFF,
                DEFAULT_MAX_BACKOFF_MS);
    }

    /**
     * 完整构造。
     *
     * @param baseIntervalMs            基础帧间隔（毫秒，正常帧率下的间隔）
     * @param sampleCount               降采样网格采样点数（gridWidth * gridHeight）
     * @param staticThresholdRatio      静止判定的差异比例阈值（如 0.005 = 0.5%）
     * @param channelTolerance          单采样点通道差容差
     * @param staticFramesBeforeBackoff 触发退避前允许的连续静止帧数
     * @param maxIntervalMs             退避间隔上限（毫秒）
     */
    public FrameDiffDetector(long baseIntervalMs, int sampleCount, double staticThresholdRatio,
                             int channelTolerance, int staticFramesBeforeBackoff, long maxIntervalMs) {
        if (baseIntervalMs <= 0) {
            throw new IllegalArgumentException("baseIntervalMs 必须为正数: " + baseIntervalMs);
        }
        if (sampleCount <= 0) {
            throw new IllegalArgumentException("sampleCount 必须为正数: " + sampleCount);
        }
        this.baseIntervalMs = baseIntervalMs;
        this.sampleCount = sampleCount;
        this.staticThresholdCount = staticThresholdRatio * sampleCount;
        this.channelTolerance = channelTolerance;
        this.staticFramesBeforeBackoff = staticFramesBeforeBackoff;
        this.maxIntervalMs = maxIntervalMs;
    }

    /**
     * 送入当前帧的降采样网格（ARGB 像素数组），与上一帧比较。
     *
     * @return 是否「变化」：首帧恒为 true；其后差异采样点比例达到阈值则为 true
     */
    public boolean update(int[] samples) {
        if (samples == null || samples.length != sampleCount) {
            throw new IllegalArgumentException(
                    "采样数组长度必须为 " + sampleCount + "，实际 " + (samples == null ? -1 : samples.length));
        }
        boolean changed;
        if (previousSamples == null) {
            changed = true;
        } else {
            int diff = 0;
            for (int i = 0; i < sampleCount; i++) {
                if (pixelDiffers(samples[i], previousSamples[i])) {
                    diff++;
                }
            }
            changed = diff > staticThresholdCount;
        }
        staticStreak = changed ? 0 : staticStreak + 1;
        previousSamples = samples.clone();
        return changed;
    }

    /**
     * 当前输出帧间隔（毫秒）：正常为基础间隔；连续静止超过阈值后指数退避（翻倍），
     * 上限 {@code maxIntervalMs}。
     */
    public long currentIntervalMs() {
        int excess = staticStreak - staticFramesBeforeBackoff;
        if (excess <= 0) {
            return baseIntervalMs;
        }
        long interval = baseIntervalMs << Math.min(excess, 20);
        return Math.min(interval, maxIntervalMs);
    }

    /** 当前是否处于退避状态（输出帧间隔已大于基础间隔） */
    public boolean isBackingOff() {
        return staticStreak > staticFramesBeforeBackoff;
    }

    /** 连续静止帧计数（最近一次变化后累计的静止帧数） */
    public int staticStreak() {
        return staticStreak;
    }

    /** 重置状态（用于 start 周期开始时清空历史） */
    public void reset() {
        previousSamples = null;
        staticStreak = 0;
    }

    /** 降采样网格采样点数 */
    public int sampleCount() {
        return sampleCount;
    }

    private boolean pixelDiffers(int a, int b) {
        int dr = ((a >> 16) & 0xFF) - ((b >> 16) & 0xFF);
        if (dr > channelTolerance || dr < -channelTolerance) {
            return true;
        }
        int dg = ((a >> 8) & 0xFF) - ((b >> 8) & 0xFF);
        if (dg > channelTolerance || dg < -channelTolerance) {
            return true;
        }
        int db = (a & 0xFF) - (b & 0xFF);
        return db > channelTolerance || db < -channelTolerance;
    }

    @Override
    public String toString() {
        return "FrameDiffDetector{baseIntervalMs=" + baseIntervalMs + ", sampleCount=" + sampleCount
                + ", staticStreak=" + staticStreak + ", currentIntervalMs=" + currentIntervalMs() + '}';
    }
}
