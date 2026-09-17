package com.gudesk.launcher.ui;

import com.gudesk.launcher.UiFont;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.PasswordField;
import javafx.scene.control.Separator;
import javafx.scene.layout.VBox;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 首页左区「被控」面板：本机被控 ID、被控状态指示，以及被控设置项
 * （改密码 / 开机自启 / 无人值守 / 信任列表）。
 *
 * <p>本类只做展示与交互；ID/状态由 {@link com.gudesk.launcher.GuDeskClientApp} 回填，
 * 设置项操作委托注入的 {@link HostSettingsActions}（组合根实现）。
 */
public class HostPanel extends VBox {

    private final HostSettingsActions actions;
    private final Label idLabel;
    private final Label statusLabel;
    private final Label addressLabel;
    private final CheckBox autostartCheckBox;
    private final Button unattendedButton;

    public HostPanel(HostSettingsActions actions) {
        this.actions = actions;

        Label title = new Label("本机被控");
        title.setStyle("-fx-font-weight: bold; -fx-font-size: 14;");

        idLabel = new Label("本机 ID: 加载中...");
        statusLabel = new Label("状态: 空闲");
        addressLabel = new Label("本机地址: --");

        Button changePasswordButton = new Button("改密码");
        changePasswordButton.setMaxWidth(Double.MAX_VALUE);
        changePasswordButton.setOnAction(e -> promptChangePassword());

        unattendedButton = new Button("配置无人值守");
        unattendedButton.setMaxWidth(Double.MAX_VALUE);
        unattendedButton.setOnAction(e -> runUnattended());

        Button trustListButton = new Button("信任列表");
        trustListButton.setMaxWidth(Double.MAX_VALUE);
        trustListButton.setOnAction(e -> openTrustList());

        autostartCheckBox = new CheckBox("开机自启（登录后自动运行被控服务）");
        autostartCheckBox.setSelected(actions.isAutostartEnabled());
        autostartCheckBox.setOnAction(e -> toggleAutostart());

        setSpacing(10);
        setPadding(new Insets(16));
        setStyle("-fx-border-color: lightgray; -fx-border-radius: 4; -fx-background-color: #f7f7f7;");
        setPrefWidth(300);
        getChildren().addAll(title, idLabel, statusLabel, addressLabel,
                new Separator(),
                new Label("设置:"),
                changePasswordButton, unattendedButton, trustListButton, autostartCheckBox);
    }

    /** 回填本机被控 ID（信令未注册时为提示文案） */
    public void setHostId(String id) {
        idLabel.setText("本机 ID: " + id);
    }

    /** 回填被控状态（空闲 / 正在被控制 / 启动失败等） */
    public void setHostStatus(String status) {
        statusLabel.setText("状态: " + status);
    }

    /** 回填本机地址（"ip:port" 列表，内网直连用） */
    public void setLocalAddresses(String text) {
        addressLabel.setText("本机地址: " + text);
    }

    // ------------------------------------------------------------------
    // 设置项交互
    // ------------------------------------------------------------------

    private void promptChangePassword() {
        PasswordField field = new PasswordField();
        field.setPromptText("新密码");

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("修改被控密码");
        dialog.setHeaderText("修改被控密码");
        dialog.getDialogPane().setContent(new VBox(8, new Label("新密码:"), field));
        ButtonType ok = new ButtonType("确定", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType("取消", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(ok, cancel);
        UiFont.apply(dialog);

        Optional<ButtonType> result = dialog.showAndWait();
        if (result.isEmpty() || result.get() != ok) {
            return;
        }
        String pwd = field.getText();
        if (pwd == null || pwd.isBlank()) {
            showAlert(Alert.AlertType.WARNING, "修改被控密码", "密码不能为空");
            return;
        }
        try {
            actions.changePassword(pwd.trim());
            showAlert(Alert.AlertType.INFORMATION, "修改被控密码", "密码已更新（对后续连接生效）");
        } catch (IOException e) {
            showAlert(Alert.AlertType.ERROR, "修改被控密码", "修改失败: " + e.getMessage());
        }
    }

    private void toggleAutostart() {
        boolean enabled = autostartCheckBox.isSelected();
        try {
            actions.setAutostartEnabled(enabled);
        } catch (IOException e) {
            autostartCheckBox.setSelected(!enabled); // 失败回滚
            showAlert(Alert.AlertType.ERROR, "开机自启", "设置失败: " + e.getMessage());
        }
    }

    private void runUnattended() {
        unattendedButton.setDisable(true);
        Thread thread = new Thread(() -> {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            PrintStream out = new PrintStream(buf, true, StandardCharsets.UTF_8);
            int code = actions.enableUnattended(out);
            String text = buf.toString(StandardCharsets.UTF_8);
            runOnFx(() -> {
                unattendedButton.setDisable(false);
                showAlert(code == 0 ? Alert.AlertType.INFORMATION : Alert.AlertType.WARNING,
                        "无人值守", text);
            });
        }, "gudesk-unattended-config");
        thread.setDaemon(true);
        thread.start();
    }

    private void openTrustList() {
        ListView<String> listView = new ListView<>();
        List<String> fingerprints = new ArrayList<>();
        refreshTrustList(listView, fingerprints);

        Button removeButton = new Button("移除所选");
        removeButton.setOnAction(e -> {
            int idx = listView.getSelectionModel().getSelectedIndex();
            if (idx < 0) {
                showAlert(Alert.AlertType.WARNING, "信任列表", "请先选择要移除的主控端");
                return;
            }
            String fingerprint = fingerprints.get(idx);
            try {
                actions.removeTrustedViewer(fingerprint);
                refreshTrustList(listView, fingerprints);
            } catch (IOException ex) {
                showAlert(Alert.AlertType.ERROR, "信任列表", "移除失败: " + ex.getMessage());
            }
        });

        VBox content = new VBox(8, listView, removeButton);

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("信任列表");
        dialog.setHeaderText("已信任的主控端（移除后需重新授权）");
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        UiFont.apply(dialog);
        dialog.showAndWait();
    }

    private void refreshTrustList(ListView<String> listView, List<String> fingerprints) {
        listView.getItems().clear();
        fingerprints.clear();
        for (HostSettingsActions.TrustedViewerEntry entry : actions.listTrustedViewers()) {
            fingerprints.add(entry.fingerprint());
            listView.getItems().add(entry.fingerprint()
                    + (entry.name() == null || entry.name().isBlank() ? "" : "  (" + entry.name() + ")"));
        }
        if (fingerprints.isEmpty()) {
            listView.setPlaceholder(new Label("暂无信任的主控端"));
        }
    }

    private static void showAlert(Alert.AlertType type, String title, String content) {
        Alert alert = new Alert(type);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(content);
        UiFont.apply(alert);
        alert.showAndWait();
    }

    private static void runOnFx(Runnable action) {
        if (Platform.isFxApplicationThread()) {
            action.run();
        } else {
            Platform.runLater(action);
        }
    }
}
