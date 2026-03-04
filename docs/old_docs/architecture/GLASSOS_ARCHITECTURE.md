# GlassOS Architecture

> **Discovered 2026-02-12: iFit's monolithic Xamarin app has been replaced by a modular Kotlin/Protobuf microservices platform called "GlassOS"**

**Last Updated:** 2026-02-12
**Device:** NN73Z115616 (NordicTrack S22i)

---

## Executive Summary

On 2026-02-12, five previously unknown apps were discovered deployed to the device via the launcher's AppStore mechanism. The old three-app Xamarin stack (launcher + standalone + ERU) has been superseded by an eight-app Kotlin microservices architecture called **GlassOS**. The Tolkien-themed naming convention (Gandalf, Arda, Rivendell, Mithlond, Valinor) continues iFit's established pattern (Wolf, Shire, Sindarin, Eru).

The legacy Xamarin standalone app (v2.6.90) remains installed as a fallback but is no longer the primary running application. Instead, **gandalf** (workout engine) and **mithlond** (workout controller) are the active foreground apps, with **glassos_service** providing the shared OS service layer.

---

## Architecture Comparison

### Old Architecture (Xamarin, pre-GlassOS)

```
┌──────────────────────────────────────────────┐
│  com.ifit.launcher v1.0.12  (5 files, 83KB)  │  Kiosk splash screen
└──────────────────────┬───────────────────────┘
                       │ Launches
┌──────────────────────┴───────────────────────┐
│  com.ifit.standalone v2.6.88  (Xamarin/C#)    │  Monolithic workout app
│  ┌─────────────┐ ┌─────────────┐              │  - All UI, workouts, streaming
│  │ Wolf.Core   │ │ Shire.Core  │              │  - Sindarin HAL (FitPro)
│  │ Wolf.Android│ │ Shire.Android│             │  - .NET DLLs in assemblies.blob
│  └─────────────┘ └─────────────┘              │
└──────────────────────┬───────────────────────┘
                       │ IPC (Intents/Broadcasts)
┌──────────────────────┴───────────────────────┐
│  com.ifit.eru v1.2.1.145  (Xamarin/C#)        │  System service (uid=system)
│  ┌─────────────┐                              │  - USB hardware control
│  │ Eru.Core    │                              │  - OTA updates
│  │ Eru.Android │                              │  - App installation
│  └─────────────┘                              │
└──────────────────────┬───────────────────────┘
                       │ USB
                   ┌───┴───┐
                   │  MCU  │  Wolf Hardware Controller
                   └───────┘
```

**Characteristics:**
- 3 apps total
- Monolithic Xamarin/C# with .NET DLLs
- Single app (standalone) handles ALL user-facing functions
- Intents/Broadcasts for IPC
- ERU as sole system service

### New Architecture (GlassOS, Kotlin/Protobuf)

```
┌─────────────────────────────────────────────────────────────────────────┐
│                        GlassOS Platform Layer                           │
│                                                                         │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │  glassos_service v7.10.3  (44MB, 11,598 classes)                │   │
│  │  ┌──────────────┐ ┌──────────────┐ ┌──────────────┐            │   │
│  │  │ ConsoleService│ │BrightnessServ│ │ VolumeService│  ...11+    │   │
│  │  └──────────────┘ └──────────────┘ └──────────────┘            │   │
│  │  ┌──────────────┐ ┌──────────────┐ ┌──────────────┐            │   │
│  │  │ AppStore      │ │FeatureGates  │ │Sindarin Arb. │            │   │
│  │  └──────────────┘ └──────────────┘ └──────────────┘            │   │
│  └─────────────────────────────────────────────────────────────────┘   │
│        ↑ gRPC/Protobuf          ↑ gRPC/Protobuf          ↑            │
│  ┌─────┴──────┐  ┌──────────────┴──┐  ┌──────────────────┴────────┐   │
│  │  gandalf   │  │    mithlond     │  │        arda               │   │
│  │  v1.34.16  │  │    v1.38.21    │  │       v1.35.15            │   │
│  │  Workout   │  │    Dashboard   │  │       Primary UI          │   │
│  │  Engine    │  │    + Creator   │  │       + Settings          │   │
│  │  48MB      │  │    75MB        │  │       96MB                │   │
│  │  RUNNING   │  │    RUNNING     │  │       (not running)       │   │
│  └────────────┘  └────────────────┘  └────────────────────────────┘   │
│        ↑                                     ↑                         │
│  ┌─────┴──────────────────────────────────────┴───────────────────┐   │
│  │                    rivendell v1.42.14  (57MB)                   │   │
│  │              Media Player / Content Streaming                   │   │
│  │              (not running)                                      │   │
│  └────────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────┘
         ↑ App Install/Update             ↑ System OTA
┌────────┴──────────┐          ┌──────────┴──────────┐
│  launcher v2.38.12│          │   eru v2.27.18      │
│  App Store +      │          │   System Updater    │
│  Firmware Updates │          │   + App Store       │
│  20MB             │          │   22MB              │
└───────────────────┘          └─────────────────────┘
         ↑                              ↑
┌────────┴──────────────────────────────┴───────────────────────────────┐
│  standalone v2.6.90  (Xamarin, 112MB)  [LEGACY FALLBACK]              │
│  Still installed, not primary. Kept for rollback.                      │
└───────────────────────────────────────────────────────────────────────┘
         │
         │ USB (Sindarin HAL - now embedded in gandalf/mithlond/arda)
         ↓
┌─────────────────┐
│  MCU (FitPro)   │  Wolf Hardware Controller
│  SpinBike 0x08  │
└─────────────────┘
```

**Characteristics:**
- 8 apps total (5 new + 3 evolved)
- Native Kotlin with Protobuf/gRPC IPC
- Microservices: each app has a focused responsibility
- Shared frameworks: `glassos_*` (platform), `val_*` (Valinor UI)
- Sindarin HAL embedded directly in client apps (not centralized in ERU)
- Multiple apps can own hardware arbitration via `glassos_sindarin_arbitrator`

---

## IPC Mechanism: gRPC/Protobuf

The old Intent/Broadcast IPC between standalone and ERU has been replaced with gRPC over Protobuf. This is a significant architectural change:

| Aspect | Old (Xamarin) | New (GlassOS) |
|--------|---------------|----------------|
| **Protocol** | Android Intents/Broadcasts | gRPC/Protobuf |
| **Serialization** | Intent extras (Bundle) | Protocol Buffers |
| **Service discovery** | Package name + action | GlassOS service registry |
| **Type safety** | Weak (string keys) | Strong (proto schemas) |
| **Streaming** | N/A | gRPC bidirectional streams |
| **Multi-client** | Broadcast (unordered) | gRPC server (ordered) |

### Service Layer

`glassos_service` acts as the central gRPC server, exposing services that client apps (gandalf, arda, mithlond, rivendell) consume:

```
glassos_service gRPC Services:
├── ConsoleService         - Hardware console state
├── BrightnessService      - Display brightness control
├── VolumeService          - Audio volume control
├── FanStateService        - Fan speed control
├── DemoModeService        - Demo/retail mode
├── TimeZoneService        - System timezone
├── SystemUnitsService     - Metric/imperial
├── VideoQualityService    - Stream quality settings
├── MaxTimeService         - Workout time limits
├── MaxSpeedService        - Speed limits
├── LightingService        - Console LED lighting
├── BluetoothService       - BLE device management
├── HdmiSoundService       - HDMI audio routing
├── AppNavigationService   - Cross-app navigation
├── AppStoreService        - App install/update
├── ClubService            - Gym club features
├── EGymService            - eGym integration
├── FeatureGateService     - Feature flags
├── UserActivityService    - Activity tracking
├── UserAuthService        - Authentication
├── ProgrammedWorkout*     - Workout programs (ActivePulse, Map, SmartAdjust)
└── SindarinArbitrator     - FitPro1 device arbitration
```

---

## Shared Frameworks

### glassos_sindarin_* (Hardware Abstraction Layer)

The Sindarin HAL, previously embedded in the monolithic standalone app's .NET DLLs, is now a set of shared Kotlin modules distributed across apps:

| Module | Purpose | Found In |
|--------|---------|----------|
| `glassos_sindarin_arbitrator` | Multi-app device arbitration | All 6 apps |
| `glassos_sindarin_fitpro1` | FitPro v1 protocol (polling) | All 6 apps |
| `glassos_sindarin_fitpro2` | FitPro v2 protocol (subscribe/push) | All 6 apps |
| `glassos_sindarin_nfc` | NFC tag reading | All 6 apps |
| `glassos_sindarin_service` | Sindarin service layer | All 6 apps |
| `glassos_sindarin_shared` | Common Sindarin utilities | All 6 apps |
| `glassos_sindarin_usb` | USB device communication | All 6 apps |
| `glassos_sindarin_usbaudio` | USB audio routing | All 6 apps |
| `glassos_sindarin_virtualconsole` | Virtual console emulation | All 6 apps |
| `glassos_sindarin_vtap` | VTAP protocol support | All 6 apps |

**Key change:** In the old architecture, only ERU (system service) talked to the MCU over USB. Now, multiple apps embed the full Sindarin stack and use `glassos_sindarin_arbitrator` to coordinate access. This is why gandalf and mithlond can run simultaneously while both needing hardware access.

### val_* (Valinor UI Framework)

A comprehensive UI component library replacing the old Wolf.Android.dll views. Over 50 modules provide consistent UI across apps:

| Category | Modules | Examples |
|----------|---------|----------|
| **Core** | `val_core_util`, `val_coreui_components`, `val_navigation_service` | Layout, navigation, theming |
| **Analytics** | `val_analytics_amplitude`, `val_analytics_firebase` | Event tracking, crash reporting |
| **Login/Auth** | `val_login_feature`, `val_login_authzero`, `val_key_provider` | Auth0 login, token management |
| **Workout** | `val_workout_core`, `val_workoutsession_handler`, `val_workoutchart_component` | Session management, charts |
| **Media** | `val_video_framework`, `val_audio_framework`, `val_feedfm_service` | Video, audio, Feed.fm |
| **Maps** | `val_map_components`, `val_map_webview` | Google Maps integration |
| **Settings** | `val_settings_feature`, `val_settingsaccount_component` | Account, equipment, preferences |
| **Features** | `val_walkup_feature`, `val_screensaver_feature`, `val_sleep_feature` | Walk-up, screensaver, sleep |
| **Sindarin** | `val_sindarin_calories`, `val_sindarin_lookup`, `val_sindarin_eruipc` | Calorie calc, field lookup, ERU IPC |

### glassos_* (Platform Services)

Shared platform modules beyond Sindarin:

| Module | Purpose |
|--------|---------|
| `glassos_common` | Common utilities and types |
| `glassos_kmp_utils` | Kotlin Multiplatform utilities |
| `glassos_kotlin_sdk` | Kotlin SDK extensions |
| `glassos_stub_engine` | Stub engine for testing |
| `glassos_flex_engine` | Flexible workout engine (arda) |
| `glassos_standalone_engine` | Standalone compatibility engine |
| `glassos_bluetooth_service` | Bluetooth device management |

---

## App Deployment via Launcher AppStore

The new apps were deployed to the device through the **launcher's AppStore mechanism** -- a state machine built into launcher v2.38.12:

```
AppStore State Machine:
  Idle → Loading → Checking → Downloading → Installing → Idle
                                              ↓
                                           Error → Idle
                                              ↓
                                        Uninstalling → Idle
```

**Deployment sequence (observed 2026-02-12):**
1. ERU v2.27.18 was installed first (replacing v2.13.9)
2. Launcher v2.38.12 was installed (replacing v1.0.17.22)
3. Launcher used its AppStore to install: glassos_service, gandalf, arda, rivendell, mithlond
4. Standalone v2.6.90 was updated from v2.6.88 (minor bump, kept as fallback)
5. Gandalf and mithlond became the active foreground apps

This means the launcher is no longer just a kiosk splash screen -- it is the **deployment orchestrator** for the entire GlassOS platform. It can install, update, and uninstall any GlassOS app.

---

## Running Processes (Observed 2026-02-12)

| Process | Status | Role |
|---------|--------|------|
| `com.ifit.glassos_service` | RUNNING | Central gRPC service layer |
| `com.ifit.gandalf` | RUNNING (foreground) | Workout engine, Sindarin HAL owner |
| `com.ifit.mithlond` | RUNNING (foreground) | Dashboard, workout controller |
| `com.ifit.eru` | RUNNING (background) | System updater |
| `com.ifit.launcher` | RUNNING | Kiosk + app store |
| `com.ifit.arda` | INSTALLED, not running | Primary UI (not yet activated?) |
| `com.ifit.rivendell` | INSTALLED, not running | Media player (not yet activated?) |
| `com.ifit.standalone` | INSTALLED, not running | Legacy fallback |

---

## App Interference Mechanisms (Discovered 2026-02-13)

### AccessibilityService — Settings Killer

`glassos_service` registers an AccessibilityService (`com.ifit.glassos_appnavigation_service.service.AccessibilityServiceImpl`) that monitors all foreground window changes. The service:

1. Checks every `TYPE_WINDOW_STATE_CHANGED` event
2. If the foreground package contains `com.android.settings`, calls `performGlobalAction(GLOBAL_ACTION_BACK)` — immediately pressing Back to dismiss Settings
3. Code location: `nf/s.java:793` method `W()`

**Re-enable cycle:** Rivendell broadcasts `com.ifit.eru.ENABLE_ACCESSIBILITY_SERVICE` to ERU every ~30 seconds. ERU's `EnableAccessibilityServiceReceiver` re-enables the service each time, unless `inDeveloperMode=true` or an update is in progress.

### Third-Party App Management (ERU)

ERU v2.27.18 includes a privileged commands system (`c9/y.java` / `PrivilegedCommandsImpl`) that can manage third-party apps:

| Method | Action | Trigger |
|--------|--------|---------|
| `killThirdPartyApps` | Force-stop | `KILL_THIRD_PARTY` broadcast, screensaver/sleep |
| `clearUserDataForThirdPartyApps` | Wipe app data | `RESET_THIRD_PARTY_CACHE` broadcast, club logout |
| `uninstallThirdPartyApps` | Full uninstall | Beta opt-out only (user action) |
| `killAllNonSystemApps` | Force-stop everything | Internal cleanup (protected: ERU, launcher, arda) |
| `uninstallValinorApps` | Uninstall GlassOS apps | Beta opt-out (protected: ERU, standalone, launcher) |

**Targeting:** Only apps listed in LaunchDarkly flags `ifit_appstore` (structured JSON) and `third-party-fqns` (comma-separated) are targeted. Both default to empty. The `ifit_appstore` flag also has a `disallowed` list (Amazon Video, Netflix, Spotify) that controls the app store UI but is NOT used for kill/uninstall.

**Defense:** Boot script disables `EnableAccessibilityServiceReceiver`, `KillThirdPartyAppReceiver`, and `ResetThirdPartyAppReceiver`. See [PROTECTION.md](../guides/PROTECTION.md).

---

## Security Implications

### Expanded Attack Surface

The move from 3 to 8 apps significantly increases the attack surface:

1. **Multiple update paths**: ERU handles system OTA, launcher handles app installs, glassos_service has its own app store service. Three separate vectors for code installation.

2. **App Store mechanism**: The launcher can now install arbitrary APKs via its AppStore state machine. If the AppStore API can be spoofed or MITM'd, any app could be deployed.

3. **Sindarin HAL in every app**: In the old architecture, only ERU (system UID) had USB access. Now, every GlassOS app embeds the full Sindarin stack. An exploit in any app could potentially access MCU hardware.

4. **gRPC services**: The glassos_service exposes dozens of gRPC endpoints. Each is a potential attack vector for privilege escalation from user-level apps.

5. **Firmware updates via launcher**: Launcher v2.38.12 includes `FirmwareUpdateStatus` with progress tracking. This is a NEW firmware update path separate from ERU's `RecoverySystem.installPackage()`.

6. **Feature flags everywhere**: LaunchDarkly feature flags (`glassos_featuregate_service`) can remotely toggle behavior. Remote code execution risk if flag evaluation is compromised.

### OTA Protection Status

The existing `/data/update.zip` immutable file protection **still works** for system OTA:
- ERU v2.27.18 still uses `/data/update.zip` for system updates
- ERU v2.27.18 still calls `RecoverySystem.installPackage()`
- The immutable flag blocks the write, same as before

However, **app-level updates are NOT blocked** by this protection:
- Launcher AppStore can install/update individual APK files
- ERU v2.27.18 has its own AppStore module (`com.ifit.appstore`)
- These use `PackageManager.installPackage()`, not recovery mode

### New Manifest Path

ERU v2.27.18 introduces a new update manifest location:
```
/sdcard/update/manifest.json  (UpdateManifestImpl)
```
This is separate from the old `/mnt/sdcard/iFit/apps/apps.json` path.

---

## Codename Reference

| Name | Type | Tolkien Reference |
|------|------|-------------------|
| **GlassOS** | Platform | - |
| **Gandalf** | Workout app | Wizard, guide of the Fellowship |
| **Arda** | UI app | The world (Middle-earth and beyond) |
| **Rivendell** | Media app | Elven refuge, House of Elrond |
| **Mithlond** | Dashboard app | Grey Havens, port city |
| **Valinor** (val_*) | UI framework | Undying Lands, home of the Valar |
| **Sindarin** | HAL | Elven language (Grey-elven) |
| **Wolf** | MCU/UI (legacy) | Hardware controller codename |
| **Shire** | Shared lib | Hobbit homeland |
| **Eru** | System service | Eru Iluvatar, supreme deity |

---

## Decompiled Source Locations

```
ifit_apps/
├── glassos_service/v7.10.3/decompiled/     # 68 com.ifit packages, 44MB APK
├── gandalf/v1.34.16/decompiled/            # 65 com.ifit packages, 48MB APK
├── arda/v1.35.15/decompiled/               # 100 com.ifit packages, 96MB APK
├── rivendell/v1.42.14/decompiled/          # 93 com.ifit packages, 57MB APK
├── mithlond/v1.38.21/decompiled/           # 80 com.ifit packages, 75MB APK
├── launcher/v2.38.12/decompiled/           # 30 com.ifit packages, 20MB APK
├── eru/v2.27.18/decompiled/                # 7 com.ifit packages, 22MB APK
└── standalone/v2.6.90/decompiled/          # Xamarin (.NET DLLs), 112MB APK
```

**Decompiler:** JADX v1.5.3
**Extraction Date:** 2026-02-12

---

## Related Documentation

- **[GLASSOS_APPS.md](GLASSOS_APPS.md)** - Detailed per-app breakdown
- **[APP_OVERVIEW.md](APP_OVERVIEW.md)** - Legacy Xamarin architecture overview
- **[IPC_COMMUNICATION.md](IPC_COMMUNICATION.md)** - Legacy IPC patterns
- **[../security/OTA_UPDATES.md](../security/OTA_UPDATES.md)** - OTA update analysis (includes ERU v2.27.18)
- **[../wolf/PROTOCOL.md](../wolf/PROTOCOL.md)** - FitPro protocol specification
- **[../guides/PROTECTION.md](../guides/PROTECTION.md)** - Update blocking runbook
