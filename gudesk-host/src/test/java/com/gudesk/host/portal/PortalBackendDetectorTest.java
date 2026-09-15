package com.gudesk.host.portal;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PortalBackendDetector} 单测：后端识别、XDG_CURRENT_DESKTOP 消歧与
 * 能力位映射（纯函数注入 env 与 busNames，全部离线）。
 */
class PortalBackendDetectorTest {

    private static Map<String, String> env(String currentDesktop) {
        Map<String, String> env = new HashMap<>();
        if (currentDesktop != null) {
            env.put("XDG_CURRENT_DESKTOP", currentDesktop);
        }
        return env;
    }

    @Test
    void KDE与GTK双注册_XDG_CURRENT_DESKTOP为KDE时选择KDE() {
        PortalBackendInfo info = PortalBackendDetector.detect(env("KDE"), Set.of(
                "org.freedesktop.impl.portal.desktop.kde",
                "org.freedesktop.impl.portal.desktop.gtk"));

        assertEquals(PortalBackendKind.KDE, info.backendKind());
        assertTrue(info.persistentSupported(), "KDE 支持 restore token 持久化授权");
        assertTrue(info.absolutePointerSupported());
    }

    @Test
    void 双注册_XDG_CURRENT_DESKTOP为XFCE时选择GTK() {
        PortalBackendInfo info = PortalBackendDetector.detect(env("XFCE"), Set.of(
                "org.freedesktop.impl.portal.desktop.gnome",
                "org.freedesktop.impl.portal.desktop.gtk"));

        assertEquals(PortalBackendKind.GTK, info.backendKind());
        assertFalse(info.persistentSupported(), "GTK 后端不支持授权持久化");
        assertFalse(info.absolutePointerSupported(), "GTK 后端不支持绝对坐标指针注入");
    }

    @Test
    void 双注册_无桌面提示时按优先级选择原生后端() {
        // 发行版同时注册 kde 与 gtk 但未设置 XDG_CURRENT_DESKTOP：原生后端优先于通用兜底
        PortalBackendInfo info = PortalBackendDetector.detect(new HashMap<>(), Set.of(
                "org.freedesktop.impl.portal.desktop.gtk",
                "org.freedesktop.impl.portal.desktop.kde"));

        assertEquals(PortalBackendKind.KDE, info.backendKind());
    }

    @Test
    void 仅注册gnome时选择GNOME且持久化为受限() {
        PortalBackendInfo info = PortalBackendDetector.detect(env("GNOME"), Set.of(
                "org.freedesktop.impl.portal.desktop.gnome"));

        assertEquals(PortalBackendKind.GNOME, info.backendKind());
        assertFalse(info.persistentSupported(), "GNOME 的 RemoteDesktop 授权持久化受限");
        assertTrue(info.absolutePointerSupported(), "GNOME（mutter）支持绝对坐标指针注入");
    }

    @Test
    void 仅注册wlr后缀时识别为WLROOTS() {
        // xdg-desktop-portal-wlr 的总线名后缀是 wlr
        PortalBackendInfo info = PortalBackendDetector.detect(env("sway"), Set.of(
                "org.freedesktop.impl.portal.desktop.wlr"));

        assertEquals(PortalBackendKind.WLROOTS, info.backendKind());
        assertTrue(info.persistentSupported());
        assertTrue(info.absolutePointerSupported());
    }

    @Test
    void 注册hyprland时识别为HYPRANDL() {
        PortalBackendInfo info = PortalBackendDetector.detect(env("Hyprland"), Set.of(
                "org.freedesktop.impl.portal.desktop.hyprland"));

        assertEquals(PortalBackendKind.HYPRANDL, info.backendKind());
        assertTrue(info.persistentSupported());
        assertTrue(info.absolutePointerSupported());
    }

    @Test
    void 未注册已知后端时返回UNKNOWN() {
        PortalBackendInfo info = PortalBackendDetector.detect(env("KDE"), Set.of(
                "org.freedesktop.impl.portal.desktop.somefuturebackend"));

        assertEquals(PortalBackendKind.UNKNOWN, info.backendKind());
        assertFalse(info.isKnown());
        assertFalse(info.persistentSupported());
        assertFalse(info.absolutePointerSupported());
    }

    @Test
    void 空入参与脏元素安全() {
        assertEquals(PortalBackendKind.UNKNOWN, PortalBackendDetector.detect(null, null).backendKind());
        assertEquals(PortalBackendKind.UNKNOWN,
                PortalBackendDetector.detect(new HashMap<>(), Set.of()).backendKind());
        // busNames 含 null/空白元素不抛异常
        Set<String> names = new HashSet<>();
        names.add(null);
        names.add(" ");
        names.add("org.freedesktop.impl.portal.desktop.");
        assertEquals(PortalBackendKind.UNKNOWN,
                PortalBackendDetector.detect(env("KDE"), names).backendKind());
    }

    @Test
    void 后端名与桌面提示的大小写空白容忍() {
        PortalBackendInfo info = PortalBackendDetector.detect(env(" kde "), Set.of(
                " org.freedesktop.impl.portal.desktop.GTK ",
                "org.freedesktop.impl.portal.desktop.KDE"));

        assertEquals(PortalBackendKind.KDE, info.backendKind(), "XDG_CURRENT_DESKTOP 小写应仍消歧到 KDE");
    }
}
