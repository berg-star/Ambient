# Runs only the history instrumentation suite against the dedicated emulator.
. (Join-Path $PSScriptRoot 'weather-ui-helper.ps1')
$ErrorActionPreference = 'Stop'
Invoke-WeatherDevice install -r (Join-Path $AmbientRoot 'app\build\outputs\apk\debug\app-debug.apk') | Out-Null
Invoke-WeatherDevice install -r (Join-Path $AmbientRoot 'app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk') | Out-Null
$output = Invoke-WeatherDevice shell am instrument -w -r -e class com.ambient.app.data.history.HistoryDatabaseTest com.ambient.app.test/androidx.test.runner.AndroidJUnitRunner
$output | Write-Output
$joined = $output -join "`n"
if ($joined -notmatch 'OK \(8 tests\)' -or $joined -match 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed') {
    throw 'History database instrumentation tests did not pass.'
}
[IO.File]::WriteAllLines((Join-Path $AmbientRoot '.tools\logs\history-database-results.txt'), $output, [Text.UTF8Encoding]::new($false))
