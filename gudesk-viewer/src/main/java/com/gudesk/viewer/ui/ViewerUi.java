package com.gudesk.viewer.ui;

import com.gudesk.viewer.input.InputForwarder;
import com.gudesk.viewer.input.ViewportMapper;
import com.gudesk.viewer.session.SessionUiConnector;
import com.gudesk.viewer.session.ViewerConnectionOrchestrator;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.control.ToolBar;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.net.InetSocketAddress;
import java.util.List;

/**
 * 主控端主窗口：顶部工具栏（连接目标输入 + 连接/断开）、中间视频区域
 * （Canvas 黑底居中）、底部状态栏（连接状态/延迟/帧率）。
 *
 * <p>布局纯 Java 代码（不用 FXML）；UI 与逻辑分离——状态与业务入口在
 * {@link UiController}，本类仅做布局、监听与展示。
 *
 * <p>输入事件：Canvas addEventFilter 采集鼠标/滚轮/键盘事件，
 * 经 {@link ViewportMapper} 归一化后转发 {@link InputForwarder}（默认 no-op，
 * Task 5 接入真实转发）。
 *
 * <p>{@code --smoke} 参数：启动 3 秒后自动 Platform.exit（有图形环境下的
 * UI 冒烟模式）。
 */
public class ViewerUi extends Application {

    /** UI 冒烟模式退出延迟（秒） */
    static final double SMOKE_EXIT_DELAY_SECONDS = 3;

    private UiController controller;
    private Canvas videoCanvas;
    private TextField targetField;
    private PasswordField passwordField;
    private Button connectButton;
    private Button disconnectButton;
    private Label stateLabel;
    private Label messageLabel;
    private Label latencyLabel;
    private Label fpsLabel;
    private InputForwarder inputForwarder = InputForwarder.NOOP;
    private SessionUiConnector sessionConnector;

    public ViewerUi() {
    }

    @Override
    public void start(Stage stage) {
        controller = new UiController();

        // 顶部工具栏：连接目标 + 密码 + 连接/断开
        targetField = new TextField();
        targetField.setPromptText("被控端 ID 或 ip:port");
        HBox.setHgrow(targetField, Priority.ALWAYS);
        passwordField = new PasswordField();
        passwordField.setPromptText("密码");
        passwordField.setPrefWidth(120);
        connectButton = new Button("连接");
        disconnectButton = new Button("断开");
        disconnectButton.setDisable(true);
        ToolBar toolBar = new ToolBar(new Label("连接目标:"), targetField,
                new Label("密码:"), passwordField, connectButton, disconnectButton);

        // 中间视频区域：Canvas 黑底、父容器居中
        videoCanvas = new Canvas(640, 480);
        videoCanvas.setFocusTraversable(true);
        fillBlack(videoCanvas);
        StackPane videoPane = new StackPane(videoCanvas);
        videoPane.setStyle("-fx-background-color: black;");

        // 底部状态栏：连接状态 + 提示 + 延迟 + 帧率
        stateLabel = new Label(ConnectionState.DISCONNECTED.displayText());
        messageLabel = new Label("");
        latencyLabel = new Label("延迟: -- ms");
        fpsLabel = new Label("帧率: -- fps");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox statusBar = new HBox(12, stateLabel, messageLabel, spacer, latencyLabel, fpsLabel);
        statusBar.setStyle("-fx-padding: 6 10 6 10;");

        BorderPane root = new BorderPane();
        root.setTop(toolBar);
        root.setCenter(videoPane);
        root.setBottom(statusBar);

        // 控制器监听：状态/统计/消息 → UI（监听回调可能来自调度线程，包装回 FX 线程）
        controller.addStateListener(this::onStateChanged);
        controller.addStatsListener(this::onStatsUpdated);
        controller.addMessageListener(this::onMessage);

        // 会话接线：控制器连接入口 + 输入转发（视频渲染画布在 SessionUiConnector 内绑定）
        // --server <host:port>：信令服务器（ID 连接用）；--prefer-relay：跳过打洞直连直接中继，均透传 UI
        List<String> rawArgs = getParameters().getRaw();
        boolean preferRelay = rawArgs.contains("--prefer-relay");
        InetSocketAddress signalingServer;
        try {
            signalingServer = ViewerConnectionOrchestrator.parseServerAddress(
                    rawArgValue(rawArgs, "--server", "127.0.0.1"));
        } catch (IllegalArgumentException e) {
            System.out.println("[错误] " + e.getMessage());
            Platform.exit();
            return;
        }
        sessionConnector = new SessionUiConnector(controller, videoCanvas,
                signalingServer, preferRelay);
        controller.setConnectHandler(sessionConnector);
        setInputForwarder(sessionConnector);

        connectButton.setOnAction(e ->
                controller.requestConnect(targetField.getText(), passwordField.getText()));
        disconnectButton.setOnAction(e -> controller.requestDisconnect());

        wireInputEvents(videoCanvas);

        Scene scene = new Scene(root, 960, 640);
        stage.setTitle("GuDesk 主控端");
        stage.setScene(scene);
        stage.show();
        videoCanvas.requestFocus();

        // --smoke：启动 3 秒后自动退出（UI 冒烟）
        if (getParameters().getRaw().contains("--smoke")) {
            PauseTransition autoExit = new PauseTransition(Duration.seconds(SMOKE_EXIT_DELAY_SECONDS));
            autoExit.setOnFinished(e -> {
                System.out.println("[smoke] UI 运行 " + SMOKE_EXIT_DELAY_SECONDS + "s，自动退出");
                Platform.exit();
            });
            autoExit.play();
            System.out.println("[smoke] ViewerUi 已启动，" + SMOKE_EXIT_DELAY_SECONDS + "s 后自动退出");
        }
    }

    @Override
    public void stop() {
        if (sessionConnector != null) {
            sessionConnector.shutdown();
        }
        if (controller != null) {
            controller.shutdown();
        }
    }

    // ------------------------------------------------------------------
    // 状态/统计/消息展示（回调可能来自非 FX 线程）
    // ------------------------------------------------------------------

    private void onStateChanged(ConnectionState newState) {
        runOnFx(() -> {
            stateLabel.setText(controller.stateDisplayText());
            connectButton.setDisable(newState != ConnectionState.DISCONNECTED);
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

    /** 原始参数列表取 {@code --option value} 的值（缺省用 defaultValue） */
    private static String rawArgValue(List<String> raw, String option, String defaultValue) {
        int idx = raw.indexOf(option);
        return idx >= 0 && idx + 1 < raw.size() ? raw.get(idx + 1) : defaultValue;
    }

    // ------------------------------------------------------------------
    // 输入事件布线（Canvas → 归一化坐标 → InputForwarder，Task 5 预留）
    // ------------------------------------------------------------------

    /** 设置输入转发器（默认 no-op） */
    public void setInputForwarder(InputForwarder forwarder) {
        this.inputForwarder = forwarder == null ? InputForwarder.NOOP : forwarder;
    }

    private void wireInputEvents(Canvas canvas) {
        canvas.addEventFilter(MouseEvent.MOUSE_MOVED, e ->
                forwardMouse(e.getX(), e.getY(), (nx, ny) -> inputForwarder.onMouseMove(nx, ny)));
        canvas.addEventFilter(MouseEvent.MOUSE_PRESSED, e ->
                forwardMouse(e.getX(), e.getY(), (nx, ny) ->
                        inputForwarder.onMouseButton(toButtonCode(e.getButton()), true, nx, ny)));
        canvas.addEventFilter(MouseEvent.MOUSE_RELEASED, e ->
                forwardMouse(e.getX(), e.getY(), (nx, ny) ->
                        inputForwarder.onMouseButton(toButtonCode(e.getButton()), false, nx, ny)));
        canvas.addEventFilter(MouseEvent.MOUSE_DRAGGED, e ->
                forwardMouse(e.getX(), e.getY(), (nx, ny) -> inputForwarder.onMouseMove(nx, ny)));
        canvas.addEventFilter(ScrollEvent.SCROLL, e ->
                inputForwarder.onWheel(e.getDeltaX(), e.getDeltaY()));
        canvas.addEventFilter(KeyEvent.KEY_PRESSED, e ->
                inputForwarder.onKey(e.getCode().getCode(), safeText(e), true));
        canvas.addEventFilter(KeyEvent.KEY_RELEASED, e ->
                inputForwarder.onKey(e.getCode().getCode(), safeText(e), false));
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

    private static void fillBlack(Canvas canvas) {
        GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.setFill(Color.BLACK);
        gc.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());
    }

    @FunctionalInterface
    private interface BiDoubleConsumer {
        void accept(double a, double b);
    }
}
