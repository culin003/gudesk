package com.gudesk.launcher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 用户级开机自启（XDG autostart）开关：写 {@code ~/.config/autostart/gudesk.desktop}。
 *
 * <p>同名用户文件优先于系统级 {@code /etc/xdg/autostart/gudesk.desktop}（deb 安装），
 * {@code Hidden=true} 即禁用。供命令行 {@code --enable/--disable-autostart} 与合并 UI
 * 首页设置项共用。
 */
public final class AutostartController {

    /** deb 安装后的入口路径（autostart desktop 文件 Exec 回退值） */
    private static final String AUTOSTART_EXEC = "/opt/gudesk/bin/gudesk --host-only";
    /** 系统级 XDG autostart 文件（deb 安装） */
    private static final Path SYSTEM_AUTOSTART = Path.of("/etc/xdg/autostart/gudesk.desktop");

    private AutostartController() {
    }

    /** 用户级 autostart 文件（优先于系统级 /etc/xdg/autostart） */
    public static Path userAutostartFile() {
        return Path.of(System.getProperty("user.home"), ".config", "autostart", "gudesk.desktop");
    }

    /** 当前是否开机自启（用户级 Hidden=true 或均无配置时视为关闭） */
    public static boolean isEnabled() {
        Path userFile = userAutostartFile();
        try {
            if (Files.exists(userFile)) {
                return !Files.readString(userFile, StandardCharsets.UTF_8).contains("Hidden=true");
            }
            return Files.exists(SYSTEM_AUTOSTART);
        } catch (IOException e) {
            return false;
        }
    }

    /** 设置开机自启开关（写用户级 desktop 文件） */
    public static void setEnabled(boolean enabled) throws IOException {
        Path file = userAutostartFile();
        String content = "[Desktop Entry]\n"
                + "Type=Application\n"
                + "Name=GuDesk Host Service\n"
                + "Comment=GuDesk 被控服务（用户登录后自动启动）\n"
                + "Exec=" + resolveExec() + "\n"
                + "Terminal=false\n"
                + "X-GNOME-Autostart-enabled=true\n"
                + "Hidden=" + (enabled ? "false" : "true") + "\n";
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    /** 命令行用的自启状态提示文案 */
    public static String statusText() {
        Path userFile = userAutostartFile();
        try {
            if (Files.exists(userFile) && Files.readString(userFile, StandardCharsets.UTF_8).contains("Hidden=true")) {
                return "开机自启：已通过用户设置关闭（" + userFile + "，可运行 gudesk --enable-autostart 重新启用）";
            }
            if (Files.exists(SYSTEM_AUTOSTART) || Files.exists(userFile)) {
                return "开机自启：已随系统启动（XDG autostart，登录后自动运行被控服务；gudesk --disable-autostart 可关闭）";
            }
            return "开机自启：未检测到系统级 XDG autostart 配置（deb 安装后自动配置；可运行 gudesk --enable-autostart 手动启用）";
        } catch (Exception e) {
            return "[警告] 读取自启状态失败: " + e.getMessage();
        }
    }

    /** autostart Exec 解析：优先 /opt/gudesk/bin/gudesk（deb 安装），其次从本类 jar 位置推导 app-image 布局 */
    private static String resolveExec() {
        Path installed = Path.of("/opt/gudesk/bin/gudesk");
        if (Files.isExecutable(installed)) {
            return installed + " --host-only";
        }
        try {
            Path jar = Path.of(AutostartController.class.getProtectionDomain()
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
