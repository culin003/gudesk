package com.gudesk.common.spi;

/**
 * 帧渲染适配器 SPI：主控端将解码后的视频帧绘制到目标表面。
 *
 * <p>设计要点：
 * <ul>
 *   <li>入参帧为 {@link NativeFrame} 承载的堆外 ByteBuffer（零拷贝热路径），
 *       渲染完成后必须调用 {@link NativeFrame#close()} 释放；</li>
 *   <li>适配器不得持有会话状态：渲染目标（画布/表面）在 init 时确定，可跨会话复用；</li>
 *   <li>{@link #capabilities()} 用于能力协商（支持的最大分辨率、像素格式）。</li>
 * </ul>
 */
public interface FrameRenderer extends Adapter {

    /**
     * 设置目标渲染区域大小（渲染表面尺寸变化时调用）。
     */
    void setSurfaceSize(int width, int height);

    /**
     * 渲染一帧。
     *
     * <p>是否必须在 UI 线程调用由具体实现定义，本接口不限定。
     */
    void render(NativeFrame frame);
}
