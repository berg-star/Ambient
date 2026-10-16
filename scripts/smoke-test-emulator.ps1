param()
. (Join-Path $PSScriptRoot 'environment.ps1')
$AmbientSerial = 'emulator-5560'
$AmbientAdb = Join-Path $AmbientSdk 'platform-tools\adb.exe'
$AmbientScreenshots = Join-Path $AmbientRoot 'docs\screenshots\regression-current\smoke'
$AmbientResults = [Collections.Generic.List[string]]::new()
New-Item -ItemType Directory -Force -Path $AmbientScreenshots | Out-Null

function Invoke-AmbientDevice {
    $DeviceArguments = $args
    $DeviceOutput = & $AmbientAdb -s $AmbientSerial @DeviceArguments
    if ($LASTEXITCODE -ne 0) { throw "Device command failed: $($DeviceArguments -join ' ')" }
    return $DeviceOutput
}
function Get-AmbientUi {
    Invoke-AmbientDevice shell uiautomator dump /data/local/tmp/ambient-ui.xml | Out-Null
    [xml]((Invoke-AmbientDevice exec-out cat /data/local/tmp/ambient-ui.xml) -join "`n")
}
function Tap-AmbientNode {
    param([string]$Text, [string]$Description)
    $Document = Get-AmbientUi
    $Node = @($Document.SelectNodes('//node') | Where-Object {
        if ($Description) { $_.'content-desc' -eq $Description } else { $_.text -eq $Text }
    }) | Select-Object -First 1
    if (-not $Node) { throw "UI element missing: $Text $Description" }
    if ($Node.bounds -notmatch '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') { throw 'Element bounds missing.' }
    $TapX = [int]( ([int]$Matches[1] + [int]$Matches[3]) / 2 )
    $TapY = [int]( ([int]$Matches[2] + [int]$Matches[4]) / 2 )
    Invoke-AmbientDevice shell input tap $TapX $TapY | Out-Null
    Start-Sleep -Milliseconds 250
}
function Assert-AmbientText {
    param([string]$Text)
    $Document = Get-AmbientUi
    if (-not @($Document.SelectNodes('//node') | Where-Object { $_.text -like "*$Text*" }).Count) {
        throw "Expected visible text missing: $Text"
    }
}
function Show-AmbientSensorElement {
    param([string]$Text, [string]$Description, [ValidateSet('Down', 'Up')][string]$Direction = 'Down')
    for ($AmbientScrollAttempt = 0; $AmbientScrollAttempt -le 5; $AmbientScrollAttempt++) {
        $AmbientScrollUi = Get-AmbientUi
        $AmbientVisibleNodes = @($AmbientScrollUi.SelectNodes('//node') | Where-Object {
            if ($Description) { $_.'content-desc' -eq $Description } else { $_.text -like "*$Text*" }
        })
        if ($AmbientVisibleNodes.Count) { return }
        if ($AmbientScrollAttempt -eq 5) { break }
        # This script resets the dedicated emulator to its 720 x 1280 viewport above.
        if ($Direction -eq 'Up') {
            Invoke-AmbientDevice shell input swipe 360 350 360 1000 400 | Out-Null
        } else {
            Invoke-AmbientDevice shell input swipe 360 1000 360 350 400 | Out-Null
        }
        Start-Sleep -Milliseconds 250
    }
    throw "Sensor element not found after scrolling: $Text $Description"
}
function Assert-AmbientListening {
    param([bool]$Expected)
    Start-Sleep -Milliseconds 250
    $SensorDump = (Invoke-AmbientDevice shell dumpsys sensorservice) -join "`n"
    $ActiveSection = ($SensorDump -split 'Previous Registrations:')[0]
    $Actual = $ActiveSection -match 'com\.ambient\.app\.data\.sensor\.AndroidSensorRepository'
    if ($Actual -ne $Expected) { throw "Sensor registration mismatch: expected=$Expected actual=$Actual" }
}
function Save-AmbientScreen {
    param([string]$Name)
    Invoke-AmbientDevice shell screencap -p /data/local/tmp/ambient-screen.png | Out-Null
    Invoke-AmbientDevice pull /data/local/tmp/ambient-screen.png (Join-Path $AmbientScreenshots "$Name.png") | Out-Null
}
function Record-AmbientPass {
    param([string]$Message)
    $AmbientResults.Add("PASS $Message")
    Write-Output "PASS $Message"
}

$AmbientAvd = (Invoke-AmbientDevice emu avd name | Select-Object -First 1).Trim()
if ($AmbientAvd -ne 'Ambient_API_31') { throw 'Only the dedicated Ambient_API_31 emulator may be tested.' }
Invoke-AmbientDevice shell wm size reset | Out-Null
Invoke-AmbientDevice shell wm density reset | Out-Null
Invoke-AmbientDevice shell cmd overlay enable-exclusive --user 0 --category com.android.internal.systemui.navbar.threebutton | Out-Null
$AmbientApk = Join-Path $AmbientRoot 'app\build\outputs\apk\debug\app-debug.apk'
Invoke-AmbientDevice install -r $AmbientApk | Out-Null
Invoke-AmbientDevice shell am force-stop com.ambient.app | Out-Null
Invoke-AmbientDevice shell am start -W -n com.ambient.app/.MainActivity | Out-Null
Tap-AmbientNode -Text '设置'
Tap-AmbientNode -Text '深色'
Tap-AmbientNode -Text '返回'
Assert-AmbientText '24.6'
Assert-AmbientText '演示数据'
Assert-AmbientListening $false
Save-AmbientScreen 'overview-dark'
Record-AmbientPass 'Launch / demo provenance / temperature visible on 360x640 dp / no overview listeners'

Tap-AmbientNode -Text '探测'
Assert-AmbientListening $true
# On-change sensors need a genuine change after a fresh registration.
Invoke-AmbientDevice emu sensor set light 100 | Out-Null
Start-Sleep -Milliseconds 250
Invoke-AmbientDevice emu sensor set light 321 | Out-Null
Start-Sleep -Milliseconds 250
Show-AmbientSensorElement -Text '321.0'
Assert-AmbientText '321.0'
Assert-AmbientText '虚拟传感器'
Save-AmbientScreen 'sensors-dark'
Record-AmbientPass 'Virtual light event 321 lx reached UI with emulator provenance'

Show-AmbientSensorElement -Description '实时传感器采集' -Direction Up
Tap-AmbientNode -Description '实时传感器采集'
Assert-AmbientListening $false
Assert-AmbientText '已暂停'
Record-AmbientPass 'Manual pause unregisters sensors'
Tap-AmbientNode -Description '实时传感器采集'
Assert-AmbientListening $true
Invoke-AmbientDevice emu sensor set light 456 | Out-Null
Start-Sleep -Milliseconds 250
Show-AmbientSensorElement -Text '456.0'
Assert-AmbientText '456.0'
Record-AmbientPass 'Manual resume receives a new hardware event'

Invoke-AmbientDevice shell input keyevent 3 | Out-Null
Assert-AmbientListening $false
Invoke-AmbientDevice shell am start -W -n com.ambient.app/.MainActivity | Out-Null
Assert-AmbientListening $true
Record-AmbientPass 'Background stops sampling; foreground sensor page resumes'

Tap-AmbientNode -Text '设置'
Assert-AmbientListening $false
Tap-AmbientNode -Text '浅色'
Save-AmbientScreen 'settings-light'
Invoke-AmbientDevice shell am force-stop com.ambient.app | Out-Null
Invoke-AmbientDevice shell am start -W -n com.ambient.app/.MainActivity | Out-Null
$AmbientPreference = (Invoke-AmbientDevice shell run-as com.ambient.app cat shared_prefs/ambient_settings.xml) -join "`n"
if ($AmbientPreference -notmatch 'LIGHT') { throw 'Light theme was not persisted.' }
Assert-AmbientText '24.6'
Save-AmbientScreen 'overview-light'
Record-AmbientPass 'Settings stops sampling; light theme persists after process restart'

Tap-AmbientNode -Text '设置'
Tap-AmbientNode -Text '深色'
Tap-AmbientNode -Text '返回'
Tap-AmbientNode -Text '记录'
Assert-AmbientText '后续阶段开放'
Assert-AmbientListening $false
Save-AmbientScreen 'history-dark'
Record-AmbientPass 'History placeholder and navigation'

Tap-AmbientNode -Text '总览'
$AmbientPid = ((Invoke-AmbientDevice shell pidof com.ambient.app) -join '').Trim()
if (-not $AmbientPid) { throw 'Ambient process is not running.' }
$AmbientCrashLog = (Invoke-AmbientDevice logcat -d "--pid=$AmbientPid" -s AndroidRuntime:E) -join "`n"
if ($AmbientCrashLog -match 'FATAL EXCEPTION') { throw 'Ambient runtime crash found.' }
Record-AmbientPass 'Current Ambient process has no fatal runtime exception'
[IO.File]::WriteAllLines((Join-Path $AmbientRoot '.tools\logs\smoke-test-results.txt'), $AmbientResults, [Text.UTF8Encoding]::new($false))
