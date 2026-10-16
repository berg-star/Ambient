param()
$ErrorActionPreference = 'Stop'
$AmbientRoot = Split-Path -Parent $PSScriptRoot
$AmbientConfig = Join-Path $AmbientRoot 'toolchain.local.ps1'
if (Test-Path -LiteralPath $AmbientConfig) { . $AmbientConfig }
if (-not $AmbientJdk) { $AmbientJdk = $env:JAVA_HOME }
if (-not $AmbientSdk) { $AmbientSdk = $env:ANDROID_HOME }
if (-not $AmbientJdk -or -not (Test-Path -LiteralPath (Join-Path $AmbientJdk 'bin\javac.exe'))) {
    throw '请在 toolchain.local.ps1 设置 $AmbientJdk 为 JDK 17 目录。'
}
if (-not $AmbientSdk -or -not (Test-Path -LiteralPath (Join-Path $AmbientSdk 'platforms\android-36\android.jar'))) {
    throw '请在 toolchain.local.ps1 设置 $AmbientSdk，需包含 Android SDK 36。'
}
$env:JAVA_HOME = $AmbientJdk
$env:ANDROID_HOME = $AmbientSdk
$env:ANDROID_SDK_ROOT = $AmbientSdk
$env:GRADLE_USER_HOME = Join-Path $AmbientRoot '.gradle-user-home'
$env:ANDROID_USER_HOME = Join-Path $AmbientRoot '.tools\android-user-home'
$env:ANDROID_EMULATOR_HOME = Join-Path $AmbientRoot '.tools\emulator-home'
$env:ANDROID_AVD_HOME = Join-Path $AmbientRoot '.tools\avd'
# Existing Windows emulator installation requires this to avoid crashpad/JIT popups.
$env:ANDROID_EMU_ENABLE_CRASH_REPORTING = '0'
$env:TEMP = Join-Path $AmbientRoot '.tools\tmp'
$env:TMP = $env:TEMP
$env:PATH = "$AmbientJdk\bin;$AmbientSdk\platform-tools;$env:PATH"
foreach ($AmbientDirectory in @($env:GRADLE_USER_HOME, $env:ANDROID_USER_HOME, $env:ANDROID_EMULATOR_HOME, $env:ANDROID_AVD_HOME, $env:TEMP)) {
    New-Item -ItemType Directory -Force -Path $AmbientDirectory | Out-Null
}
