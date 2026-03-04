# Zwift KICKR Direct Control Paths (Canonical)

This file is the source of truth for control behavior in this repo.

It describes:
- what Zwift sends,
- how `ZwiftDirconServer` routes it,
- how native FTMS parsing/execution handles it,
- what bytes are returned.

If any other note disagrees, this file wins.

## What we emulate

We emulate KICKR Direct over Dircon using a hybrid path:

1. **Primary control path:** FTMS Control Point `0x2AD9`
2. **Fallback control path:** Wahoo proprietary `0xE005`

Both paths end at the same native FTMS command executor.

## Evidence from Zwift source (`zwift_apps/zwiftgame`)

From `com/zwift/zwiftgame/ble/BleDeviceControl.java`:
- Normal writes target the requested characteristic (for us: FTMS `0x2AD9`).
- Special fallback branch triggers when:
  - `!mFTMS`
  - and device name contains `"KICKR"` or `"Hammer"`.
- In that branch Zwift writes to:
  - service `00001818-0000-1000-8000-00805f9b34fb`
  - characteristic `a026e005-0a7d-4ab3-97fa-f1500f9feb8b`.

Interpretation: Zwift prefers FTMS when FTMS is active, and uses `E005` as legacy compatibility fallback.

## What our Dircon server exposes

Dircon services:
- `0x1826` FTMS
- `0x1818` CPS

`0x1826` characteristics in our implementation:
- `0x2AD9` Control Point (write + indicate)
- `0xE005` Wahoo fallback write char (write)
- `0x2AD2` Indoor Bike Data (notify)
- `0x2ADA` Fitness Machine Status (notify)
- `0x2ACC` Fitness Machine Feature (read): `83 54 00 00 0E E0 00 00`
- `0x2AD6` Resistance Range (read): `0A 00 96 00 0A 00`
- `0x2AD5` Inclination Range (read): `6A FF 96 00 0A 00`
- `0x2AD8` Power Range (read): `00 00 90 01 01 00`
- `0x2AD3` Training Status (read): `00 01`

`0x1818` characteristics:
- `0x2A63` CPS Measurement (notify)
- `0x2A65` CPS Feature (read): `18 00 00 00`
- `0x2A5D` Sensor Location (read): `0D`

## Control path routing

| Incoming write UUID | Path | Behavior |
|---|---|---|
| `0x2AD9` | FTMS primary | parse opcode -> native FTMS apply -> enqueue CP response + status |
| `0xE005` | Wahoo fallback | parse cmd -> translate to FTMS write -> dispatch |
| other | unsupported | return characteristic op-not-supported |

## FTMS Control Point (`0x2AD9`) payloads

Request format: `[opcode, ...params]`

| Opcode | Name | Payload decode | Native action |
|---|---|---|---|
| `0x00` | Request Control | `[00]` (len must be 1) | `request_control()` |
| `0x01` | Reset | `[01]` (len must be 1) | `reset()` |
| `0x02` | Set Target Speed | `[02, s_lo, s_hi]` => `uint16 / 100` kph | `set_speed_kph()` |
| `0x03` | Set Target Incline | `[03, i_lo, i_hi]` => `sint16 / 10` percent | `set_incline_percent()` |
| `0x04` | Set Target Resistance | `[04, r_lo, r_hi]` => `sint16 / 10` level | `set_resistance_level()` |
| `0x05` | Set Target Power | `[05, w_lo, w_hi]` => `uint16` watts | `set_target_power_watts()` |
| `0x07` | Start/Resume | `[07]` (len must be 1) | `start_resume()` |
| `0x08` | Stop/Pause | `[08]` (len must be 1 in current native parser) | `stop_pause()` |
| `0x11` | Indoor Bike Simulation Params | `[11, ws_lo, ws_hi, g_lo, g_hi, crr, cw]` | `set_simulation_params(wind, grade, crr, cw)` — incline from grade (toggleable), fan from wind/speed (mode: off/simple/physics) |

Native parse/apply behavior:
- recognized + valid payload -> execute target method
- recognized + invalid payload -> result `0x03` (invalid parameter)
- unknown opcode -> result `0x02` (not supported)
- target method failure -> result `0x04` (operation failed)

Control-point response bytes are always:
- `[0x80, request_opcode, result_code]`

Result codes:
- `0x01` success
- `0x02` not supported
- `0x03` invalid parameter
- `0x04` operation failed
- `0x05` control not permitted (defined, currently not emitted by apply path)

## Machine status notifications emitted by Dircon

When a `0x2AD9` write is processed, Dircon queues these `0x2ADA` status payloads:

| Incoming opcode | Status payload |
|---|---|
| `0x01` | `[0x01]` reset |
| `0x07` | `[0x04]` started/resumed |
| `0x08` | `[0x02, 0x01]` stopped by user |
| `0x03` | `[0x06]` target incline changed |
| `0x04` | `[0x07]` target resistance changed |
| `0x05` | `[0x08]` target power changed |
| `0x11` | `[0x12]` indoor bike simulation params changed |

## Wahoo fallback (`0xE005`) command decode

Command ID is payload byte 0.

| Cmd | Name | Input decode | Translation |
|---|---|---|---|
| `66` | ERG mode | bytes 1-2: `uint16` watts LE | send FTMS `[0x05, watts_lo, watts_hi]` |
| `67` | simulation init | opaque payload | currently logged only |
| `70` | simulation grade | bytes 1-2: normalized `uint16` LE | convert to percent, send FTMS `[0x03, incline_lo, incline_hi]` |

Grade conversion used by current code for cmd `70`:
- `grade_percent = ((((raw / 65535.0) * 2.0) - 1.0) * 100.0)`
- clamp to `[-100, 100]`
- convert to FTMS incline tenths: `incline_tenths = grade_percent * 10`

## Runtime behavior details

- When client enables notifications for `0x2AD2`, Dircon auto-sends FTMS start `[0x07]`.
- Telemetry notifications are sent every `100 ms` (~10 Hz).
- CP responses are queued and sent after the write ACK (ACK first, then indication/notify).
- If native bridge is unavailable, zero-data FTMS/CPS payloads are emitted with correct flags:
  - FTMS Indoor Bike Data flags: `0x0064`
  - CPS Measurement flags: `0x0030`

## Meaning of "act like KICKR Direct" here

- Wahoo-style `_wahoo-fitness-tnp._tcp` discovery and naming.
- FTMS first-class control (`0x2AD9`).
- Wahoo `E005` fallback accepted and translated.
- One native execution path regardless of which control byte-path is used.

