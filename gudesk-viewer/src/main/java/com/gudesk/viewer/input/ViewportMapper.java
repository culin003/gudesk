package com.gudesk.viewer.input;

/**
 * 视口映射工具：视频画面在渲染表面上的 letterbox（等比缩放居中、黑边填充）
 * 区域计算，以及画布坐标 ↔ 归一化视频坐标（0~1）的正反换算。
 *
 * <p>纯逻辑、无 JavaFX 依赖，可独立单测。坐标约定：
 * <ul>
 *   <li>letterbox 区域 {@link Box}：视频完整可见、等比缩放后的绘制矩形（表面坐标系）；</li>
 *   <li>归一化坐标：相对视频画面左上角的 0~1 比例坐标，落点在 letterbox 外时
 *       裁剪到 [0,1] 边界（被控端注入时自然钳制到屏幕内）。</li>
 * </ul>
 */
public final class ViewportMapper {

    private ViewportMapper() {
    }

    /**
     * 计算视频画面在渲染表面上的 letterbox 绘制区域。
     *
     * <p>等比缩放（scale = min(surfaceW/videoW, surfaceH/videoH)）并居中；
     * 宽高比不一致时较短方向两侧留黑边。
     *
     * @param videoWidth  视频宽（像素，&gt;0 有效）
     * @param videoHeight 视频高（像素，&gt;0 有效）
     * @param surfaceWidth  渲染表面宽（&gt;0 有效）
     * @param surfaceHeight 渲染表面高（&gt;0 有效）
     * @return letterbox 区域；任一输入非正时返回 {@link Box#INVALID}
     */
    public static Box letterbox(double videoWidth, double videoHeight,
                                double surfaceWidth, double surfaceHeight) {
        if (videoWidth <= 0 || videoHeight <= 0 || surfaceWidth <= 0 || surfaceHeight <= 0) {
            return Box.INVALID;
        }
        double scale = Math.min(surfaceWidth / videoWidth, surfaceHeight / videoHeight);
        double drawWidth = videoWidth * scale;
        double drawHeight = videoHeight * scale;
        double x = (surfaceWidth - drawWidth) / 2;
        double y = (surfaceHeight - drawHeight) / 2;
        return new Box(x, y, drawWidth, drawHeight);
    }

    /**
     * 画布坐标 → 归一化视频坐标（0~1）。
     *
     * <p>落点在 letterbox 区域外（黑边/画布外）时裁剪到 [0,1]；
     * 区域无效（宽或高为 0）时返回 (0,0)。
     *
     * @return 长度 2 数组 [nx, ny]
     */
    public static double[] toNormalized(double canvasX, double canvasY, Box box) {
        if (box == null || box.isInvalid()) {
            return new double[]{0, 0};
        }
        double nx = box.width() <= 0 ? 0 : (canvasX - box.x()) / box.width();
        double ny = box.height() <= 0 ? 0 : (canvasY - box.y()) / box.height();
        return new double[]{clamp01(nx), clamp01(ny)};
    }

    /**
     * 归一化视频坐标（0~1）→ 画布坐标。
     *
     * <p>输入超范围时先裁剪到 [0,1]（反向换算保证结果落在 letterbox 区域内）；
     * 区域无效时返回 (0,0)。
     *
     * @return 长度 2 数组 [canvasX, canvasY]
     */
    public static double[] toCanvas(double nx, double ny, Box box) {
        if (box == null || box.isInvalid()) {
            return new double[]{0, 0};
        }
        double x = box.x() + clamp01(nx) * box.width();
        double y = box.y() + clamp01(ny) * box.height();
        return new double[]{x, y};
    }

    private static double clamp01(double v) {
        return v < 0 ? 0 : Math.min(v, 1);
    }

    /**
     * 矩形区域（渲染表面坐标系）。
     */
    public record Box(double x, double y, double width, double height) {

        /** 无效区域（视频尺寸未知/表面为空时使用） */
        public static final Box INVALID = new Box(0, 0, 0, 0);

        public boolean isInvalid() {
            return width <= 0 || height <= 0;
        }

        /** 判断点是否在区域内（含边界） */
        public boolean contains(double px, double py) {
            return px >= x && px <= x + width && py >= y && py <= y + height;
        }
    }
}
