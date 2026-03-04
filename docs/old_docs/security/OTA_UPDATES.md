# OTA Update System Analysis

> **Critical Security Finding: System firmware updates can remove root access**

This document analyzes the iFit OTA (Over-The-Air) update system, including API endpoints, update mechanisms, and security implications.

## Executive Summary

### 🔴 CRITICAL: Firmware Update Threat

The device currently has a **system firmware update available** (20190521 → MGA1_20210616) that, if installed, would likely remove `adb root` access and lock down the device.

**Current Status:**
- ✅ Firmware update BLOCKED — immutable `/data/update.zip` confirmed working (2026-02-11)
- ✅ Update mechanism fully reverse-engineered across 3 ERU versions
- ✅ Protection guide: [PROTECTION.md](../guides/PROTECTION.md)

---

## Strategies Tested (2026-02-10 / 2026-02-11)

| Strategy | Date Tested | Result | Details |
|----------|------------|--------|---------|
| Replace `/system/etc/security/otacerts.zip` | 2026-02-10 | **FAILED** | Recovery has its own `/res/keys`, ignores otacerts entirely |
| Iptables firewall (UID 1000 → update servers) | 2026-02-10 | Partial | Blocks downloads but resets on reboot |
| Immutable `/mnt/sdcard/iFit/system` | 2026-02-11 | **FAILED** | Only blocks ERU v1.x; v2.2.20+ downloads to `/sdcard/Download/` |
| Immutable `/data/update.zip` | 2026-02-11 | **SUCCESS** | Only path common to ALL ERU versions. ERU v2.13.9 got `FileNotFoundException: Permission denied` |

**Key finding:** Each ERU version uses a different download path, but ALL versions ultimately write to `/data/update.zip` before calling `RecoverySystem.installPackage()`. Making this file immutable is the only universal block.

---

## Update Infrastructure

### API Endpoints

**Primary API:**
```
https://api.ifit.com
```

**CDN/Download Server:**
```
https://ifit-wolf.s3-cdn.ifit.com/android/builds/public/app-updates/
```

### Update Check Process

1. **ERU periodically checks** `https://api.ifit.com` for available updates
2. **API returns** JSON with version information for all components
3. **ERU saves** response to `/mnt/sdcard/iFit/apps/apps.json`
4. **Downloads APKs** from S3 bucket if updates available
5. **System firmware** update requires separate download/flash process

### Log Evidence

From ERU logs (`ifit_apps/eru/v1.2.1.145/logs/DeviceAdmin_01Sep2009.txt`):

```
[2/10/2026 1:38:27 PM] Updates available: 5
[2/10/2026 1:38:27 PM] Updates available: [Eru, (1.2.1.145, 2.1.1.1227)],
                                          [Launcher, (1.0.12, 1.0.17.22)],
                                          [Standalone, (2.2.8.364, 2.6.86.4458)],
                                          [Workout, (-1, 93)],
                                          [System, (20190521, MGA1_20210616)]

[2/10/2026 1:38:27 PM] Attempting to load Shire.Core.Api.Ifit.DataObjects.ConsoleAppsUpdates
                       from /mnt/sdcard/iFit/apps/apps.json

[2/10/2026 1:38:27 PM] Starting download service for: App
[2/10/2026 1:38:27 PM] Deleting old update files if they exist

[2/10/2026 1:38:27 PM] Downloading:
    https://ifit-wolf.s3-cdn.ifit.com/android/builds/public/app-updates/com.ifit.eru-2.1.1.1227.apk
    https://ifit-wolf.s3-cdn.ifit.com/android/builds/public/app-updates/com.ifit.standalone-2.6.86.4458.apk
    https://ifit-wolf.s3-cdn.ifit.com/android/builds/public/app-updates/com.ifit.launcher-1.0.17.22.apk

[2/10/2026 1:38:27 PM] Failed Update analytic was called.
[2/10/2026 1:38:27 PM] There was an error updating
```

**Key Observations:**
- Update check happened automatically (periodic background check)
- 5 updates detected (3 APKs + 1 Workout library + 1 System firmware)
- Downloads were attempted but failed
- System firmware version change indicates bootloader/kernel update

---

## Available Updates

### App Updates (Safe - Can be installed)

| Component | Current | Available | APK URL |
|-----------|---------|-----------|---------|
| **ERU** | 1.2.1.145 | 2.1.1.1227 | `com.ifit.eru-2.1.1.1227.apk` |
| **Standalone** | 2.2.8.364 | 2.6.86.4458 | `com.ifit.standalone-2.6.86.4458.apk` |
| **Launcher** | 1.0.12 | 1.0.17.22 | `com.ifit.launcher-1.0.17.22.apk` |
| **Workout Library** | -1 | 93 | *(likely data files, not APK)* |

### System Firmware (⚠️ DANGEROUS - Do NOT install)

| Component | Current | Available | Notes |
|-----------|---------|-----------|-------|
| **System** | 20190521 | MGA1_20210616 | **Will lock root access!** |

**Firmware Details:**
- Current build date: **May 21, 2019**
- New build: **MGA1_20210616** (June 16, 2021)
- Version naming: `MGA1` = Model/Generation/Architecture identifier
- 2+ year update indicates major security/lockdown changes

---

## Update Mechanism by ERU Version

> **Status:** Fully reverse-engineered from decompiled source across three ERU versions. The update mechanism changed significantly between versions.

### Version Comparison

| | ERU v1.2.1.145 | ERU v2.2.20.1602 | ERU v2.13.9.1852 |
|---|---|---|---|
| **Codebase** | Xamarin/C# (.NET DLLs) | Kotlin (native Android) | Kotlin (native Android) |
| **API** | `api.ifit.com/v1/android_firmware_update/` | Gateway `/wolf-updates/` | Gateway `/wolf-updates/` |
| **Auth** | Basic (hardcoded plaintext) | Bearer (OAuth2) | Bearer (OAuth2) |
| **Download path** | `/mnt/sdcard/iFit/system/` | `/storage/emulated/0/Download/` | Direct to `/data/update.zip` |
| **Download method** | Custom FileDownloader | Android DownloadManager | Raw URLConnection |
| **Install path** | `/data/update.zip` (moved from sdcard) | `/data/update.zip` | `/data/update.zip` |
| **Install trigger** | `RecoverySystem.InstallPackage()` | `RecoverySystem.InstallPackage()` | `RecoverySystem.installPackage()` |
| **Trigger scheduler** | `PeriodicIdleReceiver` (30 min) | WorkManager | `IdleUpdateCheckWorker` (WorkManager) |
| **Feature flags** | None | Unknown | LaunchDarkly (`feature-should-download-update-during-workout`) |
| **Decompiled source** | `ifit_apps/eru/v1.2.1.145/decompiled/` | `ifit_apps/eru/v2.2.20.1602/decompiled/` | `ifit_apps/eru/v2.13.9.1852/decompiled/` |

### ERU v1.2.1.145 (Xamarin — Original on Device)

**Source files:**
- `Eru.Core/Eru.Core.Services.Updates.System/SystemUpdateInstallService.cs`
- `Eru.Core/Eru.Core.Services.Updates.AvailableCheck/OnlineUpdateManifestSource.cs`
- `Eru.Android/Eru.Android.Privileged/PrivilegedCommands.cs`

```
1. PeriodicIdleReceiver fires every 30 min (triggers after 4+ hours idle)
   ↓
2. GET https://api.ifit.com/v1/android_firmware_update/{Build.VERSION.Incremental}
   Auth: Basic (hardcoded client ID/secret)
   ↓
3. Manifest saved to /mnt/sdcard/iFit/system/system.json
   ↓
4. FileDownloader downloads OTA zip to /mnt/sdcard/iFit/system/
   ↓
5. MOVES file to /data/update.zip  ← HARDCODED PATH
   ↓
6. RecoverySystem.InstallPackage(context, new File("/data/", "update.zip"))
   ↓
7. Recovery verifies against /res/keys → applies OTA
```

**Block point:** Immutable file at `/mnt/sdcard/iFit/system` OR `/data/update.zip`

### ERU v2.2.20.1602 (Kotlin — Transitional)

**Source files:**
- `ifit_apps/eru/v2.2.20.1602/decompiled/` (partially decompiled)
- Obfuscated class names: `k5.h`, `j1.a`, `s1.j5`

```
1. WorkManager schedules periodic update check
   ↓
2. API check via Gateway: GET https://gateway.ifit.com/wolf-updates/android
   Auth: Bearer (OAuth2)
   ↓
3. DownloadManager enqueues download to /storage/emulated/0/Download/
   (This is /sdcard/Download/ on the FUSE mount)
   ↓
4. DownloadCompleteReceiver processes completed download
   ↓
5. File ends up at /data/update.zip
   ↓
6. RecoverySystem.installPackage(context, file)
```

**Tested 2026-02-11:** This version bypassed our `/mnt/sdcard/iFit/system` immutable block because it uses `/sdcard/Download/` instead. The OTA was applied before we could intervene.

**Block point:** `/data/update.zip` is the ONLY reliable block. The `/mnt/sdcard/iFit/system` path is not used by this version.

### ERU v2.13.9.1852 (Kotlin — Latest Available)

**Source files:**
- `ifit_apps/eru/v2.13.9.1852/decompiled/sources/na/b.java` — System updater
- `ifit_apps/eru/v2.13.9.1852/decompiled/sources/com/ifit/eru/services/updates/UpdateSpaceImpl.java` — Storage management
- `ifit_apps/eru/v2.13.9.1852/decompiled/sources/com/ifit/eru/workers/IdleUpdateCheckWorker.java` — Scheduler
- `ifit_apps/eru/v2.13.9.1852/decompiled/sources/com/ifit/eru/receivers/DownloadCompleteReceiver.java` — Download handler

**Phase 1: Check & Download**
```
1. IdleUpdateCheckWorker runs via WorkManager
   ↓
2. Checks canInstallUpdates() — is console idle?
   (LaunchDarkly flag: feature-should-download-update-during-workout)
   ↓
3. API check via Gateway: GET https://gateway.ifit.com/wolf-updates/android
   Auth: Bearer (OAuth2)
   ↓
4. DownloadManager enqueues download to /sdcard/Download/
```

**Phase 2: Install** (`na/b.java`)
```
5. na.b.b() — deletes existing /data/update.zip if present
   ↓
6. na.b.a(url) — opens DIRECT URLConnection to S3 URL
   (bypasses DownloadManager entirely — re-downloads from URL)
   ↓
7. Streams directly to new File("/data/update.zip")
   ↓
8. RecoverySystem.installPackage(context, file)
```

**Key insight:** The install phase in v2.13.9 does its OWN download directly to `/data/update.zip` via raw HTTP. It does not use the DownloadManager-cached file. There are effectively two downloads: one for manifest checking, one for actual install.

**Block point:** `/data/update.zip` — making this immutable blocks both `file.delete()` and `new FileOutputStream(file)`. The IOException is caught and returns FALSE.

**Cleanup list** (`UpdateSpaceImpl.foldersFilesCanClear`):
```java
this.downloadLocation = "/sdcard/Download";
this.foldersFilesCanClear = [
    LegacyUpdatingConverter.legacyUpdateLocation,  // "/mnt/sdcard/iFit"
    "/sdcard/.wolflogs/FitProBytes.txt",
    "/sdcard/eruCrashLog",
    "/sdcard/emmcStats",
    "/sdcard/feedbackZips",
    "/sdcard/temp",
    "/data/update.zip",           // ← ERU actively tries to clean this
    "/data/ota_package/update.zip"
];
```

Note: ERU v2.13.9 explicitly lists `/mnt/sdcard/iFit` as "legacy" and `/data/update.zip` in its cleanup list. An immutable file at `/data/update.zip` will cause the cleanup to fail silently (try/catch), which is exactly what we want.

### Common to All Versions

**ERU performs ZERO signature verification itself.** All versions delegate entirely to `RecoverySystem.installPackage()` which passes verification to recovery.

### API Credentials

**v1.2.1.145** — Hardcoded plaintext in `EruIfitApiEnvironment.cs`:
```
Client ID:     edbac7601985cfab0bce5120852cae9f8e4f1805
Client Secret: ce6ee7ea5bc6ca8f5299575ece620b6c617cce67
```

**v2.13.9** — Uses Gateway API with Bearer auth (OAuth2 token). Credentials obfuscated via `TransformString()`.

Full API reference: [../api/IFIT_API.md](../api/IFIT_API.md)

### API Endpoints by Version

| Version | Firmware Update Endpoint | Auth |
|---------|------------------------|------|
| v1.2.1.145 | `GET api.ifit.com/v1/android_firmware_update/{buildIncremental}` | Basic |
| v2.2.20+ | `GET gateway.ifit.com/wolf-updates/android` | Bearer |
| v2.13.9 | `GET gateway.ifit.com/wolf-updates/android/{id}` | Bearer |

### Permissions Used

```xml
<uses-permission android:name="android.permission.INSTALL_PACKAGES"/>
<uses-permission android:name="android.permission.REBOOT"/>
<uses-permission android:name="android.permission.RECOVERY"/>
<uses-permission android:name="android.permission.WRITE_SECURE_SETTINGS"/>
```

### Signature Verification Chain

There are TWO independent verification stages:

| Stage | Where | Checks Against | Can Block? |
|-------|-------|---------------|------------|
| 1. Java pre-check | `RecoverySystem.verifyPackage()` | `/system/etc/security/otacerts.zip` | **NO** — replacing otacerts did NOT block the update (tested 2026-02-10) |
| 2. Recovery verify | Recovery binary (`/sbin/recovery`) | `/res/keys` in recovery ramdisk | **YES** — this is the actual enforcement |

**Why replacing otacerts.zip alone failed (tested):**
On 2026-02-10, we replaced `/system/etc/security/otacerts.zip` with a dummy cert (`CN=DummyOTABlocker`). ERU still successfully installed the firmware update, changing the build from userdebug (2019) to user (2021). This means either:
- `RecoverySystem.verifyPackage()` silently caught the exception and proceeded
- The RECOVERY permission or system UID bypasses the Java-side check
- SELinux permissive mode allowed the bypass
The recovery partition still had iFit's original keys at `/res/keys`, so recovery verified and applied the OTA.

### Replicating the Update for Our Own Firmware

We can use the same mechanism to install our own signed OTA. See [../firmware/OTA_REPACK_GUIDE.md](../firmware/OTA_REPACK_GUIDE.md) Phase 6.

**Summary of what's needed:**
1. **Recovery keys** — Flash our `recovery_res_keys` into recovery ramdisk `/res/keys`
2. **Signed OTA** — Build modified OTA, sign with `firmware/keys/ota_signing.*`
3. **Push** — `adb push signed-ota.zip /data/update.zip`
4. **Trigger** — Call `RecoverySystem.installPackage()` via `app_process` or small APK
5. **Protect** — After reboot, set `/data/update.zip` immutable to block iFit updates

---

## Security Analysis

### Why System Update is Dangerous

**Hypothesis:** The firmware update `MGA1_20210616` likely contains:

1. **ADB Security Hardening**
   - Disable `adb root` command
   - Require authentication for ADB connections
   - Remove root binary from `/system/xbin/su`

2. **SELinux Enforcement**
   - Switch from permissive to enforcing mode
   - Block unauthorized system modifications

3. **Bootloader Lock**
   - Prevent custom firmware installation
   - Verify system partition signatures

4. **System Partition Protection**
   - Remount `/system` as read-only permanently
   - Remove ability to remount as read-write

**Evidence Supporting Hypothesis:**
- 2+ year gap in firmware updates (significant security push)
- Current build (2019) has permissive SELinux + root access
- iFit likely responded to security audits/concerns
- Newer devices probably ship with hardened firmware

### Attack Surface: Update System

| Vector | Risk | Description |
|--------|------|-------------|
| **Update API MITM** | 🟡 Medium | No cert pinning, but uses HTTPS |
| **S3 Bucket Poisoning** | 🟠 Low | Would require AWS compromise |
| **Update Signature Bypass** | 🟡 Medium | Unknown if APK signatures verified |
| **Local Update Injection** | 🔴 High | USB updates may lack verification |
| **Forced Update Trigger** | 🔴 High | Broadcast receivers may be exploitable |

---

## Blocking Updates

### ✅ Option 0: Immutable `/data/update.zip` (ONLY RELIABLE METHOD)

**Status:** CONFIRMED WORKING (2026-02-11, tested against ERU v2.13.9.1852)

All ERU versions ultimately write to `/data/update.zip` before calling `RecoverySystem.installPackage()`. This is the **only** path that works across all versions.

**Test result (2026-02-11):** ERU v2.13.9 attempted firmware install. Logs showed:
```
SystemUpdateInstall: Creating System Install Session
SystemUpdateInstall: System update target already exists, deleting     ← file.delete() failed silently (immutable)
SystemUpdateInstall: Opening connection to: /storage/emulated/0/Download/20190521_MGA1_20210616.zip
AppUpdateInstall: Connection opened, length: 564039930
SystemUpdateInstall: IOException during copyTo, machine may be low on space: java.io.FileNotFoundException: /data/update.zip (Permission denied)
```
Update failed. Device stayed on original firmware with root intact.

For setup instructions, see [PROTECTION.md](../guides/PROTECTION.md).

**Why this is the only reliable approach:**
- v1.2.1.145 downloads to `/mnt/sdcard/iFit/system/` then moves to `/data/update.zip`
- v2.2.20 downloads to `/sdcard/Download/` then writes to `/data/update.zip`
- v2.13.9 downloads DIRECTLY to `/data/update.zip` via raw HTTP (no intermediate path)
- Blocking `/mnt/sdcard/iFit/system` only stops v1.x — **tested and failed** with v2.2.20 (2026-02-11)
- Immutable flag persists across reboots (factory reset wipes `/data`, must re-apply)
- v2.13.9 cleanup tries to delete `/data/update.zip` — immutable flag prevents this silently

**Legacy block (v1.x only, NOT sufficient alone):**
```bash
# Only blocks ERU v1.2.1.145 download path — newer versions bypass this entirely
adb shell "rm -rf /mnt/sdcard/iFit/system && touch /mnt/sdcard/iFit/system"
adb shell "/data/local/tmp/chattr +i /data/media/0/iFit/system"  # Must use ext4 path for ioctl
```

### Option 1: Revoke Permissions (Recommended)

```bash
# Block APK installation (prevents app updates)
adb shell pm revoke com.ifit.eru android.permission.INSTALL_PACKAGES

# Block automatic reboot (prevents forced updates)
adb shell pm revoke com.ifit.eru android.permission.REBOOT

# Verify
adb shell dumpsys package com.ifit.eru | grep "android.permission"
```

**Pros:**
- Non-destructive (reversible)
- Selective blocking (can still use app normally)
- Updates blocked at permission check

**Cons:**
- ERU may show error notifications
- App functionality might be degraded

### Option 2: Block Network Access

```bash
# Remove INTERNET permission (blocks all network)
adb shell pm revoke com.ifit.eru android.permission.INTERNET

# Restore when needed
adb shell pm grant com.ifit.eru android.permission.INTERNET
```

**Pros:**
- Completely blocks update checks
- No update downloads possible

**Cons:**
- Breaks legitimate ERU network features
- May cause app errors

### Option 3: Disable Update Components

```bash
# Disable update receiver (prevents auto-check)
adb shell pm disable com.ifit.eru/md5ab0504f52c91429ebf22b2ed23dfbbff.PackageReplacedReceiver

# Verify
adb shell pm list packages -d | grep eru
```

**Pros:**
- Surgical approach (only affects updates)
- App otherwise functional

**Cons:**
- May not block manual update triggers
- Component names may change in updates

### Option 4: Firewall Rules (Most Selective) ✅ **IMPLEMENTED**

**Status:** 🟢 **ACTIVE** - Firewall rules deployed on device

ERU (UID 1000) is blocked from reaching:
- `api.ifit.com:443` - Update check API
- `ifit-wolf.s3-cdn.ifit.com:443` - APK download CDN

**Manual Commands:**
```bash
# Block outbound HTTPS to api.ifit.com
adb shell iptables -I OUTPUT -p tcp -d api.ifit.com --dport 443 -m owner --uid-owner 1000 -j REJECT

# Block S3 CDN
adb shell iptables -I OUTPUT -p tcp -d ifit-wolf.s3-cdn.ifit.com --dport 443 -m owner --uid-owner 1000 -j REJECT

# Verify rules
adb shell iptables -L OUTPUT -n -v | grep REJECT
```

**Helper Scripts:**
```bash
# Block updates (apply firewall rules)
./block_updates.sh

# Unblock updates (remove firewall rules)
./unblock_updates.sh
```

**Pros:**
- ✅ Most selective (only blocks update servers)
- ✅ ERU can still use network for other purposes
- ✅ Easy to enable/disable with scripts
- ✅ Doesn't break app functionality

**Cons:**
- ⚠️ Requires root access
- ⚠️ Rules lost on reboot (run `block_updates.sh` after reboot)
- ⚠️ DNS changes could bypass (but unlikely)

---

## Monitoring Updates

### Real-time Monitoring

```bash
# Monitor ERU update activity
adb logcat | grep -iE "update|download|firmware|eru"

# Watch filesystem changes
adb shell inotifyd /sdcard/iFit/apps/ &

# Monitor network connections
adb shell netstat -tulpn | grep eru
```

### Log Files to Check

```bash
# Pull latest logs
adb pull /sdcard/eru/ ./eru_logs/

# Check for update attempts
grep -i "update\|download" eru_logs/DeviceAdmin_*.txt
grep -i "firmware\|system" eru_logs/Combined_*.txt
```

---

## Captured Endpoints Reference

**Full list saved to:** `ota_endpoints.txt`

```
# API Endpoints
https://api.ifit.com

# S3 Bucket Pattern
https://ifit-wolf.s3-cdn.ifit.com/android/builds/public/app-updates/

# Known APK URLs (captured 2026-02-10)
https://ifit-wolf.s3-cdn.ifit.com/android/builds/public/app-updates/com.ifit.eru-2.1.1.1227.apk
https://ifit-wolf.s3-cdn.ifit.com/android/builds/public/app-updates/com.ifit.standalone-2.6.86.4458.apk
https://ifit-wolf.s3-cdn.ifit.com/android/builds/public/app-updates/com.ifit.launcher-1.0.17.22.apk
```

---

## Research TODO

- [x] ~~Capture full API request/response for update check~~ — API endpoint and auth confirmed from decompiled source
- [x] ~~Determine firmware download URL format~~ — `https://ifit-wolf.s3-cdn.ifit.com/android/builds/public/android-updates/{name}.zip`
- [x] ~~Analyze system update installation process~~ — Fully reverse-engineered: `RecoverySystem.InstallPackage()` at `/data/update.zip`
- [x] ~~Test update blocking methods~~ — otacerts FAILED; `/mnt/sdcard/iFit/system` immutable FAILED (v2.2.20 bypasses); `/data/update.zip` immutable is the only universal block
- [x] ~~Decompile ERU v2.13.9~~ — Full Kotlin source at `ifit_apps/eru/v2.13.9.1852/decompiled/`
- [x] ~~Document version-specific update mechanisms~~ — v1.x, v2.2.20, v2.13.9 all use different download paths
- [x] ~~Reverse engineer APK signature verification~~ — ERU does NO verification; delegates to framework/recovery
- [ ] Document Wolf MCU firmware update mechanism (brainboard updates)
- [x] ~~Investigate USB update system~~ — Documented in [../api/IFIT_API.md](../api/IFIT_API.md)
- [ ] Create automated update monitoring script
- [x] ~~Test `chattr +i /data/update.zip` immutable file block~~ — CONFIRMED WORKING (2026-02-11, ERU v2.13.9 got IOException, update blocked)
- [ ] Build `RecoverySystem.installPackage()` trigger tool for installing our own OTA

---

## Related Documentation

- **[PRIVILEGE_ESCALATION.md](PRIVILEGE_ESCALATION.md)** - ERU's system-level permissions
- **[PERMISSIONS_MATRIX.md](PERMISSIONS_MATRIX.md)** - Complete permission analysis
- **[../architecture/APP_OVERVIEW.md](../architecture/APP_OVERVIEW.md)** - Update service architecture
- **[CLAUDE.md](../../CLAUDE.md)** - Device management procedures

---

## Critical Files

**On Device:**
- `/sdcard/eru/Api_*.txt` - API request/response logs
- `/sdcard/eru/DeviceAdmin_*.txt` - Update process logs
- `/sdcard/iFit/apps/apps.json` - Update manifest (created during check)
- `/data/data/com.ifit.eru/cache/` - Downloaded update files

**In Repository:**
- `ota_endpoints.txt` - Captured API endpoints
- `ifit_apps/eru/v1.2.1.145/logs/` - Pulled ERU logs
- `ifit_apps/eru/v1.2.1.145/decompiled/resources/assemblies/Eru.Core.dll` - Update logic

---

**Last Updated:** 2026-02-13
**Status:** PROTECTED — Intent Firewall (21 rules) + immutable `/data/update.zip` + boot script
**Risk:** Firmware update available (MGA1_20210616) that removes root — blocked by IFW + immutable flag
**Operational guide:** [PROTECTION.md](../guides/PROTECTION.md)

---

## ERU v2.27.18 — GlassOS Update Framework (Discovered 2026-02-12)

> **New ERU version deployed as part of the GlassOS platform migration. Introduces the Shire update framework, an AppStore module for per-app installs, and a new manifest system. Immutable `/data/update.zip` protection STILL WORKS.**

### Discovery Context

On 2026-02-12, the device was found running a completely new app architecture called "GlassOS". As part of this migration:
1. ERU was updated from v2.13.9 to v2.27.18
2. Launcher was updated from v1.0.17.22 to v2.38.12
3. Five new apps were installed: glassos_service, gandalf, arda, rivendell, mithlond
4. Standalone was bumped from v2.6.88 to v2.6.90 (minor, kept as fallback)
5. All apps were deployed via the launcher's AppStore mechanism

### ERU v2.27.18 Overview

| Property | Value |
|----------|-------|
| **Version** | 2.27.18 |
| **Codebase** | Kotlin (native Android) |
| **APK Size** | 22MB |
| **com.ifit Packages** | 7 |
| **Privilege Level** | System UID (uid=1000) |
| **Decompiled Source** | `ifit_apps/eru/v2.27.18/decompiled/` |
| **Key change** | Shire update framework + AppStore module |

### New Shire Update Framework

ERU v2.27.18 replaces the ad-hoc update code from previous versions with a structured framework called **Shire**:

```
com.ifit.shire/          # New update framework
├── UpdateManifestImpl   # Manifest parser
├── UpdateSession        # Update lifecycle
└── (additional classes)
```

**New manifest location:**
```
/sdcard/update/manifest.json    (UpdateManifestImpl)
```

This is separate from the old paths:
- v1.x: `/mnt/sdcard/iFit/apps/apps.json`
- v2.x: Gateway API response (in-memory)
- v2.27.18: `/sdcard/update/manifest.json` (NEW, persisted)

### Update Types

ERU v2.27.18 handles five distinct update types:

| Type | Mechanism | Path | Protected? |
|------|-----------|------|------------|
| **System** | `RecoverySystem.installPackage()` | `/data/update.zip` | YES (immutable flag) |
| **Apps** | `PackageManager` (via AppStoreManager) | APK download → install | NO (new vector) |
| **Firmware** | Console firmware updater | (device-specific) | Unknown |
| **Brainboard** | MCU firmware updater | (device-specific) | Unknown |
| **TvTuner** | TV tuner firmware | (device-specific) | Unknown |

### AppStore Module (NEW VECTOR)

ERU v2.27.18 includes its own AppStore module at `com.ifit.appstore`:

```
com.ifit.appstore/
├── AppStoreManager          # Manages per-app APK installs
├── DownloadCompleteReceiver # Handles completed downloads
└── (additional classes)
```

**How it works:**
1. ERU checks for app updates via Shire manifest
2. Uses Android `DownloadManager` to download APK files
3. `DownloadCompleteReceiver` triggers on download completion
4. Installs APK via `PackageManager.installPackage()` (NOT recovery mode)

**This is a new update vector** that is NOT blocked by the immutable `/data/update.zip` protection. However, app updates are generally lower risk than system OTA because:
- They don't modify the system partition
- They don't affect root access or SELinux
- They can be individually reverted via `pm uninstall`

### Launcher as Second App Store (NEW VECTOR)

Launcher v2.38.12 has its OWN AppStore mechanism (separate from ERU's):

```
Launcher AppStore State Machine:
  Idle → Loading → Checking → Downloading → Installing → Idle
                                              ↓
                                           Error → Idle
```

The launcher can:
- Install and update GlassOS apps (glassos_service, gandalf, arda, rivendell, mithlond)
- Track firmware update status (`FirmwareUpdateStatus`)
- Manage app lifecycle (install, update, uninstall)

**This is how the GlassOS apps were deployed on 2026-02-12** — the launcher pulled them from the iFit app store and installed them on the device.

### Launcher Firmware Updates (NEW VECTOR)

Launcher v2.38.12 includes console firmware update capability:

```
FirmwareUpdateStatus:
├── Version check
├── Download progress
├── Installation status
└── Error handling
```

This handles brainboard/MCU firmware updates — separate from system OTA. The exact mechanism needs further investigation, but this represents a third update path alongside ERU's system OTA and ERU's AppStore.

### System OTA Path (TRIPLE-PROTECTED)

The critical system OTA path is now blocked at three layers:

```
ERU v2.27.18 System Update Flow:
1. IDLE_UPDATE broadcast triggers check      ← BLOCKED by IFW (layer 1)
   ↓
2. Shire framework checks for system updates
   ↓
3. REQUEST_UPDATE_INSTALL broadcast          ← BLOCKED by IFW (layer 1)
   ↓
4. Attempts to write /data/update.zip        ← BLOCKED (immutable, layer 2)
   ↓
5. RecoverySystem.installPackage()           ← Never reached
```

**Three layers of protection:**
1. **Intent Firewall** (primary): Blocks `IDLE_UPDATE`, `REQUEST_UPDATE_INSTALL`, `PREVIEW_UPDATES`, and `CONFIGURE_TABLET` broadcasts at the Android framework level (ActivityManagerService). The update check and install never even start.
2. **Immutable `/data/update.zip`** (backup): Even if IFW were bypassed, the immutable flag prevents writing the update package.
3. **Boot script** (safety net): Disables update-related receivers via `pm disable` on each boot.

ERU v2.27.18 still uses the same final install path (`/data/update.zip` -> `RecoverySystem.installPackage()`) as all previous versions. The abstraction layer (Shire framework) does not change the underlying mechanism.

### GlassOS Service Integration

ERU v2.27.18 integrates with the GlassOS service layer:
- `com.ifit.glassos/` package for GlassOS common types
- Communicates with `glassos_service` for coordinated updates
- Feature gates from `glassos_featuregate_service` may control update behavior

### Updated Version Comparison

| | ERU v1.2.1.145 | ERU v2.2.20 | ERU v2.13.9 | **ERU v2.27.18** |
|---|---|---|---|---|
| **Codebase** | Xamarin/C# | Kotlin | Kotlin | **Kotlin** |
| **Framework** | None | None | None | **Shire** |
| **API** | `api.ifit.com/v1/` | Gateway | Gateway | **Gateway + Shire manifest** |
| **Manifest** | `apps.json` | In-memory | In-memory | **`/sdcard/update/manifest.json`** |
| **Download** | FileDownloader | DownloadManager | URLConnection | **Shire + DownloadManager** |
| **Install (system)** | `/data/update.zip` | `/data/update.zip` | `/data/update.zip` | **`/data/update.zip`** |
| **Install (apps)** | Via system OTA | Via system OTA | Via system OTA | **AppStoreManager (per-app)** |
| **App install** | - | - | - | **PackageManager** |
| **Update types** | System, Apps | System, Apps | System, Apps | **System, Apps, Firmware, Brainboard, TvTuner** |
| **GlassOS** | No | No | No | **Yes** |
| **Decompiled** | `eru/v1.2.1.145/` | `eru/v2.2.20.1602/` | `eru/v2.13.9.1852/` | **`eru/v2.27.18/`** |

### Security Implications Summary

1. **System OTA**: STILL BLOCKED by immutable `/data/update.zip` -- no change needed
2. **Per-app installs**: NEW unblocked vector via ERU AppStoreManager and Launcher AppStore
3. **Firmware updates**: NEW vector via Launcher for brainboard/MCU firmware
4. **New manifest path**: `/sdcard/update/manifest.json` could potentially be blocked with immutable flag
5. **Multiple update orchestrators**: Both ERU and Launcher can now install apps -- two vectors to monitor
6. **Apps auto-installed**: On 2026-02-12, five new apps appeared without manual intervention -- the launcher AppStore deployed them automatically

### Blocking Recommendations

| Vector | Block Method | Status |
|--------|-------------|--------|
| System OTA | IFW (blocks IDLE_UPDATE, REQUEST_UPDATE_INSTALL) + immutable `/data/update.zip` + boot script | **ACTIVE — triple-protected** |
| Per-app installs (ERU) | Not blocked (intentional — allows manual app updates) | Monitoring |
| Per-app installs (Launcher) | Launcher disabled by boot script; HOME removed from manifest | **BLOCKED** |
| Firmware updates (Launcher) | Launcher disabled by boot script | **BLOCKED** |
| Manifest download | Not blocked (informational only — IFW blocks action broadcasts) | Low risk |

### Third-Party App Management (Discovered 2026-02-13)

ERU v2.27.18 includes a privileged commands system (`c9/y.java`) that can force-stop, clear data, or fully uninstall third-party apps. Decompiled with `--show-bad-code` to `ifit_apps/eru/v2.27.18/decompiled_badcode/`.

**Methods:**
- `killThirdPartyApps` — force-stop via `ActivityManager.forceStopPackage()`
- `clearUserDataForThirdPartyApps` — calls `PackageManager.clearApplicationUserData()` via reflection
- `uninstallThirdPartyApps` — calls `PackageInstaller.uninstall()` (tagged "BetaOptOut")

**Triggers:**
- `KILL_THIRD_PARTY` broadcast → `KillThirdPartyAppReceiver` → force-stop — **BLOCKED by IFW**
- `RESET_THIRD_PARTY_CACHE` broadcast → `ResetThirdPartyAppReceiver` → clear data — **BLOCKED by IFW**
- `CLUB_LOGOUT` broadcast → clear data — **BLOCKED by IFW**
- Screensaver/sleep entry → force-stop (direct call, not broadcast — **not blocked**)
- Beta opt-out UI → uninstall (requires user action — low risk)

**Defense:** IFW blocks the broadcast triggers. Boot script disables the receivers as belt-and-suspenders. Screensaver/sleep force-stop is harmless (apps restart when woken).

**Targeting:** Only apps in LaunchDarkly flags `ifit_appstore` + `third-party-fqns` (both default empty). Sideloaded apps NOT currently targeted, but iFit can remotely update flags.

**Defense:** Boot script disables `KillThirdPartyAppReceiver` and `ResetThirdPartyAppReceiver`. Uninstall only triggered by user action in beta opt-out flow.

### AccessibilityService Settings Killer (Discovered 2026-02-13)

`glassos_service` registers an AccessibilityService that presses Back when `com.android.settings` is detected in the foreground. ERU re-enables this service every ~30 seconds via `EnableAccessibilityServiceReceiver` (triggered by Rivendell broadcasts).

**Defense:** Boot script deletes `enabled_accessibility_services` setting, force-stops `glassos_service`, and disables `EnableAccessibilityServiceReceiver`.

See [PROTECTION.md](../guides/PROTECTION.md) for full defense details.

### Research TODO (ERU v2.27.18)

- [ ] Analyze Shire framework update flow in detail
- [ ] Map AppStoreManager download URLs and API endpoints
- [ ] Investigate Launcher firmware update mechanism
- [ ] Test blocking `/sdcard/update/manifest.json` with immutable flag
- [ ] Determine if `pm revoke INSTALL_PACKAGES` blocks both ERU and Launcher
- [ ] Analyze GlassOS service integration for update coordination
- [ ] Document brainboard/TvTuner firmware update paths
