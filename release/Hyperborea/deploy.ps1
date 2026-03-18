# deploy.ps1 — Install Hyperborea as a privileged system app on a rooted NordicTrack console.
#
# Place Hyperborea*.apk (and any additional APKs) in apps\, then run:
#   powershell -ExecutionPolicy Bypass -File deploy.ps1
#

$ErrorActionPreference = "Stop"

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Definition
$AppsDir = Join-Path $ScriptDir "apps"

$TotalSteps = 5

function Write-Ok($msg)      { Write-Host "  " -NoNewline; Write-Host "+" -ForegroundColor Green -NoNewline; Write-Host " $msg" }
function Write-Fail($msg)    { Write-Host "  " -NoNewline; Write-Host "x" -ForegroundColor Red -NoNewline; Write-Host " $msg" }
function Write-Info($msg)    { Write-Host "  " -NoNewline; Write-Host ">" -ForegroundColor Cyan -NoNewline; Write-Host " $msg" }
function Write-Warn($msg)    { Write-Host "  " -NoNewline; Write-Host "!" -ForegroundColor Yellow -NoNewline; Write-Host " $msg" }
function Write-Step($n, $msg) { Write-Host "`n[$n/$TotalSteps] $msg" -ForegroundColor Cyan }
function Stop-WithError($msg) { Write-Fail $msg; exit 1 }

function Format-Elapsed([int]$seconds) {
    return "{0}:{1:d2}" -f [math]::Floor($seconds / 60), ($seconds % 60)
}

function Wait-Key {
    while ($true) {
        $key = [Console]::ReadKey($true)
        if ($key.Key -eq 'Escape') { exit 0 }
        if ($key.Key -eq 'Enter') { return }
    }
}

function Read-Input {
    $buf = ""
    while ($true) {
        $key = [Console]::ReadKey($true)
        if ($key.Key -eq 'Escape') { exit 0 }
        if ($key.Key -eq 'Enter') { Write-Host ""; return $buf }
        if ($key.Key -eq 'Backspace') {
            if ($buf.Length -gt 0) {
                $buf = $buf.Substring(0, $buf.Length - 1)
                Write-Host -NoNewline "`b `b"
            }
            continue
        }
        if ($key.KeyChar) {
            $buf += $key.KeyChar
            Write-Host -NoNewline $key.KeyChar
        }
    }
}

$script:AllowRefresh = $false

function Select-Choice {
    param([string[]]$Options)
    $count = $Options.Count
    $cursor = 0

    $hint = "Up/Down navigate  -  Enter select  -  Esc cancel"
    if ($script:AllowRefresh) { $hint = "r refresh  -  $hint" }
    Write-Host "    $hint" -ForegroundColor DarkGray
    Write-Host ""

    $menuTop = [Console]::CursorTop

    function Draw-Choices {
        for ($i = 0; $i -lt $count; $i++) {
            Write-Host "`r" -NoNewline
            [Console]::Write("    ")
            if ($i -eq $cursor) {
                Write-Host "> " -ForegroundColor Cyan -NoNewline
            } else {
                Write-Host "  " -NoNewline
            }
            Write-Host $Options[$i]
        }
    }

    Draw-Choices

    while ($true) {
        $key = $Host.UI.RawUI.ReadKey("NoEcho,IncludeKeyDown")
        if ($key.VirtualKeyCode -eq 38) { if ($cursor -gt 0) { $cursor-- } }
        elseif ($key.VirtualKeyCode -eq 40) { if ($cursor -lt ($count - 1)) { $cursor++ } }
        elseif ($key.VirtualKeyCode -eq 82) { if ($script:AllowRefresh) { return -2 } }
        elseif ($key.VirtualKeyCode -eq 27) { Write-Host ""; exit 0 }
        elseif ($key.VirtualKeyCode -eq 13) { break }
        [Console]::SetCursorPosition(0, $menuTop)
        Draw-Choices
    }

    return $cursor
}

# =========================================================================
# Timer (background runspace with elapsed time)
# =========================================================================
$script:TimerRunspace = $null
$script:TimerPowerShell = $null

function Start-Timer($label) {
    $startTicks = (Get-Date).Ticks
    $script:TimerRunspace = [runspacefactory]::CreateRunspace()
    $script:TimerRunspace.Open()
    $script:TimerPowerShell = [powershell]::Create()
    $script:TimerPowerShell.Runspace = $script:TimerRunspace
    [void]$script:TimerPowerShell.AddScript({
        param($label, $startTicks)
        $start = [datetime]::new($startTicks)
        while ($true) {
            $elapsed = [int]((Get-Date) - $start).TotalSeconds
            $time = "{0}:{1:d2}" -f [math]::Floor($elapsed / 60), ($elapsed % 60)
            [Console]::Write("`r  > {0} {1}  " -f $label, $time)
            Start-Sleep -Seconds 1
        }
    })
    [void]$script:TimerPowerShell.AddArgument($label)
    [void]$script:TimerPowerShell.AddArgument($startTicks)
    $script:TimerPowerShell.BeginInvoke() | Out-Null
}

function Stop-Timer {
    if ($script:TimerPowerShell) {
        $script:TimerPowerShell.Stop()
        $script:TimerPowerShell.Dispose()
    }
    if ($script:TimerRunspace) {
        $script:TimerRunspace.Close()
        $script:TimerRunspace.Dispose()
    }
    $script:TimerPowerShell = $null
    $script:TimerRunspace = $null
    Write-Host "`r$(' ' * 60)" -NoNewline
    Write-Host "`r" -NoNewline
}

# =========================================================================
# Device Discovery
# =========================================================================
function Find-Device {
    while ($true) {
        Write-Step 1 "Connect to device"

        $raw = & adb devices 2>$null
        $devices = @($raw | Where-Object { $_ -match '\s+device$' })
        $serials = @()
        foreach ($d in $devices) { $serials += ($d -split '\s+')[0] }

        $options = @() + $serials
        $options += "Enter IP address..."

        Write-Host ""
        $script:AllowRefresh = $true
        $idx = Select-Choice $options
        $script:AllowRefresh = $false

        if ($idx -eq -2) { continue }

        $deviceCount = $serials.Count

        if ($idx -lt $deviceCount) {
            $env:ANDROID_SERIAL = $serials[$idx]
            Write-Host ""
            Write-Info "Connecting..."
            & adb root 2>$null | Out-Null
            & adb wait-for-device 2>$null | Out-Null
            Write-Ok "Connected to $($serials[$idx])"
            return
        }

        # Enter IP address
        Write-Host ""
        Write-Host -NoNewline "  Enter device IP (e.g. 192.168.1.100): "
        $ip = Read-Input
        if (-not $ip) { continue }
        if ($ip -notmatch ':') { $ip = "${ip}:5555" }

        Write-Info "Connecting to $ip..."
        & adb connect $ip 2>$null | Out-Null
        Start-Sleep -Seconds 2

        & adb -s $ip shell true 2>$null | Out-Null
        if ($LASTEXITCODE -eq 0) {
            $env:ANDROID_SERIAL = $ip
            & adb root 2>$null | Out-Null
            & adb wait-for-device 2>$null | Out-Null
            Write-Ok "Connected to $ip"
            return
        }
        Write-Warn "Couldn't connect. Check the IP and that ADB is enabled."
        Write-Host ""
        Write-Host "  Press Enter to try again, or Esc to exit." -ForegroundColor DarkGray
        Wait-Key
    }
}

# =========================================================================
# Wait for device to come back after reboot
# =========================================================================
function Wait-ForReboot {
    param([int]$MaxWait = 300)

    $waitStart = Get-Date

    # Block until ADB reconnects to the device
    $waitJob = Start-Job -ScriptBlock { & adb wait-for-device 2>$null }
    while ($waitJob.State -eq 'Running') {
        $elapsed = [int]((Get-Date) - $waitStart).TotalSeconds
        if ($elapsed -gt $MaxWait) {
            Stop-Job $waitJob; Remove-Job $waitJob
            Stop-Timer
            Stop-WithError "Timed out after ${MaxWait}s. Try reconnecting manually."
        }
        Start-Sleep -Seconds 1
    }
    Remove-Job $waitJob

    # Device is back, but boot may not be complete yet
    while ($true) {
        $elapsed = [int]((Get-Date) - $waitStart).TotalSeconds
        if ($elapsed -gt $MaxWait) {
            Stop-Timer
            Stop-WithError "Timed out after ${MaxWait}s waiting for boot."
        }

        $bootComplete = & adb shell "getprop sys.boot_completed" 2>$null
        if ($bootComplete -match "1") {
            try { & adb root 2>$null | Out-Null } catch {}
            & adb wait-for-device 2>$null | Out-Null
            break
        }

        Start-Sleep -Seconds 3
    }
}

# =========================================================================
# Step 1: Pre-flight
# =========================================================================
if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
    Stop-WithError "ADB not found. Install Android platform-tools and add to PATH."
}
if (-not (Test-Path $AppsDir)) {
    Stop-WithError "apps\ folder not found. Place APKs in apps\ next to this script."
}

# Find Hyperborea APK
$hyperboreaApk = Get-ChildItem -Path $AppsDir -Filter "Hyperborea*.apk" -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $hyperboreaApk) {
    Stop-WithError "No Hyperborea*.apk found in apps\. Place the APK there and try again."
}

# Collect other APKs
$otherApks = @(Get-ChildItem -Path $AppsDir -Filter "*.apk" -ErrorAction SilentlyContinue | Where-Object { $_.FullName -ne $hyperboreaApk.FullName })

Write-Ok "Found $($hyperboreaApk.Name)"
if ($otherApks.Count -gt 0) {
    Write-Ok "$($otherApks.Count) additional APK(s) to install"
}

# =========================================================================
# Step 2: Connect to device
# =========================================================================
Find-Device

$whoami = (& adb shell "whoami" 2>$null) -replace "`r",""
if ($whoami -ne "root") { Stop-WithError "Failed to get root (got: $whoami)" }
Write-Ok "Root access confirmed"

# =========================================================================
# Step 3: Install Hyperborea as priv-app
# =========================================================================
Write-Step 2 "Install Hyperborea as system app"

Write-Info "Remounting /system read-write..."
& adb shell "mount -o rw,remount /system" 2>$null | Out-Null
Write-Ok "System partition mounted read-write"

Write-Info "Pushing APK to /system/priv-app/Hyperborea/..."
& adb shell "mkdir -p /system/priv-app/Hyperborea" 2>$null | Out-Null
& adb push $hyperboreaApk.FullName /system/priv-app/Hyperborea/Hyperborea.apk 2>$null | Out-Null
Write-Ok "APK pushed"

Write-Info "Setting permissions..."
& adb shell "chmod 755 /system/priv-app/Hyperborea && chmod 644 /system/priv-app/Hyperborea/Hyperborea.apk" 2>$null | Out-Null
Write-Ok "Permissions set (755/644)"

Write-Info "Remounting /system read-only..."
& adb shell "mount -o ro,remount /system" 2>$null | Out-Null
Write-Ok "System partition mounted read-only"

# =========================================================================
# Step 4: Reboot and wait
# =========================================================================
Write-Step 3 "Reboot device"

Write-Info "Rebooting (PackageManager scans /system/priv-app/ at boot)..."
$rebootStart = Get-Date
& adb reboot 2>$null | Out-Null

Start-Timer "Waiting for device..."
Wait-ForReboot -MaxWait 300
Stop-Timer
Write-Ok "Device ready ($(Format-Elapsed ([int]((Get-Date) - $rebootStart).TotalSeconds)))"

# =========================================================================
# Step 5: Install additional apps
# =========================================================================
Write-Step 4 "Install additional apps"

if ($otherApks.Count -eq 0) {
    Write-Info "No additional APKs to install"
} else {
    $installed = 0; $failed = 0
    foreach ($apk in $otherApks) {
        $name = [System.IO.Path]::GetFileNameWithoutExtension($apk.Name)
        Write-Info "Installing $name..."
        $result = & adb install -r $apk.FullName 2>&1
        if ($result -match "Success") {
            Write-Ok $name
            $installed++
        } else {
            Write-Fail $name
            $failed++
        }
    }

    Write-Host ""
    if ($failed -eq 0) {
        Write-Ok "All $installed app(s) installed"
    } else {
        Write-Warn "$installed installed, $failed failed"
    }
}

# =========================================================================
# Step 6: Verify
# =========================================================================
Write-Step 5 "Verify"

$verify = (& adb shell @"
    echo PATH=`$(pm path com.nettarion.hyperborea 2>/dev/null)
    echo FLAGS=`$(dumpsys package com.nettarion.hyperborea 2>/dev/null | grep 'pkgFlags=' | head -1)
    echo PRIVFLAGS=`$(dumpsys package com.nettarion.hyperborea 2>/dev/null | grep 'privateFlags=' | head -1)
"@ 2>$null) -replace "`r",""

function Get-Val($key) {
    $line = ($verify -split "`n") | Where-Object { $_ -match "^$key=" } | Select-Object -First 1
    if ($line) { return ($line -replace "^$key=","") }
    return ""
}

$pass = 0; $total = 0

# Check priv-app path
$pkgPath = Get-Val "PATH"
$total++
if ($pkgPath -match "/system/priv-app/") {
    Write-Ok "Install path: $pkgPath"
    $pass++
} else {
    Write-Fail "Install path: expected /system/priv-app/, got '$pkgPath'"
}

# Check PRIVILEGED flag
$pkgPrivFlags = Get-Val "PRIVFLAGS"
$total++
if ($pkgPrivFlags -match "PRIVILEGED") {
    Write-Ok "Privileged: yes"
    $pass++
} else {
    Write-Fail "Privileged: not set (privateFlags='$pkgPrivFlags')"
}

Write-Host ""
if ($pass -eq $total) {
    Write-Host "  $pass/$total checks passed" -ForegroundColor Green
} else {
    Write-Host "  $pass/$total checks passed" -ForegroundColor Yellow
}

Write-Host ""
Write-Host "  Deployment complete!" -ForegroundColor Green
Write-Host ""
