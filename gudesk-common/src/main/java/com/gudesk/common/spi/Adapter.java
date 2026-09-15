package com.gudesk.common.spi;

/**
 * 适配器统一生命周期接口（屏幕捕获、视频编解码、输入注入、渲染等平台相关能力的 SPI 基础契约）。
 *
 * <p>生命周期顺序：{@link #init(AdapterConfig)} → {@link #start()} → 使用 →
 * {@link #stop()} → {@link #release()}（{@link #close()} 等价于 stop + release）。
 *
 * <p>设计要点：
 * <ul>
 *   <li>适配器不得持有会话状态：实现必须可在多个会话/连接间复用，
 *       与单个会话相关的数据通过方法参数或监听器传递；</li>
 *   <li>{@link #capabilities()} 用于能力协商：会话建立时双方依据能力
 *       （分辨率、帧率、像素格式等）协商出实际工作参数；</li>
 *   <li>出入参中的帧数据统一使用 {@link NativeFrame} 承载的堆外 ByteBuffer，避免热路径上的
 *       堆内/堆外内存拷贝。</li>
 * </ul>
 */
public interface Adapter extends AutoCloseable {

    /**
     * 初始化：分配底层资源、校验配置。仅在生命周期开始时调用一次。
     */
    void init(AdapterConfig config) throws AdapterException;

    /**
     * 启动：进入可工作状态（如开启捕获线程、初始化编解码器上下文）。
     */
    void start() throws AdapterException;

    /**
     * 停止：停止数据流动，应尽量幂等（未启动时调用不报错）。
     */
    void stop() throws AdapterException;

    /**
     * 释放底层资源（native 句柄、线程等）。stop 之后调用，默认空实现。
     */
    default void release() {
    }

    /**
     * 能力描述，用于协商。返回值应保持稳定（不随运行状态变化）。
     */
    AdapterCapabilities capabilities();

    /**
     * 便捷关闭：等价于 stop() + release()。
     */
    @Override
    default void close() {
        stop();
        release();
    }
}
