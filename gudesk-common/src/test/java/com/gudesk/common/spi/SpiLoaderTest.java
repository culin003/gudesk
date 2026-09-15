package com.gudesk.common.spi;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpiLoaderTest {

    private static final String CONFIG_KEY = "capturer";

    @AfterEach
    void cleanup() {
        System.clearProperty("gudesk.adapter." + CONFIG_KEY);
    }

    @Test
    void 默认加载_未指定实现时取ServiceLoader发现的实现() {
        ScreenCapturer capturer = SpiLoader.load(ScreenCapturer.class, CONFIG_KEY);
        assertNotNull(capturer);
        assertEquals(DummyCapturer.class, capturer.getClass());
    }

    @Test
    void 指定实现加载() {
        System.setProperty("gudesk.adapter." + CONFIG_KEY, DummyCapturer.class.getName());
        ScreenCapturer capturer = SpiLoader.load(ScreenCapturer.class, CONFIG_KEY);
        assertEquals(DummyCapturer.class, capturer.getClass());
    }

    @Test
    void 指定不存在类名时降级到默认并可用() {
        System.setProperty("gudesk.adapter." + CONFIG_KEY, "com.gudesk.common.spi.NoSuchCapturer");
        AtomicInteger fallbackInvoked = new AtomicInteger();

        ScreenCapturer capturer = SpiLoader.load(ScreenCapturer.class, CONFIG_KEY, () -> {
            fallbackInvoked.incrementAndGet();
            return new DummyCapturer();
        });

        assertNotNull(capturer);
        assertEquals(DummyCapturer.class, capturer.getClass());
        assertEquals(1, fallbackInvoked.get(), "默认供给器应恰好被调用一次");

        // 降级得到的实现可用：完整生命周期可执行
        DummyCapturer dummy = (DummyCapturer) capturer;
        assertDoesNotThrow(() -> {
            dummy.init(AdapterConfig.builder()
                    .width(1920).height(1080).fps(30).pixelFormat("BGRA")
                    .putExtra("bitrate", "4M")
                    .build());
            dummy.start();
            dummy.setCaptureListener(frame -> { });
            assertNotNull(dummy.capabilities());
            dummy.close(); // stop + release
        });
        assertTrue(dummy.isInited());
        assertFalse(dummy.isStarted(), "close 后应已 stop");
    }

    @Test
    void 无实现且无默认时抛AdapterException() {
        // VideoDecoder 未注册任何实现（META-INF/services 中仅有 ScreenCapturer）
        assertThrows(AdapterException.class, () -> SpiLoader.load(VideoDecoder.class, "decoder"));
    }
}
