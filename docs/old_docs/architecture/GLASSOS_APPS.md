# GlassOS App Details

> **Per-app breakdown of the GlassOS microservices architecture discovered 2026-02-12**

**Last Updated:** 2026-02-12
**Device:** NN73Z115616 (NordicTrack S22i)

---

## Overview

| App | Version | APK Size | Packages | Classes | Language | Status |
|-----|---------|----------|----------|---------|----------|--------|
| glassos_service | v7.10.3 | 44MB | 68 | ~11,598 | Kotlin | RUNNING |
| gandalf | v1.34.16 | 48MB | 65 | ~9,309 | Kotlin | RUNNING |
| arda | v1.35.15 | 96MB | 100 | est. ~15,000+ | Kotlin | Installed |
| rivendell | v1.42.14 | 57MB | 93 | est. ~10,000+ | Kotlin | Installed |
| mithlond | v1.38.21 | 75MB | 80 | est. ~12,000+ | Kotlin | RUNNING |
| launcher | v2.38.12 | 20MB | 30 | est. ~3,000+ | Kotlin | RUNNING |
| eru | v2.27.18 | 22MB | 7 | est. ~4,000+ | Kotlin | RUNNING |
| standalone | v2.6.90 | 112MB | N/A | N/A | Xamarin/C# | Installed |

---

## 1. glassos_service (v7.10.3)

**Package:** `com.ifit.glassos_service`
**APK Size:** 44MB
**Language:** Kotlin (native Android)
**com.ifit Packages:** 68
**Estimated Classes:** ~11,598
**Status:** RUNNING on device

### Purpose

The core OS service layer for the GlassOS platform. Provides gRPC services consumed by all other GlassOS apps. This is the backbone that makes the microservices architecture work -- every client app communicates through glassos_service rather than talking directly to each other or to hardware.

### Discovered Services

**Console Hardware:**
- `ConsoleService` - Hardware console state management
- `BrightnessService` - Display brightness control
- `VolumeService` - Audio volume control
- `FanStateService` - Fan speed control
- `LightingService` - Console LED lighting control

**System Configuration:**
- `DemoModeService` - Demo/retail mode toggle
- `TimeZoneService` - System timezone management
- `SystemUnitsService` - Metric/imperial unit selection
- `VideoQualityService` - Stream quality settings
- `MaxTimeService` - Workout time limits
- `MaxSpeedService` - Speed limits

**Platform Services:**
- `BluetoothService` - BLE device management
- `HdmiSoundService` - HDMI audio routing
- `AppNavigationService` - Cross-app navigation coordination
- `AppStoreService` - App installation and updates
- `ClubService` - Gym club features
- `EGymService` - eGym integration
- `FeatureGateService` - LaunchDarkly feature flags
- `UserActivityService` - User activity tracking
- `UserAuthService` - Authentication service

**Workout Programming:**
- `glassos_programmedworkout_core` - Workout program engine
- `glassos_programmedworkout_activepulse` - Heart rate-based auto-adjust
- `glassos_programmedworkout_map` - Map-based workout routing
- `glassos_programmedworkout_network` - Workout network sync
- `glassos_programmedworkout_service` - Workout service layer
- `glassos_programmedworkout_smartadjust` - AI-based difficulty adjustment

**Sindarin (Hardware):**
- `glassos_sindarin_arbitrator` - FitPro1 multi-app device arbitration
- Full Sindarin stack (FitPro1, FitPro2, USB, NFC, BLE, etc.)

### Key Packages

```
com.ifit.glassos_service/              # Core service implementation
com.ifit.glassos_appnavigation_service/
com.ifit.glassos_appstore_service/
com.ifit.glassos_bluetooth_service/
com.ifit.glassos_club_service/
com.ifit.glassos_egym_service/
com.ifit.glassos_featuregate_service/
com.ifit.glassos_hdmisound_service/
com.ifit.glassos_lighting_service/
com.ifit.glassos_programmedworkout_*/   # 6 workout modules
com.ifit.glassos_sindarin_*/            # 10 Sindarin HAL modules
com.ifit.glassos_standalone_engine/
com.ifit.glassos_useractivity_service/
com.ifit.glassos_userauth_service/
com.ifit.glassos_workout_core/
com.ifit.val_*/                         # 20+ Valinor UI modules
```

### Decompiled Source

```
ifit_apps/glassos_service/v7.10.3/
├── base.apk        (44MB)
├── jadx.log
└── decompiled/
    ├── sources/     # Kotlin/Java source
    └── resources/   # AndroidManifest.xml, assets
```

---

## 2. gandalf (v1.34.16)

**Package:** `com.ifit.gandalf`
**APK Size:** 48MB
**Language:** Kotlin (native Android)
**com.ifit Packages:** 65
**Estimated Classes:** ~9,309
**Status:** RUNNING on device (foreground)

### Purpose

The workout engine app -- a complete Kotlin rewrite of the main workout functionality that was previously in the Xamarin standalone app. Gandalf owns the workout session lifecycle and directly communicates with the MCU hardware through its embedded Sindarin HAL.

### Key Components

**Activities:**
- `MainActivity` - Primary workout interface
- `SplashScreenActivity` - Boot/load screen

**Services:**
- `GandalfPreloaderService` - Foreground service for preloading workout data

**Hardware Integration:**
- Embedded full Sindarin HAL stack (FitPro v1, FitPro v2, USB, NFC, BLE, USB Audio, VirtualConsole, VTAP)
- USB device monitoring with ReformRx support (VID `0x0403`, PID `0x6001`)
- IdleModeLockout management (field 95) -- same pattern as C# `PreparingWorkoutViewModel`
- `glassos_sindarin_arbitrator` for multi-app device sharing

**Build Configuration:**
- Build variants: `PROD`, `EXTERNAL`, `NOT_CHINA`
- LaunchDarkly feature flags

### Valinor UI Modules (50+)

```
val_accountselection_feature    val_login_feature
val_alert_dialog                val_measure_util
val_analytics_amplitude         val_membership_service
val_analytics_firebase          val_metadata_manager
val_analytics_service           val_metric_formatters
val_betaoptin_feature           val_navigation_service
val_coil_integration            val_overlaydialog_core
val_connectivity_services       val_performancemonitor_*
val_core_util                   val_productnews_service
val_coreinfo_services           val_retrofit_integration
val_coreui_components           val_sindarin_calories
val_crashreporter_*             val_sindarin_lookup
val_ecosystem_*                 val_video_framework
val_environment_service         val_walkup_feature
val_epoxy_integration           val_webviews_framework
val_event_bus                   val_workoutsession_handler
val_featureflag_*               valinor_annotation
val_httpclient_service
val_key_provider
val_link_service
val_local_network
val_local_storage
val_logging_service
val_login_authzero
```

### Decompiled Source

```
ifit_apps/gandalf/v1.34.16/
├── base.apk        (48MB)
├── jadx.log
└── decompiled/
    ├── sources/com/ifit/gandalf/        # App-specific code
    ├── sources/com/ifit/glassos_*/      # 16 shared glassos modules
    ├── sources/com/ifit/val_*/          # 50+ Valinor UI modules
    └── resources/
```

---

## 3. arda (v1.35.15)

**Package:** `com.ifit.arda`
**APK Size:** 96MB (largest GlassOS app)
**Language:** Kotlin (native Android)
**com.ifit Packages:** 100 (most of any app)
**Status:** Installed, NOT currently running

### Purpose

The primary user-facing UI application. Contains the full settings interface, machine setup, screensaver, sleep mode, walk-up screen, and comprehensive workout management. Uses Compose-based UI with both `flex_engine` and `standalone_engine` rendering backends. This is the most feature-rich app in the GlassOS suite.

### Activities

| Activity | Purpose |
|----------|---------|
| `MainActivity` | Primary home screen |
| `WalkUpScreenActivity` | Walk-up / attract screen |
| `ScreensaverActivity` | Idle screensaver |
| `SleepActivity` | Low-power sleep mode |
| `MachineSetupActivity` | Initial machine configuration |
| `SettingsActivity` | User settings |
| `NoConnectionActivity` | Network error handler |
| `ProductNewsVideoActivity` | Marketing/news video player |
| `FeedbackActivity` | User feedback form |

### Key Packages (100 total)

**App-specific:**
```
com.ifit.arda/                          # Core app code
com.ifit.glassos_flex_engine/           # Compose flex rendering
com.ifit.glassos_standalone_engine/     # Standalone compatibility
com.ifit.glassos_workout_core/          # Workout engine
com.ifit.glassos_userauth_service/      # Auth service
com.ifit.glassos_useractivity_service/  # Activity tracking
```

**Unique Valinor modules (not in other apps):**
```
val_appstore_component          # App store UI
val_demomode_workouts           # Demo mode workout content
val_glide_integration           # Image loading (Glide)
val_ifithome_service            # iFit Home integration
val_machinesetup_feature        # Machine setup wizard
val_noconnection_feature        # No-connection UI
val_oneclickconversion_feature  # Subscription conversion
val_pinlock_feature             # PIN lock security
val_screensaver_feature         # Screensaver
val_settings_feature            # Full settings UI
val_settingsaccount_component
val_settingsclub_component
val_settingsequipment_component
val_settingsmembership_component
val_settingspreferences_component
val_settingssupport_component
val_sleep_feature               # Sleep mode
val_survey_service              # User surveys
val_survey_sprig                # Sprig survey integration
val_systemcommands_service      # System command execution
val_walkup_feature              # Walk-up screen
val_workoutchart_component
val_workoutsettings_component
val_sindarin_eruipc             # ERU IPC bridge
```

### Protocol Support

Full Sindarin stack embedded:
- FitPro1 (polling protocol)
- FitPro2 (subscribe/push protocol)
- NFC, USB, BLE
- VirtualConsole, VTAP

### Decompiled Source

```
ifit_apps/arda/v1.35.15/
├── base.apk        (96MB)
├── jadx.log
└── decompiled/
    ├── sources/com/ifit/arda/           # App-specific code
    ├── sources/com/ifit/glassos_*/      # 20+ shared glassos modules
    ├── sources/com/ifit/val_*/          # 70+ Valinor UI modules
    └── resources/
```

---

## 4. rivendell (v1.42.14)

**Package:** `com.ifit.rivendell`
**APK Size:** 57MB
**Language:** Kotlin (native Android)
**com.ifit Packages:** 93
**Status:** Installed, NOT currently running

### Purpose

The media and content player. Focused on delivering workout video/audio content with HLS streaming, Google Maps street views, and audio from Feed.fm. Contains the in-workout experience UI with all metric widgets, controls, and overlays.

### Key Components

**Media Playback:**
- ExoPlayer/Media3 for HLS video streaming
- Feed.fm PlayerSdk for audio (`FeedAudioPlayer`, `FeedSession`, `ExoSimulcastAudioPlayer`)
- Closed captions support (`val_closedcaptions_widget`)

**Maps Integration:**
- `MapsStreetViewModel` - Google Street View for location workouts
- `MapsWebViewModel` - Web-based map rendering
- `val_map_components`, `val_map_webview`

**Workout Flow:**
- `LoadingActivity` - Workout loading/buffering
- `InWorkoutActivity` - Primary in-workout experience
- `WorkoutAcceptanceFragment` - Pre-workout confirmation
- `WorkoutPressStartFragment` - "Press Start" gate
- `WorkoutCompletedActivity` - Post-workout summary

**Safety:**
- `SafetyKeyViewModel` - Safety key detection and handling

**Unique Valinor Modules:**
```
val_activepulsenotifications_feature  # Active Pulse HR notifications
val_audio_framework                   # Audio playback framework
val_closedcaptions_widget             # Closed caption overlay
val_countdown_widget                  # Countdown timer widget
val_fatalitydialog_service            # Fatal error dialogs
val_feedfm_service                    # Feed.fm audio integration
val_followworkout_widget              # Follow-along workout UI
val_goalworkout_widget                # Goal-based workout UI
val_indicators_widgets                # Status indicator widgets
val_inworkout_feature                 # In-workout experience
val_inworkoutcharts_widget            # Real-time workout charts
val_inworkoutnotifications_feature    # In-workout notifications
val_inworkoutsettings_feature         # In-workout settings
val_manualmainscreen_widget           # Manual mode main screen
val_mapmainscreen_widget              # Map mode main screen
val_metric_widgets                    # Metric display widgets
val_metricsheader_widget              # Metrics header bar
val_overlaydialog_client              # Overlay dialog client
val_overlaydialog_server              # Overlay dialog server
val_pauseoverlay_widget               # Pause screen overlay
val_safetykey_widget                  # Safety key UI
val_smartadjust_core                  # SmartAdjust engine
val_system_ui                         # System UI integration
val_videostalling_widget              # Video buffering indicator
val_videoworkout_feature              # Video workout player
val_windowwidget_bridge               # Window widget bridge
val_windowwidget_service              # Window widget service
val_workout_acceptance                # Workout acceptance flow
val_workout_completed                 # Workout completed screen
val_workout_core                      # Core workout engine
val_workout_ui                        # Workout UI framework
val_workoutaudiomanager_feature       # Audio routing manager
val_workoutcontrols_widget            # Workout control buttons
val_workoutcore_analytics             # Workout analytics
val_workoutsession_handler            # Session lifecycle
```

### Decompiled Source

```
ifit_apps/rivendell/v1.42.14/
├── base.apk        (57MB)
├── jadx.log
└── decompiled/
    ├── sources/com/ifit/rivendell/      # App-specific code
    ├── sources/com/ifit/glassos_*/      # 16 shared glassos modules
    ├── sources/com/ifit/val_*/          # 75+ Valinor UI modules
    └── resources/
```

---

## 5. mithlond (v1.38.21)

**Package:** `com.ifit.mithlond`
**APK Size:** 75MB
**Language:** Kotlin (native Android)
**com.ifit Packages:** 80
**Status:** RUNNING on device (foreground)

### Purpose

The workout controller and companion dashboard app. Provides content discovery, workout creation, search, and the main dashboard experience. Includes eGym integration. Like gandalf, it runs a foreground preloader service and embeds the full Sindarin stack for direct hardware communication.

### Key Components

**Activities:**
- `DashboardComposeActivity` - Main Compose-based dashboard (primary)
- `DashboardActivity` - eGym-specific dashboard

**Services:**
- `MithlondPreloaderService` - Foreground service for data preloading

**Hardware:**
- Full Sindarin stack (FitPro1, FitPro2, USB, NFC, BLE, VirtualConsole, VTAP)
- `val_sindarin_calories` - Calorie calculation from Sindarin data

**Unique Valinor Modules:**
```
val_contentdiscovery_core       # Content browsing engine
val_contentdiscovery_filters    # Content filter UI
val_dashboard_feature           # Dashboard home
val_egym_feature                # eGym integration
val_map_components              # Map display
val_map_webview                 # Map web view
val_oneclickconversion_feature  # Subscription conversion
val_postworkout_feature         # Post-workout summary
val_postworkout_shared          # Shared post-workout code
val_productnews_firebase        # Product news via Firebase
val_realm_service               # Realm database
val_saved_feature               # Saved workouts
val_schedule_feature            # Workout scheduling
val_search_feature              # Workout search
val_seriescompletion_feature    # Series completion tracking
val_seriesdetails_feature       # Series detail views
val_trophycase_feature          # Trophy/achievement display
val_workout_core                # Workout engine
val_workoutchart_component      # Workout charts
val_workoutcreator_feature      # Custom workout builder
val_workoutdetails_feature      # Workout detail views
val_workoutsession_handler      # Session lifecycle
```

### Decompiled Source

```
ifit_apps/mithlond/v1.38.21/
├── base.apk        (75MB)
├── jadx.log
└── decompiled/
    ├── sources/com/ifit/mithlond/       # App-specific code
    ├── sources/com/ifit/glassos_*/      # 16 shared glassos modules
    ├── sources/com/ifit/val_*/          # 60+ Valinor UI modules
    └── resources/
```

---

## 6. launcher (v2.38.12)

**Package:** `com.ifit.launcher`
**APK Size:** 20MB
**Language:** Kotlin (native Android)
**com.ifit Packages:** 30
**Status:** RUNNING on device

### Purpose

Evolved from the original 5-file (83KB) kiosk splash screen into a full-featured control hub. Now manages app deployment via its AppStore, console firmware updates, QR code authentication, and multi-device home screen. This is the **deployment orchestrator** for the entire GlassOS platform.

### Evolution

| Version | Size | Files | Role |
|---------|------|-------|------|
| v1.0.12 | 83KB | ~5 | Splash screen / kiosk lock |
| v1.0.17.22 | (unknown) | (unknown) | Transitional |
| **v2.38.12** | **20MB** | **628+** | **Full control hub + AppStore** |

### AppStore State Machine

```
States:
  Idle → Loading → Checking → Downloading → Installing → Idle (success)
                                              ↓
                                           Error → Idle

  Idle → Loading → Checking → Uninstalling → Idle
```

The AppStore manages the lifecycle of all GlassOS apps. It can:
- Check for available app updates
- Download APK files
- Install new apps or updates
- Uninstall apps
- Track installation state and progress

### Console Firmware Updates

```
FirmwareUpdateStatus:
  - Firmware version check
  - Download progress tracking
  - Installation status
  - Error handling
```

This is a NEW firmware update path, separate from ERU's `RecoverySystem.installPackage()` mechanism. It handles brainboard/MCU firmware, not system OTA.

### QR Code Authentication

```
AUTH_QR_CODE_POLLING_IDLE     - No QR displayed
AUTH_QR_CODE_POLLING_ACTIVE   - QR displayed, polling for scan
AUTH_QR_CODE_POLLING_EXPIRED  - QR expired, regenerate
```

Users can authenticate by scanning a QR code displayed on the console with their phone.

### Console Integration Features

- **MyeTV** - Entertainment/TV integration
- **Calibration** - Machine calibration wizard
- **Sleep mode** - Low-power sleep management
- **Constant watts** - Constant power mode
- **TDF gear** - Tour de France gear simulation
- **Drive motor errors** - Motor error detection and reporting
- **IdleModeLockout** - Field 95 management (same as gandalf)

### Multi-Device Home

- Console home screen
- Phone companion integration
- Watch companion integration

### Activities

| Activity | Purpose |
|----------|---------|
| `MainActivity` | Home screen / launcher |
| `UpdateActivity` | Firmware and app update UI |

### Technical Details

- Encrypted local storage
- LaunchDarkly feature flags
- `val_fallback_feature` - Fallback UI if GlassOS apps fail

### Decompiled Source

```
ifit_apps/launcher/v2.38.12/
├── base.apk        (20MB)
├── jadx.log
└── decompiled/
    ├── sources/com/ifit/launcher/       # 628+ files across 83 packages
    ├── sources/com/ifit/glassos_*/      # 4 shared glassos modules
    ├── sources/com/ifit/val_*/          # 18 Valinor UI modules
    └── resources/
```

---

## 7. eru (v2.27.18)

**Package:** `com.ifit.eru`
**APK Size:** 22MB
**Language:** Kotlin (native Android)
**com.ifit Packages:** 7
**Privilege Level:** System UID (uid=1000)
**Status:** RUNNING on device (background)

### Purpose

The system updater, now using the new **Shire framework** for update management. Handles system OTA updates, per-app APK installs via its own AppStore module, and firmware updates for multiple hardware components. The immutable `/data/update.zip` protection still blocks its system OTA path.

### Evolution

| Version | Codebase | Size | Update Method |
|---------|----------|------|---------------|
| v1.2.1.145 | Xamarin/C# | 63MB | `/mnt/sdcard/iFit/system/` -> `/data/update.zip` |
| v2.2.20.1602 | Kotlin | (unknown) | DownloadManager -> `/data/update.zip` |
| v2.13.9.1852 | Kotlin | (unknown) | Direct URLConnection -> `/data/update.zip` |
| **v2.27.18** | **Kotlin** | **22MB** | **Shire framework -> `/data/update.zip`** |

### New Shire Update Framework

ERU v2.27.18 introduces a new update manifest system:

```
Update Manifest: /sdcard/update/manifest.json (UpdateManifestImpl)

Update Types:
├── System      - Full system OTA (RecoverySystem.installPackage)
├── Apps        - Per-app APK installs (PackageManager)
├── Firmware    - Console firmware
├── Brainboard  - MCU firmware
└── TvTuner     - TV tuner firmware
```

### AppStore Module

New `AppStoreManager` (`com.ifit.appstore`) handles individual APK installation:
- Uses Android `DownloadManager` for APK downloads
- `DownloadCompleteReceiver` processes completed downloads
- Installs via `PackageManager` (NOT recovery mode)
- This is separate from launcher's AppStore -- ERU can also install apps

### System OTA Path (Still Protected)

```
1. Shire framework checks for system updates
   ↓
2. Downloads to staging location
   ↓
3. Writes to /data/update.zip      ← BLOCKED by immutable flag
   ↓
4. RecoverySystem.installPackage()  ← Never reached
```

The immutable `/data/update.zip` protection confirmed still working against ERU v2.27.18.

### GlassOS Service Integration

ERU v2.27.18 integrates with the GlassOS service layer:
- `com.ifit.glassos/` - GlassOS common types
- `com.ifit.shire/` - Shire update framework

### Key Packages

```
com.ifit.api/        # API client
com.ifit.appstore/   # AppStore manager (NEW)
com.ifit.builtin/    # Built-in workouts
com.ifit.eru/        # Core ERU service
com.ifit.glassos/    # GlassOS integration
com.ifit.logger/     # Logging framework
com.ifit.shire/      # Shire update framework (NEW)
```

### Decompiled Source

```
ifit_apps/eru/v2.27.18/
├── base.apk        (22MB)
├── jadx.log
└── decompiled/
    ├── sources/com/ifit/eru/        # Core service
    ├── sources/com/ifit/appstore/   # App store module
    ├── sources/com/ifit/shire/      # Shire update framework
    ├── sources/com/ifit/glassos/    # GlassOS integration
    └── resources/
```

---

## 8. standalone (v2.6.90)

**Package:** `com.ifit.standalone`
**APK Size:** 112MB (largest overall)
**Language:** Xamarin/C# (.NET DLLs in assemblies.blob)
**Status:** Installed, NOT currently running (legacy fallback)

### Purpose

The legacy monolithic Xamarin app. Minor version bump from v2.6.88 (previously analyzed in detail). Kept installed as a fallback while the GlassOS platform rolls out. No longer the primary running application -- gandalf and mithlond have taken over its workout and dashboard functions.

### What Changed (v2.6.88 -> v2.6.90)

- Minor version bump (patch level only)
- Still uses `assemblies.blob` with .NET DLLs
- Same Xamarin/Mono architecture
- Same Wolf.Core + Shire.Core + Sindarin framework

### .NET DLL Assemblies

Still packaged as `assemblies.blob` (23MB compressed), extractable with pyxamstore:
- `Wolf.Core.dll` - Workout logic and UI framework
- `Wolf.Android.dll` - Android UI views
- `Shire.Core.dll` - Shared utilities
- `Shire.Android.dll` - Android helpers
- `Sindarin.*.dll` - Hardware abstraction layer (FitPro1, FitPro2)

### Why It's Still Installed

1. **Rollback path**: If GlassOS apps fail, the system can fall back to standalone
2. **Feature parity**: Not all standalone features may be implemented in GlassOS yet
3. **Gradual migration**: iFit appears to be rolling out GlassOS incrementally
4. **The launcher's `val_fallback_feature`**: Explicitly references fallback capability

### Decompiled Source

```
ifit_apps/standalone/v2.6.90/
├── decompiled/
│   └── resources/
│       └── assemblies/
│           ├── assemblies.blob       (23MB)
│           └── assemblies.manifest
```

**Previous version analysis (v2.6.88) at:**
```
ifit_apps/standalone/v2.6.88.4692/
├── decompiled_dll/                   # 11 DLLs -> 3,314 C# files
└── decompiled/resources/assemblies/out/  # Extracted blob contents
```

---

## Shared Module Matrix

Which shared modules appear in which apps:

### glassos_sindarin_* (Hardware HAL)

| Module | glassos_service | gandalf | arda | rivendell | mithlond | launcher |
|--------|:---:|:---:|:---:|:---:|:---:|:---:|
| arbitrator | X | X | X | X | X | - |
| fitpro1 | X | X | X | X | X | - |
| fitpro2 | X | X | X | X | X | - |
| nfc | X | X | X | X | X | - |
| service | X | X | X | X | X | - |
| shared | X | X | X | X | X | - |
| usb | X | X | X | X | X | - |
| usbaudio | X | X | X | X | X | - |
| virtualconsole | X | X | X | X | X | - |
| vtap | X | X | X | X | X | - |

### glassos_* (Platform Services)

| Module | glassos_service | gandalf | arda | rivendell | mithlond | launcher |
|--------|:---:|:---:|:---:|:---:|:---:|:---:|
| common | X | X | X | X | X | X |
| kmp_utils | X | X | X | X | X | - |
| kotlin_sdk | X | X | X | X | X | X |
| stub_engine | - | X | X | X | X | X |
| flex_engine | - | - | X | - | - | - |
| standalone_engine | X | - | X | - | - | - |
| bluetooth_service | X | X | X | X | X | - |
| appnavigation_service | X | - | X | - | - | - |
| appstore_service | X | - | X | - | - | - |
| club_service | X | - | X | - | - | - |
| egym_service | X | - | X | - | - | - |
| featuregate_service | X | - | X | - | - | - |
| hdmisound_service | X | - | X | - | - | - |
| lighting_service | X | - | X | - | - | - |
| useractivity_service | X | - | X | - | - | - |
| userauth_service | X | - | X | - | - | - |
| workout_core | X | - | X | - | X | - |

### val_* Core Modules (Common Across Apps)

These Valinor modules appear in nearly all GlassOS apps:

```
val_alert_dialog              val_logging_service
val_analytics_amplitude       val_measure_util
val_analytics_firebase        val_metadata_manager
val_analytics_service         val_navigation_service
val_connectivity_services     val_overlaydialog_core
val_core_util                 val_performancemonitor_firebase
val_coreinfo_services         val_performancemonitor_service
val_coreui_components         valinor_annotation
val_crashreporter_firebase
val_crashreporter_service
val_environment_service
val_event_bus
val_featureflag_amplitude
val_featureflag_service
```

---

## App Responsibility Breakdown

```
User arrives at bike
        │
        ▼
   [launcher]  ─── Home screen, QR auth, app store
        │
        ├──► [gandalf]    ─── Workout engine, hardware control, preloading
        │         │
        │         ▼
        │    [rivendell]  ─── Video/audio playback, maps, in-workout UI
        │
        ├──► [mithlond]   ─── Dashboard, content discovery, workout creator
        │
        ├──► [arda]       ─── Settings, setup, screensaver, sleep, walk-up
        │
        └──► [standalone] ─── Legacy fallback (if GlassOS fails)

Background services:
   [glassos_service]  ─── gRPC services, Sindarin arbitration, platform
   [eru]              ─── System OTA, app installs, firmware updates
```

---

## Related Documentation

- **[GLASSOS_ARCHITECTURE.md](GLASSOS_ARCHITECTURE.md)** - Architecture overview and diagrams
- **[APP_OVERVIEW.md](APP_OVERVIEW.md)** - Legacy Xamarin architecture
- **[../security/OTA_UPDATES.md](../security/OTA_UPDATES.md)** - OTA update analysis (includes ERU v2.27.18)
- **[../wolf/PROTOCOL.md](../wolf/PROTOCOL.md)** - FitPro protocol specification
- **[../reverse_engineering/DLL_NOTES.md](../reverse_engineering/DLL_NOTES.md)** - .NET DLL analysis (standalone)
