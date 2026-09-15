package com.gudesk.common.session;

import com.gudesk.common.proto.GuDeskProto.VideoFrame;

/**
 * 会话事件监听器：主控端/被控端共用的事件回调集合。
 *
 * <p>回调线程：除特别说明外，均在 Netty IO 事件循环线程（或心跳调度线程）上回调，
 * 实现方自行保证线程安全（UI 侧用 Platform.runLater / SwingUtilities.invokeLater 包装）。
 */
public interface SessionEventListener {

    /**
     * 会话状态变化（含 CLOSED；见 {@link TcpSessionEndpoint#setState}）。
     */
    void onStateChange(SessionState newState);

    /**
     * 收到对端视频帧（密文解密后的原始 H.264 编码帧，未解码像素）。
     */
    void onVideoFrame(VideoFrame frame);

    /**
     * 心跳延迟采样（单向延迟估算 = RTT / 2，毫秒）。
     */
    void onLatencySample(long oneWayMs);

    /**
     * 会话错误（协议异常、解密失败等非致命错误，通常伴随连接关闭）。
     */
    void onSessionError(String message);

    /**
     * 会话关闭（对端主动断开 / 心跳超时 / 本端主动关闭）。
     */
    void onClose(String reason);
}
