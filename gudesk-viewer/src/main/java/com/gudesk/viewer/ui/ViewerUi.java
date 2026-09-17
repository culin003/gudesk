package com.gudesk.viewer.ui;

import com.gudesk.viewer.session.SessionUiConnector;
import com.gudesk.viewer.session.ViewerConnectionOrchestrator;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.control.ToolBar;
import javafx.scene.image.Image;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.util.List;

/**
 * 主控端独立窗口（{@code --viewer-only}）：顶部连接工具栏（连接目标 + 密码 + 连接），
 * 中间复用 {@link ViewerSessionView}（视频画布 + 会话工具栏 + 状态栏）。
 *
 * <p>布局纯 Java 代码（不用 FXML）；UI 与逻辑分离——状态与业务入口在
 * {@link UiController}，本类仅做连接入口布局与 {@link SessionUiConnector} 接线。
 *
 * <p>{@code --smoke} 参数：启动 3 秒后自动 Platform.exit（有图形环境下的
 * UI 冒烟模式）。
 */
public class ViewerUi extends Application {

    /** UI 冒烟模式退出延迟（秒） */
    static final double SMOKE_EXIT_DELAY_SECONDS = 3;

    private UiController controller;
    private TextField targetField;
    private PasswordField passwordField;
    private Button connectButton;
    private ViewerSessionView sessionView;
    private SessionUiConnector sessionConnector;

    public ViewerUi() {
    }

    @Override
    public void start(Stage stage) {
        controller = new UiController();

        // 顶部连接工具栏：连接目标 + 密码 + 连接
        targetField = new TextField();
        targetField.setPromptText("被控端 ID 或 ip:port");
        HBox.setHgrow(targetField, Priority.ALWAYS);
        passwordField = new PasswordField();
        passwordField.setPromptText("密码");
        passwordField.setPrefWidth(120);
        connectButton = new Button("连接");
        ToolBar connectBar = new ToolBar(new Label("连接目标:"), targetField,
                new Label("密码:"), passwordField, connectButton);

        // 可复用会话视图（视频画布 + 会话工具栏 + 状态栏）
        sessionView = new ViewerSessionView(controller);

        BorderPane root = new BorderPane();
        // 显式指定内置中文字体：JavaFX 默认 "System" 字体在 Linux 上不回退 CJK
        root.setStyle("-fx-font-family: 'Noto Sans CJK SC';");
        root.setTop(connectBar);
        root.setCenter(sessionView);

        // 会话接线：控制器连接入口 + 输入转发（视频渲染画布在 SessionUiConnector 内绑定）
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
        sessionConnector = new SessionUiConnector(controller, sessionView.videoCanvas(),
                signalingServer, preferRelay);
        controller.setConnectHandler(sessionConnector);
        sessionView.setInputForwarder(sessionConnector);

        // 连接按钮随连接状态禁用/启用（会话视图内部负责断开按钮与状态栏）
        controller.addStateListener(newState -> runOnFx(() ->
                connectButton.setDisable(newState != ConnectionState.DISCONNECTED)));
        connectButton.setOnAction(e ->
                controller.requestConnect(targetField.getText(), passwordField.getText()));

        Scene scene = new Scene(root, 960, 640);
        stage.setTitle("GuDesk 主控端");
        stage.setScene(scene);
        InputStream icon = getClass().getResourceAsStream("/gudesk.png");
        if (icon != null) {
            stage.getIcons().add(new Image(icon));
        }
        stage.show();

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

    /** 原始参数列表取 {@code --option value} 的值（缺省用 defaultValue） */
    private static String rawArgValue(List<String> raw, String option, String defaultValue) {
        int idx = raw.indexOf(option);
        return idx >= 0 && idx + 1 < raw.size() ? raw.get(idx + 1) : defaultValue;
    }

    private static void runOnFx(Runnable action) {
        if (Platform.isFxApplicationThread()) {
            action.run();
        } else {
            Platform.runLater(action);
        }
    }
}
