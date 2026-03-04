# ERU Privilege Escalation Analysis

> **Detailed analysis of iFit ERU's system-level privileges and exploitation potential**

**Risk Level:** 🔴 **CRITICAL**
**Status:** 🔄 Under Investigation
**Last Updated:** 2026-02-10

## Executive Summary

The iFit ERU service runs with `android.uid.system` (UID 1000), granting it the highest non-root privilege level on Android. Combined with exported components accepting unauthenticated intents, this creates a critical privilege escalation vector.

**Attack Scenario:**
```
Unprivileged App (UID 10XXX)
    → Send Intent to PrivilegedModeReceiver
        → ERU processes intent as system (UID 1000)
            → Execute privileged operation
                → Full system compromise
```

## ERU Privileges

### System UID (android.uid.system)

**Manifest Declaration:**
```xml
<manifest xmlns:android="http://schemas.android.com/apk/v1/apk-res/android"
    package="com.ifit.eru"
    android:sharedUserId="android.uid.system">
```

**File:** `ifit_apps/decompiled/eru/resources/AndroidManifest.xml`

### What System UID Grants

#### File System Access
- **Read/Write:** All app data directories (`/data/data/*`)
- **Read/Write:** System partition (when mounted RW)
- **Read/Write:** System settings databases
- **Read:** Protected system files

**Verification:**
```bash
adb shell ps | grep eru
# Shows: u0_system (UID 1000)

adb shell run-as com.ifit.eru
# If successful, shell runs as system
```

#### Package Management
ERU has `INSTALL_PACKAGES` permission:
```xml
<uses-permission android:name="android.permission.INSTALL_PACKAGES"/>
```

**Capabilities:**
- Install APKs without user confirmation
- Replace existing apps (including system apps)
- Downgrade apps (bypass signature checks)
- Install to system partition

**Code to Analyze:** Search Eru.Core.dll for:
- `PackageManager.InstallPackage`
- `InstallPackageWithVerifier`
- Intent actions: `android.intent.action.INSTALL_PACKAGE`

#### System Control
```xml
<uses-permission android:name="android.permission.REBOOT"/>
<uses-permission android:name="android.permission.RECOVERY"/>
```

**Capabilities:**
- Reboot device programmatically
- Boot into recovery mode
- Potentially flash partitions via recovery

**Code to Analyze:**
- `PowerManager.Reboot()`
- Recovery boot commands

#### Settings Modification
```xml
<uses-permission android:name="android.permission.WRITE_SECURE_SETTINGS"/>
```

**Capabilities:**
- Modify system settings (ADB, developer options, etc.)
- Disable security features
- Change device configuration

## Exported Components

### PrivilegedModeReceiver

**Manifest:**
```xml
<receiver android:name="com.ifit.eru.PrivilegedModeReceiver"
          android:exported="true"/>
```

**Risk:** 🔴 **CRITICAL** - Exported receiver accepting privileged commands

**Location:** `ifit_apps/decompiled/eru/sources/com/ifit/eru/PrivilegedModeReceiver.java`

**Analysis Status:** 📋 Code analysis needed

**Key Questions:**
1. What intents does it accept?
2. What actions can be triggered?
3. Is there any authentication/validation?
4. Can it install packages?
5. Can it reboot device?
6. Can it execute shell commands?

**Testing:**
```bash
# Find intent actions accepted
adb shell dumpsys package com.ifit.eru | grep -A 20 "PrivilegedModeReceiver"

# Test sending intent (craft based on code analysis)
adb shell am broadcast -a com.ifit.eru.PRIVILEGED_ACTION --es param value
```

### UpdateReceiver

**Manifest:**
```xml
<receiver android:name="com.ifit.eru.UpdateReceiver"
          android:exported="true"/>
```

**Risk:** 🟡 **MEDIUM** - Handles system updates

**Potential Issues:**
- Could be triggered to install malicious "updates"
- May accept USB-based update packages
- Update verification may be weak

**Code to Analyze:**
- Update package verification
- Signature checking
- Source validation

## Attack Vectors

### Vector 1: Direct Intent to PrivilegedModeReceiver

**Attacker:** Malicious app on device
**Prerequisites:** None (if receiver truly unauthenticated)

**Steps:**
1. Malicious app installed (sideloaded by user)
2. App sends crafted intent to PrivilegedModeReceiver
3. ERU processes intent as system (UID 1000)
4. ERU installs attacker's APK system-wide
5. System compromise

**Proof of Concept (once intent format known):**
```kotlin
val intent = Intent("com.ifit.eru.INSTALL_PACKAGE")
intent.setPackage("com.ifit.eru")
intent.putExtra("path", "/sdcard/malicious.apk")
sendBroadcast(intent)
// If successful, malicious.apk installed as system app
```

### Vector 2: Standalone App Compromise → ERU Escalation

**Attacker:** Remote attacker via Standalone app vulnerability
**Prerequisites:** Vulnerability in Standalone app (XSS, code injection, etc.)

**Steps:**
1. Attacker exploits Standalone app (e.g., malicious workout file)
2. Gains code execution in Standalone context (UID 10XXX)
3. Uses Standalone's IPC channel to send commands to ERU
4. ERU executes commands as system (UID 1000)
5. System compromise

**IPC Analysis Needed:** See [../architecture/IPC_COMMUNICATION.md](../architecture/IPC_COMMUNICATION.md)

### Vector 3: USB Update Package Injection

**Attacker:** Physical access to USB port
**Prerequisites:** USB port accessible, update package format known

**Steps:**
1. Attacker creates malicious update package
2. Places on USB drive
3. Inserts USB drive into bike
4. ERU's UpdateReceiver detects update
5. If signature checking weak, malicious update installed
6. System compromise

**Analysis Needed:**
- Update package format
- Signature verification strength
- USB auto-detection behavior

## Code Analysis Targets

### Priority 1: PrivilegedModeReceiver

**File:** `ifit_apps/decompiled/eru/sources/com/ifit/eru/PrivilegedModeReceiver.java`

**Search for:**
```java
public void onReceive(Context context, Intent intent) {
    String action = intent.getAction();
    // What actions are accepted?
    // How are they processed?
    // Is there authentication?
}
```

**Key Methods:**
- `onReceive()` - Intent handling entry point
- Package installation methods
- Reboot/recovery methods
- Settings modification methods

### Priority 2: Eru.Core.dll Privileged Operations

**File:** `ifit_apps/decompiled/eru/resources/assemblies/Eru.Core.dll`

**Search for (in dnSpy):**
- `PackageManager`
- `InstallPackage`
- `PowerManager.Reboot`
- `Settings.Secure`
- `ShellCommand`
- `Runtime.exec`

**Classes to Examine:**
```
Com.Ifit.Eru.Core.System.PrivilegedOperations
Com.Ifit.Eru.Core.System.PackageInstaller
Com.Ifit.Eru.Core.System.UpdateManager
```

## Exploitation Scenarios

### Scenario A: App Installation

**Goal:** Install malicious app as system app

**Required Knowledge:**
- Intent action for installation
- Intent extras format
- APK path specification

**Expected Intent:**
```kotlin
Intent().apply {
    action = "com.ifit.eru.INSTALL_PACKAGE" // or similar
    setPackage("com.ifit.eru")
    putExtra("package_path", "/sdcard/malicious.apk")
    putExtra("install_flags", INSTALL_REPLACE_EXISTING)
}
```

**Result:** malicious.apk installed with system privileges

### Scenario B: Settings Modification

**Goal:** Enable ADB without user interaction

**Required Knowledge:**
- Intent action for settings
- Settings key/value format

**Expected Intent:**
```kotlin
Intent().apply {
    action = "com.ifit.eru.WRITE_SETTING"
    setPackage("com.ifit.eru")
    putExtra("key", "adb_enabled")
    putExtra("value", "1")
}
```

**Result:** ADB enabled, remote access possible

### Scenario C: Reboot to Recovery

**Goal:** Boot into recovery mode, flash malicious system image

**Required Knowledge:**
- Reboot intent action
- Recovery mode parameters

**Expected Intent:**
```kotlin
Intent().apply {
    action = "com.ifit.eru.REBOOT"
    setPackage("com.ifit.eru")
    putExtra("mode", "recovery")
}
```

**Result:** Device reboots into recovery, attacker can flash partitions

## Mitigation Recommendations

### For Icon/iFit

1. **Remove System UID**
   - ERU should NOT run as `android.uid.system`
   - Use individual permissions instead of blanket system access
   - Follow principle of least privilege

2. **Unexport PrivilegedModeReceiver**
   ```xml
   <receiver android:name="com.ifit.eru.PrivilegedModeReceiver"
             android:exported="false"
             android:permission="com.ifit.PRIVILEGED_PERMISSION"/>
   ```

3. **Add Authentication**
   - Verify sender of privileged intents
   - Use signature-protected broadcast permissions
   - Validate all parameters before executing

4. **Whitelist Operations**
   - Only allow known-good operations
   - Reject unexpected intent actions
   - Log all privileged operations

5. **Update Signing**
   - Strong signature verification for updates
   - Pin expected certificates
   - Reject unsigned or tampered updates

### For Researchers

1. **Isolated Testing**
   - Keep device on isolated network
   - Use dedicated test device
   - Back up before testing exploits

2. **Responsible Disclosure**
   - Report findings to Icon/iFit before public disclosure
   - Allow reasonable time for patching
   - Withhold exploit code until fixed

## Testing Procedure

### Phase 1: Reconnaissance

```bash
# 1. Verify ERU runs as system
adb shell ps | grep eru
# Expect: u0_system or UID 1000

# 2. Extract PrivilegedModeReceiver code
# Already decompiled in ifit_apps/decompiled/eru/

# 3. Analyze intent filters
adb shell dumpsys package com.ifit.eru | grep -A 30 "Receiver"
```

### Phase 2: Static Analysis

1. Open `PrivilegedModeReceiver.java` in editor
2. Find `onReceive()` method
3. Document all accepted intent actions
4. Identify privileged operations
5. Check for authentication logic

### Phase 3: Dynamic Testing (Safe)

```bash
# Test with non-destructive intent
adb shell am broadcast -a com.ifit.eru.GET_STATUS

# Monitor logcat for response
adb logcat | grep -E "PrivilegedMode|eru"
```

### Phase 4: Proof of Concept (Controlled)

**Only after full analysis and with backups:**
1. Create test APK (benign)
2. Send install intent to ERU
3. Verify if APK installed without user prompt
4. Document findings

**Do NOT test:**
- Reboot commands (until safe)
- System settings modification (until understood)
- Destructive operations

## Related Documentation

- **[PERMISSIONS_MATRIX.md](PERMISSIONS_MATRIX.md)** - All ERU permissions
- **[ATTACK_SURFACE.md](ATTACK_SURFACE.md)** - Other attack vectors
- **[../architecture/IPC_COMMUNICATION.md](../architecture/IPC_COMMUNICATION.md)** - Standalone ↔ ERU communication

## References

- Android System UID: https://source.android.com/docs/core/permissions
- Broadcast Security: https://developer.android.com/guide/components/broadcasts#security

---

**Status:** Code analysis in progress, PoC not yet developed
**Priority:** CRITICAL - High-impact vulnerability if exploitable
