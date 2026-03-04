# FitPro v1 Implementation Guide

> Complete implementation reference for the FitPro v1 protocol used by the NordicTrack S22i.
> Reverse-engineered from `Sindarin.FitPro1.Core` (v2.6.88.4692).

---

## Protocol at a Glance

| Property | Value |
|----------|-------|
| Transport | USB bulk (64-byte packets) |
| Byte order | Little-endian |
| Max packet size | 64 bytes |
| Checksum | Sum of bytes[0..Length-2], truncated to uint8 |
| Polling interval | 100ms (periodic data) |
| S22i Device ID | `0x07` (FitnessBike) |

**Request format:**
```
[Device][Length][Command][Content...][Checksum]
```

**Response format:**
```
[Device][Length][Command][Status][Content...][Checksum]
```

---

## Documents

| Document | Description |
|----------|-------------|
| [PACKET_FORMAT.md](PACKET_FORMAT.md) | Header layout, checksum algorithm, response validation, BLE framing |
| [COMMANDS.md](COMMANDS.md) | All 24 commands with request/response byte layouts |
| [READWRITE.md](READWRITE.md) | ReadWriteData deep dive: bitmask construction, packing, worked examples |
| [BITFIELDS.md](BITFIELDS.md) | All ~100 BitFields with IDs, sizes, converters, and categories |
| [DATA_ENCODING.md](DATA_ENCODING.md) | Every value converter with encode/decode algorithms and byte examples |
| [CONNECTION.md](CONNECTION.md) | Full startup sequence, polling loop, error recovery, timing parameters |
| [ENUMS.md](ENUMS.md) | Complete enum definitions ready to copy into implementation code |
| [NATIVE_CLIENT.md](NATIVE_CLIENT.md) | Native C client implementation, API reference, observed MCU behaviors |

---

## Hello World: DeviceInfo → Disconnect

A minimal sequence to identify the device and disconnect. All values in hex.

### Step 1: Clear Buffer

Send 64 bytes of `0xFF` to flush any stale data. The MCU responds with 64 bytes.
Repeat until the response contains recognizable data or matches the `0xFF` pattern.

### Step 2: Send DeviceInfo Request

Query the Main controller (Device=`0x02`) for device information.

```
Request:  02 04 81 87
          ││ ││ ││ └─ Checksum: 0x02+0x04+0x81 = 0x87
          ││ ││ └──── Command: DeviceInfo (0x81 = 129)
          ││ └─────── Length: 4 bytes total
          └────────── Device: Main (0x02)
```

Wait 300ms (ReadDelay), then read 64-byte response. Timeout at 1000ms.

```
Response: 07 10 81 02 4C 03 XX XX XX XX YY YY 0E ...bitmask... CC
          ││ ││ ││ ││ ││ ││                ││ │└─ bitmask bytes
          ││ ││ ││ ││ ││ ││                ││ └── Sections count (14)
          ││ ││ ││ ││ ││ ││                └┴──── Manufacturer (LE uint16)
          ││ ││ ││ ││ ││ └┴─────────────────────── Serial (LE uint32)
          ││ ││ ││ ││ ││ └────────────────────── HW version
          ││ ││ ││ ││ └──────────────────────── SW version
          ││ ││ ││ └─────────────────────────── Status: Done (0x02)
          ││ ││ └────────────────────────────── Command: DeviceInfo (0x81)
          ││ └───────────────────────────────── Length (total bytes)
          └──────────────────────────────────── Device: FitnessBike (0x07)
```

Parse: The response tells you the device type (7 = FitnessBike = S22i), firmware versions,
serial number, and a bitmask of all supported BitFields.

### Step 3: Send Disconnect

```
Request:  07 04 05 10
          ││ ││ ││ └─ Checksum: 0x07+0x04+0x05 = 0x10
          ││ ││ └──── Command: Disconnect (0x05)
          ││ └─────── Length: 4 bytes total
          └────────── Device: FitnessBike (0x07)
```

No response expected (fire-and-forget).

### Validation Pseudocode

```python
def validate_response(response, expected_command):
    if response is None or len(response) < 3:
        return False
    if response[0] == 0x00:           # Device must not be None
        return False
    if response[1] > 64:              # Length must not exceed max
        return False
    if response[2] != expected_command: # Command must match
        return False
    length = response[1]
    checksum = sum(response[0:length-1]) & 0xFF
    if response[length-1] != checksum: # Checksum must match
        return False
    return True
```

---

## Source Files

All documentation derived from decompiled source at:
```
ifit_apps/standalone/v2.6.88.4692/decompiled_dll/Sindarin.FitPro1.Core/
```

---

**Last Updated:** 2026-02-10
**Source Version:** Standalone v2.6.88.4692
