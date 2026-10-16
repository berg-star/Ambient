# Focused visual check of the wind indicator. Dedicated emulator only; no credentials required.
. (Join-Path $PSScriptRoot 'weather-ui-helper.ps1')
$ErrorActionPreference = 'Stop'
$folder = Join-Path $AmbientRoot 'docs\screenshots\wind'
New-Item -ItemType Directory -Force -Path $folder | Out-Null
$results = [Collections.Generic.List[string]]::new()
function Save-WindScreen([string]$name) {
    Invoke-WeatherDevice shell screencap -p /data/local/tmp/ambient-wind.png | Out-Null
    Invoke-WeatherDevice pull /data/local/tmp/ambient-wind.png (Join-Path $folder "$name.png") | Out-Null
}
function Show-WindIndicator {
    for ($i=0; $i -lt 8; $i++) {
        $doc = Get-WeatherUi
        if (-not @($doc.SelectNodes('//node') | Where-Object { $_.package -eq 'com.ambient.app' }).Count) {
            throw 'Ambient left the foreground while checking wind layout.'
        }
        if (@($doc.SelectNodes('//node') | Where-Object { $_.'content-desc' -like '*风从东北吹来*' }).Count) { return }
        # 950 remains inside the content viewport above the bottom navigation bar.
        Invoke-WeatherDevice shell input swipe 360 950 360 360 450 | Out-Null
    }
    throw 'Wind indicator is not visible.'
}
Invoke-WeatherDevice install -r (Join-Path $AmbientRoot 'app\build\outputs\apk\debug\app-debug.apk') | Out-Null
Invoke-WeatherDevice shell am force-stop com.ambient.app | Out-Null
Invoke-WeatherDevice shell am start -W -n com.ambient.app/.MainActivity | Out-Null
$styles = @(
    @{Name='日光琥珀'; Slug='amber'},
    @{Name='雾蓝暮色'; Slug='dusk'},
    @{Name='流光玻璃'; Slug='glass'}
)
foreach ($style in $styles) {
    foreach ($mode in @(@{Name='浅色'; Slug='light'}, @{Name='深色'; Slug='dark'})) {
        Tap-WeatherText '设置'
        Tap-WeatherText $style.Name
        Tap-WeatherText $mode.Name
        Tap-WeatherText '返回'
        Show-WindIndicator
        Assert-WeatherText '来自'
        Assert-WeatherText '演示数据'
        Save-WindScreen ($style.Slug+'-'+$mode.Slug)
        $results.Add('PASS '+$style.Slug+'/'+$mode.Slug+' north-up incoming-wind indicator with demo provenance')
        $results[$results.Count-1] | Write-Output
    }
}
Tap-WeatherText '风向'
Assert-WeatherText '亮点'
Assert-WeatherText '不随手机转动'
Save-WindScreen 'detail'
$results.Add('PASS enlarged compass and physical explanation in metric detail')
$results[$results.Count-1] | Write-Output
Tap-WeatherText '知道了'
$pidText=((Invoke-WeatherDevice shell pidof com.ambient.app) -join '').Trim()
if ((Invoke-WeatherDevice logcat -d "--pid=$pidText" -s AndroidRuntime:E) -match 'FATAL EXCEPTION') { throw 'Ambient runtime crash detected.' }
[IO.File]::WriteAllLines((Join-Path $AmbientRoot '.tools\logs\wind-test-results.txt'), $results, [Text.UTF8Encoding]::new($false))
