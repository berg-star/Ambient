# Explicit live API acceptance using a selected Beijing test city. Never targets a physical phone.
# Requires existing ignored local credentials and the already-built main/androidTest APKs.
. (Join-Path $PSScriptRoot 'history-ui-helper.ps1')
$ErrorActionPreference = 'Stop'
$SkyLiveWifi = ((Invoke-WeatherDevice shell settings get global wifi_on) -join '').Trim()
$SkyLiveData = ((Invoke-WeatherDevice shell settings get global mobile_data) -join '').Trim()
$SkyLiveFolder = Join-Path $AmbientRoot 'docs\screenshots\sky'
New-Item -ItemType Directory -Force -Path $SkyLiveFolder | Out-Null
function Invoke-SkyLiveFixture([string]$Mode) {
    Invoke-WeatherDevice shell am force-stop com.ambient.app | Out-Null
    $output = (Invoke-WeatherDevice shell am instrument -w -r -e class com.ambient.app.ui.sky.SkyUiFixture -e skyFixture $Mode com.ambient.app.test/androidx.test.runner.AndroidJUnitRunner) -join "`n"
    if ($output -notmatch 'OK \(1 test\)' -or $output -match 'FAILURES!!!') { throw 'Could not prepare/restore live-test state.' }
}
function Save-SkyLiveScreen([string]$Name) {
    Invoke-WeatherDevice shell screencap -p /data/local/tmp/ambient-sky-live.png | Out-Null
    Invoke-WeatherDevice pull /data/local/tmp/ambient-sky-live.png (Join-Path $SkyLiveFolder "$Name.png") | Out-Null
}
try {
    # Preserve the old encrypted files; the temporary fixture is replaced through the normal import UI.
    Invoke-SkyLiveFixture 'reference'
    Invoke-WeatherDevice shell svc wifi enable | Out-Null
    Invoke-WeatherDevice shell svc data enable | Out-Null
    Invoke-WeatherDevice shell am start -W -n com.ambient.app/.MainActivity | Out-Null
    & (Join-Path $PSScriptRoot 'import-qweather-emulator.ps1')
    Tap-WeatherText '返回'
    Wait-HistoryText '室外温度'
    Tap-WeatherText '选择城市'
    Tap-WeatherText '城市 / 区县名称'
    Invoke-WeatherDevice shell input text beijing | Out-Null
    Tap-WeatherText '搜索'
    Wait-HistoryText '北京市 · 北京'
    Tap-WeatherText '北京市 · 北京'
    Wait-HistoryText '室外温度'
    Show-HistoryText '日光与天空' -Up
    Tap-WeatherText '日光与天空'
    Wait-HistoryText '今日白昼'
    Assert-WeatherText '天文计算 · 和风天气 · 非手机实测'
    Assert-WeatherText '地区时区 Asia/Shanghai'
    $SkySun = Get-Content (Join-Path $AmbientRoot '.tools\sky-probe\sun.json') -Raw | ConvertFrom-Json
    $SkyRise = ([DateTimeOffset]::Parse($SkySun.sunrise)).ToString('HH:mm')
    $SkySet = ([DateTimeOffset]::Parse($SkySun.sunset)).ToString('HH:mm')
    Assert-WeatherText $SkyRise
    Assert-WeatherText $SkySet
    Save-SkyLiveScreen 'live-beijing'
    'PASS Android JWT import, city time zone and real astronomical sun times match desktop API response'
    Show-HistoryText '太阳位置'
    Assert-WeatherText '高度角'
    Assert-WeatherText '方位角'
    Show-HistoryText '高度参数 0 m · 参考假设，未取得当地高度'
    Save-SkyLiveScreen 'live-beijing-angle'
    Show-HistoryText '月相'
    $SkyMoonTexts = (Get-WeatherTexts) -join "`n"
    if ($SkyMoonTexts -notmatch '月面照明比例 \d+%') {
        Scroll-HistoryUi
        $SkyMoonTexts = (Get-WeatherTexts) -join "`n"
    }
    if ($SkyMoonTexts -notmatch '月面照明比例 \d+%') { throw 'Live moon phase did not reach UI.' }
    'PASS real solar angles and hourly moon phase are decoded on Android with explicit reference-height label'
    Invoke-WeatherDevice shell svc wifi disable | Out-Null
    Invoke-WeatherDevice shell svc data disable | Out-Null
    Invoke-WeatherDevice shell am force-stop com.ambient.app | Out-Null
    Invoke-WeatherDevice shell am start -W -n com.ambient.app/.MainActivity | Out-Null
    Tap-WeatherText '日光与天空'
    Wait-HistoryText '今日白昼'
    Assert-WeatherText $SkyRise
    Assert-WeatherText $SkySet
    'PASS process restart without network restores the same region/date astronomy cache'
    $SkyActive = (((Invoke-WeatherDevice shell dumpsys sensorservice) -join "`n") -split 'Previous Registrations:')[0]
    if ($SkyActive -match 'com\.ambient\.app\.data\.(sensor|orientation)\.') { throw 'Sky unexpectedly registered sensors.' }
    $SkyPid = ((Invoke-WeatherDevice shell pidof com.ambient.app) -join '').Trim()
    $SkyCrashes = (Invoke-WeatherDevice logcat -d "--pid=$SkyPid" -s AndroidRuntime:E) -join "`n"
    if ($SkyCrashes -match 'FATAL EXCEPTION') { throw 'Runtime crash in live test.' }
    'PASS Sky has no phone sensor listeners and no fatal runtime exception'
} finally {
    Invoke-SkyLiveFixture 'restore'
    if ($SkyLiveWifi -eq '1') { Invoke-WeatherDevice shell svc wifi enable | Out-Null }
    if ($SkyLiveData -eq '1') { Invoke-WeatherDevice shell svc data enable | Out-Null }
}
