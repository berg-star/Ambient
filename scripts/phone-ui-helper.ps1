param([Parameter(Mandatory=$true)][string]$PhoneSerial)
# Explicit device selection only. Never clears app data or injects synthetic sensor/location events.
. (Join-Path $PSScriptRoot 'environment.ps1')
$AmbientPhoneAdb=Join-Path $AmbientSdk 'platform-tools\adb.exe'
$AmbientPhoneOutput=Join-Path $AmbientRoot '.tools\phone-acceptance\v1.0.0'
New-Item -ItemType Directory -Force -Path $AmbientPhoneOutput | Out-Null
function Invoke-Phone {
    $phoneResult=& $AmbientPhoneAdb -s $PhoneSerial @args
    if($LASTEXITCODE -ne 0) { throw "Phone command failed: $(@($args | Select-Object -First 3) -join ' ')" }
    $phoneResult
}
if((Invoke-Phone shell getprop ro.product.manufacturer).Trim() -ne 'HUAWEI' -or
   (Invoke-Phone shell getprop ro.product.model).Trim() -ne 'HBN-AL80' -or
   (Invoke-Phone shell getprop ro.kernel.qemu).Trim() -eq '1') { throw 'Requires the explicitly selected Huawei HBN-AL80 phone.' }
function Assert-PhoneAppForeground {
    for($phoneFocusAttempt=0;$phoneFocusAttempt -lt 10;$phoneFocusAttempt++) {
        $phoneFocus=(Invoke-Phone shell dumpsys window | Select-String 'mCurrentFocus=') -join ''
        if($phoneFocus -match 'com\.ambient\.app/') {return}
        Start-Sleep -Milliseconds 300
    }
    throw 'Ambient is not the foreground app; UI inspection stopped.'
}
function Get-PhoneUi {
    for($phoneDumpAttempt=0;$phoneDumpAttempt -lt 3;$phoneDumpAttempt++) {
        Assert-PhoneAppForeground
        try {
            $phoneDumpResult=(Invoke-Phone shell uiautomator dump /data/local/tmp/ambient-phone-ui.xml 2>&1) -join "`n"
        } catch {
            if($phoneDumpAttempt -eq 2) {throw}
            Start-Sleep -Milliseconds 500
            continue
        }
        if($phoneDumpResult -match 'dumped to:') {
            Invoke-Phone pull /data/local/tmp/ambient-phone-ui.xml (Join-Path $AmbientPhoneOutput 'current.xml') | Out-Null
            return [xml]([IO.File]::ReadAllText((Join-Path $AmbientPhoneOutput 'current.xml')))
        }
        Start-Sleep -Milliseconds 500
    }
    throw 'Phone UI dump failed; stale XML will not be used.'
}
function Get-PhoneTexts { (Get-PhoneUi).SelectNodes('//node') | ForEach-Object { if($_.text) { $_.text } } }
function Tap-Phone {
    param([string]$Text,[string]$Description)
    $phoneNode=(Get-PhoneUi).SelectNodes('//node') | Where-Object {
        if($Description) { $_.'content-desc' -eq $Description } else { $_.text -eq $Text }
    } | Select-Object -First 1
    if(-not $phoneNode -or $phoneNode.bounds -notmatch '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') { throw "Phone control missing: $Text $Description" }
    Invoke-Phone shell input tap ([int](([int]$Matches[1]+[int]$Matches[3])/2)) ([int](([int]$Matches[2]+[int]$Matches[4])/2)) | Out-Null
}
function Scroll-Phone([switch]$Up,[xml]$Ui=$null) {
    if(-not $Ui) {$Ui=Get-PhoneUi}
    $phoneViewport=$Ui.SelectNodes('//node') | Where-Object {$_.scrollable -eq 'true'} | Sort-Object {
        if($_.bounds -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') { [int]$Matches[4]-[int]$Matches[2] } else { 0 }
    } -Descending | Select-Object -First 1
    if(-not $phoneViewport -or $phoneViewport.bounds -notmatch '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') { throw 'Phone scroll viewport missing.' }
    $phoneX=[int](([int]$Matches[1]+[int]$Matches[3])/2)
    $phoneTop=[int]$Matches[2]; $phoneBottom=[int]$Matches[4]; $phoneHeight=$phoneBottom-$phoneTop
    # Short drags avoid overshooting a single row with Huawei's scroll momentum.
    $phoneA=[int]($phoneTop+$phoneHeight*.65); $phoneB=[int]($phoneTop+$phoneHeight*.45)
    if($Up) { Invoke-Phone shell input swipe $phoneX $phoneB $phoneX $phoneA 400 | Out-Null }
    else { Invoke-Phone shell input swipe $phoneX $phoneA $phoneX $phoneB 400 | Out-Null }
}
function Show-Phone([string]$Text,[switch]$Up) {
    for($phoneAttempt=0;$phoneAttempt -lt 24;$phoneAttempt++) {
        $phoneSearchUi=Get-PhoneUi
        if($phoneSearchUi.SelectNodes('//node') | Where-Object {$_.text -eq $Text}) { return }
        Scroll-Phone -Up:$Up -Ui $phoneSearchUi
    }
    throw "Phone text not found: $Text"
}
function Assert-PhoneText([string]$Text) {
    if(-not (@(Get-PhoneTexts) | Where-Object {$_ -like "*$Text*"})) { throw "Phone expected text missing: $Text" }
}
function Show-PhoneMatch([string]$Pattern,[switch]$Up) {
    for($phoneMatchAttempt=0;$phoneMatchAttempt -lt 24;$phoneMatchAttempt++) {
        $phoneMatchUi=Get-PhoneUi
        $phoneFound=$phoneMatchUi.SelectNodes('//node') | Where-Object {$_.text -match $Pattern} | Select-Object -First 1
        if($phoneFound) {return [string]$phoneFound.text}
        Scroll-Phone -Up:$Up -Ui $phoneMatchUi
    }
    throw "Phone text pattern not found: $Pattern"
}
function Save-PhoneScreen([string]$Name) {
    Assert-PhoneAppForeground
    Invoke-Phone shell screencap -p /data/local/tmp/ambient-phone-screen.png | Out-Null
    Invoke-Phone pull /data/local/tmp/ambient-phone-screen.png (Join-Path $AmbientPhoneOutput "$Name.png") | Out-Null
}
function Get-PhoneAmbientListeners {
    $phoneSensorDump=((Invoke-Phone shell dumpsys sensorservice)-join "`n") -split 'Previous Registrations:'
    ($phoneSensorDump[0] -split "`n") | Where-Object {$_ -match 'com\.ambient\.app\.'}
}
