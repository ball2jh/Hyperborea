# Security Analysis Overview

> **Security research findings for NordicTrack S22i iFit platform**

This directory documents security-relevant findings, permissions analysis, and potential attack vectors in the iFit Android platform.

## Executive Summary

### Critical Findings

#### 🔴 CRITICAL: System Firmware Update Threat
**Risk:** Loss of root access and research capabilities

A system firmware update (20190521 → MGA1_20210616) is available that will likely:
- Remove `adb root` access
- Enable SELinux enforcement
- Lock bootloader
- Prevent system modifications

**Status:** ✅ BLOCKED - Multiple protection layers active
**Protection:** [PROTECTION.md](../guides/PROTECTION.md)
**Technical Details:** [OTA_UPDATES.md](OTA_UPDATES.md)

#### 🔴 HIGH: ERU System UID Privileges
**Risk:** Critical privilege escalation potential

The iFit ERU service runs with `android.uid.system`, granting it:
- Full system partition access
- Package installation capabilities
- Direct hardware access
- Recovery partition control

**Status:** 🔄 Under investigation
**Documentation:** [PRIVILEGE_ESCALATION.md](PRIVILEGE_ESCALATION.md)

#### 🔴 HIGH: Exported PrivilegedModeReceiver
**Risk:** Potential unauthorized privileged operations

ERU exposes `PrivilegedModeReceiver` as an exported broadcast receiver, allowing any app to send intents that trigger privileged operations without additional authentication.

**Status:** 🔄 Under investigation
**Documentation:** [ATTACK_SURFACE.md](ATTACK_SURFACE.md)

#### 🟡 MEDIUM: No SELinux Enforcement
**Risk:** Policy bypass

SELinux is in **permissive mode** on this device:
```bash
adb shell getenforce
# Output: Permissive
```

This removes a critical security boundary. Successful exploits face no SELinux restrictions.

**Status:** ✅ Confirmed

#### 🟡 MEDIUM: Network ADB Without Authentication
**Risk:** Remote unauthorized access

ADB is exposed on network (port 5555) without authentication. Any device on the local network can connect and gain root access via `adb root`.

**Status:** ✅ Confirmed
**Mitigation:** Keep device on isolated network

### Attack Surface Summary

| Component | Risk | Description |
|-----------|------|-------------|
| ERU System Privileges | 🔴 Critical | `android.uid.system` grants excessive capabilities |
| PrivilegedModeReceiver | 🔴 High | Exported receiver accepts privileged commands |
| USB Update System | 🟡 Medium | Potential for malicious firmware injection |
| IPC Communication | 🟡 Medium | Standalone ↔ ERU intents may be interceptable |
| Network ADB | 🟡 Medium | Unauthenticated remote root access |
| Wolf USB Protocol | 🟠 Low-Med | Hardware command injection possible |

## Documentation

### Permission Analysis
- **[PERMISSIONS_MATRIX.md](PERMISSIONS_MATRIX.md)** - Complete permission mapping for all apps
  - ERU permissions (system UID + dangerous permissions)
  - Standalone permissions (user-level)
  - Launcher permissions (minimal)

### Privilege Escalation
- **[PRIVILEGE_ESCALATION.md](PRIVILEGE_ESCALATION.md)** - Detailed analysis of ERU's system-level access
  - `android.uid.system` implications
  - PrivilegedModeReceiver broadcast handling
  - Package installation capabilities
  - Recovery/reboot control

### OTA Update System
- **[OTA_UPDATES.md](OTA_UPDATES.md)** - 🔴 CRITICAL analysis of update mechanism
  - System firmware update threat (will remove root access)
  - API endpoints and update infrastructure
  - Update blocking strategies
  - Captured update URLs and versions

### Attack Surface & Threat Model
Quick reference below, detailed analysis in PRIVILEGE_ESCALATION.md

## Key Security Characteristics

### ERU (Elevated Privilege Service)

**Manifest Location:** `ifit_apps/decompiled/eru/resources/AndroidManifest.xml`

```xml
<manifest xmlns:android="http://schemas.android.com/apk/v1/apk-res/android"
    package="com.ifit.eru"
    android:sharedUserId="android.uid.system">

    <!-- Dangerous Permissions -->
    <uses-permission android:name="android.permission.INSTALL_PACKAGES"/>
    <uses-permission android:name="android.permission.REBOOT"/>
    <uses-permission android:name="android.permission.RECOVERY"/>
    <uses-permission android:name="android.permission.WRITE_SECURE_SETTINGS"/>
    <!-- ... many more ... -->

    <!-- Exported Components -->
    <receiver android:name="com.ifit.eru.PrivilegedModeReceiver" android:exported="true"/>
</manifest>
```

**Critical Implications:**
1. **System UID** (`android.uid.system`) grants access to:
   - `/system` partition (read/write when remounted)
   - System databases (settings, packages)
   - All app data directories
   - Hardware devices without permission checks

2. **INSTALL_PACKAGES** allows ERU to:
   - Install APKs without user confirmation
   - Replace existing apps (including system apps)
   - Downgrade app versions (potential rollback attacks)

3. **REBOOT/RECOVERY** allows ERU to:
   - Reboot device programmatically
   - Boot into recovery mode
   - Potentially flash new system images

### Standalone (User-Level App)

**Manifest Location:** `ifit_apps/decompiled/standalone/resources/AndroidManifest.xml`

The main iFit app runs with normal user privileges but:
- Communicates with ERU via IPC (intents/broadcasts)
- Can request privileged operations through ERU
- Has network access (downloads workouts)
- Reads/writes user workout data

**Security Boundary:**
- Standalone → ERU communication is the critical security boundary
- Any vulnerability in ERU's IPC handling could elevate Standalone's privileges
- Compromised Standalone could exploit ERU to gain system access

### Network Security

#### Open Network ADB
```bash
# No authentication required
adb connect 192.168.1.177:5555  # Connects immediately
adb root                         # Grants root without password
```

**Mitigation:**
- Device should be on isolated/trusted network only
- Consider disabling network ADB (requires `setprop` in boot)

#### WiFi Configuration
- Device uses standard Android WiFi stack
- No additional authentication for iFit services
- Workouts downloaded over HTTPS (TLS verified)

## Investigation Status

### ✅ Completed Analysis
- [x] Manifest permission extraction (all apps)
- [x] Exported component enumeration
- [x] System UID privilege confirmation
- [x] SELinux status verification
- [x] Network ADB vulnerability confirmation

### 🔄 In Progress
- [ ] PrivilegedModeReceiver intent analysis
- [ ] IPC message flow mapping
- [ ] ERU → System boundary testing
- [ ] USB protocol command injection research

### 📋 Planned Research
- [ ] Proof-of-concept: Standalone → ERU privilege escalation
- [ ] USB firmware malicious injection testing
- [ ] Recovery partition access via ERU
- [ ] Custom app installation via ERU INSTALL_PACKAGES

## Attack Surface Map

| Component | Risk | Description |
|-----------|------|-------------|
| **PrivilegedModeReceiver** | 🔴 Critical | Exported, accepts privileged commands |
| **Network ADB** | 🔴 Critical | Port 5555, no authentication |
| **IPC (Standalone→ERU)** | 🔴 High | Potential privilege escalation |
| **USB Updates** | 🟡 Medium | Malicious firmware injection |
| **Wolf MCU Commands** | 🟠 Medium | Hardware command injection |
| **Deep Links** | 🟡 Medium | `ifit://` parameter injection |

## Threat Scenarios

### 1. Network ADB Exploitation
**Likelihood:** High | **Impact:** Critical
```
Attacker on WiFi → adb connect → adb root → Full device control
```
**Mitigation:** Isolated network only

### 2. Malicious App → ERU Escalation
**Likelihood:** Medium | **Impact:** Critical
```
Sideloaded app → Intent to PrivilegedModeReceiver → System compromise
```
**Mitigation:** Fix ERU authentication (see PRIVILEGE_ESCALATION.md)

### 3. Compromised Workout File
**Likelihood:** Low | **Impact:** High
```
Malicious workout → Parser exploit → Standalone compromise → ERU escalation
```
**Mitigation:** Input validation, update signing

## Quick Testing

```bash
# Verify ERU runs as system UID
adb shell ps | grep eru
# Expected: u0_system (UID 1000)

# Monitor ERU intents
adb shell logcat | grep "PrivilegedModeReceiver"

# Check exported components
adb shell dumpsys package com.ifit.eru | grep -A 5 "Receiver"
```

## Related Documentation

- **[PERMISSIONS_MATRIX.md](PERMISSIONS_MATRIX.md)** - Complete permission analysis
- **[PRIVILEGE_ESCALATION.md](PRIVILEGE_ESCALATION.md)** - ERU exploitation details
- **[../architecture/COMPONENT_ANALYSIS.md](../architecture/COMPONENT_ANALYSIS.md)** - App architecture
- **[../architecture/IPC_COMMUNICATION.md](../architecture/IPC_COMMUNICATION.md)** - IPC analysis

---

**Last Updated:** 2026-02-10
**Status:** Personal research on owned hardware | Multiple CRITICAL findings documented
