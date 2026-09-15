package com.gudesk.launcher;

import com.gudesk.host.HostApp;
import com.gudesk.server.ServerApp;
import com.gudesk.viewer.ViewerApp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * GuDesk 合并应用统一入口（打包主类，deb/app-image 的 {@code bin/gudesk} 即本类）。
 *
 * <p>运行模式：
 * <ul>
 *   <li>无参数（默认）：启动主控端 JavaFX UI + 后台被控服务（UI 关闭时进程退出，
 *       被控服务随 shutdown hook 优雅关闭）；</li>
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

    /** deb 安装后的入口路径（autostart desktop 文件 Exec 回退值） */
    private static final String AUTOSTART_EXEC = "/opt/gudesk/bin/gudesk --host-only";
    /** 系统级 XDG autostart 文件（deb 安装） */
    private static final Path SYSTEM_AUTOSTART = Path.of("/etc/xdg/autostart/gudesk.desktop");

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

        // 默认合并模式：后台被控服务（失败不拖垮 UI）+ 主控端 UI
        printAutostartStatus();
        System.out.printf("GuDesk v%s 合并模式：主控端 UI + 后台被控服务%n", VERSION);
        Thread hostService = new Thread(() -> {
            int code = HostApp.runServer(passThrough);
            if (code != 0) {
                System.out.println("[警告] 后台被控服务启动失败（退出码 " + code + "），主控端 UI 继续运行");
            }
        }, "gudesk-host-service");
        hostService.start();
        ViewerApp.main(passThrough);
        // UI 关闭后显式退出（被控服务的 Netty 非守护线程会阻止 JVM 自然退出）
        System.exit(0);
    }

    private static void printUsage() {
        System.out.printf("GuDesk v%s 统一入口%n", VERSION);
        System.out.println("用法: gudesk [模式] [参数...]");
        System.out.println();
        System.out.println("模式:");
        System.out.println("  (无参数)            主控端 UI + 后台被控服务（合并默认）");
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
    // XDG autostart 用户级开关
    // ------------------------------------------------------------------

    /** 用户级 autostart 文件（优先于系统级 /etc/xdg/autostart） */
    private static Path userAutostartFile() {
        return Path.of(System.getProperty("user.home"), ".config", "autostart", "gudesk.desktop");
    }

    private static void setAutostart(boolean enabled) {
        Path file = userAutostartFile();
        String content = "[Desktop Entry]\n"
                + "Type=Application\n"
                + "Name=GuDesk Host Service\n"
                + "Comment=GuDesk 被控服务（用户登录后自动启动）\n"
                + "Exec=" + resolveAutostartExec() + "\n"
                + "Terminal=false\n"
                + "X-GNOME-Autostart-enabled=true\n"
                + "Hidden=" + (enabled ? "false" : "true") + "\n";
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content, StandardCharsets.UTF_8);
            System.out.println((enabled ? "开机自启已开启" : "开机自启已关闭（Hidden=true，用户级优先于系统级）")
                    + "，写入 " + file);
        } catch (IOException e) {
            System.out.println("[错误] 写入自启设置失败: " + e.getMessage());
        }
    }

    /** 启动被控服务时打印自启状态提示 */
    private static void printAutostartStatus() {
        try {
            Path userFile = userAutostartFile();
            if (Files.exists(userFile) && Files.readString(userFile).contains("Hidden=true")) {
                System.out.println("开机自启：已通过用户设置关闭（" + userFile + "，可运行 gudesk --enable-autostart 重新启用）");
                return;
            }
            if (Files.exists(SYSTEM_AUTOSTART) || Files.exists(userFile)) {
                System.out.println("开机自启：已随系统启动（XDG autostart，登录后自动运行被控服务；gudesk --disable-autostart 可关闭）");
            } else {
                System.out.println("开机自启：未检测到系统级 XDG autostart 配置（deb 安装后自动配置；可运行 gudesk --enable-autostart 手动启用）");
            }
        } catch (Exception e) {
            System.out.println("[警告] 读取自启状态失败: " + e.getMessage());
        }
    }

    /** autostart Exec 解析：优先 /opt/gudesk/bin/gudesk（deb 安装），其次从本类 jar 位置推导 app-image 布局 */
    private static String resolveAutostartExec() {
        Path installed = Path.of("/opt/gudesk/bin/gudesk");
        if (Files.isExecutable(installed)) {
            return installed + " --host-only";
        }
        try {
            Path jar = Path.of(GuDeskLauncher.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            // app-image 布局: <image>/lib/app/gudesk-launcher.jar → <image>/bin/gudesk
            Path bin = jar.getParent().getParent().getParent().resolve("bin/gudesk");
            if (Files.isExecutable(bin)) {
                return bin + " --host-only";
            }
        } catch (Exception ignored) {
            // 回退到安装前缀常量
        }
        return AUTOSTART_EXEC;
    }
}
