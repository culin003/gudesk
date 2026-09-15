package com.gudesk.host.portal;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * gudesk-portal-helper 控制消息（与 native/portal-helper/portal_helper.c 的
 * struct gd_ctrl 对应）：Java 侧连接 helper 的控制 socket 后，随 SCM_RIGHTS
 * 辅助数据（PipeWire 连接 fd）一并发送。
 *
 * <p>布局（主机字节序、同机通信，共 24 字节，无填充）：
 * <pre>
 *   偏移 0  : uint32 magic     CTRL_MAGIC
 *   偏移 4  : uint32 version   1
 *   偏移 8  : uint32 node_id   流节点 ID（0 = 不使用）
 *   偏移 12 : uint32 reserved  对齐保留
 *   偏移 16 : uint64 serial    object.serial（0 = 不使用）
 * </pre>
 *
 * @param nodeId 流节点 ID（Start 响应 streams 元组首元素；0 表示不使用）
 * @param serial PipeWire object.serial（0 表示不使用；helper 优先按 serial 定位）
 */
public record PortalControlMessage(int nodeId, long serial) {

    /** 控制消息魔数："GDCT" */
    public static final int CTRL_MAGIC = 0x54434447;
    /** 协议版本 */
    public static final int PROTO_VERSION = 1;
    /** 编码后字节数 */
    public static final int ENCODED_SIZE = 24;

    /** 编码为主机字节序（同机通信，x86/ARM 小端一致）24 字节 */
    public byte[] encode() {
        ByteBuffer buf = ByteBuffer.allocate(ENCODED_SIZE).order(ByteOrder.nativeOrder());
        buf.putInt(CTRL_MAGIC);
        buf.putInt(PROTO_VERSION);
        buf.putInt(nodeId);
        buf.putInt(0); // reserved
        buf.putLong(serial);
        return buf.array();
    }

    /** 从 24 字节编码解码（校验 magic/version），非法时抛 IllegalArgumentException */
    public static PortalControlMessage decode(byte[] encoded) {
        if (encoded == null || encoded.length != ENCODED_SIZE) {
            throw new IllegalArgumentException("控制消息长度非法: "
                    + (encoded == null ? "null" : encoded.length));
        }
        ByteBuffer buf = ByteBuffer.wrap(encoded).order(ByteOrder.nativeOrder());
        int magic = buf.getInt();
        if (magic != CTRL_MAGIC) {
            throw new IllegalArgumentException("控制消息 magic 非法: 0x" + Integer.toHexString(magic));
        }
        int version = buf.getInt();
        if (version != PROTO_VERSION) {
            throw new IllegalArgumentException("控制消息版本不支持: " + version);
        }
        int nodeId = buf.getInt();
        buf.getInt(); // reserved
        long serial = buf.getLong();
        return new PortalControlMessage(nodeId, serial);
    }
}
