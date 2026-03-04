# FitPro v1 BitField Reference

> Complete listing of all BitFields with IDs, sizes, converters, and categories.
> Source: `BitField.cs`, `BitFieldInfo.cs`, `Defaults.cs`

---

## BitField Table

Fields are sorted by ID. **RO** = Read-Only, **RW** = Read-Write.

| ID | Name | Converter | Size | Dir | Description |
|----|------|-----------|------|-----|-------------|
| 0 | Kph | SpeedConverter | 2 | RW | Target speed (km/h) |
| 1 | Grade | GradeConverter | 2 | RW | Target incline (%, signed) |
| 2 | Resistance | ResistanceConverter | 2 | RW | Target resistance level |
| 3 | Watts | ShortConverter | 2 | RO | Power output (watts) |
| 4 | CurrentDistance | IntConverter | 4 | RO | Current workout distance |
| 5 | Rpm | ShortConverter | 2 | RO | Cadence (revolutions/min) |
| 6 | Distance | IntConverter | 4 | RO | Total distance |
| 7 | KeyObject | KeyObjConverter | 14 | RO | Key press data |
| 8 | FanSpeed | ByteConverter | 1 | RW | Fan speed setting |
| 9 | Volume | ByteConverter | 1 | RW | Audio volume |
| 10 | Pulse | PulseConverter | 4 | RW | Heart rate data |
| 11 | RunningTime | IntConverter | 4 | RO | Elapsed running time (seconds) |
| 12 | WorkoutMode | ModeConverter | 1 | RW | Workout state machine |
| 13 | Calories | CaloriesConverter | 4 | RO | Calories burned |
| 14 | AudioSource | AudioSourceConverter | 3 | RW | Audio input source |
| 15 | LapTime | ShortConverter | 2 | RO | Current lap time |
| 16 | ActualKph | SpeedConverter | 2 | RO | Actual measured speed |
| 17 | ActualIncline | GradeConverter | 2 | RO | Actual measured incline |
| 18 | ActualResistance | ResistanceConverter | 2 | RO | Actual measured resistance |
| 19 | ActualDistance | IntConverter | 4 | RO | Actual distance |
| 20 | CurrentTime | IntConverter | 4 | RO | Current workout time |
| 21 | CurrentCalories | CaloriesConverter | 4 | RO | Current workout calories |
| 22 | GoalTime | IntConverter | 4 | RW | Goal time setting |
| 23 | IntervalKph | IntervalDoubleConverter | 4 | RO | Interval speed (work/recovery) |
| 24 | Age | ByteConverter | 1 | RW | User age |
| 25 | Weight | DoubleConverter | 2 | RW | User weight (kg, x100) |
| 26 | Gear | GearConverter | 8 | RW | Gear setting (current/max/option) |
| 27 | MaxGrade | GradeConverter | 2 | RO | Maximum supported incline |
| 28 | MinGrade | GradeConverter | 2 | RO | Minimum supported incline |
| 29 | TransMax | ShortConverter | 2 | RW | Transition maximum |
| 30 | MaxKph | SpeedConverter | 2 | RO | Maximum supported speed |
| 31 | MinKph | SpeedConverter | 2 | RO | Minimum supported speed |
| *32* | *(gap)* | | | | *ID not defined* |
| *33* | *(gap)* | | | | *ID not defined* |
| 34 | IdleTimeout | ShortConverter | 2 | RW | Idle timeout (seconds) |
| 35 | PauseTimeout | ShortConverter | 2 | RW | Pause timeout (seconds) |
| 36 | SystemUnits | BoolConverter | 1 | RW | Unit system (false=Imperial, true=Metric) |
| 37 | Gender | BoolConverter | 1 | RW | User gender |
| 38 | FirstName | StringConverter | 45 | RW | User first name |
| 39 | LastName | StringConverter | 45 | RW | User last name |
| 40 | UserName | StringConverter | 45 | RW | User name |
| 41 | Height | ShortConverter | 2 | RW | User height |
| 42 | MaxResistanceLevel | ByteConverter | 1 | RO | Max resistance levels (S22i = 24) |
| 43 | MaxWeight | DoubleConverter | 2 | RO | Max supported user weight |
| 44 | WarmupDistance | IntConverter | 4 | RO | Warmup distance |
| 45 | WarmupTime | ShortConverter | 2 | RO | Warmup time |
| 46 | WarmupTimeout | ShortConverter | 2 | RW | Warmup timeout setting |
| 47 | WarmupCalories | CaloriesConverter | 4 | RO | Warmup calories |
| 48 | IntervalGrade | DoubleConverter | 2 | RO | Interval grade |
| 49 | MaxPulse | ByteConverter | 1 | RO | Maximum heart rate |
| *50* | *(gap)* | | | | *ID not defined* |
| 51 | WtMaxKph | SpeedConverter | 2 | RO | Workout max speed |
| 52 | AverageGrade | GradeConverter | 2 | RO | Average incline |
| 53 | WtMaxGrade | GradeConverter | 2 | RO | Workout max incline |
| 54 | AverageWatts | ShortConverter | 2 | RO | Average power |
| 55 | MaxWatts | ShortConverter | 2 | RO | Maximum power |
| 56 | AverageRpm | ShortConverter | 2 | RO | Average cadence |
| 57 | MaxRpm | ShortConverter | 2 | RO | Maximum cadence |
| 58 | KphGoal | SpeedConverter | 2 | RW | Speed goal |
| 59 | GradeGoal | GradeConverter | 2 | RW | Incline goal |
| 60 | ResistanceGoal | ResistanceConverter | 2 | RW | Resistance goal |
| 61 | WattGoal | ShortConverter | 2 | RW | Watt goal |
| *62* | *(gap)* | | | | *ID not defined* |
| 63 | RpmGoal | ShortConverter | 2 | RW | RPM goal |
| 64 | DistanceGoal | IntConverter | 4 | RW | Distance goal |
| 65 | PulseGoal | ByteConverter | 1 | RW | Heart rate goal |
| 66 | StartUpTime | IntConverter | 4 | RO | Equipment startup time |
| 67 | BeltTotalTime | IntConverter | 4 | RO | Lifetime belt time |
| 68 | BeltTotalMeters | IntConverter | 4 | RO | Lifetime belt meters |
| 69 | MotorTotalDistance | IntConverter | 4 | RO | Lifetime motor distance |
| 70 | TotalTime | IntConverter | 4 | RO | Lifetime total time |
| 71 | CoolDownTimeout | ShortConverter | 2 | RW | Cool-down timeout setting |
| 72 | CoolDownTime | ShortConverter | 2 | RO | Cool-down time |
| 73 | CoolDownDistance | IntConverter | 4 | RO | Cool-down distance |
| 74 | CoolDownCalories | CaloriesConverter | 4 | RO | Cool-down calories |
| 75 | VerticalMeterNet | VerticalMeterConverter | 4 | RO | Net vertical meters |
| 76 | VerticalMeterGain | VerticalMeterConverter | 4 | RO | Vertical meters gained |
| 77 | Reps | ShortConverter | 2 | RO | Total reps |
| 78 | LeftReps | ShortConverter | 2 | RO | Left reps |
| 79 | RightReps | ShortConverter | 2 | RO | Right reps |
| 80 | RepLength | ShortConverter | 2 | RO | Rep length |
| 81 | RepLeftLength | ShortConverter | 2 | RO | Left rep length |
| 82 | RepRightLength | ShortConverter | 2 | RO | Right rep length |
| 83 | BurnRate | BurnRateConverter | 2 | RW | Burn rate |
| 84 | AvgBurnRate | BurnRateConverter | 2 | RO | Average burn rate |
| 85 | MaxBurnRate | BurnRateConverter | 2 | RO | Maximum burn rate |
| 86 | IntervalRpm | IntervalIntConverter | 4 | RO | Interval RPM (work/recovery) |
| 87 | IntervalResistance | IntervalIntConverter | 4 | RO | Interval resistance (work/recovery) |
| *88-93* | *(gap)* | | | | *IDs not defined* |
| 94 | GoalCalories | CaloriesConverter | 4 | RW | Calorie goal |
| 95 | IdleModeLockout | BoolConverter | 1 | RW | Idle mode lockout (true=locked, must set false before Idle→Running on non-belt machines) |
| 96 | StartRequested | BoolConverter | 1 | RO | Start button pressed |
| *97* | *(gap)* | | | | *ID not defined* |
| 98 | FanState | FanStateConverter | 1 | RW | Fan on/off/auto state |
| *99* | *(gap)* | | | | *ID not defined* |
| 100 | ActivationLock | ByteConverter | 1 | RW | Activation lock state |
| *101-102* | *(gap)* | | | | *IDs not defined* |
| 103 | PausedTime | IntConverter | 4 | RO | Time spent paused |
| *104-106* | *(gap)* | | | | *IDs not defined* |
| 107 | SleepTimerState | BoolConverter | 1 | RW | Sleep timer enabled |
| 108 | RequireStartRequested | BoolConverter | 1 | RW | Require start button (set true during init, false on shutdown) |
| 109 | Strokes | ShortConverter | 2 | RO | Rowing strokes count |
| 110 | StrokesPerMin | ByteConverter | 1 | RO | Strokes per minute |
| 111 | FiveHundredSplit | ShortConverter | 2 | RO | 500m split time |
| 112 | AvgFiveHundredSplit | ShortConverter | 2 | RO | Average 500m split |
| *113-114* | *(gap)* | | | | *IDs not defined* |
| 115 | IsClubUnit | BoolConverter | 1 | RO | Is club/commercial unit |
| 116 | IsReadyToDisconnect | BoolConverter | 1 | RO | Ready to disconnect |
| *117-118* | *(gap)* | | | | *IDs not defined* |
| 119 | IsConstantWattsMode | BoolConverter | 1 | RW | Constant watts mode |

**Total defined fields: 100** (IDs 0-119 with gaps)

---

## Fields by Category

### Primary Controls (RW)

| ID | Field | Use |
|----|-------|-----|
| 0 | Kph | Set target speed |
| 1 | Grade | Set target incline |
| 2 | Resistance | Set resistance level |
| 12 | WorkoutMode | Control workout state machine |

### Sensor Readings (RO)

| ID | Field | Use |
|----|-------|-----|
| 3 | Watts | Current power output |
| 5 | Rpm | Current cadence |
| 10 | Pulse | Heart rate (4-byte struct) |
| 16 | ActualKph | Measured speed |
| 17 | ActualIncline | Measured incline |
| 18 | ActualResistance | Measured resistance |

### Workout State

| ID | Field | Use |
|----|-------|-----|
| 11 | RunningTime | Elapsed workout time |
| 20 | CurrentTime | Current time |
| 4 | CurrentDistance | Current distance |
| 21 | CurrentCalories | Current calories |
| 15 | LapTime | Lap timer |
| 103 | PausedTime | Time spent paused |
| 96 | StartRequested | Start button state |
| 116 | IsReadyToDisconnect | Ready to disconnect |

### Equipment Info (RO - read once at startup)

| ID | Field | Use |
|----|-------|-----|
| 30 | MaxKph | Speed range max |
| 31 | MinKph | Speed range min |
| 27 | MaxGrade | Incline range max |
| 28 | MinGrade | Incline range min |
| 42 | MaxResistanceLevel | Resistance levels (S22i=24) |
| 43 | MaxWeight | User weight max |
| 69 | MotorTotalDistance | Lifetime odometer |
| 70 | TotalTime | Lifetime time |
| 115 | IsClubUnit | Commercial unit flag |

### Timeouts (RW)

| ID | Field | Use |
|----|-------|-----|
| 34 | IdleTimeout | Seconds before idle shutdown |
| 35 | PauseTimeout | Seconds before auto-resume |
| 46 | WarmupTimeout | Warmup duration limit |
| 71 | CoolDownTimeout | Cool-down duration limit |

### Goals (RW)

| ID | Field | Use |
|----|-------|-----|
| 22 | GoalTime | Target time |
| 58 | KphGoal | Target speed |
| 59 | GradeGoal | Target incline |
| 60 | ResistanceGoal | Target resistance |
| 61 | WattGoal | Target watts |
| 63 | RpmGoal | Target RPM |
| 64 | DistanceGoal | Target distance |
| 65 | PulseGoal | Target heart rate |
| 94 | GoalCalories | Target calories |

### Statistics / Averages (RO)

| ID | Field | Use |
|----|-------|-----|
| 52 | AverageGrade | Average incline |
| 54 | AverageWatts | Average power |
| 56 | AverageRpm | Average cadence |
| 55 | MaxWatts | Peak power |
| 57 | MaxRpm | Peak cadence |
| 51 | WtMaxKph | Peak speed |
| 53 | WtMaxGrade | Peak incline |
| 75 | VerticalMeterNet | Net elevation change |
| 76 | VerticalMeterGain | Total elevation gained |

### User Profile (RW)

| ID | Field | Use |
|----|-------|-----|
| 24 | Age | User age |
| 25 | Weight | User weight (kg) |
| 37 | Gender | User gender |
| 41 | Height | User height |
| 38 | FirstName | First name (45 chars) |
| 39 | LastName | Last name (45 chars) |
| 40 | UserName | Username (45 chars) |

### Intervals (RO)

| ID | Field | Use |
|----|-------|-----|
| 23 | IntervalKph | Work/recovery speed pair |
| 48 | IntervalGrade | Interval grade |
| 86 | IntervalRpm | Work/recovery RPM pair |
| 87 | IntervalResistance | Work/recovery resistance pair |

### Rowing (RO)

| ID | Field | Use |
|----|-------|-----|
| 109 | Strokes | Total stroke count |
| 110 | StrokesPerMin | Stroke rate |
| 111 | FiveHundredSplit | 500m split time |
| 112 | AvgFiveHundredSplit | Average 500m split |

### Audio / Fan (RW)

| ID | Field | Use |
|----|-------|-----|
| 8 | FanSpeed | Fan speed level |
| 9 | Volume | Audio volume level |
| 14 | AudioSource | Audio source selection |
| 98 | FanState | Fan on/off/auto |

---

## Startup Fields (17 fields, read once on connect)

These fields are read immediately after identification to learn the equipment's
capabilities and current state.

```python
STARTUP_FIELDS = [
    36,   # SystemUnits
    30,   # MaxKph
    31,   # MinKph
    27,   # MaxGrade
    28,   # MinGrade
    43,   # MaxWeight
    34,   # IdleTimeout
    35,   # PauseTimeout
    71,   # CoolDownTimeout
    46,   # WarmupTimeout
    42,   # MaxResistanceLevel
    26,   # Gear
    12,   # WorkoutMode
    100,  # ActivationLock
    115,  # IsClubUnit
    69,   # MotorTotalDistance
    70,   # TotalTime
]
```

---

## Periodic Fields (30 fields, polled every 100ms)

These fields are read continuously during operation for real-time updates.

```python
PERIODIC_FIELDS = [
    12,   # WorkoutMode
    1,    # Grade
    20,   # CurrentTime
    4,    # CurrentDistance
    21,   # CurrentCalories
    2,    # Resistance
    26,   # Gear
    5,    # Rpm
    15,   # LapTime
    52,   # AverageGrade
    3,    # Watts
    54,   # AverageWatts
    75,   # VerticalMeterNet
    76,   # VerticalMeterGain
    10,   # Pulse
    46,   # WarmupTimeout
    71,   # CoolDownTimeout
    0,    # Kph
    16,   # ActualKph
    96,   # StartRequested
    98,   # FanState
    9,    # Volume
    103,  # PausedTime
    11,   # RunningTime
    109,  # Strokes
    110,  # StrokesPerMin
    111,  # FiveHundredSplit
    112,  # AvgFiveHundredSplit
    22,   # GoalTime
    7,    # KeyObject
    116,  # IsReadyToDisconnect
]
```

**Note:** Only fields reported as supported by DeviceInfo are actually polled.
Fields not in the device's bitmask are silently skipped.

---

## S22i Supported Fields

The S22i (FitnessBike) reports its supported BitFields via the DeviceInfo bitmask.
The exact set depends on firmware version. Typical S22i-relevant fields include:

- **Controls:** Kph, Grade, Resistance, WorkoutMode, FanSpeed, Volume, Pulse
- **Sensors:** Watts, Rpm, ActualKph, ActualIncline, ActualResistance
- **State:** RunningTime, CurrentTime, CurrentDistance, CurrentCalories, WorkoutMode
- **Info:** MaxKph, MinKph, MaxGrade, MinGrade, MaxResistanceLevel, Gear
- **NOT typically supported:** Rowing fields (109-112), Weight machine fields (77-82)

**Always check DeviceInfo before using a field.** The bitmask is the source of truth.

---

**Last Updated:** 2026-02-10
**Source:** `BitField.cs`, `Defaults.cs`
