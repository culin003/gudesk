package com.gudesk.viewer.decode;

import com.gudesk.common.spi.AdapterConfig;
import com.gudesk.common.spi.AdapterException;
import com.gudesk.common.spi.NativeFrame;
import org.bytedeco.ffmpeg.avcodec.AVCodec;
import org.bytedeco.ffmpeg.avcodec.AVCodecContext;
import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avutil.AVDictionary;
import org.bytedeco.ffmpeg.avutil.AVFrame;
import org.bytedeco.ffmpeg.avutil.AVRational;
import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.ffmpeg.global.swscale;
import org.bytedeco.ffmpeg.swscale.SwsContext;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.Loader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@link JavaCvVideoDecoder} 单测：测试内用 bytedeco avcodec/libx264 编码 5 帧
 * 合成 H.264 Annex-B 流（不依赖 host 模块），每个 packet 作为完整 access unit
 * 直接喂给解码器做往返验证——输出帧数量/尺寸/格式与像素内容近似性。
 * javacv/ffmpeg 原生库加载失败时按 assume 优雅跳过。
 */
class JavaCvVideoDecoderTest {

    private static final int WIDTH = 320;
    private static final int HEIGHT = 240;
    private static final int FPS = 30;
    private static final int FRAMES = 5;
    /** 有损编码往返的像素值容差 */
    private static final int PIXEL_TOLERANCE = 32;

    private static final String NATIVES_UNAVAILABLE_REASON = nativesCheck();

    private JavaCvVideoDecoder decoder;

    @BeforeEach
    void setUp() {
        assumeTrue(NATIVES_UNAVAILABLE_REASON == null, NATIVES_UNAVAILABLE_REASON);
        decoder = new JavaCvVideoDecoder();
    }

    @AfterEach
    void tearDown() {
        if (decoder != null) {
            decoder.close();
        }
    }

    private static String nativesCheck() {
        try {
            Loader.load(avcodec.class);
            if (avcodec.avcodec_find_encoder_by_name("libx264") == null) {
                return "libx264 编码器不可用（ffmpeg 原生库缺少 GPL 构建），跳过解码往返测试";
            }
            if (avcodec.avcodec_find_decoder(avcodec.AV_CODEC_ID_H264) == null) {
                return "H.264 解码器不可用，跳过解码往返测试";
            }
            return null;
        } catch (Throwable t) {
            return "javacv/ffmpeg 原生库加载失败，跳过解码往返测试: " + t;
        }
    }

    // ------------------------------------------------------------------
    // 测试内 H.264 编码小工具（bytedeco avcodec API，不依赖 host 模块）
    // ------------------------------------------------------------------

    /** 构造一帧渐变色 BGRA（direct ByteBuffer），index 随帧号变化保证画面在变 */
    private static ByteBuffer syntheticBgra(int index) {
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
        return buffer;
    }

    /** 合成 BGRA 帧中心像素期望值（与 syntheticBgra 公式一致） */
    private static int[] expectedCenterBgr(int index) {
        int x = WIDTH / 2;
        int y = HEIGHT / 2;
        int b = (x * 255 / WIDTH + index * 7) & 0xFF;
        int g = (y * 255 / HEIGHT + index * 3) & 0xFF;
        int r = ((x + y + index * 11) * 255 / (WIDTH + HEIGHT)) & 0xFF;
        return new int[]{b, g, r};
    }

    /** libx264 编码 count 帧渐变色 BGRA → Annex-B H.264 packet 列表（zerolatency：入出一一对应） */
    private static List<byte[]> encodeH264(int count) {
        AVCodec codec = avcodec.avcodec_find_encoder_by_name("libx264");
        assertNotNull(codec, "libx264 编码器不可用");
        AVCodecContext ctx = avcodec.avcodec_alloc_context3(codec);
        ctx.pix_fmt(avutil.AV_PIX_FMT_YUV420P);
        ctx.width(WIDTH);
        ctx.height(HEIGHT);
        ctx.time_base(new AVRational().num(1).den(FPS));
        ctx.framerate(new AVRational().num(FPS).den(1));
        ctx.gop_size(10);
        ctx.max_b_frames(0);
        ctx.bit_rate(1_000_000L);
        AVDictionary options = new AVDictionary();
        List<byte[]> outputs = new ArrayList<>();
        try {
            avutil.av_dict_set(options, "preset", "ultrafast", 0);
            avutil.av_dict_set(options, "tune", "zerolatency", 0);
            int ret = avcodec.avcodec_open2(ctx, null, options);
            assertTrue(ret >= 0, "avcodec_open2 失败: " + ret);
        } finally {
            avutil.av_dict_free(options);
        }
        SwsContext sws = null;
        AVFrame yuvFrame = null;
        AVFrame bgraFrame = null;
        AVPacket pkt = null;
        try {
            sws = swscale.sws_getContext(WIDTH, HEIGHT, avutil.AV_PIX_FMT_BGRA,
                    WIDTH, HEIGHT, avutil.AV_PIX_FMT_YUV420P,
                    swscale.SWS_BILINEAR, null, null, (double[]) null);
            yuvFrame = avutil.av_frame_alloc();
            yuvFrame.format(avutil.AV_PIX_FMT_YUV420P);
            yuvFrame.width(WIDTH);
            yuvFrame.height(HEIGHT);
            assertTrue(avutil.av_frame_get_buffer(yuvFrame, 0) >= 0, "av_frame_get_buffer 失败");
            bgraFrame = avutil.av_frame_alloc();
            bgraFrame.format(avutil.AV_PIX_FMT_BGRA);
            bgraFrame.width(WIDTH);
            bgraFrame.height(HEIGHT);
            pkt = avcodec.av_packet_alloc();
            for (int i = 0; i < count; i++) {
                ByteBuffer bgra = syntheticBgra(i);
                bgraFrame.data(0, new BytePointer(bgra));
                bgraFrame.linesize(0, WIDTH * 4);
                swscale.sws_scale(sws, bgraFrame.data(), bgraFrame.linesize(), 0, HEIGHT,
                        yuvFrame.data(), yuvFrame.linesize());
                yuvFrame.pts(i);
                assertEquals(0, avcodec.avcodec_send_frame(ctx, yuvFrame), "send_frame 失败");
                drainPackets(ctx, pkt, outputs);
            }
            // 冲刷编码器（zerolatency 通常无残留）
            avcodec.avcodec_send_frame(ctx, (AVFrame) null);
            drainPackets(ctx, pkt, outputs);
        } finally {
            if (pkt != null) {
                avcodec.av_packet_free(pkt);
            }
            if (bgraFrame != null) {
                avutil.av_frame_free(bgraFrame);
            }
            if (yuvFrame != null) {
                avutil.av_frame_free(yuvFrame);
            }
            if (sws != null) {
                swscale.sws_freeContext(sws);
            }
            avcodec.avcodec_free_context(ctx);
        }
        return outputs;
    }

    private static void drainPackets(AVCodecContext ctx, AVPacket pkt, List<byte[]> outputs) {
        while (avcodec.avcodec_receive_packet(ctx, pkt) == 0) {
            int size = pkt.size();
            byte[] data = new byte[size];
            pkt.data().position(0).limit(size).get(data);
            outputs.add(data);
            avcodec.av_packet_unref(pkt);
        }
    }

    // ------------------------------------------------------------------
    // 测试
    // ------------------------------------------------------------------

    @Test
    void 解码往返_5帧合成H264_输出BGRA数量尺寸与像素近似() {
        List<byte[]> packets = encodeH264(FRAMES);
        assertEquals(FRAMES, packets.size(), "zerolatency 编码应逐帧输出");
        assertTrue(packets.get(0).length > 4 && packets.get(0)[0] == 0
                        && packets.get(0)[1] == 0 && packets.get(0)[2] == 0 && packets.get(0)[3] == 1,
                "首包应为 Annex-B 起始码开头");

        decoder.init(config());
        decoder.start();
        List<NativeFrame> outputs = new ArrayList<>();
        for (byte[] packet : packets) {
            decodePacket(packet, outputs);
        }
        decoder.stop();
        // 每个 VideoFrame 已是完整 access unit（编码端 avcodec_receive_packet 输出），
        // 直接送解码器：喂 5 包应逐一解码输出 5 帧（无 parser 滞留）
        assertEquals(FRAMES, outputs.size(), "5 个完整 access unit 应解码输出 5 帧");

        for (int i = 0; i < outputs.size(); i++) {
            NativeFrame f = outputs.get(i);
            assertEquals("BGRA", f.pixelFormat(), "帧 " + i + " 格式");
            assertEquals(WIDTH, f.width(), "帧 " + i + " 宽度");
            assertEquals(HEIGHT, f.height(), "帧 " + i + " 高度");
            assertEquals(WIDTH * HEIGHT * 4, f.buffer().remaining(), "帧 " + i + " 数据量");
        }
        // 像素内容近似验证：首帧输出对应首帧编码输入（有损容差内）
        NativeFrame first = outputs.get(0);
        int centerOffset = (HEIGHT / 2 * WIDTH + WIDTH / 2) * 4;
        int actualB = first.buffer().get(centerOffset) & 0xFF;
        int actualG = first.buffer().get(centerOffset + 1) & 0xFF;
        int actualR = first.buffer().get(centerOffset + 2) & 0xFF;
        int[] expected = expectedCenterBgr(0);
        assertTrue(Math.abs(actualB - expected[0]) <= PIXEL_TOLERANCE
                        && Math.abs(actualG - expected[1]) <= PIXEL_TOLERANCE
                        && Math.abs(actualR - expected[2]) <= PIXEL_TOLERANCE,
                String.format("中心像素偏差过大: 实际 BGR=(%d,%d,%d), 期望 (%d,%d,%d)",
                        actualB, actualG, actualR, expected[0], expected[1], expected[2]));
        System.out.printf("[解码往返] 输入 %d 包, 输出 %d 帧 BGRA %dx%d, 首帧中心 BGR=(%d,%d,%d)%n",
                packets.size(), outputs.size(), WIDTH, HEIGHT, actualB, actualG, actualR);
        outputs.forEach(NativeFrame::close);
    }

    /** 单包喂入解码并收集输出 */
    private void decodePacket(byte[] packet, List<NativeFrame> outputs) {
        ByteBuffer buffer = ByteBuffer.allocateDirect(packet.length);
        buffer.put(packet);
        buffer.flip();
        NativeFrame encoded = new NativeFrame(buffer, WIDTH, HEIGHT, "H264", System.nanoTime());
        decoder.decode(encoded, outputs::add);
        encoded.close();
    }

    @Test
    void 输入格式不匹配或未启动_抛出异常() {
        decoder.init(config());
        decoder.start();
        NativeFrame wrongFormat = new NativeFrame(
                ByteBuffer.allocateDirect(64), WIDTH, HEIGHT, "I420", 0L);
        assertThrows(AdapterException.class, () -> decoder.decode(wrongFormat, f -> { }));
        wrongFormat.close();
        decoder.stop();
        // 停止后再 decode 应抛异常
        NativeFrame encoded = new NativeFrame(
                ByteBuffer.allocateDirect(16), WIDTH, HEIGHT, "H264", 0L);
        assertThrows(AdapterException.class, () -> decoder.decode(encoded, f -> { }));
        encoded.close();
    }

    @Test
    void 能力描述_软解码H264输入BGRA输出() {
        assertNotNull(decoder.capabilities());
        assertFalse(decoder.capabilities().isHardwareAccelerated());
        assertEquals(List.of("H264", "BGRA"), decoder.capabilities().supportedPixelFormats());
        assertEquals(JavaCvVideoDecoder.MAX_FPS, decoder.capabilities().maxFps());
    }

    @Test
    void 生命周期_重复stop幂等() {
        decoder.init(config());
        decoder.start();
        decoder.stop();
        decoder.stop(); // 幂等：不抛异常
        decoder.release();
    }

    private static AdapterConfig config() {
        return AdapterConfig.builder()
                .width(WIDTH).height(HEIGHT).fps(FPS)
                .pixelFormat("H264")
                .build();
    }
}
