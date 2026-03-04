# FTMS to FitProSession Mapping

This table defines how virtual trainer commands map into `FitProSession` APIs.

## Command mapping

| FTMS opcode | Meaning | FitPro call(s) | Notes |
|---|---|---|---|
| `0x00` | Request Control | no-op success | Bridge accepts control ownership. |
| `0x02` | Set Target Speed | `set_speed(kph)` | Speed is in 0.01 kph units. |
| `0x03` | Set Target Incline | `set_incline(percent)` | Incline is in 0.1% units. |
| `0x04` | Set Target Resistance | `set_resistance(round(level))` | FTMS input is 0.1 units; FitPro setter is integer level. |
| `0x05` | Set Target Power (ERG) | `set_watts_mode(true)` then `set_field(61, watts)` | BitField `61` is `WattGoal`. |
| `0x07` | Start/Resume | `set_workout_mode(MODE_RUNNING)` | Ensures commands are physically applied. |
| `0x08` | Stop/Pause | `set_workout_mode(MODE_PAUSE)` | Keeps mode transitions explicit. |
| `0x11` | Indoor Bike Simulation | `set_incline(grade)` | Grade parsed from payload bytes 3-4 as 0.01%. |

## Telemetry mapping

| FitPro `ConsoleState` field | Bridge telemetry | FTMS/CPS output |
|---|---|---|
| `actual_kph` fallback `kph` | `speed_kph` | `0x2AD2` Indoor Bike Data |
| `rpm` | `cadence_rpm` | `0x2AD2` cadence field |
| `watts` | `power_watts` | `0x2A63` Cycling Power Measurement |
| `actual_incline` fallback `grade` | `incline_percent` | used for logic/telemetry derivation |
| `actual_resistance` fallback `resistance` | `resistance_level` | `0x2AD2` resistance-level field |
| `pulse_bpm` | `heart_rate_bpm` | optional future Heart Rate service |

## Error handling policy

- Parse failure or short payload -> FTMS response `Invalid Parameter`.
- Unsupported opcode -> FTMS response `Not Supported`.
- FitPro setter call fails -> FTMS response `Operation Failed`.
- Success -> FTMS response `Success`.

## USB ownership behavior

Before running the bridge, stop iFit to secure USB access:

`adb shell am force-stop com.ifit.standalone`

If polling fails repeatedly, reconnect sequence should:

1. close session
2. re-issue iFit force-stop
3. reconnect session
