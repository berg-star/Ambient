param()
. (Join-Path $PSScriptRoot 'environment.ps1')
$AmbientSerial = 'emulator-5560'
$AmbientAdb = Join-Path $AmbientSdk 'platform-tools\adb.exe'
$AmbientThemeShots = Join-Path $AmbientRoot 'docs\screenshots\regression-current\themes'
$AmbientThemeResults = [Collections.Generic.List[string]]::new()
New-Item -ItemType Directory -Force -Path $AmbientThemeShots | Out-Null

function Invoke-ThemeDevice {
    $AmbientArguments = $args
    $AmbientOutput = & $AmbientAdb -s $AmbientSerial @AmbientArguments
    if ($LASTEXITCODE -ne 0) { throw "Device command failed: $($AmbientArguments -join ' ')" }
    $AmbientOutput
}
function Get-ThemeUi {
    Invoke-ThemeDevice shell uiautomator dump /data/local/tmp/ambient-theme-ui.xml | Out-Null
    [xml]((Invoke-ThemeDevice exec-out cat /data/local/tmp/ambient-theme-ui.xml) -join "`n")
}
function Tap-ThemeText {
    param([string]$Text)
    $AmbientUi = Get-ThemeUi
    $AmbientNode = $AmbientUi.SelectNodes('//node') | Where-Object { $_.text -eq $Text } | Select-Object -First 1
    if (-not $AmbientNode) { throw "Visible element missing: $Text" }
    if ($AmbientNode.bounds -notmatch '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') { throw 'Missing element bounds.' }
    $AmbientX = [int](([int]$Matches[1] + [int]$Matches[3]) / 2)
    $AmbientY = [int](([int]$Matches[2] + [int]$Matches[4]) / 2)
    Invoke-ThemeDevice shell input tap $AmbientX $AmbientY | Out-Null
    Start-Sleep -Milliseconds 250
}
function Assert-ThemeText {
    param([string]$Text)
    $AmbientUi = Get-ThemeUi
    if (-not @($AmbientUi.SelectNodes('//node') | Where-Object { $_.text -like "*$Text*" }).Count) {
        throw "Visible text missing: $Text"
    }
}
function Show-ThemeSensorText {
    param([string]$Text)
    for ($AmbientScrollAttempt = 0; $AmbientScrollAttempt -le 5; $AmbientScrollAttempt++) {
        $AmbientScrollUi = Get-ThemeUi
        if (@($AmbientScrollUi.SelectNodes('//node') | Where-Object { $_.text -like "*$Text*" }).Count) { return }
        if ($AmbientScrollAttempt -eq 5) { break }
        # The opt-in speed card precedes light; reveal the light reading before asserting it.
        Invoke-ThemeDevice shell input swipe 360 1000 360 350 400 | Out-Null
        Start-Sleep -Milliseconds 250
    }
    throw "Sensor text not found after scrolling: $Text"
}
function Assert-ThemePreference {
    param([string]$Style, [string]$Mode)
    $AmbientPreferences = [xml]((Invoke-ThemeDevice shell run-as com.ambient.app cat shared_prefs/ambient_settings.xml) -join "`n")
    $AmbientSavedStyle = $AmbientPreferences.SelectSingleNode('/map/string[@name="theme_style"]').InnerText
    $AmbientSavedMode = $AmbientPreferences.SelectSingleNode('/map/string[@name="theme"]').InnerText
    if ($AmbientSavedStyle -ne $Style -or $AmbientSavedMode -ne $Mode) {
        throw "Appearance not persisted: $AmbientSavedStyle/$AmbientSavedMode, expected $Style/$Mode"
    }
}
function Assert-ThemeListening {
    param([bool]$Expected)
    $AmbientDump = (Invoke-ThemeDevice shell dumpsys sensorservice) -join "`n"
    $AmbientActive = (($AmbientDump -split 'Previous Registrations:')[0]) -match 'com\.ambient\.app\.data\.sensor\.AndroidSensorRepository'
    if ($AmbientActive -ne $Expected) { throw "Expected sensor registration $Expected; got $AmbientActive" }
}
function Save-ThemeShot {
    param([string]$Name)
    Invoke-ThemeDevice shell screencap -p /data/local/tmp/ambient-theme-shot.png | Out-Null
    Invoke-ThemeDevice pull /data/local/tmp/ambient-theme-shot.png (Join-Path $AmbientThemeShots "$Name.png") | Out-Null
}
function Add-ThemePass {
    param([string]$Message)
    $AmbientThemeResults.Add("PASS $Message")
    Write-Output "PASS $Message"
}

$AmbientAvd = (Invoke-ThemeDevice emu avd name | Select-Object -First 1).Trim()
if ($AmbientAvd -ne 'Ambient_API_31') { throw 'Only the dedicated Ambient_API_31 emulator may be changed.' }
Invoke-ThemeDevice shell wm size reset | Out-Null
Invoke-ThemeDevice shell wm density reset | Out-Null
Invoke-ThemeDevice shell cmd overlay enable-exclusive --user 0 --category com.android.internal.systemui.navbar.threebutton | Out-Null
Invoke-ThemeDevice install -r (Join-Path $AmbientRoot 'app\build\outputs\apk\debug\app-debug.apk') | Out-Null
Invoke-ThemeDevice shell am force-stop com.ambient.app | Out-Null
Invoke-ThemeDevice shell am start -W -n com.ambient.app/.MainActivity | Out-Null

$AmbientStyles = @(
    @{Name='日光琥珀'; Key='SUNLIT_AMBER'; Slug='sunlit-amber'; Preferred='LIGHT'; Other='深色'; OtherKey='DARK'},
    @{Name='雾蓝暮色'; Key='MISTY_DUSK'; Slug='misty-dusk'; Preferred='LIGHT'; Other='深色'; OtherKey='DARK'},
    @{Name='流光玻璃'; Key='FLOWING_GLASS'; Slug='flowing-glass'; Preferred='DARK'; Other='浅色'; OtherKey='LIGHT'}
)
foreach ($AmbientStyle in $AmbientStyles) {
    Tap-ThemeText '设置'
    Tap-ThemeText $AmbientStyle.Name
    Assert-ThemePreference $AmbientStyle.Key $AmbientStyle.Preferred
    Save-ThemeShot ("settings-" + $AmbientStyle.Slug)
    Tap-ThemeText '返回'
    Assert-ThemeText '24.6'
    Assert-ThemeText '演示'
    Assert-ThemeListening $false
    Save-ThemeShot ($AmbientStyle.Slug + '-' + $AmbientStyle.Preferred.ToLowerInvariant())
    Add-ThemePass ($AmbientStyle.Name + ' recommended appearance / saved style and mode / honest data provenance')

    Tap-ThemeText '探测'
    Assert-ThemeListening $true
    # On-change sensors need a genuine value change after each new registration.
    Invoke-ThemeDevice emu sensor set light 100 | Out-Null
    Start-Sleep -Milliseconds 250
    Invoke-ThemeDevice emu sensor set light 321 | Out-Null
    Start-Sleep -Milliseconds 250
    Show-ThemeSensorText '321.0'
    Assert-ThemeText '321.0'
    Assert-ThemeText '虚拟传感器'
    Save-ThemeShot ('sensors-' + $AmbientStyle.Slug)
    Tap-ThemeText '设置'
    Assert-ThemeListening $false
    Tap-ThemeText $AmbientStyle.Other
    Assert-ThemePreference $AmbientStyle.Key $AmbientStyle.OtherKey
    Tap-ThemeText '返回'
    Tap-ThemeText '总览'
    Assert-ThemeText '24.6'
    Save-ThemeShot ($AmbientStyle.Slug + '-' + $AmbientStyle.OtherKey.ToLowerInvariant())
    Add-ThemePass ($AmbientStyle.Name + ' alternate appearance / sensor reading / settings stops sampling')
}

Invoke-ThemeDevice shell am force-stop com.ambient.app | Out-Null
Invoke-ThemeDevice shell am start -W -n com.ambient.app/.MainActivity | Out-Null
Assert-ThemePreference 'FLOWING_GLASS' 'LIGHT'
Assert-ThemeText '24.6'
Save-ThemeShot 'flowing-glass-light-after-restart'
Add-ThemePass 'Style and explicit appearance survive process restart'

Tap-ThemeText '设置'
Tap-ThemeText '跟随系统'
Assert-ThemePreference 'FLOWING_GLASS' 'SYSTEM'
Tap-ThemeText '返回'
Invoke-ThemeDevice shell cmd uimode night yes | Out-Null
Assert-ThemeText '24.6'
Save-ThemeShot 'flowing-glass-system-dark'
Invoke-ThemeDevice shell cmd uimode night no | Out-Null
Assert-ThemeText '24.6'
Save-ThemeShot 'flowing-glass-system-light'
Add-ThemePass 'System appearance responds to Android night-mode changes'

Tap-ThemeText '设置'
Tap-ThemeText '流光玻璃'
Assert-ThemePreference 'FLOWING_GLASS' 'DARK'
Tap-ThemeText '返回'
Tap-ThemeText '相对湿度'
Assert-ThemeText '知道了'
Assert-ThemeText '固定演示值'
Tap-ThemeText '知道了'
Add-ThemePass 'Metric detail keeps physical meaning and demo provenance accessible'

$AmbientPid = ((Invoke-ThemeDevice shell pidof com.ambient.app) -join '').Trim()
if (-not $AmbientPid) { throw 'Ambient process is not running.' }
$AmbientCrashLog = (Invoke-ThemeDevice logcat -d "--pid=$AmbientPid" -s AndroidRuntime:E) -join "`n"
if ($AmbientCrashLog -match 'FATAL EXCEPTION') { throw 'Ambient fatal exception found.' }
Add-ThemePass 'Current Ambient process has no fatal runtime exception'
[IO.File]::WriteAllLines((Join-Path $AmbientRoot '.tools\logs\theme-test-results.txt'), $AmbientThemeResults, [Text.UTF8Encoding]::new($false))
