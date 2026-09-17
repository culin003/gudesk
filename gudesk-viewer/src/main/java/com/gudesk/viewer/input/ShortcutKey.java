package com.gudesk.viewer.input;

import java.awt.event.KeyEvent;

/**
 * 常用快捷键预设（跨平台：Windows / Linux 常用组合键）。
 *
 * <p>修饰键与主键均为 AWT {@code VK_*} 值，与协议 {@code KeyEvent.key_code} 约定一致
 * （被控端 {@code RobotInputInjector} / {@code PortalInputInjector} 直接按 VK_* 注入）。
 */
public enum ShortcutKey {

    CTRL_C("复制 Ctrl+C", new int[]{KeyEvent.VK_CONTROL}, KeyEvent.VK_C),
    CTRL_V("粘贴 Ctrl+V", new int[]{KeyEvent.VK_CONTROL}, KeyEvent.VK_V),
    CTRL_X("剪切 Ctrl+X", new int[]{KeyEvent.VK_CONTROL}, KeyEvent.VK_X),
    CTRL_A("全选 Ctrl+A", new int[]{KeyEvent.VK_CONTROL}, KeyEvent.VK_A),
    CTRL_Z("撤销 Ctrl+Z", new int[]{KeyEvent.VK_CONTROL}, KeyEvent.VK_Z),
    CTRL_ALT_DEL("任务管理器 Ctrl+Alt+Del", new int[]{KeyEvent.VK_CONTROL, KeyEvent.VK_ALT}, KeyEvent.VK_DELETE),
    CTRL_SHIFT_ESC("任务管理器 Ctrl+Shift+Esc", new int[]{KeyEvent.VK_CONTROL, KeyEvent.VK_SHIFT}, KeyEvent.VK_ESCAPE),
    WIN_L("锁屏 Win+L", new int[]{KeyEvent.VK_WINDOWS}, KeyEvent.VK_L),
    WIN_D("显示桌面 Win+D", new int[]{KeyEvent.VK_WINDOWS}, KeyEvent.VK_D),
    WIN_E("资源管理器 Win+E", new int[]{KeyEvent.VK_WINDOWS}, KeyEvent.VK_E),
    WIN_R("运行 Win+R", new int[]{KeyEvent.VK_WINDOWS}, KeyEvent.VK_R),
    ALT_TAB("切换窗口 Alt+Tab", new int[]{KeyEvent.VK_ALT}, KeyEvent.VK_TAB),
    ALT_F4("关闭窗口 Alt+F4", new int[]{KeyEvent.VK_ALT}, KeyEvent.VK_F4),
    CTRL_ALT_T("终端 Ctrl+Alt+T", new int[]{KeyEvent.VK_CONTROL, KeyEvent.VK_ALT}, KeyEvent.VK_T),
    PRINT_SCREEN("截图 PrintScreen", new int[0], KeyEvent.VK_PRINTSCREEN);

    private final String label;
    private final int[] modifiers;
    private final int keyCode;

    ShortcutKey(String label, int[] modifiers, int keyCode) {
        this.label = label;
        this.modifiers = modifiers;
        this.keyCode = keyCode;
    }

    /** 菜单展示文案 */
    public String label() {
        return label;
    }

    /** 修饰键（按下顺序），AWT VK_* 值 */
    public int[] modifiers() {
        return modifiers;
    }

    /** 主键，AWT VK_* 值 */
    public int keyCode() {
        return keyCode;
    }
}
