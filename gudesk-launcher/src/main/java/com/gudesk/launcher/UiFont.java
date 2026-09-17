package com.gudesk.launcher;

import javafx.scene.control.Dialog;
import javafx.scene.image.Image;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.io.IOException;
import java.io.InputStream;

/**
 * UI 字体与图标工具：JavaFX 弹窗（{@link Dialog} / {@link javafx.scene.control.Alert}）
 * 是独立窗口，既不继承主场景根节点的字体，也不继承主窗口图标，需显式统一套用，
 * 否则 Linux 上中文会渲染为方框、窗口标题栏/任务栏图标不一致。
 */
public final class UiFont {

    /** CJK 字体样式（与主窗口根节点一致，随包内置 Noto Sans CJK SC） */
    public static final String CJK_FONT_CSS = "-fx-font-family: 'Noto Sans CJK SC';";

    private UiFont() {
    }

    /** 给对话框（含 Alert）统一套用 CJK 字体 + 应用图标（标题栏/任务栏一致） */
    public static void apply(Dialog<?> dialog) {
        dialog.getDialogPane().setStyle(CJK_FONT_CSS);
        dialog.setOnShown(event -> {
            Window window = dialog.getDialogPane().getScene().getWindow();
            if (window instanceof Stage stage) {
                Image icon = loadIcon();
                if (icon != null) {
                    stage.getIcons().add(icon);
                }
            }
        });
    }

    /** 给主窗口统一设置应用图标（标题栏/任务栏） */
    public static void applyIcon(Stage stage) {
        Image icon = loadIcon();
        if (icon != null) {
            stage.getIcons().add(icon);
        }
    }

    /** 从 classpath 加载应用图标（随包内置 gudesk.png） */
    private static Image loadIcon() {
        try (InputStream in = UiFont.class.getResourceAsStream("/gudesk.png")) {
            return in == null ? null : new Image(in);
        } catch (IOException e) {
            return null;
        }
    }
}
