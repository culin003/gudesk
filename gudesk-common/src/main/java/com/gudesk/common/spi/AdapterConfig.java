package com.gudesk.common.spi;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 适配器配置（builder 风格、不可变）：目标工作参数 + 实现自定义扩展项。
 *
 * <p>配置在 {@link Adapter#init(AdapterConfig)} 时传入，由能力协商的结果填充
 * （实际参数不应超出 {@link AdapterCapabilities} 描述的能力上限）。
 *
 * <p>示例：
 * <pre>{@code
 * AdapterConfig config = AdapterConfig.builder()
 *         .width(1920).height(1080).fps(30).pixelFormat("BGRA")
 *         .putExtra("bitrate", "8000k")
 *         .build();
 * }</pre>
 */
public final class AdapterConfig {

    private final int width;
    private final int height;
    private final int fps;
    private final String pixelFormat;
    private final Map<String, String> extra;

    private AdapterConfig(Builder builder) {
        this.width = builder.width;
        this.height = builder.height;
        this.fps = builder.fps;
        this.pixelFormat = builder.pixelFormat;
        this.extra = Collections.unmodifiableMap(new LinkedHashMap<>(builder.extra));
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 目标宽度（像素） */
    public int width() {
        return width;
    }

    /** 目标高度（像素） */
    public int height() {
        return height;
    }

    /** 目标帧率 */
    public int fps() {
        return fps;
    }

    /** 目标像素格式，如 "BGRA"、"I420"、"NV12"、"H264" */
    public String pixelFormat() {
        return pixelFormat;
    }

    /** 扩展配置项（实现自定义，如码率、设备序号等） */
    public Map<String, String> extra() {
        return extra;
    }

    /** 读取单个扩展配置项，不存在时返回 null */
    public String extraValue(String key) {
        return extra.get(key);
    }

    /**
     * 配置构建器。
     */
    public static final class Builder {

        private int width;
        private int height;
        private int fps;
        private String pixelFormat;
        private final Map<String, String> extra = new LinkedHashMap<>();

        public Builder width(int width) {
            this.width = width;
            return this;
        }

        public Builder height(int height) {
            this.height = height;
            return this;
        }

        public Builder fps(int fps) {
            this.fps = fps;
            return this;
        }

        public Builder pixelFormat(String pixelFormat) {
            this.pixelFormat = pixelFormat;
            return this;
        }

        /** 设置扩展配置项 */
        public Builder extra(Map<String, String> extra) {
            Objects.requireNonNull(extra, "extra");
            this.extra.clear();
            this.extra.putAll(extra);
            return this;
        }

        /** 追加单个扩展配置项 */
        public Builder putExtra(String key, String value) {
            Objects.requireNonNull(key, "key");
            this.extra.put(key, value);
            return this;
        }

        public AdapterConfig build() {
            return new AdapterConfig(this);
        }
    }
}
