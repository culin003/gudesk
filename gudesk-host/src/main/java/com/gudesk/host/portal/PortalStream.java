package com.gudesk.host.portal;

/**
 * ScreenCast.Start 返回的单个 PipeWire 流（显示器/窗口）。
 *
 * @param nodeId PipeWire 节点 ID（stream 元组首元素；OpenPipeWireRemote 后按此连接流）
 * @param x      流在合成器坐标空间中的 X 位置（position 属性，缺省 0）
 * @param y      流在合成器坐标空间中的 Y 位置（position 属性，缺省 0）
 * @param width  流显示宽度（size 属性，缺省 0）
 * @param height 流显示高度（size 属性，缺省 0）
 */
public record PortalStream(int nodeId, int x, int y, int width, int height) {
}
