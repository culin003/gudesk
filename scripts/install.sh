#!/usr/bin/env bash
# =============================================================================
# GuDesk 用户级安装脚本（免 root，跨 Linux 发行版）
#
# 用法（在解压后的目录内执行）：
#   ./install.sh             # 安装到 ~/.local/opt/gudesk 并配置桌面/自启
#   ./install.sh --uninstall # 卸载
#
# 安装内容：
#   ~/.local/opt/gudesk                 应用本体（app-image）
#   ~/.local/bin/gudesk                 启动命令软链
#   ~/.local/share/applications/gudesk.desktop   应用菜单
#   ~/.config/autostart/gudesk.desktop           开机自启（被控服务）
#
# 要求：同目录下存在 app-image 目录 gudesk/（由 package-linux-tar.sh 产出）
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_DIR="$SCRIPT_DIR/gudesk"
INSTALL_DIR="$HOME/.local/opt/gudesk"
BIN_LINK="$HOME/.local/bin/gudesk"
APPS_DIR="$HOME/.local/share/applications"
AUTOSTART_DIR="$HOME/.config/autostart"

install() {
    if [ ! -d "$APP_DIR" ]; then
        echo "[错误] 未找到应用目录：$APP_DIR" >&2
        echo "       请确保 install.sh 与 gudesk/ 在同一目录（解压后默认即是）。" >&2
        exit 1
    fi

    mkdir -p "$(dirname "$INSTALL_DIR")" "$(dirname "$BIN_LINK")" "$APPS_DIR" "$AUTOSTART_DIR"
    rm -rf "$INSTALL_DIR"
    cp -a "$APP_DIR" "$INSTALL_DIR"
    ln -sf "$INSTALL_DIR/bin/gudesk" "$BIN_LINK"

    cat > "$APPS_DIR/gudesk.desktop" <<EOF
[Desktop Entry]
Type=Application
Name=GuDesk
GenericName=Remote Desktop
Comment=GuDesk 远程桌面（主控 + 被控）
Exec=$INSTALL_DIR/bin/gudesk
Icon=$INSTALL_DIR/lib/gudesk.png
Terminal=false
Categories=Network;RemoteAccess;
StartupWMClass=gudesk
EOF

    cat > "$AUTOSTART_DIR/gudesk.desktop" <<EOF
[Desktop Entry]
Type=Application
Name=GuDesk Host Service
Comment=GuDesk 被控服务（用户登录后自动启动；gudesk --disable-autostart 可关闭）
Exec=$INSTALL_DIR/bin/gudesk --host-only
Icon=$INSTALL_DIR/lib/gudesk.png
Terminal=false
X-GNOME-Autostart-enabled=true
EOF

    echo "安装完成：$INSTALL_DIR"
    echo "启动命令：$BIN_LINK"
    echo "开机自启：$AUTOSTART_DIR/gudesk.desktop"
    echo "卸载请运行：$SCRIPT_DIR/install.sh --uninstall"
}

uninstall() {
    rm -rf "$INSTALL_DIR"
    rm -f "$BIN_LINK" "$APPS_DIR/gudesk.desktop" "$AUTOSTART_DIR/gudesk.desktop"
    echo "已卸载"
}

case "${1:-}" in
    --uninstall) uninstall ;;
    *) install ;;
esac
