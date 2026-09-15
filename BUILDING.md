# GuDesk 构建文档

## 环境要求

- OS: Linux x64（交叉打包 Windows/Linux 目标包时见下方说明）
- JDK 21 (Zulu 21.52.15-ca, Java 21.0.12)：
  - 路径：`/home/cooper/MySoft/jdk/zulu21.52.15-ca-jdk21.0.12-linux_x64/`
- Maven 3.9+

## JDK 配置

构建时使用上述 JDK 21 路径，优先级从高到低：

1. 命令行临时指定（推荐，不污染全局环境）：

```bash
export JAVA_HOME=/home/cooper/MySoft/jdk/zulu21.52.15-ca-jdk21.0.12-linux_x64/
export PATH="$JAVA_HOME/bin:$PATH"
```

2. 写入 `~/.mavenrc`（对所有 Maven 构建生效）：

```bash
echo 'export JAVA_HOME=/home/cooper/MySoft/jdk/zulu21.52.15-ca-jdk21.0.12-linux_x64/' >> ~/.mavenrc
```

3. IDE 中将该路径配置为 Project SDK 与 Maven 的 JDK。

## 构建命令

```bash
export JAVA_HOME=/home/cooper/MySoft/jdk/zulu21.52.15-ca-jdk21.0.12-linux_x64/
export PATH="$JAVA_HOME/bin:$PATH"

# 全量构建（跳过测试）
mvn clean package -DskipTests

# 全量构建（含测试）
mvn clean package

# 仅编译
mvn compile

# 运行（验证版本信息输出）
java -jar gudesk-host/target/gudesk-host-0.1.0-SNAPSHOT.jar
java -jar gudesk-viewer/target/gudesk-viewer-0.1.0-SNAPSHOT.jar
java -jar gudesk-server/target/gudesk-server-0.1.0-SNAPSHOT.jar
```

## JVM 运行参数（客户端/服务端通用）

打包与启动脚本中统一配置：

```
-XX:+UseZGC -XX:+ZGenerational
--enable-native-access=ALL-UNNAMED   # JavaCV 堆外内存访问
-Xlog:gc*:file=gc.log:time,uptime:filecount=5,filesize=10m
```

## 打包

### 概览

- 合并应用 `GuDesk`：主入口 `com.gudesk.launcher.GuDeskLauncher`（`gudesk-launcher` 模块），
  无参数 = 主控端 UI + 后台被控服务；`--host-only` / `--viewer-only` / `--server-only`
  分模式（参数透传三个 App）；`--help` 查看完整用法。
- 体积控制：
  - **平台裁剪**：项目依赖同时声明 linux+windows 双平台原生 jar（Maven 显式 classifier），
    打包脚本组装 jpackage input 目录时按目标平台剔除另一半（Linux 剔除 `*windows*`、
    `javafx-*-win.jar`，Windows 反之剔除 `*linux*`）；
  - **运行时裁剪**：`jlink` 按实际 import 裁剪 JDK 模块（`java.base,java.desktop,
    java.logging,java.management,java.naming,java.xml,jdk.crypto.ec,jdk.unsupported`），
    `--strip-debug --compress zip-6`，59MB。
- 打包资产在 `packaging/`：`gudesk.desktop`（菜单项）、`gudesk-autostart.desktop`
  （XDG autostart 开机自启）、`gudesk.png`（图标）、`test-adapter/`（联调测试彩条捕获器
  TestPatternCapturer，随包携带、仅经 `GUDESK_ADAPTER_CAPTURER=<类名>` 环境变量指定时生效）。
- 开机自启（用户态）：deb 安装 `/etc/xdg/autostart/gudesk.desktop`（登录后自启被控服务，
  deb 内标记 conffile）；应用内 `gudesk --disable-autostart` / `--enable-autostart` 写
  `~/.config/autostart/gudesk.desktop`（XDG 规范：用户级同名文件优先于系统级，`Hidden=true` 禁用）。

### Linux（本机实测：EndeavourOS x64，JDK 21.0.12 + Maven 3.9.16）

```bash
export JAVA_HOME=/home/cooper/MySoft/jdk/zulu21.52.15-ca-jdk21.0.12-linux_x64/
./scripts/package-linux.sh
```

流程：`mvn clean package -DskipTests` → 组装 input（剔除 Windows jar）→ 编译测试适配器 →
`jlink` 裁剪运行时 → `jpackage --type app-image` → 构建 deb → 体积报告。可重复执行。

实测产物（2026-09-14）：

| 产物 | 体积 |
|---|---|
| `dist/gudesk/`（app-image，入口 `bin/gudesk`） | 108M |
| `dist/gudesk_0.1.0_amd64.deb` | **78M（≤80MB 目标）** |

deb 构建路径自动选择：Debian/Ubuntu（有 `dpkg-deb`）用 jpackage 原生 `--type deb` 并经
`dpkg-deb -R/-b` 重打包注入 XDG 文件与 `/usr/bin/gudesk` 符号链接；非 Debian 系（本机）
基于 app-image 用 `ar/tar` 手工构建同格式 deb（debian-binary 2.0 + control.tar.gz + data.tar.xz），
安装布局一致：

```
/opt/gudesk/                      # app-image（bin/gudesk、lib/app/*.jar、lib/runtime）
/usr/bin/gudesk -> /opt/gudesk/bin/gudesk
/usr/share/applications/gudesk.desktop        # 菜单项
/etc/xdg/autostart/gudesk.desktop             # 开机自启（conffile）
```

安装与冒烟（Debian 系）：

```bash
sudo dpkg -i dist/gudesk_0.1.0_amd64.deb
gudesk --server-only --selftest    # 服务器三件套自检（信令/STUN/中继，临时端口）
gudesk --server-only               # 默认端口 48900/3478/48910；冲突时 --signaling-port N --stun-port N --relay-port N
gudesk --host-only --set-password 123456
GUDESK_ADAPTER_CAPTURER=com.gudesk.test.TestPatternCapturer gudesk --host-only --auto-accept   # 打印被控 ID
gudesk --viewer-only --connect <被控ID> --password 123456 --auto 5                            # UDP 直连冒烟
```

注意：被控端/主控端从信令服务器主机**推导默认 STUN(3478)/中继(48910) 端口**（代码内固定），
自定义服务器端口时 STUN/中继仍走默认端口。无图形环境（Wayland 抓屏受限）冒烟用
`GUDESK_ADAPTER_CAPTURER=com.gudesk.test.TestPatternCapturer` 注入彩条捕获器。

本机实测冒烟（app-image 直跑，deb 同布局）：`--server-only --selftest` SELFTEST OK；
信令 48902 起服务器 → 被控端注册（ID 893624855）→ 主控端按 ID 连接 **UDP 直连成功**，
收流 5s：145 帧 / 30.0 fps / 1280x720 / 帧延迟 avg 4ms，退出码 0。

### Windows（需在 Windows x64 上执行，本机无法产出 msi）

前提：
1. JDK 21（`JAVA_HOME` 指向，含 `bin\jpackage.exe`）
2. Maven 3.9+ 在 PATH
3. **WiX Toolset 3.x**（jpackage `--type msi` 依赖 candle/light；WiX 4/5 与 JDK 21 不兼容）：
   <https://github.com/wixtoolset/wix3/releases>

```powershell
powershell -ExecutionPolicy Bypass -File scripts\package-windows.ps1
```

流程与 Linux 一致（剔除 `*linux*` jar 保留 win 平台），产出 `dist\gudesk-0.1.0.msi`
（`--win-dir-chooser --win-menu --win-shortcut`）与 app-image（`dist\gudesk\gudesk.exe`
可先本机验证）。Windows 端开机自启（注册表 Run 键/启动文件夹）未内置，后续版本提供。

> 注：当前使用系统 `mvn`（Apache Maven 3.9.16，位于 `/home/cooper/MySoft/idea-IU-262.9437.185/plugins/maven-plugin/lib/maven3`，已加入 PATH），未生成 Maven Wrapper。
