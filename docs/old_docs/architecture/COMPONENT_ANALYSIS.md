# iFit Apps Analysis - NordicTrack S22i

Analysis of three iFit applications decompiled from device NN73Z115616.

## App Architecture

All three apps are built using **Xamarin/Mono** framework (C# compiled to Android):
- Business logic in .NET DLLs (assemblies/)
- Thin Java wrappers with MD5-hashed package names
- Native libraries for runtime (Mono VM)
- Large app sizes due to bundled .NET runtime

## 1. com.ifit.eru (Equipment Resource Unit)

**Version**: 1.2.1.145
**Type**: Privileged System Service
**Size**: 63 MB

### System Privileges

Runs with **system UID** (`android:sharedUserId="android.uid.system"`), granting maximum privileges:

```xml
Critical Permissions:
- INSTALL_PACKAGES     - Can install/uninstall apps
- REBOOT               - Can reboot device
- WRITE_SECURE_SETTINGS - Modify secure system settings
- WRITE_SETTINGS       - Modify all settings
- MANAGE_USB           - Control USB devices
- STATUS_BAR           - Control status bar
- SYSTEM_ALERT_WINDOW  - Draw over other apps
- RECOVERY             - Access recovery partition
- SET_TIME/SET_TIME_ZONE - Modify system time
- MOUNT_UNMOUNT_FILESYSTEMS - Mount/unmount storage
- ACCESS_CACHE_FILESYSTEM - Access cache partition
- DELETE_CACHE_FILES   - Clear cache files
- GET_TASKS            - See all running apps
```

### Functionality

**Primary Role**: Device management and system control service

**Broadcast Receivers**:
- `BootCompletedReceiver` - Auto-starts on device boot
- `UsbDeviceAttachedReceiver` - Monitors USB device connections
- `PrivilegedModeReceiver` - Handles privileged mode activation
- `PackageReplacedReceiver` - Monitors app updates
- `PeriodicIdleReceiver` - Periodic background tasks
- `WakeUpAppReceiver` - Wake up handling

**Key Components** (from manifest):
- `SplashScreen` - Main entry activity
- `UserUnlockedReceiver` - Responds to device unlock
- Update system (USB and network-based)
- Device administration

**Core DLLs**:
- `Eru.Core.dll` - Core equipment logic
- `Eru.Android.dll` - Android-specific implementations
- `AWSSDK.Core.dll`, `AWSSDK.S3.dll` - AWS cloud integration
- `DfuAndroidLibrary.dll` - Device Firmware Update
- `ExoPlayer.Core.dll`, `ExoPlayer.Hls.dll` - Video playback
- `LaunchDarkly.Client.dll` - Feature flags
- `HockeySDK.dll` - Crash reporting
- `Akavache.dll` - Local caching
- `Analytics.Xamarin.Pcl.dll` - Analytics

**Assets**:
- `busybox` - 1.2MB x86 busybox binary (for advanced shell commands)
- `loader_ring.json` - Lottie animations

### Hardware Interface

Based on string analysis:

**USB Communication**:
- `SendUsbWriteRequest` / `SendUsbReadRequest` - USB I/O
- `CustomUsbDevice` - Custom USB device handling
- `UsbStickService` - USB storage management
- `IsCorrectUsbStickMounted` - USB update verification
- USB update system (`OS20180331USBPrepareUpdateWolf`)

**Services**:
- `StartWolfWatchDogService` / `StopWolfWatchDogService` - Wolf device monitoring
- `StartTouchWatchService` - Touch screen monitoring
- `BrainboardUpdateInstallService` - Hardware controller updates
- System update management
- App update management
- Built-in workout updates

**"Wolf" References**: Appears to be the codename for the bike's hardware controller/MCU

### Update System

Multi-layer update architecture:
1. **System Updates** - Android OS updates
2. **App Updates** - iFit app updates
3. **Brainboard Updates** - Hardware controller firmware
4. **Built-in Workout Updates** - Pre-loaded workouts
5. **USB Transfer Service** - USB-based updates

## 2. com.ifit.standalone (Main iFit App)

**Version**: 2.2.8.364
**Type**: User Application
**Size**: 68 MB

### Permissions

Standard app permissions (not privileged):
- Storage (read/write)
- Network state
- WiFi state
- Bluetooth
- Location (coarse/fine)
- Phone state
- Recovery
- Set time/timezone
- Billing (in-app purchases)

### Functionality

**Primary Role**: User-facing workout and training application

**Key Features** (from activity names):
- Onboarding flow (email, profile, video, payment, setup)
- WelcomeView - Main entry point
- Workout preparation and execution
- Workout summary and statistics
- Safety key dialog
- Category browsing
- Facebook integration
- Maps integration (Google Maps API)
- In-app billing

**Core DLLs**:
- MVVMCross framework (Xamarin MVVM)
- ExoPlayer (video streaming)
- Lottie (animations)
- OkHttp3/Okio (networking)
- Facebook SDK
- Google Play Services
- Mapbox telemetry

**Integrations**:
- Facebook Login & Sharing
- Google Maps for location-based workouts
- Flurry Analytics
- In-app purchases (Google Play Billing)

### UI Structure

Built with MVVMCross (Model-View-ViewModel pattern):
- Xamarin-generated MD5 package names (e.g., `md59795d62579a1e2616005f7a50cf6e392`)
- Fragment-based activities
- Material Design theme
- Landscape orientation (tablet-optimized)
- Minimum width: 480dp (tablet/console screen)

**Key Views**:
- `WelcomeView` - App entry
- `OnBoardingSetupView` - Device setup
- `PreparingWorkoutView` - Pre-workout screen
- `InWorkoutView` - Active workout screen
- `WorkoutSummaryView` - Post-workout stats
- `CategoryView` - Browse workouts
- `SafetyKeyDialogView` - Emergency stop dialog

### Network Communication

- iFit cloud services for:
  - User authentication
  - Workout streaming
  - Progress syncing
  - Social features
- Terms of Service: https://www.ifit.com/termsofuse
- Privacy Policy: https://www.ifit.com/privacypolicy
- AWS integration (likely for video streaming)

## 3. com.ifit.launcher (Custom Launcher)

**Version**: 1.0.12
**Type**: System Launcher
**Size**: 83 KB (minimal)

### Functionality

**Primary Role**: Replace default Android launcher with iFit interface

**Permissions**:
- RECEIVE_BOOT_COMPLETED - Start on boot
- KILL_BACKGROUND_PROCESSES - Manage running apps

**Intent Filters**:
```xml
<action android:name="android.intent.action.MAIN"/>
<category android:name="android.intent.category.HOME"/>
<category android:name="android.intent.category.LAUNCHER"/>
<category android:name="android.intent.category.DEFAULT"/>
```

This makes it the **default home screen** when the user presses home button.

**Features**:
- Direct Boot Aware - Works before device unlock
- Fullscreen theme - No status bar or navigation
- Simple activity (`MainActivity`) - Likely just launches standalone app
- Prevents access to normal Android UI

## App Communication & Architecture

### Inter-Process Communication

**ERU ↔ Standalone**:
1. ERU runs as system service with privileged access
2. Standalone communicates with ERU for:
   - Hardware control (motor, resistance, incline)
   - System settings
   - Updates
   - Device administration
3. Likely uses Android Intents, Broadcasts, or IPC mechanisms

**Launcher → Standalone**:
- Launcher auto-launches Standalone app on boot/home press
- Prevents user from accessing other apps or settings
- Creates locked-down kiosk mode

### Data Flow

```
User
  ↓
Standalone App (User Interface)
  ↓
ERU Service (Hardware/System Control)
  ↓
USB Device ("Wolf" Controller)
  ↓
Bike Hardware (Motor, Resistance, Display)
```

### Update Flow

```
1. Check for Updates (ERU background service)
   - Network: AWS S3 / iFit API
   - USB: Detect USB stick with updates

2. Download/Stage Updates
   - System updates
   - App updates
   - Firmware updates (Brainboard/Wolf)

3. Install Updates
   - App updates: Use INSTALL_PACKAGES permission
   - System updates: Reboot to recovery
   - Firmware: USB communication to MCU

4. Reboot if needed (ERU has REBOOT permission)
```

## Security Findings

### Privilege Escalation Path

1. ERU runs as system UID - highest privilege
2. ERU can install arbitrary packages
3. Any app on device can send intents to ERU
4. `PrivilegedModeReceiver` is exported and enabled
5. Potential for privilege escalation via malicious intents

### Attack Surface

**ERU Service**:
- Exported broadcast receiver `PrivilegedModeReceiver`
- Intent action: `com.ifit.eru.PRIVILEGEDMODE`
- Can be triggered by any app with matching intent

**USB Interface**:
- Automatic USB update system
- "Correct USB stick" validation may be bypassable
- Could load malicious updates from USB

**Launcher Lock-in**:
- Users cannot easily access Android settings
- Prevents security updates or app removal
- Creates e-waste if iFit service discontinued

### Interesting Observations

1. **Busybox Inclusion**: ERU ships with x86 busybox (1.2MB) - advanced shell tools
2. **"Wolf" Hardware**: Internal codename for bike's main controller board
3. **Brainboard**: Another hardware component (secondary MCU?)
4. **Touch Watchdog**: Monitors touchscreen responsiveness
5. **Feature Flags**: LaunchDarkly integration for A/B testing
6. **Crash Reporting**: HockeySDK for crash analytics

## Reverse Engineering Next Steps

### To Understand Hardware Protocol:

1. **Decompile .NET DLLs**:
   ```bash
   # Use dnSpy, ILSpy, or dotPeek
   dnspy Eru.Core.dll
   ```

2. **Find USB Communication Code**:
   - Search for USB read/write methods
   - Identify message format/protocol
   - Document command structure

3. **Monitor USB Traffic**:
   ```bash
   # On device with ADB
   adb shell cat /sys/kernel/debug/usb/usbmon/0u
   ```

4. **Find Hardware Commands**:
   - Motor speed control
   - Incline adjustment
   - Resistance control
   - Display control

### To Extract API Endpoints:

1. **Analyze Network Traffic**:
   ```bash
   # Use Charles Proxy, mitmproxy, or Wireshark
   adb shell settings put global http_proxy <proxy_ip>:8080
   ```

2. **Extract Strings from DLLs**:
   ```bash
   strings Eru.Core.dll | grep -E "api|endpoint|service"
   ```

3. **Decompile and Search**:
   - Look for HttpClient usage
   - Find API base URLs
   - Document authentication methods

### To Create Custom Workouts:

1. **Find Workout Format**:
   - Check `/sdcard/iFit/workouts/`
   - Decompile workout loading code
   - Reverse engineer format

2. **Bypass Authentication**:
   - Find iFit API authentication
   - Extract API keys/tokens
   - Create custom server

3. **Control Hardware Directly**:
   - Bypass ERU service
   - Send USB commands directly
   - Create custom workout controller

## Development Potential

With root access and source code understanding:

1. **Custom Workout App**:
   - Bypass iFit subscription
   - Load custom workout videos
   - Control bike hardware directly

2. **Open Source Firmware**:
   - Replace ERU with open alternative
   - Community-driven features
   - Remove dependency on iFit cloud

3. **Alternative Uses**:
   - Turn bike into regular Android tablet
   - Install Zwift or other cycling apps
   - Use as general media device

4. **Hardware Research**:
   - Understand bike control protocol
   - Create diagnostic tools
   - Repair/troubleshooting tools

## Files of Interest

### For Further Analysis:
```
eru_src/resources/assemblies/Eru.Core.dll         # Core business logic
eru_src/resources/assemblies/Eru.Android.dll      # Hardware interface
eru_src/resources/assemblies/AWSSDK.*.dll         # Cloud integration
eru_src/resources/assets/busybox                  # Advanced shell tools
standalone_src/resources/assemblies/*.dll          # Workout logic
```

### Configuration Files:
```
/sdcard/.ConsoleGuid                              # Device identifier
/sdcard/eru/*.txt                                 # Log files
/sdcard/iFit/workouts/                           # Local workout cache
```

## Conclusion

The iFit system uses a three-tier architecture:

1. **Launcher**: Kiosk mode lockdown
2. **Standalone**: User interface and workouts
3. **ERU**: System service with hardware control

The ERU service is the key component - it runs with system privileges and handles all hardware communication via USB to the "Wolf" controller. The Standalone app provides the UI but relies on ERU for actual bike control.

With root access and decompiled source, it would be possible to:
- Understand the USB protocol to the bike controller
- Create custom workout applications
- Bypass iFit subscription requirements
- Repurpose the hardware for other uses

The main reverse engineering target should be `Eru.Core.dll` and `Eru.Android.dll` which contain the hardware control logic.
