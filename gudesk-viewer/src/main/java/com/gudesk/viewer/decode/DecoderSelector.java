package com.gudesk.viewer.decode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 解码器自动选择入口：在 {@code SpiLoader.load(VideoDecoder.class, "decoder", ...)} 之前调用
 * {@link #selectPlatformDefault()}，按运行环境自动选择硬件/软件解码，语义与 host 端
 * {@code PortalScreenCapturer.selectPlatformDefault} 一致。
 *
 * <ul>
 *   <li>已显式指定（系统属性 {@code gudesk.adapter.decoder} 或环境变量
 *       {@code GUDESK_ADAPTER_DECODER}）则不干预；</li>
 *   <li>否则探测硬件解码（{@link HardwareDecoderDetector#detect()}）：有则设置
 *       {@code gudesk.adapter.decoder=JavaCvHardwareVideoDecoder}；无则不设置，由
 *       ServiceLoader 默认取软解 {@link JavaCvVideoDecoder}。</li>
 * </ul>
 */
public final class DecoderSelector {

    private static final Logger LOG = LoggerFactory.getLogger(DecoderSelector.class);

    private static final String DECODER_PROPERTY = "gudesk.adapter.decoder";
    private static final String DECODER_ENV = "GUDESK_ADAPTER_DECODER";

    private DecoderSelector() {
    }

    /** 按环境自动选择解码实现（显式指定优先；无硬件回落软解） */
    public static void selectPlatformDefault() {
        String propertyOverride = System.getProperty(DECODER_PROPERTY);
        if (propertyOverride != null) {
            LOG.info("[解码器选择] 检测到系统属性 {}={}，跳过自动检测（用户显式指定，最终以该实现为准）",
                    DECODER_PROPERTY, propertyOverride);
            return;
        }
        String envOverride = System.getenv(DECODER_ENV);
        if (envOverride != null && !envOverride.isBlank()) {
            LOG.info("[解码器选择] 检测到环境变量 {}={}，跳过自动检测（用户显式指定，最终以该实现为准）",
                    DECODER_ENV, envOverride);
            return;
        }

        LOG.info("[解码器选择] 未显式指定解码器，开始自动检测硬件解码方案 ...");
        HardwareDecoderDetector.Result hw = HardwareDecoderDetector.detect();
        if (hw != null) {
            System.setProperty(DECODER_PROPERTY, JavaCvHardwareVideoDecoder.class.getName());
            LOG.info("[解码器选择] 自动选择硬件解码：accel={}, decoder={}, deviceType={}；已设置 {}={}",
                    hw.accelName(), hw.decoderName(), hw.deviceType(),
                    DECODER_PROPERTY, JavaCvHardwareVideoDecoder.class.getName());
        } else {
            LOG.info("[解码器选择] 未检测到可用硬件解码，回落到软件解码 {}（不设置属性，由 ServiceLoader 默认选取）",
                    JavaCvVideoDecoder.class.getName());
        }
    }
}
