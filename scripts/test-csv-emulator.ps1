# Requires import-qweather-emulator.ps1; saves only a manually selected test city on the dedicated emulator.
param([string]$Python='python')
. (Join-Path $PSScriptRoot 'history-ui-helper.ps1')
$ErrorActionPreference='Stop'
$results=[Collections.Generic.List[string]]::new()
$csvName='Ambient-csv-test-'+[Guid]::NewGuid().ToString('N').Substring(0,8)+'.csv'
$remote='/sdcard/Download/'+$csvName
$local=Join-Path $AmbientRoot '.tools\logs\csv-ui-export.csv'
$note='csv,"verify"'
function Csv-Pass([string]$Text) { $results.Add('PASS '+$Text); Write-Output ('PASS '+$Text) }
function Csv-Node([string]$Suffix) {
    (Get-WeatherUi).SelectNodes('//node') | Where-Object {
        if ($Suffix -eq ':id/edit_filename') {
            $_.package -like '*.documentsui' -and $_.class -eq 'android.widget.EditText'
        } elseif ($Suffix -eq ':id/button_save') {
            $_.package -like '*.documentsui' -and $_.text -eq 'SAVE' -and
                ($_.'resource-id'.EndsWith(':id/button1') -or $_.'resource-id'.EndsWith(':id/button_save'))
        } else { $_.'resource-id'.EndsWith($Suffix) }
    } | Select-Object -First 1
}
function Tap-CsvNode([string]$Suffix) {
    $node=Csv-Node $Suffix
    if (-not $node -or $node.bounds -notmatch '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') { throw "CSV picker control missing: $Suffix" }
    Invoke-WeatherDevice shell input tap ([int](([int]$Matches[1]+[int]$Matches[3])/2)) ([int](([int]$Matches[2]+[int]$Matches[4])/2)) | Out-Null
}
function Wait-CsvPicker {
    $deadline=(Get-Date).AddSeconds(35)
    while (-not (Csv-Node ':id/edit_filename')) { if ((Get-Date) -gt $deadline) { throw 'System save picker did not appear.' } }
}
function Save-CsvScreen([string]$Name) {
    $folder=Join-Path $AmbientRoot 'docs\screenshots\csv'
    New-Item -ItemType Directory -Force -Path $folder | Out-Null
    Invoke-WeatherDevice shell screencap -p /data/local/tmp/ambient-csv.png | Out-Null
    Invoke-WeatherDevice pull /data/local/tmp/ambient-csv.png (Join-Path $folder ($Name+'.png')) | Out-Null
}
$auto=((Invoke-WeatherDevice shell settings get system accelerometer_rotation)-join '').Trim()
$rotation=((Invoke-WeatherDevice shell settings get system user_rotation)-join '').Trim()
try {
    if (@(Get-WeatherTexts) -contains '返回') { Tap-WeatherText '返回' }
    Tap-WeatherText '记录'
    Wait-HistoryText '还没有环境记录'
    $button=(Get-WeatherUi).SelectNodes('//node') | Where-Object { $_.text -eq '导出 CSV' } | Select-Object -First 1
    if (-not $button -or $button.ParentNode.enabled -ne 'false') { throw 'Empty-history CSV export was enabled.' }
    Csv-Pass 'empty history disables export without creating a file'
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
    Tap-WeatherText '备注（可选）'
    Invoke-WeatherDevice shell input text 'csv,\"verify\"' | Out-Null
    Assert-WeatherText $note
    Invoke-WeatherDevice shell input keyevent 4 | Out-Null
    Tap-WeatherText '确认保存'
    Tap-WeatherText '查看记录'
    Wait-HistoryText '1 条记录'
    Show-HistoryText $note
    Tap-WeatherText $note
    $fetch=@(Get-WeatherTexts) | Where-Object { $_ -like '数据获取：*' } | Select-Object -First 1
    if (-not $fetch) { throw 'Original fetch time missing.' }
    Tap-WeatherText '关闭'
    Show-HistoryText '导出 CSV' -Up
    Save-CsvScreen 'export-ready'
    Tap-WeatherText '导出 CSV'
    Wait-CsvPicker
    Invoke-WeatherDevice shell settings put system accelerometer_rotation 0 | Out-Null
    Invoke-WeatherDevice shell settings put system user_rotation 1 | Out-Null
    Wait-CsvPicker
    for ($i=0; $i -lt 4; $i++) {
        $doc=Get-WeatherUi
        if ($doc.SelectNodes('//node') | Where-Object { $_.package -eq 'com.ambient.app' }) { break }
        Invoke-WeatherDevice shell input keyevent 4 | Out-Null
    }
    Wait-HistoryText '已取消导出'
    Invoke-WeatherDevice shell settings put system user_rotation 0 | Out-Null
    Show-HistoryText '1 条记录 · 按保存时间排列'
    Csv-Pass 'rotation during the system picker returns one cancellation and preserves the source record'

    Invoke-WeatherDevice shell svc wifi disable | Out-Null
    Invoke-WeatherDevice shell svc data disable | Out-Null
    Invoke-WeatherDevice shell am force-stop com.ambient.app | Out-Null
    Invoke-WeatherDevice shell am start -W -n com.ambient.app/.MainActivity | Out-Null
    Tap-WeatherText '记录'
    Wait-HistoryText '1 条记录'
    Show-HistoryText '导出 CSV' -Up
    Tap-WeatherText '导出 CSV'
    Wait-CsvPicker
    Tap-HistoryDescription 'Show roots'
    Tap-WeatherText 'Android SDK built for x86_64'
    Tap-WeatherText 'Download'
    Tap-CsvNode ':id/edit_filename'
    $oldName=(Csv-Node ':id/edit_filename').text
    Invoke-WeatherDevice shell input keyevent 123 | Out-Null
    $backspaces=@(67)*($oldName.Length+2)
    Invoke-WeatherDevice shell input keyevent @backspaces | Out-Null
    Invoke-WeatherDevice shell input text $csvName | Out-Null
    Invoke-WeatherDevice shell input keyevent 4 | Out-Null
    if ((Csv-Node ':id/edit_filename').text -ne $csvName) { throw 'CSV filename entry failed.' }
    Save-CsvScreen 'system-save'
    Tap-CsvNode ':id/button_save'
    Wait-HistoryText '已导出 1 份快照'
    Save-CsvScreen 'export-success'
    Invoke-WeatherDevice pull $remote $local | Out-Null
    & $Python (Join-Path $PSScriptRoot 'verify-csv-export.py') $local
    if ($LASTEXITCODE -ne 0) { throw 'Independent CSV parse failed.' }
    Csv-Pass 'offline export creates a real UTF-8 CSV through the system document provider'
    Csv-Pass 'independent parser verifies 19 columns, quoted note, Chinese, units, times, provenance and missing altitude'
    Show-HistoryText $note
    Tap-WeatherText $note
    $after=@(Get-WeatherTexts) | Where-Object { $_ -like '数据获取：*' } | Select-Object -First 1
    if ($fetch -ne $after) { throw 'CSV export altered the original fetch time.' }
    Assert-WeatherText ('备注：'+$note)
    Tap-WeatherText '关闭'
    $cache=@(Invoke-WeatherDevice shell run-as com.ambient.app ls cache/history-exports)
    if ($cache | Where-Object { $_ -match '\.csv\.tmp$' }) { throw 'Finished export left private temporary files.' }
    Csv-Pass 'export preserves the source record and cleans its private temporary CSV'
    Tap-WeatherText '设置'
    Show-HistoryText '清除本机凭据与天气缓存'
    Tap-WeatherText '清除本机凭据与天气缓存'
    Tap-WeatherText '清除'
    Wait-HistoryText '本机凭据、所选区域和天气缓存已清除。'
    Tap-WeatherText '返回'
    Show-HistoryText $note
    Tap-WeatherText $note
    Tap-WeatherText '删除'
    Tap-WeatherText '确认删除'
    Wait-HistoryText '还没有环境记录'
    Save-CsvScreen 'empty-export-disabled'
    Csv-Pass 'test record and credentials clean up through ordinary UI without changing other devices'
    [IO.File]::WriteAllLines((Join-Path $AmbientRoot '.tools\logs\csv-ui-results.txt'),$results,[Text.UTF8Encoding]::new($false))
} finally {
    Invoke-WeatherDevice shell svc wifi enable | Out-Null
    Invoke-WeatherDevice shell svc data enable | Out-Null
    foreach ($entry in @(@('accelerometer_rotation',$auto),@('user_rotation',$rotation))) {
        if ($entry[1] -eq 'null') { Invoke-WeatherDevice shell settings delete system $entry[0] | Out-Null }
        else { Invoke-WeatherDevice shell settings put system $entry[0] $entry[1] | Out-Null }
    }
    if ($remote -notmatch '^/sdcard/Download/Ambient-csv-test-[a-f0-9]{8}\.csv$') { throw 'Unexpected CSV cleanup path.' }
    Invoke-WeatherDevice shell rm -f $remote | Out-Null
}
