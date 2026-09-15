package com.gudesk.common.spi;

import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 平台帧载体：堆外零拷贝。
 *
 * <p>数据统一放在（优先为 direct 的）{@link ByteBuffer} 中，帧在捕获、编码、解码、
 * 渲染各环节间传递时直接复用同一块堆外内存，避免热路径上的堆内/堆外拷贝。
 *
 * <p>引用计数语义：初始引用计数为 1，跨线程/跨消费者传递前须 {@link #retain()}，
 * 每个持有者用完必须恰好调用一次 {@link #close()}；计数归零时若 nativeHandle != 0，
 * 通过注入的 {@link Releaser} 回调由实现方释放原生帧句柄与底层堆外内存。
 * 超额释放将抛出 {@link IllegalStateException}。
 *
 * <p>注意：实现方必须保证 buffer 在 Releaser 回调触发前不被回收
 * （例如由原生层分配并通过 Cleaner/引用计数统一管理生命周期）。
 */
public final class NativeFrame implements AutoCloseable {

    /**
     * 帧释放回调：实现方在此释放 {@link #nativeHandle()} 指向的原生资源及关联的堆外内存。
     */
    @FunctionalInterface
    public interface Releaser {

        void release(NativeFrame frame);
    }

    private final ByteBuffer buffer;
    private final int width;
    private final int height;
    private final String pixelFormat;
    private final long timestampNs;
    private final long nativeHandle;
    private final Releaser releaser;
    private final AtomicInteger refCount = new AtomicInteger(1);

    /**
     * 完整构造。
     *
     * @param buffer      帧数据缓冲区（建议为 direct ByteBuffer）
     * @param width       宽度（像素）
     * @param height      高度（像素）
     * @param pixelFormat 像素格式，如 "BGRA"、"I420"、"NV12"、"H264"（Annex-B 码流帧）
     * @param timestampNs 捕获/生成时间戳（纳秒）
     * @param nativeHandle 原生帧句柄，纯 Java 实现传 0
     * @param releaser    计数归零时的释放回调，可为 null（buffer 交给 GC 或由分配方管理时）
     */
    public NativeFrame(ByteBuffer buffer, int width, int height, String pixelFormat,
                       long timestampNs, long nativeHandle, Releaser releaser) {
        this.buffer = Objects.requireNonNull(buffer, "buffer");
        this.width = width;
        this.height = height;
        this.pixelFormat = Objects.requireNonNull(pixelFormat, "pixelFormat");
        this.timestampNs = timestampNs;
        this.nativeHandle = nativeHandle;
        this.releaser = releaser;
    }

    /**
     * 便捷构造：无原生句柄、无释放回调（buffer 生命周期交由分配方/GC 管理）。
     */
    public NativeFrame(ByteBuffer buffer, int width, int height, String pixelFormat, long timestampNs) {
        this(buffer, width, height, pixelFormat, timestampNs, 0L, null);
    }

    /** 增加一次引用计数（跨消费者传递前调用），返回自身便于链式使用 */
    public NativeFrame retain() {
        refCount.incrementAndGet();
        return this;
    }

    /** 减少一次引用计数；归零时触发 Releaser 回调释放原生资源 */
    @Override
    public void close() {
        int count = refCount.decrementAndGet();
        if (count == 0) {
            if (releaser != null) {
                releaser.release(this);
            }
        } else if (count < 0) {
            throw new IllegalStateException("NativeFrame 重复释放：nativeHandle=" + nativeHandle);
        }
    }

    /** 帧数据缓冲区（可能为 direct ByteBuffer，消费者不得越界修改） */
    public ByteBuffer buffer() {
        return buffer;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    /** 像素格式："BGRA"/"I420"/"NV12"/"H264" 等 */
    public String pixelFormat() {
        return pixelFormat;
    }

    /** 时间戳（纳秒） */
    public long timestampNs() {
        return timestampNs;
    }

    /** 原生帧句柄（纯 Java 实现为 0） */
    public long nativeHandle() {
        return nativeHandle;
    }

    /** 当前引用计数（主要用于诊断） */
    public int refCount() {
        return refCount.get();
    }

    @Override
    public String toString() {
        return "NativeFrame{" + width + 'x' + height + ", pixelFormat=" + pixelFormat
                + ", timestampNs=" + timestampNs + ", nativeHandle=" + nativeHandle
                + ", refCount=" + refCount.get() + '}';
    }
}
