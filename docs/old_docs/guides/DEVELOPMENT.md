# NordicTrack S22i - Development Guide

This document describes how to work with the NordicTrack S22i bike for development and research. For detailed device specifications, see [DEVICE.md](../DEVICE.md).

## Quick Reference

**Device IP**: 192.168.1.177:5555
**Serial**: NN73Z115616
**Platform**: Nexell S5P6818 (ARM64)
**Android**: 7.1.2 (API 25)
**Kernel**: 4.4.83+

## Documentation

For complete project overview and all documentation, see:
- **[README.md](../../README.md)** - Project overview and navigation hub

**Key Documentation:**
- **Device Specs:** [DEVICE.md](../DEVICE.md) - Hardware and software details
- **Version History:** [VERSIONS.md](../VERSIONS.md) - App and firmware versions
- **Protection:** [PROTECTION.md](PROTECTION.md) - Update blocking setup
- **Updates:** [UPDATES.md](UPDATES.md) - Manual update procedures
- **Architecture:** [architecture/](../architecture/) - App architecture and IPC
- **Wolf:** [wolf/](../wolf/) - Hardware protocol and UI framework
- **Security:** [security/](../security/) - Permissions and vulnerabilities
- **Reverse Engineering:** [reverse_engineering/](../reverse_engineering/) - DLL analysis

## Getting Started

### Connect to Device
```bash
# Connect via network ADB
adb connect 192.168.1.177:5555

# Verify connection
adb devices

# Get root access
adb root
adb shell
```

### Check Device Status
```bash
# Current user (should be shell by default, root after adb root)
adb shell whoami

# Currently running app
adb shell dumpsys activity | grep -A 3 "mResumedActivity"

# Display info
adb shell dumpsys display | grep mBaseDisplayInfo

# WiFi status
adb shell dumpsys wifi | grep mNetworkInfo
```

## File Locations

### System Apps
```
/system/priv-app/com.ifit.eru-1.2.1.145/     # iFit ERU system service
/system/priv-app/com.ifit.launcher-1.0.12/   # iFit launcher
/system/xbin/su                               # Root binary
```

### User Apps
```
/data/app/com.ifit.standalone-1/base.apk     # Main iFit app (v2.2.8.364)
```

### User Data
```
/sdcard/                                     # Main user storage
/sdcard/eru/                                 # ERU logs and data
/sdcard/iFit/workouts/                       # Workout data
/sdcard/.ConsoleGuid                         # Device GUID
```

### Working Directory
```
/data/local/tmp/                             # Temp files, binary staging
```

## Common Tasks

### Deploy and Run Binaries

```bash
# Compile for target (from macOS)
$NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin/aarch64-linux-android21-clang \
  -static -o mybinary mybinary.c

# Deploy
adb push mybinary /data/local/tmp/
adb shell chmod 755 /data/local/tmp/mybinary

# Run
adb shell /data/local/tmp/mybinary

# Run as root
adb root
adb shell /data/local/tmp/mybinary
```

**NDK Path**: `/opt/homebrew/Caskroom/android-ndk/29/AndroidNDK14206865.app/Contents/NDK`

### View Logs

```bash
# View ERU API logs
adb shell cat /sdcard/eru/Api_*.txt

# View startup logs
adb shell cat /sdcard/eru/Startup_*.txt

# View device admin logs
adb shell cat /sdcard/eru/DeviceAdmin_*.txt

# Real-time logcat
adb logcat

# Filter for iFit apps
adb logcat | grep -E "ifit|eru"
```

### Package Management

```bash
# List installed packages
adb shell pm list packages

# List third-party packages only
adb shell pm list packages -3

# Get package info
adb shell dumpsys package com.ifit.standalone

# Get APK path
adb shell pm path com.ifit.standalone

# Pull APK
adb pull $(adb shell pm path com.ifit.standalone | cut -d: -f2)
```

### App Control

```bash
# Stop iFit app
adb shell am force-stop com.ifit.standalone

# Start iFit app
adb shell am start -n com.ifit.standalone/md59795d62579a1e2616005f7a50cf6e392.WelcomeView

# Clear app data
adb shell pm clear com.ifit.standalone

# Check running processes
adb shell ps | grep ifit
```

### File Operations

```bash
# Pull files
adb pull /sdcard/eru/ ./eru_logs/
adb pull /sdcard/.ConsoleGuid

# Push files
adb push myfile.txt /sdcard/
adb push myapp.apk /data/local/tmp/

# Edit files (pull, edit, push back)
adb pull /system/build.prop
# ... edit locally ...
adb root
adb remount
adb push build.prop /system/
```

### Screen Interaction

```bash
# Take screenshot
adb shell screencap -p /sdcard/screenshot.png
adb pull /sdcard/screenshot.png

# Record screen
adb shell screenrecord /sdcard/recording.mp4
# ... do stuff ...
# Ctrl+C to stop
adb pull /sdcard/recording.mp4

# Simulate touch (x, y coordinates)
adb shell input tap 960 540

# Simulate swipe
adb shell input swipe 100 500 100 100

# Send text input
adb shell input text "Hello"

# Press keys
adb shell input keyevent KEYCODE_HOME
adb shell input keyevent KEYCODE_BACK
```

### System Information

```bash
# Get all properties
adb shell getprop

# Specific properties
adb shell getprop ro.build.version.release    # Android version
adb shell getprop ro.product.model             # Device model
adb shell getprop ro.serialno                  # Serial number
adb shell getprop ro.board.platform            # SoC platform

# Memory info
adb shell cat /proc/meminfo

# CPU info
adb shell cat /proc/cpuinfo

# Disk usage
adb shell df -h

# Storage details
adb shell dumpsys diskstats
```

### Network Operations

```bash
# Network interfaces
adb shell ip addr

# Network statistics
adb shell netstat -tulpn

# WiFi info
adb shell dumpsys wifi | grep -A 10 mNetworkInfo

# Ping test
adb shell ping -c 4 8.8.8.8
```

## Development Workflow

### Testing Native Code

1. **Compile** on macOS using NDK
2. **Push** to `/data/local/tmp/`
3. **Make executable** with `chmod 755`
4. **Run** from adb shell
5. **Debug** with logcat or stderr output

### Testing Android Apps

1. **Build** APK using Android Studio or Gradle
2. **Install**: `adb install -r myapp.apk`
3. **Launch**: `adb shell am start -n com.example.myapp/.MainActivity`
4. **Debug**: `adb logcat | grep MyApp`
5. **Uninstall**: `adb uninstall com.example.myapp`

### Persistent Changes

```bash
# Remount system as RW (already is, but for reference)
adb root
adb remount

# Modify system files
adb push myfile /system/...

# Reboot to apply changes
adb reboot

# For faster testing without reboot:
# - Use /data/local/tmp/ for binaries
# - Use /sdcard/ for data files
# - Install apps normally (no system modification)
```

## Useful Dumpsys Services

```bash
# Activity manager (what's running)
adb shell dumpsys activity

# Package manager (installed apps)
adb shell dumpsys package

# Window manager (display state)
adb shell dumpsys window

# Battery/power
adb shell dumpsys battery

# Display
adb shell dumpsys display

# Input devices
adb shell dumpsys input

# WiFi
adb shell dumpsys wifi

# Bluetooth
adb shell dumpsys bluetooth_manager
```

## Troubleshooting

### Connection Issues

```bash
# Device not showing up
adb kill-server
adb start-server
adb connect 192.168.1.177:5555

# Check if port is accessible
nc -zv 192.168.1.177 5555

# Restart ADB on device (requires root)
adb root
adb shell stop adbd
adb shell start adbd
```

### Permission Denied

```bash
# Get root access
adb root

# If that fails, check if su is available
adb shell su -c "whoami"

# Remount system as RW
adb remount
```

### App Won't Start

```bash
# Check if app exists
adb shell pm list packages | grep myapp

# Check for errors
adb logcat | grep -E "ERROR|Exception"

# Clear app data and retry
adb shell pm clear com.example.myapp
```

## Important Notes

- **Root Access**: Run `adb root` to get root shell access
- **System Partition**: Mounted read-write, permanent modifications possible
- **SELinux**: In permissive mode, no policy enforcement
- **Network ADB**: No authentication required, keep device on isolated network
- **Backups**: Consider backing up partitions before major modifications

## Additional Resources

- Device specifications: [S22I_DEVICE_INFO.md](S22I_DEVICE_INFO.md)
- Memory/offsets: `/memory/MEMORY.md` (exploit development notes)
- NDK documentation: https://developer.android.com/ndk
- ADB reference: https://developer.android.com/tools/adb
