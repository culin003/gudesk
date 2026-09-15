package com.gudesk.common.spi;

import java.util.function.Consumer;

/**
 * 视频编码适配器 SPI：将原始帧编码为 H.264 码流帧。
 *
 * <p>设计要点：
 * <ul>
 *   <li>入参 frame 为原始像素帧（如 "BGRA"/"I420"），出参 encodedOut 输出的
 *       {@link NativeFrame} 为 H.264 Annex-B 码流帧（pixelFormat="H264"），均为堆外零拷贝；</li>
 *   <li>适配器不得持有会话状态：编码参数在 init 时确定，码率等动态调整通过扩展配置实现；</li>
 *   <li>{@link #capabilities()} 用于能力协商（是否硬编、支持的最大分辨率/帧率与像素格式）。</li>
 * </ul>
 */
public interface VideoEncoder extends Adapter {

    /**
     * 编码一帧。
     *
     * <p>encodedOut 输出的帧可能少于输入帧数（如 B 帧/延迟输出），也可能在编码线程回调；
     * 消费完每帧必须调用 {@link NativeFrame#close()} 释放。
     */
    void encode(NativeFrame frame, Consumer<NativeFrame> encodedOut);

    /**
     * 请求下一个输出帧为关键帧（IDR），用于新会话接入或丢包恢复。
     */
    void requestKeyframe();
}
