package com.gudesk.launcher.ui;

import com.gudesk.viewer.ui.ConnectPolicy;
import com.gudesk.viewer.ui.UiController;
import javafx.geometry.Insets;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.function.Function;

/**
 * 合并应用首页：左「本机被控」、右上「远程控制」、右下「服务器配置」三区域布局。
 */
public class HomeView extends BorderPane {

    private final HostPanel hostPanel;

    /**
     * @param initialServer 初始服务器地址文案（来自 --server，默认 host:port）
     * @param onServerApply 「配置」确定后回调（参数为输入的地址文案，返回是否应用成功）
     */
    public HomeView(UiController controller, HostSettingsActions settingsActions,
                    String initialServer, Function<String, Boolean> onServerApply,
                    ConnectPolicy initialPolicy) {
        this.hostPanel = new HostPanel(settingsActions);
        ConnectPanel connectPanel = new ConnectPanel(controller, initialPolicy);
        ServerConfigPanel serverConfigPanel = new ServerConfigPanel(initialServer, onServerApply);

        // 右侧：上=远程控制（占据剩余高度），下=服务器配置
        VBox rightPane = new VBox(12, connectPanel, serverConfigPanel);
        VBox.setVgrow(connectPanel, Priority.ALWAYS);

        setLeft(hostPanel);
        setCenter(rightPane);
        BorderPane.setMargin(rightPane, new Insets(0, 0, 0, 12));
        setPadding(new Insets(12));
    }

    /** 左区被控面板（供 GuDeskClientApp 回填 ID 与状态） */
    public HostPanel hostPanel() {
        return hostPanel;
    }
}
