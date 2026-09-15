package com.gudesk.host.input;

/**
 * 归一化坐标（0~1）→ 屏幕像素坐标换算工具（纯逻辑、无 AWT 依赖，便于单测）。
 *
 * <p>换算规则：先 clamp 到 [0,1]，再乘屏幕宽/高并截断为 int，最后裁剪到
 * [0, screenSize-1]（保证结果落在屏幕有效像素范围内）。
 */
public final class CoordinateMapper {

    private CoordinateMapper() {
    }

    /** 裁剪到 [0.0, 1.0]，NaN 按 0 处理 */
    public static double clamp(double value) {
        if (Double.isNaN(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }

    /**
     * 归一化值（0~1）→ 像素坐标。
     *
     * @param normalized 归一化坐标（0~1，越界值裁剪到边界）
     * @param screenSize 屏幕宽度或高度（像素；&lt;=0 时返回 0）
     * @return 像素坐标，范围 [0, screenSize-1]
     */
    public static int toPixel(double normalized, int screenSize) {
        if (screenSize <= 0) {
            return 0;
        }
        int pixel = (int) (clamp(normalized) * screenSize);
        return Math.min(screenSize - 1, Math.max(0, pixel));
    }
}
