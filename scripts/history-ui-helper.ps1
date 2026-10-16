. (Join-Path $PSScriptRoot 'weather-ui-helper.ps1')
function Scroll-HistoryUi([switch]$Up) {
    $node=(Get-WeatherUi).SelectNodes('//node') | Where-Object { $_.scrollable -eq 'true' } | Sort-Object {
        if ($_.bounds -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') { [int]$Matches[4]-[int]$Matches[2] } else { 0 }
    } -Descending | Select-Object -First 1
    if (-not $node -or $node.bounds -notmatch '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') { throw 'No scrollable viewport.' }
    $x=[int](([int]$Matches[1]+[int]$Matches[3])/2)
    $top=[int]$Matches[2]; $bottom=[int]$Matches[4]; $height=$bottom-$top
    $a=[int]($bottom-$height*.12); $b=[int]($top+$height*.20)
    if ($Up) { Invoke-WeatherDevice shell input swipe $x $b $x $a 400 | Out-Null }
    else { Invoke-WeatherDevice shell input swipe $x $a $x $b 400 | Out-Null }
}
function Show-HistoryText([string]$Text,[switch]$Up) {
    for($i=0; $i -lt 10; $i++) {
        if (@(Get-WeatherTexts) -contains $Text) { return }
        Scroll-HistoryUi -Up:$Up
    }
    throw "History control not found: $Text"
}
function Wait-HistoryText([string]$Text,[int]$Seconds=35) {
    $deadline=(Get-Date).AddSeconds($Seconds)
    do {
        if (@(Get-WeatherTexts) | Where-Object { $_ -like "*$Text*" }) { return }
        if ((Get-Date) -gt $deadline) { throw "History text did not appear: $Text" }
    } while ($true)
}
function Tap-HistoryDescription([string]$Text) {
    $node=(Get-WeatherUi).SelectNodes('//node') | Where-Object { $_.'content-desc' -eq $Text } | Select-Object -First 1
    if (-not $node -or $node.bounds -notmatch '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') { throw 'Navigation control missing.' }
    Invoke-WeatherDevice shell input tap ([int](([int]$Matches[1]+[int]$Matches[3])/2)) ([int](([int]$Matches[2]+[int]$Matches[4])/2)) | Out-Null
}
function Save-HistoryScreen([string]$Name) {
    $folder=Join-Path $AmbientRoot 'docs\screenshots\history'
    New-Item -ItemType Directory -Force -Path $folder | Out-Null
    Invoke-WeatherDevice shell screencap -p /data/local/tmp/ambient-history.png | Out-Null
    Invoke-WeatherDevice pull /data/local/tmp/ambient-history.png (Join-Path $folder "$Name.png") | Out-Null
}
