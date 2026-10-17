# Real Android conversion APIs and database/state regression on the dedicated emulator.
. (Join-Path $PSScriptRoot 'weather-ui-helper.ps1')
$ErrorActionPreference='Stop'
Invoke-WeatherDevice install -r (Join-Path $AmbientRoot 'app\build\outputs\apk\debug\app-debug.apk') | Out-Null
Invoke-WeatherDevice install -r (Join-Path $AmbientRoot 'app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk') | Out-Null
$orientationOutput=Invoke-WeatherDevice shell am instrument -w -r -e notClass com.ambient.app.ui.sky.SkyUiFixture com.ambient.app.test/androidx.test.runner.AndroidJUnitRunner
$orientationJoined=$orientationOutput -join "`n"
[IO.File]::WriteAllLines((Join-Path $AmbientRoot '.tools\logs\orientation-android-results.txt'),$orientationOutput,[Text.UTF8Encoding]::new($false))
if ($orientationJoined -notmatch 'OK \(56 tests\)' -or $orientationJoined -match 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed') {
    $orientationOutput | Write-Output
    throw 'Orientation/Android regression did not pass.'
}
'PASS all 56 Android location, matrix, history, CSV and state tests'
