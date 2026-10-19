param([Parameter(Mandatory=$true)][string]$PhoneSerial,
    [ValidateSet('Sensors','Acceleration','History','Themes','Weather','Precipitation','Sky','Restart')][string]$StartAt='Sensors')
# Read-only history acceptance. Uses existing region and credentials; never resets user data.
. (Join-Path $PSScriptRoot 'phone-ui-helper.ps1') -PhoneSerial $PhoneSerial
$ErrorActionPreference='Stop'
$AmbientPhoneRegression=[Collections.Generic.List[string]]::new()
$phoneStageIndex=@('Sensors','Acceleration','History','Themes','Weather','Precipitation','Sky','Restart').IndexOf($StartAt)
if($StartAt -ne 'Sensors') {
    [IO.File]::ReadAllLines((Join-Path $AmbientPhoneOutput 'regression-results.txt')) | ForEach-Object {$AmbientPhoneRegression.Add($_)}
}
function Pass-PhoneRegression([string]$Message) {
    if(-not $AmbientPhoneRegression.Contains("PASS $Message")) {$AmbientPhoneRegression.Add("PASS $Message")}
    [IO.File]::WriteAllLines((Join-Path $AmbientPhoneOutput 'regression-results.txt'),$AmbientPhoneRegression,[Text.UTF8Encoding]::new($false))
    "PASS $Message"
}
if($phoneStageIndex -le 0) {
Tap-Phone -Text '探测'
Assert-PhoneText '查看实测读数与明确标注的系统计算数据。'
$null=Show-PhoneMatch '^\d+ / \d+ 项基础读数'
Show-Phone '手机指南针'
Save-PhoneScreen 'compass-static'
Show-Phone '倾斜水平仪'
Save-PhoneScreen 'level-static'
Pass-PhoneRegression 'Real device capability detection and static compass/level pages render with source labels; movement accuracy not tested'

Show-Phone '传感器采集已开启' -Up
Tap-Phone -Description '实时传感器采集'
if(@(Get-PhoneAmbientListeners).Count -gt 0) {throw 'Manual pause left real sensor listeners'}
Assert-PhoneText '传感器采集已暂停'
Tap-Phone -Description '实时传感器采集'
if(-not (@(Get-PhoneAmbientListeners) -match 'AndroidSensorRepository')) {throw 'Manual resume did not register real sensors'}
Pass-PhoneRegression 'Normal sensor manual pause releases listeners; resume registers them again'
}

if($phoneStageIndex -le 1) {
if($StartAt -eq 'Acceleration') {Tap-Phone -Text '探测'}
Show-Phone '去重力'
Tap-Phone -Description '加速度去重力'
Start-Sleep -Seconds 1
$null=Show-PhoneMatch '^XYZ 为系统去重力分量'
Save-PhoneScreen 'acceleration-linear-static'
Show-Phone '含重力' -Up
Tap-Phone -Description '加速度含重力'
Start-Sleep -Seconds 1
$null=Show-PhoneMatch '^XYZ 为实测分量 · m/s²$'
Save-PhoneScreen 'acceleration-raw-static'
Show-Phone '去重力' -Up
Tap-Phone -Description '加速度去重力'
Tap-Phone -Text '总览'
if(@(Get-PhoneAmbientListeners).Count -gt 0) {throw 'Leaving normal sensors retained listeners'}
Pass-PhoneRegression 'Both real acceleration modes render their distinct sources and units; leaving page stops sampling'
}

if($phoneStageIndex -le 2) {
Tap-Phone -Text '记录'
$phoneHistoryTexts=@(Get-PhoneTexts)
if(-not ($phoneHistoryTexts -contains '还没有环境记录') -and
   -not ($phoneHistoryTexts -match '^\d+ 条记录 · 按保存时间排列$')) {throw 'History content not ready or unexpected filter state'}
Tap-Phone -Text '总览'
Pass-PhoneRegression 'Existing history and navigation preserved; no records created or deleted'
}

if($phoneStageIndex -le 3) {
$phoneAppearance=[xml]((Invoke-Phone shell run-as com.ambient.app cat shared_prefs/ambient_settings.xml)-join "`n")
$phoneOriginalStyle=$phoneAppearance.SelectSingleNode('/map/string[@name="theme_style"]').InnerText
$phoneOriginalMode=$phoneAppearance.SelectSingleNode('/map/string[@name="theme"]').InnerText
# Match MainActivity defaults for older installations that never saved a style.
if([string]::IsNullOrEmpty($phoneOriginalStyle)) {$phoneOriginalStyle='FLOWING_GLASS'}
if([string]::IsNullOrEmpty($phoneOriginalMode)) {$phoneOriginalMode=if($phoneOriginalStyle -eq 'FLOWING_GLASS') {'DARK'} else {'LIGHT'}}
$phoneStyleLabels=@{SUNLIT_AMBER='日光琥珀';MISTY_DUSK='雾蓝暮色';FLOWING_GLASS='流光玻璃'}
$phoneModeLabels=@{LIGHT='浅色';DARK='深色';SYSTEM='跟随系统'}
if(-not $phoneStyleLabels.ContainsKey([string]$phoneOriginalStyle) -or -not $phoneModeLabels.ContainsKey([string]$phoneOriginalMode)) {throw 'Cannot safely restore original appearance'}
Tap-Phone -Text '设置'
try {
    foreach($phoneStyle in @('SUNLIT_AMBER','MISTY_DUSK','FLOWING_GLASS')) {
        Show-Phone $phoneStyleLabels[$phoneStyle]
        Tap-Phone -Text $phoneStyleLabels[$phoneStyle]
        Save-PhoneScreen "theme-$phoneStyle"
    }
} finally {
    Show-Phone $phoneStyleLabels[[string]$phoneOriginalStyle] -Up
    Tap-Phone -Text $phoneStyleLabels[[string]$phoneOriginalStyle]
    Show-Phone $phoneModeLabels[[string]$phoneOriginalMode]
    Tap-Phone -Text $phoneModeLabels[[string]$phoneOriginalMode]
}
Tap-Phone -Text '返回'
Pass-PhoneRegression 'Three gradient themes render on Huawei; original theme and light/dark preference restored'
}

if($phoneStageIndex -le 4) {
# One normal weather refresh with the user's existing selected region; no new location request.
Show-Phone '刷新' -Up
Tap-Phone -Text '刷新'
Start-Sleep -Seconds 5
Assert-PhoneText '和风天气 · 区域 API，非手机实测'
Show-Phone '环境详情  ›'
Tap-Phone -Text '环境详情  ›'
Assert-PhoneText '区域 API · 和风天气 · 非手机实测'
foreach($phoneMetric in @('体感温度','露点温度','能见度','云量','阵风风速')) {Show-Phone $phoneMetric}
Save-PhoneScreen 'environment-details'
Tap-Phone -Text '返回'
Pass-PhoneRegression 'Regional weather source and all five detail cards render; missing provider fields stay explicit'
}

if($phoneStageIndex -le 5) {
Show-Phone '降水预报' -Up
Tap-Phone -Text '降水预报'
Start-Sleep -Seconds 5
Assert-PhoneText '区域预报 · 和风天气 · 非手机实测'
foreach($phoneRange in @('2 小时','24 小时','72 小时')) {
    Show-Phone 'PRECIPITATION' -Up
    Tap-Phone -Text $phoneRange
    if($phoneRange -eq '2 小时') {Show-Phone '未来两小时'; Assert-PhoneText '每 5 分钟'}
    else {Show-Phone "未来 $phoneRange"; Assert-PhoneText '每小时'}
    Save-PhoneScreen ("precipitation-"+($phoneRange -replace ' 小时',''))
}
Tap-Phone -Text '返回'
Pass-PhoneRegression 'Live precipitation cards available for 2/24/72 hours with five-minute versus hourly precision labels'
}

if($phoneStageIndex -le 6) {
Show-Phone '日光与天空' -Up
Tap-Phone -Text '日光与天空'
Start-Sleep -Seconds 5
Assert-PhoneText '天文计算 · 和风天气 · 非手机实测'
Show-Phone '今日白昼'
Show-Phone '日出'
Save-PhoneScreen 'sky-daylight'
Show-Phone '太阳位置'
Show-Phone '月相'
Save-PhoneScreen 'sky-moon'
Tap-Phone -Text '返回'
Pass-PhoneRegression 'Real astronomy response renders daylight, solar position and moon phase with calculation provenance'
}

if(@(Get-PhoneAmbientListeners).Count -gt 0) {throw 'Overview retains sensor listeners after regression'}
Invoke-Phone shell am force-stop com.ambient.app | Out-Null
Invoke-Phone shell am start -W -n com.ambient.app/.MainActivity | Out-Null
Assert-PhoneText '和风天气 · 区域 API，非手机实测'
Save-PhoneScreen 'overview-after-restart'
$phoneProcessId=((Invoke-Phone shell pidof com.ambient.app)-join '').Trim()
if(-not $phoneProcessId) {throw 'Phone app not running'}
if(((Invoke-Phone logcat -d "--pid=$phoneProcessId" -s AndroidRuntime:E)-join "`n") -match 'FATAL EXCEPTION') {throw 'Phone runtime crash found'}
Pass-PhoneRegression 'Process restart restores regional overview; no active sensor listener or current-process fatal exception'
