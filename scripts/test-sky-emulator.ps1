# Offline UI verification. Fixtures live only in androidTest and restore original encrypted files.
. (Join-Path $PSScriptRoot 'weather-ui-helper.ps1')
$ErrorActionPreference = 'Stop'
$SkyResults = [Collections.Generic.List[string]]::new()
$SkyFolder = Join-Path $AmbientRoot 'docs\screenshots\sky'
New-Item -ItemType Directory -Force -Path $SkyFolder | Out-Null
$SkyWifi = ((Invoke-WeatherDevice shell settings get global wifi_on) -join '').Trim()
$SkyData = ((Invoke-WeatherDevice shell settings get global mobile_data) -join '').Trim()
function Set-SkyFixture([string]$Mode) {
    Invoke-WeatherDevice shell am force-stop com.ambient.app | Out-Null
    $output = (Invoke-WeatherDevice shell am instrument -w -r -e class com.ambient.app.ui.sky.SkyUiFixture -e skyFixture $Mode com.ambient.app.test/androidx.test.runner.AndroidJUnitRunner) -join "`n"
    if ($output -notmatch 'OK \(1 test\)' -or $output -match 'FAILURES!!!') { throw 'Sky fixture operation failed.' }
}
function Open-Sky {
    Invoke-WeatherDevice shell am force-stop com.ambient.app | Out-Null
    Invoke-WeatherDevice shell am start -W -n com.ambient.app/.MainActivity | Out-Null
    Tap-WeatherText '日光与天空'
    Assert-WeatherText '天文计算 · 和风天气 · 非手机实测'
}
function Record-SkyPass([string]$Message) {
    $SkyResults.Add("PASS $Message")
    [IO.File]::WriteAllLines((Join-Path $AmbientRoot '.tools\logs\sky-ui-results.txt'), $SkyResults, [Text.UTF8Encoding]::new($false))
    "PASS $Message"
}
function Save-SkyScreen([string]$Name) {
    Invoke-WeatherDevice shell screencap -p /data/local/tmp/ambient-sky.png | Out-Null
    Invoke-WeatherDevice pull /data/local/tmp/ambient-sky.png (Join-Path $SkyFolder "$Name.png") | Out-Null
}
function Assert-SkyNoListeners {
    $section = ((Invoke-WeatherDevice shell dumpsys sensorservice) -join "`n" -split 'Previous Registrations:')[0]
    if ($section -match 'com\.ambient\.app\.data\.(sensor|orientation)\.') { throw 'Sky must not register phone sensors.' }
}
try {
    Invoke-WeatherDevice shell svc wifi disable | Out-Null
    Invoke-WeatherDevice shell svc data disable | Out-Null
    Set-SkyFixture 'reference'
    Open-Sky
    Assert-WeatherText '12 小时 0 分钟'
    Assert-WeatherText '06:00'
    Assert-WeatherText '18:00'
    Assert-SkyNoListeners
    Save-SkyScreen 'glass-dark'
    Record-SkyPass 'Offline encrypted cache displays daylight, times and explicit calculation provenance; no sensor listeners'
    foreach ($SkyTheme in @('日光琥珀', '雾蓝暮色', '流光玻璃')) {
        foreach ($SkyMode in @('浅色', '深色')) {
            Tap-WeatherText '设置'
            Tap-WeatherText $SkyTheme
            Show-WeatherText $SkyMode
            Tap-WeatherText $SkyMode
            Tap-WeatherText '返回'
            Assert-WeatherText '天文计算 · 和风天气 · 非手机实测'
            if ($SkyMode -eq '浅色' -and $SkyTheme -eq '日光琥珀') { Save-SkyScreen 'amber-light' }
            if ($SkyMode -eq '浅色' -and $SkyTheme -eq '雾蓝暮色') { Save-SkyScreen 'mist-light' }
            Record-SkyPass "Theme $SkyTheme / $SkyMode remains readable and returns to Sky"
        }
    }
    Show-WeatherText '太阳位置'
    Assert-WeatherText '42.5°'
    Show-WeatherText '高度参数 0 m · 参考假设，未取得当地高度'
    Assert-WeatherText '高度参数 0 m'
    Save-SkyScreen 'reference-angle'
    Show-WeatherText '月相'
    Show-WeatherText '测试月相'
    Assert-WeatherText '月面照明比例 42%'
    Record-SkyPass 'Solar reference height is explicit; moon phase is separate from illumination measurements'
    Show-WeatherText '查看手机实测光照'
    Tap-WeatherText '查看手机实测光照'
    Assert-WeatherText '手机传感器'
    Tap-WeatherText '总览'
    Assert-SkyNoListeners
    Record-SkyPass 'Phone light action opens sensor page; overview stops listening again'
    Set-SkyFixture 'height'
    Open-Sky
    Show-WeatherText '高度参数 123 m · 定位报告的 WGS84 高度，近似参考，非地形海拔'
    Assert-WeatherText '系统报告的垂直精度 ±15 m'
    Save-SkyScreen 'positioning-height'
    Record-SkyPass 'Approximate positioning height and vertical accuracy are labelled; mock location is not presented as real'
    Set-SkyFixture 'missing'
    Open-Sky
    Assert-WeatherText '日出日落不完整，无法计算白昼'
    Show-WeatherText '太阳位置'
    if (@(Get-WeatherTexts) -contains '42.5°') { throw 'Missing solar value must not be fabricated.' }
    Show-WeatherText '当前时段暂无月面照明比例'
    Record-SkyPass 'Missing sun times, solar angles and moon phase remain missing'
    Invoke-WeatherDevice shell input keyevent 3 | Out-Null
    Assert-SkyNoListeners
    Invoke-WeatherDevice shell am start -W -n com.ambient.app/.MainActivity | Out-Null
    Assert-WeatherText '天文计算 · 和风天气 · 非手机实测'
    Invoke-WeatherDevice shell input keyevent 4 | Out-Null
    Assert-WeatherText '区域环境'
    Record-SkyPass 'Background and resume preserve source boundaries; system Back returns to overview'
} finally {
    Set-SkyFixture 'restore'
    if ($SkyWifi -eq '1') { Invoke-WeatherDevice shell svc wifi enable | Out-Null }
    if ($SkyData -eq '1') { Invoke-WeatherDevice shell svc data enable | Out-Null }
}
