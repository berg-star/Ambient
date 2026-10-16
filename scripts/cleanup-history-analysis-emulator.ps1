$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'history-analysis-ui-helper.ps1')
# Only removes the four explicitly named fixtures prepared by this version's UI test.
Tap-WeatherText '设置'
Show-HistoryText '清除本机凭据与天气缓存'
Tap-WeatherText '清除本机凭据与天气缓存'
Tap-WeatherText '清除'
Wait-HistoryText '本机凭据、所选区域和天气缓存已清除。'
Tap-WeatherText '返回'
Tap-WeatherText '记录'
Show-AnalysisContains '筛选记录' -Up
Show-HistoryText '4 条记录 · 按保存时间排列'
Write-Output 'PASS clearing weather credentials preserves all four historical snapshots'
foreach ($note in @('analysis-beijing-c','analysis-shanghai-b','analysis-beijing-cache','analysis-beijing-a')) {
    Show-HistoryText $note
    Tap-WeatherText $note
    Wait-HistoryText '环境快照'
    Assert-WeatherText ('备注：'+$note)
    Tap-WeatherText '删除'
    Tap-WeatherText '确认删除'
}
Wait-HistoryText '还没有环境记录'
Write-Output 'PASS only the four named UI-test records were deleted; no credentials remain on emulator'
