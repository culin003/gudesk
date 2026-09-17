#!/usr/bin/env bash
# =============================================================================
# GuDesk 服务端自包含 tar.gz 打包脚本（信令 + STUN + 中继，无 GUI）
#
# 流程：Maven 构建 server + 依赖 → jlink 裁剪运行时（无 java.desktop）
#       → 组装 bin/lib/runtime + systemd 单元 + install.sh → tar.gz
#
# 产物：
#   dist/gudesk-server-<version>-linux-x64.tar.gz
#   dist/gudesk-server-<version>-linux-x64/   （解包目录）
#
# 部署（在服务器上，需 root）：
#   tar xzf gudesk-server-<version>-linux-x64.tar.gz
#   cd gudesk-server-<version>-linux-x64 && sudo ./install.sh
# =============================================================================
set -euo pipefail
cd "$(dirname "$0")/.."

if [ -z "${JAVA_HOME:-}" ] || ! "${JAVA_HOME:-}/bin/java" -version 2>&1 | grep -q 'version "21'; then
    export JAVA_HOME=/home/cooper/MySoft/jdk/zulu21.52.15-ca-jdk21.0.12-linux_x64/
fi
export PATH="$JAVA_HOME/bin:$PATH"
JLINK="$JAVA_HOME/bin/jlink"
[ -x "$JLINK" ] || { echo "[错误] 缺少 jlink: $JLINK"; exit 1; }

APP_NAME="gudesk-server"
VERSION="0.1.0"
SERVER_JAR="gudesk-server-0.1.0-SNAPSHOT.jar"
MAIN_CLASS="com.gudesk.server.ServerApp"
# 服务端无 GUI，但 log4j2 用到 java.beans（属于 java.desktop），故需包含 java.desktop
RUNTIME_MODULES="java.base,java.desktop,java.logging,java.management,java.naming,java.xml,jdk.crypto.ec,jdk.unsupported"

echo "==> [1/4] Maven 构建 server + 依赖"
mvn -q -pl gudesk-server -am package -DskipTests

echo "==> [2/4] jlink 裁剪运行时（模块: $RUNTIME_MODULES）"
rm -rf dist/server-runtime
"$JLINK" --add-modules "$RUNTIME_MODULES" --output dist/server-runtime \
    --strip-debug --no-header-files --no-man-pages --compress zip-6
echo "    运行时体积: $(du -sh dist/server-runtime | cut -f1)"

echo "==> [3/4] 组装解包目录"
STAGE="dist/gudesk-server-${VERSION}-linux-x64"
rm -rf "$STAGE"
mkdir -p "$STAGE/bin" "$STAGE/lib"
cp "gudesk-server/target/$SERVER_JAR" "$STAGE/lib/"
cp gudesk-server/target/lib/*.jar "$STAGE/lib/"
cp -a dist/server-runtime "$STAGE/lib/runtime"
cp packaging/gudesk-server.service "$STAGE/"

cat > "$STAGE/bin/gudesk-server" <<'EOF'
#!/usr/bin/env bash
# GuDesk 服务端启动脚本
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
exec "$DIR/lib/runtime/bin/java" -cp "$DIR/lib/*" com.gudesk.server.ServerApp "$@"
EOF
chmod +x "$STAGE/bin/gudesk-server"

cat > "$STAGE/install.sh" <<'EOF'
#!/usr/bin/env bash
# GuDesk 服务端安装脚本（需 root）：安装到 /opt/gudesk-server 并注册 systemd 服务
set -euo pipefail
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
INSTALL_DIR="/opt/gudesk-server"

if [ "$(id -u)" -ne 0 ]; then
    echo "[错误] 需要 root 权限（sudo ./install.sh）" >&2
    exit 1
fi

echo "安装到 $INSTALL_DIR ..."
rm -rf "$INSTALL_DIR"
cp -a "$DIR" "$INSTALL_DIR"
install -m644 "$INSTALL_DIR/gudesk-server.service" /etc/systemd/system/gudesk-server.service
systemctl daemon-reload
systemctl enable --now gudesk-server
echo
echo "已安装并启动。"
echo "  查看状态: systemctl status gudesk-server"
echo "  查看日志: journalctl -u gudesk-server -f"
echo "  自检:     $INSTALL_DIR/bin/gudesk-server --selftest"
EOF
chmod +x "$STAGE/install.sh"

echo "==> [4/4] 打 tar.gz"
tar -czf "$STAGE.tar.gz" -C dist "$(basename "$STAGE")"

echo
echo "==================== 打包结果 ===================="
echo "tar.gz : $STAGE.tar.gz  ($(du -h "$STAGE.tar.gz" | cut -f1))"
echo "目录    : $STAGE  ($(du -sh "$STAGE" | cut -f1))"
echo "================================================="
echo "本地冒烟: $STAGE/bin/gudesk-server --selftest"
echo "部署（服务器上，需 root）: 解压后 cd $STAGE && sudo ./install.sh"
