package com.gudesk.launcher.ui;

import java.io.IOException;
import java.io.PrintStream;
import java.util.List;

/**
 * 首页被控区设置项的操作入口（由组合根 {@code GuDeskClientApp} 注入实现，
 * UI 只依赖本抽象，不直接耦合 HostService / AutostartController / Portal 助手）。
 */
public interface HostSettingsActions {

    /** 信任列表条目（UI 展示用，避免耦合 host 模块的 TrustStore 类型） */
    record TrustedViewerEntry(String fingerprint, String name, long trustedAtMillis) {
    }

    /** 修改被控密码（写盘 + 热更新到运行中的会话服务） */
    void changePassword(String newPassword) throws IOException;

    /** 当前开机自启是否启用 */
    boolean isAutostartEnabled();

    /** 设置开机自启开关 */
    void setAutostartEnabled(boolean enabled) throws IOException;

    /**
     * 配置无人值守（阻塞：探测后端 + 引导授权 + 落盘 restore token，
     * 应在后台线程调用）；过程输出写到 {@code out}。
     *
     * @return 0=成功，2=失败
     */
    int enableUnattended(PrintStream out);

    /** 已信任的主控端列表 */
    List<TrustedViewerEntry> listTrustedViewers();

    /** 移除指定指纹的信任 */
    boolean removeTrustedViewer(String fingerprint) throws IOException;
}
