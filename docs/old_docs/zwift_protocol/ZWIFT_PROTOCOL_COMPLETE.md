# Zwift FTMS/Dircon Protocol — Complete Reference

Extracted from Zwift Game v1.0.158475 (`libzwiftjni.so` symbols, `BleDeviceControl.java`) and Zwift Companion v3.78.0 (`BLEUUID.java`).

Canonical implementation policy for this repo:
- `ZWIFT_KICKR_DIRECT_CONTROL_PATHS.md`

## Connection Flow

```
1. mDNS discovery: _wahoo-fitness-tnp._tcp.local → TCP connect to port 36866
2. Dircon: DiscoverServices (0x01) → expects [0x1826, 0x1818]
3. Dircon: DiscoverCharacteristics (0x02) for 0x1826 (FTMS)
4. Dircon: DiscoverCharacteristics (0x02) for 0x1818 (CPS)
5. Reads:
   - 0x2ACC  FTMS Feature → determines available control modes
   - 0x2AD5  Supported Inclination Range → min/max/step
   - 0x2AD6  Supported Resistance Range → min/max/step
   - 0x2AD8  Supported Power Range → min/max/step
   - 0x2AD3  Training Status
   - 0x2A65  CPS Feature
   - 0x2A5D  Sensor Location
6. Subscribes (notifications/indications):
   - 0x2AD2  Indoor Bike Data (notify)
   - 0x2A63  CPS Measurement (notify)
   - 0x2AD9  Control Point (indicate)
   - 0x2ADA  Fitness Machine Status (notify)
7. Control writes to 0x2AD9:
   - 0x00 RequestControl → response 0x80/0x00/0x01
   - 0x07 StartResume → response 0x80/0x07/0x01
   - Then 0x11 (sim params) or 0x05 (ERG) at ~1Hz
```

## FTMS State Machine (from `libzwiftjni.so`)

Extracted from `FTMS_ControlComponent_v3` symbol table:

```
States (FTMS_TRAINER_CONTROL_STATE enum):
  Ready → RequestControl → StartSession → SimMode/ERGMode → ...

Transitions:
  1. FTMS_RequestControl()         → opcode 0x00
  2. FTMS_StartSession()           → opcode 0x07
  3. SetSimulationMode()           → opcode 0x11 (grade, wind, crr, cw)
     or SetERGMode()               → opcode 0x05 (target watts)
  4. FTMS_ResetTrainer()           → opcode 0x01
  5. FTMS_RequestSpindown()        → opcode 0x09

State handler functions:
  HandleState_Ready(dt)
  HandleState_RequestControl(dt, result)
  HandleState_StartSession(dt, result)
  CheckState_SimMode(dt)
  HandleState_RequestSpinDown(dt, result)
  HandleState_WaitForSpindown(dt, result)
  HandleControlPointResultCode(result, state, opcode)
  UpdateStateMachine(dt)
```

## FTMS Feature Bitmask (0x2ACC)

8 bytes: Fitness Machine Features (4) + Target Setting Features (4).

### Fitness Machine Features (bytes 0-3)
| Bit | Name | Required |
|-----|------|----------|
| 0 | Average Speed | |
| 1 | Cadence | |
| 2 | Total Distance | |
| 3 | Inclination | |
| 4 | Elevation Gain | |
| 5 | Pace | |
| 6 | **Instantaneous Power** | **Yes** — Zwift checks this |
| 7 | Resistance Level | |
| 10 | Heart Rate | |
| 12 | Remaining Time | |
| 14 | Power Measurement | |

### Target Setting Features (bytes 4-7)
| Bit | Name | Required |
|-----|------|----------|
| 0 | Speed Target | |
| 1 | **Inclination Target** | **Yes** — enables opcode 0x03 |
| 2 | Resistance Target | Yes |
| 3 | Power Target | Yes — enables ERG mode |
| 13 | Indoor Bike Simulation | Yes — enables opcode 0x11 |
| 14 | Wheel Circumference | |
| 15 | Spin Down Control | |

**Our value:** `0x83 0x54 0x00 0x00 0x0E 0xE0 0x00 0x00`

## FTMS Control Point Opcodes (0x2AD9)

### Opcode 0x00 — Request Control
```
Write:    [0x00]
Response: [0x80, 0x00, 0x01]  (success)
```

### Opcode 0x01 — Reset
```
Write:    [0x01]
Response: [0x80, 0x01, 0x01]  (success)
Status:   [0x01]              (Reset via 0x2ADA)
```

### Opcode 0x03 — Set Target Inclination
```
Write:    [0x03, incline_lo, incline_hi]
          sint16 LE, 0.1% resolution. E.g., 50 = 5.0%
Response: [0x80, 0x03, 0x01]
Status:   [0x06]              (Target Incline Changed)
```

### Opcode 0x04 — Set Target Resistance Level
```
Write:    [0x04, level_lo, level_hi]
          sint16 LE, 0.1 unitless resolution. E.g., 100 = 10.0
Response: [0x80, 0x04, 0x01]
Status:   [0x07]              (Target Resistance Changed)
```

### Opcode 0x05 — Set Target Power (ERG Mode)
```
Write:    [0x05, watts_lo, watts_hi]
          sint16 LE, 1W resolution
Response: [0x80, 0x05, 0x01]
Status:   [0x08]              (Target Power Changed)
```

### Opcode 0x07 — Start or Resume
```
Write:    [0x07]
Response: [0x80, 0x07, 0x01]
Status:   [0x04]              (Started/Resumed)
```

### Opcode 0x08 — Stop or Pause
```
Write:    [0x08, stop_code]
          stop_code: 0x01=stop, 0x02=pause
Response: [0x80, 0x08, 0x01]
Status:   [0x02, stop_code]   (Stopped/Paused)
```

Implementation note for this repo:
- Native parser currently validates `0x08` with payload length exactly `1` (`[0x08]`).
- Dircon machine-status helper emits `[0x02, 0x01]` for stop/pause notifications.

### Opcode 0x11 — Set Indoor Bike Simulation Parameters
```
Write:    [0x11, wind_lo, wind_hi, grade_lo, grade_hi, crr, cw]
          wind:  sint16 LE, 0.001 m/s resolution
          grade: sint16 LE, 0.01% resolution. E.g., 500 = 5.00%
          crr:   uint8, 0.0001 resolution. E.g., 33 = 0.0033
          cw:    uint8, 0.01 kg/m resolution. E.g., 51 = 0.51
Response: [0x80, 0x11, 0x01]
Status:   [0x12]              (Simulation Params Changed)
```

Zwift sends this at ~1Hz during sim mode (free rides, routes).

### Response Code (0x80)
```
[0x80, request_opcode, result_code]
result_code:
  0x01 = Success
  0x02 = Not Supported
  0x03 = Invalid Parameter
  0x04 = Operation Failed
  0x05 = Control Not Permitted
```

## Fitness Machine Status (0x2ADA)

Unsolicited notifications confirming state changes:

| Code | Meaning | Payload |
|------|---------|---------|
| 0x01 | Reset | — |
| 0x02 | Stopped/Paused | uint8 stop_code (1=stop, 2=pause) |
| 0x03 | Stopped by Safety Key | — |
| 0x04 | Started/Resumed | — |
| 0x05 | Target Speed Changed | uint16 speed (0.01 km/h) |
| 0x06 | Target Incline Changed | sint16 incline (0.1%) |
| 0x07 | Target Resistance Changed | — |
| 0x08 | Target Power Changed | sint16 watts |
| 0x12 | Indoor Bike Sim Params Changed | — |

## Indoor Bike Data (0x2AD2)

Flags (uint16) determine which fields are present:

| Bit | Field | Format | Unit |
|-----|-------|--------|------|
| — | Speed (always present when bit 0=0) | uint16 | 0.01 km/h |
| 2 | Instantaneous Cadence | uint16 | 0.5 rpm |
| 5 | Resistance Level | sint16 | 0.1 unitless |
| 6 | Instantaneous Power | sint16 | 1 W |
| 9 | Heart Rate | uint8 | 1 bpm |

**Our flags:** `0x0064` (no HR) or `0x0264` (with HR)

## Supported Range Characteristics

### 0x2AD5 — Supported Inclination Range
```
sint16 min (-150 = -15.0%)
sint16 max ( 150 = +15.0%)
sint16 step ( 10 =  1.0%)
```

### 0x2AD6 — Supported Resistance Level Range
```
sint16 min ( 10 =  1.0)
sint16 max (150 = 15.0)    [Note: we use 24 levels, 0.1 res]
sint16 step ( 10 =  1.0)
```

### 0x2AD8 — Supported Power Range
```
sint16 min (  0 =   0W)
sint16 max (400 = 400W)
sint16 step (  1 =   1W)
```

## Wahoo E005 Fallback (Non-FTMS Path)

From `BleDeviceControl.java:162-196`. Only used when `!mFTMS && (name.contains("KICKR") || name.contains("Hammer"))`.

Written under **CPS service (0x1818)**, NOT FTMS:
```java
UUID service = UUID.fromString("00001818-0000-1000-8000-00805f9b34fb");
UUID char = UUID.fromString("a026e005-0a7d-4ab3-97fa-f1500f9feb8b");
```

| Cmd | Name | Payload |
|-----|------|---------|
| 66 | ERG Mode | uint16 LE watts |
| 67 | Sim Mode Init | weight(kg*100), crr(*10000), cw(*100) |
| 70 | Sim Grade | uint16 LE: 0=−100%, 32768=0%, 65535=+100% |

**Key insight:** Since we advertise FTMS, Zwift uses standard FTMS opcodes. E005 is only a fallback for older non-FTMS Wahoo devices. We support both paths for maximum compatibility.

## How this repo maps Zwift behavior

Zwift BLE source behavior:
- Primary writes go to requested FTMS characteristic (`0x2AD9`).
- Fallback writes (`E005`) are selected only in non-FTMS KICKR/Hammer branch.
- Fallback UUID pair in source:
  - service `00001818-0000-1000-8000-00805f9b34fb`
  - char `a026e005-0a7d-4ab3-97fa-f1500f9feb8b`

Current Dircon server behavior in this repo:
- Accepts `0x2AD9` and `0xE005` writes.
- Exposes `0xE005` in FTMS discovery payload (`0x1826`) for compatibility with current client behavior.
- Translates:
  - `E005 cmd 66` -> FTMS `0x05` (target power)
  - `E005 cmd 70` -> FTMS `0x03` (target incline)
  - `E005 cmd 67` -> log only (no physics model state application yet)

## Native FTMS parse/apply truth table

Parser (`parse_ftms_control_point`) recognizes:
- `0x00`, `0x01`, `0x02`, `0x03`, `0x04`, `0x05`, `0x07`, `0x08`, `0x11`

Payload validation:
- exact length `1`: `0x00`, `0x01`, `0x07`, `0x08`
- min length `3`: `0x02`, `0x03`, `0x04`, `0x05`
- min length `7`: `0x11`

Apply behavior (`apply_ftms_command`):
- `0x11` uses only decoded grade for actuation (`set_incline_percent`), even though wind/crr/cw are parsed.
- Response encoding is always `[0x80, request_opcode, result]`.

## CPS (Cycling Power Service 0x1818)

### CPS Feature (0x2A65)
```
uint32: 0x00000018
  bit 3: Wheel Revolution Data Supported
  bit 4: Crank Revolution Data Supported
```

### CPS Measurement (0x2A63)
```
Flags: 0x0030 (Wheel + Crank data present)
Fields:
  sint16  instantaneous_power (W)
  uint32  cumulative_wheel_revs
  uint16  last_wheel_event_time (1/2048s)
  uint16  cumulative_crank_revs
  uint16  last_crank_event_time (1/1024s)
```

### Sensor Location (0x2A5D)
```
uint8: 13 (Rear Wheel)
```
