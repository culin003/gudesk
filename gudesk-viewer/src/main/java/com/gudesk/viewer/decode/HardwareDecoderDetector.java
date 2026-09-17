package com.gudesk.viewer.decode;

import org.bytedeco.ffmpeg.avcodec.AVCodec;
import org.bytedeco.ffmpeg.avutil.AVBufferRef;
import org.bytedeco.ffmpeg.avutil.AVDictionary;
import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.ffmpeg.global.avutil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

/**
 * 硬件解码可用性检测：按运行平台探测常见的 FFmpeg 硬件 H.264 解码方案。
 *
 * <p>探测策略（仅覆盖常见方案）：
 * <ul>
 *   <li>Linux：QSV（Intel QuickSync）→ CUDA/NVDEC（NVIDIA）→ VAAPI（Intel/AMD 核显）→ VDPAU（旧 NVIDIA）；</li>
 *   <li>Windows：D3D11VA → DXVA2。</li>
 * </ul>
 *
 * <p>单个方案判定「可用」需同时满足：{@code avcodec_find_decoder_by_name} 找到硬件解码器
 * 且 {@code av_hwdevice_ctx_create} 成功创建硬件设备上下文（探测成功后立即释放，只探测不持有）。
 *
 * <p>探测结果缓存（{@code static volatile} + 双检锁）：{@code DecoderSelector} 的选择阶段与
 * 硬件解码器 {@code init} 阶段复用同一次探测结果，避免重复探测与结果漂移。
 *
 * <p>线程约束：探测仅发生在会话建立前的选择阶段（UI/CLI/自检线程），非热路径。
 */
public final class HardwareDecoderDetector {

    private static final Logger LOG = LoggerFactory.getLogger(HardwareDecoderDetector.class);

    /** 探测结果：{@code accelName} 诊断名，{@code decoderName} FFmpeg 硬件解码器名，{@code deviceType} AV_HWDEVICE_TYPE_* */
    public record Result(String accelName, String decoderName, int deviceType) {
    }

    private static volatile Result cached;

    private HardwareDecoderDetector() {
    }

    /** 探测当前环境可用的硬件解码方案；无可用硬件返回 {@code null}（调用方回落软解） */
    public static Result detect() {
        Result result = cached;
        if (result != null) {
            LOG.debug("[硬件解码检测] 命中缓存结果: accel={}, decoder={}, deviceType={}",
                    result.accelName(), result.decoderName(), result.deviceType());
            return result;
        }
        synchronized (HardwareDecoderDetector.class) {
            if (cached == null) {
                LOG.info("[硬件解码检测] 首次探测，开始检测可用硬件解码方案 ...");
                cached = doDetect();
            }
            return cached;
        }
    }

    private static Result doDetect() {
        String osName = System.getProperty("os.name", "");
        try {
            if (isWindows()) {
                LOG.info("[硬件解码检测] 当前平台 Windows（os.name={}），按顺序探测: D3D11VA → DXVA2", osName);
                return logOutcome(firstAvailable(
                        probe("d3d11va", "h264_d3d11va", avutil.AV_HWDEVICE_TYPE_D3D11VA),
                        probe("dxva2", "h264_dxva2", avutil.AV_HWDEVICE_TYPE_DXVA2)));
            }
            LOG.info("[硬件解码检测] 当前平台 Linux/其他（os.name={}），按顺序探测: QSV → CUDA/NVDEC → VAAPI → VDPAU", osName);
            return logOutcome(firstAvailable(
                    probe("qsv", "h264_qsv", avutil.AV_HWDEVICE_TYPE_QSV),
                    probe("cuda", "h264_cuvid", avutil.AV_HWDEVICE_TYPE_CUDA),
                    probe("vaapi", "h264_vaapi", avutil.AV_HWDEVICE_TYPE_VAAPI),
                    probe("vdpau", "h264_vdpau", avutil.AV_HWDEVICE_TYPE_VDPAU)));
        } catch (Throwable t) {
            LOG.warn("[硬件解码检测] 探测过程抛出异常，回落到软件解码", t);
            return null;
        }
    }

    private static Result logOutcome(Result result) {
        if (result == null) {
            LOG.info("[硬件解码检测] 探测结束：未发现任何可用硬件解码方案，将使用软件解码");
        } else {
            LOG.info("[硬件解码检测] 探测结束：选中 accel={}, decoder={}, deviceType={}",
                    result.accelName(), result.decoderName(), result.deviceType());
        }
        return result;
    }

    private static Result firstAvailable(Result... candidates) {
        for (Result candidate : candidates) {
            if (candidate != null) {
                return candidate;
            }
        }
        return null;
    }

    /** 探测单个方案：解码器存在 + 硬件设备上下文可创建才算可用 */
    private static Result probe(String accelName, String decoderName, int deviceType) {
        LOG.debug("[硬件解码检测] 开始探测方案 accel={}, decoder={}, deviceType={}",
                accelName, decoderName, deviceType);
        try {
            AVCodec codec = avcodec.avcodec_find_decoder_by_name(decoderName);
            if (codec == null) {
                LOG.info("[硬件解码检测] 方案 {} 不可用：avcodec_find_decoder_by_name({}) 返回空（当前 ffmpeg 构建可能未启用该硬件解码器）",
                        accelName, decoderName);
                return null;
            }
            AVBufferRef deviceCtx = new AVBufferRef();
            int ret = avutil.av_hwdevice_ctx_create(deviceCtx, deviceType, (String) null,
                    (AVDictionary) null, 0);
            if (ret < 0 || deviceCtx.isNull()) {
                LOG.info("[硬件解码检测] 方案 {} 不可用：av_hwdevice_ctx_create(deviceType={}) 失败，ret={}（无对应硬件设备或驱动缺失）",
                        accelName, deviceType, ret);
                return null;
            }
            avutil.av_buffer_unref(deviceCtx);
            LOG.info("[硬件解码检测] 方案 {} 可用：decoder={}, deviceType={}", accelName, decoderName, deviceType);
            return new Result(accelName, decoderName, deviceType);
        } catch (Throwable t) {
            LOG.info("[硬件解码检测] 方案 {} 探测异常：{}", accelName, String.valueOf(t));
            return null;
        }
    }

    /** 是否 Windows 平台（os.name 含 win）；包私有便于单测 */
    static boolean isWindows() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("win");
    }
}
