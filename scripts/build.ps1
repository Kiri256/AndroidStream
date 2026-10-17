param([switch]$SkipChecks)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
if (-not $env:JAVA_HOME) {
    $studioJbr = Join-Path $env:ProgramFiles 'Android\Android Studio\jbr'
    if (Test-Path -LiteralPath (Join-Path $studioJbr 'bin\java.exe')) { $env:JAVA_HOME = $studioJbr }
}
if (-not $env:ANDROID_HOME) {
    $localSdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
    if (Test-Path -LiteralPath $localSdk) { $env:ANDROID_HOME = $localSdk }
}
if (-not $env:JAVA_HOME) { throw '请设置 JAVA_HOME 为 JDK 17 或更新版本，推荐 Android Studio 自带 JBR。' }
if (-not $env:ANDROID_HOME) { throw '请安装 Android SDK 并设置 ANDROID_HOME，或使用 local.properties 配置 sdk.dir。' }
Push-Location $projectRoot
try {
    $buildTasks = @(':android:assembleDebug')
    if (-not $SkipChecks) { $buildTasks += @(':android:testDebugUnitTest', ':android:lintDebug') }
    & .\gradlew.bat @buildTasks --console=plain
    if ($LASTEXITCODE -ne 0) { throw "Gradle 失败，退出码 $LASTEXITCODE" }
    $artifactDirectory = Join-Path $projectRoot 'artifacts'
    New-Item -ItemType Directory -Path $artifactDirectory -Force | Out-Null
    $artifactPath = Join-Path $artifactDirectory 'AndroidStream-0.1.30-test.apk'
    Copy-Item -LiteralPath 'android\build\outputs\apk\debug\android-debug.apk' -Destination $artifactPath -Force
    Get-Item -LiteralPath $artifactPath | Select-Object FullName,Length
    Get-FileHash -LiteralPath $artifactPath -Algorithm SHA256 | Format-List Algorithm,Hash
} finally { Pop-Location }
