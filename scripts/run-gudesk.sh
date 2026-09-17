#!/usr/bin/env bash
# =============================================================================
# GuDesk 客户端开发运行脚本（桌面图标/应用菜单启动入口）
#
# 用法：scripts/run-gudesk.sh [参数...]（参数透传 GuDeskLauncher，如 --server host:port）
# 前提：已执行过 mvn package（生成 gudesk-launcher/target/classes 与 target/lib）
# =============================================================================
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

export JAVA_HOME=/home/cooper/MySoft/jdk/zulu21.52.15-ca-jdk21.0.12-linux_x64/
export PATH="$JAVA_HOME/bin:$PATH"

MP="$ROOT/gudesk-launcher/target/lib/javafx-base-21-linux.jar:$ROOT/gudesk-launcher/target/lib/javafx-graphics-21-linux.jar:$ROOT/gudesk-launcher/target/lib/javafx-controls-21-linux.jar"

# 项目自身模块用各自 target/classes（fresh），第三方依赖用 target/lib（javafx 走 module-path）
CP="$ROOT/gudesk-launcher/target/classes:$ROOT/gudesk-host/target/classes:$ROOT/gudesk-viewer/target/classes:$ROOT/gudesk-common/target/classes:$ROOT/gudesk-server/target/classes"
for jar in "$ROOT"/gudesk-launcher/target/lib/*.jar; do
    case "$(basename "$jar")" in
        javafx-*|gudesk-*) : ;;
        *) CP="$CP:$jar" ;;
    esac
done

exec java --module-path "$MP" --add-modules javafx.controls \
    --enable-native-access=ALL-UNNAMED \
    -cp "$CP" com.gudesk.launcher.GuDeskLauncher "$@"
