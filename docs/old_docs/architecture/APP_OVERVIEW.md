# How the iFit Apps Work - Quick Summary

## Architecture Overview

The NordicTrack S22i uses a **three-layer Xamarin/C# application stack**:

```
┌─────────────────────────────────────────┐
│  com.ifit.launcher (Kiosk Launcher)     │  ← Locks device to iFit interface
└─────────────────────────────────────────┘
                  ↓
┌─────────────────────────────────────────┐
│  com.ifit.standalone (User App)         │  ← Workout UI & streaming
│  - Wolf.Core.dll     (Workout Logic)    │
│  - Wolf.Android.dll  (UI Views)         │
│  - Shire.Core.dll    (Shared Code)      │
│  - Shire.Android.dll (Android Layer)    │
└─────────────────────────────────────────┘
                  ↓ IPC
┌─────────────────────────────────────────┐
│  com.ifit.eru (System Service)          │  ← Hardware control (uid=system)
│  - Eru.Core.dll                         │
│  - Eru.Android.dll                      │
└─────────────────────────────────────────┘
                  ↓ USB
┌─────────────────────────────────────────┐
│  "Wolf" Hardware Controller (MCU)       │  ← Motor, resistance, incline
└─────────────────────────────────────────┘
```

## Component Breakdown

### 1. Launcher (com.ifit.launcher)
**Purpose**: Lock device into kiosk mode
- **Size**: 83 KB (minimal)
- **Runs**: On boot and when home button pressed
- **Function**: Prevents access to Android settings/other apps

### 2. Standalone App (com.ifit.standalone)
**Purpose**: User interface for workouts
- **Size**: 68 MB
- **Main DLLs**:
  - `Wolf.Core.dll` - Workout business logic
  - `Wolf.Android.dll` - Android UI views
  - `Shire.Core.dll` - Shared utilities
  - `Shire.Android.dll` - Android helpers

**Features**:
- User authentication & profiles
- Workout browsing & streaming
- Video playback (ExoPlayer)
- Maps integration (Google Maps)
- Facebook sharing
- In-workout stats display
- Pre/post workout screens

**Wolf Package**: Controls workout UI
- Speed/Resistance/Incline graphs
- In-workout views
- Stats/charts display
- Map tracking

### 3. ERU Service (com.ifit.eru)
**Purpose**: System control & hardware interface
- **Size**: 63 MB
- **Privilege Level**: **System UID** (highest privilege)
- **Main DLLs**:
  - `Eru.Core.dll` - Core device logic
  - `Eru.Android.dll` - Hardware interface

**Critical Functions**:
- USB communication with bike controller
- System updates (OTA & USB)
- Firmware updates (Brainboard/"Wolf" MCU)
- App installation/removal
- System setting control
- Boot/wake management

**Special Assets**:
- `busybox` (1.2MB) - Advanced Linux tools
- Update system for all components

## How They Communicate

### Standalone → ERU Communication
```
1. User adjusts resistance in Standalone app
2. Standalone sends intent/IPC to ERU service
3. ERU translates to USB command
4. USB sent to "Wolf" controller
5. Controller adjusts motor resistance
```

### ERU Privileged Operations
```
android:sharedUserId="android.uid.system"
```
This gives ERU:
- Install/uninstall any app
- Reboot device
- Modify all settings
- Mount filesystems
- Access recovery partition
- Control USB devices
- Full system access

## Hardware Control Flow

### Speed/Resistance/Incline Control

**UI Layer** (Wolf.Android.dll):
```
PreWorkoutSpeedPageView
PreWorkoutResistancePageView
PreWorkoutInclinePageView
↓
InWorkoutStatsView
↓
Control commands sent via IPC
```

**System Layer** (Eru.Core.dll):
```
USB Write Request
↓
Custom USB Device
↓
Wolf MCU Controller
↓
Motor/Resistance Hardware
```

### Update System

**Multi-layer updates managed by ERU**:
1. **App Updates**: `AppUpdateInstallService`
2. **System Updates**: `SystemUpdateInstallService`
3. **Firmware Updates**: `BrainboardUpdateInstallService`
4. **Workout Updates**: `BuiltInWorkoutUpdateInstallService`

**Update Sources**:
- Network: AWS S3 buckets
- USB: Auto-detect USB stick with updates

## Key Technologies

### Framework
- **Xamarin/Mono**: C# on Android
- **MVVMCross**: MVVM framework
- **.NET Runtime**: Mono VM (3MB+ runtime)

### Libraries
- **ExoPlayer**: HLS video streaming
- **Lottie**: Animations
- **OkHttp3**: Networking
- **MPAndroidChart**: Charting
- **Google Play Services**: Maps, authentication
- **AWS SDK**: Cloud storage/streaming
- **Facebook SDK**: Social integration

### Hardware Interface
- **USB Serial**: Communication with bike controller
- **Android USB Host API**: USB device management
- **Custom Protocol**: Proprietary commands to MCU

## Security Model

### Privilege Escalation
```
Any App on Device
    ↓
Send Intent: "com.ifit.eru.PRIVILEGEDMODE"
    ↓
ERU PrivilegedModeReceiver (exported!)
    ↓
System-level execution (uid=0)
```

**Risk**: Any app can potentially trigger ERU privileged operations

### Broadcast Receivers (ERU)
```
BOOT_COMPLETED        → Auto-start ERU
USB_DEVICE_ATTACHED   → USB update check
PRIVILEGEDMODE        → Privilege escalation point
PACKAGE_REPLACED      → Monitor app updates
```

## Data Flow Example: Starting a Workout

1. **User taps workout** in Standalone app
2. **Standalone loads** workout data from:
   - Local cache: `/sdcard/iFit/workouts/`
   - Or streams from iFit cloud (AWS)
3. **Video starts** playing via ExoPlayer (HLS stream)
4. **Workout commands** sent every second:
   ```
   Time: 0:30 → Resistance: 10, Incline: 2%, Speed: 15km/h
   Time: 0:31 → Resistance: 10, Incline: 2%, Speed: 15km/h
   Time: 0:32 → Resistance: 12, Incline: 3%, Speed: 16km/h
   ```
5. **Commands sent** to ERU via IPC
6. **ERU translates** to USB packets
7. **USB sent** to Wolf controller
8. **Controller adjusts** bike hardware
9. **Sensor data** flows back:
   ```
   USB ← Current RPM, Heart Rate, Actual Resistance
   ```
10. **Stats displayed** in Standalone UI

## File Locations

### On Device
```
/system/priv-app/com.ifit.eru-1.2.1.145/     # ERU system service
/system/priv-app/com.ifit.launcher-1.0.12/   # Kiosk launcher
/data/app/com.ifit.standalone-1/             # Main app

/sdcard/.ConsoleGuid                         # Device ID
/sdcard/eru/                                 # ERU logs
/sdcard/iFit/workouts/                       # Cached workouts
```

### Decompiled
```
eru_src/resources/assemblies/Eru.Core.dll        # Hardware control logic
eru_src/resources/assemblies/Eru.Android.dll     # Android hardware interface
standalone_src/resources/assemblies/Wolf.Core.dll    # Workout logic
standalone_src/resources/assemblies/Wolf.Android.dll # Workout UI
standalone_src/resources/assemblies/Shire.Core.dll   # Shared utilities
```

## Codename Meanings

- **Wolf**: Bike hardware controller MCU + Workout UI framework
- **Brainboard**: Secondary hardware controller (possibly display/input)
- **Shire**: Shared code library (probably "Shire" as in common land)
- **Eru**: Equipment Resource Unit (device management service)

## Network Endpoints

The apps communicate with:
- **iFit API**: `https://api.ifit.com` - User auth, workout library, progress sync, **update checks**
- **AWS S3 CDN**: `https://ifit-wolf.s3-cdn.ifit.com/` - Video streaming, **OTA updates**
  - App updates: `/android/builds/public/app-updates/*.apk`
- **Facebook Graph API**: Social features
- **Google Maps API**: Location-based workouts
- **Analytics**: Flurry, HockeyApp (crash reporting), LaunchDarkly (feature flags)

### Update Endpoints (Captured 2026-02-10)

**Primary API:**
- `https://api.ifit.com` - Update check API

**Download CDN:**
- `https://ifit-wolf.s3-cdn.ifit.com/android/builds/public/app-updates/`
  - `com.ifit.eru-2.1.1.1227.apk` (ERU update available)
  - `com.ifit.standalone-2.6.86.4458.apk` (Standalone update available)
  - `com.ifit.launcher-1.0.17.22.apk` (Launcher update available)

**⚠️ Security Note:** System firmware update also available (see [OTA_UPDATES.md](../security/OTA_UPDATES.md))

## Development Opportunities

### 1. Reverse USB Protocol
**Goal**: Control bike without iFit
- Decompile `Eru.Core.dll` with dnSpy/ILSpy
- Find USB read/write methods
- Document command structure
- Create open-source controller

### 2. Custom Workout App
**Goal**: Bypass iFit subscription
- Use documented USB protocol
- Create custom Android app
- Load local workout videos
- Control hardware directly

### 3. Unlock Device
**Goal**: Remove kiosk mode
- Uninstall launcher: `pm uninstall com.ifit.launcher`
- Disable ERU auto-start
- Install standard launcher
- Use as normal Android tablet

### 4. Workout Format
**Goal**: Custom workouts
- Analyze `/sdcard/iFit/workouts/` files
- Reverse format in `Wolf.Core.dll`
- Create workout file generator
- Load custom training programs

## Tools for Further Analysis

### .NET Decompilers
```bash
# dnSpy (Windows/Wine)
dnSpy Eru.Core.dll

# ILSpy (cross-platform)
ilspy Eru.Core.dll

# dotPeek (Windows)
dotpeek Eru.Core.dll
```

### USB Monitoring
```bash
# On device (requires root)
adb root
adb shell cat /sys/kernel/debug/usb/usbmon/0u

# Or use Wireshark with USB capture
```

### Network Analysis
```bash
# Setup proxy
adb shell settings put global http_proxy <ip>:8080

# Use Charles Proxy, mitmproxy, or Burp Suite
```

## Summary

**How it works**:
1. Launcher locks device to iFit
2. Standalone provides workout UI (Wolf framework)
3. ERU controls hardware via USB (Wolf MCU)
4. All built with Xamarin/C# (.NET on Android)

**Key insight**: The "Wolf" codename refers to BOTH the bike's hardware controller AND the workout UI framework - they're designed as a matched pair.

**Main reverse engineering target**: `Eru.Core.dll` - contains USB protocol to bike controller

**Biggest security issue**: ERU runs as system with exported broadcast receivers - potential privilege escalation vector
