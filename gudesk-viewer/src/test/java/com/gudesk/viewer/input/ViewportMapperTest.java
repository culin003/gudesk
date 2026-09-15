package com.gudesk.viewer.input;

import com.gudesk.viewer.input.ViewportMapper.Box;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ViewportMapper} 单测：letterbox 计算、正反坐标换算、宽高比不一致、
 * 画布外坐标裁剪与边界情况。
 */
class ViewportMapperTest {

    // ------------------------------------------------------------------
    // letterbox 计算
    // ------------------------------------------------------------------

    @Test
    void 相同宽高比_铺满整个表面() {
        // 16:9 视频在 16:9 表面 → 无黑边
        Box box = ViewportMapper.letterbox(1920, 1080, 1280, 720);
        assertEquals(0, box.x(), 1e-9);
        assertEquals(0, box.y(), 1e-9);
        assertEquals(1280, box.width(), 1e-9);
        assertEquals(720, box.height(), 1e-9);
    }

    @Test
    void 宽视频窄表面_上下黑边_水平居中() {
        // 16:9 视频在 4:3 表面（640x480）：高受限 → 上下黑边
        Box box = ViewportMapper.letterbox(640, 360, 640, 480);
        assertEquals(640, box.width(), 1e-9);
        assertEquals(360, box.height(), 1e-9);
        assertEquals(0, box.x(), 1e-9);
        assertEquals(60, box.y(), 1e-9); // (480-360)/2
    }

    @Test
    void 高视频宽表面_左右黑边_垂直居中() {
        // 4:3 视频在 16:9 表面（800x450）：高受限（scale=1.5）→ 左右黑边各 100
        Box box = ViewportMapper.letterbox(400, 300, 800, 450);
        assertEquals(600, box.width(), 1e-9);   // 400*1.5
        assertEquals(450, box.height(), 1e-9);  // 铺满表面高
        assertEquals(100, box.x(), 1e-9);       // (800-600)/2
        assertEquals(0, box.y(), 1e-9);
    }

    @Test
    void 视频小于表面_放大铺满较窄方向() {
        // 320x240 在 960x720（同比 3x 放大）
        Box box = ViewportMapper.letterbox(320, 240, 960, 720);
        assertEquals(960, box.width(), 1e-9);
        assertEquals(720, box.height(), 1e-9);
        assertEquals(0, box.x(), 1e-9);
        assertEquals(0, box.y(), 1e-9);
    }

    @Test
    void 非法输入_返回无效区域() {
        assertEquals(Box.INVALID, ViewportMapper.letterbox(0, 100, 100, 100));
        assertEquals(Box.INVALID, ViewportMapper.letterbox(100, 0, 100, 100));
        assertEquals(Box.INVALID, ViewportMapper.letterbox(100, 100, 0, 100));
        assertEquals(Box.INVALID, ViewportMapper.letterbox(100, 100, 100, -5));
        assertTrue(Box.INVALID.isInvalid());
    }

    // ------------------------------------------------------------------
    // 画布坐标 → 归一化坐标
    // ------------------------------------------------------------------

    @Test
    void 画布中心_映射到归一化中心() {
        Box box = ViewportMapper.letterbox(1920, 1080, 1280, 720);
        double[] n = ViewportMapper.toNormalized(640, 360, box);
        assertEquals(0.5, n[0], 1e-9);
        assertEquals(0.5, n[1], 1e-9);
    }

    @Test
    void letterbox区域四角_映射到归一化角点() {
        // 16:9 视频在 4:3 表面：box = (0,60,640,360)
        Box box = ViewportMapper.letterbox(640, 360, 640, 480);
        // 左上角
        double[] tl = ViewportMapper.toNormalized(0, 60, box);
        assertEquals(0, tl[0], 1e-9);
        assertEquals(0, tl[1], 1e-9);
        // 右下角
        double[] br = ViewportMapper.toNormalized(640, 420, box);
        assertEquals(1, br[0], 1e-9);
        assertEquals(1, br[1], 1e-9);
    }

    @Test
    void 画布外坐标_裁剪到归一化边界() {
        Box box = ViewportMapper.letterbox(640, 360, 640, 480); // box=(0,60,640,360)
        // 黑边区域（上方 y=0 < box.y=60）→ ny 裁剪到 0
        double[] top = ViewportMapper.toNormalized(320, 0, box);
        assertEquals(0.5, top[0], 1e-9);
        assertEquals(0, top[1], 1e-9);
        // 黑边区域（下方 y=480）→ ny 裁剪到 1
        double[] bottom = ViewportMapper.toNormalized(320, 480, box);
        assertEquals(0.5, bottom[0], 1e-9);
        assertEquals(1, bottom[1], 1e-9);
        // 画布外负坐标 → 全部裁剪到 0
        double[] neg = ViewportMapper.toNormalized(-50, -50, box);
        assertEquals(0, neg[0], 1e-9);
        assertEquals(0, neg[1], 1e-9);
        // 画布外超界 → 全部裁剪到 1
        double[] over = ViewportMapper.toNormalized(9999, 9999, box);
        assertEquals(1, over[0], 1e-9);
        assertEquals(1, over[1], 1e-9);
    }

    @Test
    void 无效区域_归一化返回原点() {
        double[] n = ViewportMapper.toNormalized(100, 100, Box.INVALID);
        assertEquals(0, n[0], 1e-9);
        assertEquals(0, n[1], 1e-9);
        double[] n2 = ViewportMapper.toNormalized(100, 100, null);
        assertEquals(0, n2[0], 1e-9);
        assertEquals(0, n2[1], 1e-9);
    }

    // ------------------------------------------------------------------
    // 归一化坐标 → 画布坐标（反向）
    // ------------------------------------------------------------------

    @Test
    void 归一化角点_映射到letterbox角点() {
        Box box = ViewportMapper.letterbox(640, 360, 640, 480); // box=(0,60,640,360)
        double[] tl = ViewportMapper.toCanvas(0, 0, box);
        assertEquals(0, tl[0], 1e-9);
        assertEquals(60, tl[1], 1e-9);
        double[] br = ViewportMapper.toCanvas(1, 1, box);
        assertEquals(640, br[0], 1e-9);
        assertEquals(420, br[1], 1e-9);
        double[] center = ViewportMapper.toCanvas(0.5, 0.5, box);
        assertEquals(320, center[0], 1e-9);
        assertEquals(240, center[1], 1e-9);
    }

    @Test
    void 超范围归一化输入_先裁剪再换算() {
        Box box = ViewportMapper.letterbox(640, 360, 640, 480);
        double[] over = ViewportMapper.toCanvas(1.5, -0.5, box);
        assertEquals(640, over[0], 1e-9); // nx=1.5 → 裁剪 1
        assertEquals(60, over[1], 1e-9);  // ny=-0.5 → 裁剪 0
    }

    @Test
    void 无效区域_画布坐标返回原点() {
        double[] c = ViewportMapper.toCanvas(0.5, 0.5, Box.INVALID);
        assertEquals(0, c[0], 1e-9);
        assertEquals(0, c[1], 1e-9);
    }

    // ------------------------------------------------------------------
    // 正反换算往返一致性
    // ------------------------------------------------------------------

    @Test
    void 正反换算_往返一致() {
        // 对若干画布坐标点：toNormalized → toCanvas 应还原（letterbox 区域内）
        Box box = ViewportMapper.letterbox(1920, 1080, 1280, 720);
        double[][] points = {
                {0, 0}, {640, 360}, {1280, 720}, {100, 200}, {1000, 600}, {320, 180}
        };
        for (double[] p : points) {
            double[] n = ViewportMapper.toNormalized(p[0], p[1], box);
            double[] back = ViewportMapper.toCanvas(n[0], n[1], box);
            assertEquals(p[0], back[0], 1e-6, "x 往返不一致: " + p[0]);
            assertEquals(p[1], back[1], 1e-6, "y 往返不一致: " + p[1]);
        }
    }

    @Test
    void 反正换算_归一化坐标还原() {
        Box box = ViewportMapper.letterbox(640, 360, 640, 480); // 含黑边
        double[][] normalized = {{0, 0}, {0.25, 0.25}, {0.5, 0.5}, {0.75, 0.9}, {1, 1}};
        for (double[] n : normalized) {
            double[] c = ViewportMapper.toCanvas(n[0], n[1], box);
            double[] back = ViewportMapper.toNormalized(c[0], c[1], box);
            assertEquals(n[0], back[0], 1e-9, "nx 往返不一致: " + n[0]);
            assertEquals(n[1], back[1], 1e-9, "ny 往返不一致: " + n[1]);
        }
    }

    // ------------------------------------------------------------------
    // Box 工具
    // ------------------------------------------------------------------

    @Test
    void box包含判断_含边界() {
        Box box = new Box(10, 20, 100, 50);
        assertTrue(box.contains(10, 20));
        assertTrue(box.contains(110, 70));
        assertTrue(box.contains(60, 45));
        assertFalse(box.contains(9.99, 45));
        assertFalse(box.contains(110.01, 45));
        assertFalse(box.contains(60, 19.99));
    }
}
