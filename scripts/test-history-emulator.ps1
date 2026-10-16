# Requires local credentials imported with import-qweather-emulator.ps1. Uses a manually selected test city.
param([switch]$ResumeFromFirstDetail)
. (Join-Path $PSScriptRoot 'history-ui-helper.ps1')
$ErrorActionPreference='Stop'
$results=[Collections.Generic.List[string]]::new()
function History-Pass([string]$Text) { $results.Add('PASS '+$Text); $results[$results.Count-1] | Write-Output }
function Enter-HistoryNote([string]$Text) {
    Tap-WeatherText '备注（可选）'
    Invoke-WeatherDevice shell input text $Text | Out-Null
    Invoke-WeatherDevice shell input keyevent 4 | Out-Null
}
function Get-DetailFetchTime {
    @(Get-WeatherTexts) | Where-Object { $_ -like '数据获取：*' } | Select-Object -First 1
}
try {
    if (-not $ResumeFromFirstDetail) {
    if (@(Get-WeatherTexts) -contains '返回') { Tap-WeatherText '返回' }
    Tap-WeatherText '总览'
    Tap-WeatherText '选择城市'
    Tap-WeatherText '城市 / 区县名称'
    Invoke-WeatherDevice shell input text beijing | Out-Null
    Tap-WeatherText '搜索'
    Wait-HistoryText '北京市 · 北京'
    Tap-WeatherText '北京市 · 北京'
    Wait-HistoryText '室外温度'
    Show-HistoryText '保存环境快照'
    Tap-WeatherText '保存环境快照'
    Enter-HistoryNote 'walk-a'
    Tap-WeatherText '确认保存'
    Tap-WeatherText '查看记录'
    Wait-HistoryText '快照已保存'
    Wait-HistoryText '1 条记录'
    Show-HistoryText 'walk-a'
    Assert-WeatherText 'walk-a'
    Save-HistoryScreen 'first-record'
    History-Pass 'real API snapshot saves through the UI and appears in history'
    Tap-WeatherText 'walk-a'
    } else {
        # Resume only the known first test record after an interrupted UI automation connection.
        Assert-WeatherText '备注：walk-a'
        History-Pass 'real API snapshot save verified before resuming UI automation'
    }
    Wait-HistoryText '环境快照'
    $originalFetch=Get-DetailFetchTime
    if (-not $originalFetch) { throw 'Original fetch time missing.' }
    Assert-WeatherText '保存时间：'
    Assert-WeatherText '服务未提供独立观测时间'
    Save-HistoryScreen 'record-detail'
    History-Pass 'detail separates save and fetch times and retains missing observation time'
    Tap-WeatherText '备注'
    Tap-WeatherText 'walk-a'
    Invoke-WeatherDevice shell input keyevent 123 | Out-Null
    Invoke-WeatherDevice shell input keyevent 67 67 67 67 67 67 | Out-Null
    Invoke-WeatherDevice shell input text walk-updated | Out-Null
    Invoke-WeatherDevice shell input keyevent 4 | Out-Null
    Tap-WeatherText '保存备注'
    Wait-HistoryText '备注：walk-updated'
    if ((Get-DetailFetchTime) -ne $originalFetch) { throw 'Editing a note changed the fetch time.' }
    History-Pass 'note editing preserves the original environmental payload time'
    Tap-WeatherText '关闭'

    Invoke-WeatherDevice shell svc wifi disable | Out-Null
    Invoke-WeatherDevice shell svc data disable | Out-Null
    Invoke-WeatherDevice shell am force-stop com.ambient.app | Out-Null
    Invoke-WeatherDevice shell am start -W -n com.ambient.app/.MainActivity | Out-Null
    Assert-WeatherText '缓存 · 获取于'
    Show-HistoryText '保存环境快照'
    Tap-WeatherText '保存环境快照'
    Assert-WeatherText '来自缓存'
    Enter-HistoryNote 'cache-b'
    Tap-WeatherText '确认保存'
    Tap-WeatherText '查看记录'
    Wait-HistoryText '快照已保存'
    Wait-HistoryText '2 条记录'
    Show-HistoryText '缓存快照'
    Assert-WeatherText '缓存快照'
    Save-HistoryScreen 'cached-records'
    Show-HistoryText 'walk-updated'
    Assert-WeatherText 'walk-updated'
    History-Pass 'offline cached data can be saved with its cache provenance'

    Invoke-WeatherDevice shell am force-stop com.ambient.app | Out-Null
    Invoke-WeatherDevice shell am start -W -n com.ambient.app/.MainActivity | Out-Null
    Tap-WeatherText '记录'
    Wait-HistoryText '2 条记录'
    Show-HistoryText 'cache-b'
    Assert-WeatherText 'cache-b'
    Show-HistoryText 'walk-updated'
    Assert-WeatherText 'walk-updated'
    History-Pass 'records and edited notes survive process restart without network'
    Show-HistoryText 'cache-b' -Up
    Tap-WeatherText 'cache-b'
    Wait-HistoryText '环境快照'
    Assert-WeatherText '区域 API · 缓存快照'
    if ((Get-DetailFetchTime) -ne $originalFetch) { throw 'Cached save invented a newer fetch time.' }
    Save-HistoryScreen 'cached-detail'
    History-Pass 'cached record retains original fetch time rather than save time'
    Tap-WeatherText '删除'
    Tap-WeatherText '取消'
    Assert-WeatherText '备注：cache-b'
    Tap-WeatherText '关闭'
    Show-HistoryText '2 条记录 · 按保存时间排列' -Up
    Assert-WeatherText '2 条记录'
    History-Pass 'canceling deletion preserves both records'
    Show-HistoryText 'cache-b'
    Tap-WeatherText 'cache-b'
    Wait-HistoryText '环境快照'
    Tap-WeatherText '删除'
    Tap-WeatherText '确认删除'
    Wait-HistoryText '1 条记录'
    Show-HistoryText 'walk-updated'
    Assert-WeatherText 'walk-updated'
    History-Pass 'confirmed deletion removes only the selected record'

    Tap-WeatherText '设置'
    Show-HistoryText '清除本机凭据与天气缓存'
    Tap-WeatherText '清除本机凭据与天气缓存'
    Tap-WeatherText '清除'
    Wait-HistoryText '本机凭据、所选区域和天气缓存已清除。'
    Tap-WeatherText '返回'
    Show-HistoryText 'walk-updated'
    Save-HistoryScreen 'history-after-credential-clear'
    History-Pass 'clearing weather credentials and cache does not delete history'
    Tap-WeatherText 'walk-updated'
    Wait-HistoryText '环境快照'
    Tap-WeatherText '删除'
    Tap-WeatherText '确认删除'
    Wait-HistoryText '还没有环境记录'
    History-Pass 'deleting the final test record restores the empty state'
    Tap-WeatherText '去总览保存'
    Show-HistoryText '保存环境快照'
    $node=(Get-WeatherUi).SelectNodes('//node') | Where-Object { $_.text -eq '保存环境快照' } | Select-Object -First 1
    if ($node.ParentNode.enabled -ne 'false') { throw 'Demo save button was not disabled.' }
    Show-HistoryText '演示数据不能保存为正式记录。'
    Assert-WeatherText '演示数据不能保存'
    History-Pass 'demo data cannot be saved as formal history'
    [IO.File]::WriteAllLines((Join-Path $AmbientRoot '.tools\logs\history-ui-results.txt'), $results, [Text.UTF8Encoding]::new($false))
} finally {
    Invoke-WeatherDevice shell svc wifi enable | Out-Null
    Invoke-WeatherDevice shell svc data enable | Out-Null
}
