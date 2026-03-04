# Device Protection Guide

> **Runbook for protecting NordicTrack S22i from unwanted firmware updates while preserving root access.**

## How It Works

Our custom firmware handles protection automatically through four mechanisms:

**1. Intent Firewall (loaded by ActivityManagerService on every boot):**
- XML rules at `/data/system/ifw/ifit_firewall.xml` (backup copy at `/system/etc/ifit_firewall.xml`)
- **21 broadcast rules** (19 action-based + 2 component-filter) block harmful broadcasts at framework level BEFORE they're delivered to any app
- Works regardless of app UID or permissions (ERU is UID 1000/system with ~140 install permissions — IFW bypasses all of them)
- Section 1 blocks: accessibility service re-enable, third-party app kill/wipe, WiFi forget, dev mode toggle, language change, log upload, standalone bounce/reboot, Valinor migration (2 variants), demo mode kill, intentional crash, API server switch, tuner flash
- Section 2 blocks: update preview, OTA check/download, OTA install, tablet lockdown re-trigger
- Section 3 blocks: `TabletStartupReceiver` (BOOT_COMPLETED lockdown chain), `StandaloneBounceReceiver` (hourly AlarmManager reboot/restart)
- Now deployed via `ota_postinstall` step 6 (embedded content). Boot script restores from `/system/etc/` as fallback.
- Source: `tools/ifw/ifit_firewall.xml`

**2. ota_postinstall (runs once in recovery during OTA install):**
- Creates `/sdcard/.wolfDev` — triggers ERU's built-in developer mode (ADB persistence)
- Creates `/data/misc/adb/` directory — ensures ADB key storage exists
- Creates immutable `/data/update.zip` — blocks ALL ERU firmware downloads
- Pre-seeds ERU SharedPreferences with `isTabletConfigCompleteKey=true` — prevents ERU's `configureTabletAsNecessary()` from running even if TabletStartupReceiver fires on first boot (which it does — PMS resets package-restrictions after OTA fingerprint change)
- Installs Intent Firewall rules to `/data/system/ifw/` — ensures IFW is present on first boot after factory reset

**3. Modified Launcher APK (system partition):**
- iFit Launcher (`com.ifit.launcher`): `HOME` category removed from intent filter — no longer competes as home screen. Stock Launcher3 is the only HOME handler.
- Re-signed with `firmware/keys/system-mod.jks` (password: `android`).
- NOTE: ERU APK cannot be modified — it uses `sharedUserId="android.uid.system"` which requires the platform signing key we don't have. ERU is handled by the boot script instead.

**4. install-recovery.sh (safety net — runs on every boot as `flash_recovery` service):**
- Waits for `sys.boot_completed=1` + 5s, then:
- Disables ERU's `TabletStartupReceiver` component (`pm disable`) — prevents iFit boot chain on subsequent boots
- Disables ERU's `KeepTheWolfAliveService` (`pm disable`) — prevents Android from auto-restarting standalone across reboots
- Disables ERU's `EnableAccessibilityServiceReceiver` (`pm disable`) — prevents Rivendell's ~30s broadcasts from re-enabling the glassos_service AccessibilityService (which presses Back when Settings is detected)
- Disables ERU's `KillThirdPartyAppReceiver` (`pm disable`) — prevents remote force-stop of sideloaded apps
- Disables ERU's `ResetThirdPartyAppReceiver` (`pm disable`) — prevents remote data wipe of sideloaded apps
- Disables `com.ifit.launcher` (`pm disable`) — belt-and-suspenders with manifest change
- Force-stops ERU, standalone, and glassos_service — kills anything that started before disables took effect
- Deletes `enabled_accessibility_services` secure setting — disables glassos_service's AccessibilityService that kills Settings app
- Revokes `SYSTEM_ALERT_WINDOW` permission from all 7 iFit apps — prevents overlay abuse
- Re-enables 11 stock Android apps (`pm enable`) — reverses any prior ERU lockdown
- Fixes system settings ERU clobbers: `user_setup_complete=1` (enables Home button), `development_settings_enabled=1`, `adb_enabled=1`, `install_non_market_apps=1`
- Deletes `policy_control` setting — clears immersive mode

**After a normal reboot, nothing needs to be re-applied.** All protection persists automatically.

For the full research behind OTA blocking (version-specific analysis, failed strategies, decompiled source), see [OTA_UPDATES.md](../security/OTA_UPDATES.md).

## Deploying Our Firmware

Use `deploy_ota.sh` to install our rooted OTA. The script handles everything that can't be baked into the OTA itself:

```bash
./tools/deploy_ota.sh firmware/repack/MGA1_20210616/output/MGA1_20210616-rooted-signed.zip
# or for the newer firmware:
./tools/deploy_ota.sh firmware/repack/MGA1_20210901/output/MGA1_20210901-rooted-signed.zip
```

The script performs 6 steps:
1. Connect and get root
2. Push deployment tools (install_ota.dex, chattr)
3. Patch recovery signing key (always, idempotent — done entirely on-device)
4. Disable stock install-recovery.sh on current system (prevents stock recovery restore before reboot)
5. Push OTA to `/data/local/tmp/ota_install.zip` and trigger install (never touches immutable `/data/update.zip`)
6. Wait for reboot and run 13 verification checks

After the OTA installs, the firmware's `ota_postinstall` and boot script handle all protection automatically.

## After Factory Reset

There are **two types** of factory reset:

**Software reset** (iFit Settings): Wipes `/data` + `/cache` only. Boot and system partitions are unchanged. Our firmware's boot script and build.prop changes survive. Only `/data`-based protection is lost.

**Hardware reset** (hold Power during boot): Restores ALL partitions from backup (mmcblk0p9). Returns to 2017 boot + 2019 system. Must redeploy our firmware from scratch.

### After Software Reset

The device still has our firmware. Protection just needs `/data` items recreated:

```bash
adb connect 192.168.1.177:5555
adb root

# Recreate .wolfDev (before reboot!)
adb shell "touch /sdcard/.wolfDev"

# Deploy chattr and block firmware updates
adb push tools/chattr_arm64 /data/local/tmp/chattr
adb shell "chmod 755 /data/local/tmp/chattr"
adb shell "touch /data/update.zip && /data/local/tmp/chattr +i /data/update.zip"
```

### After Hardware Reset

Must redeploy the full firmware. Use `deploy_ota.sh` as described above.

## After Reboot

The immutable flag, `.wolfDev` file, and developer mode settings all persist across reboots. **Nothing needs to be re-applied.** Just reconnect:

```bash
adb connect 192.168.1.177:5555
adb root
```

## Check Protection Status

```bash
adb connect 192.168.1.177:5555
adb root

# All-in-one check (same as deploy_ota.sh verification)
adb shell "
    echo ROOT=$(whoami)
    echo SELINUX=$(getenforce)
    echo TYPE=$(getprop ro.build.type)
    echo DEBUG=$(getprop ro.debuggable)
    echo ADBSEC=$(getprop ro.adb.secure)
    echo WOLFDEV=$(test -f /sdcard/.wolfDev && echo OK || echo MISSING)
    echo OTA=$(echo x > /data/update.zip 2>&1 && echo WRITABLE || echo BLOCKED)
    echo IMMERSIVE=$(settings get global policy_control)
    echo NONMARKET=$(settings get secure install_non_market_apps)
    echo ERUPREFS=$(grep isTabletConfigCompleteKey /data/data/com.ifit.eru/shared_prefs/eru-shared-prefs.xml 2>/dev/null | grep -c true)
    echo SETUP=$(settings get secure user_setup_complete)
    echo LAUNCHER=$(pm list packages -e 2>/dev/null | grep -c com.android.launcher3)
    echo DEVSET=$(settings get global development_settings_enabled)
    echo ACCESSIBILITY=$(settings get secure enabled_accessibility_services)
    echo KILL_RX=$(pm dump com.ifit.eru 2>/dev/null | grep -c 'KillThirdPartyAppReceiver.*disabled')
    echo RESET_RX=$(pm dump com.ifit.eru 2>/dev/null | grep -c 'ResetThirdPartyAppReceiver.*disabled')
    echo A11Y_RX=$(pm dump com.ifit.eru 2>/dev/null | grep -c 'EnableAccessibilityServiceReceiver.*disabled')
"

# Expected:
#   ROOT=root
#   SELINUX=Permissive
#   TYPE=user
#   DEBUG=1
#   ADBSEC=0
#   WOLFDEV=OK
#   OTA=BLOCKED
#   IMMERSIVE=null (or empty)
#   NONMARKET=1
#   ERUPREFS=1
#   SETUP=1
#   LAUNCHER=1
#   DEVSET=1
#   ACCESSIBILITY=null (disabled)
#   KILL_RX=1 (disabled)
#   RESET_RX=1 (disabled)
#   A11Y_RX=1 (disabled)
```

## GlassOS App Interference Protection

### Settings App Killer (glassos_service AccessibilityService)

**Problem:** `glassos_service` registers an AccessibilityService (`com.ifit.glassos_appnavigation_service.service.AccessibilityServiceImpl`) that monitors every foreground window change. When it detects `com.android.settings` in the foreground, it calls `performGlobalAction(GLOBAL_ACTION_BACK)` — effectively pressing Back to dismiss Settings immediately. This makes the Settings app appear to "crash" on launch.

**Re-enable vector:** Rivendell broadcasts `com.ifit.eru.ENABLE_ACCESSIBILITY_SERVICE` to ERU every ~30 seconds. ERU's `EnableAccessibilityServiceReceiver` re-enables the accessibility service each time. The receiver skips re-enable only when `inDeveloperMode=true` or during updates.

**Defense (boot script):**
1. `settings delete secure enabled_accessibility_services` — clears the service registration
2. `am force-stop com.ifit.glassos_service` — kills the running service
3. `pm disable com.ifit.eru/.receivers.EnableAccessibilityServiceReceiver` — prevents re-enable broadcasts from working
4. `appops set com.ifit.glassos_service SYSTEM_ALERT_WINDOW deny` — revokes overlay permission (note: this alone does NOT stop `performGlobalAction()` which is a native AccessibilityService method)

### Third-Party App Management (ERU)

**Problem:** ERU v2.27.18 has three levels of aggression against third-party apps:

| Method | Action | Trigger |
|--------|--------|---------|
| `killThirdPartyApps` | Force-stop | `KILL_THIRD_PARTY` broadcast, screensaver/sleep entry |
| `clearUserDataForThirdPartyApps` | Wipe app data | `RESET_THIRD_PARTY_CACHE` broadcast, club logout |
| `uninstallThirdPartyApps` | Full uninstall | Beta opt-out flow only (user-initiated) |

**Targeting:** Only apps listed in LaunchDarkly feature flags `ifit_appstore` and `third-party-fqns` are targeted. Both default to empty, so sideloaded apps (Firefox, Aurora Store, etc.) are **NOT currently targeted**. However, iFit can remotely update these flags to add any package name.

**Current disallowed list** (UI-only, not targeted for kill/uninstall):
- `com.amazon.avod.thirdpartyclient` (Amazon Video)
- `com.netflix.mediaclient` (Netflix)
- `com.spotify.music` (Spotify)

**Defense (boot script):**
1. `pm disable com.ifit.eru/.receivers.KillThirdPartyAppReceiver` — blocks remote force-stop broadcasts
2. `pm disable com.ifit.eru/.receivers.ResetThirdPartyAppReceiver` — blocks remote data-wipe broadcasts

**Note:** `uninstallThirdPartyApps` is only triggered via the beta opt-out UI flow — there is no broadcast receiver for it, so it requires intentional user action within ERU's admin interface. The disabled receivers block the remote/automated vectors.

## Optional: Additional Layers

These are not required (the immutable block alone is sufficient, tested 2026-02-11) but can be added for defense in depth.

### Firewall (resets on reboot)

Blocks ERU (UID 1000) from reaching update servers. Standalone app can still stream workouts.

```bash
adb shell "iptables -I OUTPUT -p tcp -d api.ifit.com --dport 443 -m owner --uid-owner 1000 -j REJECT"
adb shell "iptables -I OUTPUT -p tcp -d ifit-wolf.s3-cdn.ifit.com --dport 443 -m owner --uid-owner 1000 -j REJECT"
adb shell "iptables -I OUTPUT -p tcp -d gateway.ifit.com --dport 443 -m owner --uid-owner 1000 -j REJECT"
```

### Hosts file (persists across reboots)

Blocks S3 CDN at DNS level for ALL apps. Workouts won't stream while active.

```bash
# Enable
adb shell "echo '127.0.0.1 ifit-wolf.s3-cdn.ifit.com' >> /etc/hosts"

# Disable
adb shell "sed -i '/ifit-wolf/d' /etc/hosts"
```

## Removing Protection (For Our Own OTA)

Use `deploy_ota.sh` which handles clearing immutable, pushing OTA, and re-verifying automatically:

```bash
./tools/deploy_ota.sh <path-to-ota-zip>
```

Or manually:

```bash
# Push OTA to temp location (never need to touch immutable /data/update.zip)
adb push signed-ota.zip /data/local/tmp/ota_install.zip
adb push tools/install_ota.dex /data/local/tmp/
adb shell "CLASSPATH=/data/local/tmp/install_ota.dex app_process / InstallOTA /data/local/tmp/ota_install.zip"
# Exit code 255 = expected (reboot in progress)

# After reboot, protection is re-applied automatically by ota_postinstall
```

## Recovery: If ADB Gets Disabled

With `.wolfDev` in place, this should not happen. If it does (e.g., after a factory reset before the file is recreated):

1. On the ERU screen, tap version number 7 times to access Developer Options
2. Enable USB debugging and ADB over network
3. Reconnect and recreate the dev file:

```bash
adb connect 192.168.1.177:5555
adb root
adb shell "touch /sdcard/.wolfDev"
adb shell "settings put global development_settings_enabled 1"
adb shell "settings put global adb_enabled 1"
adb shell "setprop persist.sys.usb.config adb"
adb shell "setprop service.adb.tcp.port 5555"
```

## Emergency: Disable ERU Entirely

Breaks hardware control (resistance, incline) but guarantees no updates.

```bash
adb shell "pm disable com.ifit.eru"

# To restore:
adb shell "pm enable com.ifit.eru"
```

## Dev File (.wolfDev)

ERU (v2.2.20+) checks for a specific hidden file on `/sdcard/` at every boot. The filename `.wolfDev` is obfuscated in the APK — ERU stores a SHA-1 hash of the expected path and compares it against each file in `/sdcard/`. **Only ERU uses this mechanism** — the other iFit apps (Standalone, Arda, Gandalf, Mithlond, Rivendell, GlassOS Service, Launcher) do not reference it.

When `/sdcard/.wolfDev` exists, ERU's `devFileExists()` returns true, which changes behavior in several places:

### ADB and Developer Options (TabletStartupReceiver)

On every boot, `resetDeveloperOptionsIfNeeded()` checks for the dev file. **Without it**, ERU runs three functions that lock down the device:
1. `setDeveloperOptionsDisabled()` — disables ADB and developer settings via privileged commands
2. `toggleDevMode()` — exits Android developer mode
3. `toggleDevLauncherEnabled(false)` — disables the Android system launcher (`com.android.launcher3`), locking the user into the iFit launcher

**With `.wolfDev`**, all three are skipped. ADB, developer options, and the system launcher persist across reboots.

### Log Retention (TabletStartupReceiver)

On boot, `handleLogRemoval()` normally deletes old ERU log files from `/sdcard/eru/`. **With `.wolfDev`**, log deletion is skipped — all logs are retained for debugging.

### Analytics (TabletStartupReceiver)

**With `.wolfDev`**, ERU sends a "Has_Dev_File_On_Boot" analytics event on boot. No practical impact.

### Privileged Mode Timeout (ExitPrivilegedModeReceiver)

ERU has a privileged mode that grants elevated access. Without the dev file, an idle timer kicks in: after 25 minutes of inactivity, a warning dialog appears; at 30 minutes, ERU forcibly exits privileged mode. **With `.wolfDev`**, the exit alarm is cancelled — privileged mode stays active indefinitely.

### Secret Settings (MainViewModel)

ERU has a hidden settings screen. To access it:
1. The dev file must exist
2. Tap the version number 5 times rapidly
3. Must be during first navigation (app startup)

**Without the dev file**, the secret settings button is gated behind LaunchDarkly's `hide-secret-settings` feature flag, which can remotely block access.

The screen ("iFit Admin Secret Options") contains:

| Control | Function |
|---------|----------|
| **Auto Upgrade** | Toggle automatic firmware download on/off |
| **API Selector** | Switch between Prod and Test API servers |
| **USB Update Restriction** | Toggle USB firmware update restriction |
| **Last Update Record** | View/clear the last firmware install record |
| **Preview Updates** | Check available firmware without installing |
| **Send LD Attributes** | Push device attributes to LaunchDarkly |
| **Display Feature Flags** | Show all active LaunchDarkly feature flags |
| **Run Logcat Logs** | Start/stop logcat capture (5 min auto-stop) |
| **Show Settings** | Dump all ERU LocalSettings |
| **eMMC CSD & Reporting** | Run eMMC health diagnostics |
| **User Activity Duration** | Set idle timer to 15 min (testing) |
| **Test Install App Worker** | Test the app installation worker |
| **Club Unit Toggle** | Switch between home/club mode (requires code) |
| **Reset Legal Acceptance** | Reset legal/warning acceptance timer |
| **HDMI Test** | Test HDMI sound (only if HDMI present) |
| **Use Update Fallback** | Toggle fallback update mechanism (switch) |
| **Launcher Debug** | Show launcher debug info overlay (switch) |

### Summary Table

| Component | Without .wolfDev | With .wolfDev |
|-----------|-----------------|---------------|
| **TabletStartupReceiver** | Disables ADB, developer options, and system launcher on every boot | Skips — ADB, developer options, and system launcher persist |
| **TabletStartupReceiver** | Deletes old log files on boot | Skips — logs are retained |
| **TabletStartupReceiver** | No analytics flag | Sends "Has_Dev_File_On_Boot" analytics event |
| **MainViewModel** | Secret settings gated by LaunchDarkly feature flag | Secret settings accessible (5 rapid taps on version at startup) |
| **ExitPrivilegedModeReceiver** | Privileged mode exits after 30 min idle (25 min warning) | Privileged mode stays active indefinitely |

The file itself is empty — only its name matters. It must be a direct child of `/sdcard/` (not in a subdirectory).

**Present in:** ERU v2.2.20, v2.13.9, v2.27.18 (same mechanism, same filename).
**Not present in:** ERU v1.2.1.145 (Xamarin version predates this feature).
**Version note:** In v2.2.20, `KeepTheWolfAliveService` also checked the dev file to skip crash loop detection before relaunching Wolf. This check was **removed in v2.27.18**.

## Quick Reference

| Item | Value |
|------|-------|
| **Device IP** | 192.168.1.177:5555 |
| **Current firmware** | MGA1_20210901 (rooted, deployed 2026-02-12) |
| **Available OTAs** | MGA1_20210616, MGA1_20210901 (both repacked+signed) |
| **Deploy script** | `./tools/deploy_ota.sh <ota-zip>` |
| **OTA Protection** | Immutable `/data/update.zip` (set by ota_postinstall) |
| **ADB Persistence** | `/sdcard/.wolfDev` (set by ota_postinstall) |
| **ERU Lockdown Bypass** | Pre-seeded SharedPreferences (set by ota_postinstall) |
| **Intent Firewall** | `/data/system/ifw/ifit_firewall.xml` — 21 rules block harmful broadcasts at framework level (primary defense). Deployed by ota_postinstall + boot script fallback. |
| **Boot script** | `install-recovery.sh` — safety net for direct Settings API writes, service auto-restart, AppOps, stock app re-enable |
| **Modified APK** | iFit Launcher HOME removed (signed with `system-mod.jks`). ERU APK is NOT modified (requires platform key). |
| **APK signing key** | `firmware/keys/system-mod.jks` (password: `android`) |
| **chattr binary** | `tools/chattr_arm64` (pushed by deploy_ota.sh) |

---

**Established:** 2026-02-10
**Last updated:** 2026-02-13 (IFW expanded to 21 rules, embedded in ota_postinstall, covers all ERU versions)
**Last deployed:** 2026-02-12 (MGA1_20210901, all 13 verification checks pass)
**Research:** [OTA_UPDATES.md](../security/OTA_UPDATES.md)
