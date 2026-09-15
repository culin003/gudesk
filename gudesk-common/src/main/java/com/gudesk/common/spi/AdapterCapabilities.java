package com.gudesk.common.spi;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 适配器能力描述（builder 风格、不可变），用于会话建立时的能力协商：
 * 双方依据各自 capabilities 与业务策略推导最终工作参数（分辨率/帧率/像素格式等）。
 *
 * <p>示例：
 * <pre>{@code
 * AdapterCapabilities caps = AdapterCapabilities.builder()
 *         .maxWidth(3840).maxHeight(2160).maxFps(60)
 *         .hardwareAccelerated(true)
 *         .addSupportedPixelFormat("BGRA").addSupportedPixelFormat("I420")
 *         .build();
 * }</pre>
 */
public final class AdapterCapabilities {

    private final int maxWidth;
    private final int maxHeight;
    private final int maxFps;
    private final boolean hardwareAccelerated;
    private final List<String> supportedPixelFormats;
    private final boolean persistentConsent;
    private final boolean absolutePointer;

    private AdapterCapabilities(Builder builder) {
        this.maxWidth = builder.maxWidth;
        this.maxHeight = builder.maxHeight;
        this.maxFps = builder.maxFps;
        this.hardwareAccelerated = builder.hardwareAccelerated;
        this.supportedPixelFormats = List.copyOf(builder.supportedPixelFormats);
        this.persistentConsent = builder.persistentConsent;
        this.absolutePointer = builder.absolutePointer;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 支持的最大宽度（像素） */
    public int maxWidth() {
        return maxWidth;
    }

    /** 支持的最大高度（像素） */
    public int maxHeight() {
        return maxHeight;
    }

    /** 支持的最大帧率 */
    public int maxFps() {
        return maxFps;
    }

    /** 是否硬件加速 */
    public boolean isHardwareAccelerated() {
        return hardwareAccelerated;
    }

    /** 支持的像素格式列表，如 "BGRA"、"I420"、"NV12"、"H264" */
    public List<String> supportedPixelFormats() {
        return supportedPixelFormats;
    }

    /** 授权是否可持久化（如 xdg-desktop-portal 的 restore token 支持） */
    public boolean isPersistentConsent() {
        return persistentConsent;
    }

    /** 输入注入是否支持绝对坐标（如 Portal 的 NotifyPointerMotionAbsolute、Robot 的 mouseMove） */
    public boolean isAbsolutePointer() {
        return absolutePointer;
    }

    @Override
    public String toString() {
        return "AdapterCapabilities{maxWidth=" + maxWidth + ", maxHeight=" + maxHeight
                + ", maxFps=" + maxFps + ", hardwareAccelerated=" + hardwareAccelerated
                + ", supportedPixelFormats=" + supportedPixelFormats
                + ", persistentConsent=" + persistentConsent
                + ", absolutePointer=" + absolutePointer + '}';
    }

    /**
     * 能力构建器。
     */
    public static final class Builder {

        private int maxWidth;
        private int maxHeight;
        private int maxFps;
        private boolean hardwareAccelerated;
        private boolean persistentConsent;
        private boolean absolutePointer;
        private final List<String> supportedPixelFormats = new ArrayList<>();

        public Builder maxWidth(int maxWidth) {
            this.maxWidth = maxWidth;
            return this;
        }

        public Builder maxHeight(int maxHeight) {
            this.maxHeight = maxHeight;
            return this;
        }

        public Builder maxFps(int maxFps) {
            this.maxFps = maxFps;
            return this;
        }

        public Builder hardwareAccelerated(boolean hardwareAccelerated) {
            this.hardwareAccelerated = hardwareAccelerated;
            return this;
        }

        /** 设置授权是否可持久化（restore token 支持） */
        public Builder persistentConsent(boolean persistentConsent) {
            this.persistentConsent = persistentConsent;
            return this;
        }

        /** 设置输入注入是否支持绝对坐标 */
        public Builder absolutePointer(boolean absolutePointer) {
            this.absolutePointer = absolutePointer;
            return this;
        }

        /** 设置支持的像素格式列表 */
        public Builder supportedPixelFormats(List<String> formats) {
            Objects.requireNonNull(formats, "formats");
            this.supportedPixelFormats.clear();
            this.supportedPixelFormats.addAll(formats);
            return this;
        }

        /** 追加一个支持的像素格式（如 "BGRA"、"I420"、"NV12"） */
        public Builder addSupportedPixelFormat(String pixelFormat) {
            Objects.requireNonNull(pixelFormat, "pixelFormat");
            this.supportedPixelFormats.add(pixelFormat);
            return this;
        }

        public AdapterCapabilities build() {
            return new AdapterCapabilities(this);
        }
    }
}
