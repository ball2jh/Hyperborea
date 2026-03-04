# System App Uninstall Protection

## Overview

Third-party apps installed normally (via `adb install` or APK sideloading) can be fully uninstalled by ERU through its `PrivilegedCommands.uninstallApp()` AIDL/IPC path. Unlike broadcast-based attacks (which the IFW blocks), the uninstall path is a direct IPC call that cannot be blocked at the framework level.

**Solution**: Copy APKs into `/system/app/`. Android treats system apps specially — they can never be fully uninstalled. `pm uninstall` only removes the `/data/app/` overlay and reverts to the system version.

## Status

**Proof-of-concept tested on device (2026-02-13)**. Not yet baked into OTA firmware. Currently only documented for future use.

## How It Works

1. APKs placed in `/system/app/<package>/base.apk` become system apps
2. Package Manager Scanner (PMS) discovers them on boot and registers as `FLAG_SYSTEM`
3. When updated via Play Store / sideload, the update goes to `/data/app/` as an overlay
4. `pm uninstall` removes ONLY the `/data/app/` overlay — the system version persists
5. The app reverts to the system-installed version and continues to function

## Proof-of-Concept Results

### Test Setup (2026-02-13)

Five third-party apps were copied to `/system/app/`:

| App | Package | Size |
|-----|---------|------|
| Nova Launcher | com.teslacoilsw.launcher | 10 MB |
| NewPipe | org.schabi.newpipe | 12 MB |
| Aurora Store | com.aurora.store | 6 MB |
| Firefox | org.mozilla.firefox | 109 MB |
| Trainer | com.nordictrack.trainer | 8.6 MB |

### Test: Uninstall Attempt

```bash
# Attempt to uninstall Aurora Store
adb shell pm uninstall com.aurora.store
# Output: Success

# Check package flags — still installed as system app
adb shell dumpsys package com.aurora.store | grep "flags="
# Shows: SYSTEM | UPDATED_SYSTEM_APP flags

# Launch test — app still works
adb shell am start -n com.aurora.store/.MainActivity
# App launches successfully (reverted to system version)
```

### Test: Updates Still Work

After placing in `/system/app/`, installing a newer APK via `adb install -r` works normally. The update goes to `/data/app/` as an overlay on top of the system version.

## How to Protect an App

```bash
# 1. Connect and get root
adb root
adb remount

# 2. Create directory in /system/app/
adb shell mkdir -p /system/app/<package_name>

# 3. Copy the APK
adb push /path/to/app.apk /tmp/base.apk
adb shell cp /tmp/base.apk /system/app/<package_name>/base.apk

# 4. Set permissions (required for PMS to scan)
adb shell chmod 755 /system/app/<package_name>
adb shell chmod 644 /system/app/<package_name>/base.apk

# 5. Reboot for PMS to register the system app
adb reboot
```

After reboot, verify with:
```bash
adb shell dumpsys package <package_name> | grep "flags="
# Should show: SYSTEM flag present
```

## System Partition Space

As of 2026-02-13:
- **Total system partition**: ~2.5 GB
- **Free space**: ~1.1 GB (before test apps)
- **Test apps total**: ~146 MB
- **Remaining after test**: ~0.9 GB free

Plenty of space for additional protected apps.

## Limitations

1. **Requires system partition write access** — needs root + remount
2. **Requires reboot** — PMS only scans `/system/app/` at boot
3. **Not in OTA firmware yet** — apps placed manually on live device only
4. **Factory reset removes `/data/app/` overlays** — app reverts to system version (but doesn't disappear)

## Future Integration

To bake into OTA firmware:
1. Add APKs to `firmware/repack/MGA1_20210901/modified/system/app/<pkg>/base.apk`
2. Update `tools/repack_ota_MGA1_20210901.py` to include them in the system image
3. Apps will be system-installed on every fresh OTA install

## Current Defense Layers (Without System App Protection)

Even without `/system/app/` protection, third-party apps have multiple defenses:

| Threat | Defense |
|--------|---------|
| Force-stop (KILL_THIRD_PARTY) | IFW blocks broadcast + boot script disables receiver |
| Data clear (RESET_THIRD_PARTY_CACHE) | IFW blocks broadcast + boot script disables receiver |
| PiP disable | Boot script re-enables PiP for all third-party apps on every boot |
| Full uninstall (AIDL/IPC) | **Not blocked** — ERU's `PrivilegedCommands.uninstallApp()` could still work |

The `/system/app/` approach is the recommended solution for the uninstall gap.

## Related

- [IFW Rules](../../tools/ifw/ifit_firewall.xml) — Broadcast-level protection
- [Boot Script](../../firmware/repack/MGA1_20210901/modified/system/bin/install-recovery.sh) — Component disables + PiP re-enable
- [OTA Updates](OTA_UPDATES.md) — Update blocking
