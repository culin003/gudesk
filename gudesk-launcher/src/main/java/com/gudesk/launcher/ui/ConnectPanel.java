package com.gudesk.launcher.ui;

import com.gudesk.viewer.ui.ConnectionState;
import com.gudesk.viewer.ui.ConnectPolicy;
import com.gudesk.viewer.ui.UiController;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

/**
 * 首页右区「主控」连接面板：连接目标 + 密码 + 连接按钮。
 *
 * <p>连接入口委托 {@link UiController#requestConnect}；按钮禁用状态随连接状态流转。
 */
public class ConnectPanel extends VBox {

    private final TextField targetField;
    private final PasswordField passwordField;
    private final ComboBox<ConnectPolicy> policyBox;
    private final Button connectButton;

    public ConnectPanel(UiController controller, ConnectPolicy initialPolicy) {
        Label title = new Label("远程控制");
        title.setStyle("-fx-font-weight: bold; -fx-font-size: 14;");

        targetField = new TextField();
        targetField.setPromptText("被控端 ID 或 ip:port");
        passwordField = new PasswordField();
        passwordField.setPromptText("密码");
        policyBox = new ComboBox<>();
        policyBox.getItems().addAll(ConnectPolicy.values());
        policyBox.setValue(initialPolicy);
        connectButton = new Button("连接");

        connectButton.setOnAction(e ->
                controller.requestConnect(targetField.getText(), passwordField.getText(),
                        policyBox.getValue()));
        controller.addStateListener(newState -> runOnFx(() ->
                connectButton.setDisable(newState != ConnectionState.DISCONNECTED)));

        setSpacing(10);
        setPadding(new Insets(16));
        setStyle("-fx-border-color: lightgray; -fx-border-radius: 4; -fx-background-color: #f7f7f7;");
        getChildren().addAll(title,
                new Label("连接目标:"), targetField,
                new Label("密码:"), passwordField,
                new Label("连接方式:"), policyBox,
                connectButton);
    }

    private static void runOnFx(Runnable action) {
        if (Platform.isFxApplicationThread()) {
            action.run();
        } else {
            Platform.runLater(action);
        }
    }
}
