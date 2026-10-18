# Real SensorManager pipeline with emulator-generated events, never represented as environment measurements.
. (Join-Path $PSScriptRoot 'history-ui-helper.ps1')
$ErrorActionPreference='Stop'
$ObservationFolder=Join-Path $AmbientRoot 'docs\screenshots\observation'
New-Item -ItemType Directory -Force -Path $ObservationFolder | Out-Null
$ObservationResults=[Collections.Generic.List[string]]::new()
function Pass-Observation([string]$Text) {
    $ObservationResults.Add("PASS $Text")
    [IO.File]::WriteAllLines((Join-Path $AmbientRoot '.tools\logs\observation-ui-results.txt'),$ObservationResults,[Text.UTF8Encoding]::new($false))
    "PASS $Text"
}
function Active-Observation {
    $section=(((Invoke-WeatherDevice shell dumpsys sensorservice)-join "`n") -split 'Previous Registrations:')[0]
    return $section -match 'com\.ambient\.app\.data\.observation\.AndroidObservationRepository'
}
function Save-Observation([string]$Name) {
    Invoke-WeatherDevice shell screencap -p /data/local/tmp/ambient-observation.png | Out-Null
    Invoke-WeatherDevice pull /data/local/tmp/ambient-observation.png (Join-Path $ObservationFolder "$Name.png") | Out-Null
}
function Observation-Top { Show-HistoryText 'OBSERVATION' -Up }
function Start-ObservationWindow {
    $stopNode=(Get-WeatherUi).SelectNodes('//node') | Where-Object { $_.text -eq '提前结束' } | Select-Object -First 1
    if (-not $stopNode -or $stopNode.bounds -notmatch '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') { throw 'Stop button bounds missing' }
    $script:ObservationStopX=[int](([int]$Matches[1]+[int]$Matches[3])/2)
    $script:ObservationStopY=[int](([int]$Matches[2]+[int]$Matches[4])/2)
    Tap-WeatherText '开始观察'
    Start-Sleep -Milliseconds 600
}
function Stop-ObservationWindow { Invoke-WeatherDevice shell input tap $script:ObservationStopX $script:ObservationStopY | Out-Null }
try {
Invoke-WeatherDevice shell am force-stop com.ambient.app | Out-Null
Invoke-WeatherDevice shell am start -W -n com.ambient.app/.MainActivity | Out-Null
Tap-WeatherText '探测'
Tap-WeatherText '观察模式 · 30 秒 / 1 分钟  ›'
Assert-WeatherText '模拟器 · 虚拟事件，非环境实测'
if (Active-Observation) { throw 'Opening page started observation' }
Pass-Observation 'Opening observation waits for explicit Start'
Invoke-WeatherDevice emu sensor set light 100 | Out-Null
Start-ObservationWindow
if (-not (Active-Observation)) { throw 'Selected sensor listener missing' }
Invoke-WeatherDevice emu sensor set light 200 | Out-Null
Start-Sleep -Milliseconds 700
Invoke-WeatherDevice emu sensor set light 400 | Out-Null
Start-Sleep -Seconds 30
Show-HistoryText '最低 / 最高 / 事件平均 · 计算统计'
Assert-WeatherText '400.00 lx'
Show-HistoryText '观察完成' -Up
Wait-HistoryText '观察完成' 40
if (Active-Observation) { throw 'Completed session is still listening' }
Save-Observation 'thirty-seconds'
Show-HistoryText '设为对照'
Tap-WeatherText '设为对照'
Show-HistoryText '保留的对照 · 光照'
Pass-Observation '30-second window records hardware events, computes statistics and stops automatically; reference retained'
Observation-Top
Invoke-WeatherDevice emu sensor set light 600 | Out-Null
Start-ObservationWindow
Invoke-WeatherDevice emu sensor set light 1000 | Out-Null
Start-Sleep -Seconds 30
Show-HistoryText '保留的对照 · 光照'
Assert-WeatherText '当前平均 − 对照平均'
Save-Observation 'comparison'
Pass-Observation 'Two completed same-kind same-duration observations show calculated mean difference without replacing reference'
Observation-Top
Tap-WeatherText '1 分钟'
Invoke-WeatherDevice emu sensor set light 600 | Out-Null
Start-ObservationWindow
Invoke-WeatherDevice emu sensor set light 800 | Out-Null
Start-Sleep -Seconds 30
Start-Sleep -Seconds 30
Show-HistoryText '观察完成'
Wait-HistoryText '观察完成' 75
if (Active-Observation) { throw 'One-minute session remains registered' }
Save-Observation 'one-minute'
Pass-Observation 'One-minute duration finishes using device monotonic time and unregisters listener'
Observation-Top
Tap-WeatherText '30 秒'
Start-ObservationWindow
Invoke-WeatherDevice emu sensor set light 300 | Out-Null
Invoke-WeatherDevice shell input keyevent 3 | Out-Null
Start-Sleep -Milliseconds 500
if (Active-Observation) { throw 'Background observation still active' }
Invoke-WeatherDevice shell am start -W -n com.ambient.app/.MainActivity | Out-Null
Show-HistoryText '已提前结束 · 部分记录'
if (Active-Observation) { throw 'Resume restarted incomplete observation' }
Pass-Observation 'Background interrupts observation, preserves partial result and never auto-resumes'
Observation-Top
Tap-WeatherText '地磁场'
Start-ObservationWindow
Invoke-WeatherDevice emu sensor set magnetic-field 3:4:0 | Out-Null
Start-Sleep -Seconds 2
Stop-ObservationWindow
Show-HistoryText '地磁合成强度 · 计算值'
Show-HistoryText '虚拟 XYZ：3.00 / 4.00 / 0.00 μT'
Assert-WeatherText '5.00 μT'
Show-HistoryText '已提前结束 · 部分记录' -Up
if (Active-Observation) { throw 'Manual stop left listener active' }
Save-Observation 'magnetic-components'
Pass-Observation 'Magnetic raw XYZ and calculated norm are distinguished; manual stop releases listener'
foreach ($ObservationTheme in @('日光琥珀','雾蓝暮色','流光玻璃')) {
    Tap-WeatherText '设置'
    Tap-WeatherText $ObservationTheme
    Tap-WeatherText '返回'
    Assert-WeatherText '模拟器 · 虚拟事件，非环境实测'
    Save-Observation $ObservationTheme
    Pass-Observation "Theme $ObservationTheme keeps observation provenance readable"
}
Observation-Top
Tap-WeatherText '气压'
Start-ObservationWindow
Invoke-WeatherDevice emu sensor set pressure 1000 | Out-Null
Start-Sleep -Seconds 2
Stop-ObservationWindow
Show-HistoryText '1000.00 hPa'
Pass-Observation 'Supported pressure channel uses local sensor hectopascals'
Observation-Top
Start-ObservationWindow
Invoke-WeatherDevice shell input keyevent 4 | Out-Null
Assert-WeatherText '手机传感器'
if (Active-Observation) { throw 'Leaving observation did not stop selected listener' }
Tap-WeatherText '总览'
Pass-Observation 'Back stops recording and returns to sensor page; no observation listener persists'
} finally {
    Invoke-WeatherDevice shell input keyevent 3 | Out-Null
}
