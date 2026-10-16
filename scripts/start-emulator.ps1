param()

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'environment.ps1')

$AmbientAvdName = 'Ambient_API_31'
$AmbientSerial = 'emulator-5560'
$AmbientEmulator = Join-Path $AmbientSdk 'emulator\emulator.exe'
$AmbientAdb = Join-Path $AmbientSdk 'platform-tools\adb.exe'
$AmbientImage = Join-Path $AmbientSdk 'system-images\android-31\default\x86_64'
$AmbientAvdPath = Join-Path $env:ANDROID_AVD_HOME "$AmbientAvdName.avd"
$AmbientLogPath = Join-Path $AmbientRoot '.tools\logs'
$AmbientProcess = $null

foreach ($AmbientRequired in @($AmbientEmulator, $AmbientAdb, (Join-Path $AmbientImage 'system.img'))) {
    if (-not (Test-Path -LiteralPath $AmbientRequired)) {
        throw "Missing emulator tool or API 31 x86_64 system image: $AmbientRequired"
    }
}

New-Item -ItemType Directory -Force -Path $AmbientLogPath | Out-Null
# An exclusive local handle prevents two invocations creating or starting the same AVD.
try {
    $AmbientLock = [IO.File]::Open((Join-Path $AmbientLogPath 'emulator-start.lock'), 'OpenOrCreate', 'ReadWrite', 'None')
} catch {
    throw 'Another Ambient emulator startup is already in progress.'
}

try {
    & $AmbientAdb start-server | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Could not start the Android device bridge.' }
    $AmbientDevices = @(& $AmbientAdb devices)
    $AmbientExisting = @($AmbientDevices | Where-Object { $_ -match "^$AmbientSerial\s+" })
    if ($AmbientExisting.Count -gt 0) {
        if ($AmbientExisting[0] -notmatch "^$AmbientSerial\s+device\s*$") {
            throw "$AmbientSerial is already present but not ready. No device was changed."
        }
        $AmbientRunningName = (& $AmbientAdb -s $AmbientSerial emu avd name | Select-Object -First 1).Trim()
        if ($AmbientRunningName -ne $AmbientAvdName) {
            throw "Port 5560 belongs to a different virtual device ($AmbientRunningName). No device was changed."
        }
        Write-Host "Already running: $AmbientSerial ($AmbientAvdName)"
        return
    }

    # Refuse to reuse occupied console or ADB ports, even if ADB has not detected a device yet.
    foreach ($AmbientPort in @(5560, 5561)) {
        $AmbientSocket = [Net.Sockets.TcpClient]::new()
        try {
            $AmbientConnection = $AmbientSocket.ConnectAsync('127.0.0.1', $AmbientPort)
            if ($AmbientConnection.Wait(400) -and $AmbientSocket.Connected) {
                throw "Port $AmbientPort is occupied. No existing device was changed."
            }
        } catch [AggregateException] {
            # Connection refused means the port is free.
        } finally {
            $AmbientSocket.Dispose()
        }
    }

    if (-not (Test-Path -LiteralPath (Join-Path $AmbientAvdPath 'config.ini'))) {
        New-Item -ItemType Directory -Force -Path $AmbientAvdPath | Out-Null
        $AmbientAvdConfig = @(
            'avd.ini.encoding=UTF-8',
            "avd.id=$AmbientAvdName",
            "avd.name=$AmbientAvdName",
            'PlayStore.enabled=false',
            'abi.type=x86_64',
            'tag.id=default',
            'tag.display=Default Android System Image',
            ('image.sysdir.1=' + $AmbientImage.Replace('\', '/') + '/'),
            'hw.cpu.arch=x86_64',
            'hw.cpu.ncore=2',
            'hw.ramSize=2048',
            'vm.heapSize=256',
            'hw.lcd.width=720',
            'hw.lcd.height=1280',
            'hw.lcd.density=320',
            'hw.initialOrientation=portrait',
            'hw.mainKeys=no',
            'hw.keyboard=no',
            'hw.gpu.enabled=yes',
            'hw.gpu.mode=software',
            'hw.audioInput=no',
            'hw.audioOutput=no',
            'hw.camera.front=none',
            'hw.camera.back=none',
            'hw.gps=yes',
            'hw.accelerometer=yes',
            'hw.gyroscope=yes',
            'hw.sensors.light=yes',
            'hw.sensors.magnetic_field=yes',
            'hw.sensors.orientation=yes',
            'hw.sensors.pressure=yes',
            'hw.sensors.proximity=yes',
            'hw.sensors.humidity=yes',
            'hw.sensors.temperature=yes',
            'disk.dataPartition.size=2147483648',
            'disk.cachePartition=yes',
            'disk.cachePartition.size=66MB',
            'showDeviceFrame=no',
            'fastboot.forceColdBoot=yes',
            'fastboot.forceFastBoot=no'
        )
        [IO.File]::WriteAllLines((Join-Path $AmbientAvdPath 'config.ini'), $AmbientAvdConfig, [Text.UTF8Encoding]::new($false))
    }
    $AmbientAvdIndex = @('avd.ini.encoding=UTF-8', "path=$AmbientAvdPath", 'target=android-31')
    [IO.File]::WriteAllLines((Join-Path $env:ANDROID_AVD_HOME "$AmbientAvdName.ini"), $AmbientAvdIndex, [Text.UTF8Encoding]::new($false))

    $AmbientArguments = @('-avd', $AmbientAvdName, '-port', '5560', '-no-window', '-gpu', 'software', '-no-audio', '-no-boot-anim', '-no-snapshot')
    $AmbientProcess = Start-Process -FilePath $AmbientEmulator -ArgumentList $AmbientArguments -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $AmbientLogPath 'emulator-stdout.log') -RedirectStandardError (Join-Path $AmbientLogPath 'emulator-stderr.log')
    Write-Host "Starting $AmbientAvdName (PID $($AmbientProcess.Id)); logs: $AmbientLogPath"

    $AmbientDeadline = (Get-Date).AddMinutes(3)
    do {
        if ($AmbientProcess.HasExited) { throw 'Emulator exited. Check .tools/logs/emulator-stderr.log.' }
        $AmbientState = @(& $AmbientAdb devices) -join "`n"
        if ($AmbientState -match "(?m)^$AmbientSerial\s+device\s*$") {
            $AmbientBoot = (& $AmbientAdb -s $AmbientSerial shell getprop sys.boot_completed 2>$null)
            if ($AmbientBoot -eq '1') {
                $AmbientRunningName = (& $AmbientAdb -s $AmbientSerial emu avd name | Select-Object -First 1).Trim()
                if ($AmbientRunningName -ne $AmbientAvdName) { throw 'Unexpected AVD identity; no device settings were changed.' }
                # Emulator 37 / API 31 has a known Bluetooth-service crash; Ambient does not use Bluetooth.
                & $AmbientAdb -s $AmbientSerial shell pm disable-user --user 0 com.android.bluetooth
                if ($LASTEXITCODE -ne 0) { throw 'Could not disable the incompatible Bluetooth service in this dedicated test AVD.' }
                & $AmbientAdb -s $AmbientSerial shell am force-stop com.android.bluetooth
                & $AmbientAdb -s $AmbientSerial shell input keyevent 82
                Write-Host "Ready: $AmbientSerial ($AmbientAvdName), API 31, 720x1280, 320 dpi"
                return
            }
        }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $AmbientDeadline)
    throw 'Emulator boot timed out after 3 minutes. Check .tools/logs/emulator-stderr.log. The emulator was left running.'
} finally {
    $AmbientLock.Dispose()
}
