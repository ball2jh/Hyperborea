# deploy.ps1 - Install Hyperborea on a NordicTrack/iFit console over ADB. No root required.
#
# Hyperborea is installed as a regular APK via `adb install`; iFit's competing
# apps are silenced with `adb shell pm disable-user --user 0 ...`, which works as
# the unprivileged `shell` user on any console with ADB enabled.
#
# Privileged install to /system/priv-app/ (the old flow) lives on the
# `archive/priv-app-deployment` branch if you need to revive it.
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
function Assert-LastExitCode($msg) {
    if ($LASTEXITCODE -ne 0) { Stop-WithError $msg }
}
function Invoke-Adb {
    param([Parameter(ValueFromRemainingArguments = $true)][object[]]$Arguments)

    # adb routinely writes diagnostics to stderr ("daemon not running; starting it
    # now...", "su: not found", a stale-transport message after a reboot, ...). With
    # the script-level $ErrorActionPreference = 'Stop' that would promote the first
    # such line to a *terminating* error -- which `2>$null` at the call sites can't
    # swallow (it only redirects non-terminating errors). A *function-local*
    # $ErrorActionPreference is the only thing that actually shadows the script-scoped
    # one here (assigning $global: doesn't -- the script scope wins the lookup).
    # Every caller already routes adb's stderr (2>$null or 2>&1) where it wants it.
    $ErrorActionPreference = 'Continue'
    & adb @Arguments
}

function Format-Elapsed([int]$seconds) {
    return "{0}:{1:d2}" -f [int][math]::Floor($seconds / 60), [int]($seconds % 60)
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
        if ($key.KeyChar -ge ' ') {
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
        [Console]::SetCursorPosition(0, $menuTop)
        for ($i = 0; $i -lt $count; $i++) {
            Write-Host "    " -NoNewline
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
        Draw-Choices
    }

    return $cursor
}

# =========================================================================
# Wizard-style multi-step configuration
# =========================================================================
function Select-WizardConfig {
    $numSections = $script:WizSections.Count
    $currentStep = 0
    $width = [Console]::WindowWidth

    function Clear-Line {
        $row = [Console]::CursorTop
        [Console]::SetCursorPosition(0, $row)
        [Console]::Write(" " * [Math]::Max(0, $width - 1))
        [Console]::SetCursorPosition(0, $row)
    }

    while ($currentStep -lt $numSections) {
        $secStart = $script:WizSecStart[$currentStep]
        $secCount = $script:WizSecCount[$currentStep]
        $isDone = $script:WizSections[$currentStep] -eq "Done"
        $cursor = 0

        # Build summary for Done step
        $summaryItems = @()
        if ($isDone) {
            for ($s = 0; $s -lt $numSections - 1; $s++) {
                $summaryItems += [PSCustomObject]@{ Type = 'header'; Text = $script:WizSections[$s]; State = 0 }
                for ($j = 0; $j -lt $script:WizSecCount[$s]; $j++) {
                    $idx = $script:WizSecStart[$s] + $j
                    $summaryItems += [PSCustomObject]@{ Type = 'item'; Text = $script:WizLabels[$idx]; State = $script:WizStates[$idx] }
                }
            }
        }

        $contentLines = if ($isDone) { $summaryItems.Count + 2 } else { $secCount + 2 }
        $totalLines = $contentLines + 4
        $wizTop = [Console]::CursorTop

        function Draw-TabBar {
            [Console]::SetCursorPosition(0, $wizTop)
            Clear-Line
            Write-Host "  <-" -NoNewline
            for ($s = 0; $s -lt $numSections; $s++) {
                Write-Host "  " -NoNewline
                if ($s -lt $currentStep) {
                    Write-Host "+" -ForegroundColor Green -NoNewline
                    Write-Host " $($script:WizSections[$s])" -NoNewline
                } elseif ($s -eq $currentStep) {
                    Write-Host "*" -ForegroundColor Cyan -NoNewline
                    Write-Host " $($script:WizSections[$s])" -NoNewline
                } else {
                    Write-Host "o $($script:WizSections[$s])" -ForegroundColor DarkGray -NoNewline
                }
            }
            Write-Host "  ->"
        }

        function Draw-Content {
            [Console]::SetCursorPosition(0, $wizTop + 4)
            if ($isDone) {
                foreach ($item in $summaryItems) {
                    Clear-Line
                    if ($item.Type -eq 'header') {
                        Write-Host "    $($item.Text)"
                    } elseif ($item.State -eq 1) {
                        Write-Host "      " -NoNewline
                        Write-Host "+" -ForegroundColor Green -NoNewline
                        Write-Host " $($item.Text)"
                    } else {
                        Write-Host "      x $($item.Text)" -ForegroundColor DarkGray
                    }
                }
            } else {
                for ($i = 0; $i -lt $secCount; $i++) {
                    Clear-Line
                    $idx = $secStart + $i
                    Write-Host "    " -NoNewline
                    if ($i -eq $cursor) {
                        Write-Host "> " -ForegroundColor Cyan -NoNewline
                    } else {
                        Write-Host "  " -NoNewline
                    }
                    if ($script:WizStates[$idx] -eq 1) {
                        Write-Host "+" -ForegroundColor Green -NoNewline
                        Write-Host " $($script:WizLabels[$idx])"
                    } else {
                        Write-Host "x $($script:WizLabels[$idx])" -ForegroundColor DarkGray
                    }
                }
            }
            # Blank line
            Clear-Line
            Write-Host ""
            # Action button
            Clear-Line
            $btnLabel = if ($isDone) { "Confirm" } else { "Continue ->" }
            Write-Host "    " -NoNewline
            if ($cursor -eq $secCount) {
                Write-Host "> " -ForegroundColor Cyan -NoNewline
            } else {
                Write-Host "  " -NoNewline
            }
            Write-Host "$btnLabel"
        }

        function Draw-Wizard {
            Draw-TabBar
            [Console]::SetCursorPosition(0, $wizTop + 1)
            Clear-Line
            Write-Host ""
            Clear-Line
            if ($isDone) {
                Write-Host "    Enter confirm  -  Esc cancel" -ForegroundColor DarkGray
            } else {
                Write-Host "    Up/Down navigate  -  Space toggle  -  Enter continue  -  Esc cancel" -ForegroundColor DarkGray
            }
            Clear-Line
            Write-Host ""
            Draw-Content
        }

        Draw-Wizard

        while ($true) {
            $key = $Host.UI.RawUI.ReadKey("NoEcho,IncludeKeyDown")
            if ($key.VirtualKeyCode -eq 27) { exit 0 }            # Escape
            if ($key.VirtualKeyCode -eq 13) { break }             # Enter
            if ($key.VirtualKeyCode -eq 38) {                      # Up
                if ($cursor -gt 0) { $cursor-- }
            }
            elseif ($key.VirtualKeyCode -eq 40) {                  # Down
                if ($cursor -lt $secCount) { $cursor++ }
            }
            elseif ($key.VirtualKeyCode -eq 32) {                  # Space
                if ($cursor -eq $secCount) {
                    break                                           # Continue/Confirm
                } elseif ($secCount -gt 0) {
                    $idx = $secStart + $cursor
                    $script:WizStates[$idx] = if ($script:WizStates[$idx] -eq 1) { 0 } else { 1 }
                }
            }
            if ($contentLines -gt 0) {
                Draw-Content
            }
        }

        # Clear wizard area for next step
        [Console]::SetCursorPosition(0, $wizTop)
        for ($l = 0; $l -lt $totalLines; $l++) {
            Clear-Line
            Write-Host ""
        }
        [Console]::SetCursorPosition(0, $wizTop)

        $currentStep++
    }
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
# Helpers
# =========================================================================
function Test-IpConnection {
    return $env:ANDROID_SERIAL -and $env:ANDROID_SERIAL -match ':'
}

function Invoke-AdbReconnectWait {
    # No `adb root` -- production-build firmware (the case this script targets)
    # rejects it. Just reconnect (if IP) and wait for the device.
    if (Test-IpConnection) {
        Start-Sleep -Seconds 2
        Invoke-Adb connect $env:ANDROID_SERIAL 2>$null | Out-Null
        Start-Sleep -Seconds 1
    }
    Invoke-Adb wait-for-device 2>$null | Out-Null
}

# =========================================================================
# Device Discovery
# =========================================================================
function Find-Device {
    while ($true) {
        Write-Step 1 "Connect to device"

        $raw = Invoke-Adb devices 2>$null
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
            Invoke-AdbReconnectWait
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
        Invoke-Adb connect $ip 2>$null | Out-Null
        Start-Sleep -Seconds 2

        Invoke-Adb -s $ip shell true 2>$null | Out-Null
        if ($LASTEXITCODE -eq 0) {
            $env:ANDROID_SERIAL = $ip
            Invoke-AdbReconnectWait
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

    # Let the device actually start shutting down before we look for it again --
    # otherwise getprop reads a stale boot_completed=1 from the system that
    # hasn't begun rebooting yet.
    if (Test-IpConnection) {
        # adb doesn't notice a TCP transport died when the console reboots; it
        # clings to the dead socket and blocks on it for the full socket timeout
        # (minutes). Drop the stale transport explicitly so the poll loop's
        # `adb connect` reattaches as soon as adbd is back.
        Start-Sleep -Seconds 5
        Invoke-Adb disconnect $env:ANDROID_SERIAL 2>$null | Out-Null
    } else {
        # USB: the device falls off the bus on reboot, so this probe fails
        # promptly -- just wait for that.
        while ($true) {
            $null = Invoke-Adb shell true 2>$null
            if ($LASTEXITCODE -ne 0) { break }
            Start-Sleep -Seconds 1
        }
    }

    # Poll for boot completion (reconnect if IP)
    while ($true) {
        $elapsed = [int]((Get-Date) - $waitStart).TotalSeconds
        if ($elapsed -gt $MaxWait) {
            Stop-Timer
            Write-Warn "Timed out after ${MaxWait}s waiting for the device to come back."
            Write-Warn "The device likely rebooted fine but ADB didn't return. Some iFit firmware"
            Write-Warn "disables USB debugging on every boot (a live ERU does this) -- if the ADB"
            Write-Warn "indicator is red, re-enable USB debugging in Settings > Developer options."
            if (Test-IpConnection) {
                Write-Warn "Over WiFi the console may also have come back on a different IP."
            }
            Stop-WithError "Reconnect ADB and re-run this script -- it will verify and finish from where it left off."
        }

        if (Test-IpConnection) {
            Invoke-Adb connect $env:ANDROID_SERIAL 2>$null | Out-Null
            Start-Sleep -Seconds 2
        }

        $bootComplete = Invoke-Adb shell "getprop sys.boot_completed" 2>$null
        if ($bootComplete -match "1") {
            Invoke-AdbReconnectWait
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

# The BLE-peripheral overlay is handled separately (step 3): on these consoles
# it only takes effect when pushed to /vendor/overlay/ with root -- adb-installing
# it to /data/app/ does nothing (no OverlayManagerService until API 26). So keep
# it out of the regular "additional apps" picker.
$overlayApk = Join-Path $AppsDir "BluetoothPeripheralOverlay.apk"
$skipOverlay = ($env:HYPERBOREA_SKIP_OVERLAY -eq '1')

# Collect other APKs (excluding Hyperborea and the overlay)
$otherApks = @(Get-ChildItem -Path $AppsDir -Filter "*.apk" -ErrorAction SilentlyContinue |
    Where-Object { $_.FullName -ne $hyperboreaApk.FullName -and $_.FullName -ne $overlayApk })

Write-Ok "Found $($hyperboreaApk.Name)"

# Build wizard sections -- only the additional-apps picker remains, gated
# on the apps\ directory containing anything beyond Hyperborea.
$script:WizSections = @()
$script:WizLabels = @()
$script:WizStates = @()
$script:WizSecStart = @()
$script:WizSecCount = @()

if ($otherApks.Count -gt 0) {
    $script:WizSections += "Additional apps"
    $script:WizSecStart += 0
    $appCount = 0
    foreach ($apk in $otherApks) {
        $script:WizLabels += [System.IO.Path]::GetFileNameWithoutExtension($apk.Name)
        $script:WizStates += 1
        $appCount++
    }
    $script:WizSecCount += $appCount

    $script:WizSections += "Done"
    $script:WizSecStart += $script:WizLabels.Count
    $script:WizSecCount += 0
}

# =========================================================================
# Step 1: Connect to device
# =========================================================================
Find-Device

# Sanity check that we have a working ADB shell. Running as the unprivileged
# `shell` user is expected and fine -- the rest of the script never reaches
# for root.
Invoke-Adb shell true 2>$null | Out-Null
if ($LASTEXITCODE -ne 0) {
    Stop-WithError "ADB shell unavailable. Make sure the console allows ADB connections."
}
$whoami = (Invoke-Adb shell "whoami" 2>$null) -replace "`r",""
$deviceSdk = (Invoke-Adb shell "getprop ro.build.version.sdk" 2>$null) -replace "`r",""
$deviceRelease = (Invoke-Adb shell "getprop ro.build.version.release" 2>$null) -replace "`r",""
$userStr = if ($whoami) { $whoami } else { 'shell' }
$releaseStr = if ($deviceRelease) { $deviceRelease } else { '?' }
$sdkStr = if ($deviceSdk) { $deviceSdk } else { '?' }
Write-Ok ("ADB connected (user: {0}, Android {1} / API {2})" -f $userStr, $releaseStr, $sdkStr)

# `adb install -g` (auto-grant runtime permissions) was introduced with the
# runtime-permission model in API 23. On API 22 the device's `pm` rejects -g
# and the install fails entirely. Runtime permissions don't exist on API 22
# anyway (everything is install-time auto-granted), so dropping -g is safe.
$installFlags = @("-r")
if ([int]::TryParse($deviceSdk, [ref]$null) -and ([int]$deviceSdk -ge 23)) {
    $installFlags = @("-r", "-g")
}

# Detect whether the console exposes root -- needed only to install the
# BLE-peripheral overlay (step 3). Factory S22i firmware has root ADB; MGA1 and
# newer firmware does not (ro.debuggable=0). Try a `su` binary first, then a
# root adbd; record which so step 3 knows whether to wrap commands in `su -c`.
$hasRoot = $false
$script:rootViaSu = $false
$idOut = (Invoke-Adb shell "su -c id" 2>$null) -replace "`r",""
if ($idOut -match 'uid=0') {
    $hasRoot = $true; $script:rootViaSu = $true
} elseif ($whoami -eq 'root') {
    $hasRoot = $true
} else {
    Invoke-Adb root 2>$null | Out-Null
    Invoke-AdbReconnectWait
    if (((Invoke-Adb shell "whoami" 2>$null) -replace "`r","") -eq 'root') { $hasRoot = $true }
}

# Run a shell command on the device with root (via `su -c` if root came from a
# su binary; plain otherwise, since the adbd itself is root). Only call when
# $hasRoot is $true.
function Invoke-RootShell([string]$cmd) {
    if ($script:rootViaSu) { Invoke-Adb shell "su -c '$cmd'" } else { Invoke-Adb shell $cmd }
}

# =========================================================================
# Step 2: Configure
# =========================================================================
Write-Step 2 "Configure"

$appInstallStates = @()
if ($script:WizSections.Count -gt 0) {
    Write-Host ""
    Select-WizardConfig
    Clear-Host
    Write-Ok "Configuration saved"
    for ($i = 0; $i -lt $otherApks.Count; $i++) {
        $appInstallStates += $script:WizStates[$i]
    }
} else {
    Write-Info "No optional configuration; continuing"
}

# =========================================================================
# Step 3: Install Hyperborea (and any selected additional APKs)
# =========================================================================
Write-Step 3 "Install Hyperborea"

Write-Info "Installing $($hyperboreaApk.Name)..."
# $installFlags is "-r" or "-r -g"; -g is added when the device is API 23+.
$result = Invoke-Adb install @installFlags $hyperboreaApk.FullName 2>&1
if ($result -match "Success") {
    Write-Ok "Hyperborea installed"
} else {
    Stop-WithError "adb install of Hyperborea failed: $($result | Select-Object -Last 1)"
}

$installed = 0; $failed = 0
for ($i = 0; $i -lt $otherApks.Count; $i++) {
    if ($appInstallStates[$i] -ne 1) { continue }
    $apk = $otherApks[$i]
    $name = [System.IO.Path]::GetFileNameWithoutExtension($apk.Name)
    Write-Info "Installing $name..."
    $result = Invoke-Adb install @installFlags $apk.FullName 2>&1
    if ($result -match "Success") {
        Write-Ok $name
        $installed++
    } else {
        Write-Fail $name
        $failed++
    }
}
if ($installed -gt 0 -or $failed -gt 0) {
    Write-Host ""
    if ($failed -eq 0) {
        Write-Ok "$installed additional app(s) installed"
    } else {
        Write-Warn "$installed installed, $failed failed"
    }
}

# BLE FTMS needs config_bluetooth_le_peripheral_mode_supported=true, which the
# stock framework-res sets to false. Push the RRO overlay to /vendor/overlay/
# (a static overlay; idmap'd at boot -- the reboot in step 5 activates it). This
# needs root: factory S22i firmware has it, MGA1 firmware doesn't. Without root
# BLE FTMS just isn't available -- the WiFi broadcast still works.
if ((Test-Path $overlayApk) -and -not $skipOverlay) {
    if ($hasRoot) {
        Write-Host ""
        Write-Info "Enabling BLE peripheral mode (vendor overlay)..."
        Invoke-RootShell "mount -o rw,remount /system" 2>$null | Out-Null
        if ($LASTEXITCODE -ne 0) { Invoke-Adb remount 2>$null | Out-Null }
        Invoke-RootShell "mkdir -p /vendor/overlay" 2>$null | Out-Null
        Invoke-Adb push $overlayApk /vendor/overlay/BluetoothPeripheralOverlay.apk 2>$null | Out-Null
        $lsOut = (Invoke-Adb shell "ls /vendor/overlay/BluetoothPeripheralOverlay.apk" 2>$null) -replace "`r",""
        if ($lsOut -match "BluetoothPeripheralOverlay.apk") {
            Invoke-RootShell "chmod 644 /vendor/overlay/BluetoothPeripheralOverlay.apk" 2>$null | Out-Null
            Write-Ok "BLE peripheral overlay installed (takes effect after the reboot in step 5)"
        } else {
            Write-Warn "Couldn't write the overlay to /vendor/overlay/ -- BLE FTMS won't be available (WiFi broadcast still works)."
        }
        Invoke-RootShell "mount -o ro,remount /system" 2>$null | Out-Null
        $pkgOut = (Invoke-Adb shell "pm list packages com.nettarion.hyperborea.overlay.bluetooth" 2>$null) -replace "`r",""
        if ($pkgOut -match "com.nettarion.hyperborea.overlay.bluetooth") {
            Invoke-Adb uninstall com.nettarion.hyperborea.overlay.bluetooth 2>$null | Out-Null
        }
    } else {
        Write-Host ""
        Write-Warn "No root ADB on this console -- BLE FTMS won't be available (WiFi broadcast still works)."
        Write-Info "Enabling BLE FTMS needs root or a firmware mod; see overlay/README.md."
    }
}

# =========================================================================
# Step 4: Disable iFit packages
# =========================================================================
Write-Step 4 "Disable iFit"

# Enumerate every iFit package actually present rather than hardcoding a list --
# firmware revisions ship different sets of GlassOS service packages, and a
# fixed list silently misses whatever a later update adds.
#
# ALL of them get disabled, com.ifit.launcher included. Leaving the launcher
# enabled doesn't work: it has RECEIVE_BOOT_COMPLETED and on every boot it
# re-enables com.ifit.eru and com.ifit.standalone (ERU then disables the
# launcher, launches standalone, and resumes pushing firmware updates that wipe
# the device). With the launcher disabled too, nothing iFit fires on boot and
# the disables stick. Hyperborea registers itself as the home activity (step 5),
# and com.android.launcher3 -- if present -- remains as the fallback.
#
# `pm disable-user --user 0` works as the unprivileged shell user; it doesn't
# kill running processes (the reboot in step 5 handles that), but it stops them
# from launching again. We always *attempt* the disable rather than trusting a
# pre-check: a stale or failed "already hidden" reading once skipped the real
# disable and left ERU live, and a live ERU re-enables its siblings and disables
# ADB on every boot. A package ERU previously hard-disabled (state "disabled"
# rather than "disabled-user") still shows under `pm list packages -d` and is
# counted as already-disabled, avoiding the SecurityException disable-user throws
# on those.
$ifitPackages = @(
    (Invoke-Adb shell "pm list packages com.ifit" 2>$null) -replace "`r","" |
        ForEach-Object { $_ -replace '^package:','' } |
        Where-Object { $_ -match '^com\.ifit\.' } |
        Sort-Object
)

# A package is "neutralised" if it's either disabled (shows under `pm list packages -d`) or hidden
# (vanishes from the plain package list). pm disable-user is preferred, but some firmware refuses it
# from an unprivileged shell (system-uid com.ifit.eru, and even user-installed com.ifit.standalone);
# pm hide needs MANAGE_USERS, which the shell user lacks on some builds (Android 13 refuses it
# outright); where it's accepted it has the same end effect, so we keep it as a fallback.
function Test-PkgDisabled($pkg) {
    $d = (Invoke-Adb shell "pm list packages -d com.ifit" 2>$null) -replace "`r",""
    return ($d -contains "package:$pkg")
}

# Only trust a "hidden" verdict when the query actually returned packages -- an empty result means
# the adb call failed, NOT that the package is hidden. That false positive previously skipped the
# real disable and left iFit live.
function Test-PkgHidden($pkg) {
    $p = (Invoke-Adb shell "pm list packages com.ifit" 2>$null) -replace "`r",""
    if (-not $p) { return $false }
    return (-not ($p -contains "package:$pkg"))
}

if ($ifitPackages.Count -eq 0) {
    Write-Warn "No com.ifit.* packages found on the device -- nothing to disable."
} else {
    $disabled = 0; $hidden = 0; $already = 0; $failed = 0
    foreach ($pkg in $ifitPackages) {
        # Skip ONLY when genuinely already disabled -- re-running disable-user on a hard-"disabled"
        # system package throws SecurityException. We deliberately do not skip on Test-PkgHidden:
        # always attempt the disable so a stale/false "hidden" reading can't leave the package live.
        if (Test-PkgDisabled $pkg) {
            Write-Ok "$pkg already disabled"
            $already++
            continue
        }
        # PackageManagerShellCommand prints "Package $pkg new state: disabled-user"
        # on success; on a system pkg already in the hard-"disabled" state it
        # throws a SecurityException instead -- re-check before declaring failure.
        $result = Invoke-Adb shell "pm disable-user --user 0 $pkg" 2>&1
        if ($result -match "new state: disabled-user") {
            Write-Ok "Disabled $pkg"
            $disabled++
        } elseif (Test-PkgDisabled $pkg) {
            Write-Ok "$pkg already disabled"
            $already++
        } else {
            # disable-user refused (SecurityException) -- fall back to the less-privileged pm hide.
            $hideResult = Invoke-Adb shell "pm hide $pkg" 2>&1
            if (($hideResult -match "new hidden state: true") -or (Test-PkgHidden $pkg)) {
                Write-Ok "Hid $pkg (pm disable-user refused)"
                $hidden++
            } else {
                Write-Warn "Could not disable or hide $pkg"
                $failed++
            }
        }
    }
    Write-Host ""
    $summary = "$disabled newly disabled, $hidden hidden, $already already disabled/hidden"
    if ($failed -eq 0) {
        Write-Info "$summary ($($ifitPackages.Count) iFit package(s) found)"
    } else {
        Write-Warn "$summary, $failed could not be disabled or hidden ($($ifitPackages.Count) iFit package(s) found)"
    }
}

# =========================================================================
# Step 5: Reboot, verify, and launch
# =========================================================================
Write-Step 5 "Reboot and launch"

# PackageManager debounces its write of package-restrictions.xml by ~10 s; the
# `pm disable-user` calls above only update in-memory state until that timer
# fires. Reboot inside that window and the disables are lost -- iFit comes back
# on the next boot and re-grabs the USB device. Wait it out, then sync, then
# reboot. (~15 s here is dwarfed by the reboot wait that follows.)
Write-Info "Flushing package state..."
Start-Sleep -Seconds 15
Invoke-Adb shell sync 2>$null | Out-Null

# Make ADB-over-WiFi survive the reboot. `adb tcpip` mode is transient and wiped
# on reboot, so without this the reconnect loop can never re-establish the TCP
# transport and the user has to re-pair manually. persist.adb.tcp.port makes adbd
# re-listen on the same port every boot. Harmless if the property is read-only for
# the shell user (the write just fails silently).
if (Test-IpConnection) {
    $port = ($env:ANDROID_SERIAL -split ':')[-1]
    if (-not $port) { $port = "5555" }
    Invoke-Adb shell "setprop persist.adb.tcp.port $port" 2>$null | Out-Null
}

Write-Info "Rebooting..."
$rebootStart = Get-Date
Start-Timer "Waiting for device..."
Invoke-Adb reboot 2>$null | Out-Null
Wait-ForReboot -MaxWait 300
Stop-Timer
Write-Ok "Device back online ($(Format-Elapsed ([int]((Get-Date) - $rebootStart).TotalSeconds)))"

$pkgPath = (Invoke-Adb shell "pm path com.nettarion.hyperborea" 2>$null) -replace "`r",""
if (-not $pkgPath) {
    Stop-WithError "Hyperborea is not installed after reboot -- something went wrong."
}
Write-Ok "Install verified: $pkgPath"

# Re-verify iFit stayed disabled across the reboot. The disable in step 4 only
# "took" if it survived the boot: a live ERU (com.ifit.eru) re-enables its
# siblings and rewrites settings -- including disabling ADB -- on every boot, so an
# iFit package that came back means the deploy hasn't actually held. This is the
# check that distinguishes "looked successful" from "is successful".
if ($ifitPackages.Count -gt 0) {
    $disabledNow = (Invoke-Adb shell "pm list packages -d com.ifit" 2>$null) -replace "`r",""
    $plainNow    = (Invoke-Adb shell "pm list packages com.ifit" 2>$null) -replace "`r",""
    $back = @()
    foreach ($pkg in $ifitPackages) {
        # Neutralised = disabled (in -d list) OR hidden (gone from a non-empty plain list).
        if ($disabledNow -contains "package:$pkg") { continue }
        if ($plainNow -and (-not ($plainNow -contains "package:$pkg"))) { continue }
        $back += $pkg
    }
    if ($back.Count -eq 0) {
        Write-Ok "iFit stayed disabled after reboot"
    } else {
        Write-Warn "iFit re-enabled itself after reboot: $($back -join ', ')"
        if ($back -contains "com.ifit.eru") {
            Write-Warn "com.ifit.eru is the cause -- while it runs it re-enables the others and disables ADB each boot."
        }
        Write-Warn "The deploy has NOT fully taken on this console. Re-run this script; if it keeps coming"
        Write-Warn "back, this firmware needs root or a firmware-route deploy to neutralise ERU."
    }
}

# Every com.ifit.* package is disabled now, so the stock iFit home screen is
# gone. Hand the HOME intent to the device's own AOSP launcher
# (com.android.launcher3) -- that's what the user lands on after a plain reboot.
# Hyperborea isn't the home app; it keeps running headless as a foreground
# service (BootReceiver restarts it on boot) and is reachable from the launcher's
# app list. Only if the console ships no other launcher do we fall back to making
# Hyperborea itself the home activity, so it's never left with no home screen.
# `cmd package set-home-activity`/`query-activities` need `cmd` (API 24+); on
# older shells both fail and the system shows its home chooser on first Home press.
function Set-HomeActivity($component) {
    return ((Invoke-Adb shell "cmd package set-home-activity $component" 2>&1) -match "(?i)success")
}

# First HOME-category activity that isn't iFit's, isn't Hyperborea, and isn't one
# of the bare system fallbacks (settings FallbackHome / internal SystemUserHome).
$homeTarget = (Invoke-Adb shell "cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME" 2>$null) -replace "`r","" |
    Select-String -Pattern '[A-Za-z0-9_.]+/[A-Za-z0-9_.]+' -AllMatches |
    ForEach-Object { $_.Matches.Value } |
    Where-Object { $_ -notmatch '^(com\.ifit\.|com\.nettarion\.hyperborea/|com\.android\.settings/|android/)' } |
    Select-Object -First 1

if ($homeTarget -and (Set-HomeActivity $homeTarget)) {
    Write-Ok "Home screen: $homeTarget"
    Write-Info "Hyperborea runs in the background and auto-starts on boot -- open it from the launcher to see the dashboard."
} elseif (Set-HomeActivity "com.nettarion.hyperborea/.MainActivity") {
    Write-Warn "No other launcher on this console -- set Hyperborea as the home screen"
} else {
    Write-Warn "Couldn't set a home screen -- pick one from the home chooser on the device"
}

Write-Info "Launching Hyperborea..."
Invoke-Adb shell "am start -n com.nettarion.hyperborea/.MainActivity" 2>$null | Out-Null
Write-Ok "Hyperborea started"

Write-Host ""
Write-Host "  Deployment complete!" -ForegroundColor Green
Write-Host ""
