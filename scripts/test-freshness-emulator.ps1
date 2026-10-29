# Offline synthetic states; the helper restricts all device commands to Ambient_API_31.
. (Join-Path $PSScriptRoot 'history-ui-helper.ps1')
$ErrorActionPreference = 'Stop'
$FreshnessWifi = ((Invoke-WeatherDevice shell settings get global wifi_on) -join '').Trim()
$FreshnessData = ((Invoke-WeatherDevice shell settings get global mobile_data) -join '').Trim()
$FreshnessFolder = Join-Path $AmbientRoot '.tools\freshness-validation'
New-Item -ItemType Directory -Force -Path $FreshnessFolder | Out-Null
$FreshnessResults = [Collections.Generic.List[string]]::new()
function Set-FreshnessFixture([string]$Mode) {
    Invoke-WeatherDevice shell am force-stop com.ambient.app | Out-Null
    $output = (Invoke-WeatherDevice shell am instrument -w -r -e class com.ambient.app.ui.freshness.FreshnessUiFixture -e freshnessFixture $Mode com.ambient.app.test/androidx.test.runner.AndroidJUnitRunner) -join "`n"
    if ($output -notmatch 'OK \(1 test\)' -or $output -match 'FAILURES!!!') { throw "Fixture failed: $Mode" }
    if ($Mode -ne 'restore') {
        Invoke-WeatherDevice shell am start -W -n com.ambient.app/.MainActivity | Out-Null
        Wait-HistoryText '离线合成测试'
    }
}
function Pass-Freshness([string]$Message) {
    $FreshnessResults.Add("PASS $Message")
    [IO.File]::WriteAllLines((Join-Path $FreshnessFolder 'results.txt'), $FreshnessResults, [Text.UTF8Encoding]::new($false))
    "PASS $Message"
}
function Save-Freshness([string]$Name) {
    Invoke-WeatherDevice shell screencap -p /data/local/tmp/ambient-freshness.png | Out-Null
    Invoke-WeatherDevice pull /data/local/tmp/ambient-freshness.png (Join-Path $FreshnessFolder "$Name.png") | Out-Null
}
try {
    Invoke-WeatherDevice shell svc wifi disable | Out-Null
    Invoke-WeatherDevice shell svc data disable | Out-Null
    Set-FreshnessFixture 'recent-manual'
    Assert-WeatherText '手选区域 · 非手机定位'
    Assert-WeatherText '天气 · 本地缓存'
    Assert-WeatherText '空气质量 · 本地缓存'
    $FreshnessBefore = @(Get-WeatherTexts) | Where-Object { $_ -like '天气 ·*' }
    Save-Freshness 'recent-cache'
    Tap-WeatherText '数据时间与定位说明  ›'
    Assert-WeatherText '获取时间：'
    Assert-WeatherText '观测时间：未提供，不能由获取时间推断'
    Assert-WeatherText '读取状态：本地缓存'
    Save-Freshness 'time-details'
    Tap-WeatherText '知道了'
    Tap-WeatherText '刷新'
    Wait-HistoryText '刷新未完成 · 保留上次数据'
    $FreshnessAfter = @(Get-WeatherTexts) | Where-Object { $_ -like '天气 ·*' }
    if (($FreshnessBefore -join '') -ne ($FreshnessAfter -join '')) { throw 'Failed refresh changed the original weather time' }
    Pass-Freshness 'Recent local cache; manual area; absent observation time; failed refresh retains time'

    Set-FreshnessFixture 'old-location'
    Assert-WeatherText '上次模拟定位'
    Assert-WeatherText '天气 · 本地缓存'
    Assert-WeatherText '空气质量 · 本地缓存 · 获取较早'
    Save-Freshness 'independent-source-times'
    Tap-WeatherText '数据时间与定位说明  ›'
    Assert-WeatherText '未持续追踪'
    Tap-WeatherText '知道了'
    Show-HistoryText '近似定位高度'
    Tap-WeatherText '近似定位高度'
    Assert-WeatherText '上次定位较早'
    Assert-WeatherText '定位读数时间：'
    Assert-WeatherText '保留上次定位高度'
    Save-Freshness 'height-time'
    Tap-WeatherText '知道了'
    Pass-Freshness 'New weather cannot renew old air or old positioning height; independent location time'

    Set-FreshnessFixture 'legacy'
    Assert-WeatherText '上次模拟定位 · 时间未记录'
    Assert-WeatherText '分项时间未记录'
    Tap-WeatherText '数据时间与定位说明  ›'
    Assert-WeatherText '原缓存批次获取：'
    Assert-WeatherText '旧版仅记录整批获取时间'
    Tap-WeatherText '知道了'
    Pass-Freshness 'Legacy cache opens without fabricating per-source or positioning time'

    Set-FreshnessFixture 'missing-air'
    Assert-WeatherText '空气质量 · 暂无有效数据'
    Show-HistoryText '空气质量 · 中国'
    Tap-WeatherText '空气质量 · 中国'
    Assert-WeatherText '暂无有效数据'
    if (((Get-WeatherTexts) -join "`n") -match '获取时间：') { throw 'Missing air value borrowed a timestamp' }
    Tap-WeatherText '知道了'
    Pass-Freshness 'Unavailable air does not borrow weather time or fake a current reading'

    Set-FreshnessFixture 'future'
    Assert-WeatherText '时间异常 · 请核对设备时间'
    Save-Freshness 'future-clock'
    Pass-Freshness 'Future timestamp is flagged instead of fresh'

    Set-FreshnessFixture 'stale'
    Wait-HistoryText '刷新未完成 · 保留上次数据'
    Assert-WeatherText '获取较早 · 建议刷新'
    Save-Freshness 'old-offline'
    Show-HistoryText '环境详情  ›'
    Tap-WeatherText '环境详情  ›'
    Show-HistoryText '21.5'
    Assert-WeatherText '获取较早'
    Assert-WeatherText '获取时间：'
    Save-Freshness 'environment-details'
    Invoke-WeatherDevice shell input keyevent 4 | Out-Null
    Pass-Freshness 'Stale offline values survive; details use the same original metric time'
} finally {
    Set-FreshnessFixture 'restore'
    if ($FreshnessWifi -eq '1') { Invoke-WeatherDevice shell svc wifi enable | Out-Null }
    if ($FreshnessData -eq '1') { Invoke-WeatherDevice shell svc data enable | Out-Null }
}
