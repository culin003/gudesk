package com.gudesk.host.session;

import com.gudesk.common.proto.GuDeskProto.AuthorizeRequest;
import com.gudesk.common.proto.GuDeskProto.AuthorizeResponse;
import com.google.protobuf.ByteString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.GraphicsEnvironment;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

/**
 * Swing 授权确认弹窗实现：显示主控端来源与长期身份公钥指纹，
 * 选项 接受/拒绝，附"始终信任此主控"复选框；
 * 超时（默认 {@value #DEFAULT_TIMEOUT_MS}ms）自动视为拒绝并关闭弹窗；
 * 无头环境直接拒绝并记录日志（不弹窗）。
 *
 * <p>弹窗在 EDT 上显示（模态），{@link #requestAuthorization} 立即返回 future 不阻塞调用线程。
 */
public final class SwingAuthorizer implements Authorizer {

    private static final Logger LOG = LoggerFactory.getLogger(SwingAuthorizer.class);

    /** 授权确认超时（毫秒）：超时自动视为拒绝并关闭弹窗 */
    public static final long DEFAULT_TIMEOUT_MS = 30_000;

    private static final String OPTION_ACCEPT = "接受";
    private static final String OPTION_REJECT = "拒绝";

    private final long timeoutMs;
    private final BooleanSupplier headlessCheck;

    public SwingAuthorizer() {
        this(DEFAULT_TIMEOUT_MS);
    }

    public SwingAuthorizer(long timeoutMs) {
        this(timeoutMs, GraphicsEnvironment::isHeadless);
    }

    /** 测试用构造：可注入无头判定与超时时长 */
    SwingAuthorizer(long timeoutMs, BooleanSupplier headlessCheck) {
        this.timeoutMs = timeoutMs;
        this.headlessCheck = headlessCheck;
    }

    @Override
    public CompletableFuture<AuthorizeResponse> requestAuthorization(AuthorizeRequest request) {
        CompletableFuture<AuthorizeResponse> future = new CompletableFuture<>();
        if (headlessCheck.getAsBoolean()) {
            LOG.warn("无图形环境（headless），授权确认默认拒绝: 来源={}", request.getViewerId());
            future.complete(rejected(request));
            return future;
        }
        SwingUtilities.invokeLater(() -> {
            try {
                showDialog(request, future);
            } catch (Throwable t) {
                LOG.warn("授权确认弹窗异常，视为拒绝: {}", String.valueOf(t));
                future.complete(rejected(request));
            }
        });
        return future;
    }

    /** 在 EDT 上显示模态确认弹窗（超时由 Swing Timer 关闭弹窗并拒绝） */
    private void showDialog(AuthorizeRequest request, CompletableFuture<AuthorizeResponse> future) {
        String fingerprint = request.getViewerFingerprint().isEmpty()
                ? "（主控端未提供身份密钥）" : request.getViewerFingerprint();
        String message = "主控端请求远程控制本机：\n\n"
                + "来源: " + request.getViewerId() + "\n"
                + "主控端指纹:\n" + fingerprint + "\n";
        JCheckBox alwaysTrust = new JCheckBox("始终信任此主控（指纹匹配时下次免确认）");
        JOptionPane pane = new JOptionPane(new Object[]{message, alwaysTrust},
                JOptionPane.WARNING_MESSAGE, JOptionPane.YES_NO_OPTION,
                null, new Object[]{OPTION_ACCEPT, OPTION_REJECT}, OPTION_REJECT);
        JDialog dialog = pane.createDialog(null, "GuDesk 远程控制授权");
        dialog.setAlwaysOnTop(true);
        Timer timeout = new Timer((int) timeoutMs, e -> {
            LOG.warn("授权确认超时（{}ms），自动拒绝并关闭弹窗", timeoutMs);
            dialog.dispose();
            future.complete(rejected(request));
        });
        timeout.setRepeats(false);
        timeout.start();
        try {
            dialog.setVisible(true);
        } finally {
            timeout.stop();
        }
        boolean accepted = OPTION_ACCEPT.equals(pane.getValue());
        future.complete(AuthorizeResponse.newBuilder()
                .setAccepted(accepted)
                .setAlwaysTrust(accepted && alwaysTrust.isSelected())
                .setViewerFingerprint(ByteString.copyFrom(
                        request.getViewerFingerprint().getBytes(StandardCharsets.UTF_8)))
                .build());
    }

    private static AuthorizeResponse rejected(AuthorizeRequest request) {
        return AuthorizeResponse.newBuilder()
                .setAccepted(false)
                .setViewerFingerprint(ByteString.copyFrom(
                        request.getViewerFingerprint().getBytes(StandardCharsets.UTF_8)))
                .build();
    }
}
