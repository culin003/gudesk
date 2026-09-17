package com.gudesk.viewer.render;

import com.gudesk.common.spi.AdapterCapabilities;
import com.gudesk.common.spi.AdapterConfig;
import com.gudesk.common.spi.AdapterException;
import com.gudesk.common.spi.FrameRenderer;
import com.gudesk.common.spi.NativeFrame;
import com.gudesk.viewer.input.ViewportMapper;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.Objects;

/**
 * 基于 JavaFX Canvas 的帧渲染默认实现：BGRA 帧 →
 * {@code WritableImage.pixelWriter.setPixels(PixelFormat.getByteBgraInstance())} →
 * {@code GraphicsContext.drawImage} 到 Canvas，保持宽高比 letterbox 居中绘制（黑边填充）。
 *
 * <p><b>线程约束</b>：{@link #render(NativeFrame)} 必须在 JavaFX Application Thread
 * 调用（内部不做 {@code Platform.runLater}，由调用方保证）；防御策略：检测到非
 * FX 线程调用时 log warn 并丢弃该帧（不抛异常、不阻塞），避免会话线程被 UI 卡顿反压。
 *
 * <p>画布注入：优先构造器注入 {@code new JavaFxFrameRenderer(canvas)}；
 * 经 SPI/无参构造创建的实例未绑定画布时，render 同样 warn 丢弃帧。
 * {@code WritableImage} 按视频尺寸懒创建（首帧/分辨率变化时重建），避免提前
 * 初始化 JavaFX toolkit 依赖。
 */
public class JavaFxFrameRenderer implements FrameRenderer {

    private static final Logger LOG = LoggerFactory.getLogger(JavaFxFrameRenderer.class);

    /** 输入像素格式 */
    public static final String PIXEL_FORMAT = "BGRA";
    /** 能力上限 */
    public static final int MAX_WIDTH = 3840;
    public static final int MAX_HEIGHT = 2160;
    public static final int MAX_FPS = 120;

    /** 渲染目标画布（可为 null：未绑定画布时帧被丢弃） */
    private final Canvas canvas;
    /** 按视频尺寸懒创建的中间图像（BGRA 直写，再按 letterbox 绘制到画布） */
    private WritableImage image;
    private int videoWidth;
    private int videoHeight;
    private int surfaceWidth;
    private int surfaceHeight;
    /** 上次黑边填充时的画布/视频尺寸（尺寸不变时跳过整幅黑边填充） */
    private int lastFillCanvasW = -1;
    private int lastFillCanvasH = -1;
    private int lastFillVideoW = -1;
    private int lastFillVideoH = -1;
    private volatile boolean started;
    private long renderedCount;
    private long droppedCount;

    /** SPI 实例化用无参构造（画布未绑定，render 丢弃帧） */
    public JavaFxFrameRenderer() {
        this(null);
    }

    /** 构造器注入渲染目标画布 */
    public JavaFxFrameRenderer(Canvas canvas) {
        this.canvas = canvas;
        if (canvas != null) {
            // 画布尺寸变化（窗口缩放）时用最近一帧重绘，避免缩小/放大后画面不跟随
            canvas.widthProperty().addListener((obs, o, n) -> redraw());
            canvas.heightProperty().addListener((obs, o, n) -> redraw());
        }
    }

    /**
     * 默认降级实现工厂，供
     * {@code SpiLoader.load(FrameRenderer.class, "renderer", JavaFxFrameRenderer::defaultRenderer)}
     * 使用。
     */
    public static JavaFxFrameRenderer defaultRenderer() {
        return new JavaFxFrameRenderer();
    }

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    @Override
    public void init(AdapterConfig config) throws AdapterException {
        Objects.requireNonNull(config, "config");
        this.videoWidth = config.width();
        this.videoHeight = config.height();
        this.surfaceWidth = config.width();
        this.surfaceHeight = config.height();
        this.image = null;
        this.renderedCount = 0;
        this.droppedCount = 0;
        LOG.info("JavaFxFrameRenderer 初始化完成: {} (视频 {}x{}, canvas={})",
                PIXEL_FORMAT, videoWidth, videoHeight, canvas != null ? "已绑定" : "未绑定");
    }

    @Override
    public void start() throws AdapterException {
        if (videoWidth <= 0 || videoHeight <= 0) {
            throw new AdapterException("渲染尺寸非法: " + videoWidth + "x" + videoHeight);
        }
        started = true;
    }

    @Override
    public void stop() throws AdapterException {
        started = false;
    }

    @Override
    public void release() {
        image = null;
    }

    @Override
    public AdapterCapabilities capabilities() {
        return AdapterCapabilities.builder()
                .maxWidth(MAX_WIDTH)
                .maxHeight(MAX_HEIGHT)
                .maxFps(MAX_FPS)
                .hardwareAccelerated(true)
                .addSupportedPixelFormat(PIXEL_FORMAT)
                .build();
    }

    // ------------------------------------------------------------------
    // 渲染
    // ------------------------------------------------------------------

    @Override
    public void setSurfaceSize(int width, int height) {
        this.surfaceWidth = width;
        this.surfaceHeight = height;
    }

    @Override
    public void render(NativeFrame frame) throws AdapterException {
        Objects.requireNonNull(frame, "frame");
        if (!started) {
            throw new AdapterException("渲染器未启动");
        }
        if (!PIXEL_FORMAT.equalsIgnoreCase(frame.pixelFormat())) {
            throw new AdapterException("不支持的输入像素格式: " + frame.pixelFormat()
                    + "（仅支持 " + PIXEL_FORMAT + "）");
        }
        if (canvas == null) {
            droppedCount++;
            LOG.warn("渲染画布未绑定，丢弃帧 {}x{}", frame.width(), frame.height());
            return;
        }
        if (!isFxApplicationThread()) {
            droppedCount++;
            LOG.warn("render 在非 JavaFX Application Thread 调用，丢弃帧 {}x{} (线程={})",
                    frame.width(), frame.height(), Thread.currentThread().getName());
            return;
        }
        int width = frame.width();
        int height = frame.height();
        if (width <= 0 || height <= 0) {
            return;
        }
        // 分辨率变化：按新尺寸重建中间图像
        if (image == null || (int) image.getWidth() != width || (int) image.getHeight() != height) {
            image = new WritableImage(width, height);
            videoWidth = width;
            videoHeight = height;
        }
        ByteBuffer bgra = frame.buffer();
        if (bgra.position() != 0) {
            ByteBuffer dup = bgra.duplicate();
            dup.position(0);
            bgra = dup;
        }
        if (bgra.remaining() < (long) width * height * 4) {
            throw new AdapterException("BGRA 帧数据不足: 需要 " + width * height * 4
                    + " 字节, 实际 " + bgra.remaining());
        }
        image.getPixelWriter().setPixels(0, 0, width, height,
                PixelFormat.getByteBgraInstance(), bgra, width * 4);
        drawLetterboxed(canvas.getGraphicsContext2D());
        renderedCount++;
    }

    /** 画布尺寸变化时用缓存的最近一帧重绘（保持窗口缩放时画面实时跟随） */
    private void redraw() {
        if (image == null || canvas == null || !isFxApplicationThread()) {
            return;
        }
        drawLetterboxed(canvas.getGraphicsContext2D());
    }

    /** letterbox 居中绘制：黑边填充整个画布后，将视频按比例绘制到中间区域 */
    private void drawLetterboxed(GraphicsContext gc) {
        double canvasWidth = canvas.getWidth() > 0 ? canvas.getWidth() : surfaceWidth;
        double canvasHeight = canvas.getHeight() > 0 ? canvas.getHeight() : surfaceHeight;
        ViewportMapper.Box box = ViewportMapper.letterbox(videoWidth, videoHeight, canvasWidth, canvasHeight);
        if (box.isInvalid()) {
            return;
        }
        // 黑边只在画布或视频尺寸变化时重填一次（drawImage 会覆盖视频区域，黑边保持不变）
        int cw = (int) canvasWidth;
        int ch = (int) canvasHeight;
        if (cw != lastFillCanvasW || ch != lastFillCanvasH
                || videoWidth != lastFillVideoW || videoHeight != lastFillVideoH) {
            gc.setFill(Color.BLACK);
            gc.fillRect(0, 0, canvasWidth, canvasHeight);
            lastFillCanvasW = cw;
            lastFillCanvasH = ch;
            lastFillVideoW = videoWidth;
            lastFillVideoH = videoHeight;
        }
        gc.drawImage(image, box.x(), box.y(), box.width(), box.height());
    }

    /** FX 线程检查（无 toolkit 环境下 Platform 查询可能抛错，按非 FX 线程处理） */
    private static boolean isFxApplicationThread() {
        try {
            return javafx.application.Platform.isFxApplicationThread();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 累计渲染帧数（诊断用） */
    public long renderedCount() {
        return renderedCount;
    }

    /** 累计丢弃帧数（诊断用） */
    public long droppedCount() {
        return droppedCount;
    }
}
