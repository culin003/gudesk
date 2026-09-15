package com.gudesk.viewer;

import com.gudesk.common.spi.AdapterConfig;
import com.gudesk.common.spi.AdapterException;
import com.gudesk.common.spi.FrameRenderer;
import com.gudesk.common.spi.SpiLoader;
import com.gudesk.common.spi.VideoDecoder;
import com.gudesk.viewer.decode.JavaCvVideoDecoder;
import com.gudesk.viewer.render.JavaFxFrameRenderer;
import com.gudesk.viewer.session.CliSessionRunner;
import com.gudesk.viewer.ui.ViewerUi;
import javafx.application.Application;

import java.util.List;

/**
 * 主控端启动入口。
 *
 * <ul>
 *   <li>默认模式：启动 JavaFX UI（{@link ViewerUi}；{@code --server <host:port>}
 *       指定信令服务器，{@code --prefer-relay} 跳过打洞直连直接中继，均透传 UI）；</li>
 *   <li>{@code --selftest}：加载 decoder/renderer 适配器，验证 javacv 原生库加载与
 *       编解码器上下文初始化成功后打印 OK（无需图形环境）；</li>
 *   <li>{@code --smoke}：启动 UI 3 秒后自动退出（有图形环境下的 UI 冒烟模式）；</li>
 *   <li>{@code --connect <ID|ip:port> --password pwd [--server host:port]
 *       [--prefer-relay] [--auto 秒]}：CLI 连接连调模式（无 UI），纯数字 ID 走
 *       信令编排（UDP 打洞优先，失败回落中继），自动连接、收流统计后自动断开退出
 *       （端到端验证）。</li>
 * </ul>
 */
public class ViewerApp {

    public static void main(String[] args) {
        List<String> argList = args == null ? List.of() : List.of(args);
        if (argList.contains("--selftest")) {
            System.exit(runSelftest());
            return;
        }
        int connectIdx = argList.indexOf("--connect");
        if (connectIdx >= 0) {
            String target = connectIdx + 1 < argList.size() ? argList.get(connectIdx + 1) : null;
            String password = valueOf(argList, "--password");
            String server = valueOf(argList, "--server");
            boolean preferRelay = argList.contains("--prefer-relay");
            int autoSeconds = 5;
            String autoText = valueOf(argList, "--auto");
            if (autoText != null) {
                try {
                    autoSeconds = Integer.parseInt(autoText);
                } catch (NumberFormatException e) {
                    System.out.println("[错误] --auto 参数非法: " + autoText);
                    System.exit(2);
                    return;
                }
            }
            System.exit(CliSessionRunner.run(target, password, autoSeconds, server, preferRelay));
            return;
        }
        // 默认 / --smoke 均启动 UI；--smoke 透传给 ViewerUi 定时自动退出
        Application.launch(ViewerUi.class, argList.toArray(new String[0]));
    }

    private static String valueOf(List<String> args, String option) {
        int idx = args.indexOf(option);
        return idx >= 0 && idx + 1 < args.size() ? args.get(idx + 1) : null;
    }

    /**
     * 自检：SpiLoader 加载 decoder/renderer，执行 init/start/stop 生命周期，
     * 验证 javacv 原生库加载与编解码器上下文初始化成功。
     *
     * @return 0=通过，2=失败
     */
    private static int runSelftest() {
        System.out.println("=== GuDesk Viewer 自检（decoder + renderer 适配器）===");
        // SPI 加载（失败降级到默认实现）
        VideoDecoder decoder = SpiLoader.load(VideoDecoder.class, "decoder",
                JavaCvVideoDecoder::defaultDecoder);
        FrameRenderer renderer = SpiLoader.load(FrameRenderer.class, "renderer",
                JavaFxFrameRenderer::defaultRenderer);
        System.out.println("decoder  = " + decoder.getClass().getName());
        System.out.println("renderer = " + renderer.getClass().getName());

        AdapterConfig config = AdapterConfig.builder()
                .width(1280).height(720).fps(30)
                .pixelFormat(JavaCvVideoDecoder.OUTPUT_PIXEL_FORMAT)
                .build();
        try {
            // 解码器：avcodec_find_decoder + alloc/open + av_parser_init 全部成功
            // 即 javacv 原生库与编解码器上下文初始化成功
            decoder.init(config);
            decoder.start();
            decoder.stop();
            // 渲染器：上下文初始化（画布未绑定，帧渲染由 UI 侧负责）
            renderer.init(config);
            renderer.start();
            renderer.stop();
            System.out.println("javacv 原生库加载与编解码器上下文初始化成功: OK");
            System.out.println("=== 自检通过 ===");
            return 0;
        } catch (AdapterException e) {
            System.out.println("[错误] 自检失败: " + e.getMessage());
            e.printStackTrace(System.out);
            return 2;
        } finally {
            closeQuietly(decoder);
            closeQuietly(renderer);
        }
    }

    private static void closeQuietly(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception e) {
            System.out.println("[警告] 关闭失败: " + e.getMessage());
        }
    }

    private ViewerApp() {
    }
}
