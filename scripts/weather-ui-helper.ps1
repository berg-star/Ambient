# Shared helpers for the dedicated emulator; never targets physical devices.
. (Join-Path $PSScriptRoot 'environment.ps1')
$WeatherAdb = Join-Path $AmbientSdk 'platform-tools\adb.exe'
$WeatherSerial = 'emulator-5560'
if ((& $WeatherAdb -s $WeatherSerial emu avd name | Select-Object -First 1).Trim() -ne 'Ambient_API_31') {
    throw 'Weather tests require the dedicated Ambient_API_31 emulator.'
}
function Invoke-WeatherDevice {
    $result = & $WeatherAdb -s $WeatherSerial @args
    if ($LASTEXITCODE -ne 0) { throw 'Emulator command failed.' }
    $result
}
function Get-WeatherUi {
    Invoke-WeatherDevice shell uiautomator dump /data/local/tmp/ambient-weather-ui.xml | Out-Null
    [xml]((Invoke-WeatherDevice exec-out cat /data/local/tmp/ambient-weather-ui.xml) -join "`n")
}
function Get-WeatherTexts {
    (Get-WeatherUi).SelectNodes('//node') | ForEach-Object { if ($_.text) { $_.text } }
}
function Tap-WeatherText {
    param([string]$Text)
    $node = (Get-WeatherUi).SelectNodes('//node') | Where-Object { $_.text -eq $Text } | Select-Object -First 1
    if (-not $node) { throw "Element missing: $Text" }
    if ($node.bounds -notmatch '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') { throw 'Missing bounds' }
    $x = [int](([int]$Matches[1]+[int]$Matches[3])/2)
    $y = [int](([int]$Matches[2]+[int]$Matches[4])/2)
    Invoke-WeatherDevice shell input tap $x $y | Out-Null
}
function Show-WeatherText {
    param([string]$Text, [int]$Attempts = 8)
    for ($i = 0; $i -lt $Attempts; $i++) {
        if (@(Get-WeatherTexts) -contains $Text) { return }
        Invoke-WeatherDevice shell input swipe 360 1080 360 420 350 | Out-Null
    }
    throw "Element not found: $Text"
}
function Assert-WeatherText {
    param([string]$Text)
    if (-not (@(Get-WeatherTexts) | Where-Object { $_ -like "*$Text*" })) { throw "Expected text missing: $Text" }
}
function Save-WeatherScreen {
    param([string]$Name)
    $folder = Join-Path $AmbientRoot 'docs\screenshots\weather'
    New-Item -ItemType Directory -Force -Path $folder | Out-Null
    Invoke-WeatherDevice shell screencap -p /data/local/tmp/ambient-weather.png | Out-Null
    Invoke-WeatherDevice pull /data/local/tmp/ambient-weather.png (Join-Path $folder "$Name.png") | Out-Null
}
