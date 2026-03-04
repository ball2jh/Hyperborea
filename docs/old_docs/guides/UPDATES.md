# Manual App Update Workflow

> **Safe method for updating iFit apps while Intent Firewall + immutable flag protect against firmware updates**

## Overview

This workflow allows you to safely update iFit apps (ERU, Standalone, GlassOS apps) while system firmware updates remain blocked by the Intent Firewall and immutable `/data/update.zip`.

**See [PROTECTION.md](./PROTECTION.md) for:**
- Complete protection status
- Layered defense strategy (IFW + ota_postinstall + boot script)
- Recovery procedures
- Troubleshooting

## Why Manual Updates?

- **100% safe** - IFW blocks system OTA broadcasts at framework level, immutable flag blocks the install path
- **Simple** - No need to disable anything; protection is always active
- **Blocks firmware** - `IDLE_UPDATE` and `REQUEST_UPDATE_INSTALL` broadcasts are silently dropped by IFW
- **Allows app updates** - Per-app APK installs via PackageManager are intentionally NOT blocked

## Protection During Updates

Unlike the old iptables approach, **you do NOT need to disable any protection** during updates. The Intent Firewall specifically blocks:
- System OTA broadcasts (`IDLE_UPDATE`, `REQUEST_UPDATE_INSTALL`, `PREVIEW_UPDATES`)
- Tablet lockdown (`CONFIGURE_TABLET`, `TabletStartupReceiver`)
- Third-party app interference (`KILL_THIRD_PARTY`, `RESET_THIRD_PARTY_CACHE`)
- Device reboot (`STANDALONE_BOUNCE`, `StandaloneBounceReceiver`)

But intentionally ALLOWS per-app APK installs via PackageManager (safe — doesn't affect system partition or root).

## When to Update

Check for new versions every few months or when you notice:
- New features announced by iFit
- Bug fixes needed
- App acting unstable

## Update Procedure

### Step 1: Prepare Environment

```bash
cd /Users/Jonathan.Ball/Documents/NordicTrackExploit
adb connect 192.168.1.177:5555
adb root
```

### Step 2: Stop iFit Apps

```bash
# Stop all iFit apps (they won't auto-restart — IFW blocks the restart mechanisms)
adb shell am force-stop com.ifit.glassos_service
adb shell am force-stop com.ifit.gandalf
adb shell am force-stop com.ifit.arda
adb shell am force-stop com.ifit.rivendell
adb shell am force-stop com.ifit.mithlond
adb shell am force-stop com.ifit.launcher
adb shell am force-stop com.ifit.eru
adb shell am force-stop com.ifit.standalone

# Verify all stopped
adb shell "ps | grep com.ifit"
# Should output nothing
```

### Step 3: Capture Update URLs from Logs

```bash
# Clear logcat
adb logcat -c

# Temporarily start ERU to check for updates
adb shell am start -n com.ifit.eru/.ui.main.MainActivity

# Wait for update check, then capture logs
sleep 30
adb logcat -d -v time > latest_update_check.log

# Extract version information
grep -i "checking.*against\|requires an update\|found update\|Matching.*URL" latest_update_check.log

# Stop ERU again
adb shell am force-stop com.ifit.eru
```

**Example output:**
```
Checking com.ifit.standalone 2.6.90 against remote version 2.7.1.5000
com.ifit.standalone requires an update: true
Matching apps URL: https://ifit-wolf.s3-cdn.ifit.com/android/builds/public/app-updates/com.ifit.standalone-2.7.1.5000.apk
```

### Step 4: Download APKs Manually

```bash
# Create directory for new versions
mkdir -p app_updates_$(date +%Y%m%d)
cd app_updates_$(date +%Y%m%d)

# Download APKs (replace URLs with captured ones)
curl -L -o com.ifit.eru-NEW_VERSION.apk "CAPTURED_ERU_URL"
curl -L -o com.ifit.standalone-NEW_VERSION.apk "CAPTURED_STANDALONE_URL"

# Verify downloads
ls -lh *.apk
```

### Step 5: Install APKs

```bash
# Install (order doesn't matter for per-app installs)
adb install -r com.ifit.eru-NEW_VERSION.apk
adb install -r com.ifit.standalone-NEW_VERSION.apk

# Verify versions
adb shell "dumpsys package com.ifit.eru | grep versionName | head -1"
adb shell "dumpsys package com.ifit.standalone | grep versionName | head -1"
```

### Step 6: Organize APKs

```bash
# Move to proper directory structure
cd /Users/Jonathan.Ball/Documents/NordicTrackExploit
mkdir -p ifit_apps/eru/vNEW_VERSION
mkdir -p ifit_apps/standalone/vNEW_VERSION

mv app_updates_*/com.ifit.eru-*.apk ifit_apps/eru/vNEW_VERSION/
mv app_updates_*/com.ifit.standalone-*.apk ifit_apps/standalone/vNEW_VERSION/
```

### Step 7: Verify Protection Still Active

```bash
# IFW rules loaded
adb shell "logcat -d -s IntentFirewall | tail -3"
# Should show: Read new rules (A:0 B:19 S:0)

# OTA still blocked
adb shell "echo test > /data/update.zip" 2>&1
# Should show: Permission denied

# Test blocked broadcast
adb shell "am broadcast -a com.ifit.eru.KILL_THIRD_PARTY"
# result=0 means silently dropped
```

### Step 8: Update Documentation

Update [VERSIONS.md](../VERSIONS.md) with new version entry.

## Quick Commands

**Check current versions:**
```bash
adb shell "dumpsys package com.ifit.eru | grep versionName | head -1"
adb shell "dumpsys package com.ifit.standalone | grep versionName | head -1"
adb shell "dumpsys package com.ifit.glassos_service | grep versionName | head -1"
adb shell "dumpsys package com.ifit.gandalf | grep versionName | head -1"
```

**Check all running iFit apps:**
```bash
adb shell "ps | grep com.ifit"
```

**Kill all iFit apps (no specific order needed):**
```bash
for app in glassos_service gandalf arda rivendell mithlond launcher eru standalone; do
    adb shell am force-stop com.ifit.$app
done
```

**Verify IFW status:**
```bash
adb shell "cat /data/system/ifw/ifit_firewall.xml | grep -c '<broadcast'"
# Should output: 21
```

## Troubleshooting

### Settings app closes immediately
The glassos_service AccessibilityService is re-enabled. Check:
```bash
adb shell "settings get secure enabled_accessibility_services"
# Should be: null (empty)
# If not, run: adb shell "settings put secure enabled_accessibility_services null"
```

### Apps auto-restart after force-stop
IFW may not be loaded. Check:
```bash
adb shell "ls -la /data/system/ifw/ifit_firewall.xml"
# If missing, restore from backup:
adb shell "cp /system/etc/ifit_firewall.xml /data/system/ifw/"
adb shell "chown system:system /data/system/ifw/ifit_firewall.xml"
adb shell "chmod 600 /data/system/ifw/ifit_firewall.xml"
# Then reboot to reload rules
```

### APK install fails
- Make sure you're using `adb install -r` (replace flag)
- Check APK downloaded completely (file size matches)
- Try `adb root` first

## Important Notes

- **Never install system firmware** — files named like `20190521_MGA1_*.zip`
- **Protection persists across reboots** — IFW rules load automatically, boot script runs on every boot
- **No kill order needed** — all iFit apps can be force-stopped in any order; none auto-restart
- **Safe to update apps anytime** — only system OTA is blocked

## Update Endpoints Reference

### API Endpoint
```
https://api.ifit.com
```

### S3 CDN Pattern
```
https://ifit-wolf.s3-cdn.ifit.com/android/builds/public/app-updates/
```

### Known APK URLs (captured 2026-02-10)

**Latest installed versions:**
- `https://ifit-wolf.s3-cdn.ifit.com/android/builds/public/app-updates/com.ifit.eru-2.2.20.1602-release-PROD.apk`
- `https://ifit-wolf.s3-cdn.ifit.com/android/builds/public/app-updates/com.ifit.standalone-2.6.88.4692.apk`
- `https://ifit-wolf.s3-cdn.ifit.com/android/builds/public/app-updates/com.ifit.launcher-1.0.17.22.apk`

### System Firmware Update (DANGEROUS - DO NOT INSTALL)

**Current:** MGA1_20210901 (safe, has root + IFW)
**Available:** MGA1_20210616 (older — will remove root and IFW!)
**Status:** BLOCKED via IFW + immutable `/data/update.zip`

---

**Last Updated:** 2026-02-13
**Status:** Protected — IFW (21 rules) + immutable flag + boot script
**See Also:** [PROTECTION.md](./PROTECTION.md) for complete protection documentation
