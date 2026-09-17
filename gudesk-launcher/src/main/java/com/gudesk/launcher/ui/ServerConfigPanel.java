package com.gudesk.launcher.ui;

import com.gudesk.launcher.UiFont;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.Optional;
import java.util.function.Function;

/**
 * 服务器配置区域（右下）：只读显示当前信令服务器地址 + 「配置」弹窗修改。
 */
public class ServerConfigPanel extends VBox {

    private final Label serverValueLabel;
    private String currentServer;

    /**
     * @param initialServer 初始服务器地址文案（来自 --server，默认 host:port）
     * @param onServerApply 「配置」确定后回调（参数为输入的地址文案，返回是否应用成功）
     */
    public ServerConfigPanel(String initialServer, Function<String, Boolean> onServerApply) {
        Label title = new Label("服务器配置");
        title.setStyle("-fx-font-weight: bold; -fx-font-size: 14;");

        this.currentServer = initialServer;
        serverValueLabel = new Label(initialServer);
        Button configButton = new Button("配置");
        configButton.setOnAction(e -> promptServerConfig(onServerApply));

        HBox row = new HBox(8, new Label("地址:"), serverValueLabel, configButton);
        HBox.setHgrow(serverValueLabel, Priority.ALWAYS);

        setSpacing(10);
        setPadding(new Insets(16));
        setStyle("-fx-border-color: lightgray; -fx-border-radius: 4; -fx-background-color: #f7f7f7;");
        getChildren().addAll(title, row);
    }

    /** 弹出「配置服务器地址」对话框，确定后应用并刷新只读显示 */
    private void promptServerConfig(Function<String, Boolean> onServerApply) {
        TextField field = new TextField(currentServer);
        field.setPromptText("host:port（默认 48900）");

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("配置服务器地址");
        dialog.setHeaderText("修改信令服务器地址");
        dialog.getDialogPane().setContent(new VBox(8, new Label("服务器地址:"), field));
        ButtonType ok = new ButtonType("确定", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType("取消", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(ok, cancel);
        UiFont.apply(dialog);

        Optional<ButtonType> result = dialog.showAndWait();
        if (result.isEmpty() || result.get() != ok) {
            return;
        }
        String text = field.getText();
        if (text == null || text.isBlank()) {
            return;
        }
        String trimmed = text.trim();
        if (onServerApply.apply(trimmed)) {
            currentServer = trimmed;
            serverValueLabel.setText(trimmed);
        }
    }
}
