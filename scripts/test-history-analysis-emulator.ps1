param([switch]$DifferencesAndTrendOnly)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'history-analysis-ui-helper.ps1')

# Requires the four real regional snapshots created by prepare-history-analysis-emulator.ps1.
# Uses the dedicated guarded emulator only. Leaves records and weather credentials intact.
function Assert-AnalysisCount([int]$Expected) {
    Show-AnalysisContains '筛选记录' -Up
    Show-HistoryText "$Expected 条记录 · 按保存时间排列"
}
function Open-AnalysisComparison {
    Show-HistoryText '对比这两份快照' -Up
    Tap-WeatherText '对比这两份快照'
    Wait-HistoryText '快照对比'
}
function Clear-AnalysisComparison {
    Show-HistoryText '清空选择' -Up
    Tap-WeatherText '清空选择'
}
function Assert-AnalysisNoDifference {
    # Inspect the entire scrollable comparison, not just the currently visible rows.
    $previous = $null
    for ($attempt = 0; $attempt -lt 14; $attempt++) {
        $texts = @(Get-WeatherTexts)
        if ($texts | Where-Object { $_.Contains('计算差值') }) {
            throw 'A comparison that must only show originals displayed a calculated difference.'
        }
        $current = $texts -join "`n"
        if ($null -ne $previous -and $current -eq $previous) { return }
        $previous = $current
        Scroll-HistoryUi
    }
    throw 'Could not reach the end of the comparison while checking differences.'
}

if ($DifferencesAndTrendOnly) {
    if (@(Get-WeatherTexts) -contains '关闭对比') { Tap-WeatherText '关闭对比' }
    Clear-AnalysisComparison
} else {
Tap-WeatherText '记录'
Assert-AnalysisCount 4

# Region and original-fetch-date filters do not change the underlying four records.
Select-AnalysisArea '北京市 · 北京'
Assert-AnalysisCount 3
Select-AnalysisArea '上海市 · 上海'
Assert-AnalysisCount 1
Select-AnalysisArea '全部区域'
Assert-AnalysisCount 4
Select-AnalysisDates '今天'
Assert-AnalysisCount 4
Select-AnalysisDates '近 7 天'
Assert-AnalysisCount 4
Save-AnalysisScreen '01-region-date-filters'
Select-AnalysisDates '全部日期'
Write-Output 'PASS region and preset-date filters: 3 / 1 / 4 records'

# A repeated save of the same original fetch is not a new observation.
Select-AnalysisArea '北京市 · 北京'
Toggle-AnalysisComparison 'analysis-beijing-a'
Toggle-AnalysisComparison 'analysis-beijing-cache' -Up
Open-AnalysisComparison
Show-AnalysisContains '两份记录的数据获取时间相同'
Save-AnalysisScreen '02-same-fetch-comparison'
Assert-AnalysisNoDifference
Tap-WeatherText '关闭对比'
Clear-AnalysisComparison
Write-Output 'PASS same-fetch cached comparison has no calculated differences'

# Selections survive a region filter change; cross-region values are shown without deltas.
Toggle-AnalysisComparison 'analysis-beijing-a'
Select-AnalysisArea '上海市 · 上海'
Toggle-AnalysisComparison 'analysis-shanghai-b'
Open-AnalysisComparison
Show-AnalysisContains '不同区域仅并排展示'
Save-AnalysisScreen '03-cross-region-comparison'
Assert-AnalysisNoDifference
Tap-WeatherText '关闭对比'
Clear-AnalysisComparison
Write-Output 'PASS cross-region comparison preserves selections and shows no differences'
}

# The two genuine Beijing fetches can show B minus A, including humidity percentage points.
Select-AnalysisArea '北京市 · 北京'
Toggle-AnalysisComparison 'analysis-beijing-a'
Toggle-AnalysisComparison 'analysis-beijing-c' -Up
Open-AnalysisComparison
Show-AnalysisContains '计算差值'
Show-AnalysisContains '个百分点'
Save-AnalysisScreen '04-same-region-differences'
Tap-WeatherText '关闭对比'
Clear-AnalysisComparison
Write-Output 'PASS distinct-fetch comparison shows valid deltas and humidity percentage points'

# Optional trends require one region and merge the duplicate cached fetch into one point.
Select-AnalysisArea '全部区域'
Show-HistoryText '区域趋势 · 可选' -Up
Tap-WeatherText '区域趋势 · 可选'
Wait-HistoryText '先选择一个区域，再查看趋势。'
Save-AnalysisScreen '05-trend-area-selection'
Tap-WeatherText '北京市 · 北京'
Wait-HistoryText '2 个获取时间点'
Show-AnalysisContains '本窗口合并 1 份'
Save-AnalysisScreen '06-trend-cache-deduplication'
Show-HistoryText '上一点'
Tap-WeatherText '上一点'
Assert-WeatherText '1 / 2'
Tap-WeatherText '下一点'
Assert-WeatherText '2 / 2'

foreach ($metric in @('湿度', '海平面气压', 'AQI')) {
    Show-HistoryText $metric -Up
    Tap-WeatherText $metric
    Wait-HistoryText '2 个获取时间点'
    Show-AnalysisContains '本窗口合并 1 份'
}
Save-AnalysisScreen '07-trend-aqi'
Show-HistoryText '温度' -Up
Tap-WeatherText '温度'
Wait-HistoryText '2 个获取时间点'
Save-AnalysisScreen '08-trend-temperature'
Tap-WeatherText '关闭趋势'

# Leave a predictable state for the separate date-picker checks and explicit test-data cleanup.
Select-AnalysisArea '全部区域'
Assert-AnalysisCount 4
if ($DifferencesAndTrendOnly) {
    Write-Output 'PASS focused difference and optional-trend UI checks'
} else {
    Write-Output 'PASS full history-analysis UI checks'
}
