package com.gudesk.common.spi;

/**
 * 输入注入适配器 SPI：被控端将主控端的输入事件注入本机。
 *
 * <p>设计要点：
 * <ul>
 *   <li>坐标为归一化值（0~1）或事件自带归一化坐标，实现内部换算为本机像素坐标；</li>
 *   <li>适配器不得持有会话状态；</li>
 *   <li>{@link #capabilities()} 用于能力协商（可注入的键位范围等）。</li>
 * </ul>
 */
public interface InputInjector extends Adapter {

    /**
     * 注入鼠标移动。
     *
     * @param normalizedX 归一化横坐标（0~1）
     * @param normalizedY 归一化纵坐标（0~1）
     */
    void injectMouse(double normalizedX, double normalizedY);

    /**
     * 注入鼠标按键。
     *
     * @param button   按键：0=左键，1=中键，2=右键
     * @param pressed  true=按下，false=释放
     * @param x        归一化横坐标（0~1）
     * @param y        归一化纵坐标（0~1）
     */
    void injectMouseButton(int button, boolean pressed, double x, double y);

    /**
     * 注入滚轮滚动。
     *
     * @param deltaX 横向滚动量
     * @param deltaY 纵向滚动量
     */
    void injectWheel(double deltaX, double deltaY);

    /**
     * 注入键盘事件。
     *
     * @param keyCode 键码（平台相关，如 AWT keyCode）
     * @param keyChar 字符（无对应字符时为空/空白）
     * @param pressed true=按下，false=释放
     */
    void injectKey(int keyCode, String keyChar, boolean pressed);
}
