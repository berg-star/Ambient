# Only installs and tests against the verified dedicated emulator.
. (Join-Path $PSScriptRoot 'weather-ui-helper.ps1')
$ErrorActionPreference='Stop'
Invoke-WeatherDevice install -r (Join-Path $AmbientRoot 'app\build\outputs\apk\debug\app-debug.apk') | Out-Null
Invoke-WeatherDevice install -r (Join-Path $AmbientRoot 'app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk') | Out-Null
$output=Invoke-WeatherDevice shell am instrument -w -r com.ambient.app.test/androidx.test.runner.AndroidJUnitRunner
$joined=$output -join "`n"
[IO.File]::WriteAllLines((Join-Path $AmbientRoot '.tools\logs\history-analysis-database-results.txt'),$output,[Text.UTF8Encoding]::new($false))
if ($joined -notmatch 'OK \(40 tests\)' -or $joined -match 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed') {
    $output | Write-Output
    throw 'History/filter/comparison/trend instrumentation did not pass.'
}
'PASS all 40 history, CSV, filtering, comparison and trend Android tests'
