param(
    [float]$Speed = 5.144444,
    [switch]$NoSpeed,
    [float]$Accuracy = 0.25,
    [switch]$NoAccuracy,
    [ValidateRange(0, 86400)][float]$AgeSeconds = 0,
    [ValidateRange(0, 86400)][float]$FutureSeconds = 0,
    [switch]$Reset
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'environment.ps1')

# This independent APK is an emulator test fixture, never an Ambient app dependency.
$AmbientFixtureSerial = 'emulator-5560'
$AmbientFixtureAvd = 'Ambient_API_31'
$AmbientFixtureAdb = Join-Path $AmbientSdk 'platform-tools\adb.exe'
$AmbientFixtureBuildTools = Join-Path $AmbientSdk 'build-tools\35.0.0'
$AmbientFixtureAndroidJar = Join-Path $AmbientSdk 'platforms\android-36\android.jar'
$AmbientFixtureSource = Join-Path $AmbientRoot 'test-fixtures\location'
$AmbientFixtureOutput = Join-Path $AmbientRoot '.tools\location-fixture'
$AmbientFixturePackage = 'com.ambient.locationfixture'
$AmbientFixtureApk = Join-Path $AmbientFixtureOutput 'location-fixture.apk'
$AmbientFixtureCulture = [Globalization.CultureInfo]::InvariantCulture

function Invoke-AmbientFixtureTool {
    param([string]$Tool, [string[]]$Arguments)
    & $Tool @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Fixture tool failed ($LASTEXITCODE): $Tool" }
}

# A hardcoded serial plus an AVD-name check prevents installing this helper on a real phone.
$AmbientFixtureDevices = @(& $AmbientFixtureAdb devices)
if ($LASTEXITCODE -ne 0 -or -not ($AmbientFixtureDevices -match "^$AmbientFixtureSerial\s+device\s*$")) {
    throw 'Ambient emulator is not ready. Run scripts/start-emulator.ps1 first.'
}
$AmbientFixtureRunningAvd = (& $AmbientFixtureAdb -s $AmbientFixtureSerial emu avd name | Select-Object -First 1).Trim()
if ($LASTEXITCODE -ne 0 -or $AmbientFixtureRunningAvd -ne $AmbientFixtureAvd) {
    throw 'Port 5560 belongs to another virtual device. No device was changed.'
}

New-Item -ItemType Directory -Force -Path $AmbientFixtureOutput | Out-Null
$AmbientFixtureClasses = Join-Path $AmbientFixtureOutput 'classes'
$AmbientFixtureDex = Join-Path $AmbientFixtureOutput 'dex'
New-Item -ItemType Directory -Force -Path $AmbientFixtureClasses, $AmbientFixtureDex | Out-Null
$AmbientFixtureUnsigned = Join-Path $AmbientFixtureOutput 'unsigned.apk'
$AmbientFixtureAligned = Join-Path $AmbientFixtureOutput 'aligned.apk'

Invoke-AmbientFixtureTool (Join-Path $AmbientJdk 'bin\javac.exe') @(
    '--release', '8', '-classpath', $AmbientFixtureAndroidJar,
    '-d', $AmbientFixtureClasses, (Join-Path $AmbientFixtureSource 'FixtureActivity.java')
)
$AmbientFixtureClassFiles = @(Get-ChildItem -LiteralPath $AmbientFixtureClasses -Filter '*.class' -Recurse | ForEach-Object { $_.FullName })
Invoke-AmbientFixtureTool (Join-Path $AmbientFixtureBuildTools 'd8.bat') (@(
    '--min-api', '26', '--lib', $AmbientFixtureAndroidJar, '--output', $AmbientFixtureDex
) + $AmbientFixtureClassFiles)
Invoke-AmbientFixtureTool (Join-Path $AmbientFixtureBuildTools 'aapt2.exe') @(
    'link', '-o', $AmbientFixtureUnsigned,
    '--manifest', (Join-Path $AmbientFixtureSource 'AndroidManifest.xml'),
    '-I', $AmbientFixtureAndroidJar, '--min-sdk-version', '26', '--target-sdk-version', '28'
)
Invoke-AmbientFixtureTool (Join-Path $AmbientJdk 'bin\jar.exe') @(
    'uf', $AmbientFixtureUnsigned, '-C', $AmbientFixtureDex, 'classes.dex'
)
Invoke-AmbientFixtureTool (Join-Path $AmbientFixtureBuildTools 'zipalign.exe') @(
    '-f', '4', $AmbientFixtureUnsigned, $AmbientFixtureAligned
)
$AmbientFixtureKeystore = Join-Path $env:ANDROID_USER_HOME 'debug.keystore'
if (-not (Test-Path -LiteralPath $AmbientFixtureKeystore)) {
    $AmbientFixtureKeystore = Join-Path $AmbientFixtureOutput 'fixture-debug.keystore'
    if (-not (Test-Path -LiteralPath $AmbientFixtureKeystore)) {
        Invoke-AmbientFixtureTool (Join-Path $AmbientJdk 'bin\keytool.exe') @(
            '-genkeypair', '-keystore', $AmbientFixtureKeystore, '-storepass', 'android',
            '-keypass', 'android', '-alias', 'androiddebugkey', '-dname', 'CN=Ambient Test Fixture',
            '-keyalg', 'RSA', '-keysize', '2048', '-validity', '3650'
        )
    }
}
Invoke-AmbientFixtureTool (Join-Path $AmbientFixtureBuildTools 'apksigner.bat') @(
    'sign', '--ks', $AmbientFixtureKeystore, '--ks-key-alias', 'androiddebugkey',
    '--ks-pass', 'pass:android', '--key-pass', 'pass:android', '--out', $AmbientFixtureApk,
    $AmbientFixtureAligned
)
Invoke-AmbientFixtureTool $AmbientFixtureAdb @('-s', $AmbientFixtureSerial, 'install', '-r', $AmbientFixtureApk)
Invoke-AmbientFixtureTool $AmbientFixtureAdb @(
    '-s', $AmbientFixtureSerial, 'shell', 'appops', 'set', $AmbientFixturePackage, 'android:mock_location', 'allow'
)

$AmbientFixtureRequestId = [Guid]::NewGuid().ToString('N')
$AmbientFixtureLaunchArguments = @(
    '-s', $AmbientFixtureSerial, 'shell', 'am', 'start', '-W',
    '-n', "$AmbientFixturePackage/.FixtureActivity", '--es', 'request_id', $AmbientFixtureRequestId,
    '--ef', 'speed', $Speed.ToString('R', $AmbientFixtureCulture),
    '--ef', 'age_seconds', $AgeSeconds.ToString('R', $AmbientFixtureCulture),
    '--ef', 'future_seconds', $FutureSeconds.ToString('R', $AmbientFixtureCulture),
    '--ez', 'no_speed', $NoSpeed.IsPresent.ToString().ToLowerInvariant(),
    '--ez', 'reset', $Reset.IsPresent.ToString().ToLowerInvariant()
)
if (-not $NoAccuracy) {
    $AmbientFixtureLaunchArguments += @('--ef', 'accuracy', $Accuracy.ToString('R', $AmbientFixtureCulture))
}
Invoke-AmbientFixtureTool $AmbientFixtureAdb $AmbientFixtureLaunchArguments
Start-Sleep -Milliseconds $(if ($Reset) { 1500 } else { 3600 })
$AmbientFixtureLogs = @(& $AmbientFixtureAdb -s $AmbientFixtureSerial logcat -d -s 'AmbientLocationFixture:I' '*:S')
$AmbientFixtureRequestLogs = @($AmbientFixtureLogs | Where-Object { $_ -match $AmbientFixtureRequestId })
$AmbientFixtureRequestLogs | Write-Host
if (-not $AmbientFixtureRequestLogs -or ($AmbientFixtureRequestLogs -match ' ERROR ')) {
    throw 'The emulator location fixture did not report a successful injection/reset.'
}
if ($Reset -and -not ($AmbientFixtureRequestLogs -match ' RESET')) { throw 'Reset was not confirmed.' }
if (-not $Reset -and -not ($AmbientFixtureRequestLogs -match ' INJECTED 3/3')) {
    throw 'The third test location was not confirmed.'
}
Write-Host "Fixture completed on $AmbientFixtureSerial only. Remove the mock GPS provider with -Reset."
