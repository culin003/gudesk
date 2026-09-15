package com.gudesk.host;

import com.gudesk.common.session.ConnectionGatekeeper;
import com.gudesk.common.spi.AdapterCapabilities;
import com.gudesk.common.spi.AdapterConfig;
import com.gudesk.common.spi.AdapterException;
import com.gudesk.common.spi.NativeFrame;
import com.gudesk.common.spi.ScreenCapturer;
import com.gudesk.common.spi.SpiLoader;
import com.gudesk.common.spi.VideoEncoder;
import com.gudesk.host.capture.PortalScreenCapturer;
import com.gudesk.host.capture.RobotScreenCapturer;
import com.gudesk.host.encode.JavaCvVideoEncoder;
import com.gudesk.host.session.AutoAuthorizer;
import com.gudesk.host.session.Authorizer;
import com.gudesk.host.session.HostPasswordStore;
import com.gudesk.host.session.HostSessionManager;
import com.gudesk.host.session.HostSignalingService;
import com.gudesk.host.session.SwingAuthorizer;
import com.gudesk.host.session.TcpSessionServer;
import com.gudesk.host.session.TrustStore;

import java.awt.GraphicsEnvironment;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 被控端启动入口。
 *
 * <p>运行模式：
 * <ul>
 *   <li>默认：启动 TCP 会话服务 + 信令注册（获得被控 ID，支持主控端纯 ID 连接：
 *       UDP 打洞直连 / TCP 直连并发，失败回落中继），打印本机地址列表与密码提示后
 *       守护运行；密码文件不存在时自动生成随机 6 位数字密码（哈希存储于
 *       {@code ~/.gudesk/password}），明文仅启动时打印/弹窗一次性告知；
 *       未信任连接弹出 Swing 授权确认（显示主控端指纹，30s 超时自动拒绝；
 *       信任列表 {@code ~/.gudesk/trusted_viewers} 命中免确认）；
 *       参数：{@code --server <host:port>}（信令服务器，默认 127.0.0.1:48900，
 *       STUN/中继按同主机默认端口推导）、{@code --port <n>}（TCP 会话监听端口，
 *       默认 {@value TcpSessionServer#DEFAULT_PORT}）、{@code --no-udp}（测试：
 *       跳过打洞直接预连接中继）；</li>
 *   <li>{@code --auto-accept}：默认模式附加参数，跳过授权确认弹窗（无人值守联调测试用）；</li>
 *   <li>{@code --set-password <pwd>}：修改密码（PBKDF2 哈希存储）后退出；</li>
 *   <li>{@code --selftest}：捕获 + 编码链路自检（原逻辑保留）。</li>
 * </ul>
 */
public class HostApp {

    private static final String VERSION = "0.1.0-SNAPSHOT";
    private static final int SELFTEST_FRAMES = 10;
    private static final long SELFTEST_TIMEOUT_SECONDS = 20;

    public static void main(String[] args) {
        if (args != null && args.length > 0) {
            if ("--selftest".equals(args[0])) {
                System.exit(runSelftest());
                return;
            }
            if ("--set-password".equals(args[0])) {
                System.exit(runSetPassword(args));
                return;
            }
        }
        System.exit(runServer(args == null ? new String[0] : args));
    }

    // ------------------------------------------------------------------
    // 默认模式：TCP 会话服务 + 信令注册守护
    // ------------------------------------------------------------------

    /**
     * 启动被控端守护服务。
     *
     * <p>成功时阻塞当前线程直至进程退出（join 自身）；失败返回非 0 退出码。
     * 供 {@code GuDeskLauncher} 合并模式以后台线程调用（失败仅告警，不退出进程）。
     */
    public static int runServer(String[] args) {
        System.out.printf("GuDesk Host v%s, Java: %s, OS: %s%n",
                VERSION,
                System.getProperty("java.version"),
                System.getProperty("os.name"));
        List<String> argList = List.of(args);
        boolean autoAccept = argList.contains("--auto-accept");
        boolean noUdp = argList.contains("--no-udp");
        int tcpPort = intArg(argList, "--port", TcpSessionServer.DEFAULT_PORT);
        InetSocketAddress signalingAddress = parseAddress(
                stringArg(argList, "--server", "127.0.0.1:" + HostSignalingService.DEFAULT_SIGNALING_PORT),
                HostSignalingService.DEFAULT_SIGNALING_PORT);
        if (autoAccept) {
            System.out.println("[警告] --auto-accept 模式：跳过授权确认弹窗（仅供联调测试）");
        }
        if (noUdp) {
            System.out.println("[警告] --no-udp 模式：跳过 UDP 打洞，仅 TCP 直连/中继（仅供联调测试）");
        }
        if (!autoAccept && GraphicsEnvironment.isHeadless()) {
            System.out.println("[警告] 无图形环境（headless）：授权确认将默认拒绝（仅密码验证可用）");
        }

        HostPasswordStore.PasswordRecord passwordRecord;
        try {
            HostPasswordStore.LoadResult loaded = HostPasswordStore.loadOrGenerate();
            passwordRecord = loaded.record();
            if (loaded.newlyGenerated()) {
                String plain = loaded.generatedPlainPassword();
                System.out.println("首次启动已生成随机密码: " + plain + "（仅本次显示，请妥善保存）");
                showPasswordDialogOnce(plain);
            } else {
                System.out.println("密码已设置（存储于 " + HostPasswordStore.DEFAULT_FILE + "）");
            }
        } catch (Exception e) {
            System.out.println("[错误] 密码文件加载失败: " + e.getMessage());
            return 2;
        }

        Authorizer authorizer = autoAccept
                ? new AutoAuthorizer(AutoAuthorizer.Decision.ACCEPT)
                : new SwingAuthorizer();
        // 共享会话管理器：TCP accept / UDP 打洞 / 中继预连接三条接入路径复用单会话槽
        HostSessionManager sessionManager = new HostSessionManager(passwordRecord, authorizer,
                new TrustStore(TrustStore.DEFAULT_FILE),
                new ConnectionGatekeeper(ConnectionGatekeeper.DEFAULT_MAX_FAILS,
                        ConnectionGatekeeper.DEFAULT_LOCK_DURATION));
        TcpSessionServer server = new TcpSessionServer(tcpPort, sessionManager);
        try {
            server.start();
        } catch (Exception e) {
            System.out.println("[错误] 会话服务启动失败: " + e.getMessage());
            return 2;
        }

        // 信令注册（失败降级为仅 ip:port 直连模式）
        HostSignalingService signalingService = new HostSignalingService(sessionManager,
                server::boundPort, signalingAddress, noUdp);
        String hostId = null;
        try {
            signalingService.start();
            hostId = signalingService.assignedId();
        } catch (Exception e) {
            System.out.println("[警告] 信令注册失败（纯 ID 连接不可用，仅支持 ip:port 直连）: "
                    + e.getMessage());
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            signalingService.close();
            server.stop();
        }, "gudesk-host-shutdown"));
        System.out.println("=== GuDesk 被控端就绪，等待主控端连接 ===");
        if (hostId != null) {
            System.out.println("被控 ID: " + hostId + "（主控端可直接输入 ID 连接）");
        }
        System.out.println("TCP 直连端口: " + server.boundPort());
        printLocalAddresses();
        System.out.println("主控端连接格式: <被控 ID> 或 <本机IP>:" + server.boundPort());

        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return 0;
    }

    private static String stringArg(List<String> args, String option, String defaultValue) {
        int idx = args.indexOf(option);
        return idx >= 0 && idx + 1 < args.size() ? args.get(idx + 1) : defaultValue;
    }

    private static int intArg(List<String> args, String option, int defaultValue) {
        String value = stringArg(args, option, null);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            System.out.println("[警告] " + option + " 参数非法（" + value + "），使用默认值 " + defaultValue);
            return defaultValue;
        }
    }

    /** host[:port] → 地址（端口缺省用 defaultPort） */
    private static InetSocketAddress parseAddress(String text, int defaultPort) {
        int colon = text.lastIndexOf(':');
        try {
            if (colon <= 0) {
                return new InetSocketAddress(text, defaultPort);
            }
            return new InetSocketAddress(text.substring(0, colon),
                    Integer.parseInt(text.substring(colon + 1)));
        } catch (NumberFormatException e) {
            System.out.println("[警告] 地址端口非法（" + text + "），使用默认端口 " + defaultPort);
            return new InetSocketAddress(text, defaultPort);
        }
    }

    /** 打印本机非回环 IPv4 地址（多网卡全列） */
    private static void printLocalAddresses() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (!ni.isUp() || ni.isLoopback() || ni.isPointToPoint()) {
                    continue;
                }
                Enumeration<InetAddress> addresses = ni.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        System.out.printf("本机地址: %s (%s)%n", addr.getHostAddress(), ni.getName());
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("[警告] 枚举本机地址失败: " + e.getMessage());
        }
    }

    /** 首次生成密码时弹窗一次性显示（headless 环境跳过） */
    private static void showPasswordDialogOnce(String plain) {
        if (GraphicsEnvironment.isHeadless()) {
            return;
        }
        try {
            javax.swing.JOptionPane.showMessageDialog(null,
                    "GuDesk 首次启动，已生成随机密码：\n\n    " + plain + "\n\n"
                            + "仅本次显示（哈希已存储），请妥善保存。",
                    "GuDesk 被控端",
                    javax.swing.JOptionPane.INFORMATION_MESSAGE);
        } catch (Throwable ignored) {
            // 弹窗失败不影响服务启动
        }
    }

    // ------------------------------------------------------------------
    // --set-password 子命令
    // ------------------------------------------------------------------

    private static int runSetPassword(String[] args) {
        if (args.length < 2 || args[1] == null || args[1].isBlank()) {
            System.out.println("用法: HostApp --set-password <新密码>");
            return 1;
        }
        String plain = args[1].trim();
        try {
            HostPasswordStore.setPassword(plain);
            System.out.println("密码已更新（PBKDF2 哈希存储于 " + HostPasswordStore.DEFAULT_FILE + "）");
            System.out.println("新密码对运行中的会话服务在下一次密码验证时生效需重启进程。");
            return 0;
        } catch (Exception e) {
            System.out.println("[错误] 密码设置失败: " + e.getMessage());
            return 2;
        }
    }

    private static int runSelftest() {
        System.out.println("=== GuDesk Host 自检（capturer + encoder 链路）===");

        // 1. SPI 加载（失败降级到默认实现；Wayland 会话优先 Portal 实现）
        PortalScreenCapturer.preferOnWaylandSession(System.getenv());
        ScreenCapturer capturer = SpiLoader.load(ScreenCapturer.class, "capturer",
                RobotScreenCapturer::defaultCapturer);
        VideoEncoder encoder = SpiLoader.load(VideoEncoder.class, "encoder",
                JavaCvVideoEncoder::defaultEncoder);
        System.out.println("capturer = " + capturer.getClass().getName());
        System.out.println("encoder  = " + encoder.getClass().getName());

        // 2. 图形环境检查（headless 给出明确错误并以 2 退出；Wayland 走 Portal，
        //    授权弹窗可能出现，等待窗口 90s）
        if (RobotScreenCapturer.isWaylandSession(System.getenv())) {
            System.out.println("[提示] Wayland 会话：经 xdg-desktop-portal 捕获，"
                    + "请在弹出的授权对话框中允许 GuDesk");
        }
        if (GraphicsEnvironment.isHeadless()) {
            System.out.println("[错误] 无图形环境（headless 模式），无法执行屏幕捕获自检");
            return 2;
        }

        // 3. 捕获 SELFTEST_FRAMES 帧并编码
        try {
            selftestCaptureEncode(capturer, encoder);
            return 0;
        } catch (AdapterException e) {
            System.out.println("[错误] 自检失败: " + e.getMessage());
            e.printStackTrace(System.out);
            return 2;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.out.println("[错误] 自检被中断");
            return 2;
        } finally {
            closeQuietly(capturer);
            closeQuietly(encoder);
        }
    }

    private static void selftestCaptureEncode(ScreenCapturer capturer, VideoEncoder encoder)
            throws InterruptedException {
        AdapterCapabilities caps = capturer.capabilities();
        int width = Math.max(2, caps.maxWidth());
        int height = Math.max(2, caps.maxHeight());
        int fps = Math.min(30, caps.maxFps());
        AdapterConfig config = AdapterConfig.builder()
                .width(width).height(height).fps(fps)
                .pixelFormat(RobotScreenCapturer.PIXEL_FORMAT)
                .putExtra("bitrate", "4000000")
                .build();
        System.out.printf("自检参数: %dx%d@%dfps, 输出 %s%n",
                width, height, fps, JavaCvVideoEncoder.OUTPUT_PIXEL_FORMAT);

        List<String> frameStats = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(SELFTEST_FRAMES);
        AtomicLong firstFrameNs = new AtomicLong(-1);
        AtomicLong lastFrameNs = new AtomicLong(-1);

        capturer.setCaptureListener(frame -> {
            try {
                long captureNs = System.nanoTime();
                encoder.encode(frame, encoded -> {
                    int size = encoded.buffer().remaining();
                    boolean keyframe = JavaCvVideoEncoder.containsSps(encoded.buffer());
                    frameStats.add(String.format(Locale.ROOT,
                            "%s 帧大小=%d B 关键帧=%b",
                            JavaCvVideoEncoder.OUTPUT_PIXEL_FORMAT, size, keyframe));
                    encoded.close();
                    firstFrameNs.compareAndSet(-1, captureNs);
                    lastFrameNs.set(captureNs);
                    done.countDown();
                });
            } catch (Throwable t) {
                System.out.println("[错误] 编码失败: " + t.getMessage());
                done.countDown();
            } finally {
                frame.close();
            }
        });

        encoder.init(config);
        capturer.init(config);
        encoder.start();
        capturer.start();
        boolean finished = done.await(SELFTEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        capturer.stop();
        encoder.stop();

        System.out.println("--- 编码输出统计 ---");
        for (int i = 0; i < frameStats.size(); i++) {
            System.out.printf("帧 %2d: %s%n", i + 1, frameStats.get(i));
        }
        if (!finished || frameStats.size() < SELFTEST_FRAMES) {
            throw new AdapterException(String.format(Locale.ROOT,
                    "编码帧数不足: %d/%d（超时 %ds）", frameStats.size(), SELFTEST_FRAMES,
                    SELFTEST_TIMEOUT_SECONDS));
        }
        long elapsedMs = Math.max(1, (lastFrameNs.get() - firstFrameNs.get()) / 1_000_000L);
        System.out.printf("实测编码帧率: %.1f fps（%d 帧 / %d ms）%n",
                (frameStats.size() - 1) * 1000.0 / elapsedMs, frameStats.size(), elapsedMs);
        System.out.println("=== 自检通过 ===");
    }

    private static void closeQuietly(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception e) {
            System.out.println("[警告] 关闭失败: " + e.getMessage());
        }
    }
}
