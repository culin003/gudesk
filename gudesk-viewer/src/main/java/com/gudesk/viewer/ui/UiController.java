package com.gudesk.viewer.ui;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 主控端 UI 控制器：持有连接状态与统计数据，UI（ViewerUi）只做布局与展示，
 * 状态流转与业务入口集中在此（UI 与逻辑分离）。
 *
 * <p>线程模型：状态/统计字段 volatile，监听器回调在调用线程执行（会话侧回调
 * 来自 IO 线程）；监听器（UI 更新）需自行保证线程安全（JavaFX 侧用
 * Platform.runLater 包装）。真实会话经 {@link ConnectHandler} 接入
 * （SessionUiConnector）。
 */
public class UiController {

    private static final Logger LOG = LoggerFactory.getLogger(UiController.class);

    /** 状态变化监听器 */
    public interface StateListener {
        void onStateChanged(ConnectionState newState);
    }

    /** 统计更新监听器（latencyMs/fps 为 -1 表示暂无数据） */
    public interface StatsListener {
        void onStatsUpdated(long latencyMs, double fps);
    }

    /** 消息监听器（状态栏提示等） */
    public interface MessageListener {
        void onMessage(String message);
    }

    /**
     * 真实会话连接处理器（SessionUiConnector 实现）：connect 异步执行，
     * 结果经 updateState / publishMessage 回流。
     */
    public interface ConnectHandler {

        /** 连接目标（ip:port 直连；纯 ID 需信令服务器）与连接策略 */
        void connect(String target, String password, ConnectPolicy policy);

        /** 主动断开当前会话 */
        void disconnect();
    }

    private volatile ConnectionState state = ConnectionState.DISCONNECTED;
    /** 状态栏展示文案覆盖（如 "已连接-直连(UDP)"；null=用枚举默认文案） */
    private volatile String stateDisplayOverride;
    private volatile long latencyMs = -1;
    private volatile double fps = -1;
    /** 视频画面尺寸（渲染链路首帧后更新；0 表示未知，输入事件暂不换算） */
    private volatile int videoWidth;
    private volatile int videoHeight;

    /** 真实会话处理器（null = 会话服务未接入） */
    private volatile ConnectHandler connectHandler;

    private final List<StateListener> stateListeners = new CopyOnWriteArrayList<>();
    private final List<StatsListener> statsListeners = new CopyOnWriteArrayList<>();
    private final List<MessageListener> messageListeners = new CopyOnWriteArrayList<>();

    // ------------------------------------------------------------------
    // 状态与统计
    // ------------------------------------------------------------------

    public ConnectionState state() {
        return state;
    }

    public long latencyMs() {
        return latencyMs;
    }

    public double fps() {
        return fps;
    }

    public int videoWidth() {
        return videoWidth;
    }

    public int videoHeight() {
        return videoHeight;
    }

    /** 更新连接状态并通知监听器（状态栏用枚举默认文案） */
    public void updateState(ConnectionState newState) {
        doUpdateState(newState, null);
    }

    /**
     * 更新连接状态并指定状态栏文案（区分连接模式："已连接-直连(UDP)" /
     * "已连接-直连(TCP)" / "已连接-中继"）。
     */
    public void updateState(ConnectionState newState, String displayText) {
        doUpdateState(newState, Objects.requireNonNull(displayText, "displayText"));
    }

    /** 当前状态栏展示文案（覆盖优先，无覆盖用枚举默认） */
    public String stateDisplayText() {
        String override = stateDisplayOverride;
        return override != null ? override : state.displayText();
    }

    private void doUpdateState(ConnectionState newState, String displayOverride) {
        Objects.requireNonNull(newState, "newState");
        ConnectionState old = state;
        String oldOverride = stateDisplayOverride;
        stateDisplayOverride = displayOverride;
        if (old == newState && Objects.equals(oldOverride, displayOverride)) {
            return;
        }
        state = newState;
        LOG.info("连接状态: {} → {}（{}）", old, newState, stateDisplayText());
        for (StateListener listener : stateListeners) {
            listener.onStateChanged(newState);
        }
    }

    /** 更新延迟/帧率统计并通知监听器 */
    public void updateStats(long latencyMs, double fps) {
        this.latencyMs = latencyMs;
        this.fps = fps;
        for (StatsListener listener : statsListeners) {
            listener.onStatsUpdated(latencyMs, fps);
        }
    }

    /** 更新视频画面尺寸（渲染链路分辨率变化时调用） */
    public void updateVideoSize(int width, int height) {
        this.videoWidth = width;
        this.videoHeight = height;
    }

    /** 发布提示消息 */
    public void publishMessage(String message) {
        LOG.info("UI 消息: {}", message);
        for (MessageListener listener : messageListeners) {
            listener.onMessage(message);
        }
    }

    public void addStateListener(StateListener listener) {
        stateListeners.add(Objects.requireNonNull(listener, "listener"));
    }

    public void addStatsListener(StatsListener listener) {
        statsListeners.add(Objects.requireNonNull(listener, "listener"));
    }

    public void addMessageListener(MessageListener listener) {
        messageListeners.add(Objects.requireNonNull(listener, "listener"));
    }

    // ------------------------------------------------------------------
    // 连接入口
    // ------------------------------------------------------------------

    /** 注入真实会话处理器（ViewerUi 启动时接线 SessionUiConnector） */
    public void setConnectHandler(ConnectHandler handler) {
        this.connectHandler = handler;
    }

    /**
     * 请求连接目标（被控端 ID 或 ip:port）与密码。
     *
     * <p>切换 CONNECTING 后交由 {@link ConnectHandler} 异步完成；
     * 成功/失败经 {@link #updateState} / {@link #publishMessage} 回流。
     */
    public void requestConnect(String target, String password) {
        requestConnect(target, password, ConnectPolicy.AUTO);
    }

    /**
     * 请求连接目标（被控端 ID 或 ip:port）与密码，并指定连接策略。
     */
    public void requestConnect(String target, String password, ConnectPolicy policy) {
        if (target == null || target.isBlank()) {
            publishMessage("请输入连接目标（被控端 ID 或 ip:port）");
            return;
        }
        if (state != ConnectionState.DISCONNECTED) {
            LOG.warn("忽略连接请求: 当前状态为 {}", state);
            return;
        }
        String normalizedTarget = target.trim();
        if (!isValidTarget(normalizedTarget)) {
            publishMessage(String.format(Locale.ROOT, "连接目标格式非法: %s（支持 ID 或 ip:port）", normalizedTarget));
            return;
        }
        ConnectHandler handler = connectHandler;
        if (handler == null) {
            publishMessage("会话服务未接入");
            return;
        }
        updateState(ConnectionState.CONNECTING);
        publishMessage("正在连接 " + normalizedTarget + "（" + policy.label() + "）...");
        handler.connect(normalizedTarget, password, policy);
    }

    /** 请求断开（交由 ConnectHandler；状态经 onClosed 回调回流） */
    public void requestDisconnect() {
        if (state == ConnectionState.DISCONNECTED) {
            return;
        }
        ConnectHandler handler = connectHandler;
        if (handler != null) {
            handler.disconnect();
        } else {
            updateState(ConnectionState.DISCONNECTED);
            publishMessage("已断开");
        }
    }

    /** 简单目标校验：纯 ID（字母数字）或 ip:port */
    static boolean isValidTarget(String target) {
        if (target.matches("[0-9]{1,10}")) {
            return true; // 被控端 ID
        }
        return target.matches("[A-Za-z0-9.\\-]+:[0-9]{1,5}");
    }

    /** 释放内部资源（Application.stop 时调用） */
    public void shutdown() {
        // 无常驻资源（调度器已移至 SessionUiConnector）
    }
}
