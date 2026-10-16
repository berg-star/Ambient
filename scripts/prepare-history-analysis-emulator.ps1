# Uses only real QWeather responses in two explicitly selected test cities, never fabricated measurements.
. (Join-Path $PSScriptRoot 'history-analysis-ui-helper.ps1')
$ErrorActionPreference='Stop'
if (@(Get-WeatherTexts) -contains '返回') { Tap-WeatherText '返回' }
Tap-WeatherText '记录'
Wait-HistoryText '还没有环境记录'
try {
    New-AnalysisSnapshot 'analysis-beijing-a' 'beijing' '北京市 · 北京'
    Invoke-WeatherDevice shell svc wifi disable | Out-Null
    Invoke-WeatherDevice shell svc data disable | Out-Null
    Invoke-WeatherDevice shell am force-stop com.ambient.app | Out-Null
    Invoke-WeatherDevice shell am start -W -n com.ambient.app/.MainActivity | Out-Null
    Assert-WeatherText '缓存 · 获取于'
    New-AnalysisSnapshot 'analysis-beijing-cache' '' ''
    Invoke-WeatherDevice shell svc wifi enable | Out-Null
    Invoke-WeatherDevice shell svc data enable | Out-Null
    New-AnalysisSnapshot 'analysis-shanghai-b' 'shanghai' '上海市 · 上海'
    New-AnalysisSnapshot 'analysis-beijing-c' 'beijing' '北京市 · 北京'
    Show-HistoryText '4 条记录 · 按保存时间排列' -Up
    'PASS four genuine test snapshots: two Beijing fetches, one cached duplicate, one Shanghai fetch'
} finally {
    Invoke-WeatherDevice shell svc wifi enable | Out-Null
    Invoke-WeatherDevice shell svc data enable | Out-Null
}
