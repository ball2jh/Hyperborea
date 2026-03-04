# Version History

> **Tracking firmware, app versions, and decompilation snapshots**

This file tracks version changes as the bike firmware and iFit apps are updated over time.

## Current Versions (2026-02-10)

### Device Firmware
- **Android Version:** 7.1.2 (API Level 25)
- **Kernel:** 4.4.83+
- **Build Date:** May 21, 2019 (safe firmware with root access)
- **Platform:** Nexell S5P6818 (ARM64)
- **Serial:** NN73Z115616

### iFit Applications

| App | Current Version | Package | Install Date |
|-----|----------------|---------|--------------|
| **iFit ERU** | 2.13.9.1852 | com.ifit.eru | 2026-02-10 |
| **iFit Standalone** | 2.6.88.4692 | com.ifit.standalone | 2026-02-10 |
| **iFit Launcher** | 1.0.17.22 | com.ifit.launcher | 2026-02-10 |

### App Archive

| App | Version | Location | Notes |
|-----|---------|----------|-------|
| ERU | 1.2.1.145 | `ifit_apps/eru/v1.2.1.145/` | Original version |
| ERU | 2.1.1.1227 | `ifit_apps/eru/v2.1.1.1227/` | Archived |
| ERU | 2.2.20.1602 | `ifit_apps/eru/v2.2.20.1602/` | Previous version |
| ERU | 2.13.9.1852 | `ifit_apps/eru/v2.13.9.1852/` | **Current** |
| Standalone | 2.2.8.364 | `ifit_apps/standalone/v2.2.8.364/` | Original version |
| Standalone | 2.6.86.4458 | `ifit_apps/standalone/v2.6.86.4458/` | Archived |
| Standalone | 2.6.88.4692 | `ifit_apps/standalone/v2.6.88.4692/` | **Current** |
| Launcher | 1.0.12 | `ifit_apps/launcher/v1.0.12/` | Original version |
| Launcher | 1.0.17.22 | `ifit_apps/launcher/v1.0.17.22/` | **Current** |

---

## Version Update Workflow

When apps update on the device:

### 1. Check Current Versions
```bash
# Check what's on device
adb shell dumpsys package com.ifit.standalone | grep versionName
adb shell dumpsys package com.ifit.eru | grep versionName
```

### 2. Pull New APKs
```bash
# Pull updated APK
adb pull $(adb shell pm path com.ifit.standalone | cut -d: -f2) standalone-new.apk

# Check version (example: v3.0.0.100)
aapt dump badging standalone-new.apk | grep versionName
```

### 3. Create New Version Directory
```bash
# Create directory structure for new version
cd ifit_apps/standalone
mkdir -p v3.0.0.100/decompiled

# Move APK
mv ../../standalone-new.apk v3.0.0.100/com.ifit.standalone-3.0.0.100.apk

# Generate checksum
shasum -a 256 v3.0.0.100/*.apk
```

### 4. Decompile New Version
```bash
# Decompile with jadx
jadx -d v3.0.0.100/decompiled v3.0.0.100/com.ifit.standalone-3.0.0.100.apk

# Verify DLLs present
ls v3.0.0.100/decompiled/resources/assemblies/*.dll
```

### 5. Compare Versions
```bash
# Diff DLL assemblies
diff -r v2.2.8.364/decompiled/resources/assemblies v3.0.0.100/decompiled/resources/assemblies

# Check manifest changes
diff v2.2.8.364/decompiled/resources/AndroidManifest.xml v3.0.0.100/decompiled/resources/AndroidManifest.xml

# Compare permissions
diff <(aapt dump permissions v2.2.8.364/*.apk) <(aapt dump permissions v3.0.0.100/*.apk)
```

### 6. Update Documentation
- Update this file (VERSION_HISTORY.md) with new version entry
- Update `ifit_apps/README.md` if new version is now current
- Note any significant changes in security, permissions, or functionality

---

## Version History Log

### 2026-02-10 - Major App Updates

**Installed Today:**
- ✅ **ERU:** 2.2.20.1602 → **2.13.9.1852** (MAJOR update)
- ✅ **Standalone:** 2.2.8.364 → 2.6.88.4692 (already updated)
- ✅ **Launcher:** 1.0.12 → 1.0.17.22 (already updated)

**Method:** Manual update via ADB (firewall temporarily disabled for download)

**Notes:**
- All apps successfully updated while maintaining protection
- Firewall and blocks immediately re-enabled after installation
- Device remains fully protected
- Wolf/FitPro protocol investigation completed
- Documentation consolidated (reduced from 20 to 17 MD files)

### 2026-02-10 (Baseline)
**iFit Apps:**
- Standalone v2.2.8.364
- ERU v1.2.1.145
- Launcher v1.0.12

**Device:**
- Android 7.1.2, Kernel 4.4.83+
- Serial: NN73Z115616

**Notes:**
- Initial extraction and decompilation
- Baseline for future comparisons
- Full project documentation created

---

_Future updates will be logged above this line_

---

## Firmware Update Notes

**Important:** When the bike firmware updates (OTA or USB):
1. Document current firmware version before update
2. Capture update package if possible (check `/sdcard/eru/` or `/cache/`)
3. Note any behavioral changes after update
4. Re-check security findings (permissions may change)

**How to Check Firmware:**
```bash
adb shell getprop | grep build
adb shell getprop ro.build.display.id
adb shell getprop ro.build.version.incremental
```
