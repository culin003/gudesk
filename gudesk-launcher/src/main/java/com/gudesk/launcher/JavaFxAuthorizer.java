package com.gudesk.launcher;

import com.gudesk.common.proto.GuDeskProto.AuthorizeRequest;
import com.gudesk.common.proto.GuDeskProto.AuthorizeResponse;
import com.gudesk.host.session.Authorizer;
import com.google.protobuf.ByteString;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * JavaFX 版授权确认弹窗：显示主控端来源与长期身份公钥指纹，选项 接受/拒绝，
 * 附「始终信任此主控」复选框；超时（默认 {@value #TIMEOUT_MS}ms）自动关闭并视为拒绝。
 *
 * <p>与 {@code SwingAuthorizer} 的区别：本实现经 {@link Platform#runLater} 在 JavaFX
 * 线程弹模态 {@link Dialog}，仅供合并应用（JavaFX 已启动）使用；独立 {@code --host-only}
 * 模式不启动 JavaFX，仍使用 Swing 版，避免双 UI 线程共存。
 *
 * <p>超时兜底：{@code HostSession} 侧另有 {@code orTimeout(30s)} 保证授权不会永久挂起；
 * 本实现自身也在超时后关闭弹窗并回填拒绝结果。
 */
public final class JavaFxAuthorizer implements Authorizer {

    /** 授权确认超时（毫秒） */
    static final long TIMEOUT_MS = 30_000;

    private static final ButtonType ACCEPT = new ButtonType("接受", ButtonBar.ButtonData.OK_DONE);
    private static final ButtonType REJECT = new ButtonType("拒绝", ButtonBar.ButtonData.CANCEL_CLOSE);

    @Override
    public CompletableFuture<AuthorizeResponse> requestAuthorization(AuthorizeRequest request) {
        CompletableFuture<AuthorizeResponse> future = new CompletableFuture<>();
        Platform.runLater(() -> showDialog(request, future));
        return future;
    }

    /** 在 FX 线程显示模态确认弹窗（超时由 PauseTransition 关闭弹窗并拒绝） */
    private void showDialog(AuthorizeRequest request, CompletableFuture<AuthorizeResponse> future) {
        String fingerprint = request.getViewerFingerprint().isEmpty()
                ? "（主控端未提供身份密钥）" : request.getViewerFingerprint();

        CheckBox alwaysTrust = new CheckBox("始终信任此主控（指纹匹配时下次免确认）");
        VBox content = new VBox(8,
                new Label("主控端请求远程控制本机："),
                new Label("来源: " + request.getViewerId()),
                new Label("主控端指纹: " + fingerprint),
                alwaysTrust);

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("GuDesk 远程控制授权");
        dialog.setHeaderText("远程控制授权确认");
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().addAll(ACCEPT, REJECT);
        UiFont.apply(dialog);

        PauseTransition timeout = new PauseTransition(Duration.millis(TIMEOUT_MS));
        timeout.setOnFinished(e -> dialog.close());
        timeout.play();

        Optional<ButtonType> result = dialog.showAndWait();
        timeout.stop();

        boolean accepted = result.isPresent() && result.get() == ACCEPT;
        future.complete(AuthorizeResponse.newBuilder()
                .setAccepted(accepted)
                .setAlwaysTrust(accepted && alwaysTrust.isSelected())
                .setViewerFingerprint(ByteString.copyFrom(
                        request.getViewerFingerprint().getBytes(StandardCharsets.UTF_8)))
                .build());
    }
}
