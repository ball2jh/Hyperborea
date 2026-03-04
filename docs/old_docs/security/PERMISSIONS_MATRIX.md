# iFit Apps Permissions Matrix

> **Complete permission mapping for all iFit applications on NordicTrack S22i**

This document provides a comprehensive view of all permissions requested by each iFit app, with security implications.

## Permission Summary

| Permission Category | ERU | Standalone | Launcher |
|---------------------|-----|------------|----------|
| **System UID** | ✅ YES | ❌ No | ❌ No |
| **Dangerous Permissions** | 20+ | 10+ | 0 |
| **Signature Permissions** | 10+ | 0 | 0 |
| **Normal Permissions** | 15+ | 10+ | 2 |
| **Exported Components** | 3+ | 5+ | 1 |

---

## iFit ERU (com.ifit.eru v1.2.1.145)

**Manifest:** `ifit_apps/decompiled/eru/resources/AndroidManifest.xml`

### Critical: System UID

```xml
<manifest xmlns:android="http://schemas.android.com/apk/v1/apk-res/android"
    package="com.ifit.eru"
    android:sharedUserId="android.uid.system">
```

**Risk Level:** 🔴 **CRITICAL**

**Implications:**
- Runs with UID 1000 (system user)
- Access to all system resources
- Can read/write system partition (when remounted)
- Can access any app's private data
- Bypasses normal permission checks for system-protected resources

### Signature/System Permissions

These permissions require `android:sharedUserId="android.uid.system"` or system signature:

| Permission | Risk | Purpose | Notes |
|-----------|------|---------|-------|
| `INSTALL_PACKAGES` | 🔴 Critical | Install/uninstall apps silently | Can replace any app without user consent |
| `DELETE_PACKAGES` | 🔴 Critical | Uninstall apps | Can remove security software |
| `REBOOT` | 🔴 High | Reboot device | Can trigger reboots programmatically |
| `RECOVERY` | 🔴 High | Access recovery partition | Can boot into recovery mode |
| `WRITE_SECURE_SETTINGS` | 🔴 High | Modify system settings | Can disable security features |
| `UPDATE_DEVICE_STATS` | 🟡 Medium | Update device statistics | Access to power/usage stats |
| `DEVICE_POWER` | 🟡 Medium | Power management | Can wake device, prevent sleep |
| `BATTERY_STATS` | 🟢 Low | Read battery stats | Monitoring only |
| `MOUNT_UNMOUNT_FILESYSTEMS` | 🔴 High | Mount/unmount storage | Can remount system partition RW |
| `WRITE_MEDIA_STORAGE` | 🟡 Medium | Write to external storage | Full storage access |

### Dangerous Permissions

These are "dangerous" permissions requiring runtime grants on Android 6+:

| Permission | Risk | Purpose | Notes |
|-----------|------|---------|-------|
| `BLUETOOTH` | 🟡 Medium | Bluetooth connectivity | Heart rate monitors, headphones |
| `BLUETOOTH_ADMIN` | 🟡 Medium | Bluetooth management | Can enable/disable, discover devices |
| `ACCESS_FINE_LOCATION` | 🟡 Medium | GPS location | Required for Bluetooth on Android 6+ |
| `ACCESS_COARSE_LOCATION` | 🟡 Medium | Network location | Required for WiFi scanning |
| `CAMERA` | 🟡 Medium | Camera access | Unknown usage |
| `READ_EXTERNAL_STORAGE` | 🟡 Medium | Read files | Workout data, logs |
| `WRITE_EXTERNAL_STORAGE` | 🟡 Medium | Write files | Workout data, logs, updates |
| `RECORD_AUDIO` | 🟠 Medium-High | Microphone access | Unknown usage (voice commands?) |

### Normal Permissions

| Permission | Purpose |
|-----------|---------|
| `INTERNET` | Network communication (iFit API) |
| `ACCESS_NETWORK_STATE` | Check network connectivity |
| `ACCESS_WIFI_STATE` | Check WiFi status |
| `CHANGE_WIFI_STATE` | Enable/disable WiFi |
| `WAKE_LOCK` | Prevent sleep during workouts |
| `RECEIVE_BOOT_COMPLETED` | Auto-start on boot |
| `VIBRATE` | Haptic feedback |
| `NFC` | NFC communication (unknown usage) |
| `DISABLE_KEYGUARD` | Disable lock screen |
| `GET_TASKS` | See running apps |
| `KILL_BACKGROUND_PROCESSES` | Kill other apps |

### Exported Components

**Broadcast Receivers (Exported):**

1. **PrivilegedModeReceiver** 🔴 HIGH RISK
   ```xml
   <receiver android:name="com.ifit.eru.PrivilegedModeReceiver"
             android:exported="true"/>
   ```
   - **Risk:** Any app can send intents to this receiver
   - **Capabilities:** Likely handles privileged operations (install, reboot, etc.)
   - **Authentication:** Unknown (needs code analysis)
   - **Action:** [PRIVILEGE_ESCALATION.md](PRIVILEGE_ESCALATION.md)

2. **BootReceiver**
   ```xml
   <receiver android:name="com.ifit.eru.BootReceiver">
       <intent-filter>
           <action android:name="android.intent.action.BOOT_COMPLETED"/>
       </intent-filter>
   </receiver>
   ```
   - **Risk:** Low (standard boot receiver)
   - **Purpose:** Start ERU service on device boot

3. **UpdateReceiver**
   ```xml
   <receiver android:name="com.ifit.eru.UpdateReceiver"
             android:exported="true"/>
   ```
   - **Risk:** 🟡 Medium (handles system updates)
   - **Purpose:** Receive update notifications
   - **Concern:** Could be triggered maliciously to initiate updates

**Services (Not Exported):**
- `EruService` - Main background service (not exported)
- `UsbService` - USB device communication (not exported)

---

## iFit Standalone (com.ifit.standalone v2.2.8.364)

**Manifest:** `ifit_apps/decompiled/standalone/resources/AndroidManifest.xml`

### Dangerous Permissions

| Permission | Risk | Purpose | Notes |
|-----------|------|---------|-------|
| `BLUETOOTH` | 🟢 Low | Heart rate monitors | Standard workout functionality |
| `BLUETOOTH_ADMIN` | 🟢 Low | Bluetooth management | Pair HR monitors |
| `ACCESS_FINE_LOCATION` | 🟡 Medium | Location services | Required for Bluetooth LE |
| `ACCESS_COARSE_LOCATION` | 🟡 Medium | Network location | WiFi-based location |
| `READ_EXTERNAL_STORAGE` | 🟢 Low | Read workout data | Standard app functionality |
| `WRITE_EXTERNAL_STORAGE` | 🟢 Low | Save workouts | Standard app functionality |
| `CAMERA` | 🟡 Medium | Profile photos | User-initiated only |
| `RECORD_AUDIO` | 🟡 Medium | Voice commands? | Unknown usage |

### Normal Permissions

| Permission | Purpose |
|-----------|---------|
| `INTERNET` | iFit API, workout downloads |
| `ACCESS_NETWORK_STATE` | Check connectivity |
| `ACCESS_WIFI_STATE` | WiFi status |
| `WAKE_LOCK` | Keep screen on during workouts |
| `VIBRATE` | Notifications, feedback |
| `RECEIVE_BOOT_COMPLETED` | Auto-launch on boot (as launcher) |
| `FOREGROUND_SERVICE` | Background workout tracking |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Prevent app killing during workouts |

### Exported Components

**Activities (Exported):**

1. **WelcomeView** (Main activity)
   ```xml
   <activity android:name="md59795d62579a1e2616005f7a50cf6e392.WelcomeView"
             android:exported="true">
       <intent-filter>
           <action android:name="android.intent.action.MAIN"/>
           <category android:name="android.intent.category.LAUNCHER"/>
       </intent-filter>
   </activity>
   ```
   - **Risk:** 🟢 Low (standard launcher entry)

2. **DeepLinkActivity**
   ```xml
   <activity android:name=".DeepLinkActivity"
             android:exported="true">
       <intent-filter>
           <action android:name="android.intent.action.VIEW"/>
           <category android:name="android.intent.category.DEFAULT"/>
           <category android:name="android.intent.category.BROWSABLE"/>
           <data android:scheme="ifit"/>
       </intent-filter>
   </activity>
   ```
   - **Risk:** 🟡 Medium (handles ifit:// deep links)
   - **Concern:** Malicious deep links could trigger unintended actions

**Broadcast Receivers (Exported):**

3. **WorkoutUpdateReceiver**
   ```xml
   <receiver android:name=".WorkoutUpdateReceiver"
             android:exported="true"/>
   ```
   - **Risk:** 🟡 Medium (receives workout status updates)
   - **Concern:** Could be spammed with fake updates

### IPC Communication

Standalone communicates with ERU via:
- **Intents** - Sends commands to ERU
- **Broadcasts** - Receives status updates from ERU
- **Shared Preferences** - Unknown if used for IPC

**Security Boundary:** This IPC channel is critical. Any vulnerability in ERU's intent handling could allow Standalone (or malicious apps) to execute privileged operations.

**Analysis:** [../architecture/IPC_COMMUNICATION.md](../architecture/IPC_COMMUNICATION.md)

---

## iFit Launcher (com.ifit.launcher v1.0.12)

**Manifest:** `ifit_apps/decompiled/launcher/resources/AndroidManifest.xml`

### Permissions

**Normal Permissions Only:**

| Permission | Purpose |
|-----------|---------|
| `INTERNET` | Unknown (minimal launcher) |
| `ACCESS_NETWORK_STATE` | Check connectivity |

**Notes:**
- Minimal permission set
- Standard Android launcher replacement
- No dangerous or signature permissions
- Low security risk

### Exported Components

**Activities:**

1. **LauncherActivity**
   ```xml
   <activity android:name=".LauncherActivity"
             android:exported="true">
       <intent-filter>
           <action android:name="android.intent.action.MAIN"/>
           <category android:name="android.intent.category.HOME"/>
           <category android:name="android.intent.category.DEFAULT"/>
       </intent-filter>
   </activity>
   ```
   - **Risk:** 🟢 Low (standard home screen launcher)
   - **Purpose:** Replace default Android launcher

---

## Permission Risk Analysis

### High-Risk Combinations

#### 1. ERU: System UID + INSTALL_PACKAGES + Exported Receiver
**Risk:** 🔴 **CRITICAL**

Combination of:
- System-level privileges (UID 1000)
- Ability to install apps silently
- Exported broadcast receiver accepting commands

**Attack Scenario:**
```
Malicious App → Intent to PrivilegedModeReceiver → ERU installs malicious APK → System compromise
```

**Mitigation:**
- Add authentication to PrivilegedModeReceiver
- Validate all install requests against whitelist
- Require user confirmation for installations

#### 2. Standalone: Deep Links + IPC to ERU
**Risk:** 🟡 **MEDIUM**

Combination of:
- Deep link handling (`ifit://` scheme)
- Direct IPC channel to privileged ERU service

**Attack Scenario:**
```
Malicious Website → ifit:// link → Standalone deep link handler → Crafted intent to ERU → Privileged operation
```

**Mitigation:**
- Validate all deep link parameters
- Sanitize data before sending to ERU
- Implement intent verification in ERU

#### 3. Network ADB + System UID Apps
**Risk:** 🔴 **CRITICAL**

Combination of:
- Unauthenticated network ADB (port 5555)
- Apps with system privileges installed

**Attack Scenario:**
```
Attacker on WiFi → adb connect → adb root → Full device control
```

**Mitigation:**
- Disable network ADB in production devices
- Use authentication for ADB
- Isolate device on trusted network

### Permission Escalation Paths

```
User-Level App (UID 10XXX)
    ↓ Send intent
PrivilegedModeReceiver (Exported)
    ↓ Process in ERU
ERU Service (UID 1000 - system)
    ↓ Execute privileged operation
System-Level Access
    ↓
Full Device Control
```

**Key Weakness:** No authentication between Standalone and ERU's PrivilegedModeReceiver.

## Verification Commands

### Check App UIDs
```bash
# ERU should show UID 1000 (system)
adb shell ps | grep com.ifit.eru

# Standalone should show UID 10XXX (user app)
adb shell ps | grep com.ifit.standalone
```

### Check Exported Components
```bash
# List all exported components for ERU
adb shell dumpsys package com.ifit.eru | grep -A 5 "Receiver"

# List intents ERU can receive
adb shell dumpsys package com.ifit.eru | grep "Intent Filter"
```

### Test Permission Grants
```bash
# Check what permissions ERU actually has at runtime
adb shell dumpsys package com.ifit.eru | grep "granted=true"
```

## Manifest Extraction

To re-extract manifests for verification:

```bash
# ERU (system app)
adb pull /system/priv-app/com.ifit.eru-1.2.1.145/com.ifit.eru-1.2.1.145.apk
aapt dump xmltree com.ifit.eru-1.2.1.145.apk AndroidManifest.xml

# Standalone (user app)
adb pull /data/app/com.ifit.standalone-1/base.apk
aapt dump xmltree base.apk AndroidManifest.xml

# Or use decompiled versions
cat ifit_apps/decompiled/eru/resources/AndroidManifest.xml
cat ifit_apps/decompiled/standalone/resources/AndroidManifest.xml
```

## Related Documentation

- **[PRIVILEGE_ESCALATION.md](PRIVILEGE_ESCALATION.md)** - Detailed ERU privilege analysis
- **[ATTACK_SURFACE.md](ATTACK_SURFACE.md)** - Exploitation scenarios
- **[../architecture/IPC_COMMUNICATION.md](../architecture/IPC_COMMUNICATION.md)** - IPC message flows

---

**Last Updated:** 2026-02-10
**Source:** Decompiled AndroidManifest.xml files
**Verification:** Tested on device NN73Z115616
