package com.gudesk.host.input;

import com.gudesk.common.spi.AdapterCapabilities;
import com.gudesk.common.spi.AdapterConfig;
import com.gudesk.common.spi.AdapterException;
import com.gudesk.common.spi.InputInjector;
import com.gudesk.host.capture.RobotScreenCapturer;
import com.gudesk.host.portal.PortalBackendDetector;
import com.gudesk.host.portal.PortalBackendInfo;
import com.gudesk.host.portal.PortalClient;
import com.gudesk.host.portal.PortalContextHolder;
import com.gudesk.host.portal.PortalException;
import com.gudesk.host.portal.PortalStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Wayland 会话的输入注入实现：经 xdg-desktop-portal RemoteDesktop 的 Notify* 方法
 * 注入，复用 {@link PortalContextHolder} 的共享捆绑会话（与 PortalScreenCapturer
 * 同一授权弹窗、同一 restore token）。
 *
 * <p>权限检查链（每事件依次执行，未通过即丢弃并记日志）：
 * <ol>
 *   <li>会话有效性：租约有效（未释放、未 invalidate）且设备授权未撤销；</li>
 *   <li>设备授权：portal 授权弹窗中用户可能只勾选部分设备（如允许鼠标、拒绝键盘），
 *       {@code Start} 返回的 devices 位掩码是实际授予集——键盘事件在未授权时丢弃；</li>
 *   <li>坐标域：归一化 clamp 0~1（与 Robot 实现一致，见 {@link CoordinateMapper}）；</li>
 *   <li>按钮域：0/1/2 → evdev BTN_*；键码域：AWT VK → evdev KEY_*（见
 *       {@link AwtToEvdevKeycodes}），无映射键丢弃；</li>
 *   <li>Notify* fire-and-forget（D-Bus 调用不等响应）。</li>
 * </ol>
 *
 * <p>平台限制（portal 协议/compositor 行为，本层无法绕过）：
 * <ul>
 *   <li>SAS 保留键（Ctrl+Alt+Del 等）：不可注入（与 Robot/Windows 一致）；</li>
 *   <li>compositor 全局快捷键（锁屏等）部分被 compositor 优先消费，注入可能无效；</li>
 *   <li>用户从系统设置撤销授权后 portal 发送 Closed 信号 → 会话失效 →
 *       注入抛 AdapterException 通知 HostSession 断开（与帧流中断语义一致）。</li>
 * </ul>
 */
public class PortalInputInjector implements InputInjector {

    private static final Logger LOG = LoggerFactory.getLogger(PortalInputInjector.class);

    /** 输入事件处理频率上限（capabilities().maxFps，事件数/秒；D-Bus 往返成本低于 Robot，联测后可调） */
    public static final int MAX_EVENT_RATE = 1000;

    private volatile PortalContextHolder.Lease lease;
    private final AtomicLong droppedEvents = new AtomicLong();

    // ------------------------------------------------------------------
    // 平台选择（Wayland 会话优先本实现，用户显式配置时不干预）
    // ------------------------------------------------------------------

    /**
     * 在 Wayland 会话下将输入注入器 SPI 偏好设置为 Portal 实现（若用户未以系统属性
     * gudesk.adapter.injector 或环境变量 GUDESK_ADAPTER_INJECTOR 显式指定）。
     * 应在 SpiLoader.load(InputInjector.class, "injector", ...) 之前调用。
     */
    public static void preferOnWaylandSession(Map<String, String> env) {
        if (!RobotScreenCapturer.isWaylandSession(env)) {
            return;
        }
        if (System.getProperty("gudesk.adapter.injector") != null) {
            return;
        }
        String envOverride = env.get("GUDESK_ADAPTER_INJECTOR");
        if (envOverride != null && !envOverride.isBlank()) {
            return;
        }
        System.setProperty("gudesk.adapter.injector", PortalInputInjector.class.getName());
    }

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    @Override
    public void init(AdapterConfig config) throws AdapterException {
        init(config, RobotScreenCapturer.currentEnv());
    }

    /** init 的可注入环境版本（测试用；检测语义与 {@link RobotInputInjector} 一致） */
    public void init(AdapterConfig config, Map<String, String> env) throws AdapterException {
        Objects.requireNonNull(config, "config");
        if (!RobotScreenCapturer.isWaylandSession(env)) {
            throw new AdapterException("PortalInputInjector 仅适用于 Wayland 会话（X11 请使用 RobotInputInjector）");
        }
        LOG.info("PortalInputInjector 初始化完成（会话经 PortalContextHolder 共享，start 时建立）");
    }

    @Override
    public void start() throws AdapterException {
        if (lease != null) {
            return;
        }
        try {
            // 复用/建立共享捆绑会话：capturer 先启动时零往返直接复用
            this.lease = PortalContextHolder.getInstance().acquire();
            PortalStream stream = lease.stream();
            LOG.info("Portal 输入注入已就绪: 键盘={}, 指针={}, 流节点={}",
                    lease.isKeyboardGranted(), lease.isPointerGranted(),
                    stream != null ? stream.nodeId() : "无");
            if (!lease.isKeyboardGranted() || !lease.isPointerGranted()) {
                LOG.warn("Portal 授权不完整（键盘={}, 指针={}）: 未授权设备的输入事件将被丢弃",
                        lease.isKeyboardGranted(), lease.isPointerGranted());
            }
        } catch (PortalException e) {
            lease = null;
            throw new AdapterException("Portal 输入注入启动失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void stop() {
        PortalContextHolder.Lease l = lease;
        if (l != null) {
            long dropped = droppedEvents.get();
            if (dropped > 0) {
                LOG.info("停止 Portal 输入注入: 累计丢弃 {} 个未授权/无法映射事件", dropped);
            }
            l.close();
            lease = null;
        }
    }

    @Override
    public void release() {
        stop();
    }

    @Override
    public AdapterCapabilities capabilities() {
        PortalBackendInfo backend = PortalBackendDetector.detectCurrent();
        return AdapterCapabilities.builder()
                .maxWidth(4096)
                .maxHeight(4096)
                .maxFps(MAX_EVENT_RATE)
                .hardwareAccelerated(false)
                // 绝对坐标注入（NotifyPointerMotionAbsolute）取决于后端能力，探测失败 fail-closed
                .absolutePointer(backend.absolutePointerSupported())
                .build();
    }

    // ------------------------------------------------------------------
    // 输入注入（调用线程直接执行；检查链见类注释）
    // ------------------------------------------------------------------

    @Override
    public void injectMouse(double normalizedX, double normalizedY) {
        PortalClient client = requireClient("鼠标移动");
        if (!requirePointer("鼠标移动")) {
            return;
        }
        PortalStream stream = lease.stream();
        if (stream == null) {
            return;
        }
        try {
            client.notifyPointerMotionAbsolute(stream.nodeId(),
                    CoordinateMapper.clamp(normalizedX), CoordinateMapper.clamp(normalizedY));
        } catch (PortalException e) {
            handleInjectFailure("鼠标移动", e);
        }
    }

    @Override
    public void injectMouseButton(int button, boolean pressed, double x, double y) {
        PortalClient client = requireClient("鼠标按键");
        if (!requirePointer("鼠标按键")) {
            return;
        }
        OptionalInt evdevButton = AwtToEvdevKeycodes.evdevButton(button);
        if (evdevButton.isEmpty()) {
            drop("非法鼠标按键 " + button);
            return;
        }
        try {
            // 移动到目标位置再注入按钮（与 Robot 实现语义一致）
            PortalStream stream = lease.stream();
            if (stream != null) {
                client.notifyPointerMotionAbsolute(stream.nodeId(),
                        CoordinateMapper.clamp(x), CoordinateMapper.clamp(y));
            }
            client.notifyPointerButton(evdevButton.getAsInt(), pressed);
        } catch (PortalException e) {
            handleInjectFailure("鼠标按键", e);
        }
    }

    @Override
    public void injectWheel(double deltaX, double deltaY) {
        PortalClient client = requireClient("滚轮");
        if (!requirePointer("滚轮")) {
            return;
        }
        if (deltaX == 0 && deltaY == 0) {
            return;
        }
        try {
            // NotifyPointerAxis 为触摸板风格平滑滚动：dy 正=向上（AWT mouseWheel 同语义）
            client.notifyPointerAxis(deltaX, deltaY);
        } catch (PortalException e) {
            handleInjectFailure("滚轮", e);
        }
    }

    @Override
    public void injectKey(int keyCode, String keyChar, boolean pressed) {
        PortalClient client = requireClient("按键");
        if (!requireKeyboard("按键")) {
            return;
        }
        int code = KeyEventCodes.resolveKeyCode(keyCode, keyChar);
        if (code == 0) {
            drop("无法解析键码 keyCode=" + keyCode);
            return;
        }
        OptionalInt evdev = AwtToEvdevKeycodes.evdevForKeyCode(code);
        boolean withShift = false;
        if (evdev.isEmpty() && keyChar != null && !keyChar.isEmpty()) {
            // 字符型扩展键码（如 '!' 的 VK_EXCLAMATION_MARK）无 evdev 物理键槽位：
            // 分解为 Shift+基础键（US 布局）
            int baseVk = AwtToEvdevKeycodes.shiftedCharBaseVk(keyChar.charAt(0));
            if (baseVk != 0) {
                OptionalInt base = AwtToEvdevKeycodes.evdevForKeyCode(baseVk);
                if (base.isPresent()) {
                    evdev = base;
                    withShift = true;
                }
            }
        }
        if (evdev.isEmpty()) {
            drop("无 evdev 映射的键位 " + KeyEventCodes.nameForCode(code));
            return;
        }
        try {
            if (withShift) {
                // 按下序：Shift→基础键；释放序：基础键→Shift（与真实按键次序一致）
                if (pressed) {
                    client.notifyKeyboardKeycode(AwtToEvdevKeycodes.KEY_LEFTSHIFT, true);
                    client.notifyKeyboardKeycode(evdev.getAsInt(), true);
                } else {
                    client.notifyKeyboardKeycode(evdev.getAsInt(), false);
                    client.notifyKeyboardKeycode(AwtToEvdevKeycodes.KEY_LEFTSHIFT, false);
                }
            } else {
                client.notifyKeyboardKeycode(evdev.getAsInt(), pressed);
            }
        } catch (PortalException e) {
            handleInjectFailure("按键", e);
        }
    }

    // ------------------------------------------------------------------
    // 检查链（第 1/2 步：会话与设备授权）
    // ------------------------------------------------------------------

    /** 检查链第 1 步：会话有效（租约未释放/未失效）；失效时抛 AdapterException 断开媒体管线 */
    private PortalClient requireClient(String event) {
        PortalContextHolder.Lease l = lease;
        if (l == null || !l.isValid()) {
            throw new AdapterException("Portal 会话已失效（输入注入不可用），" + event + " 事件触发会话断开");
        }
        PortalClient client = l.client();
        if (client == null) {
            throw new AdapterException("Portal 会话已失效，" + event + " 事件触发会话断开");
        }
        return client;
    }

    /** 检查链第 2 步：设备授权（弹窗中未勾选的设备事件直接丢弃 + WARN） */
    private boolean requirePointer(String event) {
        PortalContextHolder.Lease l = lease;
        if (l != null && l.isPointerGranted()) {
            return true;
        }
        drop(event + "（指针设备未授权）");
        return false;
    }

    private boolean requireKeyboard(String event) {
        PortalContextHolder.Lease l = lease;
        if (l != null && l.isKeyboardGranted()) {
            return true;
        }
        drop(event + "（键盘设备未授权）");
        return false;
    }

    private void drop(String reason) {
        long total = droppedEvents.incrementAndGet();
        if (total == 1 || total % 100 == 0) {
            LOG.warn("丢弃输入事件: {}（累计 {}）", reason, total);
        }
    }

    /** 注入失败：会话失效类（含 portal 重启后的 Invalid session）触发 invalidate 并断开；
     *  租约已失效时同样断开；其余瞬时失败记 WARN 继续 */
    private void handleInjectFailure(String event, PortalException e) {
        if (e.isSessionInvalid()) {
            // portal 守护进程重启等场景不会发 Closed 信号，状态直接丢失——
            // 以注入错误为准触发失效传播（通知捕获/注入两侧的监听器）
            LOG.warn("Portal 会话已失效（{}）: {}", event, e.getMessage());
            PortalContextHolder.getInstance().invalidate("注入失败: " + e.getMessage());
        }
        PortalContextHolder.Lease l = lease;
        if (e.isSessionInvalid() || l == null || !l.isValid()) {
            throw new AdapterException("Portal 会话已失效，" + event + " 事件触发会话断开: " + e.getMessage(), e);
        }
        LOG.warn("Portal {} 注入失败（会话仍有效，继续）: {}", event, e.getMessage());
    }

    /** 累计丢弃事件数（诊断/测试用） */
    public long droppedEvents() {
        return droppedEvents.get();
    }
}
