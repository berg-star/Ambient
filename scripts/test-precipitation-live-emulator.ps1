# Explicit live acceptance through normal credential import. Dedicated AVD; no physical device.
. (Join-Path $PSScriptRoot 'history-ui-helper.ps1')
$ErrorActionPreference = 'Stop'
$RainLiveWifi = ((Invoke-WeatherDevice shell settings get global wifi_on) -join '').Trim()
$RainLiveData = ((Invoke-WeatherDevice shell settings get global mobile_data) -join '').Trim()
function Invoke-RainLiveFixture([string]$Mode) {
    Invoke-WeatherDevice shell am force-stop com.ambient.app | Out-Null
    $output = (Invoke-WeatherDevice shell am instrument -w -r -e class com.ambient.app.ui.precipitation.PrecipitationUiFixture -e precipitationFixture $Mode com.ambient.app.test/androidx.test.runner.AndroidJUnitRunner) -join "`n"
    if ($output -notmatch 'OK \(1 test\)' -or $output -match 'FAILURES!!!') { throw 'Could not prepare/restore precipitation live-test state.' }
}
try {
    Invoke-WeatherDevice shell svc wifi disable | Out-Null
    Invoke-WeatherDevice shell svc data disable | Out-Null
    Invoke-RainLiveFixture 'wet'
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
    Show-HistoryText '降水预报' -Up
    Tap-WeatherText '降水预报'
    Wait-HistoryText '预报更新时间'
    Assert-WeatherText '区域预报 · 和风天气 · 非手机实测'
    Assert-WeatherText '地区时区 · Asia/Shanghai'
    'PASS real minute API response reaches Android with original update time and forecast provenance'
    Show-HistoryText 'mm / 5 分钟'
    $minuteTexts=(Get-WeatherTexts) -join "`n"
    if ($minuteTexts -notmatch '\d+\.\d{2}') { throw 'No minute quantity rendered.' }
    'PASS real five-minute accumulated amount is decoded on Android'
    Show-HistoryText '24 小时' -Up
    Tap-WeatherText '24 小时'
    Wait-HistoryText '接口未提供预报发布时间'
    Show-HistoryText 'mm / 60 分钟'
    $hourTexts=(Get-WeatherTexts) -join "`n"
    if ($hourTexts -notmatch '降水概率：\d+%') { throw 'No live hourly probability rendered.' }
    Show-HistoryText '72 小时' -Up
    Tap-WeatherText '72 小时'
    Assert-WeatherText '未来 72 小时'
    'PASS real hourly amount/probability and 24/72-hour views use documented hourly data'
    Invoke-WeatherDevice shell svc wifi disable | Out-Null
    Invoke-WeatherDevice shell svc data disable | Out-Null
    Invoke-WeatherDevice shell am force-stop com.ambient.app | Out-Null
    Invoke-WeatherDevice shell am start -W -n com.ambient.app/.MainActivity | Out-Null
    Tap-WeatherText '降水预报'
    Wait-HistoryText '预报更新时间'
    Assert-WeatherText '本地缓存'
    $active=(((Invoke-WeatherDevice shell dumpsys sensorservice) -join "`n") -split 'Previous Registrations:')[0]
    if ($active -match 'com\.ambient\.app\.data\.(sensor|orientation)\.') { throw 'Unexpected sensor listeners.' }
    $pidText=((Invoke-WeatherDevice shell pidof com.ambient.app) -join '').Trim()
    $crash=(Invoke-WeatherDevice logcat -d "--pid=$pidText" -s AndroidRuntime:E) -join "`n"
    if ($crash -match 'FATAL EXCEPTION') { throw 'Runtime crash.' }
    'PASS encrypted forecast cache survives process restart offline; no sensor listeners or runtime crash'
} finally {
    Invoke-RainLiveFixture 'restore'
    if ($RainLiveWifi -eq '1') { Invoke-WeatherDevice shell svc wifi enable | Out-Null }
    if ($RainLiveData -eq '1') { Invoke-WeatherDevice shell svc data enable | Out-Null }
}
