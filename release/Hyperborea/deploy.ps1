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

    $previousPreference = $global:ErrorActionPreference
    try {
        $global:ErrorActionPreference = "Continue"
        & adb @Arguments
    } finally {
        $global:ErrorActionPreference = $previousPreference
    }
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
    # No `adb root` — production-build firmware (the case this script targets)
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

    # Wait for device to actually go offline
    while ($true) {
        $null = Invoke-Adb shell true 2>$null
        if ($LASTEXITCODE -ne 0) { break }
        Start-Sleep -Seconds 1
    }

    # Poll for boot completion (reconnect if IP)
    while ($true) {
        $elapsed = [int]((Get-Date) - $waitStart).TotalSeconds
        if ($elapsed -gt $MaxWait) {
            Stop-Timer
            Stop-WithError "Timed out after ${MaxWait}s. Try reconnecting manually."
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
# Configuration
# =========================================================================
# `com.ifit.launcher` is intentionally absent: leaving it enabled gives the
# device's home button somewhere to land. The launcher can't open the workout
# apps once those are disabled, but it remains as a benign navigation anchor.
$IfitPackages = @(
    "com.ifit.eru"
    "com.ifit.standalone"
    "com.ifit.arda"
    "com.ifit.glassos_service"
    "com.ifit.gandalf"
    "com.ifit.rivendell"
    "com.ifit.mithlond"
)

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

# Collect other APKs (excluding Hyperborea)
$otherApks = @(Get-ChildItem -Path $AppsDir -Filter "*.apk" -ErrorAction SilentlyContinue |
    Where-Object { $_.FullName -ne $hyperboreaApk.FullName })

Write-Ok "Found $($hyperboreaApk.Name)"

# Build wizard sections — only the additional-apps picker remains, gated
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
# `shell` user is expected and fine — the rest of the script never reaches
# for root.
Invoke-Adb shell true 2>$null | Out-Null
if ($LASTEXITCODE -ne 0) {
    Stop-WithError "ADB shell unavailable. Make sure the console allows ADB connections."
}
$whoami = (Invoke-Adb shell "whoami" 2>$null) -replace "`r",""
$deviceSdk = (Invoke-Adb shell "getprop ro.build.version.sdk" 2>$null) -replace "`r",""
$deviceRelease = (Invoke-Adb shell "getprop ro.build.version.release" 2>$null) -replace "`r",""
Write-Ok ("ADB connected (user: {0}, Android {1} / API {2})" -f
    (if ($whoami) { $whoami } else { 'shell' }),
    (if ($deviceRelease) { $deviceRelease } else { '?' }),
    (if ($deviceSdk) { $deviceSdk } else { '?' }))

# `adb install -g` (auto-grant runtime permissions) was introduced with the
# runtime-permission model in API 23. On API 22 the device's `pm` rejects -g
# and the install fails entirely. Runtime permissions don't exist on API 22
# anyway (everything is install-time auto-granted), so dropping -g is safe.
$installFlags = @("-r")
if ([int]::TryParse($deviceSdk, [ref]$null) -and ([int]$deviceSdk -ge 23)) {
    $installFlags = @("-r", "-g")
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

# =========================================================================
# Step 4: Disable iFit packages
# =========================================================================
Write-Step 4 "Disable iFit"

# `pm disable-user --user 0` works as the unprivileged shell user; it
# doesn't kill running processes (the reboot in step 5 handles that), but
# it stops them from launching again.
$disabled = 0; $already = 0; $absent = 0
$installedPkgs = Invoke-Adb shell "pm list packages" 2>$null
$disabledPkgs = Invoke-Adb shell "pm list packages -d" 2>$null
foreach ($pkg in $IfitPackages) {
    $needle = "package:$pkg"
    if (-not ($installedPkgs -match [regex]::Escape($needle))) {
        $absent++
        continue
    }
    if ($disabledPkgs -match [regex]::Escape($needle)) {
        Write-Ok "$pkg already disabled"
        $already++
        continue
    }
    $result = Invoke-Adb shell "pm disable-user --user 0 $pkg" 2>&1
    # PackageManagerShellCommand prints "Package $pkg new state: disabled-user".
    # Match the exact suffix to avoid colliding with `disable-until-used`.
    if ($result -match "new state: disabled-user") {
        Write-Ok "Disabled $pkg"
        $disabled++
    } else {
        Write-Warn "Could not disable $pkg"
    }
}
Write-Host ""
Write-Info "$disabled newly disabled, $already already disabled, $absent not present"

# =========================================================================
# Step 5: Reboot, verify, and launch
# =========================================================================
Write-Step 5 "Reboot and launch"

Write-Info "Rebooting..."
$rebootStart = Get-Date
Start-Timer "Waiting for device..."
Invoke-Adb reboot 2>$null | Out-Null
Wait-ForReboot -MaxWait 300
Stop-Timer
Write-Ok "Device back online ($(Format-Elapsed ([int]((Get-Date) - $rebootStart).TotalSeconds)))"

$pkgPath = (Invoke-Adb shell "pm path com.nettarion.hyperborea" 2>$null) -replace "`r",""
if (-not $pkgPath) {
    Stop-WithError "Hyperborea is not installed after reboot — something went wrong."
}
Write-Ok "Install verified: $pkgPath"

Write-Info "Launching Hyperborea..."
Invoke-Adb shell "am start -n com.nettarion.hyperborea/.MainActivity" 2>$null | Out-Null
Write-Ok "Hyperborea started"

Write-Host ""
Write-Host "  Deployment complete!" -ForegroundColor Green
Write-Host ""
