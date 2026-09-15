package com.gudesk.viewer.input;

/**
 * 主控端输入事件转发器：将 UI 采集的鼠标/滚轮/键盘事件（归一化坐标）转发给
 * 会话通道（Task 5 接入真实发送与被控端注入）。
 *
 * <p>坐标约定：{@code nx}/{@code ny} 为 0~1 的归一化视频坐标
 * （相对被控端视频画面左上角，由 {@link ViewportMapper} 从画布坐标换算）。
 */
public interface InputForwarder {

    /** 默认空实现（骨架阶段/无会话时的事件黑洞） */
    InputForwarder NOOP = new InputForwarder() {
        @Override
        public void onMouseMove(double nx, double ny) {
        }

        @Override
        public void onMouseButton(int button, boolean pressed, double nx, double ny) {
        }

        @Override
        public void onWheel(double dx, double dy) {
        }

        @Override
        public void onKey(int keyCode, String keyChar, boolean pressed) {
        }
    };

    /**
     * 鼠标移动。
     *
     * @param nx 归一化 x 坐标（0~1）
     * @param ny 归一化 y 坐标（0~1）
     */
    void onMouseMove(double nx, double ny);

    /**
     * 鼠标按键按下/释放。
     *
     * @param button  按钮编号：1=左键(PRIMARY)、2=右键(SECONDARY)、3=中键(MIDDLE)、4=后退、5=前进、0=其他
     * @param pressed true=按下，false=释放
     * @param nx      归一化 x 坐标（0~1）
     * @param ny      归一化 y 坐标（0~1）
     */
    void onMouseButton(int button, boolean pressed, double nx, double ny);

    /**
     * 滚轮滚动。
     *
     * @param dx 水平滚动增量（像素，右为正）
     * @param dy 垂直滚动增量（像素，下为负——JavaFX ScrollEvent 约定：向上滚 deltaY 为负）
     */
    void onWheel(double dx, double dy);

    /**
     * 键盘按键按下/释放。
     *
     * @param keyCode JavaFX KeyCode 序号（{@code KeyEvent.getCode().getCode()}）
     * @param keyChar 按键字符（无字符输出键为空串）
     * @param pressed true=按下，false=释放
     */
    void onKey(int keyCode, String keyChar, boolean pressed);
}
