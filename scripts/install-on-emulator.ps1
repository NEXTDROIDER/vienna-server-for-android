param(
    [Parameter(Mandatory = $true)]
    [string]$ApkPath,

    [string]$AvdName = ""
)

$ErrorActionPreference = "Stop"

function Invoke-Tool {
    param(
        [Parameter(Mandatory = $true)]
        [string]$FilePath,

        [string[]]$Arguments = @(),

        [int]$TimeoutSeconds = 30,

        [switch]$IgnoreExitCode
    )

    $process = New-Object System.Diagnostics.Process
    $process.StartInfo.FileName = $FilePath
    $process.StartInfo.Arguments = ($Arguments | ForEach-Object {
        if ($_ -match '[\s"]') {
            '"' + ($_ -replace '"', '\"') + '"'
        } else {
            $_
        }
    }) -join " "
    $process.StartInfo.RedirectStandardOutput = $true
    $process.StartInfo.RedirectStandardError = $true
    $process.StartInfo.UseShellExecute = $false
    [void]$process.Start()

    if (!$process.WaitForExit($TimeoutSeconds * 1000)) {
        try { $process.Kill() } catch {}
        throw "Timed out running $FilePath $($Arguments -join ' ')"
    }

    $stdout = $process.StandardOutput.ReadToEnd()
    $stderr = $process.StandardError.ReadToEnd()
    if (!$IgnoreExitCode -and $process.ExitCode -ne 0) {
        throw "$FilePath failed with exit code $($process.ExitCode): $stderr"
    }

    return [pscustomobject]@{
        ExitCode = $process.ExitCode
        Stdout = $stdout
        Stderr = $stderr
    }
}

function Resolve-AndroidSdk {
    if ($env:ANDROID_HOME -and (Test-Path -LiteralPath $env:ANDROID_HOME)) {
        return $env:ANDROID_HOME
    }
    if ($env:ANDROID_SDK_ROOT -and (Test-Path -LiteralPath $env:ANDROID_SDK_ROOT)) {
        return $env:ANDROID_SDK_ROOT
    }
    throw "ANDROID_HOME or ANDROID_SDK_ROOT must point to an installed Android SDK."
}

function Get-OnlineDevice {
    param([string]$Adb)

    $result = Invoke-Tool -FilePath $Adb -Arguments @("devices") -TimeoutSeconds 15
    $lines = $result.Stdout -split "`r?`n" | Select-Object -Skip 1
    foreach ($line in $lines) {
        if ($line -match "^(\S+)\s+device$") {
            return $Matches[1]
        }
    }
    return $null
}

function Wait-ForBoot {
    param(
        [string]$Adb,
        [string]$Serial
    )

    $deadline = (Get-Date).AddMinutes(5)
    do {
        Start-Sleep -Seconds 2
        $result = Invoke-Tool -FilePath $Adb -Arguments @("-s", $Serial, "shell", "getprop", "sys.boot_completed") -TimeoutSeconds 10 -IgnoreExitCode
        $booted = $result.Stdout.Trim()
        if ($booted -eq "1") {
            Invoke-Tool -FilePath $Adb -Arguments @("-s", $Serial, "shell", "input", "keyevent", "82") -TimeoutSeconds 10 -IgnoreExitCode | Out-Null
            return
        }
    } while ((Get-Date) -lt $deadline)

    throw "Timed out waiting for emulator boot."
}

if (!(Test-Path -LiteralPath $ApkPath)) {
    throw "APK not found: $ApkPath"
}

$sdk = Resolve-AndroidSdk
$adb = Join-Path $sdk "platform-tools\adb.exe"
$emulator = Join-Path $sdk "emulator\emulator.exe"

if (!(Test-Path -LiteralPath $adb)) {
    throw "adb.exe not found: $adb"
}
if (!(Test-Path -LiteralPath $emulator)) {
    throw "emulator.exe not found: $emulator"
}

Invoke-Tool -FilePath $adb -Arguments @("start-server") -TimeoutSeconds 20 | Out-Null
$device = Get-OnlineDevice -Adb $adb

if (!$device) {
    $avdResult = Invoke-Tool -FilePath $emulator -Arguments @("-list-avds") -TimeoutSeconds 20
    $avds = @($avdResult.Stdout -split "`r?`n" | Where-Object { $_ -and $_.Trim().Length -gt 0 })
    if (!$AvdName) {
        $AvdName = $avds | Select-Object -First 1
    }
    if (!$AvdName) {
        throw "No Android Virtual Device found. Create one in Android Studio Device Manager, then run: .\gradlew.bat testInstallOnEmulator"
    }

    Write-Host "Starting emulator: $AvdName"
    Start-Process -FilePath $emulator -ArgumentList @("-avd", $AvdName, "-netdelay", "none", "-netspeed", "full")
    $deadline = (Get-Date).AddMinutes(3)
    do {
        Start-Sleep -Seconds 2
        $device = Get-OnlineDevice -Adb $adb
        if ($device) { break }
    } while ((Get-Date) -lt $deadline)

    if (!$device) {
        throw "Emulator started, but adb did not report an online device."
    }
    Wait-ForBoot -Adb $adb -Serial $device
} else {
    Write-Host "Using online Android device: $device"
}

Write-Host "Installing APK: $ApkPath"
$install = Invoke-Tool -FilePath $adb -Arguments @("-s", $device, "install", "-r", "-d", $ApkPath) -TimeoutSeconds 120
Write-Host $install.Stdout

Write-Host "Installed com.vienna.server.android on $device"
