# Explicit synthetic fixtures; verifies only the dedicated emulator, never the physical phone.
. (Join-Path $PSScriptRoot 'history-ui-helper.ps1')
$ErrorActionPreference = 'Stop'
$HeightResults = [Collections.Generic.List[string]]::new()
$HeightFolder = Join-Path $AmbientRoot 'docs\screenshots\overview-height'
New-Item -ItemType Directory -Force -Path $HeightFolder | Out-Null
$HeightWifi = ((Invoke-WeatherDevice shell settings get global wifi_on) -join '').Trim()
$HeightData = ((Invoke-WeatherDevice shell settings get global mobile_data) -join '').Trim()
function Set-HeightFixture([string]$Mode) {
    Invoke-WeatherDevice shell am force-stop com.ambient.app | Out-Null
    $output = (Invoke-WeatherDevice shell am instrument -w -r -e class com.ambient.app.ui.sky.SkyUiFixture -e skyFixture $Mode com.ambient.app.test/androidx.test.runner.AndroidJUnitRunner) -join "`n"
    if ($output -notmatch 'OK \(1 test\)' -or $output -match 'FAILURES!!!') { throw 'Fixture failed.' }
}
function Open-HeightOverview {
    Invoke-WeatherDevice shell am start -W -n com.ambient.app/.MainActivity | Out-Null
    Show-HistoryText '近似定位高度'
}
function Height-Pass([string]$Text) {
    $HeightResults.Add("PASS $Text")
    [IO.File]::WriteAllLines((Join-Path $AmbientRoot '.tools\logs\overview-height-ui-results.txt'), $HeightResults, [Text.UTF8Encoding]::new($false))
    "PASS $Text"
}
function Save-HeightScreen([string]$Name) {
    Invoke-WeatherDevice shell screencap -p /data/local/tmp/ambient-height.png | Out-Null
    Invoke-WeatherDevice pull /data/local/tmp/ambient-height.png (Join-Path $HeightFolder "$Name.png") | Out-Null
}
try {
    Invoke-WeatherDevice shell svc wifi disable | Out-Null
    Invoke-WeatherDevice shell svc data disable | Out-Null
    Set-HeightFixture 'overview-height'
    Open-HeightOverview
    Assert-WeatherText '123'
    Assert-WeatherText '模拟定位'
    Save-HeightScreen 'glass-height'
    Tap-WeatherText '近似定位高度'
    Assert-WeatherText 'WGS84'
    Assert-WeatherText '±15 m'
    Assert-WeatherText '定位时间（UTC）'
    Assert-WeatherText '不是和风天气数据'
    Tap-WeatherText '知道了'
    Height-Pass 'Overview displays approximate height, explicit mock provenance, precision and separate positioning time'
    foreach ($HeightTheme in @('日光琥珀', '雾蓝暮色', '流光玻璃')) {
        Tap-WeatherText '设置'
        Tap-WeatherText $HeightTheme
        Tap-WeatherText '返回'
        Show-HistoryText '近似定位高度'
        Assert-WeatherText '123'
        Save-HeightScreen $HeightTheme
        Height-Pass "Height is readable in theme $HeightTheme"
    }
    Set-HeightFixture 'overview-missing'
    Open-HeightOverview
    Tap-WeatherText '近似定位高度'
    Assert-WeatherText '尚未取得有效定位高度'
    Tap-WeatherText '知道了'
    Height-Pass 'Absent height stays missing; zero-metre astronomy reference is not used as a reading'
    Set-HeightFixture 'overview-manual'
    Open-HeightOverview
    if (@(Get-WeatherTexts) -contains '123') { throw 'Manual city reused device height.' }
    Height-Pass 'Manual city selection ignores a stale phone height'
    Set-HeightFixture 'overview-no-weather'
    Open-HeightOverview
    Assert-WeatherText '123'
    Height-Pass 'Positioning height remains available when regional weather cannot be loaded'
} finally {
    Set-HeightFixture 'restore'
    if ($HeightWifi -eq '1') { Invoke-WeatherDevice shell svc wifi enable | Out-Null }
    if ($HeightData -eq '1') { Invoke-WeatherDevice shell svc data enable | Out-Null }
}
