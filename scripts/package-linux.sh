#!/usr/bin/env bash
# =============================================================================
# GuDesk Linux 打包脚本（可重复执行）
#
# 流程：Maven 构建 → 依赖平台裁剪（剔除 Windows jar）→ 编译联调测试适配器
#       → jlink 裁剪运行时 → jpackage app-image → 构建 deb → 体积报告
#
# 产物：
#   dist/gudesk/                  app-image（bin/gudesk 可直接运行，可先冒烟验证）
#   dist/gudesk_<version>_amd64.deb   deb 安装包
#
# deb 构建两条路径（自动选择）：
#   1) Debian/Ubuntu（有 dpkg-deb）：jpackage --type deb + dpkg-deb 重打包，
#      注入 XDG desktop 文件（/usr/share/applications 菜单项、/etc/xdg/autostart
#      开机自启）与 /usr/bin/gudesk 符号链接；
#   2) 非 Debian 系（无 dpkg-deb，如 Arch/EndeavourOS）：基于 app-image 用
#      ar/tar 手工构建同格式 deb（debian-binary 2.0 + control.tar.gz + data.tar.xz），
#      内容物与路径布局一致（/opt/gudesk）。
#
# 平台裁剪说明：项目依赖同时携带 linux+windows 双平台原生库 jar（Maven 显式
# classifier），本脚本在组装 jpackage input 目录时按目标平台剔除另一半
# （Windows 包见 scripts/package-windows.ps1，剔除规则相反），控制包体积。
# =============================================================================
set -euo pipefail
cd "$(dirname "$0")/.."

# --- JDK 21（jpackage/jlink/jar/javac；必须是 21，17 的 jpackage 会导致 release 21 编译失败）---
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
# 运行时 JDK 模块裁剪（按代码实际 import 决定，缺了运行时报错再补）：
#   java.desktop   Swing/AWT（host 授权弹窗、JavaFX 依赖）
#   java.logging/java.management/java.naming/java.xml  Netty resolver / log4j2
#   jdk.crypto.ec  ECDH 密钥交换（CryptoUtil/SessionHandshake）
#   jdk.unsupported  Netty/JavaCPP 的 sun.misc.Unsafe、sun.nio.ch
RUNTIME_MODULES="java.base,java.desktop,java.logging,java.management,java.naming,java.xml,jdk.crypto.ec,jdk.unsupported"

echo "==> [1/6] Maven 构建（跳过测试；全量回归请另行执行 mvn clean package）"
mvn -q clean package -DskipTests

echo "==> [1/6] 编译 portal helper（Wayland 真实捕获用，动态链接 libpipewire）"
for dep in gcc pkg-config; do
    command -v "$dep" >/dev/null 2>&1 || {
        echo "[错误] 缺少 $dep，无法编译 gudesk-portal-helper（安装后重试）"; exit 1; }
done
pkg-config --exists libpipewire-0.3 || {
    echo "[错误] 缺少 libpipewire-0.3 开发包（Debian/Ubuntu: libpipewire-0.3-dev）"; exit 1; }
make -C native/portal-helper clean all
echo "    helper 产物: native/portal-helper/gudesk-portal-helper"

echo "==> [2/6] 组装 jpackage input 目录（剔除 Windows 平台 jar）"
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

echo "==> [3/6] 编译联调测试适配器（TestPatternCapturer，无图形环境冒烟用）"
rm -rf dist/test-adapter-classes
mkdir -p dist/test-adapter-classes
"$JAVAC" -cp dist/jpackage-input/gudesk-common-*.jar -d dist/test-adapter-classes \
    packaging/test-adapter/com/gudesk/test/TestPatternCapturer.java
"$JAR" --create --file dist/jpackage-input/gudesk-test-adapter.jar -C dist/test-adapter-classes .
echo "    gudesk-test-adapter.jar 已加入 input（SPI 门控：仅 GUDESK_ADAPTER_CAPTURER 指定时生效）"

echo "==> [4/6] jlink 裁剪运行时（模块: $RUNTIME_MODULES）"
rm -rf dist/runtime
"$JLINK" --add-modules "$RUNTIME_MODULES" --output dist/runtime \
    --strip-debug --no-header-files --no-man-pages --compress zip-6
echo "    运行时体积: $(du -sh dist/runtime | cut -f1)"

echo "==> [5/6] jpackage app-image（dist/$APP_NAME）"
"$JPACKAGE" --name "$APP_NAME" --type app-image \
    --input dist/jpackage-input \
    --main-jar "$MAIN_JAR" --main-class "$MAIN_CLASS" \
    --runtime-image dist/runtime \
    --dest dist --app-version "$VERSION" \
    --java-options "-XX:+UseZGC" \
    --java-options "--enable-native-access=ALL-UNNAMED" \
    --java-options "-Xmx1g"
# 应用图标放入 app-image（deb/desktop 文件引用 /opt/gudesk/lib/gudesk.png）
cp packaging/gudesk.png "dist/$APP_NAME/lib/gudesk.png"
echo "    app-image 体积: $(du -sh "dist/$APP_NAME" | cut -f1)（入口 dist/$APP_NAME/bin/$APP_NAME）"

echo "==> [6/6] 构建 deb"
if command -v dpkg-deb >/dev/null 2>&1; then
    build_deb_with_jpackage() {
        echo "    检测到 dpkg-deb：jpackage 原生 deb + 重打包注入 XDG 文件"
        "$JPACKAGE" --name "$APP_NAME" --type deb \
            --input dist/jpackage-input \
            --main-jar "$MAIN_JAR" --main-class "$MAIN_CLASS" \
            --runtime-image dist/runtime \
            --dest dist --app-version "$VERSION" \
            --linux-install-dir /opt \
            --java-options "-XX:+UseZGC" \
            --java-options "--enable-native-access=ALL-UNNAMED" \
            --java-options "-Xmx1g"
        local deb
        deb=$(ls dist/"${APP_NAME}"_*.deb | head -1)
        rm -rf dist/deb-root
        dpkg-deb -R "$deb" dist/deb-root
        # 追加 helper 运行时依赖（jpackage 生成的 control 无 Depends 字段）
        if ! grep -q '^Depends:' dist/deb-root/DEBIAN/control; then
            sed -i '/^Package:/a Depends: libpipewire-0.3-0' dist/deb-root/DEBIAN/control
        fi
        install -Dm644 packaging/gudesk.desktop dist/deb-root/usr/share/applications/gudesk.desktop
        install -Dm644 packaging/gudesk-autostart.desktop dist/deb-root/etc/xdg/autostart/gudesk.desktop
        install -Dm644 packaging/gudesk.png "dist/deb-root/opt/$APP_NAME/lib/gudesk.png"
        install -Dm755 native/portal-helper/gudesk-portal-helper dist/deb-root/usr/lib/gudesk/gudesk-portal-helper
        mkdir -p dist/deb-root/usr/bin
        ln -sf "/opt/$APP_NAME/bin/$APP_NAME" "dist/deb-root/usr/bin/$APP_NAME"
        dpkg-deb -b dist/deb-root "$deb"
        echo "$deb"
    }
    DEB_FILE=$(build_deb_with_jpackage)
else
    build_deb_with_ar() {
        echo "    未检测到 dpkg-deb（非 Debian 系）：基于 app-image 用 ar/tar 手工构建 deb" >&2
        local staging=dist/deb-staging control_dir=dist/deb-control
        rm -rf "$staging" "$control_dir"
        # data：/opt/gudesk（app-image）+ desktop 文件 + /usr/bin 符号链接 + portal helper
        mkdir -p "$staging/opt/$APP_NAME" "$staging/usr/share/applications" \
            "$staging/etc/xdg/autostart" "$staging/usr/bin" "$staging/usr/lib/gudesk"
        cp -a "dist/$APP_NAME/." "$staging/opt/$APP_NAME/"
        cp packaging/gudesk.desktop "$staging/usr/share/applications/gudesk.desktop"
        cp packaging/gudesk-autostart.desktop "$staging/etc/xdg/autostart/gudesk.desktop"
        install -m755 native/portal-helper/gudesk-portal-helper "$staging/usr/lib/gudesk/gudesk-portal-helper"
        ln -s "/opt/$APP_NAME/bin/$APP_NAME" "$staging/usr/bin/$APP_NAME"
        # control
        mkdir -p "$control_dir"
        local installed_kb
        installed_kb=$(du -sk "$staging" | cut -f1)
        cat > "$control_dir/control" <<CONTROL
Package: $APP_NAME
Version: $VERSION
Architecture: amd64
Maintainer: GuDesk Project <dev@gudesk.example>
Section: net
Priority: optional
Depends: libpipewire-0.3-0
Installed-Size: $installed_kb
Description: GuDesk remote desktop (viewer + host + server)
 GuDesk cross-platform remote desktop: viewer UI, host service and
 signaling/STUN/relay server in one package. XDG autostart of the host
 service is configured in /etc/xdg/autostart; per-user toggle via
 'gudesk --disable-autostart' / '--enable-autostart'.
CONTROL
        printf '/etc/xdg/autostart/gudesk.desktop\n' > "$control_dir/conffiles"
        tar --owner=0 --group=0 --numeric-owner -czf dist/control.tar.gz -C "$control_dir" .
        tar --owner=0 --group=0 --numeric-owner -cJf dist/data.tar.xz -C "$staging" .
        printf '2.0\n' > dist/debian-binary
        local deb="dist/${APP_NAME}_${VERSION}_amd64.deb"
        rm -f "$deb"
        ar rc "$deb" dist/debian-binary dist/control.tar.gz dist/data.tar.xz
        rm -rf "$staging" "$control_dir" dist/debian-binary dist/control.tar.gz dist/data.tar.xz
        echo "$deb"
    }
    DEB_FILE=$(build_deb_with_ar)
fi

echo
echo "==================== 打包结果 ===================="
echo "app-image : dist/$APP_NAME  ($(du -sh "dist/$APP_NAME" | cut -f1))"
echo "deb       : $DEB_FILE  ($(du -h "$DEB_FILE" | cut -f1))"
echo "目标体积 ≤ 80MB：$(du -m "$DEB_FILE" | cut -f1)MB"
echo "================================================="
echo "deb 内容物（ar 归档成员）:"
ar t "$DEB_FILE" 2>/dev/null || dpkg-deb -c "$DEB_FILE" 2>/dev/null | head -5 || true
echo
echo "冒烟提示（安装 deb 后，或直接对 app-image 执行 dist/$APP_NAME/bin/$APP_NAME ...）:"
echo "  1) $APP_NAME --server-only --selftest                       # 服务器三件套自检"
echo "  2) $APP_NAME --server-only --signaling-port 48902 --stun-port 3479 --relay-port 48912"
echo "  3) $APP_NAME --host-only --set-password 123456              # 设定被控密码"
echo "  4) GUDESK_ADAPTER_CAPTURER=com.gudesk.test.TestPatternCapturer \\"
echo "     $APP_NAME --host-only --auto-accept --port 48903 --server 127.0.0.1:48902   # 记录打印的被控 ID"
echo "  5) $APP_NAME --viewer-only --connect <被控ID> --password 123456 --server 127.0.0.1:48902 --auto 5"
