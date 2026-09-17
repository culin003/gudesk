package com.gudesk.host.capture;

import com.gudesk.common.spi.AdapterCapabilities;
import com.gudesk.common.spi.AdapterConfig;
import com.gudesk.common.spi.AdapterException;
import com.gudesk.common.spi.NativeFrame;
import com.gudesk.common.spi.ScreenCapturer;
import com.gudesk.host.portal.PortalBackendDetector;
import com.gudesk.host.portal.PortalBackendInfo;
import com.gudesk.host.portal.PortalClient;
import com.gudesk.host.portal.PortalControlMessage;
import com.gudesk.host.portal.PortalContextHolder;
import com.gudesk.host.portal.PortalException;
import com.gudesk.host.portal.PortalSession;
import com.gudesk.host.portal.PortalStream;
import org.freedesktop.dbus.spi.message.ISocketProvider;
import org.freedesktop.dbus.transport.junixsocket.JUnixSocketSocketProvider;
import org.newsclub.net.unix.AFUNIXSocket;
import org.newsclub.net.unix.AFUNIXSocketAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Wayland 会话的屏幕捕获实现：经 xdg-desktop-portal（RemoteDesktop+ScreenCast
 * 捆绑会话）授权后，由 gudesk-portal-helper 子进程消费 PipeWire 流，
 * stdout 二进制帧流转为 {@link NativeFrame}。
 *
 * <p>架构（与 Robot 实现的平台分工）：
 * <ul>
 *   <li>Java 侧 {@link PortalClient} 持有唯一捆绑会话：授权弹窗合一，
 *       输入注入（Notify*，见 Task 4 的 PortalInputInjector）与 restore token
 *       均复用同一会话；</li>
 *   <li>Java 经 ScreenCast.OpenPipeWireRemote 取得 PipeWire 连接 fd，
 *       经控制 unix socket 以 SCM_RIGHTS 移交给 helper；helper 消费流并将
 *       BGRA 帧写 stdout（帧协议见 portal_helper.c 文件头，28 字节帧头）；</li>
 *   <li>帧回调在 helper stdout 读线程（普通平台线程，禁虚拟线程）上同步回调，
 *       消费方负责 {@link NativeFrame#close()}；</li>
 *   <li>portal 流按 damage 驱动（静止画面无帧），无需 FrameDiffDetector；
 *       静止心跳由下游（UDP 可靠层的关键帧请求）按需驱动；</li>
 *   <li>帧缓冲复用池 + 容量 1 交付槽：消费不及时丢旧帧，不反压 helper
 *       （helper 侧亦有队列排空策略，双层保护）。</li>
 * </ul>
 *
 * <p>限制：分辨率由 portal 会话决定（整屏 MONITOR），config 的 width/height
 * 仅作记录不裁剪；单显示器场景（selectSources multiple=false）。
 */
public class PortalScreenCapturer implements ScreenCapturer {

    private static final Logger LOG = LoggerFactory.getLogger(PortalScreenCapturer.class);

    /** 帧读取专用平台线程名 */
    public static final String THREAD_NAME = "gudesk-portal-capturer";
    /** 输出像素格式（helper 统一输出 BGRA） */
    public static final String PIXEL_FORMAT = "BGRA";
    /** 帧缓冲复用池大小 */
    public static final int POOL_SIZE = 3;
    /** 帧头字节数（pts 8 + magic 4 + version 2 + flags 2 + w 4 + h 4 + size 4） */
    public static final int FRAME_HEADER_SIZE = 28;
    /** 帧魔数："GDF1"（与 portal_helper.c 的 FRAME_MAGIC 一致） */
    public static final int FRAME_MAGIC = 0x31464447;
    /** helper 二进制名 */
    public static final String HELPER_BIN = "gudesk-portal-helper";
    /** helper 控制 socket 就绪的等待窗口 */
    private static final Duration HELPER_CONNECT_TIMEOUT = Duration.ofSeconds(3);

    private final ArrayBlockingQueue<ByteBuffer> bufferPool = new ArrayBlockingQueue<>(POOL_SIZE);
    /** 交付槽（容量 1）：监听器异常未消费时保留最新帧、丢弃旧帧 */
    private final ArrayBlockingQueue<NativeFrame> handoff = new ArrayBlockingQueue<>(1);

    private volatile Consumer<NativeFrame> listener;
    private volatile boolean running;
    private volatile Thread readerThread;
    private volatile Process helperProcess;
    /** 共享 portal 会话租约（与 PortalInputInjector 复用同一捆绑会话） */
    private volatile PortalContextHolder.Lease portalLease;

    private int fps;
    private long frameCount;
    private long deliverCount;
    private long dropCount;

    // ------------------------------------------------------------------
    // 平台选择（按运行环境显式决定默认实现，用户显式配置时不干预）
    // ------------------------------------------------------------------

    /**
     * 按运行环境显式选择屏幕捕获默认实现：Wayland 会话选本 Portal 实现，X11 选
     * {@link RobotScreenCapturer}。用户以系统属性 gudesk.adapter.capturer 或环境变量
     * GUDESK_ADAPTER_CAPTURER 显式指定时不干预。
     * 应在 SpiLoader.load(ScreenCapturer.class, "capturer", ...) 之前调用；
     * 显式按环境决定，不依赖 ServiceLoader 注册顺序。
     */
    public static void selectPlatformDefault(Map<String, String> env) {
        if (System.getProperty("gudesk.adapter.capturer") != null) {
            return;
        }
        String envOverride = env.get("GUDESK_ADAPTER_CAPTURER");
        if (envOverride != null && !envOverride.isBlank()) {
            return;
        }
        String impl = RobotScreenCapturer.isWaylandSession(env)
                ? PortalScreenCapturer.class.getName()
                : RobotScreenCapturer.class.getName();
        System.setProperty("gudesk.adapter.capturer", impl);
    }

    // ------------------------------------------------------------------
    // helper 路径解析（纯函数，注入环境便于单测）
    // ------------------------------------------------------------------

    /**
     * 解析 helper 可执行文件路径：系统属性 gudesk.portal.helper &gt; 环境变量
     * GUDESK_PORTAL_HELPER &gt; 常规安装/开发目录探测。
     *
     * @param env 环境变量映射（键：GUDESK_PORTAL_HELPER）
     * @throws AdapterException 未找到可执行文件时
     */
    public static Path resolveHelperPath(Map<String, String> env) throws AdapterException {
        String configured = System.getProperty("gudesk.portal.helper");
        if (configured == null || configured.isBlank()) {
            configured = env.get("GUDESK_PORTAL_HELPER");
        }
        if (configured != null && !configured.isBlank()) {
            Path path = Path.of(configured.trim());
            if (isExecutable(path)) {
                LOG.debug("helper 路径（显式配置）: {}", path);
                return path;
            }
            throw new AdapterException("指定的 portal helper 不存在或不可执行: " + path);
        }
        for (String candidate : helperCandidates()) {
            Path path = Path.of(candidate);
            if (isExecutable(path)) {
                LOG.debug("helper 路径（目录探测）: {}", path);
                return path;
            }
        }
        LOG.warn("未找到 {}: 候选路径均不可用（候选: {}）", HELPER_BIN, helperCandidates());
        throw new AdapterException("未找到 " + HELPER_BIN + "（可安装到 /usr/lib/gudesk/，"
                + "或以 gudesk.portal.helper / GUDESK_PORTAL_HELPER 指定路径）");
    }

    /** 常规候选路径：系统安装位置 + 开发工作目录 + app-image 内 lib/（自包含 tar.gz 部署） */
    private static List<String> helperCandidates() {
        List<String> candidates = new ArrayList<>(List.of(
                "/usr/lib/gudesk/" + HELPER_BIN,
                "/usr/libexec/gudesk/" + HELPER_BIN,
                "/usr/local/lib/gudesk/" + HELPER_BIN));
        String cwd = System.getProperty("user.dir", ".");
        candidates.add(cwd + "/" + HELPER_BIN);
        candidates.add(cwd + "/native/portal-helper/" + HELPER_BIN);
        candidates.add(cwd + "/../native/portal-helper/" + HELPER_BIN);
        // app-image 布局：<image>/lib/app/gudesk-host-*.jar → <image>/lib/gudesk-portal-helper
        try {
            Path jar = Path.of(PortalScreenCapturer.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            candidates.add(jar.getParent().getParent().resolve(HELPER_BIN).toString());
        } catch (Exception ignored) {
            // 非 jar 部署（如 IDE classes 目录）时忽略
        }
        return candidates;
    }

    private static boolean isExecutable(Path path) {
        return Files.isRegularFile(path) && Files.isExecutable(path);
    }

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    @Override
    public void init(AdapterConfig config) throws AdapterException {
        Objects.requireNonNull(config, "config");
        if (!RobotScreenCapturer.isWaylandSession(RobotScreenCapturer.currentEnv())) {
            throw new AdapterException("PortalScreenCapturer 仅适用于 Wayland 会话（X11 请使用 RobotScreenCapturer）");
        }
        // 提前解析 helper 路径：init 阶段即报错，而非授权弹窗之后
        resolveHelperPath(RobotScreenCapturer.currentEnv());
        this.fps = config.fps() > 0 ? config.fps() : 30;
        LOG.info("PortalScreenCapturer 初始化完成: 目标帧率={}（分辨率由 portal 会话决定）", fps);
    }

    @Override
    public void start() throws AdapterException {
        if (running) {
            return;
        }
        PortalContextHolder.Lease lease = null;
        Process process = null;
        try {
            // 1. 取得共享捆绑 portal 会话租约（首个使用者触发授权弹窗，
            //    输入注入器复用同一会话；token 恢复/重试逻辑见 PortalContextHolder）
            PortalContextHolder holder = PortalContextHolder.getInstance();
            lease = holder.acquire();
            PortalSession session = lease.session();
            if (session == null || session.streams().isEmpty()) {
                throw new AdapterException("Portal 会话未返回任何屏幕流");
            }
            PortalStream stream = lease.stream();
            LOG.info("Portal 会话信息: 会话句柄={}, 授权设备=0x{}, 流数={}, "
                            + "流[0]: nodeId={}, 位置=({},{},{}x{}), restoreToken={}, 键盘={}, 指针={}",
                    session.handle() != null ? session.handle() : "未知",
                    String.format("%x", session.devices()),
                    session.streams().size(),
                    stream.nodeId(), stream.x(), stream.y(), stream.width(), stream.height(),
                    session.restoreToken() != null ? "已签发" : "无",
                    lease.isKeyboardGranted(), lease.isPointerGranted());
            PortalClient client = lease.client();
            int pwFd = client.openPipeWireRemote();
            LOG.info("OpenPipeWireRemote 成功: fd={}", pwFd);

            // 2. 拉起 helper 并移交 fd（SCM_RIGHTS）
            Path helper = resolveHelperPath(RobotScreenCapturer.currentEnv());
            Path sockPath = controlSocketPath();
            LOG.info("拉起 helper: {} --fd-socket {} --node {} --max-fps {}",
                    helper, sockPath, stream.nodeId(), fps);
            process = new ProcessBuilder(
                    helper.toString(),
                    "--fd-socket", sockPath.toString(),
                    "--node", Integer.toString(stream.nodeId()),
                    "--max-fps", Integer.toString(fps))
                    .redirectErrorStream(false)
                    .start();
            // helper 的 stderr 异步转发到本进程日志（stdout 为二进制帧流，不混入）
            pumpHelperStderr(process);
            handoffControlMessage(sockPath, new PortalControlMessage(stream.nodeId(), 0L), pwFd);
            LOG.info("控制消息与 PipeWire fd 已移交 helper（SCM_RIGHTS）");

            // 3. 启动帧读取线程
            this.portalLease = lease;
            this.helperProcess = process;
            this.running = true;
            this.frameCount = 0;
            this.deliverCount = 0;
            this.dropCount = 0;
            bufferPool.clear();
            Thread thread = new Thread(this::readerLoop, THREAD_NAME);
            thread.setDaemon(true);
            thread.start();
            this.readerThread = thread;
            LOG.info("Portal 捕获已启动: 流节点 {}, 分辨率由首帧协商", stream.nodeId());
        } catch (PortalException | IOException e) {
            // 失败清理：半启动的 helper 进程与租约（最后一个释放者关闭会话）
            LOG.warn("Portal 捕获启动失败: {}（清理半启动资源）", e.getMessage(), e);
            if (process != null && process.isAlive()) {
                String errTail = drainProcessTail(process);
                if (errTail != null && !errTail.isBlank()) {
                    LOG.warn("helper stderr 末尾输出:\n{}", errTail);
                }
                process.destroyForcibly();
            }
            if (lease != null) {
                lease.close();
            }
            throw new AdapterException("Portal 屏幕捕获启动失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void stop() {
        LOG.info("停止 Portal 捕获: 累计读到 {} 帧, 交付 {} 帧, 丢弃 {} 帧",
                frameCount, deliverCount, dropCount);
        running = false;
        Thread thread = readerThread;
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            readerThread = null;
        }
        Process process = helperProcess;
        if (process != null) {
            process.destroy();
            try {
                if (!process.waitFor(1, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
            helperProcess = null;
        }
        PortalContextHolder.Lease lease = portalLease;
        if (lease != null) {
            lease.close();
            portalLease = null;
        }
        // 丢弃仍未被消费的交付帧
        NativeFrame stale = handoff.poll();
        if (stale != null) {
            stale.close();
        }
    }

    @Override
    public void release() {
        stop();
        bufferPool.clear();
    }

    @Override
    public AdapterCapabilities capabilities() {
        PortalBackendInfo backend = PortalBackendDetector.detectCurrent();
        return AdapterCapabilities.builder()
                .maxWidth(4096)
                .maxHeight(4096)
                .maxFps(60)
                .hardwareAccelerated(false)
                // 授权持久化（restore token）取决于后端能力，探测失败 fail-closed 为 false
                .persistentConsent(backend.persistentSupported())
                .addSupportedPixelFormat(PIXEL_FORMAT)
                .build();
    }

    @Override
    public void setCaptureListener(Consumer<NativeFrame> listener) {
        this.listener = listener;
    }

    // ------------------------------------------------------------------
    // helper 进程交接（fd 经 SCM_RIGHTS 移交）
    // ------------------------------------------------------------------

    /** 启动后台线程持续转发 helper stderr 到本进程日志（helper 日志一律走 stderr） */
    private static void pumpHelperStderr(Process process) {
        Thread pump = new Thread(() -> {
            try (InputStream err = process.getErrorStream()) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = err.read(buf)) >= 0) {
                    if (n > 0) {
                        LOG.info("[portal-helper] {}", new String(buf, 0, n).trim());
                    }
                }
            } catch (IOException e) {
                // helper 退出时管道关闭属正常路径，不打扰
            }
        }, THREAD_NAME + "-stderr");
        pump.setDaemon(true);
        pump.start();
    }

    /** 读取进程 stderr 末尾输出（失败清理时诊断用，最多等 500ms） */
    private static String drainProcessTail(Process process) {
        try (InputStream err = process.getErrorStream()) {
            byte[] buf = new byte[4096];
            StringBuilder sb = new StringBuilder();
            long deadline = System.nanoTime() + 500_000_000L;
            while (sb.length() < 8192 && System.nanoTime() < deadline) {
                int avail = err.available();
                if (avail <= 0) {
                    Thread.sleep(20);
                    continue;
                }
                int n = err.read(buf, 0, Math.min(avail, buf.length));
                if (n <= 0) {
                    break;
                }
                sb.append(new String(buf, 0, n));
            }
            return sb.toString().trim();
        } catch (IOException | InterruptedException e) {
            return null;
        }
    }

    /** 控制 socket 路径：$XDG_RUNTIME_DIR 下（缺省回退 /tmp），进程级唯一 */
    static Path controlSocketPath() {
        String runtimeDir = System.getenv("XDG_RUNTIME_DIR");
        String base = runtimeDir != null && !runtimeDir.isBlank() ? runtimeDir : "/tmp";
        return Path.of(base, "gudesk-portal-" + ProcessHandle.current().pid()
                + "-" + Long.toHexString(System.nanoTime() & 0xFFFFFF) + ".sock");
    }

    /**
     * 连接 helper 的控制 socket 并发送控制消息 + PipeWire fd（SCM_RIGHTS）。
     * 包私有静态以便离线单测（unix socket 对 + 本地 fd 往返）。
     */
    static void handoffControlMessage(Path sockPath, PortalControlMessage message, int rawFd)
            throws IOException {
        ISocketProvider provider = new JUnixSocketSocketProvider();
        java.io.FileDescriptor jfd = provider.createFileDescriptor(rawFd)
                .orElseThrow(() -> new IOException("无法将 fd " + rawFd + " 转换为 java.io.FileDescriptor"));
        long deadline = System.nanoTime() + HELPER_CONNECT_TIMEOUT.toNanos();
        IOException lastError = null;
        int attempts = 0;
        while (System.nanoTime() < deadline) {
            attempts++;
            try (AFUNIXSocket socket = AFUNIXSocket.newInstance()) {
                socket.connect(AFUNIXSocketAddress.of(sockPath));
                socket.setOutboundFileDescriptors(jfd);
                OutputStream out = socket.getOutputStream();
                out.write(message.encode());
                out.flush();
                if (attempts > 1) {
                    LOG.debug("控制 socket 连接第 {} 次尝试成功", attempts);
                }
                return;
            } catch (IOException e) {
                // helper 绑定 socket 存在毫秒级窗口：短暂重试
                lastError = e;
                try {
                    Thread.sleep(50);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("等待 helper 控制 socket 被中断", ie);
                }
            }
        }
        LOG.error("连接 helper 控制 socket 失败（尝试 {} 次，超时 {} ms）: {}，最后错误: {}",
                attempts, HELPER_CONNECT_TIMEOUT.toMillis(), sockPath,
                lastError != null ? lastError.getMessage() : "无");
        throw new IOException("连接 helper 控制 socket 失败: " + sockPath, lastError);
    }

    // ------------------------------------------------------------------
    // 帧读取循环（专用平台线程）
    // ------------------------------------------------------------------

    private void readerLoop() {
        Process process = helperProcess;
        if (process == null) {
            return;
        }
        boolean firstFrame = true;
        try (InputStream raw = process.getInputStream()) {
            InputStream in = new BufferedInputStream(raw, 128 * 1024);
            byte[] header = new byte[FRAME_HEADER_SIZE];
            while (running) {
                if (!readFully(in, header)) {
                    // helper 退出（EOF）：区分正常停止与异常退出
                    if (running && !process.isAlive()) {
                        try {
                            LOG.warn("helper 进程已退出（退出码 {}），帧流中断", process.exitValue());
                        } catch (IllegalThreadStateException e) {
                            LOG.warn("helper 进程状态未知，帧流中断");
                        }
                    }
                    break;
                }
                FrameHeader h = parseFrameHeader(header);
                if (h == null) {
                    LOG.error("帧流格式错误（magic/version/尺寸非法），终止捕获; 原始帧头: {}",
                            hexDump(header));
                    break;
                }
                if (firstFrame) {
                    firstFrame = false;
                    LOG.info("首帧到达: {}x{} (BGRA, {} 字节, pts 偏移 {} ms)",
                            h.width(), h.height(), h.payloadSize(),
                            (System.nanoTime() - h.ptsNs()) / 1_000_000);
                }
                ByteBuffer buffer = acquireBuffer(h.width(), h.height());
                if (buffer == null) {
                    // 消费方持有全部缓冲未归还：跳过本帧负载
                    skipFully(in, h.payloadSize());
                    dropCount++;
                    if (dropCount == 10 || dropCount % 500 == 0) {
                        LOG.warn("帧缓冲池耗尽（消费方未归还），已丢弃 {} 帧", dropCount);
                    }
                    continue;
                }
                byte[] array = buffer.array();
                if (!readFully(in, array, h.payloadSize())) {
                    break;
                }
                frameCount++;
                deliver(buffer, h);
            }
        } catch (IOException e) {
            if (running) {
                LOG.warn("读取 helper 帧流失败（helper 可能已崩溃）: {}", e.getMessage());
            }
        }
        LOG.info("Portal 帧读取线程退出: 读到 {} 帧, 交付 {} 帧, 丢弃 {} 帧",
                frameCount, deliverCount, dropCount);
    }

    /** 帧头解析结果（与 portal_helper.c 的 gd_frame_header 对应） */
    record FrameHeader(long ptsNs, int width, int height, int payloadSize, boolean fullFrame) {
    }

    /** 解析 28 字节帧头（小端），非法返回 null */
    static FrameHeader parseFrameHeader(byte[] header) {
        if (header.length != FRAME_HEADER_SIZE) {
            return null;
        }
        ByteBuffer buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        long ptsNs = buf.getLong();
        int magic = buf.getInt();
        short version = buf.getShort();
        short flags = buf.getShort();
        int width = buf.getInt();
        int height = buf.getInt();
        int payloadSize = buf.getInt();
        if (magic != FRAME_MAGIC || version != 1) {
            return null;
        }
        if (width <= 0 || height <= 0 || payloadSize != width * height * 4) {
            return null;
        }
        return new FrameHeader(ptsNs, width, height, payloadSize, (flags & 0x1) != 0);
    }

    /** 取缓冲：尺寸变化时重建池；池耗尽（消费方未归还）返回 null */
    private ByteBuffer acquireBuffer(int width, int height) {
        int need = width * height * 4;
        ByteBuffer pooled = bufferPool.peek();
        if (pooled != null && pooled.capacity() == need) {
            ByteBuffer buffer = bufferPool.poll();
            if (buffer != null) {
                return buffer;
            }
            return null;
        }
        if (pooled != null) {
            // 分辨率变化：丢弃旧池重建（旧缓冲由消费方 close 后自然被 GC，
            // 此处清空避免归还旧尺寸缓冲造成混用）
            LOG.info("分辨率变化: 缓冲池从 {} 字节重建为 {} 字节（{}x{}）",
                    pooled.capacity(), need, width, height);
            bufferPool.clear();
        }
        return ByteBuffer.allocate(need);
    }

    /** 交付一帧：容量 1 交付槽 + 读线程上同步回调（与 RobotScreenCapturer 同款策略） */
    private void deliver(ByteBuffer buffer, FrameHeader h) {
        Consumer<NativeFrame> consumer = listener;
        if (consumer == null) {
            bufferPool.offer(buffer);
            return;
        }
        buffer.clear();
        NativeFrame frame = new NativeFrame(buffer, h.width(), h.height(), PIXEL_FORMAT,
                System.nanoTime(), 0L, f -> {
                    ByteBuffer bb = f.buffer();
                    bb.clear();
                    bufferPool.offer(bb);
                });
        deliverCount++;
        if (!handoff.offer(frame)) {
            NativeFrame stale = handoff.poll();
            if (stale != null) {
                stale.close();
                dropCount++;
                if (dropCount == 10 || dropCount % 500 == 0) {
                    LOG.warn("交付槽积压（消费方处理慢），累计丢弃 {} 帧", dropCount);
                }
            }
            handoff.offer(frame);
        }
        NativeFrame pending;
        while ((pending = handoff.poll()) != null) {
            try {
                consumer.accept(pending);
            } catch (Throwable t) {
                LOG.warn("捕获监听器异常", t);
            }
        }
    }

    /** 16 进制 dump（帧头诊断用） */
    private static String hexDump(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 3);
        for (byte b : bytes) {
            sb.append(String.format("%02x ", b));
        }
        return sb.toString().trim();
    }

    /** 阻塞读满 len 字节，EOF 返回 false */
    private static boolean readFully(InputStream in, byte[] buf, int len) throws IOException {
        int off = 0;
        while (off < len) {
            int n = in.read(buf, off, len - off);
            if (n < 0) {
                return false;
            }
            off += n;
        }
        return true;
    }

    private static boolean readFully(InputStream in, byte[] buf) throws IOException {
        return readFully(in, buf, buf.length);
    }

    /** 丢弃 len 字节（缓冲耗尽时的丢帧路径） */
    private static void skipFully(InputStream in, long len) throws IOException {
        long skipped = 0;
        while (skipped < len) {
            long n = in.skip(len - skipped);
            if (n < 0) {
                return;
            }
            skipped += n;
        }
    }

    // ------------------------------------------------------------------
    // 统计（诊断用）
    // ------------------------------------------------------------------

    /** 累计读到帧数 */
    public long frameCount() {
        return frameCount;
    }

    /** 累计交付帧数 */
    public long deliverCount() {
        return deliverCount;
    }

    /** 累计丢弃帧数 */
    public long dropCount() {
        return dropCount;
    }
}
