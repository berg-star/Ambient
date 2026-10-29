# Explicit real-data acceptance. Normal import and selected Beijing city; never physical phone.
. (Join-Path $PSScriptRoot 'history-ui-helper.ps1')
$ErrorActionPreference='Stop'
$DetailsLiveWifi=((Invoke-WeatherDevice shell settings get global wifi_on)-join '').Trim()
$DetailsLiveData=((Invoke-WeatherDevice shell settings get global mobile_data)-join '').Trim()
function Invoke-DetailsLiveFixture([string]$Mode) {
    Invoke-WeatherDevice shell am force-stop com.ambient.app | Out-Null
    $output=(Invoke-WeatherDevice shell am instrument -w -r -e class com.ambient.app.ui.sky.SkyUiFixture -e skyFixture $Mode com.ambient.app.test/androidx.test.runner.AndroidJUnitRunner)-join "`n"
    if ($output -notmatch 'OK \(1 test\)' -or $output -match 'FAILURES!!!') { throw 'Details live assertion or fixture failed' }
}
try {
    Invoke-WeatherDevice shell svc wifi disable | Out-Null
    Invoke-WeatherDevice shell svc data disable | Out-Null
    Invoke-DetailsLiveFixture 'details-values'
    Invoke-WeatherDevice shell am start -W -n com.ambient.app/.MainActivity | Out-Null
    & (Join-Path $PSScriptRoot 'import-qweather-emulator.ps1')
    Invoke-WeatherDevice shell svc wifi enable | Out-Null
    Invoke-WeatherDevice shell svc data enable | Out-Null
    Tap-WeatherText '返回'
    Tap-WeatherText '选择城市'
    Tap-WeatherText '城市 / 区县名称'
    Invoke-WeatherDevice shell input text beijing | Out-Null
    Tap-WeatherText '搜索'
    Wait-HistoryText '北京市 · 北京'
    Tap-WeatherText '北京市 · 北京'
    Wait-HistoryText '室外温度'
    Show-HistoryText '环境详情  ›'
    Tap-WeatherText '环境详情  ›'
    Wait-HistoryText '体感温度'
    Assert-WeatherText '区域 API · 和风天气 · 非手机实测'
    Show-HistoryText '露点温度'
    Show-HistoryText '能见度'
    Show-HistoryText '云量'
    Show-HistoryText '阵风风速'
    'PASS real weather response displays all five detail cards on Android'
    Invoke-DetailsLiveFixture 'details-verify-live'
    'PASS encrypted real cache contains finite values and regional API provenance for all five metrics'
    Invoke-WeatherDevice shell svc wifi disable | Out-Null
    Invoke-WeatherDevice shell svc data disable | Out-Null
    Invoke-WeatherDevice shell am start -W -n com.ambient.app/.MainActivity | Out-Null
    Show-HistoryText '环境详情  ›'
    Tap-WeatherText '环境详情  ›'
    Wait-HistoryText '体感温度'
    Assert-WeatherText '本地缓存'
    Invoke-DetailsLiveFixture 'details-verify-live'
    'PASS same real detail data survives offline process restart'
} finally {
    Invoke-DetailsLiveFixture 'restore'
    if ($DetailsLiveWifi -eq '1') { Invoke-WeatherDevice shell svc wifi enable | Out-Null }
    if ($DetailsLiveData -eq '1') { Invoke-WeatherDevice shell svc data enable | Out-Null }
}
