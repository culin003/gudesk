package com.gudesk.viewer.ui;

import com.gudesk.viewer.input.InputForwarder;
import com.gudesk.viewer.input.ShortcutKey;
import com.gudesk.viewer.input.ViewportMapper;
import javafx.application.Platform;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ToolBar;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;

/**
 * 可复用的主控会话视图（视频画布 + 会话工具栏 + 状态栏 + 输入事件采集）。
 *
 * <p>从 {@link ViewerUi} 抽出：只承载「会话进行中」的界面，不包含连接目标/密码等
 * 「连接入口」（连接入口由独立主控 {@link ViewerUi} 顶部工具栏或合并应用的
 * 首页 ConnectPanel 提供）。输入事件与状态展示依赖注入的 {@link UiController}，
 * 真实转发经 {@link #setInputForwarder} 注入（默认 no-op）。
 */
public class ViewerSessionView extends BorderPane {

    private final UiController controller;
    private final Canvas videoCanvas;
    private final Button disconnectButton;
    private final Label stateLabel;
    private final Label messageLabel;
    private final Label latencyLabel;
    private final Label fpsLabel;
    private InputForwarder inputForwarder = InputForwarder.NOOP;

    public ViewerSessionView(UiController controller) {
        this.controller = controller;

        // 顶部会话工具栏：断开 + 常用快捷键
        disconnectButton = new Button("断开");
        disconnectButton.setDisable(true);
        MenuButton shortcutButton = new MenuButton("快捷键");
        for (ShortcutKey sc : ShortcutKey.values()) {
            MenuItem item = new MenuItem(sc.label());
            item.setOnAction(e -> inputForwarder.sendShortcut(sc.modifiers(), sc.keyCode()));
            shortcutButton.getItems().add(item);
        }
        ToolBar toolBar = new ToolBar(disconnectButton, shortcutButton);

        // 中间视频区域：Canvas 黑底、跟随可用区域缩放（支持窗口调整大小）
        videoCanvas = new Canvas();
        videoCanvas.setFocusTraversable(true);
        StackPane videoPane = new StackPane(videoCanvas);
        videoPane.setStyle("-fx-background-color: black;");
        // Canvas 绑定到容器尺寸会反向锁死 min size，显式归零才能让 Center 随窗口缩小
        videoPane.setMinSize(0, 0);
        videoCanvas.widthProperty().bind(videoPane.widthProperty());
        videoCanvas.heightProperty().bind(videoPane.heightProperty());

        // 底部状态栏：连接状态 + 提示 + 延迟 + 帧率
        stateLabel = new Label(ConnectionState.DISCONNECTED.displayText());
        messageLabel = new Label("");
        latencyLabel = new Label("延迟: -- ms");
        fpsLabel = new Label("帧率: -- fps");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox statusBar = new HBox(12, stateLabel, messageLabel, spacer, latencyLabel, fpsLabel);
        statusBar.setStyle("-fx-padding: 6 10 6 10;");

        setTop(toolBar);
        setCenter(videoPane);
        setBottom(statusBar);

        controller.addStateListener(this::onStateChanged);
        controller.addStatsListener(this::onStatsUpdated);
        controller.addMessageListener(this::onMessage);

        disconnectButton.setOnAction(e -> controller.requestDisconnect());
        wireInputEvents(videoCanvas);
    }

    /** 视频画布（供 {@link com.gudesk.viewer.session.SessionUiConnector} 绑定渲染器） */
    public Canvas videoCanvas() {
        return videoCanvas;
    }

    /** 设置输入转发器（默认 no-op） */
    public void setInputForwarder(InputForwarder forwarder) {
        this.inputForwarder = forwarder == null ? InputForwarder.NOOP : forwarder;
    }

    // ------------------------------------------------------------------
    // 状态/统计/消息展示（回调可能来自非 FX 线程）
    // ------------------------------------------------------------------

    private void onStateChanged(ConnectionState newState) {
        runOnFx(() -> {
            stateLabel.setText(controller.stateDisplayText());
            disconnectButton.setDisable(newState == ConnectionState.DISCONNECTED);
        });
    }

    private void onStatsUpdated(long latencyMs, double fps) {
        runOnFx(() -> {
            latencyLabel.setText(latencyMs >= 0 ? "延迟: " + latencyMs + " ms" : "延迟: -- ms");
            fpsLabel.setText(fps >= 0 ? String.format(java.util.Locale.ROOT, "帧率: %.1f fps", fps)
                    : "帧率: -- fps");
        });
    }

    private void onMessage(String message) {
        runOnFx(() -> messageLabel.setText(message));
    }

    private static void runOnFx(Runnable action) {
        if (Platform.isFxApplicationThread()) {
            action.run();
        } else {
            Platform.runLater(action);
        }
    }

    // ------------------------------------------------------------------
    // 输入事件布线（Canvas → 归一化坐标 → InputForwarder）
    // ------------------------------------------------------------------

    private void wireInputEvents(Canvas canvas) {
        canvas.addEventFilter(MouseEvent.MOUSE_MOVED, e ->
                forwardMouse(e.getX(), e.getY(), (nx, ny) -> inputForwarder.onMouseMove(nx, ny)));
        canvas.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
            canvas.requestFocus();
            forwardMouse(e.getX(), e.getY(), (nx, ny) ->
                    inputForwarder.onMouseButton(toButtonCode(e.getButton()), true, nx, ny));
        });
        canvas.addEventFilter(MouseEvent.MOUSE_RELEASED, e ->
                forwardMouse(e.getX(), e.getY(), (nx, ny) ->
                        inputForwarder.onMouseButton(toButtonCode(e.getButton()), false, nx, ny)));
        canvas.addEventFilter(MouseEvent.MOUSE_DRAGGED, e ->
                forwardMouse(e.getX(), e.getY(), (nx, ny) -> inputForwarder.onMouseMove(nx, ny)));
        canvas.addEventFilter(ScrollEvent.SCROLL, e ->
                inputForwarder.onWheel(e.getDeltaX(), e.getDeltaY()));
        canvas.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            inputForwarder.onKey(e.getCode().getCode(), safeText(e), true);
            e.consume();
        });
        canvas.addEventFilter(KeyEvent.KEY_RELEASED, e -> {
            inputForwarder.onKey(e.getCode().getCode(), safeText(e), false);
            e.consume();
        });
    }

    /** 画布坐标 → 归一化视频坐标后转发（视频尺寸未知时不转发） */
    private void forwardMouse(double canvasX, double canvasY, BiDoubleConsumer action) {
        int videoWidth = controller.videoWidth();
        int videoHeight = controller.videoHeight();
        if (videoWidth <= 0 || videoHeight <= 0) {
            return;
        }
        ViewportMapper.Box box = ViewportMapper.letterbox(videoWidth, videoHeight,
                videoCanvas.getWidth(), videoCanvas.getHeight());
        double[] normalized = ViewportMapper.toNormalized(canvasX, canvasY, box);
        action.accept(normalized[0], normalized[1]);
    }

    private static String safeText(KeyEvent e) {
        String text = e.getText();
        return text == null ? "" : text;
    }

    /** JavaFX MouseButton → 协议按钮编号 */
    static int toButtonCode(javafx.scene.input.MouseButton button) {
        if (button == null) {
            return 0;
        }
        return switch (button) {
            case PRIMARY -> 1;
            case SECONDARY -> 2;
            case MIDDLE -> 3;
            case BACK -> 4;
            case FORWARD -> 5;
            default -> 0;
        };
    }

    @FunctionalInterface
    private interface BiDoubleConsumer {
        void accept(double a, double b);
    }
}
