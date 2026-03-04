# FitPro v2 Protocol Specification

> **Reverse-engineered from decompiled Sindarin.FitPro2.Core (v2.6.88.4692)**
> **The S22i does NOT use this protocol.** FitPro v2 is for newer hardware with USB PID 3.

---

## Packet Structure

```
Byte 0:     Communication Type (0x01 for FitPro2)
Byte 1:     Device | Command (high nibble = device, low nibble = command)
Byte 2:     Payload Length
Bytes 3+:   Payload (command-specific)
```

**No checksum required in v2.**

---

## Device Values (High Nibble of Byte 1)

| Value | Device | Purpose |
|-------|--------|---------|
| 0x00 | App | Mobile/tablet application |
| 0x10 | Console | Main console controller |
| 0x20 | Controller1 | Primary hardware control unit |
| 0x30 | Controller2 | Secondary hardware control unit |

---

## Command IDs (Low Nibble of Byte 1)

| ID | Command | Purpose |
|----|---------|---------|
| 1 | Subscribe | Subscribe to feature value updates |
| 2 | Write | Write a feature value |
| 6 | SupportedFeatures | Query all supported features |
| 7 | Unsubscribe | Unsubscribe from feature updates |
| 9 | Bootloader | Enter bootloader mode |
| 14 | Extended | Extended command |

---

## Response Types

| ID | Type | Purpose |
|----|------|---------|
| 1 | Features | Feature list response |
| 3 | Acknowledge | Command acknowledged |
| 4 | Error | Error response |
| 5 | Event | Unsolicited data update (push notification) |

---

## Feature IDs

Feature IDs are 16-bit values, encoded as 2-byte little-endian in packets.

```csharp
// Encoding
byte[] ToBytes(FeatureId id) => new byte[] { (byte)(id & 0xFF), (byte)((int)id >> 8) };
// Decoding
FeatureId FromBytes(byte msb, byte lsb) => (FeatureId)(msb | (lsb << 8));
```

### Motor Control Features

| ID | Feature | Type | RW | Purpose |
|----|---------|------|----|---------|
| **301** | **TargetKph** | float | RW | **Set target speed (km/h)** |
| 302 | CurrentKph | float | RO | Actual speed |
| 303 | MinKph | float | RO | Minimum speed capability |
| 304 | MaxKph | float | RO | Maximum speed capability |
| **401** | **TargetGradePercent** | float | RW | **Set target incline (%)** |
| 402 | CurrentGradePercent | float | RO | Actual incline |
| 403 | MinGradePercent | float | RO | Minimum incline |
| 404 | MaxGradePercent | float | RO | Maximum incline |
| **501** | **TargetResistancePercent** | float | RW | **Set target resistance (%)** |
| 502 | CurrentResistancePercent | float | RO | Actual resistance |
| 503 | TargetResistanceLevel | float | RW | Resistance level (0-N) |
| 504 | MaxResistance | int | RO | Max resistance level |

### Workout State Features

| ID | Feature | Type | RW | Purpose |
|----|---------|------|----|---------|
| **602** | **WorkoutState** | int | RW | **Set workout state** |
| 612 | StartRequested | bool | RW | Request workout start |
| 613 | ExitWorkoutRequested | bool | RW | Request workout exit |

WorkoutState values: 0=Idle, 1=WarmUp, 2=Running, 3=CoolDown, 4=Results

### Sensor Features

| ID | Feature | Type | Purpose |
|----|---------|------|---------|
| 222 | Pulse | float | Heart rate (bpm) |
| 227 | AveragePulse | float | Average HR |
| 228 | MaxPulse | float | Max HR |
| 252 | Distance | float | Total distance |
| 302 | CurrentKph | float | Current speed |
| 322 | Rpm | int | Current RPM/cadence |
| 344 | RowerStrokesPerMin | float | Rowing cadence |
| 522 | Watts | float | Power output |

---

## Write Command Payload

```
Bytes 0-1:  Feature ID (2-byte little-endian)
Bytes 2-5:  Value (IEEE 754 32-bit float, little-endian)
```

**Total: 6 bytes per write**

Example - Set resistance to 50%:
```
Header:  01 22 06        (FitPro2, Console|Write, 6 bytes payload)
Payload: F5 01 00 00 48 42  (FeatureID 501 LE, float 50.0 LE)
```

---

## Event Response Payload

Unsolicited updates from hardware:
```
Bytes 0-1:  Feature ID (little-endian)
Bytes 2-5:  Float value (IEEE 754, little-endian)
```

Value converters:
- `FloatValueConverter`: Direct float
- `IntValueConverter`: Float cast to int
- `BooleanValueConverter`: Float != 0.0
- `WorkoutStateConverter`: Float to WorkoutState enum

---

## Error Response

```
Byte 0:     Error Type
Bytes 1+:   Error info (variable, feature-specific)
```

| Value | ErrorType | Meaning |
|-------|-----------|---------|
| 0 | NoError | Success |
| 1 | Unassigned | Unassigned error |
| 2 | Framing | Protocol framing error |
| 3 | FeaturesNotSupported | Feature ID not recognized |
| 4 | WriteNotSupported | Feature is read-only |
| 5 | DataOutOfRange | Value outside valid range |
| 6 | CommandNotSupported | Command not implemented |
| 7 | WriteValueNotAllowed | Write denied (state-dependent) |

---

## Communication Lifecycle

```
1. Query Features    → Send SupportedFeatures command
                     ← Receive FeaturesResponse with available feature list

2. Subscribe         → Send Subscribe (up to 8 features per command)
                     ← Receive Event responses confirming subscriptions
                     ← Device begins pushing Event updates for subscribed features

3. Control Loop      → Send Write commands (TargetKph, TargetGradePercent, etc.)
                     ← Receive Acknowledge responses
                     ← Device pushes Event responses for sensor updates

4. Unsubscribe       → Send Unsubscribe commands
                     ← Receive Acknowledge responses
```

---

## Transport

FitPro v2 uses the same transport layers as v1, but is selected when USB PID = 3:

**USB:** Same VID `0x213C`, PID `0x0003`, 64-byte bulk transfers
**BLE:** Same service UUIDs (see [FITPRO_V1.md](./FITPRO_V1.md#transport-usb---primary-for-s22i) for BLE details)

---

## Source Files

```
ifit_apps/standalone/v2.6.88.4692/decompiled_dll/
├── Sindarin.FitPro2.Core/          # FitPro v2 protocol (84 files)
│   ├── Sindarin.FitPro2.Core.Commands/   # Commands
│   ├── Sindarin.FitPro2.Core.Responses/  # Response parsing
│   ├── Sindarin.FitPro2.Core.Features/   # Feature ID definitions
│   └── Sindarin.FitPro2.Core.Responses.DataObjects/  # Error types
```

---

**Last Updated:** 2026-02-10
**Status:** Complete protocol specification from decompiled source
**Source Version:** Standalone v2.6.88.4692
