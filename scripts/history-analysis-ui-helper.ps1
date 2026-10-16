. (Join-Path $PSScriptRoot 'history-ui-helper.ps1')
function Show-AnalysisContains([string]$Text,[switch]$Up) {
    for ($i=0;$i -lt 16;$i++) {
        if (@(Get-WeatherTexts) | Where-Object { $_.Contains($Text) }) { return }
        Scroll-HistoryUi -Up:$Up
    }
    throw "Analysis text missing: $Text"
}
function Select-AnalysisArea([string]$Area) {
    Show-AnalysisContains '区域：' -Up
    $label=@(Get-WeatherTexts) | Where-Object { $_.StartsWith('区域：') } | Select-Object -First 1
    Tap-WeatherText $label
    Tap-WeatherText $Area
}
function Select-AnalysisDates([string]$Preset) {
    Show-AnalysisContains '日期：' -Up
    $label=@(Get-WeatherTexts) | Where-Object { $_.StartsWith('日期：') } | Select-Object -First 1
    Tap-WeatherText $label
    Tap-WeatherText $Preset
}
function Toggle-AnalysisComparison([string]$Note,[switch]$Up) {
    for ($i=0;$i -lt 16;$i++) {
        $node=(Get-WeatherUi).SelectNodes('//node') | Where-Object {
            $_.'content-desc' -like '*对比*' -and $_.'content-desc'.EndsWith('备注：'+$Note)
        } | Select-Object -First 1
        if ($node) { Tap-HistoryDescription $node.'content-desc'; return }
        Scroll-HistoryUi -Up:$Up
    }
    throw "Comparison button missing for note: $Note"
}
function Save-AnalysisScreen([string]$Name) {
    $folder=Join-Path $AmbientRoot 'docs\screenshots\history-analysis'
    New-Item -ItemType Directory -Force -Path $folder | Out-Null
    Invoke-WeatherDevice shell screencap -p /data/local/tmp/ambient-analysis.png | Out-Null
    Invoke-WeatherDevice pull /data/local/tmp/ambient-analysis.png (Join-Path $folder ($Name+'.png')) | Out-Null
}
function New-AnalysisSnapshot([string]$Note,[string]$CityQuery,[string]$CityLabel) {
    Tap-WeatherText '总览'
    if ($CityQuery) {
        Show-HistoryText '选择城市' -Up
        Tap-WeatherText '选择城市'
        Tap-WeatherText '城市 / 区县名称'
        Invoke-WeatherDevice shell input text $CityQuery | Out-Null
        Tap-WeatherText '搜索'
        Wait-HistoryText $CityLabel
        Tap-WeatherText $CityLabel
    }
    Wait-HistoryText '室外温度'
    Show-HistoryText '保存环境快照'
    Tap-WeatherText '保存环境快照'
    Tap-WeatherText '备注（可选）'
    Invoke-WeatherDevice shell input text $Note | Out-Null
    Invoke-WeatherDevice shell input keyevent 4 | Out-Null
    Tap-WeatherText '确认保存'
    Tap-WeatherText '查看记录'
    Show-HistoryText $Note
}
