# =============================================================================
# GuDesk Windows 打包脚本 —— 必须在 Windows x64 上运行（本机 Linux 无法产出 msi）
#
# 前提条件：
#   1. JDK 21（含 jpackage.exe/jlink.exe/jar.exe/javac.exe）：设置 JAVA_HOME 指向它
#      （例如 Zulu 21：https://www.azul.com/downloads/?version=21&os=windows&package=jdk）
#   2. Maven 3.9+ 在 PATH 中
#   3. WiX Toolset 3.x（jpackage --type msi 依赖 candle.exe/light.exe 在 PATH）：
#      下载 https://github.com/wixtoolset/wix3/releases（选 wix314.exe 或 wix311.exe）
#      注意：WiX 4/5 与 JDK 21 jpackage 不兼容，必须 3.x
#   4. 项目 Maven 依赖已声明 windows 平台 classifier（ffmpeg windows-x86_64-gpl、
#      javacpp windows-x86_64、javafx-*-win），首次构建会自动下载
#
# 用法（项目根目录）：
#   powershell -ExecutionPolicy Bypass -File scripts\package-windows.ps1
#
# 流程：Maven 构建 → 依赖平台裁剪（剔除 Linux 平台 jar）→ 编译测试适配器
#       → jlink 裁剪运行时 → jpackage msi（--win-dir-chooser --win-menu --win-shortcut）
#
# 产物：
#   dist\gudesk-0.1.0.msi     Windows 安装包
#   dist\gudesk\              app-image（dist\gudesk\gudesk.exe 可先本机验证）
#
# 说明：
#   - 应用入口统一为 GuDeskLauncher：gudesk.exe（无参数=主控端 UI+后台被控服务；
#     --host-only / --viewer-only / --server-only 分模式，--help 查看用法）
#   - Windows 端开机自启（注册表 Run 键 / 启动文件夹）不在本脚本范围，
#     后续版本可在安装后提供（Linux 端为 XDG autostart，已内置）
#   - 联调测试适配器（TestPatternCapturer）随包携带，仅经
#     GUDESK_ADAPTER_CAPTURER=com.gudesk.test.TestPatternCapturer 指定时生效
# =============================================================================
$ErrorActionPreference = "Stop"
Set-Location (Join-Path $PSScriptRoot "..")

if (-not $env:JAVA_HOME -or -not (Test-Path (Join-Path $env:JAVA_HOME "bin\jpackage.exe"))) {
    Write-Error "JAVA_HOME 未指向有效 JDK 21（需含 bin\jpackage.exe）"
}
$Jpackage = Join-Path $env:JAVA_HOME "bin\jpackage.exe"
$Jlink    = Join-Path $env:JAVA_HOME "bin\jlink.exe"
$Jar      = Join-Path $env:JAVA_HOME "bin\jar.exe"
$Javac    = Join-Path $env:JAVA_HOME "bin\javac.exe"

$AppName      = "gudesk"
$Version      = "0.1.0"
$MainJar      = "gudesk-launcher-0.1.0-SNAPSHOT.jar"
$MainClass    = "com.gudesk.launcher.GuDeskLauncher"
# 运行时 JDK 模块裁剪（与 Linux 脚本一致；缺模块时运行报错再补）
$RuntimeModules = "java.base,java.desktop,java.logging,java.management,java.naming,java.xml,jdk.crypto.ec,jdk.unsupported"

Write-Host "==> [1/6] Maven 构建（跳过测试）"
mvn -q clean package -DskipTests
if ($LASTEXITCODE -ne 0) { Write-Error "Maven 构建失败" }

Write-Host "==> [2/6] 组装 jpackage input 目录（剔除 Linux 平台 jar）"
if (Test-Path dist) { Remove-Item -Recurse -Force dist }
New-Item -ItemType Directory -Path dist\jpackage-input | Out-Null
Copy-Item "gudesk-launcher\target\$MainJar" dist\jpackage-input\
$copied = 1; $skipped = 0
Get-ChildItem gudesk-launcher\target\lib\*.jar | ForEach-Object {
    if ($_.Name -match "linux") {
        Write-Host "    剔除(Linux 平台): $($_.Name)"
        $skipped++
    } else {
        Copy-Item $_ dist\jpackage-input\
        $copied++
    }
}
Write-Host "    保留 $copied 个 jar（含主 jar），剔除 $skipped 个 Linux 平台 jar"

Write-Host "==> [3/6] 编译联调测试适配器（TestPatternCapturer）"
New-Item -ItemType Directory -Path dist\test-adapter-classes -Force | Out-Null
$commonJar = (Get-Item dist\jpackage-input\gudesk-common-*.jar).FullName
& $Javac -cp $commonJar -d dist\test-adapter-classes `
    packaging\test-adapter\com\gudesk\test\TestPatternCapturer.java
if ($LASTEXITCODE -ne 0) { Write-Error "测试适配器编译失败" }
& $Jar --create --file dist\jpackage-input\gudesk-test-adapter.jar -C dist\test-adapter-classes .
if ($LASTEXITCODE -ne 0) { Write-Error "测试适配器 jar 打包失败" }

Write-Host "==> [4/6] jlink 裁剪运行时（模块: $RuntimeModules）"
& $Jlink --add-modules $RuntimeModules --output dist\runtime `
    --strip-debug --no-header-files --no-man-pages --compress zip-6
if ($LASTEXITCODE -ne 0) { Write-Error "jlink 失败" }

Write-Host "==> [5/6] jpackage app-image（dist\$AppName）"
& $Jpackage --name $AppName --type app-image `
    --input dist\jpackage-input `
    --main-jar $MainJar --main-class $MainClass `
    --runtime-image dist\runtime `
    --dest dist --app-version $Version `
    --java-options=-XX:+UseZGC `
    --java-options=--enable-native-access=ALL-UNNAMED `
    --java-options=-Xmx1g
if ($LASTEXITCODE -ne 0) { Write-Error "jpackage app-image 失败" }
Copy-Item packaging\gudesk.png "dist\$AppName\lib\gudesk.png" -ErrorAction SilentlyContinue

Write-Host "==> [6/6] jpackage msi（需 WiX Toolset 3.x 在 PATH）"
& $Jpackage --name $AppName --type msi `
    --input dist\jpackage-input `
    --main-jar $MainJar --main-class $MainClass `
    --runtime-image dist\runtime `
    --dest dist --app-version $Version `
    --win-dir-chooser --win-menu --win-menu-group GuDesk --win-shortcut `
    --java-options "-XX:+UseZGC" `
    --java-options "--enable-native-access=ALL-UNNAMED" `
    --java-options "-Xmx1g"
if ($LASTEXITCODE -ne 0) {
    Write-Error "jpackage msi 失败（确认 WiX Toolset 3.x 已安装且 candle/light 在 PATH）"
}

Write-Host ""
Write-Host "==================== 打包结果 ===================="
Get-ChildItem dist\*.msi | ForEach-Object {
    Write-Host ("msi      : {0}  ({1:N1} MB)" -f $_.FullName, ($_.Length / 1MB))
}
Write-Host ("app-image: dist\{0}\{0}.exe（可直接运行验证）" -f $AppName)
Write-Host "================================================="
