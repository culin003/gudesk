package com.gudesk.launcher;

import com.gudesk.host.HostApp;
import com.gudesk.server.ServerApp;
import com.gudesk.viewer.ViewerApp;
import javafx.application.Application;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * GuDesk 合并应用统一入口（打包主类，deb/app-image 的 {@code bin/gudesk} 即本类）。
 *
 * <p>运行模式：
 * <ul>
 *   <li>无参数（默认）：启动单窗口双区合并应用 {@link GuDeskClientApp}（首页左「被控」
 *       右「主控」，连接后切视频会话视图；UI 关闭时进程退出）；</li>
 *   <li>{@code --host-only [args]}：仅被控端（其余参数透传 {@link HostApp}，如
 *       {@code --auto-accept --port 48901 --set-password &lt;pwd&gt;}）；</li>
 *   <li>{@code --viewer-only [args]}：仅主控端（透传 {@link ViewerApp}，如
 *       {@code --connect &lt;ID|ip:port&gt; --password pwd --auto 5}）；</li>
 *   <li>{@code --server-only [args]}：仅服务器（透传 {@link ServerApp}，如
 *       {@code --signaling-port 48900}）；</li>
 *   <li>{@code --disable-autostart} / {@code --enable-autostart}：用户级开机自启开关，
 *       写 {@code ~/.config/autostart/gudesk.desktop}（XDG autostart 规范：同名用户
 *       文件优先于 {@code /etc/xdg/autostart} 系统文件，{@code Hidden=true} 即禁用），
 *       执行后退出；</li>
 *   <li>主控端专属参数（{@code --connect} / {@code --selftest} / {@code --smoke}）
 *       未带模式标志时视为主控端模式（无需后台被控服务）。</li>
 * </ul>
 */
public final class GuDeskLauncher {

    private static final String VERSION = "0.1.0-SNAPSHOT";

    private GuDeskLauncher() {
    }

    public static void main(String[] args) {
        List<String> argList = args == null ? List.of() : List.of(args);

        if (argList.contains("--help") || argList.contains("-h")) {
            printUsage();
            return;
        }
        if (argList.contains("--disable-autostart")) {
            setAutostart(false);
            return;
        }
        if (argList.contains("--enable-autostart")) {
            setAutostart(true);
            return;
        }

        boolean hostOnly = argList.contains("--host-only");
        boolean viewerOnly = argList.contains("--viewer-only");
        boolean serverOnly = argList.contains("--server-only");
        List<String> rest = new ArrayList<>(argList);
        rest.removeAll(List.of("--host-only", "--viewer-only", "--server-only"));
        String[] passThrough = rest.toArray(new String[0]);

        if (serverOnly) {
            ServerApp.main(passThrough);
            return;
        }
        if (hostOnly) {
            printAutostartStatus();
            HostApp.main(passThrough);
            return;
        }
        // 主控端专属参数（无模式标志）→ 仅主控端
        if (viewerOnly || rest.contains("--connect") || rest.contains("--selftest") || rest.contains("--smoke")) {
            ViewerApp.main(passThrough);
            return;
        }

        // 默认合并模式：单窗口双区应用（主控 + 被控）
        printAutostartStatus();
        System.out.printf("GuDesk v%s 合并模式：单窗口（主控 + 被控）%n", VERSION);
        Application.launch(GuDeskClientApp.class, passThrough);
        // UI 关闭后显式退出（Netty/媒体等后台线程即使残留也不阻塞进程退出）
        System.exit(0);
    }

    private static void printUsage() {
        System.out.printf("GuDesk v%s 统一入口%n", VERSION);
        System.out.println("用法: gudesk [模式] [参数...]");
        System.out.println();
        System.out.println("模式:");
        System.out.println("  (无参数)            单窗口双区应用（主控 + 被控，合并默认）");
        System.out.println("  --host-only         仅被控端（参数透传 HostApp: --auto-accept --port N --server host:port --set-password pwd --selftest）");
        System.out.println("  --viewer-only       仅主控端（参数透传 ViewerApp: --connect ID|ip:port --password pwd --server host:port --auto 秒 --prefer-relay --selftest）");
        System.out.println("  --server-only       仅服务器（参数透传 ServerApp: --signaling-port N --stun-port N --relay-port N --selftest）");
        System.out.println("  --disable-autostart 关闭用户级开机自启（~/.config/autostart Hidden=true）");
        System.out.println("  --enable-autostart  开启用户级开机自启");
        System.out.println("  --help              显示本帮助");
        System.out.println();
        System.out.println("适配器覆盖（SPI）: 环境变量 GUDESK_ADAPTER_CAPTURER=<类名>（或 -Dgudesk.adapter.capturer=<类名>）");
    }

    // ------------------------------------------------------------------
    // XDG autostart 用户级开关（逻辑委托 AutostartController）
    // ------------------------------------------------------------------

    private static void setAutostart(boolean enabled) {
        try {
            AutostartController.setEnabled(enabled);
            System.out.println((enabled ? "开机自启已开启" : "开机自启已关闭（Hidden=true，用户级优先于系统级）")
                    + "，写入 " + AutostartController.userAutostartFile());
        } catch (IOException e) {
            System.out.println("[错误] 写入自启设置失败: " + e.getMessage());
        }
    }

    /** 启动被控服务时打印自启状态提示 */
    private static void printAutostartStatus() {
        System.out.println(AutostartController.statusText());
    }
}
