param([switch]$HelpersOnly)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'history-ui-helper.ps1')
$orientationResults=[Collections.Generic.List[string]]::new()
$orientationScreens=Join-Path $AmbientRoot 'docs\screenshots\orientation'
New-Item -ItemType Directory -Force -Path $orientationScreens | Out-Null
function Orientation-Pass([string]$Text) { $orientationResults.Add('PASS '+$Text); Write-Output ('PASS '+$Text) }
function Save-OrientationScreen([string]$Name) {
    Invoke-WeatherDevice shell screencap -p /data/local/tmp/ambient-orientation.png | Out-Null
    Invoke-WeatherDevice pull /data/local/tmp/ambient-orientation.png (Join-Path $orientationScreens ($Name+'.png')) | Out-Null
}
function Show-OrientationPart([string]$Text,[switch]$Up) {
    for($attempt=0;$attempt -lt 12;$attempt++) {
        $node=(Get-WeatherUi).SelectNodes('//node') | Where-Object { $_.text.Contains($Text) -or $_.'content-desc'.Contains($Text) } | Select-Object -First 1
        if($node){return}
        Scroll-HistoryUi -Up:$Up
    }
    throw "Orientation UI missing: $Text"
}
function Assert-OrientationListening([bool]$Expected) {
    $deadline=(Get-Date).AddSeconds(5)
    do {
        $dump=(Invoke-WeatherDevice shell dumpsys sensorservice) -join "`n"
        $active=($dump -split 'Previous Registrations:')[0]
        $actual=$active -match 'com\.ambient\.app\.data\.orientation\.AndroidOrientationPlatform'
        if($actual -eq $Expected){return}
        Start-Sleep -Milliseconds 200
    } while((Get-Date) -lt $deadline)
    throw "Orientation listener: expected $Expected got $actual"
}
function Assert-OrientationHeading([double]$Expected) {
    Show-HistoryText '屏幕上边缘朝向 · 0° 北 / 90° 东'
    $deadline=(Get-Date).AddSeconds(25)
    do {
        $node=(Get-WeatherUi).SelectNodes('//node') | Where-Object { $_.'content-desc' -match '^磁北方位 ([0-9.]+) 度' } | Select-Object -First 1
        if($node -and $node.'content-desc' -match '^磁北方位 ([0-9.]+) 度') {
            $heading=[double]::Parse($Matches[1],[Globalization.CultureInfo]::InvariantCulture)
            $difference=[Math]::Abs($heading-$Expected)
            if([Math]::Min($difference,360-$difference) -lt 3){return}
        }
    } while((Get-Date) -lt $deadline)
    throw "Expected simulated heading near $Expected degrees"
}
function Reset-OrientationPose([string]$Acceleration,[string]$Magnetic) {
    Invoke-WeatherDevice shell am force-stop com.ambient.app | Out-Null
    Invoke-WeatherDevice emu sensor set acceleration $Acceleration | Out-Null
    Invoke-WeatherDevice emu sensor set magnetic-field $Magnetic | Out-Null
    Invoke-WeatherDevice emu sensor set gyroscope '0:0:0' | Out-Null
    Invoke-WeatherDevice shell am start -W -n com.ambient.app/.MainActivity | Out-Null
    Wait-HistoryText '探测'
    Tap-WeatherText '探测'
}

if ($HelpersOnly) { return }

$originalAuto=(Invoke-WeatherDevice shell settings get system accelerometer_rotation).Trim()
$originalRotation=(Invoke-WeatherDevice shell settings get system user_rotation).Trim()
try {
    Invoke-WeatherDevice shell settings put system accelerometer_rotation 0 | Out-Null
    Invoke-WeatherDevice shell settings put system user_rotation 0 | Out-Null
    Reset-OrientationPose '0:0:9.80665' '0:30:-20'
    Assert-OrientationListening $true
    Assert-OrientationHeading 0
    Assert-WeatherText '虚拟数据 · 姿态计算'
    Save-OrientationScreen '01-magnetic-north'
    Orientation-Pass 'Simulated north reaches calculated compass with explicit virtual provenance'

    Show-HistoryText '接近水平 · 偏离水平均小于 1°'
    Save-OrientationScreen '02-flat-level'
    Orientation-Pass 'Face-up horizontal pose displays near-level bubble and signed angles'

    Show-OrientationPart '实时传感器采集' -Up
    Tap-HistoryDescription '实时传感器采集'
    Assert-OrientationListening $false
    Show-HistoryText '保留最后一次计算结果，当前未采集。'
    Save-OrientationScreen '03-paused-reading'
    Show-OrientationPart '实时传感器采集' -Up
    Tap-HistoryDescription '实时传感器采集'
    Assert-OrientationListening $true
    Tap-WeatherText '设置'
    Assert-OrientationListening $false
    Tap-WeatherText '返回'
    Assert-OrientationListening $true
    Invoke-WeatherDevice shell input keyevent 3 | Out-Null
    Assert-OrientationListening $false
    Invoke-WeatherDevice shell am start -W -n com.ambient.app/.MainActivity | Out-Null
    Assert-OrientationListening $true
    Tap-WeatherText '记录'
    Assert-OrientationListening $false
    Tap-WeatherText '探测'
    Assert-OrientationListening $true
    Orientation-Pass 'Pause/resume, settings, background and tab navigation correctly unregister/restart orientation'

    Invoke-WeatherDevice shell settings put system user_rotation 1 | Out-Null
    Assert-OrientationHeading 90
    Save-OrientationScreen '04-landscape-coordinate-mapping'
    Invoke-WeatherDevice shell settings put system user_rotation 0 | Out-Null
    Orientation-Pass 'Display rotation remaps the same physical sensor pose to the screen top edge'

    Reset-OrientationPose '0:4.903325:8.492808' '0:15.980762:-32.320508'
    Show-HistoryText '前后 · 正值上边抬高'
    $tiltDeadline=(Get-Date).AddSeconds(25)
    $tiltFound=$false
    do {
        $tilts=@(Get-WeatherTexts) | Where-Object { $_ -match '^\+[23][0-9]\.[0-9]°$' }
        foreach($text in $tilts){$degrees=[double]::Parse(($text -replace '°',''),[Globalization.CultureInfo]::InvariantCulture); if($degrees -gt 28 -and $degrees -lt 32){$tiltFound=$true}}
    } while(-not $tiltFound -and (Get-Date) -lt $tiltDeadline)
    if(-not $tiltFound){throw 'Expected +30-degree simulated top-edge tilt'}
    Save-OrientationScreen '05-top-edge-raised'
    Orientation-Pass 'A simulated 30-degree top-edge raise produces the positive front/back tilt'

    Reset-OrientationPose '0:0:-9.80665' '0:-30:20'
    Show-HistoryText '请将屏幕朝上；翻面时不显示气泡。'
    Assert-WeatherText '请将屏幕朝上'
    Save-OrientationScreen '06-face-down'
    Orientation-Pass 'Face-down pose is identified and does not show a false centered bubble'

    Reset-OrientationPose '0:9.80665:0' '0:-20:-30'
    Show-OrientationPart '手机接近直立'
    Save-OrientationScreen '07-vertical-heading-unavailable'
    Orientation-Pass 'Near-vertical screen top has no fabricated magnetic heading'

    Reset-OrientationPose '0:0:9.80665' '0:30:-20'
    Tap-WeatherText '设置'
    Tap-WeatherText '浅色'
    Tap-WeatherText '返回'
    Show-HistoryText '接近水平 · 偏离水平均小于 1°'
    Save-OrientationScreen '08-light-level'
    Tap-WeatherText '设置'
    Tap-WeatherText '深色'
    Tap-WeatherText '返回'
    Tap-WeatherText '总览'
    Assert-OrientationListening $false
    $processId=((Invoke-WeatherDevice shell pidof com.ambient.app) -join '').Trim()
    $crashes=(Invoke-WeatherDevice logcat -d "--pid=$processId" -s AndroidRuntime:E) -join "`n"
    if($crashes -match 'FATAL EXCEPTION'){throw 'Ambient runtime crash found'}
    Orientation-Pass 'Light/dark instruments render; overview stops orientation; no fatal runtime exception'
    [IO.File]::WriteAllLines((Join-Path $AmbientRoot '.tools\logs\orientation-ui-results.txt'),$orientationResults,[Text.UTF8Encoding]::new($false))
} finally {
    Invoke-WeatherDevice shell settings put system user_rotation $originalRotation | Out-Null
    Invoke-WeatherDevice shell settings put system accelerometer_rotation $originalAuto | Out-Null
    Invoke-WeatherDevice emu sensor set acceleration '0:0:9.8' | Out-Null
    Invoke-WeatherDevice emu sensor set magnetic-field '0:5.9:-48.1' | Out-Null
    Invoke-WeatherDevice emu sensor set gyroscope '0:0:0' | Out-Null
}
