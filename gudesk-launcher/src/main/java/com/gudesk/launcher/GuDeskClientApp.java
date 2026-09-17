package com.gudesk.launcher;

import com.gudesk.host.NetworkAddresses;
import com.gudesk.host.portal.PortalUnattendedHelper;
import com.gudesk.host.session.HostService;
import com.gudesk.host.session.HostSessionManager;
import com.gudesk.host.session.TcpSessionServer;
import com.gudesk.host.session.TrustStore;
import com.gudesk.launcher.ui.HomeView;
import com.gudesk.launcher.ui.HostSettingsActions;
import com.gudesk.viewer.session.SessionUiConnector;
import com.gudesk.viewer.session.ViewerConnectionOrchestrator;
import com.gudesk.viewer.ui.ConnectionState;
import com.gudesk.viewer.ui.ConnectPolicy;
import com.gudesk.viewer.ui.UiController;
import com.gudesk.viewer.ui.ViewerSessionView;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.util.List;

/**
 * GuDesk 合并应用（主控 + 被控单窗口）统一入口。
 *
 * <p>布局：{@link HomeView}（左「被控」右「主控」双区首页）与
 * {@link ViewerSessionView}（视频会话视图）叠加于同一 {@link StackPane}，
 * 按连接状态切换：DISCONNECTED 显示首页，其余状态显示会话视图。
 *
 * <p>被控服务经 {@link HostService} 在后台线程启动（注入 {@link JavaFxAuthorizer}），
 * 其被控 ID 与「被控中」状态回填到首页左区。
 */
public class GuDeskClientApp extends Application {

    private UiController controller;
    private ViewerSessionView sessionView;
    private SessionUiConnector sessionConnector;
    private HomeView homeView;
    private HostService hostService;

    @Override
    public void start(Stage stage) {
        List<String> rawArgs = getParameters().getRaw();
        String serverText = rawArgValue(rawArgs, "--server", "");
        InetSocketAddress signalingServer = null;
        if (!serverText.isBlank()) {
            try {
                signalingServer = ViewerConnectionOrchestrator.parseServerAddress(serverText);
            } catch (IllegalArgumentException e) {
                System.out.println("[错误] " + e.getMessage());
                Platform.exit();
                return;
            }
        }
        boolean preferRelay = rawArgs.contains("--prefer-relay");
        ConnectPolicy initialPolicy = preferRelay ? ConnectPolicy.RELAY_ONLY : ConnectPolicy.AUTO;

        controller = new UiController();
        sessionView = new ViewerSessionView(controller);
        sessionConnector = new SessionUiConnector(controller, sessionView.videoCanvas(),
                signalingServer, preferRelay);
        controller.setConnectHandler(sessionConnector);
        sessionView.setInputForwarder(sessionConnector);

        // 先建被控服务对象（未启动），供首页设置项调用（改密码需热更新到运行中的服务）
        HostService service = new HostService(new JavaFxAuthorizer(),
                TcpSessionServer.DEFAULT_PORT, signalingServer, false);
        this.hostService = service;
        TrustStore trustStore = new TrustStore(TrustStore.DEFAULT_FILE);
        HostSettingsActions settingsActions = new HostSettingsActions() {
            @Override
            public void changePassword(String newPassword) throws IOException {
                service.setPassword(newPassword);
            }

            @Override
            public boolean isAutostartEnabled() {
                return AutostartController.isEnabled();
            }

            @Override
            public void setAutostartEnabled(boolean enabled) throws IOException {
                AutostartController.setEnabled(enabled);
            }

            @Override
            public int enableUnattended(PrintStream out) {
                return PortalUnattendedHelper.enableUnattended(out);
            }

            @Override
            public List<TrustedViewerEntry> listTrustedViewers() {
                return trustStore.entries().stream()
                        .map(e -> new TrustedViewerEntry(e.fingerprint(), e.name(), e.trustedAtMillis()))
                        .toList();
            }

            @Override
            public boolean removeTrustedViewer(String fingerprint) throws IOException {
                return trustStore.remove(fingerprint);
            }
        };

        homeView = new HomeView(controller, settingsActions, serverText, this::applyServer, initialPolicy);

        StackPane root = new StackPane(homeView, sessionView);
        root.setStyle("-fx-font-family: 'Noto Sans CJK SC';");
        homeView.setVisible(true);
        sessionView.setVisible(false);

        controller.addStateListener(newState -> runOnFx(() -> {
            boolean connected = newState != ConnectionState.DISCONNECTED;
            homeView.setVisible(!connected);
            sessionView.setVisible(connected);
        }));

        startHostService(service);

        Scene scene = new Scene(root, 960, 640);
        stage.setTitle("GuDesk");
        stage.setScene(scene);
        UiFont.applyIcon(stage);
        stage.show();
    }

    @Override
    public void stop() {
        if (sessionConnector != null) {
            sessionConnector.shutdown();
        }
        if (controller != null) {
            controller.shutdown();
        }
        if (hostService != null) {
            hostService.stop();
        }
    }

    /** 后台线程启动被控服务，回填被控 ID 与「被控中」状态到首页左区 */
    private void startHostService(HostService service) {
        service.addSessionListener(new HostSessionManager.SessionListener() {
            @Override
            public void onSessionEstablished() {
                runOnFx(() -> homeView.hostPanel().setHostStatus("正在被控制"));
            }

            @Override
            public void onSessionEnded() {
                runOnFx(() -> homeView.hostPanel().setHostStatus("空闲"));
            }
        });
        Thread thread = new Thread(() -> {
            try {
                service.start();
                String id = service.assignedId();
                String plain = service.newlyGeneratedPassword();
                String addresses = formatLocalAddresses(service.boundPort());
                runOnFx(() -> {
                    homeView.hostPanel().setHostId(
                            id == null ? "未配置服务器（仅 ip:port 直连）" : id);
                    homeView.hostPanel().setLocalAddresses(addresses);
                    if (plain != null) {
                        showFirstRunPassword(plain);
                    }
                });
            } catch (Exception e) {
                runOnFx(() -> homeView.hostPanel().setHostStatus("被控服务启动失败: " + e.getMessage()));
            }
        }, "gudesk-host-service");
        thread.setDaemon(true);
        thread.start();
    }

    /** 应用服务器地址（空=未配置）：更新主控编排器 + 后台重启被控信令注册；返回地址是否合法 */
    private boolean applyServer(String text) {
        InetSocketAddress server = null;
        if (text != null && !text.isBlank()) {
            try {
                server = ViewerConnectionOrchestrator.parseServerAddress(text);
            } catch (IllegalArgumentException e) {
                homeView.hostPanel().setHostStatus("服务器地址非法: " + e.getMessage());
                return false;
            }
        }
        if (sessionConnector != null) {
            sessionConnector.setServer(server);
        }
        InetSocketAddress srv = server;
        Thread thread = new Thread(() -> {
            if (hostService != null) {
                hostService.reconfigureServer(srv);
            }
            String id = hostService == null ? null : hostService.assignedId();
            runOnFx(() -> homeView.hostPanel().setHostId(
                    id == null ? "未配置服务器（仅 ip:port 直连）" : id));
        }, "gudesk-host-reconfigure");
        thread.setDaemon(true);
        thread.start();
        return true;
    }

    /** 组装本机地址展示文案（"ip:port" 列表，内网直连用） */
    private static String formatLocalAddresses(int port) {
        List<String> addrs = NetworkAddresses.localIpv4Addresses();
        if (addrs.isEmpty()) {
            return "未知";
        }
        return addrs.stream()
                .map(a -> a + ":" + port)
                .collect(java.util.stream.Collectors.joining(", "));
    }

    /** 首次生成密码时一次性展示（与独立被控模式行为一致） */
    private void showFirstRunPassword(String plain) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("GuDesk 被控端");
        alert.setHeaderText("首次启动，已生成随机密码");
        alert.setContentText("随机密码：\n\n    " + plain + "\n\n仅本次显示（哈希已存储），请妥善保存。");
        UiFont.apply(alert);
        alert.showAndWait();
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
