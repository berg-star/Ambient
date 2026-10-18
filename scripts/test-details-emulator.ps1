# Offline weather fixtures are opt-in androidTest-only and restored in finally.
. (Join-Path $PSScriptRoot 'history-ui-helper.ps1')
$ErrorActionPreference='Stop'
$DetailsWifi=((Invoke-WeatherDevice shell settings get global wifi_on)-join '').Trim()
$DetailsData=((Invoke-WeatherDevice shell settings get global mobile_data)-join '').Trim()
$DetailsResults=[Collections.Generic.List[string]]::new()
$DetailsFolder=Join-Path $AmbientRoot 'docs\screenshots\environment-details'
New-Item -ItemType Directory -Force -Path $DetailsFolder | Out-Null
function Set-DetailsFixture([string]$Mode) {
    Invoke-WeatherDevice shell am force-stop com.ambient.app | Out-Null
    $output=(Invoke-WeatherDevice shell am instrument -w -r -e class com.ambient.app.ui.sky.SkyUiFixture -e skyFixture $Mode com.ambient.app.test/androidx.test.runner.AndroidJUnitRunner)-join "`n"
    if ($output -notmatch 'OK \(1 test\)' -or $output -match 'FAILURES!!!') { throw 'Details fixture failed' }
}
function Open-Details {
    Invoke-WeatherDevice shell am start -W -n com.ambient.app/.MainActivity | Out-Null
    Show-HistoryText '环境详情  ›'
    $detailsHomeText=(Get-WeatherTexts)-join "`n"
    if ($detailsHomeText -match '体感温度|露点温度|能见度|阵风风速|云量') { throw 'Detail-only fields leaked onto overview' }
    Tap-WeatherText '环境详情  ›'
    Wait-HistoryText '体感温度'
}
function Pass-Details([string]$Message) {
    $DetailsResults.Add("PASS $Message")
    [IO.File]::WriteAllLines((Join-Path $AmbientRoot '.tools\logs\details-ui-results.txt'),$DetailsResults,[Text.UTF8Encoding]::new($false))
    "PASS $Message"
}
function Save-Details([string]$Name) {
    Invoke-WeatherDevice shell screencap -p /data/local/tmp/ambient-details.png | Out-Null
    Invoke-WeatherDevice pull /data/local/tmp/ambient-details.png (Join-Path $DetailsFolder "$Name.png") | Out-Null
}
try {
    Invoke-WeatherDevice shell svc wifi disable | Out-Null
    Invoke-WeatherDevice shell svc data disable | Out-Null
    Set-DetailsFixture 'details-values'
    Open-Details
    Assert-WeatherText '区域 API · 和风天气 · 非手机实测'
    Assert-WeatherText '22.4'
    Assert-WeatherText '缓存 · 获取于'
    Save-Details 'glass-top'
    Show-HistoryText '-2.3'
    Show-HistoryText '12.34'
    Assert-WeatherText 'km'
    Show-HistoryText '42'
    Show-HistoryText '7.1'
    Assert-WeatherText 'm/s'
    Save-Details 'glass-lower'
    Pass-Details 'Five API fields and units render offline; new values stay off overview'
    foreach ($DetailsTheme in @('日光琥珀','雾蓝暮色','流光玻璃')) {
        Tap-WeatherText '设置'
        Tap-WeatherText $DetailsTheme
        Tap-WeatherText '返回'
        Assert-WeatherText '体感温度'
        Save-Details $DetailsTheme
        Pass-Details "Theme $DetailsTheme returns to details"
    }
    Set-DetailsFixture 'details-zero'
    Open-Details
    Assert-WeatherText '0.0'
    Show-HistoryText '云量'
    Show-HistoryText '0'
    Pass-Details 'Real API zeros remain numeric values rather than missing'
    Set-DetailsFixture 'details-missing'
    Open-Details
    Assert-WeatherText '暂无有效数据'
    if (((Get-WeatherTexts)-join "`n") -match '22\.4') { throw 'Missing value reused earlier fixture' }
    Pass-Details 'Missing fields remain unavailable without estimation or cross-snapshot reuse'
    Set-DetailsFixture 'details-legacy'
    Open-Details
    Assert-WeatherText '此缓存未包含部分详情指标'
    Assert-WeatherText '暂无有效数据'
    Pass-Details 'Old cache opens with placeholders and refresh explanation'
    Set-DetailsFixture 'details-old'
    Open-Details
    Assert-WeatherText '较早数据或设备时间异常'
    Assert-WeatherText '22.4'
    Pass-Details 'Offline failure retains original cached values with old-data warning'
    Set-DetailsFixture 'details-no-weather'
    Open-Details
    Assert-WeatherText '暂无区域天气数据'
    Pass-Details 'No weather shows a recoverable empty state'
    Set-DetailsFixture 'details-demo'
    Open-Details
    Assert-WeatherText '演示模式 · 这五项未生成示例数值'
    Assert-WeatherText '暂无有效数据'
    Pass-Details 'Demo is explicit and does not fabricate additional measurements'
    Invoke-WeatherDevice shell input keyevent 3 | Out-Null
    $active=(((Invoke-WeatherDevice shell dumpsys sensorservice)-join "`n") -split 'Previous Registrations:')[0]
    if ($active -match 'com\.ambient\.app\.data\.(sensor|orientation)\.') { throw 'Details registered phone sensors' }
    Invoke-WeatherDevice shell am start -W -n com.ambient.app/.MainActivity | Out-Null
    Assert-WeatherText '体感温度'
    Invoke-WeatherDevice shell input keyevent 4 | Out-Null
    Assert-WeatherText '区域环境'
    Pass-Details 'Background, resume, Back and sensor lifecycle remain correct'
} finally {
    Set-DetailsFixture 'restore'
    if ($DetailsWifi -eq '1') { Invoke-WeatherDevice shell svc wifi enable | Out-Null }
    if ($DetailsData -eq '1') { Invoke-WeatherDevice shell svc data enable | Out-Null }
}
