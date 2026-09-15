package com.gudesk.common.spi;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeFrameTest {

    @Test
    void 引用计数与释放回调() {
        AtomicBoolean released = new AtomicBoolean(false);
        ByteBuffer buffer = ByteBuffer.allocateDirect(64);
        NativeFrame frame = new NativeFrame(buffer, 8, 8, "BGRA", 123456L, 42L, f -> released.set(true));

        assertEquals(1, frame.refCount());
        assertEquals(42L, frame.nativeHandle());
        assertEquals("BGRA", frame.pixelFormat());
        assertEquals(8, frame.width());
        assertEquals(8, frame.height());
        assertEquals(123456L, frame.timestampNs());

        // retain 后未全部 close 前不触发释放
        frame.retain();
        frame.close();
        assertFalse(released.get());
        assertEquals(1, frame.refCount());

        // 最后一次 close 触发 Releaser 回调
        frame.close();
        assertTrue(released.get());

        // 超额释放抛异常
        assertThrows(IllegalStateException.class, frame::close);
    }

    @Test
    void 无原生句柄时close不触发回调() {
        AtomicBoolean released = new AtomicBoolean(false);
        NativeFrame frame = new NativeFrame(ByteBuffer.allocateDirect(16), 4, 4, "I420", 1L);
        frame.close();
        assertFalse(released.get());
    }
}
