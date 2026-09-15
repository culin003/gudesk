package com.gudesk.host.encode;

import com.gudesk.common.spi.AdapterCapabilities;
import com.gudesk.common.spi.AdapterConfig;
import com.gudesk.common.spi.AdapterException;
import com.gudesk.common.spi.NativeFrame;
import com.gudesk.common.spi.VideoEncoder;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 基于 JavaCV（JavaCPP Presets for FFmpeg，libx264 软编码）的 H.264 视频编码默认实现。
 *
 * <p>编码链路：输入 BGRA 帧（堆外 direct ByteBuffer，零拷贝包装）→
 * {@code sws_scale} 转换为 YUV420P → {@code avcodec_send_frame/receive_packet}
 * （libx264，preset=ultrafast、tune=zerolatency、GOP=60、无 B 帧、码率自适应）→
 * 输出 Annex-B 码流帧（{@code pixelFormat="H264"}，x264 未设置 GLOBAL_HEADER 时
 * 输出本身即为带起始码的 Annex-B，SPS/PPS 内联于关键帧之前）。
 *
 * <p><b>线程约束</b>：本适配器全程在调用线程执行（调用方为捕获线程/会话线程），
 * 不创建任何线程；bytedeco JNI 调用不得运行在虚拟线程上——FFmpeg 原生库的
 * 线程局部状态与 JNI 实现在虚拟线程载体切换场景下未经验证，调用方必须使用
 * 平台线程驱动 {@link #encode(NativeFrame, Consumer)}。
 *
 * <p>关键帧请求：{@link #requestKeyframe()} 置位标记，下一帧以
 * {@code AV_PICTURE_TYPE_I} 送入编码器强制 IDR（x264 默认在 IDR 前重复 SPS/PPS）。
 */
public class JavaCvVideoEncoder implements VideoEncoder {

    private static final Logger LOG = LoggerFactory.getLogger(JavaCvVideoEncoder.class);

    /** 输入像素格式 */
    public static final String INPUT_PIXEL_FORMAT = "BGRA";
    /** 输出像素格式（Annex-B 码流） */
    public static final String OUTPUT_PIXEL_FORMAT = "H264";
    /** 编码器名称（位于 ffmpeg -gpl 原生库中） */
    public static final String ENCODER_NAME = "libx264";
    /** 默认 GOP（关键帧间隔） */
    public static final int DEFAULT_GOP = 60;
    /** 码率下限（bps） */
    public static final long MIN_BITRATE = 1_000_000L;
    /** 能力上限 */
    public static final int MAX_WIDTH = 3840;
    public static final int MAX_HEIGHT = 2160;
    public static final int MAX_FPS = 60;

    private final AtomicBoolean keyframeRequested = new AtomicBoolean(true);

    private AVCodecContext codecCtx;
    private SwsContext swsCtx;
    private AVFrame yuvFrame;
    private AVFrame bgraFrame;
    private AVPacket packet;
    private int width;
    private int height;
    private int encodeWidth;
    private int encodeHeight;
    private int fps;
    private volatile boolean inited;
    private volatile boolean started;
    private long frameIndex;
    private boolean extradataEmitted;
    private long encodedCount;

    /**
     * 默认降级实现工厂，供
     * {@code SpiLoader.load(VideoEncoder.class, "encoder", JavaCvVideoEncoder::defaultEncoder)}
     * 使用。
     */
    public static JavaCvVideoEncoder defaultEncoder() {
        return new JavaCvVideoEncoder();
    }

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    @Override
    public void init(AdapterConfig config) throws AdapterException {
        Objects.requireNonNull(config, "config");
        this.width = config.width();
        this.height = config.height();
        if (width <= 0 || height <= 0) {
            throw new AdapterException("编码尺寸非法: " + width + "x" + height);
        }
        // YUV420P 要求宽高为偶数：奇数尺寸裁剪 1 像素
        this.encodeWidth = width & ~1;
        this.encodeHeight = height & ~1;
        this.fps = config.fps() > 0 ? config.fps() : 15;
        this.frameIndex = 0;
        this.extradataEmitted = false;
        this.encodedCount = 0;
        this.keyframeRequested.set(true);

        AVCodec codec = avcodec.avcodec_find_encoder_by_name(ENCODER_NAME);
        if (codec == null) {
            throw new AdapterException("编码器 " + ENCODER_NAME
                    + " 不可用（需 ffmpeg -gpl 原生库，请检查 javacpp/ffmpeg 平台依赖）");
        }
        codecCtx = avcodec.avcodec_alloc_context3(codec);
        if (codecCtx == null) {
            throw new AdapterException("avcodec_alloc_context3 失败");
        }
        long bitrate = resolveBitrate(config);
        codecCtx.pix_fmt(avutil.AV_PIX_FMT_YUV420P);
        codecCtx.width(encodeWidth);
        codecCtx.height(encodeHeight);
        codecCtx.time_base(new AVRational().num(1).den(fps));
        codecCtx.framerate(new AVRational().num(fps).den(1));
        codecCtx.gop_size(parseGop(config));
        codecCtx.max_b_frames(0);
        codecCtx.bit_rate(bitrate);
        codecCtx.thread_count(0); // 线程数自动

        // x264 私有选项：ultrafast + zerolatency（低延迟实时编码）；
        // forced-idr=1：强制关键帧（pict_type=I）时输出 IDR（此前重复 SPS/PPS，供解码端重同步）
        AVDictionary options = new AVDictionary();
        try {
            avutil.av_dict_set(options, "preset", "ultrafast", 0);
            avutil.av_dict_set(options, "tune", "zerolatency", 0);
            avutil.av_dict_set(options, "forced-idr", "1", 0);
            int ret = avcodec.avcodec_open2(codecCtx, null, options);
            if (ret < 0) {
                throw new AdapterException("avcodec_open2 失败: " + ret);
            }
        } finally {
            avutil.av_dict_free(options);
        }

        // BGRA → YUV420P 转换器（奇数尺寸由 sws 顺带裁剪到偶数）
        swsCtx = swscale.sws_getContext(width, height, avutil.AV_PIX_FMT_BGRA,
                encodeWidth, encodeHeight, avutil.AV_PIX_FMT_YUV420P,
                swscale.SWS_BILINEAR, null, null, (double[]) null);
        if (swsCtx == null) {
            release();
            throw new AdapterException("sws_getContext 失败");
        }
        yuvFrame = avutil.av_frame_alloc();
        yuvFrame.format(avutil.AV_PIX_FMT_YUV420P);
        yuvFrame.width(encodeWidth);
        yuvFrame.height(encodeHeight);
        if (avutil.av_frame_get_buffer(yuvFrame, 0) < 0) {
            release();
            throw new AdapterException("av_frame_get_buffer 失败");
        }
        // 输入 BGRA 包装帧：data/linesize 每帧 encode 时填充
        bgraFrame = avutil.av_frame_alloc();
        bgraFrame.format(avutil.AV_PIX_FMT_BGRA);
        bgraFrame.width(width);
        bgraFrame.height(height);
        packet = avcodec.av_packet_alloc();
        inited = true;
        LOG.info("JavaCvVideoEncoder 初始化完成: {}→{} ({}x{}@{}fps, {}bps, gop={}, libx264 ultrafast/zerolatency)",
                INPUT_PIXEL_FORMAT, OUTPUT_PIXEL_FORMAT, encodeWidth, encodeHeight, fps, bitrate,
                codecCtx.gop_size());
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
        // 冲刷编码器缓冲（ultrafast/zerolatency 下通常无残留帧），输出直接丢弃
        try {
            avcodec.avcodec_send_frame(codecCtx, (AVFrame) null);
            drainPackets(f -> { });
        } catch (Throwable t) {
            LOG.warn("编码器冲刷失败", t);
        }
    }

    @Override
    public void release() {
        if (bgraFrame != null) {
            avutil.av_frame_free(bgraFrame);
            bgraFrame = null;
        }
        if (yuvFrame != null) {
            avutil.av_frame_free(yuvFrame);
            yuvFrame = null;
        }
        if (packet != null) {
            avcodec.av_packet_free(packet);
            packet = null;
        }
        if (swsCtx != null) {
            swscale.sws_freeContext(swsCtx);
            swsCtx = null;
        }
        if (codecCtx != null) {
            avcodec.avcodec_free_context(codecCtx);
            codecCtx = null;
        }
        inited = false;
    }

    @Override
    public AdapterCapabilities capabilities() {
        return AdapterCapabilities.builder()
                .maxWidth(MAX_WIDTH)
                .maxHeight(MAX_HEIGHT)
                .maxFps(MAX_FPS)
                .hardwareAccelerated(false)
                // 输入 BGRA，输出 H264（编码后像素格式）
                .addSupportedPixelFormat(INPUT_PIXEL_FORMAT)
                .addSupportedPixelFormat(OUTPUT_PIXEL_FORMAT)
                .build();
    }

    // ------------------------------------------------------------------
    // 编码
    // ------------------------------------------------------------------

    @Override
    public void encode(NativeFrame frame, Consumer<NativeFrame> encodedOut) throws AdapterException {
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(encodedOut, "encodedOut");
        if (!started) {
            throw new AdapterException("编码器未启动");
        }
        if (!INPUT_PIXEL_FORMAT.equalsIgnoreCase(frame.pixelFormat())) {
            throw new AdapterException("不支持的输入像素格式: " + frame.pixelFormat()
                    + "（仅支持 " + INPUT_PIXEL_FORMAT + "）");
        }
        if (frame.width() != width || frame.height() != height) {
            throw new AdapterException("帧尺寸不匹配: 输入 " + frame.width() + "x" + frame.height()
                    + ", 编码器 " + width + "x" + height);
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
        // 零拷贝包装：direct ByteBuffer 的内存指针作为源帧 data[0]
        bgraFrame.data(0, new BytePointer(bgra));
        bgraFrame.linesize(0, width * 4);
        int scaled = swscale.sws_scale(swsCtx,
                bgraFrame.data(), bgraFrame.linesize(), 0, height,
                yuvFrame.data(), yuvFrame.linesize());
        if (scaled != encodeHeight) {
            throw new AdapterException("sws_scale 失败: 输出行数 " + scaled + " != " + encodeHeight);
        }
        // pts 按 time_base=1/fps 递增；关键帧请求标记强制下一帧为 I 帧
        yuvFrame.pts(frameIndex++);
        yuvFrame.pict_type(keyframeRequested.compareAndSet(true, false)
                ? avutil.AV_PICTURE_TYPE_I
                : avutil.AV_PICTURE_TYPE_NONE);
        int ret = avcodec.avcodec_send_frame(codecCtx, yuvFrame);
        if (ret < 0) {
            throw new AdapterException("avcodec_send_frame 失败: " + ret);
        }
        drainPackets(encodedOut);
    }

    @Override
    public void requestKeyframe() {
        keyframeRequested.set(true);
    }

    /** 接收编码输出包并转为 Annex-B NativeFrame 输出 */
    private void drainPackets(Consumer<NativeFrame> encodedOut) {
        while (avcodec.avcodec_receive_packet(codecCtx, packet) == 0) {
            emitPacket(encodedOut);
            avcodec.av_packet_unref(packet);
        }
    }

    private void emitPacket(Consumer<NativeFrame> encodedOut) {
        int size = packet.size();
        boolean keyframe = (packet.flags() & avcodec.AV_PKT_FLAG_KEY) != 0;
        byte[] data = new byte[size];
        packet.data().position(0).limit(size).get(data);
        // 首个关键帧前拼接 extradata（AVCC 格式转换为 Annex-B；未设 GLOBAL_HEADER 时
        // x264 输出本身已内联 SPS/PPS，此处为防御性处理）
        if (keyframe && !extradataEmitted && codecCtx.extradata_size() > 0) {
            byte[] extradata = new byte[codecCtx.extradata_size()];
            codecCtx.extradata().position(0).limit(extradata.length).get(extradata);
            byte[] annexB = extradataToAnnexB(extradata);
            if (annexB.length > 0) {
                emit(encodedOut, annexB, keyframe);
            }
            extradataEmitted = true;
        }
        emit(encodedOut, data, keyframe);
    }

    private void emit(Consumer<NativeFrame> encodedOut, byte[] bytes, boolean keyframe) {
        ByteBuffer buffer = ByteBuffer.allocateDirect(bytes.length);
        buffer.put(bytes);
        buffer.flip();
        encodedCount++;
        encodedOut.accept(new NativeFrame(buffer, encodeWidth, encodeHeight,
                OUTPUT_PIXEL_FORMAT, System.nanoTime(), 0L, null));
    }

    /** 码率解析：extra "bitrate"（bps）优先，否则 1080p 自适应 width*height*fps*0.05 */
    private static long resolveBitrate(AdapterConfig config) {
        String configured = config.extraValue("bitrate");
        if (configured != null && !configured.isBlank()) {
            try {
                return Math.max(MIN_BITRATE, Long.parseLong(configured.trim()));
            } catch (NumberFormatException e) {
                LOG.warn("非法 bitrate 配置 '{}', 使用自适应码率", configured);
            }
        }
        long adaptive = (long) ((double) config.width() * config.height() * Math.max(1, config.fps()) * 0.05);
        return Math.max(MIN_BITRATE, adaptive);
    }

    /** GOP 解析：extra "gop" 优先，否则默认 {@link #DEFAULT_GOP} */
    private static int parseGop(AdapterConfig config) {
        String configured = config.extraValue("gop");
        if (configured != null && !configured.isBlank()) {
            try {
                return Math.max(1, Integer.parseInt(configured.trim()));
            } catch (NumberFormatException e) {
                LOG.warn("非法 gop 配置 '{}', 使用默认值 {}", configured, DEFAULT_GOP);
            }
        }
        return DEFAULT_GOP;
    }

    // ------------------------------------------------------------------
    // Annex-B 工具
    // ------------------------------------------------------------------

    /** Annex-B 起始码 */
    public static final byte[] ANNEX_B_START_CODE = {0, 0, 0, 1};

    /**
     * extradata AVCC（avcC，长度前缀 NAL 列表）→ Annex-B（起始码分隔）转换。
     * 输入已是 Annex-B 或无法解析时原样返回。
     */
    public static byte[] extradataToAnnexB(byte[] extradata) {
        if (extradata == null || extradata.length < 7 || extradata[0] != 1) {
            return extradata == null ? new byte[0] : extradata;
        }
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try {
            int pos = 5; // 跳过 version/profile/compat/level(4 字节) + lengthSizeMinusOne(1 字节)
            int spsCount = extradata[pos++] & 0x1F;
            for (int i = 0; i < spsCount && pos + 2 <= extradata.length; i++) {
                int len = ((extradata[pos] & 0xFF) << 8) | (extradata[pos + 1] & 0xFF);
                pos += 2;
                if (len <= 0 || pos + len > extradata.length) {
                    return extradata; // 数据损坏：原样返回
                }
                out.write(ANNEX_B_START_CODE);
                out.write(extradata, pos, len);
                pos += len;
            }
            int ppsCount = pos < extradata.length ? extradata[pos++] & 0x1F : 0;
            for (int i = 0; i < ppsCount && pos + 2 <= extradata.length; i++) {
                int len = ((extradata[pos] & 0xFF) << 8) | (extradata[pos + 1] & 0xFF);
                pos += 2;
                if (len <= 0 || pos + len > extradata.length) {
                    return extradata;
                }
                out.write(ANNEX_B_START_CODE);
                out.write(extradata, pos, len);
                pos += len;
            }
        } catch (java.io.IOException e) {
            return extradata; // ByteArrayOutputStream 不会抛出
        }
        return out.toByteArray();
    }

    /**
     * 检测 Annex-B H.264 码流中是否含 SPS NAL（type=7），用于识别关键帧
     * （x264 默认在 IDR 前重复 SPS/PPS）。
     */
    public static boolean containsSps(ByteBuffer h264) {
        ByteBuffer buf = h264.duplicate();
        int i = 0;
        int limit = buf.limit() - 4;
        while (i < limit) {
            if (buf.get(i) == 0 && buf.get(i + 1) == 0 && buf.get(i + 2) == 0 && buf.get(i + 3) == 1) {
                int nalType = buf.get(i + 4) & 0x1F;
                if (nalType == 7 || nalType == 8) {
                    return true;
                }
                i += 4;
            } else {
                i++;
            }
        }
        return false;
    }

    /** 累计输出编码帧数（诊断用） */
    public long encodedCount() {
        return encodedCount;
    }
}
