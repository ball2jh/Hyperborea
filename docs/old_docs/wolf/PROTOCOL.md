# FitPro Protocol - Overview

> **Reverse-engineered from decompiled Sindarin.FitPro1.Core and Sindarin.FitPro2.Core (v2.6.88.4692)**

## Overview

The NordicTrack S22i communicates with its hardware controller (Wolf MCU) using the **FitPro protocol** - a proprietary binary protocol that exists in two versions.

**The S22i uses FitPro v1.** Protocol version is determined by USB Product ID: PID 2 = v1, PID 3 = v2. The S22i reports PID 0x0002. Selection logic is in `PlatformWolfAppSetup.CreateConsole()` which switches on `UsbProduct` enum.

| Aspect | FitPro v1 (Legacy) | FitPro v2 (Modern) |
|--------|--------------------|--------------------|
| Header Size | 4 bytes | 3 bytes |
| Max Packet | 64 bytes | 64 bytes |
| Checksum | Yes (1 byte sum) | No |
| Value Encoding | BitField-based (mixed types) | Feature ID + IEEE 754 float |
| Commands | 28+ types | 6 types |
| Status Reporting | Per-command status byte | Response type + error code |
| Unsolicited Updates | No (polling) | Yes (Event responses) |
| Model | Request/Response polling | Publish/Subscribe |

## Protocol Specifications

- **[FITPRO_V1.md](./FITPRO_V1.md)** - FitPro v1 protocol (used by S22i)
- **[FITPRO_V2.md](./FITPRO_V2.md)** - FitPro v2 protocol (newer hardware with PID 3)

**Transport layers:** USB HID, Bluetooth LE, TCP
**Source files:** `Sindarin.FitPro1.Core/`, `Sindarin.FitPro2.Core/`
**Implementation:** C# (.NET/Xamarin)

---

## Transport Layers

### USB (Primary - S22i)

**Device:**
- Vendor ID: `0x213C` (8508) - ICON Fitness
- Product ID: `0x0002` (console) / `0x0099` (153, bootloader)
- Endpoints: EP 0x81 IN (64 bytes), EP 0x02 OUT (64 bytes)
- Transfer type: Bulk (via USBFS, not kernel HID driver)

**Timing:**
- Read delay: 25ms between write and read
- Write timeout: 50ms
- Max read retries: 10
- Buffer size: 64 bytes per transfer

**Buffer clearing:** Sends `0xFF` pattern bytes, validates response with wildcard matching.

### Bluetooth LE

**FitPro Service UUID:** `00001533-1412-efde-1523-785feabcd123`

| UUID | Name | Direction | Type |
|------|------|-----------|------|
| `00001534-1412-efde-1523-785feabcd123` | DeviceRx | App -> Device | Write |
| `00001535-1412-efde-1523-785feabcd123` | DeviceTx | Device -> App | Notify/Read |

**DFU Service UUID:** `00001530-1212-efde-1523-785feabcd123`

**BLE Protocol Flow:**
1. Connect to device, discover service UUID `00001533-...`
2. Subscribe to DeviceTx characteristic for notifications
3. Write FitPro packets to DeviceRx characteristic
4. Receive responses via DeviceTx notifications

**BLE Timing:**
- Read delay: 400ms
- Reconnection: Polly-based retry with exponential backoff (max 4 retries)
- Console backoff: delay = count^2
- Wearable backoff: [10s, 30s, 60s, 120s, 240s]

**Multi-packet framing (BLE/TCP):**
- Start marker: `0xFE` (first packet)
- End marker: `0xFF` (last packet)

### TCP

Raw socket communication with same packet framing as BLE:
- Start: `0xFE`, End: `0xFF`
- Timeout: 1 second
- Read delay: 400ms

---

## Software Architecture

### Layer Diagram

```
┌─────────────────────────────────────────────────────┐
│                   Wolf.Core (UI)                     │
│  SpeedSlider ─→ IKphFacade                          │
│  ResistanceSlider ─→ IResistanceFacade              │
│  InclineSlider ─→ IGradeFacade                      │
├─────────────────────────────────────────────────────┤
│                  Sindarin.Core                        │
│  IFitnessConsole (62 FitnessValue types)            │
│  ConsoleInfoBase (device capabilities)              │
│  FacadeRepository (read/write facades)              │
├─────────────────────────────────────────────────────┤
│          Sindarin.FitPro1.Core / FitPro2.Core       │
│  FitProCommunication (protocol implementation)      │
│  Commands, Responses, Feature/BitField encoding     │
├─────────────────────────────────────────────────────┤
│    Sindarin.Usb.Android / Sindarin.Ble.Android      │
│  BaseAndroidUsbDevice / BleDevice                   │
│  UsbConsoleConnection / PersistentConnection        │
├─────────────────────────────────────────────────────┤
│              Android USB / BLE APIs                  │
│  UsbManager, BluetoothGatt, BroadcastReceivers     │
└─────────────────────────────────────────────────────┘
```

### Console States (IFitnessConsole)

```
Idle → Locked → WarmUp → Workout → CoolDown → WorkoutResults → Idle
                  ↕                    ↕
               Paused ←──────── PauseOverride
```

### 62 FitnessValue Types

The `IFitnessConsole` interface supports 62 named fitness values:

**Speed:** Kph, AvgSpeedKph, ActualKph, MaxKph, MinKph
**Incline:** Grade, ActualIncline, AverageGrade, MaxGrade, MinGrade
**Resistance:** Resistance, MaxResistanceLevel
**Power:** Watts, AverageWatts, WattGoal
**Cadence:** Rpm, StrokesPerMin
**Distance:** CurrentDistance, MotorTotalDistance, DistanceGoal
**Time:** RunningTime, TotalTime, LapTime, CurrentTime, PausedTime
**Calories:** CurrentCalories
**Heart Rate:** Pulse, MaxPulse, EffortScore
**Equipment:** Gear, Volume, FanState, IsConstantWattsMode
**Device:** DeviceType, IsReadyToDisconnect, IsClubUnit, IdleModeLockout
**Activation:** ActivationLock, StartRequested, RequireStartRequested

### IConsoleInfo (Device Capabilities)

```csharp
interface IConsoleInfo {
    int ModelNumber, PartNumber, SerialNumber;
    int SoftwareVersion, HardwareVersion;
    string FirmwareVersion, BrainboardSerialNumber;
    ConsoleType MachineType;
    Manufacturer ManufacturerId;

    // Capability flags
    bool CanSetKph, CanSetIncline, CanSetResistance, CanSetGear;
    bool SupportsPulse, SupportsVerticalGain, SupportsConstantWatts;

    // Range limits
    double MaxKph, MinKph;
    double MaxIncline, MinIncline;
    double MaxResistanceLevel, MinResistanceLevel;
    int MaxGear, MinGear;
    double MaxWeight;

    // Timeouts (seconds)
    int WarmUpTimeoutSeconds, CoolDownTimeoutSeconds;
    int PauseTimeoutSeconds, IdleTimeoutSeconds;
}
```

---

## ERU System Service

ERU (Equipment Resource Unit) acts as the privileged control plane:

### USB Device Management
- **VID 8508 (0x213C), PID 2**: Console (Wolf MCU)
- **VID 8508 (0x213C), PID 153 (0x99)**: Bootloader mode
- Grants USB permissions to Standalone via reflection on `UsbManager.grantDevicePermission()`
- Auto-grants on USB attach via `UsbDeviceAttachedReceiver`

### IPC (ERU <-> Standalone)
- Intent filter: `com.ifit.eru.IpcService`
- JSON-serialized RPC with 5-second timeout
- Key methods: `GetConsoleInfo`, `GetConsoleState`
- Fallback: Direct USB if Standalone isn't running

### KeepTheWolfAlive Watchdog
- 1-second check loop
- Detects bootloader (>3 sightings -> recovery mode)
- Restarts Standalone if not running and brainboard is attached

### Privileged Operations
```
GrantUsbPermission(package)     - USB access control
WolfGoKillYourself()            - Force-kill Standalone (SIGKILL)
Reboot(reason)                  - System reboot
InstallPackage(apkFile)         - Silent APK installation
StartWolfWatchDogService()      - Start watchdog
SetImmersiveFull()              - Fullscreen mode
IsBootloaderAttached()          - USB enumeration check
IsBrainboardAttached()          - USB enumeration check
```

### Developer Mode (Konami Code)
- 20 rapid touches: first 10 in ~5s, next 10 within 5-10s window
- Toggles `InDeveloperMode` in SharedPreferences
- Also available: `IsTestServer`, `IsUsbUpdateUnrestricted` flags

### Brainboard Firmware Update Flow
```
1. Kill Standalone (WolfGoKillYourself)
2. Connect to console via FitPro USB
3. Send ResetBrainboard() → enters bootloader
4. Wait for bootloader USB attach (max 30s)
5. Flash firmware row-by-row with checksum verification
6. Wait for brainboard re-attach (max 60s)
7. Grant USB permissions, restart Standalone
```

---

## No Encryption

Both protocol versions transmit all values in **plaintext**. There is no encryption layer. Security relies on:
- USB physical access / Android permission model
- BLE pairing (if configured)
- VerifySecurity hash (equipment validation only, not comms encryption)

---

## Source File Locations

```
ifit_apps/standalone/v2.6.88.4692/decompiled_dll/
├── Sindarin.FitPro1.Core/          # FitPro v1 protocol (90 files)
│   ├── Sindarin.FitPro1.Commands/  # Command implementations
│   ├── Sindarin.FitPro1.Communication/  # Transport layer
│   └── Sindarin.FitPro1.Core/      # Core types, enums, converters
├── Sindarin.FitPro2.Core/          # FitPro v2 protocol (84 files)
│   ├── Sindarin.FitPro2.Core.Commands/   # Commands
│   ├── Sindarin.FitPro2.Core.Responses/  # Response parsing
│   ├── Sindarin.FitPro2.Core.Features/   # Feature ID definitions
│   └── Sindarin.FitPro2.Core.Responses.DataObjects/  # Error types
├── Sindarin.Core/                  # Hardware abstraction (333 files)
│   ├── Sindarin.Core.Console/      # IFitnessConsole, ConsoleInfo
│   ├── Sindarin.Core.Ble/          # BLE abstraction
│   ├── Sindarin.Core.Ble.KnownTypes/  # Service/Characteristic UUIDs
│   └── Sindarin.Core.Connections/  # Connection management
├── Sindarin.Usb.Android/           # USB transport (12 files)
├── Sindarin.Ble.Android/           # BLE transport (11 files)
├── Sindarin.FitPro1.Tcp/           # TCP transport (3 files)
├── Sindarin.FitPro1.Ble/           # BLE FitPro (5 files)
├── Wolf.Core/                      # UI & workout orchestration (1,173 files)
└── Shire.Core/                     # Shared utilities (836 files)

ifit_apps/eru/v1.2.1.145/decompiled_dll/
├── Eru.Core/                       # System service core (71 files)
│   ├── Eru.Core.Privileged/        # Privileged operations
│   └── Eru.Core.Services/          # Service interfaces
└── Eru.Android/                    # Android implementation (57 files)
```

---

**Last Updated:** 2026-02-10
**Status:** Complete protocol specification from decompiled source
**Source Version:** Standalone v2.6.88.4692, ERU v1.2.1.145
