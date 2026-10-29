# Requires credentials imported through Settings and a city selected on the dedicated emulator.
. (Join-Path $PSScriptRoot 'weather-ui-helper.ps1')
$ErrorActionPreference = 'Stop'
Invoke-WeatherDevice shell am force-stop com.ambient.app | Out-Null
try {
    Invoke-WeatherDevice shell svc wifi disable | Out-Null
    Invoke-WeatherDevice shell svc data disable | Out-Null
    Invoke-WeatherDevice shell am start -W -n com.ambient.app/.MainActivity | Out-Null
    Assert-WeatherText '本地缓存'
    Assert-WeatherText '区域 API，非手机实测'
    $before = @(Get-WeatherTexts) | Where-Object { $_ -like '天气 ·*' } | Select-Object -First 1
    Save-WeatherScreen 'offline-cache-restored'
    Write-Output 'PASS encrypted disk cache restored after process restart without network'
    Tap-WeatherText '刷新'
    $deadline = (Get-Date).AddSeconds(40)
    do {
        $text = @(Get-WeatherTexts) -join "`n"
        if ($text -like '*网络连接失败*') { break }
        if ((Get-Date) -gt $deadline) { throw 'Expected network error did not appear.' }
        Start-Sleep -Milliseconds 300
    } while ($true)
    Assert-WeatherText '本地缓存'
    $after = @(Get-WeatherTexts) | Where-Object { $_ -like '天气 ·*' } | Select-Object -First 1
    if ($before -ne $after) { throw 'Offline error changed the original fetch time.' }
    Save-WeatherScreen 'offline-refresh-error'
    Write-Output 'PASS failed refresh retains cached values and original fetch time with explicit error'
    Tap-WeatherText '刷新'
    Assert-WeatherText '30 秒'
    Assert-WeatherText '本地缓存'
    Write-Output 'PASS repeated refresh is throttled without losing cached values'
} finally {
    Invoke-WeatherDevice shell svc wifi enable | Out-Null
    Invoke-WeatherDevice shell svc data enable | Out-Null
}
