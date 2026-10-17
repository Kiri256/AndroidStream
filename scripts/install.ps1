param([string]$Serial)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$sdkRoot = $env:ANDROID_HOME
if (-not $sdkRoot) { $sdkRoot = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$adbPath = Join-Path $sdkRoot 'platform-tools\adb.exe'
if (-not (Test-Path -LiteralPath $adbPath)) { throw '找不到 adb，请设置 ANDROID_HOME。' }
$apkPath = Join-Path $projectRoot 'artifacts\AndroidStream-0.1.30-test.apk'
if (-not (Test-Path -LiteralPath $apkPath)) { throw '请先运行 scripts/build.ps1。' }
$deviceArguments = @()
if ($Serial) { $deviceArguments = @('-s', $Serial) }
& $adbPath @deviceArguments install -r $apkPath
if ($LASTEXITCODE -ne 0) { throw 'APK 安装失败，请检查 USB 调试授权与设备连接。' }
& $adbPath @deviceArguments shell am start -n dev.androidstream/.MainActivity
if ($LASTEXITCODE -ne 0) { throw '应用启动失败。' }
