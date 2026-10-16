param()
. (Join-Path $PSScriptRoot 'environment.ps1')
$AmbientSerial = 'emulator-5560'
$AmbientAdb = Join-Path $AmbientSdk 'platform-tools\adb.exe'
$AmbientMotionShots = Join-Path $AmbientRoot 'docs\screenshots\motion'
$AmbientMotionResults = [Collections.Generic.List[string]]::new()
New-Item -ItemType Directory -Force -Path $AmbientMotionShots | Out-Null

function Invoke-MotionDevice {
    $AmbientOutput = & $AmbientAdb -s $AmbientSerial @args
    if ($LASTEXITCODE -ne 0) { throw "Device command failed: $($args -join ' ')" }
    $AmbientOutput
}
function Get-MotionUi {
    Invoke-MotionDevice shell uiautomator dump /data/local/tmp/ambient-motion-ui.xml | Out-Null
    [xml]((Invoke-MotionDevice exec-out cat /data/local/tmp/ambient-motion-ui.xml) -join "`n")
}
function Find-MotionNode {
    param([string]$Text, [switch]$Description)
    $AmbientUi = Get-MotionUi
    $AmbientUi.SelectNodes('//node') | Where-Object {
        if ($Description) { $_.'content-desc' -eq $Text } else { $_.text -eq $Text }
    } | Select-Object -First 1
}
function Tap-MotionText {
    param([string]$Text, [switch]$Description)
    $AmbientNode = Find-MotionNode $Text -Description:$Description
    if (-not $AmbientNode) { throw "Visible element missing: $Text" }
    if ($AmbientNode.bounds -notmatch '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') { throw 'Missing bounds' }
    Invoke-MotionDevice shell input tap ([int](([int]$Matches[1]+[int]$Matches[3])/2)) ([int](([int]$Matches[2]+[int]$Matches[4])/2)) | Out-Null
    Start-Sleep -Milliseconds 350
}
function Assert-MotionText {
    param([string]$Text)
    $AmbientUi = Get-MotionUi
    if (-not @($AmbientUi.SelectNodes('//node') | Where-Object { $_.text -like "*$Text*" }).Count) {
        throw "Visible text missing: $Text"
    }
}
function Save-MotionShot {
    param([string]$Name)
    Invoke-MotionDevice shell screencap -p /data/local/tmp/ambient-motion-shot.png | Out-Null
    Invoke-MotionDevice pull /data/local/tmp/ambient-motion-shot.png (Join-Path $AmbientMotionShots "$Name.png") | Out-Null
}
function Add-MotionPass {
    param([string]$Message)
    $AmbientMotionResults.Add("PASS $Message")
    Write-Output "PASS $Message"
}
function Assert-MotionLocationRequest {
    param([bool]$Expected)
    $AmbientDump = (Invoke-MotionDevice shell dumpsys location) -join "`n"
    # Historical event logs retain prior requests; inspect current provider registrations only.
    $AmbientCurrent = ($AmbientDump -split 'Historical Aggregate Location Provider Data:')[0]
    $AmbientActive = $AmbientCurrent -match 'com\.ambient\.app/.*Request\['
    if ($AmbientActive -ne $Expected) { throw "Expected Ambient GPS request $Expected; got $AmbientActive" }
}
function Reset-MotionApp {
    Invoke-MotionDevice shell am force-stop com.ambient.app | Out-Null
    Invoke-MotionDevice shell am start -W -n com.ambient.app/.MainActivity | Out-Null
    Tap-MotionText '探测'
}

$AmbientAvd = (Invoke-MotionDevice emu avd name | Select-Object -First 1).Trim()
if ($AmbientAvd -ne 'Ambient_API_31') { throw 'Only the dedicated Ambient_API_31 emulator may be changed.' }
Invoke-MotionDevice shell wm size reset | Out-Null
Invoke-MotionDevice shell wm density reset | Out-Null
Invoke-MotionDevice install -r (Join-Path $AmbientRoot 'app\build\outputs\apk\debug\app-debug.apk') | Out-Null
# Reset only Ambient's test-app state on the identity-checked dedicated emulator.
Invoke-MotionDevice shell pm clear com.ambient.app | Out-Null
Invoke-MotionDevice shell cmd location set-location-enabled true | Out-Null
Reset-MotionApp
Assert-MotionText '定位速度'
Assert-MotionText '开启后显示移动速度'
Save-MotionShot 'speed-off'
Add-MotionPass 'Speed is opt-in and has no fabricated value before authorization'

Tap-MotionText '定位速度采集' -Description
$AmbientUi = Get-MotionUi
$AmbientDeny = $AmbientUi.SelectNodes('//node') | Where-Object { $_.'resource-id' -like '*permission_deny_button' } | Select-Object -First 1
if (-not $AmbientDeny) { throw 'Android runtime permission prompt missing' }
Tap-MotionText $AmbientDeny.text
Assert-MotionText '需要定位授权'
Save-MotionShot 'speed-permission-denied'
Add-MotionPass 'Permission denial shows an explicit unavailable state'

Invoke-MotionDevice shell pm grant com.ambient.app android.permission.ACCESS_COARSE_LOCATION | Out-Null
Reset-MotionApp
Tap-MotionText '定位速度采集' -Description
# Coarse-only access may reopen the Android precise-location prompt; dismiss it.
$AmbientUi = Get-MotionUi
$AmbientDeny = $AmbientUi.SelectNodes('//node') | Where-Object { $_.'resource-id' -like '*permission_deny_button' } | Select-Object -First 1
if ($AmbientDeny) { Tap-MotionText $AmbientDeny.text }
elseif (Find-MotionNode 'KEEP APPROXIMATE LOCATION') { Tap-MotionText 'KEEP APPROXIMATE LOCATION' }
Assert-MotionText '当前仅有大致位置权限'
Add-MotionPass 'Approximate-only permission is not presented as a GPS speed'

Invoke-MotionDevice shell pm grant com.ambient.app android.permission.ACCESS_FINE_LOCATION | Out-Null
Reset-MotionApp
Tap-MotionText '定位速度采集' -Description
Invoke-MotionDevice shell input swipe 360 1000 360 660 800 | Out-Null
& (Join-Path $PSScriptRoot 'inject-test-location.ps1') -Speed 5.144444 -Accuracy 0.25
Start-Sleep -Milliseconds 1500
Assert-MotionText '18.5'
Assert-MotionText '模拟定位 · 测试数据'
Assert-MotionLocationRequest $true
Save-MotionShot 'speed-active'
Add-MotionPass '5.144444 m/s test GPS input displays 18.5 km/h with test provenance and accuracy'

& (Join-Path $PSScriptRoot 'inject-test-location.ps1') -NoSpeed
Start-Sleep -Milliseconds 1300
Assert-MotionText '系统尚未提供有效速度'
Save-MotionShot 'speed-missing'
Add-MotionPass 'Location without a speed field shows unavailable rather than zero'

& (Join-Path $PSScriptRoot 'inject-test-location.ps1') -Speed 5.144444 -Accuracy 0.25
Start-Sleep -Milliseconds 11500
Assert-MotionText '定位读数已过期'
Save-MotionShot 'speed-stale'
Add-MotionPass 'A speed with no updates expires and is hidden after ten seconds'

Invoke-MotionDevice shell cmd location set-location-enabled false | Out-Null
Start-Sleep -Milliseconds 1300
Assert-MotionText '系统定位已关闭'
Save-MotionShot 'speed-gps-off'
Invoke-MotionDevice shell cmd location set-location-enabled true | Out-Null
Start-Sleep -Milliseconds 1300
& (Join-Path $PSScriptRoot 'inject-test-location.ps1') -Speed 5.144444 -Accuracy 0.25
Start-Sleep -Milliseconds 1300
Assert-MotionText '18.5'
Add-MotionPass 'System GPS disable clears the value and re-enable can recover'

Tap-MotionText '设置'
Assert-MotionLocationRequest $false
Tap-MotionText '返回'
Invoke-MotionDevice shell input keyevent 3 | Out-Null
Start-Sleep -Milliseconds 400
Assert-MotionLocationRequest $false
Invoke-MotionDevice shell am start -W -n com.ambient.app/.MainActivity | Out-Null
Add-MotionPass 'Settings and background stop the foreground GPS request'

# Scroll to the acceleration card. Its mode selector remains available on unsupported devices.
for ($AmbientAttempt = 0; $AmbientAttempt -lt 8; $AmbientAttempt++) {
    $AmbientAccelerationNode = Find-MotionNode '加速度去重力' -Description
    if ($AmbientAccelerationNode -and $AmbientAccelerationNode.bounds -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') {
        if ([int]$Matches[4] -le 650) { break }
        # A partly visible selector needs a smaller scroll to reveal its explanation and value.
        Invoke-MotionDevice shell input swipe 360 940 360 680 700 | Out-Null
    } else {
        Invoke-MotionDevice shell input swipe 360 940 360 420 700 | Out-Null
    }
}
Assert-MotionText '去重力'
Assert-MotionText '静止时通常接近 0'
Save-MotionShot 'acceleration-linear'
Tap-MotionText '加速度含重力' -Description
Assert-MotionText '保留重力分量'
Invoke-MotionDevice emu sensor set acceleration 0:0:9.81 | Out-Null
Start-Sleep -Milliseconds 500
Assert-MotionText '9.81'
Save-MotionShot 'acceleration-raw'
Tap-MotionText '加速度去重力' -Description
Assert-MotionText '静止时通常接近 0'
Add-MotionPass 'Acceleration defaults to gravity-removed and can switch both ways'

$AmbientPid = ((Invoke-MotionDevice shell pidof com.ambient.app) -join '').Trim()
if (-not $AmbientPid) { throw 'Ambient is not running' }
$AmbientCrashLog = (Invoke-MotionDevice logcat -d "--pid=$AmbientPid" -s AndroidRuntime:E) -join "`n"
if ($AmbientCrashLog -match 'FATAL EXCEPTION') { throw 'Ambient fatal exception found' }
Add-MotionPass 'No fatal exception in the current Ambient process'
& (Join-Path $PSScriptRoot 'inject-test-location.ps1') -Reset
[IO.File]::WriteAllLines((Join-Path $AmbientRoot '.tools\logs\motion-test-results.txt'), $AmbientMotionResults, [Text.UTF8Encoding]::new($false))
