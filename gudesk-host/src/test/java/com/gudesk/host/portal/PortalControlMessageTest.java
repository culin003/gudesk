package com.gudesk.host.portal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link PortalControlMessage} 编解码测试（与 portal_helper.c 的 struct gd_ctrl 对应）。
 */
class PortalControlMessageTest {

    @Test
    void 编解码往返_字段保持一致() {
        PortalControlMessage message = new PortalControlMessage(12345, 67890L);

        byte[] encoded = message.encode();
        PortalControlMessage decoded = PortalControlMessage.decode(encoded);

        assertEquals(12345, decoded.nodeId());
        assertEquals(67890L, decoded.serial());
    }

    @Test
    void 编码_固定24字节且布局符合C结构() {
        byte[] encoded = new PortalControlMessage(42, 7L).encode();

        assertEquals(PortalControlMessage.ENCODED_SIZE, encoded.length);
        // 布局校验：偏移 0 magic、4 version、8 node_id、12 reserved、16 serial（主机字节序）
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(encoded)
                .order(java.nio.ByteOrder.nativeOrder());
        assertEquals(PortalControlMessage.CTRL_MAGIC, buf.getInt());
        assertEquals(PortalControlMessage.PROTO_VERSION, buf.getInt());
        assertEquals(42, buf.getInt());
        assertEquals(0, buf.getInt());
        assertEquals(7L, buf.getLong());
    }

    @Test
    void 解码_长度非法时抛异常() {
        assertThrows(IllegalArgumentException.class,
                () -> PortalControlMessage.decode(new byte[23]));
        assertThrows(IllegalArgumentException.class,
                () -> PortalControlMessage.decode(null));
    }

    @Test
    void 解码_magic或版本非法时抛异常() {
        byte[] encoded = new PortalControlMessage(1, 1L).encode();
        encoded[0] ^= 0x1; // 破坏 magic 首字节
        assertThrows(IllegalArgumentException.class, () -> PortalControlMessage.decode(encoded));

        byte[] encoded2 = new PortalControlMessage(1, 1L).encode();
        encoded2[4] = 9; // 破坏 version
        assertThrows(IllegalArgumentException.class, () -> PortalControlMessage.decode(encoded2));
    }

    @Test
    void 全零目标合法_表示不使用该定位方式() {
        PortalControlMessage message = new PortalControlMessage(0, 0L);
        assertArrayEquals(message.encode(), PortalControlMessage.decode(message.encode()).encode());
    }
}
