package com.gudesk.common.spi;

import java.util.function.Consumer;

/**
 * 屏幕捕获适配器 SPI：被控端持续输出屏幕帧。
 *
 * <p>设计要点：
 * <ul>
 *   <li>出入参帧数据均为 {@link NativeFrame} 承载的堆外 ByteBuffer（零拷贝热路径）；</li>
 *   <li>适配器不得持有会话状态：监听器、帧率策略等随 start/stop 周期管理，
 *       同一实例应可在多个会话间复用；</li>
 *   <li>{@link #capabilities()} 用于能力协商（最大分辨率、帧率、像素格式）。</li>
 * </ul>
 */
public interface ScreenCapturer extends Adapter {

    /**
     * 设置捕获监听器。
     *
     * <p>监听器在捕获专用平台线程上回调（禁虚拟线程），实现不得阻塞该线程做耗时操作；
     * 消费完每帧必须调用 {@link NativeFrame#close()} 释放。
     */
    void setCaptureListener(Consumer<NativeFrame> listener);

    /**
     * 请求立即输出下一帧为完整帧（关键帧）。截图类捕获器无需区分，空实现即可。
     */
    default void requestKeyframe() {
    }
}
