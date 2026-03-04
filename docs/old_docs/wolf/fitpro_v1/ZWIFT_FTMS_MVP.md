# Zwift FTMS/CPS MVP for FitPro Bridge

This is the minimum characteristic/opcode set to make Zwift pair and control resistance using a virtual trainer.

## Services and characteristics

### Fitness Machine Service (`0x1826`)

- `0x2AD9` Fitness Machine Control Point
  - Properties: `write`, `indicate`
  - Must accept control opcodes and return response code packets (`0x80`).
- `0x2AD2` Indoor Bike Data
  - Property: `notify`
  - Push every ~100ms (10Hz target).
- `0x2ACC` Fitness Machine Feature
  - Property: `read`
  - Must advertise supported control capabilities.
- `0x2AD6` Supported Resistance Level Range
  - Property: `read`
  - Should match real FitPro range.

### Cycling Power Service (`0x1818`) (recommended)

- `0x2A63` Cycling Power Measurement (`notify`)
- `0x2A65` Cycling Power Feature (`read`)
- `0x2A5D` Sensor Location (`read`)

This service improves compatibility for Zwift "Power Source" pairing.

## Control point opcodes required in MVP

- `0x00` Request Control -> response success
- `0x04` Set Target Resistance Level -> map to FitPro resistance setter
- `0x05` Set Target Power -> map to FitPro ERG/target-watts path
- `0x07` Start/Resume -> transition device to running mode
- `0x08` Stop/Pause -> transition device to pause mode
- `0x11` Indoor Bike Simulation Parameters -> use grade/slope to set incline

Unknown opcodes should return `Not Supported` response.

## Response format

Every control-point write should produce:

- Byte 0: `0x80` (Response Code)
- Byte 1: original opcode
- Byte 2: result (`0x01` success, `0x02` not supported, etc.)

## Telemetry cadence targets

- Notification loop: 100ms
- Poll loop from FitPro: 100ms (`POLL_INTERVAL_MS`)
- Aim to keep bridge processing under one poll interval to avoid lag.

## Practical compatibility notes

- Start with standards-only FTMS/CPS.
- Add Wahoo naming/profile quirks only if specific Zwift client/platform fails discovery.
- Dircon (Wahoo Direct over TCP) should be an optional layer and reuse the same command dispatcher as BLE.
