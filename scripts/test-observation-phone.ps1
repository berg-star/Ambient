param([Parameter(Mandatory=$true)][string]$PhoneSerial,[switch]$ContinueAfterLight,[switch]$ValidateCurrentLight,[switch]$LifecycleOnly)
# Static physical-device acceptance only. No synthetic events, app-data clearing or weather requests.
. (Join-Path $PSScriptRoot 'phone-ui-helper.ps1') -PhoneSerial $PhoneSerial
$ErrorActionPreference='Stop'
if($ValidateCurrentLight -and (-not $ContinueAfterLight -or $LifecycleOnly)) {throw 'ValidateCurrentLight requires ContinueAfterLight and cannot be combined with LifecycleOnly'}
if($LifecycleOnly -and $ContinueAfterLight) {throw 'Choose LifecycleOnly or ContinueAfterLight'}
$AmbientPhoneChecks=[Collections.Generic.List[string]]::new()
if($ContinueAfterLight -or $LifecycleOnly) {
    [IO.File]::ReadAllLines((Join-Path $AmbientPhoneOutput 'observation-results.txt')) | ForEach-Object {$AmbientPhoneChecks.Add($_)}
}
function Pass-Phone([string]$Message) {
    if(-not $AmbientPhoneChecks.Contains("PASS $Message")) {$AmbientPhoneChecks.Add("PASS $Message")}
    [IO.File]::WriteAllLines((Join-Path $AmbientPhoneOutput 'observation-results.txt'),$AmbientPhoneChecks,[Text.UTF8Encoding]::new($false))
    "PASS $Message"
}
function Start-PhoneObservation {
    $phoneStartUi=Get-PhoneUi
    $phoneSettingsNode=$phoneStartUi.SelectNodes('//node') | Where-Object {$_.text -eq '设置'} | Select-Object -First 1
    if(-not $phoneSettingsNode -or $phoneSettingsNode.bounds -notmatch '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') {throw 'Phone settings control missing'}
    $script:PhoneSettingsX=[int](([int]$Matches[1]+[int]$Matches[3])/2)
    $script:PhoneSettingsY=[int](([int]$Matches[2]+[int]$Matches[4])/2)
    $phoneStopNode=$phoneStartUi.SelectNodes('//node') | Where-Object {$_.text -eq '提前结束'} | Select-Object -First 1
    if(-not $phoneStopNode -or $phoneStopNode.bounds -notmatch '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') {throw 'Phone stop control missing'}
    $script:PhoneStopX=[int](([int]$Matches[1]+[int]$Matches[3])/2)
    $script:PhoneStopY=[int](([int]$Matches[2]+[int]$Matches[4])/2)
    Tap-Phone -Text '开始观察'
    Start-Sleep -Milliseconds 600
    $phoneListeners=@(Get-PhoneAmbientListeners)
    if(-not ($phoneListeners -match 'AndroidObservationRepository') -or
       ($phoneListeners -match 'AndroidSensorRepository|AndroidOrientationPlatform')) {throw 'Expected selected observation listener only'}
}
function Stop-PhoneObservation {Invoke-Phone shell input tap $script:PhoneStopX $script:PhoneStopY | Out-Null}
function Phone-ObservationTop {Show-Phone 'OBSERVATION' -Up}
function Check-PhoneStatistics([string]$Unit) {
    Show-Phone '观察完成'
    $phoneStatusTexts=@(Get-PhoneTexts)
    $phoneCountLine=$phoneStatusTexts | Where-Object {$_ -match '^有效事件 \d+'} | Select-Object -First 1
    if($phoneCountLine -notmatch '^有效事件 (\d+)' -or [long]$Matches[1] -le 0) {throw 'No real sensor events in completed observation'}
    $phoneCount=[long]$Matches[1]
    $phoneStats=Show-PhoneMatch "^[-\d.]+ / [-\d.]+ / [-\d.]+ $([regex]::Escape($Unit))$"
    $phoneStatMatch=[regex]::Match($phoneStats,"^([-\d.]+) / ([-\d.]+) / ([-\d.]+) $([regex]::Escape($Unit))$")
    if(-not $phoneStatMatch.Success) {throw 'Phone summary statistics missing or unit mismatch'}
    $phoneMinimum=[double]::Parse($phoneStatMatch.Groups[1].Value,[Globalization.CultureInfo]::InvariantCulture)
    $phoneMaximum=[double]::Parse($phoneStatMatch.Groups[2].Value,[Globalization.CultureInfo]::InvariantCulture)
    $phoneMean=[double]::Parse($phoneStatMatch.Groups[3].Value,[Globalization.CultureInfo]::InvariantCulture)
    if($phoneMinimum -gt $phoneMean -or $phoneMean -gt $phoneMaximum) {throw 'Phone statistics ordering invalid'}
    if(@(Get-PhoneAmbientListeners).Count -gt 0) {throw 'Completed phone observation still listening'}
    "REAL count=$phoneCount; min/max/mean=$phoneStats"
}
if(-not $LifecycleOnly) {
if(-not $ContinueAfterLight) {
Assert-PhoneText '手机实测分量 · 统计结果为计算值'
Assert-PhoneText '尚未开始'
if(@(Get-PhoneAmbientListeners).Count -gt 0) {throw 'Idle observation started listeners'}
Pass-Phone 'Physical device provenance; opening observation does not start sensors'

Start-PhoneObservation
Start-Sleep -Seconds 30
Check-PhoneStatistics 'lx'
Save-PhoneScreen 'light-thirty-seconds'
Show-Phone '设为对照'
Tap-Phone -Text '设为对照'
Show-Phone '保留的对照 · 光照'
Pass-Phone 'Real 30-second light session completed with valid statistics and released listener; reference retained'
} else {
    Phone-ObservationTop
    Show-Phone '保留的对照 · 光照'
    if(@(Get-PhoneAmbientListeners).Count -gt 0) {throw 'Cannot continue while previous session is active'}
}

Phone-ObservationTop
if(-not $ValidateCurrentLight) {
Start-PhoneObservation
Start-Sleep -Seconds 30
}
Check-PhoneStatistics 'lx'
Show-Phone '保留的对照 · 光照'
$null=Show-PhoneMatch '^当前平均 − 对照平均：'
Save-PhoneScreen 'light-comparison'
Pass-Phone 'Second real same-duration light session shows calculated reference difference'

Phone-ObservationTop
Tap-Phone -Text '地磁场'
Tap-Phone -Text '1 分钟'
Start-PhoneObservation
Start-Sleep -Seconds 30
Start-Sleep -Seconds 30
Check-PhoneStatistics 'μT'
Show-Phone '地磁合成强度 · 计算值' -Up
$phoneAxes=Show-PhoneMatch '^实测 XYZ：'
$phoneAxisMatch=[regex]::Match($phoneAxes,'^实测 XYZ：([-\d.]+) / ([-\d.]+) / ([-\d.]+) μT$')
if(-not $phoneAxisMatch.Success) {throw 'Real signed magnetic components missing'}
$phoneX=[double]$phoneAxisMatch.Groups[1].Value; $phoneY=[double]$phoneAxisMatch.Groups[2].Value; $phoneZ=[double]$phoneAxisMatch.Groups[3].Value
$phoneNorm=[Math]::Sqrt($phoneX*$phoneX+$phoneY*$phoneY+$phoneZ*$phoneZ)
$phoneMagnitude=Show-PhoneMatch '^[-\d.]+ μT$' -Up
if(-not $phoneMagnitude -or [Math]::Abs([double]($phoneMagnitude -replace ' μT$','')-$phoneNorm) -gt .03) {throw 'Magnetic norm disagrees with rounded XYZ'}
Save-PhoneScreen 'magnetic-one-minute'
Pass-Phone 'Real one-minute magnetic session completed; calculated magnitude agrees with measured XYZ'

Phone-ObservationTop
Tap-Phone -Text '气压'
Tap-Phone -Text '30 秒'
if(@(Get-PhoneTexts) -contains '设备不支持此传感器') {
    Pass-Phone 'Pressure unsupported is clearly labelled; no invented reading'
} else {
    Start-PhoneObservation
    Start-Sleep -Seconds 3
    Stop-PhoneObservation
    Show-Phone '已提前结束 · 部分记录'
    Show-Phone '最低 / 最高 / 事件平均 · 计算统计'
    Assert-PhoneText 'hPa'
    Save-PhoneScreen 'pressure-partial'
    if(@(Get-PhoneAmbientListeners).Count -gt 0) {throw 'Manual stop left phone listener active'}
    Pass-Phone 'Supported pressure sensor reports local hectopascals; manual stop retains partial result'
}
}

Phone-ObservationTop
Tap-Phone -Text '光照'
Start-PhoneObservation
Start-Sleep -Seconds 2
Stop-PhoneObservation
Show-Phone '已提前结束 · 部分记录'
if(@(Get-PhoneAmbientListeners).Count -gt 0) {throw 'Manual stop retained observation listener'}
Pass-Phone 'Manual stop releases physical sensor and preserves explicitly partial result'

Phone-ObservationTop
Tap-Phone -Text '光照'
Start-PhoneObservation
Start-Sleep -Seconds 2
Invoke-Phone shell input keyevent 3 | Out-Null
Start-Sleep -Milliseconds 500
if(@(Get-PhoneAmbientListeners).Count -gt 0) {throw 'Phone observation remains active in background'}
Invoke-Phone shell am start -W -n com.ambient.app/.MainActivity | Out-Null
Show-Phone '已提前结束 · 部分记录'
if(@(Get-PhoneAmbientListeners).Count -gt 0) {throw 'Phone observation auto-resumed'}
Pass-Phone 'Background immediately stops physical sensor session; foreground keeps partial result without auto-resume'

Phone-ObservationTop
Start-PhoneObservation
Invoke-Phone shell input tap $script:PhoneSettingsX $script:PhoneSettingsY | Out-Null
if(@(Get-PhoneAmbientListeners).Count -gt 0) {throw 'Settings left phone observation active'}
Tap-Phone -Text '返回'
Show-Phone '已提前结束 · 部分记录'
if(@(Get-PhoneAmbientListeners).Count -gt 0) {throw 'Returning from settings auto-resumed observation'}
Pass-Phone 'Settings interrupts observation and returning does not restart it'

Tap-Phone -Text '返回'
Assert-PhoneText '手机传感器'
if(@(Get-PhoneAmbientListeners) -match 'AndroidObservationRepository') {throw 'Back left observation listener active'}
Tap-Phone -Text '总览'
if(@(Get-PhoneAmbientListeners).Count -gt 0) {throw 'Overview retains phone sensor listeners'}
Pass-Phone 'Back exits observation and overview stops all Ambient sensor listeners'
