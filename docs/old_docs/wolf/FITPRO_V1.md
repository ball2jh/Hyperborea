# FitPro v1 Protocol Specification

> **Reverse-engineered from decompiled Sindarin.FitPro1.Core (v2.6.88.4692)**
> **This is the protocol used by the S22i** (USB PID 2 = FitPro v1)

---

## Implementation Guide

For complete implementation-level documentation, see the **[fitpro_v1/](fitpro_v1/)** subfolder:

| Document | Description |
|----------|-------------|
| **[fitpro_v1/README.md](fitpro_v1/README.md)** | Quick reference, index, and "Hello World" example |
| **[fitpro_v1/PACKET_FORMAT.md](fitpro_v1/PACKET_FORMAT.md)** | Header layout, checksum algorithm, response validation, BLE framing |
| **[fitpro_v1/COMMANDS.md](fitpro_v1/COMMANDS.md)** | All 24 commands with request/response byte layouts |
| **[fitpro_v1/READWRITE.md](fitpro_v1/READWRITE.md)** | ReadWriteData deep dive: bitmask construction, packing, worked examples |
| **[fitpro_v1/BITFIELDS.md](fitpro_v1/BITFIELDS.md)** | All ~100 BitFields with IDs, sizes, converters, and categories |
| **[fitpro_v1/DATA_ENCODING.md](fitpro_v1/DATA_ENCODING.md)** | Every value converter with encode/decode algorithms and byte examples |
| **[fitpro_v1/CONNECTION.md](fitpro_v1/CONNECTION.md)** | Full startup sequence, polling loop, error recovery, timing parameters |
| **[fitpro_v1/ENUMS.md](fitpro_v1/ENUMS.md)** | Complete enum definitions ready to copy into code |
| **[fitpro_v1/NATIVE_CLIENT.md](fitpro_v1/NATIVE_CLIENT.md)** | Native C client: architecture, API, observed MCU behaviors, testing |

---

## Protocol Summary

| Property | Value |
|----------|-------|
| Transport | USB bulk (64-byte packets) |
| Byte order | Little-endian |
| Max packet size | 64 bytes |
| Checksum | Sum of bytes[0..Length-2] & 0xFF |
| S22i Device ID | 0x07 (FitnessBike) |
| Polling interval | 100ms |
| Encryption | None (plaintext) |

**Request:** `[Device][Length][Command][Content...][Checksum]`
**Response:** `[Device][Length][Command][Status][Content...][Checksum]`

### Key Commands

| ID | Command | Purpose |
|----|---------|---------|
| 0x02 | ReadWriteData | Read sensors, control motors (primary command) |
| 0x81 | DeviceInfo | Identify device and supported BitFields |
| 0x82 | SystemInfo | Model/part numbers, configuration |
| 0x84 | VersionInfo | Firmware versions |
| 0x90 | VerifySecurity | Unlock device for write operations |

### Connection Sequence

1. Open USB device (VID `0x213C`, PID `0x0002`)
2. Clear buffer (0xFF exchange)
3. DeviceInfo → identify device type and supported fields
4. SupportedDevices + SupportedCommands → learn capabilities
5. SystemInfo + VersionInfo + SerialNumber → get device details
6. VerifySecurity → unlock for writes (if SW version > 75)
7. Read startup fields → learn ranges and current state
8. Begin 100ms polling loop with ReadWriteData

---

## Source Files

```
ifit_apps/standalone/v2.6.88.4692/decompiled_dll/
├── Sindarin.FitPro1.Core/          # FitPro v1 protocol (90 files)
│   ├── Sindarin.FitPro1.Commands/  # Command implementations
│   ├── Sindarin.FitPro1.Communication/  # Transport layer
│   └── Sindarin.FitPro1.Core/      # Core types, enums, converters
```

---

**Last Updated:** 2026-02-10
**Status:** Complete protocol specification from decompiled source
**Source Version:** Standalone v2.6.88.4692
