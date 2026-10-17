# Optional real-API test setup using local ignored files. Dedicated emulator only.
. (Join-Path $PSScriptRoot 'history-ui-helper.ps1')
$ErrorActionPreference='Stop'
$remote='/sdcard/Download/Ambient-history-'+[Guid]::NewGuid().ToString('N').Substring(0,8)
$folder=($remote -split '/')[-1]
Invoke-WeatherDevice shell mkdir $remote | Out-Null
try {
    Invoke-WeatherDevice push (Join-Path $AmbientRoot 'qweather.local.json') "$remote/qweather.local.json" | Out-Null
    $config=Get-Content (Join-Path $AmbientRoot 'qweather.local.json') -Raw | ConvertFrom-Json
    $keyPath=[IO.Path]::GetFullPath((Join-Path $AmbientRoot $config.privateKeyFile))
    if (-not $keyPath.StartsWith((Join-Path $AmbientRoot 'secrets\'),[StringComparison]::OrdinalIgnoreCase)) { throw 'Test key must be in this project secrets directory.' }
    Invoke-WeatherDevice push $keyPath "$remote/ed25519-private.pem" | Out-Null
    Tap-WeatherText '设置'
    $importButton = $null
    for ($attempt = 0; $attempt -lt 10; $attempt++) {
        $importButton = @(Get-WeatherTexts) | Where-Object { $_ -in @('导入配置与私钥', '重新导入配置与私钥') } | Select-Object -First 1
        if ($importButton) { break }
        Scroll-HistoryUi
    }
    if (-not $importButton) { throw 'Credential import button not found.' }
    Tap-WeatherText $importButton
    Tap-HistoryDescription 'Show roots'
    # The current location can have the same label behind the drawer; use its last (drawer) node.
    $storageNode=(Get-WeatherUi).SelectNodes('//node') | Where-Object { $_.text -eq 'Android SDK built for x86_64' } | Select-Object -Last 1
    if (-not $storageNode -or $storageNode.bounds -notmatch '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') { throw 'Storage drawer entry missing.' }
    Invoke-WeatherDevice shell input tap ([int](([int]$Matches[1]+[int]$Matches[3])/2)) ([int](([int]$Matches[2]+[int]$Matches[4])/2)) | Out-Null
    Wait-HistoryText 'Download'
    Tap-WeatherText 'Download'
    Show-HistoryText $folder
    Tap-WeatherText $folder
    Wait-HistoryText 'qweather.local.json'
    Tap-WeatherText 'qweather.local.json'
    Wait-HistoryText 'ed25519-private.pem'
    Tap-WeatherText 'ed25519-private.pem'
    Wait-HistoryText '已配置'
    Write-Output 'PASS credentials imported through normal UI and stored on the dedicated emulator'
} finally {
    if ($remote -notmatch '^/sdcard/Download/Ambient-history-[a-f0-9]{8}$') { throw 'Unexpected cleanup path.' }
    Invoke-WeatherDevice shell rm -f "$remote/qweather.local.json" "$remote/ed25519-private.pem" | Out-Null
    Invoke-WeatherDevice shell rmdir $remote | Out-Null
}
