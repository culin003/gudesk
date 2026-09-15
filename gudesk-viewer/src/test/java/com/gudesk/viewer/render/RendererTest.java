package com.gudesk.viewer.render;

import com.gudesk.common.spi.AdapterConfig;
import com.gudesk.common.spi.AdapterException;
import com.gudesk.common.spi.NativeFrame;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.awt.GraphicsEnvironment;
import java.nio.ByteBuffer;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 渲染相关测试：WritableImage BGRA 像素写入正确性（{@code PixelFormat.getByteBgraInstance()}
 * 字节序验证）与 {@link JavaFxFrameRenderer} 生命周期/防御逻辑。
 * 无头环境（headless 或 FX 图像 API 不可用）assume 优雅跳过。
 */
class RendererTest {

    private static final int W = 8;
    private static final int H = 6;

    private static final String UNAVAILABLE_REASON = availabilityCheck();

    private JavaFxFrameRenderer renderer;

    @BeforeEach
    void setUp() {
        assumeTrue(UNAVAILABLE_REASON == null, UNAVAILABLE_REASON);
        renderer = JavaFxFrameRenderer.defaultRenderer();
    }

    @AfterEach
    void tearDown() {
        if (renderer != null) {
            renderer.close();
        }
    }

    /** 无头 / FX 图像 API 不可用检测 */
    private static String availabilityCheck() {
        if (GraphicsEnvironment.isHeadless()) {
            return "无图形环境（headless），跳过渲染测试";
        }
        try {
            new WritableImage(2, 2);
            return null;
        } catch (Throwable t) {
            return "JavaFX 图像 API 不可用，跳过渲染测试: " + t;
        }
    }

    // ------------------------------------------------------------------
    // WritableImage 像素写入正确性
    // ------------------------------------------------------------------

    @Test
    void WritableImage像素写入_BGRA字节序正确() {
        WritableImage image = new WritableImage(W, H);
        // 整幅同色 B=0x11 G=0x22 R=0x33 A=0xFF → getArgb 应为 0xFF332211
        ByteBuffer bgra = ByteBuffer.allocateDirect(W * H * 4);
        for (int i = 0; i < W * H; i++) {
            bgra.put((byte) 0x11).put((byte) 0x22).put((byte) 0x33).put((byte) 0xFF);
        }
        bgra.flip();
        image.getPixelWriter().setPixels(0, 0, W, H,
                PixelFormat.getByteBgraInstance(), bgra, W * 4);
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                assertEquals(0xFF332211, image.getPixelReader().getArgb(x, y),
                        "像素 (" + x + "," + y + ") BGRA→ARGB 转换错误");
            }
        }
    }

    @Test
    void WritableImage像素写入_渐变颜色行正确() {
        WritableImage image = new WritableImage(W, H);
        // 每像素一个颜色（B=x*16, G=y*32, R=0x80, A=0xFF）
        ByteBuffer bgra = ByteBuffer.allocateDirect(W * H * 4);
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                bgra.put((byte) (x * 16)).put((byte) (y * 32)).put((byte) 0x80).put((byte) 0xFF);
            }
        }
        bgra.flip();
        image.getPixelWriter().setPixels(0, 0, W, H,
                PixelFormat.getByteBgraInstance(), bgra, W * 4);
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int expected = 0xFF800000 | ((y * 32) << 8) | (x * 16);
                assertEquals(expected, image.getPixelReader().getArgb(x, y),
                        "像素 (" + x + "," + y + ") 错误");
            }
        }
    }

    // ------------------------------------------------------------------
    // JavaFxFrameRenderer 生命周期与防御逻辑（不依赖 FX 线程/画布）
    // ------------------------------------------------------------------

    @Test
    void 画布未绑定_渲染丢弃帧不抛异常() throws AdapterException {
        renderer.init(config());
        renderer.start();
        // canvas == null：warn 丢弃，不抛异常（SPI 无参构造实例的防御行为）
        NativeFrame frame = bgraFrame();
        assertDoesNotThrow(() -> renderer.render(frame));
        assertEquals(1, renderer.droppedCount());
        assertEquals(0, renderer.renderedCount());
        renderer.stop();
    }

    @Test
    void 渲染器未启动_渲染抛异常() throws AdapterException {
        renderer.init(config());
        NativeFrame frame = bgraFrame();
        assertThrows(AdapterException.class, () -> renderer.render(frame));
        frame.close();
    }

    @Test
    void 输入格式不匹配_渲染抛异常() throws AdapterException {
        renderer.init(config());
        renderer.start();
        NativeFrame wrong = new NativeFrame(
                ByteBuffer.allocateDirect(W * H * 4), W, H, "I420", 0L);
        assertThrows(AdapterException.class, () -> renderer.render(wrong));
        wrong.close();
        renderer.stop();
    }

    @Test
    void 能力描述_120fpsBGRA输入() {
        assertNotNull(renderer.capabilities());
        assertEquals(120, renderer.capabilities().maxFps());
        assertEquals(List.of("BGRA"), renderer.capabilities().supportedPixelFormats());
        assertTrue(renderer.capabilities().maxWidth() >= 1920);
        assertTrue(renderer.capabilities().maxHeight() >= 1080);
    }

    @Test
    void 表面尺寸更新_不抛异常() throws AdapterException {
        renderer.init(config());
        renderer.setSurfaceSize(1280, 720);
        renderer.setSurfaceSize(1920, 1080);
    }

    private static AdapterConfig config() {
        return AdapterConfig.builder()
                .width(W).height(H).fps(30)
                .pixelFormat("BGRA")
                .build();
    }

    private static NativeFrame bgraFrame() {
        ByteBuffer buffer = ByteBuffer.allocateDirect(W * H * 4);
        for (int i = 0; i < W * H; i++) {
            buffer.put((byte) 0x44).put((byte) 0x55).put((byte) 0x66).put((byte) 0xFF);
        }
        buffer.flip();
        return new NativeFrame(buffer, W, H, "BGRA", System.nanoTime());
    }
}
