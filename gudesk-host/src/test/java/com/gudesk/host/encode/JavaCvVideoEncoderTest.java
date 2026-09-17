package com.gudesk.host.encode;

import com.gudesk.common.spi.AdapterConfig;
import com.gudesk.common.spi.AdapterException;
import com.gudesk.common.spi.NativeFrame;
import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.javacpp.Loader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@link JavaCvVideoEncoder} 单测：合成 BGRA 帧编码往返（30 帧渐变色）、关键帧请求、
 * extradata AVCC→Annex-B 转换。javacv/ffmpeg 原生库加载失败时按 assume 优雅跳过。
 */
class JavaCvVideoEncoderTest {

    private static final int WIDTH = 320;
    private static final int HEIGHT = 240;
    private static final int FPS = 30;

    private static final String NATIVES_UNAVAILABLE_REASON = nativesCheck();

    private JavaCvVideoEncoder encoder;

    @BeforeEach
    void setUp() {
        assumeTrue(NATIVES_UNAVAILABLE_REASON == null, NATIVES_UNAVAILABLE_REASON);
        encoder = new JavaCvVideoEncoder();
    }

    @AfterEach
    void tearDown() {
        if (encoder != null) {
            encoder.close();
        }
    }

    private static String nativesCheck() {
        try {
            Loader.load(avcodec.class);
            if (avcodec.avcodec_find_encoder_by_name(JavaCvVideoEncoder.ENCODER_NAME) == null) {
                return "libx264 编码器不可用（ffmpeg 原生库缺少 GPL 构建），跳过编码往返测试";
            }
            return null;
        } catch (Throwable t) {
            return "javacv/ffmpeg 原生库加载失败，跳过编码往返测试: " + t;
        }
    }

    /** 构造一帧渐变色 BGRA（direct ByteBuffer），colorPhase 随帧号变化保证画面在变 */
    private static NativeFrame syntheticFrame(int index) {
        ByteBuffer buffer = ByteBuffer.allocateDirect(WIDTH * HEIGHT * 4);
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                int b = (x * 255 / WIDTH + index * 7) & 0xFF;
                int g = (y * 255 / HEIGHT + index * 3) & 0xFF;
                int r = ((x + y + index * 11) * 255 / (WIDTH + HEIGHT)) & 0xFF;
                buffer.put((byte) b).put((byte) g).put((byte) r).put((byte) 0xFF);
            }
        }
        buffer.flip();
        return new NativeFrame(buffer, WIDTH, HEIGHT, "BGRA", System.nanoTime());
    }

    @Test
    void 编码往返_30帧渐变色_输出非空且首帧为关键帧() {
        encoder.init(config(WIDTH, HEIGHT));
        encoder.start();
        List<NativeFrame> outputs = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            NativeFrame input = syntheticFrame(i);
            encoder.encode(input, outputs::add);
            input.close();
        }
        encoder.stop();

        assertFalse(outputs.isEmpty(), "编码输出不应为空");
        // 每个输出帧均为 Annex-B H264
        for (NativeFrame f : outputs) {
            assertEquals("H264", f.pixelFormat());
            assertTrue(f.buffer().remaining() > 0);
        }
        NativeFrame first = outputs.get(0);
        ByteBuffer data = first.buffer();
        assertEquals(0, data.get(0), "首帧应以 Annex-B 起始码 0x00000001 开头");
        assertEquals(0, data.get(1));
        assertEquals(0, data.get(2));
        assertEquals(1, data.get(3));
        assertTrue(JavaCvVideoEncoder.containsSps(data), "首帧应含 SPS/PPS（关键帧标志）");
        System.out.printf("[编码往返] 输入 30 帧, 输出 %d 帧, 首帧 %d B (关键帧), 总输出 %d B%n",
                outputs.size(), data.remaining(), outputs.stream().mapToLong(f -> f.buffer().remaining()).sum());
        outputs.forEach(NativeFrame::close);
    }

    @Test
    void 请求关键帧_后续输出帧包含SPS() {
        encoder.init(config(WIDTH, HEIGHT));
        encoder.start();
        // gop 默认 60，30 帧内不会自然出现第二个关键帧
        List<NativeFrame> outputs = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            NativeFrame input = syntheticFrame(i);
            encoder.encode(input, outputs::add);
            input.close();
        }
        encoder.requestKeyframe();
        for (int i = 5; i < 10; i++) {
            NativeFrame input = syntheticFrame(i);
            encoder.encode(input, outputs::add);
            input.close();
        }
        encoder.stop();

        assertTrue(outputs.size() > 5, "应有输出帧");
        // 请求后（跳过编码器内部可能的 1 帧缓冲）应出现含 SPS 的关键帧
        boolean keyframeAfterRequest = outputs.subList(5, outputs.size()).stream()
                .anyMatch(f -> JavaCvVideoEncoder.containsSps(f.buffer()));
        assertTrue(keyframeAfterRequest, "requestKeyframe 后应输出含 SPS/PPS 的关键帧");
        outputs.forEach(NativeFrame::close);
    }

    @Test
    void 输入格式不匹配抛出异常_尺寸变化自适应() {
        encoder.init(config(WIDTH, HEIGHT));
        encoder.start();
        // 格式不匹配 → 抛异常
        NativeFrame wrongFormat = new NativeFrame(
                ByteBuffer.allocateDirect(WIDTH * HEIGHT * 4), WIDTH, HEIGHT, "I420", 0L);
        assertThrows(AdapterException.class, () -> encoder.encode(wrongFormat, f -> { }));
        wrongFormat.close();
        // 尺寸变化 → 自适应重建，正常编码（不再抛异常）
        List<NativeFrame> outputs = new ArrayList<>();
        NativeFrame smaller = new NativeFrame(
                ByteBuffer.allocateDirect(64 * 64 * 4), 64, 64, "BGRA", 0L);
        assertDoesNotThrow(() -> encoder.encode(smaller, outputs::add));
        smaller.close();
        assertFalse(outputs.isEmpty(), "尺寸自适应后应正常输出编码帧");
        outputs.forEach(NativeFrame::close);
    }

    @Test
    void 超出最大编码宽度时等比缩放输出() {
        AdapterConfig scaled = AdapterConfig.builder()
                .width(WIDTH).height(HEIGHT).fps(FPS)
                .pixelFormat("BGRA")
                .putExtra("bitrate", "1000000")
                .putExtra("max-encode-width", "160")
                .build();
        encoder.init(scaled);
        encoder.start();
        List<NativeFrame> outputs = new ArrayList<>();
        NativeFrame input = syntheticFrame(0); // 320x240
        encoder.encode(input, outputs::add);
        input.close();
        encoder.stop();
        assertFalse(outputs.isEmpty(), "应输出编码帧");
        // 320x240 等比缩放到最大宽 160 → 160x120
        NativeFrame first = outputs.get(0);
        assertEquals(160, first.width());
        assertEquals(120, first.height());
        outputs.forEach(NativeFrame::close);
    }

    @Test
    void extradata转换_AVCC转AnnexB() {
        // 构造最小 AVCC: version=1, profile=0x42, compat=0, level=0x1e, lenSize=0xFF,
        // 1 个 SPS(len=2, 0x67 0x42), 1 个 PPS(len=2, 0x68 0xC9)
        byte[] avcc = {
                0x01, 0x42, 0x00, 0x1E, (byte) 0xFF, (byte) 0xE1,
                0x00, 0x02, 0x67, 0x42,
                0x01,
                0x00, 0x02, 0x68, (byte) 0xC9
        };
        byte[] annexB = JavaCvVideoEncoder.extradataToAnnexB(avcc);
        // 期望: 00000001 67 42 00000001 68 C9
        byte[] expected = {
                0, 0, 0, 1, 0x67, 0x42,
                0, 0, 0, 1, 0x68, (byte) 0xC9
        };
        assertArrayEquals(expected, annexB);

        // 已是 Annex-B 的输入原样返回
        byte[] already = {0, 0, 0, 1, 0x67, 0x42};
        assertArrayEquals(already, JavaCvVideoEncoder.extradataToAnnexB(already));
        // 空输入返回空
        assertEquals(0, JavaCvVideoEncoder.extradataToAnnexB(new byte[0]).length);
    }

    @Test
    void containsSps检测起始码与NAL类型() {
        // SPS NAL (type=7)
        ByteBuffer withSps = ByteBuffer.wrap(new byte[]{0, 0, 0, 1, 0x67, 0x42, 0, 0, 0, 1, 0x21});
        assertTrue(JavaCvVideoEncoder.containsSps(withSps));
        // PPS NAL (type=8)
        ByteBuffer withPps = ByteBuffer.wrap(new byte[]{0, 0, 0, 1, 0x68, (byte) 0xC9});
        assertTrue(JavaCvVideoEncoder.containsSps(withPps));
        // 非 SPS/PPS
        ByteBuffer slice = ByteBuffer.wrap(new byte[]{0, 0, 0, 1, 0x41, 0x1A});
        assertFalse(JavaCvVideoEncoder.containsSps(slice));
        // 无起始码
        assertFalse(JavaCvVideoEncoder.containsSps(ByteBuffer.wrap(new byte[]{0x67, 0x42})));
    }

    @Test
    void 能力描述_软编码BGRA输入H264输出() {
        assertNotNull(encoder.capabilities());
        assertFalse(encoder.capabilities().isHardwareAccelerated());
        assertEquals(List.of("BGRA", "H264"), encoder.capabilities().supportedPixelFormats());
        assertEquals(60, encoder.capabilities().maxFps());
    }

    private static AdapterConfig config(int width, int height) {
        return AdapterConfig.builder()
                .width(width).height(height).fps(FPS)
                .pixelFormat("BGRA")
                .putExtra("bitrate", "1000000")
                .build();
    }
}
