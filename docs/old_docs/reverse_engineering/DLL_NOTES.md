# .NET DLL Analysis Notes

> **Decompiled Xamarin.Android DLL assemblies from iFit apps**

## Overview

The real application logic is in .NET assemblies (Xamarin.Android), not the Java code. The Java layer consists of auto-generated stubs in `md5XXXXX.ClassName` format.

**Decompilation Tools Used:**
- **ILSpy** v9.1.0.7988 (ilspycmd) - C# decompilation
- **pyxamstore** v1.0.0 - Extract DLLs from assemblies.blob
- **JADX** v1.5.3 - Java/smali decompilation (for Android layer)
- **APKTool** v2.12.1 - smali + resource decoding

## Decompiled Assemblies

### Old Version (v2.2.8.364 Standalone / v1.2.1.145 ERU)

Individual DLLs available in `decompiled/resources/assemblies/`:

| DLL | C# Files | Purpose |
|-----|----------|---------|
| Wolf.Core.dll (1.3MB) | 470 | Workout UI framework |
| Wolf.Android.dll (1.1MB) | 347 | Android-specific UI |
| Shire.Core.dll | 785 | Shared utilities |
| Eru.Core.dll | 71 | System service core |
| Eru.Android.dll | 57 | Android implementation |

Location: `ifit_apps/standalone/v2.2.8.364/decompiled_dll/` and `ifit_apps/eru/v1.2.1.145/decompiled_dll/`

### New Version (v2.6.88.4692 Standalone)

DLLs packed in `assemblies.blob` format - extracted with pyxamstore (268 total DLLs).

| DLL | C# Files | Purpose |
|-----|----------|---------|
| **Wolf.Core.dll** (4.1MB) | 1,173 | Workout UI - **3x growth** |
| **Wolf.Android.dll** (2.4MB) | 622 | Android UI - **2x growth** |
| **Sindarin.Core.dll** | 333 | **NEW** - Hardware abstraction layer |
| **Sindarin.FitPro1.Core.dll** | 90 | **NEW** - FitPro v1 protocol |
| **Sindarin.FitPro2.Core.dll** | 84 | **NEW** - FitPro v2 protocol |
| **Sindarin.FitPro1.Tcp.dll** | 3 | **NEW** - TCP transport |
| **Sindarin.FitPro1.Ble.dll** | 5 | **NEW** - BLE transport |
| **Sindarin.Usb.Android.dll** | 12 | **NEW** - USB transport |
| **Sindarin.Ble.Android.dll** | 11 | **NEW** - BLE transport |
| Shire.Core.dll | 836 | Shared utilities |
| Shire.Android.dll | 145 | Android utilities |

Location: `ifit_apps/standalone/v2.6.88.4692/decompiled_dll/`
Extracted DLLs: `ifit_apps/standalone/v2.6.88.4692/decompiled/resources/assemblies/out/`

**Total: 3,314 C# files decompiled from 11 key DLLs**

---

## Key Findings

### Sindarin - Hardware Abstraction Layer (NEW)

The biggest discovery is the **Sindarin** namespace, which didn't exist in the old version. It contains the complete hardware communication stack:

**Sindarin.Core** - Defines:
- `IFitnessConsole` interface with 62 FitnessValue types
- `IConsoleInfo` with device capabilities and range limits
- Console state machine (Idle, Locked, WarmUp, Workout, CoolDown, etc.)
- Facade pattern (IKphFacade, IResistanceFacade, IGradeFacade)
- Transport abstractions (IDeviceConnection)
- BLE service UUIDs (`00001533-1412-efde-1523-785feabcd123`)

**Sindarin.FitPro1.Core** - FitPro v1 protocol:
- Packet format: Device(1) + Length(1) + Command(1) + Status(1) + Payload(0-60) + Checksum(1)
- BitField-based data encoding (Speed, Grade, Resistance, Watts, RPM, etc.)
- Checksum: sum of bytes[0..Length-2]
- 28+ command types, 9 status codes
- Security hash algorithm (32-byte XOR-based)

**Sindarin.FitPro2.Core** - FitPro v2 protocol:
- Packet format: Type(1) + Device|Command(1) + PayloadLength(1) + Payload
- Feature ID-based (16-bit LE) with IEEE 754 float values
- Subscribe/Event model (push-based, not polling)
- No checksum required
- Key features: TargetKph(301), TargetGradePercent(401), TargetResistancePercent(501)

### ERU System Service

- USB device management: VID 8508 (0x213C), PID 2 (console) / PID 153 (bootloader)
- Privileged operations: `GrantUsbPermission`, `WolfGoKillYourself`, `Reboot`, `InstallPackage`
- IPC: `com.ifit.eru.IpcService` with JSON RPC (5s timeout)
- KeepTheWolfAlive watchdog: 1s loop, restarts Standalone if crashed
- Developer mode: Konami code (20 rapid touches)
- SharedPrefs: `InDeveloperMode`, `IsTestServer`, `IsUsbUpdateUnrestricted`

### Wolf.Core Workout Framework

- Chain-of-responsibility handler pattern for workout initialization
- Sindarin facade integration (IKphFacade, IResistanceFacade, IGradeFacade)
- Reactive streams (Rx.NET) for all sensor data consumption
- JSON-driven console configuration per equipment type
- SmartAdjust and ActivePulse HR-based features
- MvvmCross dependency injection

---

## Analysis Workflow

### Extracting DLLs from assemblies.blob (v2.6.88+)

```bash
# Activate Python environment
source /tmp/pyxam_env/bin/activate

# Extract DLLs
cd ifit_apps/standalone/v2.6.88.4692/decompiled/resources/assemblies/
pyxamstore unpack --blob-dir .

# DLLs extracted to out/ directory (268 files)
```

### Decompiling with ILSpy

```bash
# Set up .NET 8 runtime for ILSpy
export DOTNET_ROOT="/opt/homebrew/opt/dotnet@8/libexec"

# Decompile to C# project
~/.dotnet/tools/ilspycmd -p -o output_dir/ input.dll

# Key flags:
#   -p    Generate project file
#   -o    Output directory
#   -lv   Language version (e.g., CSharp10_0)
```

### Searching Decompiled Code

```bash
# Find specific class
grep -r "class FitProCommunication" decompiled_dll/

# Find USB-related code
grep -r "VendorId\|ProductId\|UsbDevice" decompiled_dll/

# Find protocol constants
grep -r "MaxMsgLength\|GetCheckSum\|ReadWriteData" decompiled_dll/

# Find feature IDs
grep -r "FeatureId\.\|TargetKph\|TargetResistance" decompiled_dll/
```

---

## Xamarin-Specific Notes

- Main logic in DLLs, not Java code
- Java classes are stubs: `md5XXXXX.ClassName` format
- Class names usually intact (not obfuscated)
- New versions use assemblies.blob (requires pyxamstore)
- Old versions have individual DLLs in assemblies/ directory
- Check `mono` process: `adb shell ps | grep mono`

---

**Last Updated:** 2026-02-10
**Status:** Complete analysis of 11 key DLLs (3,314 C# files)
