# Wolf Components Overview

> **"Wolf" is the codename for three related systems in iFit:**
> 1. **Wolf Hardware** - USB HID controller (bike's "brain" / MCU)
> 2. **Wolf Software** - UI framework in Standalone app (Wolf.Core.dll)
> 3. **Wolf Services** - Configuration and data management
>
> **"Sindarin"** is the new hardware abstraction layer (v2.6.88+) that sits between Wolf and the FitPro protocol.

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────┐
│                   Wolf.Core (UI Layer)               │
│  Workout Orchestration, Sliders, Metrics, SmartAdjust│
│  Handler Chain: State→Controls→Runner→SmartAdjust→..│
├─────────────────────────────────────────────────────┤
│                  Sindarin.Core (HAL)                  │
│  IFitnessConsole (62 FitnessValue types)            │
│  Facades: IKphFacade, IResistanceFacade, IGradeFacade│
│  ConsoleInfo, FacadeRepository, State Machine        │
├─────────────────────────────────────────────────────┤
│       Sindarin.FitPro1.Core / FitPro2.Core           │
│  Protocol v1: BitField polling, checksum             │
│  Protocol v2: Feature ID subscribe, no checksum      │
├─────────────────────────────────────────────────────┤
│   Sindarin.Usb.Android / Sindarin.Ble.Android        │
│  USB: Bulk 64-byte, 25ms delay, 10 retries          │
│  BLE: GATT, 400ms delay, Polly backoff              │
├─────────────────────────────────────────────────────┤
│              ERU System Service                       │
│  USB permissions, Watchdog, IPC, Updates             │
├─────────────────────────────────────────────────────┤
│              Wolf MCU Hardware                        │
│  VID 0x213C / PID 0x0002 (Console)                  │
│  VID 0x213C / PID 0x0099 (Bootloader)               │
└─────────────────────────────────────────────────────┘
```

---

## 1. Wolf Hardware Controller (USB HID)

### Device Identification
```
Bus 001 Device 003: ID 213c:0002
Manufacturer: ICON Fitness
Product: ICON Generic HID
Class: HID v1.11
Power: 20mA
```

**USB Endpoints:**
- **EP 81 (Input):** 64 bytes, Interrupt, 1ms interval - Read from bike
- **EP 02 (Output):** 64 bytes, Interrupt, 1ms interval - Write to bike

**System Path:** `/dev/bus/usb/001/003`

### Communication Protocol: FitPro

Two protocol versions exist - see **[PROTOCOL.md](./PROTOCOL.md)** for complete specification.

**FitPro v1 (Legacy):** Polling-based, 4-byte header, checksum, BitField encoding
**FitPro v2 (Modern):** Subscribe-based, 3-byte header, no checksum, Feature ID + float

### Hardware Capabilities (S22i)
- **Resistance control** - 24 levels of magnetic resistance
- **Incline control** - -10% to +20% deck tilt
- **Sensor reading** - Cadence (RPM), power (Watts), heart rate (BLE)
- **Console info** - Model, firmware version, capabilities
- **Workout state machine** - Idle, WarmUp, Workout, CoolDown, Paused, Results

### Security Model

ERU grants USB privileges to authorized apps:
```
Granted USB privilege of ICON Generic HID to com.ifit.standalone - 10050
Granted USB privilege of ICON Generic HID to com.ifit.launcher - 10021
```

No encryption on the protocol. Security relies on Android USB permission model.

---

## 2. Sindarin Hardware Abstraction Layer (NEW in v2.6.88)

### DLL Structure
| Assembly | Files | Size | Purpose |
|----------|-------|------|---------|
| Sindarin.Core | 333 | - | Hardware abstraction, IFitnessConsole |
| Sindarin.FitPro1.Core | 90 | - | FitPro v1 protocol |
| Sindarin.FitPro2.Core | 84 | - | FitPro v2 protocol |
| Sindarin.Usb.Android | 12 | - | USB transport |
| Sindarin.Ble.Android | 11 | - | BLE transport |
| Sindarin.FitPro1.Tcp | 3 | - | TCP transport |
| Sindarin.FitPro1.Ble | 5 | - | BLE FitPro bridge |

### Key Interfaces

**IFitnessConsole** - Main hardware abstraction:
- 62 FitnessValue types (speed, resistance, incline, watts, RPM, HR, etc.)
- Observable streams for real-time sensor updates
- SetValueAsync / GetValueAsync for control
- Console state machine with 8 states

**IConsoleInfo** - Device capabilities:
- Model/part/serial numbers, firmware versions
- Capability flags (CanSetKph, CanSetIncline, CanSetResistance)
- Range limits (min/max for all controllable values)
- Timeout configuration

**IDeviceConnection** - Transport abstraction:
- USB, BLE, TCP implementations
- Retry logic (Polly-based), connection state observables
- 64-byte buffer management

### Control Flow
```
User taps slider in Wolf UI
    ↓
SpeedSlider / ResistanceSlider / InclineSlider
    ↓
IKphFacade / IResistanceFacade / IGradeFacade  (Sindarin facades)
    ↓
IFitnessConsole.SetValueAsync(FitnessValue, value)
    ↓
FitProCommunication (v1 or v2 protocol encoding)
    ↓
UsbConsoleConnection / PersistentConnection (transport)
    ↓
USB bulk write (64 bytes) → Wolf MCU executes
    ↓
USB bulk read (64 bytes) ← Wolf MCU responds
    ↓
Response parsed → Observable streams updated → UI refreshes
```

---

## 3. Wolf UI Framework (Wolf.Core.dll)

### Key Facts
- **Old version:** 470 C# files (v2.2.8.364)
- **New version:** 1,173 C# files (v2.6.88.4692) - 2.5x growth

### Workout Orchestration

Uses **chain-of-responsibility pattern** for initialization:
```
WorkoutContainerStateHandler
  → WorkoutContainerControlsHandler
    → WorkoutContainerRunnerHandler
      → WorkoutContainerSmartAdjust
        → WorkoutContainerHandler
          → WorkoutContainerAnalyticsHandler
            → WorkoutContainerCacheHandler
              → [optional] WorkoutContainerRecoveryHandler
```

### Configuration
- **JSON-driven** console configuration per equipment type
- Embedded JSON configs: `Wolf.Core.Source.Services.WorkoutContainer.Configurations.Json`
- Reflection-based metric instantiation from type names
- Equipment-specific UI (flippers, HUD, charts, sliders)

### Design Patterns
1. **Chain of Responsibility** - Handler pipeline for workout init
2. **Facade Pattern** - Sindarin facades abstract hardware
3. **Reactive Streams** - Rx.NET observables for all state
4. **Dependency Injection** - MvvmCross IoC container
5. **Observer Pattern** - Metrics subscribe to console changes
6. **Strategy Pattern** - Per-console type configurations

---

## 4. ERU System Service

### Role
ERU (Equipment Resource Unit) runs as `android.uid.system` and controls:
- USB device permission management
- Wolf process lifecycle (KeepTheWolfAlive watchdog)
- Firmware updates (brainboard flashing)
- System configuration (immersive mode, Bluetooth, time)
- IPC between apps

### Key Operations
| Operation | Method | Purpose |
|-----------|--------|---------|
| USB access | `GrantUsbPermission()` | Grant USB to Standalone |
| Kill Wolf | `WolfGoKillYourself()` | SIGKILL Standalone |
| Reboot | `Reboot(reason)` | System reboot |
| Install | `InstallPackage(apk)` | Silent APK install |
| Watchdog | `StartWolfWatchDogService()` | Monitor hardware |

### Developer Mode
Activated via **Konami code**: 20 rapid touches (10 in ~5s, then 10 more in 5-10s).
Toggles `InDeveloperMode` flag. Also exposes: `IsTestServer`, `IsUsbUpdateUnrestricted`.

### IPC
- Intent: `com.ifit.eru.IpcService`
- JSON-serialized RPC, 5s timeout
- Methods: `GetConsoleInfo`, `GetConsoleState`

---

## Investigation Status

### Completed (2026-02-10)

**Decompilation:**
- JADX 1.5.3 decompiled all 3 newest APKs (standalone, ERU, launcher)
- ILSpy 9.1 decompiled 11 key DLLs from v2.6.88 (3,314 C# files)
- pyxamstore extracted 268 DLLs from assemblies.blob
- APKTool decoded smali and resources

**Protocol Analysis:**
- FitPro v1 fully documented (packet structure, checksum, BitFields, data encoding)
- FitPro v2 fully documented (Feature IDs, subscribe/write model, error types)
- USB transport: VID 0x213C, PID 2/153, 64-byte bulk, 25ms timing
- BLE transport: Service UUID, characteristics, 400ms timing
- Security: No encryption, plaintext protocol, hash-based device verification

**Software Architecture:**
- Sindarin layer mapped (7 assemblies, 538 files)
- Wolf.Core mapped (1,173 files, handler chain, facades, metrics)
- ERU system service mapped (privileged ops, watchdog, IPC, updates)
- Complete control flow: UI → Facade → Console → Protocol → Transport → Hardware

### Next Steps

- [ ] Capture live USB traffic during workout (enable `send-fitpro-bytes-log`)
- [x] ~~Determine which protocol version S22i uses~~ → **FitPro v1** (USB PID 2 = v1, PID 3 = v2)
- [ ] Write test harness to send FitPro commands directly
- [ ] Build open-source controller library
- [ ] Document complete Feature ID enumeration from source

---

## Related Documentation

- **[HARDWARE.md](./HARDWARE.md)** - USB device specifications and capabilities
- **[PROTOCOL.md](./PROTOCOL.md)** - FitPro protocol overview, transport layers, software architecture
- **[FITPRO_V1.md](./FITPRO_V1.md)** - FitPro v1 protocol specification (used by S22i)
- **[fitpro_v1/](./fitpro_v1/)** - FitPro v1 implementation guide (packet format, commands, BitFields, encoding, connection lifecycle, enums)
- **[FITPRO_V2.md](./FITPRO_V2.md)** - FitPro v2 protocol specification (newer hardware)
- **[../architecture/COMPONENT_ANALYSIS.md](../architecture/COMPONENT_ANALYSIS.md)** - App architecture
- **[../security/README.md](../security/README.md)** - Security analysis
- **[../reverse_engineering/README.md](../reverse_engineering/README.md)** - DLL analysis notes

---

**Last Updated:** 2026-02-10
**Investigation Status:** Protocol fully documented from decompiled source
