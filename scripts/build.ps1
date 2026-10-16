param([switch]$Offline)
. (Join-Path $PSScriptRoot 'environment.ps1')
Push-Location $AmbientRoot
try {
    $AmbientArguments = @('--no-daemon', 'assembleDebug', 'testDebugUnitTest', 'lintDebug')
    if ($Offline) { $AmbientArguments += '--offline' }
    & (Join-Path $AmbientRoot 'gradlew.bat') @AmbientArguments
    if ($LASTEXITCODE -ne 0) { throw "Gradle 构建失败，退出码 $LASTEXITCODE" }
    Write-Output (Join-Path $AmbientRoot 'app\build\outputs\apk\debug\app-debug.apk')
} finally { Pop-Location }
