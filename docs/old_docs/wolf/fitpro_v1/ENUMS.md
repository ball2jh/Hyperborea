# FitPro v1 Type Definitions

> Complete enum definitions from the decompiled source.
> Ready to copy into implementation code.
> Source: Various files in `Sindarin.FitPro1.Core/` and `Sindarin.Core/`

---

## Device (58 values)

Equipment and sub-device types. Use in packet byte 0.

```python
class Device:
    NONE = 0
    MULTIPLE_DEVICES = 1
    MAIN = 2                          # Primary controller
    PORTAL = 3
    TREADMILL = 4
    INCLINE_TRAINER = 5
    ELLIPTICAL = 6
    FITNESS_BIKE = 7                  # ← S22i
    SPIN_BIKE = 8
    VERTICAL_ELLIPTICAL = 9
    VIBRATION = 10
    STAIR_CLIMBER = 11
    SKIER = 12
    FITNESS_WEIGHT_MACHINE = 13
    DOUBLE_STACK_WEIGHT_MACHINE = 14
    DUMBBELL_WEIGHT_HOLDER = 15
    SINGLE_JOIN_PLATE_WEIGHT_MACHINE = 16
    DOUBLE_JOIN_PLATE_WEIGHT_MACHINE = 17
    FREE_OLYMPIC_WEIGHT_MACHINE = 18
    FREE_STRIDER = 19
    ROWER = 20
    # Sensors / Sub-devices (IDs 40-86)
    STRIDE_BASED_SPEED_DISTANCE = 40
    WEIGHT_SCALE = 41
    AUDIO_CONTROL = 48
    BIKE_POWER = 49
    BIKE_SPEED_AND_CADENCE = 50
    BLOOD_PRESSURE = 51
    ENVIRONMENT = 52
    FITNESS_EQUIPMENT_DEVICE = 53
    GEOCACHE = 54
    HEART_RATE_MONITOR = 55
    LIGHT_ELECTRIC_VEHICLE = 56
    MULTI_SPORT_SPEED_DISTANCE = 57
    AUDIO = 64
    SPEED = 65
    GRADE = 66
    WATTS = 67
    TORQUE = 68
    RESISTANCE = 69
    PULSE = 70
    KEY_PRESS = 71
    BIKE_GEAR = 72
    STRIDE = 73
    FAN = 74
    SAFETY = 75
    MODE = 76
    DISTANCE = 77
    USER_TIME = 78
    USER_DATA = 79
    WORKOUT_CONTROL = 81
    REPS = 83
    BURN_RATE = 84
    EQUIPMENTLESS = 85
    MIRROR = 86
```

---

## Command (24 values)

Command IDs for packet byte 2.

```python
class Command:
    NONE = 0
    PORTAL_DEV_LISTEN = 1
    READ_WRITE_DATA = 2               # ← Primary data command
    TEST = 3
    CONNECT = 4
    DISCONNECT = 5
    CALIBRATE = 6
    UPDATE = 9
    ENTER_BOOTLOADER = 56             # 0x38
    SET_TESTING_KEY = 112             # 0x70
    SET_TESTING_TACH = 113            # 0x71
    SUPPORTED_DEVICES = 128           # 0x80
    DEVICE_INFO = 129                 # 0x81
    SYSTEM_INFO = 130                 # 0x82
    TASK_INFO = 131                   # 0x83
    VERSION_INFO = 132                # 0x84
    MODE_HISTORY = 134                # 0x86
    SUPPORTED_COMMANDS = 136          # 0x88
    READ_CONFIG = 137                 # 0x89
    VERIFY_SECURITY = 144             # 0x90
    PROTOCOL_DATA = 145               # 0x91
    SPEED_GRADE_LIMIT = 146           # 0x92
    SERIAL_NUMBER = 149               # 0x95
    RAW = 255                         # 0xFF
```

---

## CmdStatus (9 values)

Response status codes in packet byte 3.

```python
class CmdStatus:
    DEV_NOT_SUPPORTED = 0
    CMD_NOT_SUPPORTED = 1
    DONE = 2                          # ← Success
    IN_PROGRESS = 3
    FAILED = 4
    TIME_LEFT = 5
    # Note: 6 is undefined
    UNKNOWN_FAILURE = 7
    SECURITY_BLOCK = 8                # Needs VerifySecurity
    COMM_FAILED = 9
```

---

## WorkoutMode (16 values)

State machine for equipment operation. Used by BitField 12.

```python
class WorkoutMode:
    UNKNOWN = 0
    IDLE = 1
    RUNNING = 2                       # Active workout
    PAUSE = 3
    RESULTS = 4                       # Workout complete, showing results
    DEBUG = 5
    LOG = 6
    MAINTENANCE = 7
    DMK = 8                           # Safety key removed
    DEMO = 9
    WARM_UP = 10
    COOL_DOWN = 11
    SLEEP = 12
    RESUME = 13                       # Resuming from pause
    LOCKED = 14                       # Security locked
    PAUSE_OVERRIDE = 20               # Pause override (belt stop)
```

### WorkoutMode ↔ ConsoleState Mapping

```python
MODE_TO_STATE = {
    WorkoutMode.UNKNOWN:        ConsoleState.UNKNOWN,
    WorkoutMode.IDLE:           ConsoleState.IDLE,
    WorkoutMode.RUNNING:        ConsoleState.WORKOUT,
    WorkoutMode.PAUSE:          ConsoleState.PAUSED,
    WorkoutMode.RESULTS:        ConsoleState.WORKOUT_RESULTS,
    WorkoutMode.DEBUG:          ConsoleState.UNKNOWN,
    WorkoutMode.LOG:            ConsoleState.UNKNOWN,
    WorkoutMode.MAINTENANCE:    ConsoleState.UNKNOWN,
    WorkoutMode.DMK:            ConsoleState.SAFETY_KEY_REMOVED,
    WorkoutMode.DEMO:           ConsoleState.UNKNOWN,
    WorkoutMode.WARM_UP:        ConsoleState.WARM_UP,
    WorkoutMode.COOL_DOWN:      ConsoleState.COOL_DOWN,
    WorkoutMode.SLEEP:          ConsoleState.UNKNOWN,
    WorkoutMode.RESUME:         ConsoleState.RESUME,
    WorkoutMode.LOCKED:         ConsoleState.LOCKED,
    WorkoutMode.PAUSE_OVERRIDE: ConsoleState.PAUSE_OVERRIDE,
}
```

---

## ConsoleState (13 values)

Higher-level state used by the Sindarin abstraction layer.

```python
class ConsoleState:
    UNKNOWN = 0
    IDLE = 1
    WORKOUT = 2
    PAUSED = 3
    WORKOUT_RESULTS = 4
    SAFETY_KEY_REMOVED = 5
    WARM_UP = 6
    COOL_DOWN = 7
    RESUME = 8
    PAUSE_OVERRIDE = 9
    LOCKED = 10
    DEMO = 11
    SLEEP = 12
    ERROR = 13
```

---

## Manufacturer (15 values)

Equipment manufacturer identifier from DeviceInfo response.

```python
class Manufacturer:
    NONE = 0
    ICON = 1
    FREE_MOTION = 2
    PRO_FORM = 3
    NORDIC_TRACK = 4                  # ← S22i
    WEIDER = 5
    HEALTH_RIDER = 6
    REEBOK = 7
    WORKOUT_WAREHOUSE = 8
    WESLO = 9
    UTS = 10
    RIP60 = 11
    GOLDS_GYM = 12
    IFIT = 13
    ALTRA = 14
    SEARS = 15
```

---

## ConsoleType (12 values)

Equipment category type.

```python
class ConsoleType:
    TREADMILL = 0
    INCLINE_TRAINER = 1
    ELLIPTICAL = 2
    BIKE = 3                          # ← S22i
    STRIDER = 4
    FREE_STRIDER = 5
    VERTICAL_ELLIPTICAL = 6
    SPIN_BIKE = 7
    ROWER = 8
    EQUIPMENTLESS = 9
    MIRROR = 10
    NONE = 11
```

---

## FitnessValue (62 values)

High-level value identifiers used by the Sindarin abstraction.
Maps 1:1 to BitField names (where applicable).

```python
class FitnessValue:
    KPH = 0
    AVG_SPEED_KPH = 1
    GRADE = 2
    AVERAGE_GRADE = 3
    RESISTANCE = 4
    WATTS = 5
    CURRENT_DISTANCE = 6
    RPM = 7
    KEY_OBJECT = 8
    VOLUME = 9
    PULSE = 10
    RUNNING_TIME = 11
    WORKOUT_MODE = 12
    LAP_TIME = 13
    ACTUAL_KPH = 14
    ACTUAL_INCLINE = 15
    CURRENT_TIME = 16
    CURRENT_CALORIES = 17
    GOAL_TIME = 18
    WEIGHT = 19
    GEAR = 20
    MAX_GRADE = 21
    MIN_GRADE = 22
    MAX_KPH = 23
    MIN_KPH = 24
    IDLE_TIMEOUT = 25
    PAUSE_TIMEOUT = 26
    SYSTEM_UNITS = 27
    MAX_RESISTANCE_LEVEL = 28
    MAX_WEIGHT = 29
    WARMUP_TIMEOUT = 30
    COOL_DOWN_TIMEOUT = 31
    MAX_PULSE = 32
    MAX_RPM = 33
    AVERAGE_WATTS = 34
    WATT_GOAL = 35
    DISTANCE_GOAL = 36
    MOTOR_TOTAL_DISTANCE = 37
    TOTAL_TIME = 38
    VERTICAL_METER_NET = 39
    VERTICAL_METER_GAIN = 40
    IDLE_MODE_LOCKOUT = 41
    SLEEP_TIMER_STATE = 42
    START_REQUESTED = 43
    FAN_STATE = 44
    ACTIVATION_LOCK = 45
    PAUSED_TIME = 46
    REQUIRE_START_REQUESTED = 47
    STROKES = 48
    STROKES_PER_MIN = 49
    FIVE_HUNDRED_SPLIT = 50
    AVG_FIVE_HUNDRED_SPLIT = 51
    IS_CLUB_UNIT = 52
    IS_READY_TO_DISCONNECT = 53
    IS_CONSTANT_WATTS_MODE = 54
    FIVE_HUNDRED_SPLIT_DECIMAL = 55
    DEVICE_TYPE = 56
    EFFORT_SCORE = 57
```

---

## SystemConfiguration (6 values)

MCU system configuration from SystemInfo response.

```python
class SystemConfiguration:
    SLAVE = 0
    MASTER = 1
    MULTI_MASTER = 2
    SINGLE_MASTER = 3
    PORTAL_TO_SLAVE = 4
    PORTAL_TO_MASTER = 5
```

---

## SystemLanguage (10 values)

Default language setting from SystemInfo response.

```python
class SystemLanguage:
    NONE = 0
    GERMAN = 1
    ENGLISH = 2
    SPANISH = 3
    FRENCH = 4
    ITALIAN = 5
    DUTCH = 6
    RUSSIAN = 7
    PORTUGUESE = 8
    CHINESE = 9
    JAPANESE = 10
```

---

**Last Updated:** 2026-02-10
**Source:** `Device.cs`, `Command.cs`, `CmdStatus.cs`, `WorkoutMode.cs`, `Manufacturer.cs`,
`ConsoleState.cs`, `ConsoleType.cs`, `FitnessValue.cs`, `SystemDeviceInfo.cs`
