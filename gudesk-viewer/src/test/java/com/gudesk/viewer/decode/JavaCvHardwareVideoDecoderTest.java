package com.gudesk.viewer.decode;

import com.gudesk.common.spi.AdapterConfig;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@link JavaCvHardwareVideoDecoder} 单测：测试内用 libx264 编码合成 H.264 Annex-B 流，
 * 喂给硬件解码器做往返验证（输出 BGRA 数量/尺寸/格式）。无可用硬件解码器（
 * {@link HardwareDecoderDetector#detect()} 为 null）或原生库不可用时按 assume 优雅跳过。
 */
class JavaCvHardwareVideoDecoderTest {

    private static final int WIDTH = 320;
    private static final int HEIGHT = 240;
    private static final int FPS = 30;
    private static final int FRAMES = 5;

    private static final String NATIVES_UNAVAILABLE_REASON = nativesCheck();

    private JavaCvHardwareVideoDecoder decoder;

    @BeforeEach
    void setUp() {
        assumeTrue(NATIVES_UNAVAILABLE_REASON == null, NATIVES_UNAVAILABLE_REASON);
        assumeTrue(HardwareDecoderDetector.detect() != null, "无可用硬件解码器，跳过硬件解码测试");
        decoder = new JavaCvHardwareVideoDecoder();
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
                return "libx264 编码器不可用（ffmpeg 原生库缺少 GPL 构建），跳过硬件解码往返测试";
            }
            return null;
        } catch (Throwable t) {
            return "javacv/ffmpeg 原生库加载失败，跳过硬件解码往返测试: " + t;
        }
    }

    // ------------------------------------------------------------------
    // 测试内 H.264 编码小工具（与 JavaCvVideoDecoderTest 一致，不依赖 host 模块）
    // ------------------------------------------------------------------

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
            assertTrue(avcodec.avcodec_open2(ctx, null, options) >= 0, "avcodec_open2 失败");
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
    void 硬件解码往返_5帧合成H264_输出BGRA() {
        List<byte[]> packets = encodeH264(FRAMES);
        assertEquals(FRAMES, packets.size(), "zerolatency 编码应逐帧输出");

        decoder.init(config());
        decoder.start();
        List<NativeFrame> outputs = new ArrayList<>();
        for (byte[] packet : packets) {
            decodePacket(packet, outputs);
        }
        // 尾帧滞留于 parser，补喂一包触发输出
        decodePacket(packets.get(FRAMES - 1), outputs);
        decoder.stop();

        assertFalse(outputs.isEmpty(), "硬件解码应输出帧");
        for (NativeFrame f : outputs) {
            assertEquals("BGRA", f.pixelFormat());
            assertEquals(WIDTH, f.width());
            assertEquals(HEIGHT, f.height());
            assertEquals(WIDTH * HEIGHT * 4, f.buffer().remaining());
        }
        System.out.printf("[硬件解码往返] 输入 %d 包, 输出 %d 帧 BGRA %dx%d%n",
                packets.size() + 1, outputs.size(), WIDTH, HEIGHT);
        outputs.forEach(NativeFrame::close);
    }

    @Test
    void 能力描述_硬件加速H264输入BGRA输出() {
        assertNotNull(decoder.capabilities());
        assertTrue(decoder.capabilities().isHardwareAccelerated());
        assertEquals(List.of("H264", "BGRA"), decoder.capabilities().supportedPixelFormats());
    }

    private void decodePacket(byte[] packet, List<NativeFrame> outputs) {
        ByteBuffer buffer = ByteBuffer.allocateDirect(packet.length);
        buffer.put(packet);
        buffer.flip();
        NativeFrame encoded = new NativeFrame(buffer, WIDTH, HEIGHT, "H264", System.nanoTime());
        decoder.decode(encoded, outputs::add);
        encoded.close();
    }

    private static AdapterConfig config() {
        return AdapterConfig.builder()
                .width(WIDTH).height(HEIGHT).fps(FPS)
                .pixelFormat("H264")
                .build();
    }
}
