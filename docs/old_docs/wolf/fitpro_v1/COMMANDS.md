# FitPro v1 Commands

> Complete reference for all FitPro v1 commands.
> Source: `Command.cs`, individual `*Cmd.cs` files in `Sindarin.FitPro1.Commands/`

---

## Command Summary

| ID | Hex | Name | Content Len | Expects Response | ReadDelay | Timeout | Purpose |
|----|-----|------|-------------|-----------------|-----------|---------|---------|
| 0 | 0x00 | None | - | - | - | - | No operation |
| 1 | 0x01 | PortalDevListen | - | - | - | - | Portal device listener |
| **2** | **0x02** | **ReadWriteData** | **variable** | **yes** | **80ms** | **1000ms** | **Read/write BitField values** |
| 3 | 0x03 | Test | 0 | no | 400ms | 2500ms | Equipment test mode |
| **4** | **0x04** | **Connect** | **0** | **no** | **400ms** | **2500ms** | **Initialize connection** |
| **5** | **0x05** | **Disconnect** | **0** | **no** | **400ms** | **2500ms** | **Close connection** |
| **6** | **0x06** | **Calibrate** | **1** | **yes** | **400ms** | **2500ms** | **Calibrate device** |
| 9 | 0x09 | Update | - | - | - | - | Firmware update |
| 56 | 0x38 | EnterBootloader | 0 | no | 400ms | 2500ms | Enter bootloader mode |
| 112 | 0x70 | SetTestingKey | - | - | - | - | Set testing key |
| 113 | 0x71 | SetTestingTach | - | - | - | - | Set testing tach |
| **128** | **0x80** | **SupportedDevices** | **0** | **yes** | **300ms** | **1000ms** | **List supported devices** |
| **129** | **0x81** | **DeviceInfo** | **0** | **yes** | **300ms** | **1000ms** | **Device info + BitField support** |
| **130** | **0x82** | **SystemInfo** | **2** | **yes** | **300ms** | **1000ms** | **Model, part number, CPU** |
| 131 | 0x83 | TaskInfo | - | - | - | - | Task information |
| **132** | **0x84** | **VersionInfo** | **2** | **yes** | **400ms** | **2500ms** | **Firmware versions** |
| 134 | 0x86 | ModeHistory | - | - | - | - | Mode history |
| **136** | **0x88** | **SupportedCommands** | **0** | **yes** | **300ms** | **1000ms** | **List supported commands** |
| 137 | 0x89 | ReadConfig | - | - | - | - | Read configuration |
| **144** | **0x90** | **VerifySecurity** | **36** | **yes** | **400ms** | **2500ms** | **Unlock with security hash** |
| 145 | 0x91 | ProtocolData | - | - | - | - | Protocol data |
| 146 | 0x92 | SpeedGradeLimit | - | - | - | - | Speed/grade limits |
| **149** | **0x95** | **SerialNumber** | **0** | **yes** | **400ms** | **2500ms** | **Brainboard serial number** |
| 255 | 0xFF | Raw | - | - | - | - | Raw packet |

Bold = used in the standard S22i connection sequence.

---

## Command Details

### Connect (0x04)

Initializes the connection to a device. Fire-and-forget (no response expected).

```
Request:
  [Device] [0x04] [0x04] [Checksum]

Example (FitnessBike):
  07 04 04 0F
```

**Note:** The v2.6.88 codebase does NOT explicitly send Connect during initialization.
The USB transport handles connection at the hardware level. Connect exists for
BLE/TCP transports or legacy compatibility.

---

### Disconnect (0x05)

Closes the connection. Fire-and-forget (no response expected).

```
Request:
  [Device] [0x04] [0x05] [Checksum]

Example (FitnessBike):
  07 04 05 10
```

---

### DeviceInfo (0x81)

Returns device identification and a bitmask of all supported BitFields.
**This is the first command sent during initialization.**

```
Request:
  [Device] [0x04] [0x81] [Checksum]

Example (Main):
  02 04 81 87
```

```
Response:
  Offset  Size  Field
  ──────  ────  ─────
  0       1     Device (echoed, may change to actual device type)
  1       1     Length
  2       1     0x81 (DeviceInfo)
  3       1     Status
  4       1     SoftwareVersion (uint8)
  5       1     HardwareVersion (uint8)
  6       4     SerialNumber (uint32 LE)
  10      2     Manufacturer (uint16 LE, enum Manufacturer)
  12      1     Sections (number of bitmask bytes that follow)
  13      N     BitField bitmask (one byte per section, 8 bits each)
  L-1     1     Checksum
```

**Parsing the BitField bitmask:**
```python
supported_fields = []
for section_index in range(sections):
    byte = response[13 + section_index]
    for bit in range(8):
        if byte & (1 << bit):
            field_id = section_index * 8 + bit
            supported_fields.append(field_id)
```

ReadDelay: 300ms | Timeout: 1000ms

---

### SupportedDevices (0x80)

Returns a list of sub-device types supported by this controller.

```
Request:
  [Device] [0x04] [0x80] [Checksum]

Example (Main):
  02 04 80 86
```

```
Response:
  Offset  Size  Field
  ──────  ────  ─────
  0-3     4     Header (Device, Length, 0x80, Status)
  4       1     Count (number of device IDs)
  5       N     Device IDs (one byte each, enum Device)
  L-1     1     Checksum
```

ReadDelay: 300ms | Timeout: 1000ms

---

### SupportedCommands (0x88)

Returns a set of command IDs supported by this device.

```
Request:
  [Device] [0x04] [0x88] [Checksum]

Example (FitnessBike):
  07 04 88 93
```

```
Response:
  Offset  Size  Field
  ──────  ────  ─────
  0-3     4     Header
  4       N     Command IDs (one byte each, enum Command)
                N = ResponseLength - 4 - 1 (subtract header and checksum)
  L-1     1     Checksum
```

ReadDelay: 300ms | Timeout: 1000ms

---

### SystemInfo (0x82)

Returns model number, part number, CPU info, and configuration.

```
Request:
  [Device] [0x06] [0x82] [fetchMcuName] [0x00] [Checksum]

  fetchMcuName: 0x00 = no MCU name, 0x01 = include MCU name

Example (Main, no MCU name):
  02 06 82 00 00 8A
```

```
Response:
  Offset  Size  Field
  ──────  ────  ─────
  0-3     4     Header
  4       2     ConfigSize (uint16 LE)
  6       1     Configuration (enum: 0=Slave, 1=Master, 2=MultiMaster,
                  3=SingleMaster, 4=PortalToSlave, 5=PortalToMaster)
  7       4     Model (uint32 LE)
  11      4     PartNumber (uint32 LE)
  15      2     CpuUse (uint16 LE, divide by 1000.0 for percentage)
  17      1     NumberOfTasks (uint8)
  18      2     IntervalTime (uint16 LE)
  20      4     CpuFrequency (uint32 LE, Hz)
  24      2     PollingFrequency (uint16 LE)
  26      1     IsUnitMetricDefault (bool)
  27      1     IsMarketTypeClub (0x00=Home, 0x01=Club)
  28      1     FitProConfigLibVersion (uint8)
  29      1     DefaultLanguage (enum: 0=None, 1=German, 2=English,
                  3=Spanish, 4=French, 5=Italian, 6=Dutch, 7=Russian,
                  8=Portuguese, 9=Chinese, 10=Japanese)
  30      1     McuNameLength
  31      N     McuName bytes (ASCII)
  31+N    1     ConsoleNameLength
  32+N    M     ConsoleName bytes (ASCII)
  L-1     1     Checksum
```

**Special case:** If PartNumber=370357 and Model=39915, override PartNumber to 374677.

ReadDelay: 300ms | Timeout: 1000ms

---

### VersionInfo (0x84)

Returns firmware library and BLE versions.

```
Request:
  [Device] [0x06] [0x84] [fetchMcuName] [fetchConsoleName] [Checksum]

Example (Main, no names):
  02 06 84 00 00 8C
```

```
Response:
  Offset  Size  Field
  ──────  ────  ─────
  0-3     4     Header
  4       1     MasterLibraryVersion (uint8)
  5       2     MasterLibraryBuild (uint16 LE)
  7       17    IconBleLibVersion (UTF-8 string, "NO BLE" if byte[0]==0x00)
  24      1     ConfigToolVersion (uint8)
  25      2     BleSdkVersion (uint16 LE)
  L-1     1     Checksum
```

ReadDelay: 400ms (default) | Timeout: 2500ms (default)

---

### SerialNumber (0x95)

Returns the brainboard's serial number as raw bytes.

```
Request:
  [Device] [0x04] [0x95] [Checksum]

Example (Main):
  02 04 95 9B
```

```
Response:
  Offset  Size  Field
  ──────  ────  ─────
  0-3     4     Header
  4       1     SerialLength (number of serial bytes)
  5       N     Serial bytes (raw)
  L-1     1     Checksum
```

**Parsing:** If all bytes are 0xFF or all are 0x00, the serial is invalid (return null).
Otherwise, format as dash-separated hex: `BitConverter.ToString(bytes)` → "XX-XX-XX-..."

ReadDelay: 400ms (default) | Timeout: 2500ms (default)

---

### VerifySecurity (0x90)

Unlocks the device for write operations. Required when SoftwareVersion > 75.

```
Request:
  [Device] [0x28] [0x90] [hash: 32 bytes] [secretKey: 4 bytes LE] [Checksum]

  ContentLength = 36 (32-byte hash + 4-byte secret key)
  secretKey = 8 * MasterLibraryVersion (from VersionInfo)
  hash = CalculateSecurityHash(serialNumber, partNumber, modelNumber)
```

```
Response:
  Offset  Size  Field
  ──────  ────  ─────
  0-3     4     Header
  4       1     UnlockedKey (uint8)
  L-1     1     Checksum
```

**Security is unlocked when Status == Done (0x02).**

See [CONNECTION.md](CONNECTION.md) for the hash algorithm.

ReadDelay: 400ms (default) | Timeout: 2500ms (default)

---

### ReadWriteData (0x02)

The primary command for reading sensor values and controlling the equipment.
**See [READWRITE.md](READWRITE.md) for the complete deep dive.**

```
Request:
  [Device] [Length] [0x02] [WriteSection] [ReadSection] [Checksum]

Response:
  [Device] [Length] [0x02] [Status] [ReadValues...] [Checksum]
```

ReadDelay: 80ms | Timeout: 1000ms

---

### Calibrate (0x06)

Initiates device calibration (e.g., incline motor calibration).

```
Request:
  [Device] [0x05] [0x06] [CalibrationType] [Checksum]

  CalibrationType: 0x00 = default

Example (Grade device, default calibration):
  42 05 06 00 4D
  (Device 66=Grade, Length 5, Command 6, Type 0, Checksum)
```

```
Response:
  Offset  Size  Field
  ──────  ────  ─────
  0-3     4     Header (Status indicates calibration state)
  L-1     1     Checksum
```

**Status interpretation:**
- `Done` (2) → Calibration complete
- `InProgress` (3) → Still calibrating (poll again after 4 seconds)
- `Failed` (4) → Calibration failed

ReadDelay: 400ms (default) | Timeout: 2500ms (default)

---

### EnterBootloader (0x38)

Switches the MCU to bootloader mode for firmware updates.
**Fire-and-forget. Use with extreme caution.**

```
Request:
  [Device] [0x04] [0x38] [Checksum]

Example (Main):
  02 04 38 3E
```

No response expected. After this command, the USB device will re-enumerate
with PID 0x0099 (bootloader mode) instead of PID 0x0002 (console mode).

---

### Test (0x03)

Enters equipment test mode. Fire-and-forget.

```
Request:
  [Device] [0x04] [0x03] [Checksum]
```

---

### Commands Always Approved

These commands can be sent to any device without checking SupportedCommands:

```python
ALWAYS_APPROVED = [
    0x82,  # SystemInfo
    0x81,  # DeviceInfo
    0x80,  # SupportedDevices
    0x88,  # SupportedCommands
    0x02,  # ReadWriteData
    0x90,  # VerifySecurity
    0x06,  # Calibrate
]
```

---

**Last Updated:** 2026-02-10
**Source:** `Sindarin.FitPro1.Commands/` (all command files)
