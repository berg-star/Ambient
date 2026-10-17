# Offline UI states. Dedicated AVD only; encrypted weather files restored in finally.
. (Join-Path $PSScriptRoot 'history-ui-helper.ps1')
$ErrorActionPreference = 'Stop'
$RainResults = [Collections.Generic.List[string]]::new()
$RainFolder = Join-Path $AmbientRoot 'docs\screenshots\precipitation'
New-Item -ItemType Directory -Force -Path $RainFolder | Out-Null
$RainWifi = ((Invoke-WeatherDevice shell settings get global wifi_on) -join '').Trim()
$RainData = ((Invoke-WeatherDevice shell settings get global mobile_data) -join '').Trim()
function Set-RainFixture([string]$Mode) {
    Invoke-WeatherDevice shell am force-stop com.ambient.app | Out-Null
    $output = (Invoke-WeatherDevice shell am instrument -w -r -e class com.ambient.app.ui.precipitation.PrecipitationUiFixture -e precipitationFixture $Mode com.ambient.app.test/androidx.test.runner.AndroidJUnitRunner) -join "`n"
    if ($output -notmatch 'OK \(1 test\)' -or $output -match 'FAILURES!!!') { throw 'Rain fixture failed.' }
}
function Open-Rain {
    Invoke-WeatherDevice shell am force-stop com.ambient.app | Out-Null
    Invoke-WeatherDevice shell am start -W -n com.ambient.app/.MainActivity | Out-Null
    Tap-WeatherText '降水预报'
    Wait-HistoryText '精细降水 · 每 5 分钟一个预报点'
}
function Pass-Rain([string]$Message) {
    $RainResults.Add("PASS $Message")
    [IO.File]::WriteAllLines((Join-Path $AmbientRoot '.tools\logs\precipitation-ui-results.txt'), $RainResults, [Text.UTF8Encoding]::new($false))
    "PASS $Message"
}
function Save-Rain([string]$Name) {
    Invoke-WeatherDevice shell screencap -p /data/local/tmp/ambient-rain.png | Out-Null
    Invoke-WeatherDevice pull /data/local/tmp/ambient-rain.png (Join-Path $RainFolder "$Name.png") | Out-Null
}
function Top-Rain { Show-HistoryText '2 小时' -Up }
try {
    Invoke-WeatherDevice shell svc wifi disable | Out-Null
    Invoke-WeatherDevice shell svc data disable | Out-Null
    Set-RainFixture 'wet'
    Open-Rain
    Assert-WeatherText '区域预报 · 和风天气 · 非手机实测'
    Assert-WeatherText '测试降水提示'
    Save-Rain 'two-hours'
    Show-HistoryText '5 分钟累计降水量 · mm'
    Show-HistoryText 'mm / 5 分钟'
    Assert-WeatherText '0.15'
    Assert-WeatherText '降水概率：此接口未提供'
    Save-Rain 'five-minute-bars'
    $bar = (Get-WeatherUi).SelectNodes('//node') | Where-Object { $_.'content-desc' -like '*降水量*毫米' } | Select-Object -Skip 1 -First 1
    if (-not $bar -or $bar.bounds -notmatch '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') { throw 'Selectable forecast bar missing.' }
    Invoke-WeatherDevice shell input tap ([int](([int]$Matches[1]+[int]$Matches[3])/2)) ([int](([int]$Matches[2]+[int]$Matches[4])/2)) | Out-Null
    Assert-WeatherText '降水类型：雪'
    Pass-Rain 'Five-minute amount and unavailable probability are explicit; no hourly intensity substitution'
    Top-Rain
    Tap-WeatherText '24 小时'
    Assert-WeatherText '降水趋势 · 每小时一个预报点'
    Assert-WeatherText '接口未提供预报发布时间'
    Show-HistoryText 'mm / 60 分钟'
    Assert-WeatherText '降水概率：35%'
    Save-Rain 'hourly-bars'
    Pass-Rain '24-hour view uses hourly intervals and percentage; fetch time is not provider issue time'
    Top-Rain
    Tap-WeatherText '72 小时'
    Assert-WeatherText '未来 72 小时'
    Show-HistoryText '60 分钟累计降水量 · mm'
    Save-Rain 'three-days'
    Pass-Rain '72-hour view reuses hourly cache offline without minute interpolation'
    foreach ($RainTheme in @('日光琥珀','雾蓝暮色','流光玻璃')) {
        Tap-WeatherText '设置'
        Tap-WeatherText $RainTheme
        Tap-WeatherText '返回'
        Assert-WeatherText '区域预报 · 和风天气 · 非手机实测'
        Save-Rain $RainTheme
        Pass-Rain "Theme $RainTheme remains readable and settings returns to precipitation"
    }
    Set-RainFixture 'dry'
    Open-Rain
    Assert-WeatherText '预计无降水 · 测试预报'
    Show-HistoryText 'mm / 5 分钟'
    Assert-WeatherText '0.00'
    Pass-Rain 'Actual forecast zeros render zero rather than missing'
    Set-RainFixture 'missing'
    Open-Rain
    Assert-WeatherText '降水量暂缺'
    if (((Get-WeatherTexts) -join "`n") -match '测试降水提示') { throw 'Missing quantities must suppress forecast summary.' }
    Pass-Rain 'Missing amounts never display dry-weather message or stale provider summary'
    Set-RainFixture 'expired'
    Open-Rain
    Wait-HistoryText '预报时段已结束'
    if (((Get-WeatherTexts) -join "`n") -match '测试降水提示|选中预报时间') { throw 'Expired forecasts are still presented as future.' }
    Tap-WeatherText '72 小时'
    Assert-WeatherText '预报时段已结束'
    Pass-Rain 'Expired minute and hourly forecasts hide chart and previous summary'
    Set-RainFixture 'partial'
    Open-Rain
    Wait-HistoryText '暂无此类降水预报'
    Tap-WeatherText '24 小时'
    Show-HistoryText '降水概率：35%'
    Pass-Rain 'Missing minute API leaves independently cached hourly forecast usable'
    Set-RainFixture 'unconfigured'
    Open-Rain
    Assert-WeatherText '请先启用区域 API'
    Pass-Rain 'Unconfigured state does not fabricate a precipitation forecast'
    Invoke-WeatherDevice shell input keyevent 3 | Out-Null
    $active = (((Invoke-WeatherDevice shell dumpsys sensorservice) -join "`n") -split 'Previous Registrations:')[0]
    if ($active -match 'com\.ambient\.app\.data\.(sensor|orientation)\.') { throw 'Precipitation registered phone sensors.' }
    Invoke-WeatherDevice shell am start -W -n com.ambient.app/.MainActivity | Out-Null
    Invoke-WeatherDevice shell input keyevent 4 | Out-Null
    Assert-WeatherText '区域环境'
    Pass-Rain 'Background and Back behave correctly and no phone sensor listeners remain'
} finally {
    Set-RainFixture 'restore'
    if ($RainWifi -eq '1') { Invoke-WeatherDevice shell svc wifi enable | Out-Null }
    if ($RainData -eq '1') { Invoke-WeatherDevice shell svc data enable | Out-Null }
}
