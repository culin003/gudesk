package com.gudesk.common.spi;

import java.util.function.Consumer;

/**
 * 视频解码适配器 SPI：将 H.264 码流帧解码为可渲染的原始帧。
 *
 * <p>设计要点：
 * <ul>
 *   <li>入参 encoded 为 H.264 Annex-B 码流帧（pixelFormat="H264"），
 *       decodedOut 输出 "I420"/"BGRA" 等原始像素帧，均为堆外零拷贝；</li>
 *   <li>适配器不得持有会话状态：解码上下文只依赖 init 配置，可跨会话复用；</li>
 *   <li>{@link #capabilities()} 用于能力协商（是否硬解、支持的最大分辨率与输出像素格式）。</li>
 * </ul>
 */
public interface VideoDecoder extends Adapter {

    /**
     * 解码一帧 H.264 码流。
     *
     * <p>输出帧数与输入码流帧可能不完全一一对应（解码器内部缓冲/重排）；
     * 消费完每帧必须调用 {@link NativeFrame#close()} 释放。
     */
    void decode(NativeFrame encoded, Consumer<NativeFrame> decodedOut);
}
