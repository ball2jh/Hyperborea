# Firmware & Deployment: Installing Hyperborea as a System App

> **Goal:** Deploy Hyperborea as an uninstallable system app on the NordicTrack S22i with exclusive USB control, protected from all iFit interference.

## Current State

| Item | Status |
|------|--------|
| Custom firmware (MGA1_20210901) | Deployed 2026-02-12, 25 install attempts documented |
| Root access | `adb root` works, SELinux permissive, `ro.debuggable=1` |
| OTA protection | Triple-layered: IFW (21 rules) + immutable `/data/update.zip` + boot script |
| iFit lockdown | Boot script disables key receivers/services, IFW blocks 21 broadcasts |
| Hyperborea system install | **TODO** |
| USB exclusive access | **TODO** |
| Simplified deploy process | **TODO** |

---

## 1. Device & Firmware Essentials

### Hardware

| Spec | Value |
|------|-------|
| SoC | Nexell S5P6818, 4x Cortex-A53 @ 1.4GHz |
| RAM | 2GB DDR3 |
| Storage | 8GB eMMC (system: 2GB, data: 3GB, cache: 573MB) |
| Display | 1920x1080 landscape, 22", 160 DPI |
| Android | 7.1.2 (API 25), userdebug with dev-keys |
| SELinux | Permissive (kernel cmdline) |
| System mount | Read-write by default (no remount needed) |

### Partition Layout

| Partition | Device | Mount | Size |
|-----------|--------|-------|------|
| Boot | mmcblk0p1 | /boot | 64MB |
| System | mmcblk0p2 | /system | 2GB (~1.1GB free) |
| Cache | mmcblk0p5 | /cache | 573MB |
| Recovery | mmcblk0p6 | /recovery | 64MB |
| Misc | mmcblk0p7 | /misc | 1MB |
| Backup | mmcblk0p9 | — | 1.5GB (factory restore) |
| Data | mmcblk0p10 | /data | 3GB |

### Serial Ports

| Port | Purpose |
|------|---------|
| `/dev/ttySAC0` | UART debug console (U-Boot + kernel, 115200) |
| `/dev/ttySAC1` | Bluetooth UART |
| `/dev/ttySAC3` | Factory test serial |
| `/dev/ttySAC4` | Factory test serial |

**FitPro USB:** The bike's motor controller connects via USB (VID 0x0403 / PID 0x6001 — FTDI chip), not a built-in UART. It appears as a USB device node, accessed via Android's `UsbManager` API.

---

## 2. Proven Firmware Modification Process

### What the Custom Firmware Does

The OTA modifies two things: the **boot.img ramdisk** (4 property changes + kernel cmdline) and **system partition files** (build.prop, otacerts, launcher, boot script, IFW rules).

**Ramdisk patches** (via `tools/binpatch_ramdisk.py` — binary patch, never extract+rebuild):

| Property | Stock | Patched |
|----------|-------|---------|
| `ro.secure` | 1 | 0 |
| `ro.adb.secure` | 1 | 0 |
| `ro.debuggable` | 0 | 1 |
| `persist.sys.usb.config` | none | adb |

**Kernel cmdline:** `buildvariant=userdebug androidboot.selinux=permissive`

**System changes:**
- `build.prop`: `persist.sys.usb.config=adb`, `ro.adb.secure=0` (keep `ro.build.type=user` for stealth)
- `otacerts.zip`: Replaced with dummy (blocks unsigned OTAs)
- iFit Launcher APK: HOME intent removed, re-signed with `system-mod.jks`
- `install-recovery.sh`: Replaced with boot script (component disables, settings fixes)
- `ifit_firewall.xml`: Copied to `/system/etc/` as IFW backup
- `updater-script`: Creates `.wolfDev` during install, mounts/creates on `/data`

### Stealth Design

Apps see a stock device: `Build.TYPE=user`, no `su` binary, stock fingerprint. Root comes from ramdisk properties and kernel cmdline — no app checks these.

### Critical Rules (from 25 attempts)

| Rule | Why |
|------|-----|
| Use `tools/binpatch_ramdisk.py` | Only method that preserves cpio inode structure (300000+). Never extract+rebuild. |
| Pad with `\n` not `\0` | Null byte kills Android init's property parser. |
| Use Python gzip, not macOS gzip | macOS gzip produces different output → wrong boot.img size → hang. |
| `buildvariant=userdebug` in cmdline | Required for `ALLOW_ADBD_NO_AUTH=1`. |
| Keep `ro.build.type=user` in build.prop | Stealth — apps see stock. |
| No `su` binary | Root detection returns false. Use `adb root` instead. |
| `.wolfDev` on `/sdcard/` | Prevents ERU from disabling ADB/dev mode on boot. |
| Strip comments from recovery `/res/keys` | `load_keys()` fails if first char isn't `v`. |
| Patch recovery on-device, not Mac | Avoids macOS cpio bugs. |
| `repack_ota.py`, not `zip -r` | Must preserve original zip entry structure. |

### Deploy Procedure (current)

```bash
./tools/deploy_ota.sh firmware/repack/MGA1_20210901/output/MGA1_20210901-rooted-signed.zip
```

Steps:
1. Connect + root
2. Push tools (install_ota.dex, chattr)
3. Patch recovery signing key (on-device)
4. Disable stock install-recovery.sh
5. Push OTA to `/data/local/tmp/ota_install.zip`, trigger via `RecoverySystem.installPackage()`
6. Wait for reboot, run 13 verification checks

### Key Files

| File | Purpose |
|------|---------|
| `tools/binpatch_ramdisk.py` | Binary-patches ramdisk (7 bytes) |
| `tools/mkbootimg/` | Pack/unpack boot.img |
| `tools/repack_ota_MGA1_20210901.py` | Rebuild OTA with modified files |
| `tools/sign_ota_minimal_pkcs7.py` | Sign OTA |
| `tools/install_ota.dex` | Calls `RecoverySystem.installPackage()` |
| `tools/chattr_arm64` | Sets immutable flag |
| `tools/deploy_ota.sh` | End-to-end deploy script |
| `firmware/keys/ota_signing.pk8` | OTA signing key (RSA 2048, DER) |
| `firmware/keys/ota_signing.x509.pem` | OTA signing cert |
| `firmware/keys/otacerts_dummy.zip` | Blocks unsigned OTAs |
| `firmware/keys/recovery_res_keys_stripped` | Our key for recovery (mincrypt v2, no comment) |
| `firmware/keys/system-mod.jks` | APK signing key (password: `android`) |

---

## 3. Protection Layers (What Keeps iFit at Bay)

### Layer 1: Intent Firewall (Primary)

Located at `/data/system/ifw/ifit_firewall.xml`, loaded by ActivityManagerService on every boot. Silently drops broadcasts before delivery.

**21 rules blocking:**

| Category | Blocked Intents |
|----------|----------------|
| **Accessibility abuse** | `ENABLE_ACCESSIBILITY_SERVICE` (Rivendell sends every ~30s to re-enable Settings killer) |
| **Third-party app attacks** | `KILL_THIRD_PARTY`, `RESET_THIRD_PARTY_CACHE` |
| **System lockdown** | `CONFIGURE_TABLET`, `PRIVILEGEDMODE`, `SET_LANGUAGE`, `FORGET_WIFI_NETWORK` |
| **OTA updates** | `IDLE_UPDATE`, `REQUEST_UPDATE_INSTALL`, `PREVIEW_UPDATES` |
| **App restart/reboot** | `STANDALONE_BOUNCE` (can reboot entire device via LaunchDarkly flag) |
| **Platform migration** | `VALINOR_OPT_IN_FROM_WOLF`, `VALINOR_OPT_IN_FROM_WOLF_ACTION` |
| **Diagnostics/debug** | `SEND_LOGS`, `PLEASE_CRASH`, `UPDATE_ENVIRONMENT`, `FLASH_TUNER`, `KILL_DEMO_MODE`, `CLUB_LOGOUT` |
| **Overlays** | `SYSTEM_OVERLAY_START`, `SYSTEM_OVERLAY_STOP` |
| **Component blocks** | `TabletStartupReceiver` (BOOT_COMPLETED chain), `StandaloneBounceReceiver` (hourly alarm), `GlassOSAutoStartReceiver` |

### Layer 2: Immutable `/data/update.zip`

All ERU versions (v1.x through v2.27.18) write to `/data/update.zip` before calling `RecoverySystem.installPackage()`. An immutable empty file at this path blocks all system OTA installs. Tested 2026-02-11 against ERU v2.13.9 — got `FileNotFoundException: Permission denied`.

Created by `ota_postinstall` during OTA install. Re-created by boot script if missing.

### Layer 3: Boot Script (`install-recovery.sh`)

Runs as `flash_recovery` service after `sys.boot_completed=1` + 5 seconds. Handles what IFW can't:

| Action | Why IFW Can't Handle |
|--------|---------------------|
| `pm disable` TabletStartupReceiver | Belt-and-suspenders with IFW |
| `pm disable` KeepTheWolfAliveService | Android auto-restarts "started" services (not broadcast-triggered) |
| `pm disable` GlassOSAutoStartReceiver | Belt-and-suspenders |
| `pm disable` KillThirdPartyAppReceiver | Belt-and-suspenders |
| `pm disable` ResetThirdPartyAppReceiver | Belt-and-suspenders |
| `pm disable com.ifit.launcher` | HOME intent, not broadcast |
| `am force-stop` eru, standalone, glassos_service | Kill anything that started before disables took effect |
| `pm enable` 11 stock Android apps | Reverses any prior ERU lockdown |
| `appops set SYSTEM_ALERT_WINDOW deny` (7 iFit apps) | Prevents invisible overlays |
| `appops set PICTURE_IN_PICTURE allow` (all 3rd-party) | ERU disables PiP on every wake cycle |
| `settings delete enabled_accessibility_services` | ERU enables Settings-killer directly on startup |
| Fix `user_setup_complete=1`, `adb_enabled=1`, etc. | ERU writes these via Settings API |
| Restore IFW from `/system/etc/` if missing | Survives software factory reset |

### Layer 4: `.wolfDev` File

ERU checks for `/sdcard/.wolfDev` on every boot (SHA-1 hash lookup). When present:
- ADB and developer options persist across reboots
- Log deletion skipped
- Privileged mode stays active indefinitely
- Secret settings accessible (5 rapid taps on version)

Created by `ota_postinstall` during OTA install.

### Layer 5: Pre-seeded ERU SharedPreferences

`ota_postinstall` writes `isTabletConfigCompleteKey=true` to ERU's shared prefs. This prevents `configureTabletAsNecessary()` from running even if `TabletStartupReceiver` fires on first boot (PMS resets package-restrictions after OTA fingerprint change).

---

## 4. USB Control — Taking Ownership from iFit

### How iFit Accesses USB

The FitPro USB device (FTDI VID 0x0403 / PID 0x6001) is accessed through Android's `UsbManager` API. Multiple iFit components interact with it:

**ERU:**
- `UsbDeviceAttachedReceiver` — handles `USB_DEVICE_ATTACHED` broadcast
- `FitproAttachedReceiver` — handles `com.ifit.eru.FITPRO_ATTACHED`
- `GrantUsbPermissionReceiver` — handles `com.ifit.eru.USB_PERMISSION_REQUEST`
- Permission: `MANAGE_USB` (system-level USB management)

**Standalone (Xamarin):**
- `Sindarin.Usb.Android.BaseAndroidUsbDevice` — USB bulk transfers (50ms timeout)
- `Sindarin.Usb.Android.UsbConsoleConnection` — FitPro protocol connection
- `EruUsbPermissionService` / `UsbManagerUsbPermissionService` — USB permission requests
- Auto-reconnect: max 20 attempts, 5-second timeout

**Gandalf:**
- `MainActivity` scans USB devices on resume for ReformRx (VID 0x0403 / PID 0x6001)
- Sets ecosystem to "ReformRx" when detected

**GlassOS Service:**
- gRPC services for hardware abstraction: DriveMotor, ThrottleCalibration, VirtualDMK
- `glassos_sindarin_usb` module for USB enumeration

### Strategy for USB Takeover

**Current approach (boot script already handles most of this):**

1. **ERU is force-stopped on boot** — its `UsbDeviceAttachedReceiver` and `FitproAttachedReceiver` won't fire because ERU is stopped
2. **Standalone is force-stopped on boot** — its USB connection code won't run
3. **GlassOS is force-stopped on boot** — its Sindarin USB module won't initialize
4. **Gandalf's USB scanning** happens in `onResume()` of its MainActivity — if the user doesn't launch Gandalf, it won't touch USB

**What still needs to happen for Hyperborea:**

| Action | Implementation |
|--------|---------------|
| Register as USB device handler | Add `<intent-filter>` for `USB_DEVICE_ATTACHED` with VID 0x0403/PID 0x6001 in Hyperborea's manifest |
| System app for auto-permission | Install in `/system/app/` or `/system/priv-app/` to auto-grant USB permission (avoids user dialog) |
| Block iFit USB access | IFW already blocks `FITPRO_ATTACHED`; add `USB_DEVICE_ATTACHED` block for ERU's receiver |
| Prevent USB permission grants to iFit | Add IFW rule for `com.ifit.eru.USB_PERMISSION_REQUEST` |
| Handle USB reconnect | Implement reconnect logic (iFit uses 20 attempts, 5s timeout) |
| Buffer clearing | Send 0xFF packets before communication (iFit does this to clear stuck data) |

### USB Intent Filter for Hyperborea

```xml
<activity android:name=".UsbAttachedActivity"
    android:exported="true"
    android:launchMode="singleTask">
    <intent-filter>
        <action android:name="android.hardware.usb.action.USB_DEVICE_ATTACHED" />
    </intent-filter>
    <meta-data
        android:name="android.hardware.usb.action.USB_DEVICE_ATTACHED"
        android:resource="@xml/usb_device_filter" />
</activity>
```

```xml
<!-- res/xml/usb_device_filter.xml -->
<resources>
    <usb-device vendor-id="1027" product-id="24577" /> <!-- 0x0403 / 0x6001 -->
</resources>
```

### New IFW Rules for USB Protection

Add to `ifit_firewall.xml`:

```xml
<!-- Block ERU from receiving USB device attached events -->
<broadcast block="true" log="true">
    <component-filter name="com.ifit.eru/com.ifit.eru.receivers.UsbDeviceAttachedReceiver" />
</broadcast>

<!-- Block ERU from receiving FitPro attachment events -->
<broadcast block="true" log="true">
    <intent-filter>
        <action name="com.ifit.eru.FITPRO_ATTACHED" />
    </intent-filter>
</broadcast>

<!-- Block ERU USB permission grants to other apps -->
<broadcast block="true" log="true">
    <intent-filter>
        <action name="com.ifit.eru.USB_PERMISSION_REQUEST" />
    </intent-filter>
</broadcast>
```

---

## 5. Installing Hyperborea as a System App

### Why System App

| Benefit | Mechanism |
|---------|-----------|
| Uninstallable | `pm uninstall` only removes `/data/app/` overlay — system version persists |
| Auto USB permission | System apps don't need user confirmation for USB device access |
| Survives ERU attacks | ERU's `uninstallThirdPartyApps()` can't remove system apps |
| Survives factory reset | Software reset wipes `/data` but not `/system` |
| Boot-time priority | System apps are loaded before user apps |

### Tested (2026-02-13 POC)

Five third-party apps were installed to `/system/app/` and tested:
- `pm uninstall` returns "Success" but app reverts to system version (not removed)
- Updates via `adb install -r` still work (overlay in `/data/app/`)
- ~1.1GB free on system partition (plenty of room)

### Integration into OTA

Add to `firmware/repack/MGA1_20210901/modified/`:

```
system/app/com.nettarion.hyperborea/base.apk   # Hyperborea APK
```

Add to `updater-script` (permissions section):

```
set_perm_recursive(0, 0, 0755, 0644, "/system/app/com.nettarion.hyperborea");
```

Or if using `set_metadata`:

```
set_metadata_recursive("/system/app/com.nettarion.hyperborea", "uid", 0, "gid", 0, "dmode", 0755, "fmode", 0644, "selabel", "u:object_r:system_file:s0");
```

### Manual Install (without OTA rebuild)

```bash
adb root
adb shell mkdir -p /system/app/com.nettarion.hyperborea
adb push app/build/outputs/apk/debug/app-debug.apk /tmp/base.apk
adb shell cp /tmp/base.apk /system/app/com.nettarion.hyperborea/base.apk
adb shell chmod 755 /system/app/com.nettarion.hyperborea
adb shell chmod 644 /system/app/com.nettarion.hyperborea/base.apk
adb reboot
```

Verify:
```bash
adb shell dumpsys package com.nettarion.hyperborea | grep "flags="
# Should show: SYSTEM flag present
```

### Privileged System App (`/system/priv-app/`)

If Hyperborea needs privileged permissions (e.g., `MANAGE_USB`), install to `/system/priv-app/` instead:

```bash
adb shell mkdir -p /system/priv-app/com.nettarion.hyperborea
# ... same as above but in priv-app ...
```

And add a privapp-permissions whitelist:

```xml
<!-- /system/etc/permissions/privapp-permissions-hyperborea.xml -->
<permissions>
    <privapp-permissions package="com.nettarion.hyperborea">
        <permission name="android.permission.MANAGE_USB" />
    </privapp-permissions>
</permissions>
```

---

## 6. iFit Components That Threaten Hyperborea

### Direct Threats (must block)

| Component | App | Threat | Defense |
|-----------|-----|--------|---------|
| `UsbDeviceAttachedReceiver` | ERU | Steals USB device on attach | Add IFW component block + ERU is force-stopped |
| `FitproAttachedReceiver` | ERU | Triggers iFit USB init chain | IFW blocks `FITPRO_ATTACHED` |
| `GrantUsbPermissionReceiver` | ERU | Grants USB to iFit apps | IFW blocks `USB_PERMISSION_REQUEST` |
| `KillThirdPartyAppReceiver` | ERU | Force-stops sideloaded apps | IFW + pm disable (already blocked) |
| `ResetThirdPartyAppReceiver` | ERU | Wipes app data | IFW + pm disable (already blocked) |
| `KeepTheWolfAliveService` | ERU | Restarts Standalone (which grabs USB) | pm disable (already blocked) |
| `TabletStartupReceiver` | ERU | Boot chain: enables all iFit apps, disables stock apps, enables accessibility | IFW + pm disable (already blocked) |
| `AccessibilityServiceImpl` | GlassOS/Arda | Presses Back when Settings detected, invisible overlay | Settings cleared + force-stop (already blocked) |
| `EnableAccessibilityServiceReceiver` | ERU | Re-enables accessibility every ~30s (from Rivendell) | IFW + pm disable (already blocked) |
| `GlassOSAutoStartReceiver` | GlassOS | Starts gRPC server + Sindarin USB HAL on boot | IFW + pm disable (already blocked) |
| `StandaloneBounceReceiver` | ERU | Hourly alarm: restarts Standalone or reboots device | IFW component block (already blocked) |
| `GandalfPreloaderService` | Gandalf | Starts Mithlond preloader, launches Gandalf UI | Not currently blocked (manual launch only) |

### Indirect Threats (monitor)

| Component | App | Risk | Notes |
|-----------|-----|------|-------|
| `AppStoreService` | ERU | Can install apps without consent | Per-app installs, not system OTA. Lower risk since system partition unchanged. |
| `AwakeSleepReceiver` | ERU | Triggers third-party kill on sleep/wake | Direct call, not broadcast. IFW can't block. But targets are controlled by LaunchDarkly flags (default empty). |
| `PackageReplacedReceiver` | ERU | Detects app installs | Informational — doesn't directly interfere |
| `InternetMonitoringService` | ERU | Could show "No Connection" overlay | Force-stopped on boot |
| Gandalf USB scan | Gandalf | Scans for ReformRx device in `onResume()` | Only active if user launches Gandalf. Sets ecosystem provider. |
| `PiPDisablerServiceImpl` | ERU | Disables PiP on wake cycles | Boot script re-enables PiP for all third-party apps |

### Non-Threats (safe to ignore)

| Component | Why Safe |
|-----------|---------|
| Rivendell workout UI | Only active during iFit workouts |
| Mithlond content discovery | Only active if launched |
| `FileContentProvider` (all apps) | Read-only log access, permission-protected |
| Firebase/Analytics services | Informational only |
| Media/audio services | Won't interfere with USB |

---

## 7. Simplified Deployment Plan

### Current Pain Points

1. Recovery key patching is manual and fiddly (on-device cpio operations)
2. After hardware factory reset, full 6-step deploy process required
3. OTA is ~560MB, takes ~80s to push
4. No way to update just Hyperborea without full OTA rebuild

### Simplified Approach

**Phase 1: One-Time Firmware Install** (same as current `deploy_ota.sh`)
- Flash modified OTA with all protection baked in
- This only needs to happen once per device (or after hardware factory reset)

**Phase 2: Hyperborea Updates** (new, lightweight)
- Since system partition is RW, just push new APK:
```bash
adb root
adb push hyperborea.apk /system/app/com.nettarion.hyperborea/base.apk
adb shell chmod 644 /system/app/com.nettarion.hyperborea/base.apk
adb reboot
```
- Or use the overlay mechanism (no reboot):
```bash
adb install -r hyperborea.apk
# Updates as /data/app/ overlay on top of system version
```

**Phase 3: Bake Hyperborea into OTA** (for clean installs)
- Add Hyperborea APK to `firmware/repack/MGA1_20210901/modified/system/app/`
- Update `repack_ota_MGA1_20210901.py` to include it
- Single OTA gives: root + protection + Hyperborea pre-installed

### Updated Boot Script Additions

Add to `install-recovery.sh` for USB protection:

```bash
# --- USB protection for Hyperborea ---

# Block ERU from stealing USB device on hot-plug
pm disable com.ifit.eru/.receivers.UsbDeviceAttachedReceiver

# Block Gandalf from detecting ReformRx USB device
# (Gandalf scans in onResume - this prevents it from setting ecosystem)
pm disable com.ifit.gandalf/.views.MainActivity 2>/dev/null

# Grant USB permission to Hyperborea (system app auto-grants on API 25)
# No action needed — system apps get automatic USB permission
```

### Updated IFW Rules

Add these rules to the existing `ifit_firewall.xml`:

```xml
<!-- Block ERU from receiving USB device attachment (Hyperborea owns USB) -->
<broadcast block="true" log="true">
    <component-filter name="com.ifit.eru/com.ifit.eru.receivers.UsbDeviceAttachedReceiver" />
</broadcast>

<!-- Block FitPro attachment broadcast (prevents iFit USB init chain) -->
<broadcast block="true" log="true">
    <intent-filter>
        <action name="com.ifit.eru.FITPRO_ATTACHED" />
    </intent-filter>
</broadcast>

<!-- Block USB permission grants to iFit apps -->
<broadcast block="true" log="true">
    <intent-filter>
        <action name="com.ifit.eru.USB_PERMISSION_REQUEST" />
    </intent-filter>
</broadcast>
```

---

## 8. Recovery Procedures

### After Software Factory Reset (Settings → Factory Reset)

Wipes `/data` + `/cache` only. Boot and system partitions unchanged. Hyperborea system APK survives.

```bash
adb connect 192.168.1.177:5555
adb root
adb shell touch /sdcard/.wolfDev
adb push tools/chattr_arm64 /data/local/tmp/chattr
adb shell chmod 755 /data/local/tmp/chattr
adb shell "touch /data/update.zip && /data/local/tmp/chattr +i /data/update.zip"
# Boot script restores IFW from /system/etc/ automatically
```

### After Hardware Factory Reset (hold Power during boot)

Restores ALL partitions from backup (mmcblk0p9). Returns to 2017 boot + 2019 system. Everything is gone.

```bash
./tools/deploy_ota.sh firmware/repack/MGA1_20210901/output/MGA1_20210901-rooted-signed.zip
```

### If ADB Gets Disabled

With `.wolfDev` present, shouldn't happen. If it does:
1. On ERU screen, tap version number 7 times → Developer Options
2. Enable USB debugging and ADB over network
3. Reconnect and recreate dev file

### Brick Recovery Paths

| Method | When to Use |
|--------|-------------|
| Fastboot (USB) | Bootloader has full fastboot support |
| Recovery partition | Separate from boot, can reflash |
| SD card recovery | Bootloader has `sd_recovery` command |
| UART console | ttySAC0 at 115200 — U-Boot shell for manual recovery |
| Hardware factory reset | Hold Power during boot — restores from mmcblk0p9 backup |

---

## 9. Verification Checklist

After any deployment, verify:

```bash
adb shell "
    echo ROOT=$(whoami)
    echo SELINUX=$(getenforce)
    echo TYPE=$(getprop ro.build.type)
    echo DEBUG=$(getprop ro.debuggable)
    echo ADBSEC=$(getprop ro.adb.secure)
    echo WOLFDEV=$(test -f /sdcard/.wolfDev && echo OK || echo MISSING)
    echo OTA=$(echo x > /data/update.zip 2>&1 && echo WRITABLE || echo BLOCKED)
    echo HYPERBOREA=$(pm list packages -s | grep -c com.nettarion.hyperborea)
    echo HYPERBOREA_FLAGS=$(dumpsys package com.nettarion.hyperborea 2>/dev/null | grep 'pkgFlags' | head -1)
    echo USB_RX=$(pm dump com.ifit.eru 2>/dev/null | grep -c 'UsbDeviceAttachedReceiver.*disabled')
    echo KILL_RX=$(pm dump com.ifit.eru 2>/dev/null | grep -c 'KillThirdPartyAppReceiver.*disabled')
    echo RESET_RX=$(pm dump com.ifit.eru 2>/dev/null | grep -c 'ResetThirdPartyAppReceiver.*disabled')
    echo A11Y_RX=$(pm dump com.ifit.eru 2>/dev/null | grep -c 'EnableAccessibilityServiceReceiver.*disabled')
    echo LAUNCHER=$(pm list packages -d 2>/dev/null | grep -c com.ifit.launcher)
    echo IFW=$(test -f /data/system/ifw/ifit_firewall.xml && echo OK || echo MISSING)
"

# Expected:
#   ROOT=root
#   SELINUX=Permissive
#   TYPE=user
#   DEBUG=1
#   ADBSEC=0
#   WOLFDEV=OK
#   OTA=BLOCKED
#   HYPERBOREA=1
#   HYPERBOREA_FLAGS=[ SYSTEM ... ]
#   USB_RX=1 (disabled)
#   KILL_RX=1 (disabled)
#   RESET_RX=1 (disabled)
#   A11Y_RX=1 (disabled)
#   LAUNCHER=1 (in disabled list)
#   IFW=OK
```
