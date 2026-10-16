# Run the complete history and CSV instrumentation suites on the dedicated emulator only.
. (Join-Path $PSScriptRoot 'weather-ui-helper.ps1')
$ErrorActionPreference='Stop'
Invoke-WeatherDevice install -r (Join-Path $AmbientRoot 'app\build\outputs\apk\debug\app-debug.apk') | Out-Null
Invoke-WeatherDevice install -r (Join-Path $AmbientRoot 'app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk') | Out-Null
$classes='com.ambient.app.data.history.HistoryDatabaseTest,com.ambient.app.data.history.HistoryExportReadTest,com.ambient.app.ui.history.HistoryExportViewModelTest'
$output=Invoke-WeatherDevice shell am instrument -w -r -e class $classes com.ambient.app.test/androidx.test.runner.AndroidJUnitRunner
$output | Write-Output
$joined=$output -join "`n"
if ($joined -notmatch 'OK \(21 tests\)' -or $joined -match 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed') { throw 'History and CSV instrumentation failed.' }
[IO.File]::WriteAllLines((Join-Path $AmbientRoot '.tools\logs\csv-database-results.txt'),$output,[Text.UTF8Encoding]::new($false))
