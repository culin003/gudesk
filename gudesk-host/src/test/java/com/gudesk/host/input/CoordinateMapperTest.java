package com.gudesk.host.input;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link CoordinateMapper} 单测：归一化→像素换算、clamp 边界、常见分辨率。
 */
class CoordinateMapperTest {

    @Test
    void clamp_边界裁剪() {
        assertEquals(0.0, CoordinateMapper.clamp(0.0));
        assertEquals(1.0, CoordinateMapper.clamp(1.0));
        assertEquals(0.5, CoordinateMapper.clamp(0.5));
        assertEquals(0.0, CoordinateMapper.clamp(-0.01));
        assertEquals(0.0, CoordinateMapper.clamp(-1.0));
        assertEquals(1.0, CoordinateMapper.clamp(1.01));
        assertEquals(1.0, CoordinateMapper.clamp(2.0));
        assertEquals(0.0, CoordinateMapper.clamp(Double.NaN));
    }

    @Test
    void 归一化转像素_常见分辨率() {
        // 1920×1080
        assertEquals(0, CoordinateMapper.toPixel(0.0, 1920));
        assertEquals(960, CoordinateMapper.toPixel(0.5, 1920));
        assertEquals(540, CoordinateMapper.toPixel(0.5, 1080));
        // 2560×1440
        assertEquals(1280, CoordinateMapper.toPixel(0.5, 2560));
        assertEquals(720, CoordinateMapper.toPixel(0.5, 1440));
        // 3840×2160（4K）
        assertEquals(1920, CoordinateMapper.toPixel(0.5, 3840));
        assertEquals(540, CoordinateMapper.toPixel(0.25, 2160));
        assertEquals(1620, CoordinateMapper.toPixel(0.75, 2160));
        // 1280×720
        assertEquals(640, CoordinateMapper.toPixel(0.5, 1280));
        assertEquals(360, CoordinateMapper.toPixel(0.5, 720));
    }

    @Test
    void 归一化转像素_越界裁剪到屏幕范围内() {
        // 负值/超 1 裁剪后落在 [0, screenSize-1]
        assertEquals(0, CoordinateMapper.toPixel(-0.5, 1920));
        assertEquals(0, CoordinateMapper.toPixel(-1.0, 1080));
        assertEquals(1919, CoordinateMapper.toPixel(1.0, 1920));
        assertEquals(1919, CoordinateMapper.toPixel(1.5, 1920));
        assertEquals(1079, CoordinateMapper.toPixel(2.0, 1080));
    }

    @Test
    void 归一化转像素_截断语义() {
        // 乘积非整数时截断（不四舍五入），与 (int)(n * size) 一致
        assertEquals(999, CoordinateMapper.toPixel(0.333333, 3000));
        assertEquals(333, CoordinateMapper.toPixel(0.3334, 1000));
        assertEquals(0, CoordinateMapper.toPixel(0.0001, 8192));
    }

    @Test
    void 归一化转像素_异常输入() {
        assertEquals(0, CoordinateMapper.toPixel(Double.NaN, 1920));
        assertEquals(0, CoordinateMapper.toPixel(0.5, 0));
        assertEquals(0, CoordinateMapper.toPixel(0.5, -100));
    }
}
