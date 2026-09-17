package com.gudesk.viewer.decode;

import com.gudesk.common.spi.AdapterCapabilities;
import com.gudesk.common.spi.AdapterConfig;
import com.gudesk.common.spi.AdapterException;
import com.gudesk.common.spi.NativeFrame;
import com.gudesk.common.spi.VideoDecoder;
import org.bytedeco.ffmpeg.avcodec.AVCodec;
import org.bytedeco.ffmpeg.avcodec.AVCodecContext;
import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avutil.AVFrame;
import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.ffmpeg.global.swscale;
import org.bytedeco.ffmpeg.swscale.SwsContext;
import org.bytedeco.javacpp.BytePointer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * 基于 JavaCV（JavaCPP Presets for FFmpeg，软解码）的 H.264 视频解码默认实现。
 *
 * <p>解码链路：输入 Annex-B 码流帧（{@code pixelFormat="H264"}，每个帧已是
 * 编码端 {@code avcodec_receive_packet} 输出的完整 access unit，SPS/PPS 内联于关键帧）→
 * {@code avcodec_send_packet/receive_frame} →
 * {@code sws_scale} 转换 YUV420P（等帧格式）→ BGRA →
 * 输出 {@code pixelFormat="BGRA"} 的堆外 direct ByteBuffer 帧
 * （复用池 3 块轮转：消费者同步消费场景下零分配热路径；
 * 注意持有帧超过 3 帧周期会被后续输出覆盖）。
 *
 * <p><b>线程约束</b>：本适配器全程在调用线程同步执行（调用方为会话/网络线程），
 * 不创建任何线程；bytedeco JNI 调用不得运行在虚拟线程上——FFmpeg 原生库的
 * 线程局部状态与 JNI 实现在虚拟线程载体切换场景下未经验证，调用方必须使用
 * 平台线程驱动 {@link #decode(NativeFrame, Consumer)}。
 *
 * <p>生命周期：{@code init} 分配并打开解码器上下文与解析器；{@code stop}
 * 冲刷解码器内部缓冲（冲刷输出丢弃）；{@code release} 释放
 * parser/codec/sws 上下文与帧缓冲。
 */
public class JavaCvVideoDecoder implements VideoDecoder {

    private static final Logger LOG = LoggerFactory.getLogger(JavaCvVideoDecoder.class);

    /** 输入像素格式（Annex-B 码流帧） */
    public static final String INPUT_PIXEL_FORMAT = "H264";
    /** 输出像素格式 */
    public static final String OUTPUT_PIXEL_FORMAT = "BGRA";
    /** 输出 direct buffer 复用池大小（轮转） */
    public static final int OUTPUT_POOL_SIZE = 3;
    /** 能力上限 */
    public static final int MAX_WIDTH = 3840;
    public static final int MAX_HEIGHT = 2160;
    public static final int MAX_FPS = 60;

    private AVCodecContext codecCtx;
    private AVPacket packet;
    private AVFrame decFrame;
    private AVFrame bgraFrame;
    private SwsContext swsCtx;
    /** sws 上下文当前对应的输入宽高/像素格式（码流自适应，首帧或变化时重建） */
    private int srcWidth;
    private int srcHeight;
    private int srcFormat;
    /** 输出帧缓冲复用池 */
    private ByteBuffer[] outputPool;
    private int poolIndex;

    private volatile boolean inited;
    private volatile boolean started;
    private long decodedCount;

    /**
     * 默认降级实现工厂，供
     * {@code SpiLoader.load(VideoDecoder.class, "decoder", JavaCvVideoDecoder::defaultDecoder)}
     * 使用。
     */
    public static JavaCvVideoDecoder defaultDecoder() {
        return new JavaCvVideoDecoder();
    }

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    @Override
    public void init(AdapterConfig config) throws AdapterException {
        Objects.requireNonNull(config, "config");
        AVCodec codec = avcodec.avcodec_find_decoder(avcodec.AV_CODEC_ID_H264);
        if (codec == null) {
            throw new AdapterException("H.264 解码器不可用（avcodec_find_decoder 失败，"
                    + "请检查 javacpp/ffmpeg 平台依赖）");
        }
        codecCtx = avcodec.avcodec_alloc_context3(codec);
        if (codecCtx == null) {
            throw new AdapterException("avcodec_alloc_context3 失败");
        }
        // 单线程解码：帧级多线程会引入输出缓冲延迟（低延迟场景不可接受）
        codecCtx.thread_count(1);
        int ret = avcodec.avcodec_open2(codecCtx, codec,
                (org.bytedeco.ffmpeg.avutil.AVDictionary) null);
        if (ret < 0) {
            release();
            throw new AdapterException("avcodec_open2 失败: " + ret);
        }
        packet = avcodec.av_packet_alloc();
        decFrame = avutil.av_frame_alloc();
        bgraFrame = avutil.av_frame_alloc();
        srcWidth = 0;
        srcHeight = 0;
        srcFormat = -1;
        outputPool = null;
        poolIndex = 0;
        decodedCount = 0;
        inited = true;
        LOG.info("JavaCvVideoDecoder 初始化完成: {}→{} (完整 access unit 直送, 单线程软解码)",
                INPUT_PIXEL_FORMAT, OUTPUT_PIXEL_FORMAT);
    }

    @Override
    public void start() throws AdapterException {
        if (!inited) {
            throw new AdapterException("尚未初始化，请先调用 init(config)");
        }
        started = true;
    }

    @Override
    public void stop() throws AdapterException {
        if (!started) {
            return;
        }
        started = false;
        // 冲刷解码器内部缓冲帧（输出直接丢弃；会话正常收流时已在 decode 中同步消费）
        try {
            avcodec.avcodec_send_packet(codecCtx, (AVPacket) null);
            while (avcodec.avcodec_receive_frame(codecCtx, decFrame) == 0) {
                avutil.av_frame_unref(decFrame);
            }
        } catch (Throwable t) {
            LOG.warn("解码器冲刷失败", t);
        }
    }

    @Override
    public void release() {
        if (packet != null) {
            avcodec.av_packet_free(packet);
            packet = null;
        }
        if (decFrame != null) {
            avutil.av_frame_free(decFrame);
            decFrame = null;
        }
        if (bgraFrame != null) {
            avutil.av_frame_free(bgraFrame);
            bgraFrame = null;
        }
        if (swsCtx != null) {
            swscale.sws_freeContext(swsCtx);
            swsCtx = null;
        }
        if (codecCtx != null) {
            avcodec.avcodec_free_context(codecCtx);
            codecCtx = null;
        }
        outputPool = null;
        inited = false;
    }

    @Override
    public AdapterCapabilities capabilities() {
        return AdapterCapabilities.builder()
                .maxWidth(MAX_WIDTH)
                .maxHeight(MAX_HEIGHT)
                .maxFps(MAX_FPS)
                .hardwareAccelerated(false)
                // 输入 H264（Annex-B 码流帧），输出 BGRA（解码后像素格式）
                .addSupportedPixelFormat(INPUT_PIXEL_FORMAT)
                .addSupportedPixelFormat(OUTPUT_PIXEL_FORMAT)
                .build();
    }

    // ------------------------------------------------------------------
    // 解码
    // ------------------------------------------------------------------

    @Override
    public void decode(NativeFrame encoded, Consumer<NativeFrame> decodedOut) throws AdapterException {
        Objects.requireNonNull(encoded, "encoded");
        Objects.requireNonNull(decodedOut, "decodedOut");
        if (!started) {
            throw new AdapterException("解码器未启动");
        }
        if (!INPUT_PIXEL_FORMAT.equalsIgnoreCase(encoded.pixelFormat())) {
            throw new AdapterException("不支持的输入像素格式: " + encoded.pixelFormat()
                    + "（仅支持 " + INPUT_PIXEL_FORMAT + "）");
        }
        ByteBuffer in = encoded.buffer();
        if (in.position() != 0) {
            ByteBuffer dup = in.duplicate();
            dup.position(0);
            in = dup;
        }
        int len = in.remaining();
        if (len <= 0) {
            return;
        }
        BytePointer inPtr = in.isDirect() ? new BytePointer(in) : copyToDirect(in);
        // 每个 VideoFrame 的 h264_data 已是编码端 avcodec_receive_packet 输出的完整
        // H.264 access unit（Annex-B，含内联 SPS/PPS），直接送解码器，无需 parser 流式切分。
        packet.data(inPtr);
        packet.size(len);
        int ret = avcodec.avcodec_send_packet(codecCtx, packet);
        if (ret < 0) {
            // 丢帧导致缺少参考/PPS（帧间依赖链断裂）：静默跳过损坏帧，等待后续关键帧重同步
            LOG.debug("avcodec_send_packet 失败: {}（帧数据损坏，等待关键帧恢复）", ret);
            return;
        }
        while (avcodec.avcodec_receive_frame(codecCtx, decFrame) == 0) {
            try {
                emitDecodedFrame(decodedOut);
            } finally {
                avutil.av_frame_unref(decFrame);
            }
        }
    }

    /** 解码帧 → sws_scale 转 BGRA → 从复用池取 buffer 输出 */
    private void emitDecodedFrame(Consumer<NativeFrame> decodedOut) throws AdapterException {
        int width = decFrame.width();
        int height = decFrame.height();
        int format = decFrame.format();
        if (width <= 0 || height <= 0) {
            return;
        }
        // 首帧或码流参数变化：重建 sws 上下文与输出池
        if (swsCtx == null || width != srcWidth || height != srcHeight || format != srcFormat) {
            rebuildSws(width, height, format);
        }
        // 池轮转：取一块 direct buffer 作为本帧输出，覆盖写
        ByteBuffer outBuffer = outputPool[poolIndex];
        poolIndex = (poolIndex + 1) % outputPool.length;
        bgraFrame.data(0, new BytePointer(outBuffer));
        bgraFrame.linesize(0, width * 4);
        int scaled = swscale.sws_scale(swsCtx,
                decFrame.data(), decFrame.linesize(), 0, height,
                bgraFrame.data(), bgraFrame.linesize());
        if (scaled != height) {
            throw new AdapterException("sws_scale 失败: 输出行数 " + scaled + " != " + height);
        }
        outBuffer.position(0).limit(width * height * 4);
        decodedCount++;
        decodedOut.accept(new NativeFrame(outBuffer, width, height,
                OUTPUT_PIXEL_FORMAT, System.nanoTime(), 0L, null));
    }

    private void rebuildSws(int width, int height, int format) throws AdapterException {
        if (swsCtx != null) {
            swscale.sws_freeContext(swsCtx);
            swsCtx = null;
        }
        // FAST_BILINEAR 降低颜色转换 CPU（远程桌面文字主要为亮度，色度影响小）
        swsCtx = swscale.sws_getContext(width, height, format,
                width, height, avutil.AV_PIX_FMT_BGRA,
                swscale.SWS_FAST_BILINEAR, null, null, (double[]) null);
        if (swsCtx == null) {
            throw new AdapterException("sws_getContext 失败: " + width + "x" + height
                    + " fmt=" + format + " → BGRA");
        }
        outputPool = new ByteBuffer[OUTPUT_POOL_SIZE];
        for (int i = 0; i < OUTPUT_POOL_SIZE; i++) {
            outputPool[i] = ByteBuffer.allocateDirect(width * height * 4);
        }
        poolIndex = 0;
        srcWidth = width;
        srcHeight = height;
        srcFormat = format;
        LOG.info("解码输出转换上下文就绪: {}x{} (fmt={} → BGRA)", width, height, format);
    }

    /** 非 direct 输入复制到堆外（av_parser_parse2 需要连续原生内存） */
    private static BytePointer copyToDirect(ByteBuffer heap) {
        ByteBuffer direct = ByteBuffer.allocateDirect(heap.remaining());
        direct.put(heap.duplicate());
        direct.flip();
        return new BytePointer(direct);
    }

    /** 累计输出解码帧数（诊断用） */
    public long decodedCount() {
        return decodedCount;
    }
}
