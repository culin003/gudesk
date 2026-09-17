#!/usr/bin/env bash
# =============================================================================
# GuDesk Linux 自包含 tar.gz 打包脚本（跨发行版，无需 root 安装）
#
# 流程：Maven 构建 → 编译 portal helper → 依赖平台裁剪（剔除 Windows jar）
#       → 编译测试适配器 → jlink 裁剪运行时 → jpackage app-image
#       → helper 内嵌 lib/ → 组装 install.sh + app-image → tar.gz
#
# 产物：
#   dist/gudesk-<version>-linux-x64.tar.gz   自包含包（解压后 ./install.sh 安装）
#   dist/gudesk-<version>-linux-x64/         解包目录（install.sh + gudesk/ app-image）
#
# 唯一运行时要求：glibc + libpipewire-0.3（仅 Wayland 抓屏需要）
# =============================================================================
set -euo pipefail
cd "$(dirname "$0")/.."

# --- JDK 21（jpackage/jlink/jar/javac）---
if [ -z "${JAVA_HOME:-}" ] || ! "${JAVA_HOME:-}/bin/java" -version 2>&1 | grep -q 'version "21'; then
    export JAVA_HOME=/home/cooper/MySoft/jdk/zulu21.52.15-ca-jdk21.0.12-linux_x64/
fi
export PATH="$JAVA_HOME/bin:$PATH"
JPACKAGE="$JAVA_HOME/bin/jpackage"
JLINK="$JAVA_HOME/bin/jlink"
JAR="$JAVA_HOME/bin/jar"
JAVAC="$JAVA_HOME/bin/javac"
for tool in "$JPACKAGE" "$JLINK" "$JAR" "$JAVAC"; do
    [ -x "$tool" ] || { echo "[错误] 缺少工具: $tool（检查 JAVA_HOME=$JAVA_HOME）"; exit 1; }
done

APP_NAME="gudesk"
VERSION="0.1.0"
MAIN_JAR="gudesk-launcher-0.1.0-SNAPSHOT.jar"
MAIN_CLASS="com.gudesk.launcher.GuDeskLauncher"
# 运行时 JDK 模块裁剪（与 deb 脚本一致；缺模块时运行报错再补）
#   jdk.security.auth   dbus-java SASL external 认证需要 com.sun.security.auth.module.UnixSystem
RUNTIME_MODULES="java.base,java.desktop,java.logging,java.management,java.naming,java.xml,jdk.crypto.ec,jdk.security.auth,jdk.unsupported"

echo "==> [1/7] Maven 构建（跳过测试）"
mvn -q clean package -DskipTests

echo "==> [2/7] 编译 portal helper（Wayland 真实捕获用，动态链接 libpipewire）"
for dep in gcc pkg-config; do
    command -v "$dep" >/dev/null 2>&1 || {
        echo "[错误] 缺少 $dep，无法编译 gudesk-portal-helper（安装后重试）"; exit 1; }
done
pkg-config --exists libpipewire-0.3 || {
    echo "[错误] 缺少 libpipewire-0.3 开发包（Debian/Ubuntu: libpipewire-0.3-dev）"; exit 1; }
make -C native/portal-helper clean all

echo "==> [3/7] 组装 jpackage input 目录（剔除 Windows 平台 jar）"
rm -rf dist
mkdir -p dist/jpackage-input
cp "gudesk-launcher/target/$MAIN_JAR" dist/jpackage-input/
copied=1; skipped=0
for jar in gudesk-launcher/target/lib/*.jar; do
    base=$(basename "$jar")
    case "$base" in
        *windows*|*-win.jar)
            echo "    剔除(Windows 平台): $base"
            skipped=$((skipped + 1));;
        *)
            cp "$jar" dist/jpackage-input/
            copied=$((copied + 1));;
    esac
done
echo "    保留 $copied 个 jar（含主 jar），剔除 $skipped 个 Windows 平台 jar"

echo "==> [4/7] 编译联调测试适配器（TestPatternCapturer）"
rm -rf dist/test-adapter-classes
mkdir -p dist/test-adapter-classes
"$JAVAC" -cp dist/jpackage-input/gudesk-common-*.jar -d dist/test-adapter-classes \
    packaging/test-adapter/com/gudesk/test/TestPatternCapturer.java
"$JAR" --create --file dist/jpackage-input/gudesk-test-adapter.jar -C dist/test-adapter-classes .

echo "==> [5/7] jlink 裁剪运行时（模块: $RUNTIME_MODULES）"
rm -rf dist/runtime
"$JLINK" --add-modules "$RUNTIME_MODULES" --output dist/runtime \
    --strip-debug --no-header-files --no-man-pages --compress zip-6
mkdir -p dist/runtime/lib/fonts
install -m644 packaging/fonts/NotoSansCJK-Regular.ttc dist/runtime/lib/fonts/

echo "==> [6/7] jpackage app-image（dist/$APP_NAME）"
"$JPACKAGE" --name "$APP_NAME" --type app-image \
    --input dist/jpackage-input \
    --main-jar "$MAIN_JAR" --main-class "$MAIN_CLASS" \
    --runtime-image dist/runtime \
    --dest dist --app-version "$VERSION" \
    --java-options "-XX:+UseZGC" \
    --java-options "--enable-native-access=ALL-UNNAMED" \
    --java-options "-Xmx1g"
cp packaging/gudesk.png "dist/$APP_NAME/lib/gudesk.png"

echo "==> [7/7] 组装自包含 tar.gz（helper 内嵌 lib/ + install.sh）"
STAGE="dist/gudesk-${VERSION}-linux-x64"
rm -rf "$STAGE"
mkdir -p "$STAGE"
cp -a "dist/$APP_NAME" "$STAGE/"
install -m755 native/portal-helper/gudesk-portal-helper "$STAGE/$APP_NAME/lib/gudesk-portal-helper"
install -m755 scripts/install.sh "$STAGE/install.sh"
tar -czf "$STAGE.tar.gz" -C dist "$(basename "$STAGE")"

echo
echo "==================== 打包结果 ===================="
echo "tar.gz    : $STAGE.tar.gz  ($(du -h "$STAGE.tar.gz" | cut -f1))"
echo "解包目录   : $STAGE"
echo "app-image : $STAGE/$APP_NAME  ($(du -sh "$STAGE/$APP_NAME" | cut -f1))"
echo "================================================="
echo "安装（解压后进入 $STAGE 目录）:"
echo "  ./install.sh               # 安装到 ~/.local/opt/gudesk"
echo "  ./install.sh --uninstall   # 卸载"
