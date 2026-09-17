package com.gudesk.viewer.decode;

import com.gudesk.common.spi.AdapterCapabilities;
import com.gudesk.common.spi.AdapterConfig;
import com.gudesk.common.spi.AdapterException;
import com.gudesk.common.spi.NativeFrame;
import com.gudesk.common.spi.VideoDecoder;
import org.bytedeco.ffmpeg.avcodec.AVCodec;
import org.bytedeco.ffmpeg.avcodec.AVCodecContext;
import org.bytedeco.ffmpeg.avcodec.AVCodecParserContext;
import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avutil.AVBufferRef;
import org.bytedeco.ffmpeg.avutil.AVDictionary;
import org.bytedeco.ffmpeg.avutil.AVFrame;
import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.ffmpeg.global.swscale;
import org.bytedeco.ffmpeg.swscale.SwsContext;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.IntPointer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * 基于 JavaCV（JavaCPP Presets for FFmpeg）的 H.264 硬件解码实现。
 *
 * <p>解码链路：输入 Annex-B 码流帧（{@code pixelFormat="H264"}）→
 * {@code av_parser_parse2} 流式切分 NAL/帧 → {@code avcodec_send_packet/receive_frame}
 * 硬解（输出硬件帧，如 VAAPI/CUDA/D3D11VA）→ {@code av_hwframe_transfer_data} 拷贝到内存 →
 * {@code sws_scale} 转 BGRA → 输出 {@code pixelFormat="BGRA"} 的堆外 direct ByteBuffer 帧。
 *
 * <p>硬件方案由 {@link HardwareDecoderDetector#detect()} 探测决定（init 阶段复用其缓存结果）；
 * 未检测到硬件时 {@link #init} 抛 {@link AdapterException}，由调用方回落软件解码。
 *
 * <p><b>线程约束</b>：与 {@link JavaCvVideoDecoder} 一致，全程在调用线程同步执行，不创建线程；
 * bytedeco JNI 调用不得运行在虚拟线程上（FFmpeg 原生库线程局部状态与 JNI 实现在虚拟线程
 * 载体切换场景下未经验证），调用方必须使用平台线程驱动 {@link #decode(NativeFrame, Consumer)}。
 */
public class JavaCvHardwareVideoDecoder implements VideoDecoder {

    private static final Logger LOG = LoggerFactory.getLogger(JavaCvHardwareVideoDecoder.class);

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

    /** 硬件设备上下文（引用计数，release 时归还） */
    private AVBufferRef hwDeviceCtx;
    private AVCodecContext codecCtx;
    private AVCodecParserContext parserCtx;
    private AVPacket packet;
    /** 硬解输出帧（硬件内存，receive_frame 回填） */
    private AVFrame hwFrame;
    /** 硬件帧 transfer 到内存的目标帧（sws_scale 的源） */
    private AVFrame swFrame;
    private AVFrame bgraFrame;
    private SwsContext swsCtx;
    /** sws 上下文当前对应的输入宽高/像素格式（首帧或变化时重建） */
    private int srcWidth;
    private int srcHeight;
    private int srcFormat;
    /** 输出帧缓冲复用池 */
    private ByteBuffer[] outputPool;
    private int poolIndex;
    /** parser 输出参数（@ByPtrPtr 回填的输出缓冲指针/长度，复用避免每帧分配） */
    private BytePointer parserOutBuf;
    private IntPointer parserOutSize;

    private volatile boolean inited;
    private volatile boolean started;
    private long decodedCount;

    /** 默认降级实现工厂，供 {@code SpiLoader.load(VideoDecoder.class, "decoder", ...)} 使用 */
    public static JavaCvHardwareVideoDecoder defaultDecoder() {
        return new JavaCvHardwareVideoDecoder();
    }

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    @Override
    public void init(AdapterConfig config) throws AdapterException {
        Objects.requireNonNull(config, "config");
        HardwareDecoderDetector.Result hw = HardwareDecoderDetector.detect();
        if (hw == null) {
            throw new AdapterException("未检测到可用的硬件解码器，请回落软件解码");
        }

        AVBufferRef deviceCtx = new AVBufferRef();
        int ret = avutil.av_hwdevice_ctx_create(deviceCtx, hw.deviceType(), (String) null,
                (AVDictionary) null, 0);
        if (ret < 0 || deviceCtx.isNull()) {
            throw new AdapterException("av_hwdevice_ctx_create 失败: " + ret + "（" + hw.accelName() + "）");
        }
        this.hwDeviceCtx = deviceCtx;

        AVCodec codec = avcodec.avcodec_find_decoder_by_name(hw.decoderName());
        if (codec == null) {
            release();
            throw new AdapterException("硬件解码器不可用（avcodec_find_decoder_by_name 失败）: " + hw.decoderName());
        }
        codecCtx = avcodec.avcodec_alloc_context3(codec);
        if (codecCtx == null) {
            release();
            throw new AdapterException("avcodec_alloc_context3 失败");
        }
        // 关联硬件设备上下文（解码器持有独立引用，release 时随 codecCtx 一并归还）
        codecCtx.hw_device_ctx(avutil.av_buffer_ref(hwDeviceCtx));
        // 单线程解码：低延迟场景避免帧级多线程引入输出缓冲延迟
        codecCtx.thread_count(1);
        ret = avcodec.avcodec_open2(codecCtx, codec, (AVDictionary) null);
        if (ret < 0) {
            release();
            throw new AdapterException("avcodec_open2 失败: " + ret);
        }

        // Annex-B 流式解析：按 NAL 边界切分完整帧（与软解一致，无需 extradata）
        parserCtx = avcodec.av_parser_init(avcodec.AV_CODEC_ID_H264);
        if (parserCtx == null) {
            release();
            throw new AdapterException("av_parser_init(AV_CODEC_ID_H264) 失败");
        }
        packet = avcodec.av_packet_alloc();
        hwFrame = avutil.av_frame_alloc();
        swFrame = avutil.av_frame_alloc();
        bgraFrame = avutil.av_frame_alloc();
        parserOutBuf = new BytePointer(1);
        parserOutSize = new IntPointer(1);
        srcWidth = 0;
        srcHeight = 0;
        srcFormat = -1;
        outputPool = null;
        poolIndex = 0;
        decodedCount = 0;
        inited = true;
        LOG.info("JavaCvHardwareVideoDecoder 初始化完成: {}→{}（{}，{}）",
                INPUT_PIXEL_FORMAT, OUTPUT_PIXEL_FORMAT, hw.accelName(), hw.decoderName());
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
        try {
            avcodec.avcodec_send_packet(codecCtx, (AVPacket) null);
            while (avcodec.avcodec_receive_frame(codecCtx, hwFrame) == 0) {
                avutil.av_frame_unref(hwFrame);
            }
        } catch (Throwable t) {
            LOG.warn("解码器冲刷失败", t);
        }
    }

    @Override
    public void release() {
        if (parserCtx != null) {
            avcodec.av_parser_close(parserCtx);
            parserCtx = null;
        }
        if (packet != null) {
            avcodec.av_packet_free(packet);
            packet = null;
        }
        if (hwFrame != null) {
            avutil.av_frame_free(hwFrame);
            hwFrame = null;
        }
        if (swFrame != null) {
            avutil.av_frame_free(swFrame);
            swFrame = null;
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
        if (hwDeviceCtx != null) {
            avutil.av_buffer_unref(hwDeviceCtx);
            hwDeviceCtx = null;
        }
        parserOutBuf = null;
        parserOutSize = null;
        outputPool = null;
        inited = false;
    }

    @Override
    public AdapterCapabilities capabilities() {
        return AdapterCapabilities.builder()
                .maxWidth(MAX_WIDTH)
                .maxHeight(MAX_HEIGHT)
                .maxFps(MAX_FPS)
                .hardwareAccelerated(true)
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
        // 与软解一致的 Annex-B 流式解析循环
        int offset = 0;
        while (offset < len) {
            int consumed = avcodec.av_parser_parse2(parserCtx, codecCtx, parserOutBuf, parserOutSize,
                    inPtr.position(offset), len - offset,
                    avutil.AV_NOPTS_VALUE, avutil.AV_NOPTS_VALUE, 0);
            if (consumed < 0) {
                throw new AdapterException("av_parser_parse2 失败: " + consumed);
            }
            offset += consumed;
            boolean hasOutput = parserOutSize.get() > 0;
            if (hasOutput) {
                packet.data(parserOutBuf);
                packet.size(parserOutSize.get());
                sendPacket(decodedOut);
            }
            if (consumed == 0 && !hasOutput) {
                break;
            }
        }
    }

    /** 送包解码：硬解输出硬件帧 → transfer 到内存 → 转 BGRA */
    private void sendPacket(Consumer<NativeFrame> decodedOut) throws AdapterException {
        int ret = avcodec.avcodec_send_packet(codecCtx, packet);
        if (ret < 0) {
            throw new AdapterException("avcodec_send_packet 失败: " + ret);
        }
        while (avcodec.avcodec_receive_frame(codecCtx, hwFrame) == 0) {
            try {
                int transferred = avutil.av_hwframe_transfer_data(swFrame, hwFrame, 0);
                if (transferred < 0) {
                    throw new AdapterException("av_hwframe_transfer_data 失败: " + transferred);
                }
                emitDecodedFrame(decodedOut);
            } finally {
                avutil.av_frame_unref(hwFrame);
                avutil.av_frame_unref(swFrame);
            }
        }
    }

    /** 内存帧（transfer 后）→ sws_scale 转 BGRA → 从复用池取 buffer 输出 */
    private void emitDecodedFrame(Consumer<NativeFrame> decodedOut) throws AdapterException {
        int width = swFrame.width();
        int height = swFrame.height();
        int format = swFrame.format();
        if (width <= 0 || height <= 0) {
            return;
        }
        if (swsCtx == null || width != srcWidth || height != srcHeight || format != srcFormat) {
            rebuildSws(width, height, format);
        }
        ByteBuffer outBuffer = outputPool[poolIndex];
        poolIndex = (poolIndex + 1) % outputPool.length;
        bgraFrame.data(0, new BytePointer(outBuffer));
        bgraFrame.linesize(0, width * 4);
        int scaled = swscale.sws_scale(swsCtx,
                swFrame.data(), swFrame.linesize(), 0, height,
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
        // FAST_BILINEAR 降低颜色转换 CPU（与软解一致）
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
        LOG.info("硬件解码输出转换上下文就绪: {}x{} (fmt={} → BGRA)", width, height, format);
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
