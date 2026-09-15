package com.gudesk.common.spi;

import java.util.function.Consumer;

/**
 * 测试用 SPI 桩实现（仅测试目录使用）：验证 SpiLoader 的发现、指定加载与降级逻辑。
 */
public class DummyCapturer implements ScreenCapturer {

    private Consumer<NativeFrame> listener;
    private volatile boolean inited;
    private volatile boolean started;

    @Override
    public void init(AdapterConfig config) throws AdapterException {
        this.inited = true;
    }

    @Override
    public void start() throws AdapterException {
        this.started = true;
    }

    @Override
    public void stop() throws AdapterException {
        this.started = false;
    }

    @Override
    public AdapterCapabilities capabilities() {
        return AdapterCapabilities.builder()
                .maxWidth(1920)
                .maxHeight(1080)
                .maxFps(30)
                .hardwareAccelerated(false)
                .addSupportedPixelFormat("BGRA")
                .build();
    }

    @Override
    public void setCaptureListener(Consumer<NativeFrame> listener) {
        this.listener = listener;
    }

    public Consumer<NativeFrame> listener() {
        return listener;
    }

    public boolean isInited() {
        return inited;
    }

    public boolean isStarted() {
        return started;
    }
}
