# FitPro v1 Data Encoding

> Every value converter with exact encode/decode algorithms and byte examples.
> All multi-byte values are **little-endian**.
> Source: `Sindarin.FitPro1.Bits.Converters/`

---

## Converter Summary

| Converter | Size | Data Type | Used By |
|-----------|------|-----------|---------|
| SpeedConverter | 2 | double | Kph, ActualKph, MaxKph, MinKph, WtMaxKph, KphGoal |
| GradeConverter | 2 | double (signed) | Grade, ActualIncline, MaxGrade, MinGrade, AverageGrade, WtMaxGrade, GradeGoal |
| ResistanceConverter | 2 | double | Resistance, ActualResistance, ResistanceGoal |
| ShortConverter | 2 | int (uint16) | Watts, Rpm, LapTime, TransMax, IdleTimeout, PauseTimeout, Height, WarmupTime, WarmupTimeout, AverageWatts, MaxWatts, AverageRpm, MaxRpm, WattGoal, RpmGoal, CoolDownTimeout, CoolDownTime, Reps, LeftReps, RightReps, RepLength, RepLeftLength, RepRightLength, Strokes, FiveHundredSplit, AvgFiveHundredSplit |
| IntConverter | 4 | int (uint32) | CurrentDistance, Distance, RunningTime, ActualDistance, CurrentTime, GoalTime, WarmupDistance, MotorTotalDistance, TotalTime, CoolDownDistance, DistanceGoal, StartUpTime, BeltTotalTime, BeltTotalMeters, PausedTime |
| ByteConverter | 1 | int (uint8) | FanSpeed, Volume, Age, MaxResistanceLevel, MaxPulse, StrokesPerMin, ActivationLock |
| DoubleConverter | 2 | double | Weight, MaxWeight, IntervalGrade |
| CaloriesConverter | 4 | double | Calories, CurrentCalories, WarmupCalories, CoolDownCalories, GoalCalories |
| ModeConverter | 1 | WorkoutMode | WorkoutMode |
| BoolConverter | 1 | bool | SystemUnits, Gender, IdleModeLockout, SleepTimerState, StartRequested, RequireStartRequested, IsClubUnit, IsReadyToDisconnect, IsConstantWattsMode |
| PulseConverter | 4 | Pulse struct | Pulse |
| KeyObjConverter | 14 | KeyObj struct | KeyObject |
| GearConverter | 8 | Gear struct | Gear |
| IntervalDoubleConverter | 4 | Interval struct | IntervalKph |
| IntervalIntConverter | 4 | Interval struct | IntervalRpm, IntervalResistance |
| StringConverter | 45 | string | FirstName, LastName, UserName |
| VerticalMeterConverter | 4 | double | VerticalMeterNet, VerticalMeterGain |
| FanStateConverter | 1 | FanState enum | FanState |
| AudioSourceConverter | 3 | AudioSource struct | AudioSource |
| BurnRateConverter | 2 | double | BurnRate, AvgBurnRate, MaxBurnRate |

---

## SpeedConverter (2 bytes)

Multiply by 100, store as unsigned 16-bit little-endian.

```python
def encode_speed(kph: float) -> bytes:
    raw = int(kph * 100.0)
    return raw.to_bytes(2, 'little')

def decode_speed(data: bytes) -> float:
    raw = int.from_bytes(data[:2], 'little', signed=False)
    return raw / 100.0
```

| Value | Encoded | Bytes |
|-------|---------|-------|
| 0.0 km/h | 0 | `00 00` |
| 5.5 km/h | 550 | `26 02` |
| 20.0 km/h | 2000 | `D0 07` |
| 20.5 km/h | 2050 | `02 08` |

---

## GradeConverter (2 bytes, SIGNED)

Multiply by 100, store as **signed** 16-bit little-endian. Decode casts to
signed before dividing.

```python
def encode_grade(percent: float) -> bytes:
    raw = int(percent * 100.0)
    return raw.to_bytes(2, 'little', signed=True)

def decode_grade(data: bytes) -> float:
    # Read as unsigned, then cast to signed
    unsigned = int.from_bytes(data[:2], 'little', signed=False)
    signed = unsigned if unsigned < 0x8000 else unsigned - 0x10000
    return signed / 100.0
```

| Value | Raw | Bytes |
|-------|-----|-------|
| 0.0% | 0 | `00 00` |
| 5.0% | 500 | `F4 01` |
| 20.0% | 2000 | `D0 07` |
| -3.0% | -300 | `D4 FE` |
| -10.0% | -1000 | `18 FC` |

**Key difference from SpeedConverter:** GradeConverter decodes using
`(short)BitConverter.ToUInt16(bytes)` which produces signed values. SpeedConverter
uses `(int)BitConverter.ToUInt16(bytes)` which stays unsigned.

---

## ResistanceConverter (2 bytes, device-scaled)

Uses a step size based on the device's MaxResistanceLevel. **Requires
initialization with device info.**

```python
def resistance_step_size(max_resistance_level: int) -> float:
    return 10000.0 / max_resistance_level  # S22i: 10000/24 = 416.667

def encode_resistance(level: float, max_level: int) -> bytes:
    step = resistance_step_size(max_level)
    raw = int(level * step)
    adjusted = max(0, int(raw - step * 0.1))
    return adjusted.to_bytes(2, 'little')

def decode_resistance(data: bytes, max_level: int, device_type=None) -> float:
    step = resistance_step_size(max_level)
    raw = int.from_bytes(data[:2], 'little', signed=False)
    result = raw / step
    if device_type == 'FitnessWeightMachine':
        result += 1.0
    return round(result)
```

| Level | stepSize (S22i) | Raw | Adjusted | Bytes |
|-------|----------------|-----|----------|-------|
| 1 | 416.667 | 416 | 375 | `77 01` |
| 12 | 416.667 | 5000 | 4958 | `5E 13` |
| 24 | 416.667 | 10000 | 9958 | `E6 26` |

**Note:** The 0.1 step adjustment on encode is a deadband to prevent jitter.

---

## IntConverter (4 bytes) / ShortConverter (2 bytes) / ByteConverter (1 byte)

These share the same base class. The size determines how many bytes to use.
All values are unsigned little-endian.

```python
def encode_int(value: int, size: int) -> bytes:
    return value.to_bytes(size, 'little')

def decode_int(data: bytes, size: int) -> int:
    if size == 1:
        return data[0]
    elif size == 2:
        return int.from_bytes(data[:2], 'little', signed=False)
    elif size == 4:
        return int.from_bytes(data[:4], 'little', signed=False)
```

| Converter | Size | Example Value | Bytes |
|-----------|------|---------------|-------|
| ByteConverter | 1 | 24 | `18` |
| ShortConverter | 2 | 150 | `96 00` |
| ShortConverter | 2 | 1000 | `E8 03` |
| IntConverter | 4 | 100000 | `A0 86 01 00` |

---

## DoubleConverter (2 bytes)

Identical to SpeedConverter: multiply by 100, unsigned 16-bit LE.

```python
def encode_double(value: float) -> bytes:
    return int(value * 100.0).to_bytes(2, 'little')

def decode_double(data: bytes) -> float:
    return int.from_bytes(data[:2], 'little', signed=False) / 100.0
```

Used for Weight, MaxWeight, IntervalGrade.

---

## CaloriesConverter (4 bytes)

Uses a non-obvious scaling factor: `value * 100_000_000 / 1024`.

```python
def encode_calories(kcal: float) -> bytes:
    raw = int(kcal * 100_000_000.0 / 1024.0)
    return raw.to_bytes(4, 'little', signed=True)

def decode_calories(data: bytes) -> float:
    raw = int.from_bytes(data[:4], 'little', signed=False)
    return raw * 1024.0 / 100_000_000.0
```

| Calories | Raw | Bytes |
|----------|-----|-------|
| 0.0 | 0 | `00 00 00 00` |
| 1.0 | 97656 | `68 7D 01 00` |
| 100.0 | 9765625 | `59 12 95 00` |
| 500.0 | 48828125 | `7D 96 E9 02` |

---

## ModeConverter (1 byte)

Direct enum mapping. See [ENUMS.md](ENUMS.md) for full WorkoutMode values.

```python
def encode_mode(mode: int) -> bytes:
    return bytes([mode])

def decode_mode(data: bytes) -> int:
    value = data[0]
    if value not in VALID_MODES:
        return 0  # Unknown
    return value
```

| Mode | Value | Byte |
|------|-------|------|
| Idle | 1 | `01` |
| Running | 2 | `02` |
| Pause | 3 | `03` |
| Results | 4 | `04` |
| WarmUp | 10 | `0A` |
| CoolDown | 11 | `0B` |
| Sleep | 12 | `0C` |
| Resume | 13 | `0D` |
| PauseOverride | 20 | `14` |

---

## BoolConverter (1 byte)

Standard boolean: `0x00` = false, `0x01` = true.

```python
def encode_bool(value: bool) -> bytes:
    return bytes([0x01 if value else 0x00])

def decode_bool(data: bytes) -> bool:
    return data[0] != 0x00
```

---

## PulseConverter (4 bytes)

Composite struct with four fields.

```
Byte 0: UserPulse (uint8, BPM)
Byte 1: AveragePulse (uint8, BPM)
Byte 2: PulseCount (uint8)
Byte 3: PulseSource (enum: 0=None, 1=Strap, 2=Ble, 3=Hand)
```

```python
def encode_pulse(bpm: int, source: int = 2) -> bytes:
    return bytes([bpm, 0, 0, source])

def decode_pulse(data: bytes) -> dict:
    return {
        'user_pulse': data[0],
        'average': data[1],
        'count': data[2],
        'source': data[3],  # 0=None, 1=Strap, 2=BLE, 3=Hand
    }
```

---

## KeyObjConverter (14 bytes, read-only)

Key press event data. Cannot be written (encode raises NotSupportedException).

```
Bytes 0-1:   KeyCode (uint16 LE)
Bytes 2-9:   RawKey (uint64 LE)
Bytes 10-11: TimePressed (uint16 LE)
Bytes 12-13: TimeHeld (uint16 LE)
```

```python
def decode_keyobj(data: bytes) -> dict:
    return {
        'code': int.from_bytes(data[0:2], 'little'),
        'raw_key': int.from_bytes(data[2:10], 'little'),
        'time_pressed': int.from_bytes(data[10:12], 'little'),
        'time_held': int.from_bytes(data[12:14], 'little'),
    }
```

---

## GearConverter (8 bytes)

Sparse struct using bytes at specific offsets.

```
Byte 0-3:  Reserved (zeros)
Byte 4:    CurrentGear (uint8)
Byte 5:    GearOption (uint8)
Byte 6:    Reserved
Byte 7:    MaxGear + 1 (uint8)
```

```python
def encode_gear(current: int, gear_option: int, max_gear: int) -> bytes:
    data = bytearray(8)
    data[4] = current
    data[5] = gear_option
    data[7] = max_gear + 1
    return bytes(data)

def decode_gear(data: bytes, fallback_max: int = 24) -> dict:
    current = data[4]
    gear_option = data[5]
    max_gear = data[7] - 1
    if max_gear < 1 or max_gear < current:
        max_gear = fallback_max
    return {
        'current': current,
        'gear_option': gear_option,
        'max_gear': max_gear,
    }
```

---

## IntervalConverter (4 bytes) — IntervalDoubleConverter / IntervalIntConverter

Two uint16 LE values packed together: Recovery and Work.

**IntervalDoubleConverter** (`divideToHundredths=false`): raw values.
**IntervalIntConverter** (`divideToHundredths=true`): divide by 100.

```
Bytes 0-1: Recovery (uint16 LE)
Bytes 2-3: Work (uint16 LE)
```

```python
def encode_interval(recovery: float, work: float, divide_hundredths: bool) -> bytes:
    if divide_hundredths:
        recovery *= 100
        work *= 100
    return int(recovery).to_bytes(2, 'little') + int(work).to_bytes(2, 'little')

def decode_interval(data: bytes, divide_hundredths: bool) -> dict:
    recovery = int.from_bytes(data[0:2], 'little', signed=False)
    work = int.from_bytes(data[2:4], 'little', signed=False)
    if divide_hundredths:
        recovery /= 100.0
        work /= 100.0
    return {'recovery': recovery, 'work': work}
```

---

## StringConverter (45 bytes)

UTF-8 string, padded to 45 bytes.

```python
def encode_string(text: str) -> bytes:
    return text.encode('utf-8')  # Note: may be shorter than 45 bytes

def decode_string(data: bytes) -> str:
    return data.decode('utf-8').strip()
```

---

## VerticalMeterConverter (4 bytes)

Multiply by 10000, store as uint32 LE.

```python
def encode_vertical(meters: float) -> bytes:
    return int(meters * 10000.0).to_bytes(4, 'little', signed=True)

def decode_vertical(data: bytes) -> float:
    raw = int.from_bytes(data[:4], 'little', signed=False)
    return raw / 10000.0
```

| Meters | Raw | Bytes |
|--------|-----|-------|
| 0.0 | 0 | `00 00 00 00` |
| 1.5 | 15000 | `98 3A 00 00` |
| 100.0 | 1000000 | `40 42 0F 00` |

---

## FanStateConverter (1 byte)

Direct enum mapping.

```python
def encode_fan(state: int) -> bytes:
    return bytes([state])

def decode_fan(data: bytes) -> int:
    value = data[0]
    # Returns FanState.Unknown if value not in enum
    return value
```

---

## AudioSourceConverter (3 bytes)

```
Byte 0: Current source (enum AudioSource.Source)
Byte 1: Available source 1
Byte 2: Available source 2
```

```python
def encode_audio(current: int) -> bytes:
    return bytes([current, 0, 0])

def decode_audio(data: bytes) -> dict:
    return {
        'current': data[0],
        'available': [data[1], data[2]],
    }
```

---

## BurnRateConverter (2 bytes)

Multiply by 1000, store as signed 16-bit LE.

```python
def encode_burnrate(rate: float) -> bytes:
    return int(rate * 1000.0).to_bytes(2, 'little', signed=True)

def decode_burnrate(data: bytes) -> float:
    raw = int.from_bytes(data[:2], 'little', signed=False)
    signed = raw if raw < 0x8000 else raw - 0x10000
    return signed / 1000.0
```

---

**Last Updated:** 2026-02-10
**Source:** `Sindarin.FitPro1.Bits.Converters/` (all converter files)
