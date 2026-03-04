# iFit App Ecosystem Analysis

> **Device:** NordicTrack S22i (NN73Z115616) — Android 7.1.2, 1920x1080 landscape, 22"
>
> **Analysis date:** 2026-03-03

## Ecosystem Overview

The iFit console runs 7 interconnected apps that form a layered system. From bottom to top:

| Layer | App | Package | Role |
|-------|-----|---------|------|
| System | **ERU** | `com.ifit.eru` | Device management, hardware lifecycle, system control (runs as `android.uid.system`) |
| Platform | **GlassOS Service** | `com.ifit.glassos_service` | gRPC service bus (port 54321), BLE, accessibility, inter-app middleware |
| Runtime | **Standalone** | `com.ifit.standalone` | Legacy Xamarin app — FitPro USB protocol, BLE/TCP broadcast, workout UI |
| App | **Arda** | `com.ifit.arda` | Console home screen — screensaver, settings, WiFi, equipment setup |
| App | **Gandalf** | `com.ifit.gandalf` | Fitness UI — login, workouts, ecosystem selection, walk-up |
| App | **Rivendell** | `com.ifit.rivendell` | Workout delivery — in-workout UI, overlays, media, post-workout |
| App | **Mithlond** | `com.ifit.mithlond` | Content discovery — dashboard, workout library, challenges, search |

### Inter-App Communication

```
                    ┌─────────────┐
                    │   ERU       │ ← system UID, boot receiver, watchdog
                    │ (Messenger) │
                    └──────┬──────┘
                           │ IPC
         ┌─────────────────┼─────────────────┐
         │                 │                  │
    ┌────▼────┐    ┌───────▼───────┐   ┌─────▼─────┐
    │ GlassOS │    │  Standalone   │   │  Arda     │
    │ (gRPC)  │    │ (USB/BLE/TCP) │   │ (Console) │
    └────┬────┘    └───────────────┘   └─────┬─────┘
         │                                    │
    ┌────┼──────────┬────────────┐           │
    │    │          │            │            │
┌───▼──┐ ┌──▼───┐ ┌──▼────┐ ┌───▼───┐ ┌────▼────┐
│Gandalf│ │Rivndl│ │Mithld │ │(other)│ │Screensavr│
└───────┘ └──────┘ └───────┘ └───────┘ └─────────┘
```

**Primary IPC mechanisms:**
- **Messenger-based bound services** — ERU IpcService, Standalone WolfIpcService, Rivendell IpcService
- **gRPC over TLS (port 54321)** — GlassOS hosts 30+ services
- **Explicit broadcast intents** — ERU has 40+ custom actions
- **Content providers** — Gandalf ecosystem provider, log providers per app
- **Accessibility service** — GlassOS/Arda monitor foreground app

---

## 1. ERU — Equipment Resource Unit

| | |
|---|---|
| **Package** | `com.ifit.eru` |
| **Version** | 2.27.18 (build 2445) |
| **Type** | System app (`android.uid.system`) |
| **SDK** | min 22 / target 33 |

### Purpose
System-level device management daemon. Manages hardware lifecycle, firmware updates, boot recovery, app installation, system settings, and serves as the privileged orchestrator for the entire iFit ecosystem.

### Permissions (40+)

**Hardware:** `MANAGE_USB`, `BLUETOOTH`, `BLUETOOTH_ADMIN`, `BLUETOOTH_CONNECT`, `ACCESS_FINE_LOCATION`

**System (requires system UID):** `WRITE_SECURE_SETTINGS`, `WRITE_SETTINGS`, `SET_TIME`, `SET_TIME_ZONE`, `REBOOT`, `STATUS_BAR`, `SYSTEM_ALERT_WINDOW`, `MOUNT_UNMOUNT_FILESYSTEMS`, `INSTALL_PACKAGES`, `DELETE_PACKAGES`, `FORCE_STOP_PACKAGES`, `GRANT_RUNTIME_PERMISSIONS`, `INTERACT_ACROSS_USERS`, `READ_LOGS`, `RECOVERY`, `HDMI_CEC`, `CLEAR_APP_CACHE`, `CLEAR_APP_USER_DATA`

**Network:** `INTERNET`, `ACCESS_NETWORK_STATE`, `CHANGE_WIFI_STATE`, `CHANGE_NETWORK_STATE`

### Activities

| Activity | Intent Filter | Purpose |
|----------|---------------|---------|
| `MainActivity` | MAIN/LAUNCHER | Main UI entry point |
| `PrivilegedModeActivity` | `com.ifit.eru.PRIVILEGED_DIALOG` | Developer/privileged mode entry |
| `UpdateActivity` | `com.ifit.eru.UPDATE` | Firmware update UI (singleInstance) |
| `RecoveryActivity` | `com.ifit.eru.FORCE_FLASH` | Bootloader recovery |
| `StorageFailureActivity` | `com.ifit.eru.STORAGE_DIALOG` | Storage error dialog |
| `UsbActivity` | — | USB device/stick handling |

### Services

| Service | Intent Filter | Purpose |
|---------|---------------|---------|
| **EruIpcServiceImpl** | `com.ifit.eru.IpcService` | **Primary IPC endpoint.** Messenger-based bound service. Exposes console info, state, user info, brightness, language, timezone to all iFit apps. |
| **AppStoreService** | `com.ifit.appstore.AppStoreManagerService` | App installation/update management |
| **HdmiSoundManagerService** | `com.ifit.hdmi.HdmiSoundManagerService` | HDMI audio output control |
| **KeepTheWolfAliveService** | — | **Watchdog** for the "Wolf" system daemon. Monitors crashes, restarts it, tracks launch counts, handles bootloader detection. |
| **InternetMonitoringService** | — | Network connectivity monitor. Pings every 30s, triggers "No Connection" screen. |
| **TouchWatchService** | — | User touch activity monitor |

### Broadcast Receivers (40+)

**Boot & Startup:**

| Receiver | Trigger | Purpose |
|----------|---------|---------|
| `TabletStartupReceiver` | `BOOT_COMPLETED`, `com.ifit.eru.CONFIGURE_TABLET` | **Main boot orchestrator.** Sets up console UUID, configures tablet, manages logging, sets time/timezone, disables dev mode, enqueues update workers. |

**Hardware:**

| Receiver | Trigger | Purpose |
|----------|---------|---------|
| `UsbDeviceAttachedReceiver` | `USB_DEVICE_ATTACHED` | Handles USB device attachment (firmware sticks) |
| `FitproAttachedReceiver` | `com.ifit.eru.FITPRO_ATTACHED` | FitPro hardware protocol device attached |
| `GrantUsbPermissionReceiver` | `com.ifit.eru.USB_PERMISSION_REQUEST` | Grants USB permission to other apps |

**Network & Time:**

| Receiver | Trigger | Purpose |
|----------|---------|---------|
| `NetworkStateReceiver` | `CONNECTIVITY_CHANGE` | Network state changes |
| `DateTimeChangedReceiver` | `DATE_CHANGED`, `TIME_SET` | Caches system time |
| `TimezoneChangedReceiver` | `TIMEZONE_CHANGED` | Caches timezone |
| `IsNetworkConnectedReceiver` | `com.ifit.eru.IS_NETWORK_CONNECTED` | Query network status |

**App & Package Management:**

| Receiver | Trigger | Purpose |
|----------|---------|---------|
| `PackageReplacedReceiver` | `PACKAGE_ADDED`, `PACKAGE_REPLACED` | Reacts to app installation/replacement |
| `MyPackageReplacedReceiver` | `MY_PACKAGE_REPLACED` | ERU self-update handler |
| `DownloadCompleteReceiver` | `DOWNLOAD_COMPLETE` | Download completion |
| `KillThirdPartyAppReceiver` | `com.ifit.eru.KILL_THIRD_PARTY` | Force stop third-party apps |
| `KillDemoModeAppReceiver` | `com.ifit.eru.KILL_DEMO_MODE` | Kill demo mode app |
| `ResetThirdPartyAppReceiver` | `com.ifit.eru.RESET_THIRD_PARTY_CACHE` | Clear third-party cache |
| `StandaloneBounceReceiver` | `com.ifit.eru.STANDALONE_BOUNCE` | Restart app ecosystem |

**Updates:**

| Receiver | Trigger | Purpose |
|----------|---------|---------|
| `UpdateCheckReceiver` | `com.ifit.eru.IDLE_UPDATE` | Check for updates when idle |
| `IsUpdateAvailableReceiver` | `com.ifit.eru.IS_UPDATE_AVAILABLE` | Query update availability |
| `RequestUpdateInstallReceiver` | `com.ifit.eru.REQUEST_UPDATE_INSTALL` | Trigger update install |
| `TvTunerForceFlashReceiver` | `com.ifit.eru.FLASH_TUNER` | Force reflash TV tuner firmware |

**Privileged Mode & Settings:**

| Receiver | Trigger | Purpose |
|----------|---------|---------|
| `PrivilegedModeReceiver` | `com.ifit.eru.PRIVILEGEDMODE` | Toggle developer mode |
| `SetSystemLanguageReceiver` | `com.ifit.eru.SET_LANGUAGE` | Set system language |
| `UpdateEnvironmentReceiver` | `com.ifit.eru.UPDATE_ENVIRONMENT` | Update environment config |
| `RequestPermissionsReceiver` | `com.ifit.eru.REQUEST_PERMISSIONS` | Grant permissions to apps |
| `EnableAccessibilityServiceReceiver` | `com.ifit.eru.ENABLE_ACCESSIBILITY_SERVICE` | Enable accessibility service |

**Sleep/Power:**

| Receiver | Trigger | Purpose |
|----------|---------|---------|
| `AwakeSleepReceiver` | `com.ifit.arda.AWAKE_SLEEP` | Handle wake/sleep from Arda |
| `InstantSleepRequestReceiver` | `com.ifit.arda.REQUEST_SLEEP` | Trigger immediate sleep |
| `AutomaticOfflineModeEnabledReceiver` | `com.ifit.eru.AUTOMATIC_OFFLINE_MODE_ENABLED` | Toggle offline mode |
| `UpdateLastUserTouchReceiver` | `com.ifit.eru.RESET_LAST_USER_TOUCH` | Track last user interaction |

**Diagnostics:**

| Receiver | Trigger | Purpose |
|----------|---------|---------|
| `CauseCrashReceiver` | `com.ifit.eru.PLEASE_CRASH` | Trigger intentional crash (debug) |
| `SendLogsReceiver` | `com.ifit.eru.SEND_LOGS` | Collect and send logs |
| `ClubLogoutReceiver` | `com.ifit.eru.CLUB_LOGOUT` | Handle club logout |

### Content Providers
None custom — uses Messenger-based IPC instead.

---

## 2. GlassOS Service — Platform Service Bus

| | |
|---|---|
| **Package** | `com.ifit.glassos_service` |
| **Version** | 7.10.3 (build 1434) |
| **Type** | System service |
| **SDK** | min 21 / target 30 / compile 34 |

### Purpose
Core middleware layer. Hosts a gRPC server on port 54321 with TLS, providing 30+ services for hardware abstraction, UI navigation, accessibility monitoring, BLE management, and cross-app coordination.

### Permissions

**Bluetooth (full stack):** `BLUETOOTH`, `BLUETOOTH_ADMIN`, `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, `BLUETOOTH_ADVERTISE`, `BLUETOOTH_PRIVILEGED`

**System:** `FOREGROUND_SERVICE`, `RECEIVE_BOOT_COMPLETED`, `SYSTEM_ALERT_WINDOW`, `QUERY_ALL_PACKAGES`, `PACKAGE_USAGE_STATS`, `WAKE_LOCK`, `WRITE_SECURE_SETTINGS`, `WRITE_SETTINGS`

**Network:** `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE`

**Location:** `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`

**Custom:** `com.ifit.launcher.READ`, `com.ifit.glassos_service.val_logging_service.PERMISSION`

### Activities

| Activity | Intent Filter | Purpose |
|----------|---------------|---------|
| `MainActivity` | MAIN/LAUNCHER | Status display, starts GlassOSPlatformService |
| `EnableBluetoothActivity` | — | Bluetooth enablement prompt (Compose UI) |
| `ValinorAlertDialogActivity` | `val.alert.dialog.open` | Generic alert dialog |

### Services

| Service | Intent Filter | Purpose |
|---------|---------------|---------|
| **GlassOSPlatformService** | `com.ifit.glassos.GLASSOS_PLATFORM` | **Core service.** Runs as foreground service. Hosts OkHttp gRPC server on 0.0.0.0:54321 with TLS. 30+ BindableService implementations. Also exposes Messenger IPC as fallback. |
| **AccessibilityServiceImpl** | `AccessibilityService` | Monitors foreground app changes, draws invisible touch overlay, tracks IME state. Requires `BIND_ACCESSIBILITY_SERVICE`. |

### gRPC Services (port 54321)

| Category | Services |
|----------|----------|
| **System** | Brightness, Volume, TimeZone, DemoMode |
| **Hardware** | DriveMotor, ThrottleCalibration, VirtualDMK, Spoofing |
| **Bluetooth** | Device scanning, pairing, connection management |
| **Console** | Device status, UI coordination |
| **User/Auth** | Authentication, user profile |
| **Navigation** | App launching, screen management |
| **Fitness** | ActivityLog, HeartRate, ActivePulse, EGym (22 sub-services) |
| **Content** | Home dashboard, widgets |
| **Sensors** | ANT+, ProximitySensing |
| **Power** | SleepState management |
| **Media** | MyETV, ExternalAudio |
| **Config** | FeatureGate (dynamic feature flags), ClubSettings |

### Broadcast Receivers

| Receiver | Trigger | Purpose |
|----------|---------|---------|
| `GlassOSAutoStartReceiver` | `BOOT_COMPLETED` | Auto-starts GlassOSPlatformService as foreground service on boot |

### Content Providers

| Provider | Authority | Purpose |
|----------|-----------|---------|
| `FileContentProvider` | `com.ifit.glassos_service.logprovider` | Read-only log file access (permission-protected) |

---

## 3. Standalone — Legacy FitPro App (Xamarin)

| | |
|---|---|
| **Package** | `com.ifit.standalone` |
| **Version** | 2.6.90 |
| **Type** | Xamarin.Android app (.NET/C#) |
| **Note** | No AndroidManifest.xml in decompiled output |

### Purpose
Legacy Xamarin.Android fitness console app. Implements the proprietary FitPro USB serial protocol and broadcasts equipment data over BLE GATT and TCP (DIRCON). Contains the core hardware communication stack.

### Java-Side Components (thin wrappers → .NET)

| Component | Type | Purpose |
|-----------|------|---------|
| `BackgroundVideoService` | Service | Background video playback, audio focus management |
| `WolfIpcService` | Service (exported) | IPC bound service (intent: `com.ifit.standalone.IpcService`) |
| `LocaleChangedReceiver` | Receiver | Locale/language changes |
| `StandaloneBounceReceiver` | Receiver | App bounce/restart events |

### .NET Architecture

**FitPro1 Protocol (`Sindarin.FitPro1.Core`):**
- Message format: `[Device] [Length] [Command] [Content...] [Checksum]`
- USB serial: 115200 baud, 50ms transfer timeout, 2500ms response timeout
- 100+ data fields: speed, resistance, incline, watts, pulse, calories, etc.
- Commands: Connect, Disconnect, ReadWriteData, VerifySecurity, Calibrate, Update, EnterBootloader
- Device types: Treadmill, FitnessBike, SpinBike, Elliptical, Rower, InclineTrainer, etc.

**USB Communication (`Sindarin.Usb.Android`):**
- Android USB Host API with bulk transfers
- Auto-reconnect: max 20 attempts, 5-second timeout
- Buffer clearing (0xFF packets) before communication
- Permission handling via `IUsbPermissionService`

**Broadcast Adapters:**
- `Sindarin.FitPro1.Ble` — BLE GATT server broadcasting
- `Sindarin.FitPro1.Tcp` — TCP socket for Wahoo DIRCON
- `Sindarin.FitPro2.Core` — Newer FitPro2 protocol support

**UI Framework:**
- Wolf.Core + Wolf.Android — MvvmCross MVVM framework
- Shire.Core + Shire.Android — API services, OAuth, workout sync
- Full workout UI: login, dashboard, in-workout HUD, BLE pairing, post-workout

**Key Assemblies:**
`Sindarin.FitPro1.Core.dll`, `Sindarin.Usb.Android.dll`, `Sindarin.Ble.Android.dll`, `Sindarin.FitPro1.Ble.dll`, `Sindarin.FitPro1.Tcp.dll`, `Wolf.Core.dll`, `Wolf.Android.dll`, `Shire.Core.dll`

### Data Flow
```
USB Serial (115200 baud)
  → Sindarin.Usb.Android (bulk transfers)
    → FitPro1Console (protocol parsing, command queue)
      → Wolf.Android (UI updates via MvvmCross)
      → Sindarin.FitPro1.Ble (BLE GATT broadcast)
      → Sindarin.FitPro1.Tcp (TCP broadcast)
```

---

## 4. Arda — Console Home Screen

| | |
|---|---|
| **Package** | `com.ifit.arda` |
| **Version** | 1.35.15 (build 1025) |
| **Type** | User app |
| **SDK** | min 22 / target 30 / compile 34 |

### Purpose
Primary console home screen app. Manages screensaver, sleep mode, WiFi/Bluetooth settings, equipment setup/calibration, and walk-up experience. Acts as the device's idle-state manager.

### Permissions
**Hardware:** `BLUETOOTH` (full stack + `BLUETOOTH_PRIVILEGED`), `CAMERA`, `RECORD_AUDIO`, USB host

**System:** `SYSTEM_ALERT_WINDOW`, `WRITE_SETTINGS`, `WRITE_SECURE_SETTINGS`, `DEVICE_POWER`, `FORCE_STOP_PACKAGES`, `KILL_BACKGROUND_PROCESSES`, `GRANT_RUNTIME_PERMISSIONS`, `CHANGE_COMPONENT_ENABLED_STATE`

**Network:** `INTERNET`, `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE`

### Activities

| Activity | Intent Filter | Purpose |
|----------|---------------|---------|
| `MainActivity` | MAIN/LAUNCHER | Main entry point |
| `NoConnectionActivity` | `val.noconnection.open` | No-internet error/WiFi setup (singleTask, noHistory) |
| `WifiSettingsActivity` | `val.wifisettings.open` | WiFi network setup |
| `ScreensaverActivity` | `arda.screensaver.open` | Idle screensaver (singleInstance). Supports image cycling, video. Broadcasts `com.ifit.arda.AWAKE_SLEEP` on exit. |
| `SettingsActivity` | `val.settings.open` | System & app settings (WiFi, BLE, language, region) |
| `MachineSetupActivity` | `val.machinesetup.open` | Equipment calibration/setup. Sends `com.ifit.eru.AUTOMATIC_OFFLINE_MODE_ENABLED` broadcast. |
| `SleepActivity` | `arda.sleep.open` | Device sleep mode countdown/lock |
| `WalkUpScreenActivity` | `val.walkup.open` | Walk-up/warm-up screen before workout |
| `RenewMembershipDialogActivity` | `val.one_click_conversion.renew.open` | Subscription renewal prompt (singleTask) |
| `LoadingActivity` | `val.loading.view.open`/`.dismiss` | Progress dialog (singleTop, transparent) |
| `ValinorAlertDialogActivity` | `val.alert.dialog.open` | Generic alert dialog |
| `FeedbackActivity` | `val.feedback.activity.open` | User feedback/survey (singleTask) |
| `ProductNewsVideoActivity` | — | Promotional video content |
| `ProductNewsPagesActivity` | — | Promotional pages/articles |
| `ColorScreenTestActivity` | — | Display color calibration (diagnostics) |
| `CameraTestActivity` | — | Camera hardware test (diagnostics) |

### Services

| Service | Intent Filter | Purpose |
|---------|---------------|---------|
| `AccessibilityServiceImpl` | `AccessibilityService` | Monitors foreground app, overlay windows, IME state. Requires `BIND_ACCESSIBILITY_SERVICE`. |

### Broadcast Receivers

| Receiver | Trigger | Purpose |
|----------|---------|---------|
| `ClubCodeBroadcastReceiver` | `com.ifit.CLUB_PIN` | Receives club PIN codes (async via goAsync()) |

### Content Providers

| Provider | Authority | Purpose |
|----------|-----------|---------|
| `FileContentProvider` | `com.ifit.arda.logprovider` | Read-only log file access (permission: `com.ifit.arda.val_logging_service.PERMISSION`) |

### Notable IPC
- Sends `com.ifit.arda.AWAKE_SLEEP` to ERU on screensaver exit
- Sends `com.ifit.eru.AUTOMATIC_OFFLINE_MODE_ENABLED` during machine setup
- Queries packages: eru, mithlond, glassos_service, launcher, rivendell, gandalf, standalone, eriador

---

## 5. Gandalf — Fitness Login & Workout UI

| | |
|---|---|
| **Package** | `com.ifit.gandalf` |
| **Version** | 1.34.16 (build 923) |
| **Type** | User app |
| **SDK** | min 21 / target 30 |

### Purpose
Main fitness application UI. Handles user authentication, ecosystem selection (cloud vs ReformRx local), workout orchestration, and acts as the entry point for the iFit fitness experience.

### Permissions
**Bluetooth:** Full stack + `BLUETOOTH_PRIVILEGED`

**System:** `FOREGROUND_SERVICE`, `KILL_BACKGROUND_PROCESSES`, `WAKE_LOCK`

**Network:** `INTERNET`, `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE`

**Billing:** `com.android.vending.BILLING`

### Activities

| Activity | Intent Filter | Purpose |
|----------|---------------|---------|
| `SplashScreenActivity` | MAIN/LAUNCHER | Splash → transitions to MainActivity |
| `MainActivity` | `val.gatekeeper.open` | Main app UI hub. Monitors USB for ReformRx device (VID 0x403, PID 0x6001). Sends `com.ifit.eru.KILL_THIRD_PARTY` broadcasts. |
| `LoginActivity` | `val.onboarding.open` | User authentication |
| `ResetPasswordActivity` | `val.login.reset.open` | Password reset |
| `AccountActivationActivity` | `val.login.account.activation.open` | Account activation |
| `AccountSelectionActivity` | `val.accountselection.open` | Multi-account selection |
| `WalkUpScreenActivity` | `val.walkup.open` | Walk-up login with club code |
| `BetaOptInActivity` | `val.betaoptin.open` | Beta testing opt-in |
| `LoadingActivity` | `val.loading.view.open`/`.dismiss` | Loading dialog |
| `ValinorAlertDialogActivity` | `val.alert.dialog.open` | Generic alert |
| `CloseableWebViewActivity` | `eressea.closeablewebview.open` | Embedded web content |
| `NoInternetActivity` | `eressea.nointernet.open` | No internet fallback |

### Services

| Service | Intent Filter | Purpose |
|---------|---------------|---------|
| **GandalfPreloaderService** | `com.ifit.gandalf.GANDALF_PRELOADER` | Foreground preloader (notification ID 8462). Launches MainActivity via PendingIntent. **Starts Mithlond preloader** via `com.ifit.mithlond.MITHLOND_PRELOADER`. |

### Content Providers

| Provider | Authority | Purpose |
|----------|-----------|---------|
| **ContentProviderImpl** | `com.ifit.gandalf.provider` | **Ecosystem selector.** Manages ecosystem state (NotSet, EXTERNAL, ReformRx) via CRUD. Queried by other apps to determine active ecosystem. |
| `FileContentProvider` | `com.ifit.gandalf.logprovider` | Read-only log file access (permission-protected) |

### Notable IPC
- Sends `com.ifit.eru.KILL_THIRD_PARTY` to ERU's `KillThirdPartyAppReceiver`
- Ecosystem content provider queried by other apps: `content://com.ifit.gandalf.provider/ecosystem`
- Starts Mithlond preloader service during initialization
- USB monitoring for ReformRx device (VID 0x0403 / PID 0x6001)

---

## 6. Rivendell — Workout Delivery & Execution

| | |
|---|---|
| **Package** | `com.ifit.rivendell` |
| **Version** | 1.42.14 (build 1159) |
| **Type** | User app (privileged) |
| **SDK** | min 21 / target 30 / compile 34 |

### Purpose
Workout session delivery platform. Manages the full workout lifecycle: pre-workout acceptance, in-workout display with real-time metrics, system overlays, media playback, and post-workout completion/feedback.

### Permissions
**Bluetooth:** Full stack + `BLUETOOTH_PRIVILEGED`

**System:** `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `SYSTEM_ALERT_WINDOW`, `WRITE_SECURE_SETTINGS`, `WRITE_SETTINGS`, `DEVICE_POWER`, `KILL_BACKGROUND_PROCESSES`, `FORCE_STOP_PACKAGES`, `GRANT_RUNTIME_PERMISSIONS`, `CLEAR_APP_USER_DATA`

**Media:** `MEDIA_CONTENT_CONTROL`, `WAKE_LOCK`

**Network:** `INTERNET`, `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE`

### Activities

| Activity | Intent Filter | Purpose |
|----------|---------------|---------|
| `WorkoutAcceptanceActivity` | MAIN/LAUNCHER, `val.preparing.workout.open` | Pre-workout confirmation (sensorLandscape, noHistory). Sends `com.ifit.rivendell.SYSTEM_OVERLAY_START` broadcast. |
| `InWorkoutActivity` | `val.inworkout.open` | **Core workout UI.** Real-time metrics display (sensorLandscape). |
| `WorkoutCompletedActivity` | `val.workout.completed.open` | Post-workout completion screen (noHistory) |
| `LoadingActivity` | `val.loading.view.open`/`.dismiss` | Progress indicator (singleTop, transparent) |
| `CloseableWebViewActivity` | `eressea.closeablewebview.open` | Embedded web content |
| `NoInternetActivity` | `eressea.nointernet.open` | No internet fallback |
| `ValinorAlertDialogActivity` | `val.alert.dialog.open` | Generic alert dialog |
| `FeedbackActivity` | `val.feedback.activity.open` | Post-workout feedback/survey (singleTask) |
| `SafetyKeyActivity` | — | Emergency stop widget (singleInstance, fullscreen translucent) |
| `FatalDialogActivity` | — | Critical error dialog |

### Services

| Service | Intent Filter | Purpose |
|---------|---------------|---------|
| **IpcService** | `com.ifit.rivendell.IpcService` | Messenger-based IPC for cross-app coordination |
| **MediaNotificationListenerService** | `NotificationListenerService` | Monitors active media sessions during workout. Requires `BIND_NOTIFICATION_LISTENER_SERVICE`. |
| **PlayerService** (Feed.FM) | — | Background music playback (foregroundServiceType: mediaPlayback) |

### Broadcast Receivers

| Receiver | Trigger | Purpose |
|----------|---------|---------|
| **RivendellSystemOverlayBroadcastReceiver** | `com.ifit.rivendell.SYSTEM_OVERLAY_START`/`STOP` | Manages system overlay window for on-screen display during workout |
| `ShowDialogReceiver` | `com.ifit.overlay.SHOW_DIALOG` | Display dialog overlays (accepts title, message, buttons, checkbox) |
| `DismissDialogReceiver` | `com.ifit.overlay.DISMISS_DIALOG` | Close dialogs by ID |
| `DialogButtonClickedReceiver` | `com.ifit.overlay.DIALOG_BUTTON_CLICKED` | Handle dialog button clicks (isPositive, checkedByUser) |

### Content Providers

| Provider | Authority | Purpose |
|----------|-----------|---------|
| `FileContentProvider` | `com.ifit.rivendell.logprovider` | Read-only log file access (permission-protected) |

---

## 7. Mithlond — Content Discovery & Dashboard

| | |
|---|---|
| **Package** | `com.ifit.mithlond` |
| **Version** | 1.38.21 (build 1343) |
| **Type** | User app |
| **Note** | No AndroidManifest.xml in decompiled output (heavily obfuscated) |

### Purpose
Content discovery and dashboard app. Provides workout library browsing, challenge/series tracking, search, scheduling, and serves as the content hub for the iFit platform.

### Components (from source analysis)

**Activities:**

| Activity | Purpose |
|----------|---------|
| `MainActivity` | Main app entry point |
| `SplashScreenActivity` | Splash → delegates to MainActivity |
| `GenericActivity` | Base for secondary screens |
| `EnableBluetoothActivity` | BLE enablement |
| `ValinorAlertDialogActivity` | Alert dialogs |

**Services:**

| Service | Purpose |
|---------|---------|
| **MithlondPreloaderService** | Foreground preloader. Pre-caches app data before main launch. Started by Gandalf's `GandalfPreloaderService` via `com.ifit.mithlond.MITHLOND_PRELOADER` intent. |
| **ChallengeDetailsPreloaderService** | Preloads challenge/series data. Rate-limited (500ms minimum). Accepts challenge IDs via extras. |

**Broadcast Receivers:**

| Receiver | Purpose |
|----------|---------|
| `ClearAppDataReceiver` | Clears app data on language changes |
| `AppNotAvailableReceiver` | Handles `com.ifit.mithlond.APP_NOT_AVAILABLE` broadcasts |
| Network connectivity receivers | Monitors `CONNECTIVITY_CHANGE` |

**Content Providers:**

| Provider | Purpose |
|----------|---------|
| `FileContentProvider` | Read-only log file access |

### Key Modules (80+)
- `val_contentdiscovery_core/filters` — Content search/discovery
- `val_dashboard_feature` — Main dashboard
- `val_workout_core` — Core workout logic
- `val_workoutsession_handler` — Session management
- `val_workoutcreator_feature` — Custom workout creation
- `val_schedule_feature` — Workout scheduling
- `val_search_feature` — Search
- `val_saved_feature` — Saved workouts
- `val_seriesdetails_feature` — Challenge/series details
- `val_video_framework` — Video streaming
- `val_realm_service` — Realm local database

---

## Cross-App Communication Summary

### Broadcast Intent Map

| Intent Action | Sender | Receiver | Purpose |
|---------------|--------|----------|---------|
| `com.ifit.eru.KILL_THIRD_PARTY` | Gandalf | ERU | Kill competing fitness apps |
| `com.ifit.eru.AUTOMATIC_OFFLINE_MODE_ENABLED` | Arda | ERU | Control offline mode during setup |
| `com.ifit.arda.AWAKE_SLEEP` | Arda | ERU | Signal wake from screensaver |
| `com.ifit.arda.REQUEST_SLEEP` | (any) | ERU | Trigger immediate sleep |
| `com.ifit.eru.FITPRO_ATTACHED` | (system) | ERU | FitPro hardware detected |
| `com.ifit.eru.USB_PERMISSION_REQUEST` | (apps) | ERU | USB permission grant |
| `com.ifit.eru.PRIVILEGEDMODE` | (apps) | ERU | Toggle developer mode |
| `com.ifit.eru.SET_LANGUAGE` | (apps) | ERU | Set system language |
| `com.ifit.eru.STANDALONE_BOUNCE` | (apps) | ERU | Restart app ecosystem |
| `com.ifit.rivendell.SYSTEM_OVERLAY_START/STOP` | Rivendell | Rivendell | Manage workout overlay |
| `com.ifit.overlay.SHOW_DIALOG` | (apps) | Rivendell | Show overlay dialog |
| `com.ifit.overlay.DISMISS_DIALOG` | (apps) | Rivendell | Dismiss overlay dialog |
| `com.ifit.CLUB_PIN` | (system) | Arda | Club PIN code |
| `com.ifit.gandalf.GANDALF_PRELOADER` | (system) | Gandalf | Start preloader |
| `com.ifit.mithlond.MITHLOND_PRELOADER` | Gandalf | Mithlond | Start preloader |

### Bound Service Map

| Service Intent | App | IPC Type | Consumers |
|----------------|-----|----------|-----------|
| `com.ifit.eru.IpcService` | ERU | Messenger | All apps |
| `com.ifit.glassos.GLASSOS_PLATFORM` | GlassOS | gRPC (54321) + Messenger | All apps |
| `com.ifit.standalone.IpcService` | Standalone | Messenger | All apps |
| `com.ifit.rivendell.IpcService` | Rivendell | Messenger | Gandalf, Arda |
| `com.ifit.appstore.AppStoreManagerService` | ERU | Binder | System |
| `com.ifit.hdmi.HdmiSoundManagerService` | ERU | Binder | System |

### Content Provider Map

| Authority | App | Purpose |
|-----------|-----|---------|
| `com.ifit.gandalf.provider` | Gandalf | Ecosystem state (EXTERNAL/ReformRx) |
| `com.ifit.arda.logprovider` | Arda | Log files |
| `com.ifit.gandalf.logprovider` | Gandalf | Log files |
| `com.ifit.rivendell.logprovider` | Rivendell | Log files |
| `com.ifit.glassos_service.logprovider` | GlassOS | Log files |

### Boot Sequence

```
1. BOOT_COMPLETED
   ├→ ERU: TabletStartupReceiver
   │   → Configure tablet, set time, UUID, permissions, DPI
   │   → Start KeepTheWolfAliveService (watchdog)
   │   → Start InternetMonitoringService
   │   → Start TouchWatchService
   │   → Enqueue update workers
   │
   └→ GlassOS: GlassOSAutoStartReceiver
       → Start GlassOSPlatformService (foreground)
       → Start gRPC server on port 54321

2. App launch (user or auto)
   → Gandalf: GandalfPreloaderService
     → Starts Mithlond: MithlondPreloaderService
     → Both preload data, show foreground notification
     → Launch respective MainActivities
```

---

## Naming Convention (Tolkien Theme)

All iFit internal codenames reference Tolkien's Middle-earth:

| Codename | Tolkien Reference | App Role |
|----------|-------------------|----------|
| **Arda** | The world itself | Console home (the "world" you see first) |
| **ERU** | Eru Ilúvatar (creator god) | System-level creator/controller |
| **Gandalf** | The wizard guide | Guides users through login/onboarding |
| **Rivendell** | Elven sanctuary | Safe workout execution environment |
| **Mithlond** | Grey Havens (port city) | Content discovery/departure point |
| **GlassOS** | (not Tolkien) | Operating system service layer |
| **Standalone** | (not Tolkien) | Legacy self-contained app |
| **Wolf** | (internal daemon) | System daemon monitored by ERU |
| **Valinor** | Blessed realm | UI framework/component library prefix (`val_*`) |
| **Sindarin** | Elven language | Hardware protocol library prefix |
| **Shire** | Hobbit homeland | API/feature services library prefix |
| **Eressea** | Lonely Isle | Web content activities prefix |
| **Eriador** | Region of Middle-earth | Another iFit app (referenced but not present) |
