package com.gudesk.host.portal;

import com.gudesk.common.spi.AdapterConfig;
import com.gudesk.common.spi.AdapterException;
import com.gudesk.host.input.PortalInputInjector;

import java.awt.event.KeyEvent;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Portal 输入注入真实链路冒烟（联调用，不入常规测试）。三种模式：
 * <ul>
 *   <li>full（默认）：全授权 → 注入键盘输入文本 + 鼠标移动扫掠 + 滚轮，0 丢弃为通过；</li>
 *   <li>pointer-only：弹窗中只勾鼠标不勾键盘 → 键盘事件丢弃（droppedEvents&gt;0）、
 *       文本不出现在编辑器，鼠标仍可动；</li>
 *   <li>revoke：全授权后从系统设置撤销（或 systemctl --user restart
 *       xdg-desktop-portal）→ Closed 信号 → 注入抛 AdapterException 为通过。</li>
 * </ul>
 * 运行方式：见 spec Task 4 SubTask 4.6（java -cp classes+deps 本类 模式）。
 */
public class PortalInputLiveSmoke {

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "full";
        System.out.println(">>> 模式: " + mode);
        PortalInputInjector injector = new PortalInputInjector();
        injector.init(AdapterConfig.builder().build());

        System.out.println(">>> start() 调用：授权对话框应已弹出，请允许…");
        injector.start();
        try (PortalContextHolder.Lease probe = PortalContextHolder.getInstance().acquire()) {
            System.out.printf(">>> 授权状态: 键盘=%b 指针=%b（流节点=%d）%n",
                    probe.isKeyboardGranted(), probe.isPointerGranted(),
                    probe.stream() != null ? probe.stream().nodeId() : -1);
        }

        switch (mode) {
            case "full" -> fullAuthScenario(injector);
            case "pointer-only" -> pointerOnlyScenario(injector);
            case "revoke" -> revokeScenario(injector);
            default -> System.out.println(">>> 未知模式: " + mode + "（可用: full / pointer-only / revoke）");
        }
        injector.stop();
        System.out.println(">>> 已停止（累计丢弃事件: " + injector.droppedEvents() + "）");
    }

    // ------------------------------------------------------------------
    // 场景 1：全授权——键盘文本 + 鼠标扫掠 + 滚轮
    // ------------------------------------------------------------------

    private static void fullAuthScenario(PortalInputInjector injector) throws Exception {
        countdown("请打开一个文本编辑器（如 kate），点击编辑区取得焦点", 8);
        System.out.println(">>> 注入文本: 「Gudesk portal input works!」+ 回车");
        typeText(injector, "Gudesk portal input works!");
        injector.injectKey(KeyEvent.VK_ENTER, "\n", true);
        injector.injectKey(KeyEvent.VK_ENTER, "\n", false);
        Thread.sleep(500);

        System.out.println(">>> 注入鼠标: 屏幕中央水平扫掠（应看到光标移动）");
        mouseSweep(injector);
        Thread.sleep(500);

        System.out.println(">>> 注入滚轮: 向下 3 格 + 向上 3 格（编辑器内容应滚动）");
        injector.injectWheel(0, -3);
        Thread.sleep(300);
        injector.injectWheel(0, 3);
        Thread.sleep(500);

        long dropped = injector.droppedEvents();
        System.out.println(dropped == 0
                ? ">>> 冒烟通过 ✓（0 丢弃；请目视确认编辑器中出现文本、光标移动、内容滚动）"
                : ">>> 冒烟失败：全授权下不应有丢弃，dropped=" + dropped);
    }

    // ------------------------------------------------------------------
    // 场景 2：只授权鼠标——键盘事件丢弃、鼠标仍工作
    // ------------------------------------------------------------------

    private static void pointerOnlyScenario(PortalInputInjector injector) throws Exception {
        countdown("请打开一个文本编辑器（如 kate），点击编辑区取得焦点", 8);
        System.out.println(">>> 注入文本（预期不出现在编辑器，被丢弃）: 「should not appear」");
        typeText(injector, "should not appear");
        Thread.sleep(500);

        System.out.println(">>> 注入鼠标: 水平扫掠（应仍能看到光标移动）");
        mouseSweep(injector);
        Thread.sleep(800);

        long dropped = injector.droppedEvents();
        boolean keyboardDropped = dropped >= "should not appear".length();
        System.out.printf(">>> 键盘丢弃计数: %d（预期 ≥ %d）%n",
                dropped, (long) "should not appear".length());
        System.out.println(keyboardDropped
                ? ">>> 冒烟通过 ✓（键盘被丢弃、鼠标不受影响；请目视确认文本未出现且光标移动）"
                : ">>> 冒烟失败：键盘事件未被丢弃，可能实际授权了键盘");
    }

    // ------------------------------------------------------------------
    // 场景 3：撤销授权——Closed 信号 → invalidate → 注入抛异常
    // ------------------------------------------------------------------

    private static void revokeScenario(PortalInputInjector injector) throws Exception {
        System.out.println(">>> 会话保持中，每 200ms 注入一次鼠标移动…");
        System.out.println(">>> 请现在撤销授权：系统设置中搜索「权限」/「门户」移除 GuDesk 的授权，");
        System.out.println(">>> 或另开终端执行: systemctl --user restart xdg-desktop-portal");
        AtomicBoolean invalidated = new AtomicBoolean();
        PortalContextHolder.getInstance().addInvalidationListener(reason -> {
            System.out.println(">>> [监听器] 会话已失效: " + reason);
            invalidated.set(true);
        });

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
        while (System.nanoTime() < deadline) {
            try {
                injector.injectMouse(0.5, 0.5);
            } catch (AdapterException e) {
                System.out.println(">>> 注入抛出 AdapterException（预期）: " + e.getMessage());
                System.out.println(invalidated.get()
                        ? ">>> 冒烟通过 ✓（Closed 信号 → invalidate → 注入失效）"
                        : ">>> 部分通过：注入已失效但 invalidate 监听器未触发（需排查）");
                return;
            }
            Thread.sleep(200);
        }
        System.out.println(">>> 冒烟失败：90 秒内会话未失效（未撤销授权或 Closed 信号未传播）");
        System.exit(1);
    }

    // ------------------------------------------------------------------
    // 注入序列
    // ------------------------------------------------------------------

    /** 逐字符注入（大写自动带 Shift），每键 40ms 间隔 */
    private static void typeText(PortalInputInjector injector, String text) throws InterruptedException {
        for (char c : text.toCharArray()) {
            String s = String.valueOf(c);
            int code = KeyEvent.getExtendedKeyCodeForChar(c);
            boolean upper = Character.isUpperCase(c);
            if (upper) {
                injector.injectKey(KeyEvent.VK_SHIFT, "", true);
            }
            injector.injectKey(code, s, true);
            injector.injectKey(code, s, false);
            if (upper) {
                injector.injectKey(KeyEvent.VK_SHIFT, "", false);
            }
            Thread.sleep(40);
        }
    }

    /** 归一化 X 0.1→0.9 水平扫掠（y=0.5），20 步 */
    private static void mouseSweep(PortalInputInjector injector) throws InterruptedException {
        for (int i = 0; i <= 20; i++) {
            injector.injectMouse(0.1 + 0.8 * i / 20.0, 0.5);
            Thread.sleep(30);
        }
    }

    /** 倒计时提示（给用户切窗口/操作的时间） */
    private static void countdown(String prompt, int seconds) throws InterruptedException {
        System.out.println(">>> " + prompt);
        for (int i = seconds; i > 0; i--) {
            System.out.println(">>> " + i + "…");
            Thread.sleep(1000);
        }
    }
}
