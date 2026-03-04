# Reverse Engineering Workflow

> **Tools, methodology, and findings for reverse engineering iFit applications**

This directory contains reverse engineering notes, DLL analysis, and protocol documentation for the NordicTrack S22i iFit platform.

## Toolchain

### .NET Assembly Analysis

#### dnSpy (Recommended)
**Platform:** Windows (can run via Wine on macOS)
**Purpose:** Debug and decompile .NET assemblies

**Features:**
- Decompile C# code from DLLs
- Edit and recompile assemblies
- Debug running applications
- Search across all types/methods
- Export decompiled source

**Usage:**
```bash
# Download from: https://github.com/dnSpy/dnSpy
# Load DLL: File → Open → Select .dll file
# Navigate: Explore tree or Ctrl+Shift+K (search)
# Export: File → Export to Project (C# code)
```

**Target DLLs:**
- `ifit_apps/decompiled/eru/resources/assemblies/Eru.Core.dll`
- `ifit_apps/decompiled/standalone/resources/assemblies/Wolf.Core.dll`
- `ifit_apps/decompiled/standalone/resources/assemblies/Shire.Core.dll`

#### ILSpy (Cross-Platform Alternative)
**Platform:** Windows, macOS, Linux
**Purpose:** .NET decompiler (read-only)

**Installation (macOS):**
```bash
brew install --cask ilspy
```

**Usage:**
```bash
# Open ILSpy GUI
open -a ILSpy

# Or use command-line
ilspycmd Eru.Core.dll -o eru_core_decompiled/
```

### APK Decompilation

#### JADX (Java Decompiler)
**Platform:** Cross-platform (Java-based)
**Purpose:** Decompile Java/Kotlin code from APKs

**Installation:**
```bash
brew install jadx
```

**Usage:**
```bash
# Decompile entire APK
jadx -d output_dir/ input.apk

# GUI mode
jadx-gui input.apk
```

**Notes:**
- Xamarin apps contain mostly stub Java code
- Real logic is in .NET DLLs (see dnSpy/ILSpy above)
- Use for AndroidManifest.xml and resource extraction

#### APKTool (Resource Extraction)
**Platform:** Cross-platform (Java-based)
**Purpose:** Extract resources, manifest, smali code

**Installation:**
```bash
brew install apktool
```

**Usage:**
```bash
# Decode APK
apktool d input.apk -o output_dir/

# Extract manifest
apktool d input.apk -o output_dir/ --no-src

# Rebuild APK (for testing modifications)
apktool b output_dir/ -o modified.apk
```

### Native Library Analysis

#### Ghidra
**Platform:** Cross-platform (Java-based)
**Purpose:** Disassemble native libraries (.so files)

**Installation:**
```bash
# Download from: https://ghidra-sre.org/
# Requires JDK 11+
```

**Target Libraries:**
- `ifit_apps/apks/lib/arm64-v8a/*.so` (native ARM64 libraries)
- Look for: `libmonodroid.so`, `libmono-android.debug.so`, custom JNI libs

#### Hopper Disassembler (macOS)
**Platform:** macOS, Linux
**Purpose:** Native code disassembly (commercial)

**Installation:**
```bash
brew install --cask hopper-disassembler
```

### USB Protocol Analysis

#### Wireshark + USBPcap
**Platform:** Windows (for USB capture), macOS (for analysis)
**Purpose:** Capture and analyze USB traffic

**Workflow:**
1. Capture USB traffic on Windows PC between bike and PC
2. Export pcap file
3. Analyze in Wireshark on macOS

**Alternative: On-Device Capture**
```bash
# Enable USB debugging on device
adb root
adb shell mount -o remount,rw /sys/kernel/debug

# Start capture (requires kernel support)
adb shell cat /sys/kernel/debug/usb/usbmon/0u > usb_capture.txt

# Perform action on bike (change speed, resistance, etc.)
# Ctrl+C to stop

# Pull capture
adb pull /data/local/tmp/usb_capture.txt
```

#### sigrok + PulseView (Logic Analyzer)
**Platform:** Cross-platform
**Purpose:** Hardware-level protocol analysis

**Installation:**
```bash
brew install sigrok-cli pulseview
```

**Use case:** If USB serial protocol needs electrical-level analysis

### String Extraction

```bash
# Extract strings from .NET DLL
strings Eru.Core.dll | grep -iE "usb|serial|motor|resistance"

# Extract strings from native .so
strings libmonodroid.so | less

# Search for URLs/endpoints
strings Eru.Core.dll | grep -E "http|api|endpoint"
```

## Decompilation Status

### iFit Apps (Completed ✅)

| App | Version | Decompiled | Location |
|-----|---------|------------|----------|
| iFit Launcher | 1.0.12 | ✅ Yes | `ifit_apps/decompiled/launcher/` |
| iFit ERU | 1.2.1.145 | ✅ Yes | `ifit_apps/decompiled/eru/` |
| iFit Standalone | 2.2.8.364 | ✅ Yes | `ifit_apps/decompiled/standalone/` |

**Decompiler Used:** JADX v1.4.7

**Contents:**
- `sources/` - Java code (mostly Xamarin stubs)
- `resources/` - Assets, manifest, layouts
- `resources/assemblies/` - **.NET DLLs (primary logic)**

### .NET DLLs (In Progress 🔄)

| DLL | App | Status | Analysis Doc |
|-----|-----|--------|--------------|
| Eru.Core.dll | ERU | 🔄 In Progress | [dll_analysis/Eru.Core.md](dll_analysis/Eru.Core.md) |
| Eru.Android.dll | ERU | 📋 Planned | - |
| Wolf.Core.dll | Standalone | 🔄 In Progress | [dll_analysis/Wolf.Core.md](dll_analysis/Wolf.Core.md) |
| Wolf.Android.dll | Standalone | 📋 Planned | - |
| Shire.Core.dll | Standalone | 📋 Planned | [dll_analysis/Shire.Core.md](dll_analysis/Shire.Core.md) |

## Reverse Engineering Workflow

### Phase 1: Reconnaissance

1. **Extract APK**
   ```bash
   adb shell pm path com.ifit.eru
   adb pull /system/priv-app/com.ifit.eru-1.2.1.145/com.ifit.eru-1.2.1.145.apk
   ```

2. **Decompile with JADX**
   ```bash
   jadx -d eru_decompiled/ com.ifit.eru-1.2.1.145.apk
   ```

3. **Locate .NET DLLs**
   ```bash
   find eru_decompiled/ -name "*.dll"
   # Typically in: resources/assemblies/
   ```

4. **Extract File Metadata**
   ```bash
   file Eru.Core.dll
   strings Eru.Core.dll | head -100
   ```

### Phase 2: Static Analysis

1. **Load DLL in dnSpy/ILSpy**
   - Open dnSpy
   - File → Open → Select DLL
   - Explore namespace tree

2. **Identify Key Classes**
   - Look for: USB, Serial, Device, Motor, Resistance, Incline
   - Search (Ctrl+Shift+K): "SendUsb", "WriteUsb", "ReadUsb"
   - Search: "Motor", "Resistance", "Incline", "Speed"

3. **Document Class Hierarchy**
   ```
   Namespace: Com.Ifit.Eru.Core
     ├── UsbManager
     │   ├── CustomUsbDevice
     │   └── UsbStickService
     ├── DeviceController
     │   ├── MotorController
     │   ├── ResistanceController
     │   └── InclineController
     └── Commands
         ├── SetSpeedCommand
         ├── SetResistanceCommand
         └── SetInclineCommand
   ```

4. **Extract Method Signatures**
   ```csharp
   // Example from Eru.Core.dll
   public bool SendUsbWriteRequest(byte[] data);
   public byte[] SendUsbReadRequest(int length);
   public void SetMotorSpeed(int speed);
   ```

5. **Document Constants**
   - Look for: Command bytes, device IDs, protocol markers
   - Example: `const byte MOTOR_CMD = 0x01;`

### Phase 3: Dynamic Analysis (Future)

**Requirements:**
- Rooted device with debugger support
- Modified APK with debugging enabled
- dnSpy debugger attached to running process

**Steps:**
1. Enable debugging in AndroidManifest.xml
2. Rebuild APK with apktool
3. Sign and install modified APK
4. Attach dnSpy debugger over network
5. Set breakpoints in key methods
6. Trigger actions on bike
7. Inspect variables, call stacks

**Status:** 📋 Planned (not yet attempted)

### Phase 4: Protocol Documentation

1. **Map Command Structure**
   ```
   USB Command Format:
   [Header] [Length] [Command] [Payload] [Checksum]
   ```

2. **Document All Commands**
   - See: [../wolf/COMMAND_MAPPING.md](../wolf/COMMAND_MAPPING.md)
   - See: [../wolf/HARDWARE_PROTOCOL.md](../wolf/HARDWARE_PROTOCOL.md)

3. **Test Custom Commands**
   - Write test app
   - Send crafted USB commands
   - Verify bike response

## Key Findings Repository

### DLL Analysis Documents

Each DLL gets its own analysis document in `dll_analysis/`:

**Template:**
- **Assembly Info** - Name, version, size, dependencies
- **Namespaces** - Key namespaces and purpose
- **Critical Classes** - Important classes and methods
- **Strings Analysis** - Interesting strings found
- **Protocol Details** - Command/response formats discovered
- **Next Steps** - Further investigation needed

**Documents:**
- [dll_analysis/Eru.Core.md](dll_analysis/Eru.Core.md) - USB hardware control
- [dll_analysis/Wolf.Core.md](dll_analysis/Wolf.Core.md) - Workout UI framework
- [dll_analysis/Shire.Core.md](dll_analysis/Shire.Core.md) - Shared libraries

### USB Protocol Captures

Store raw captures in `usb_captures/`:
```
usb_captures/
├── speed_change_10_to_12.pcap      # Speed change from 10 to 12 mph
├── resistance_increase_5.pcap       # Resistance +5 levels
├── incline_up_2_percent.pcap        # Incline +2%
└── emergency_stop.pcap              # Emergency stop button
```

**Capture Metadata:**
- Timestamp
- Action performed
- Expected command
- Observed response
- Analysis notes

## Investigation Priorities

### 🔴 High Priority

1. **Eru.Core.dll USB Communication**
   - Find `SendUsbWriteRequest` implementation
   - Document command format
   - Map all command types
   - **Goal:** Full USB protocol documentation

2. **Wolf MCU Command Set**
   - Extract command bytes from Eru.Core.dll
   - Test commands on device
   - Document safe ranges (speed limits, etc.)
   - **Goal:** Complete command reference

### 🟡 Medium Priority

3. **Wolf.Core.dll Workout Logic**
   - Map workout state machine
   - Identify IPC calls to ERU
   - Document preset configurations
   - **Goal:** Understand workout orchestration

4. **IPC Message Format**
   - Analyze Standalone → ERU intents
   - Document intent actions and extras
   - Identify authentication (if any)
   - **Goal:** IPC protocol documentation

### 🟢 Low Priority

5. **Shire.Core.dll Shared Code**
   - Identify utility functions
   - Look for crypto/obfuscation
   - Document common data structures

6. **Native Libraries**
   - Analyze libmonodroid.so
   - Look for JNI bridges
   - Check for anti-debugging

## Common Search Patterns

When analyzing DLLs in dnSpy:

```
Search Pattern                  | Looking For
-------------------------------|----------------------------------
"Usb"                          | USB communication code
"Serial"                       | Serial port handling
"Motor" / "Speed"              | Motor control methods
"Resistance"                   | Resistance control
"Incline"                      | Incline control
"Sensor"                       | Sensor reading (cadence, HR)
"Command" / "Request"          | Command structures
"Response"                     | Response parsing
"Broadcast" / "Intent"         | IPC mechanisms
"Privileged"                   | Privileged operations (ERU)
"Package" / "Install"          | App installation (ERU)
"Reboot" / "Recovery"          | System control (ERU)
"Http" / "Api" / "Endpoint"    | Network communication
"Bluetooth"                    | BLE device handling
```

## Debugging Tips

### Xamarin Apps
- Main logic is in .NET DLLs, not Java code
- Look for `mono` in process list: `adb shell ps | grep mono`
- Java classes are mostly stubs: `md5XXXX.ClassName` (MD5 hash namespace)

### Finding Key Code
- Start with string searches (URLs, error messages)
- Follow method references backwards
- Look for exported functions (JNI, IPC)
- Check static constructors for initialization

### Understanding Obfuscation
- Xamarin apps typically not heavily obfuscated
- Class names usually intact (not renamed to a/b/c)
- If obfuscated, look for string patterns, numeric constants

## Related Documentation

- **[../wolf/README.md](../wolf/README.md)** - Wolf components overview
- **[../wolf/HARDWARE_PROTOCOL.md](../wolf/HARDWARE_PROTOCOL.md)** - USB protocol docs
- **[../architecture/COMPONENT_ANALYSIS.md](../architecture/COMPONENT_ANALYSIS.md)** - App architecture
- **[../security/README.md](../security/README.md)** - Security findings

## External Resources

- Xamarin.Android internals: https://docs.microsoft.com/xamarin/android/internals/
- .NET reverse engineering: https://github.com/0xd4d/dnSpy/wiki
- Android IPC: https://developer.android.com/guide/components/intents-filters
- USB serial: https://developer.android.com/guide/topics/connectivity/usb/host

---

**Last Updated:** 2026-02-10
**Primary Tools:** dnSpy (DLL analysis), JADX (APK decompilation)
**Status:** Eru.Core.dll and Wolf.Core.dll analysis in progress
