# GlassOS Workout System — Reverse Engineering Notes

> Derived from decompiled source: glassos_service v7.10.3, arda v1.35.15, gandalf v1.34.16, rivendell v1.42.14, standalone v2.6.90, ERU v2.27.18. Source in `iFit/ifit_apps/`.

## Architecture Overview

The GlassOS workout system is a gRPC service architecture running on the bike's Android console:

| App | Role |
|-----|------|
| **glassos_service** | Core hardware service — owns USB, FitPro protocol, gRPC workout APIs |
| **arda/gandalf/rivendell** | UI clients — connect to glassos_service via gRPC |
| **standalone** (Xamarin) | Legacy app — talks FitPro directly over USB (no glassos_service) |
| **ERU** | System service — USB permissions, device management, IPC bridge |

The glassos_service is the authoritative reference for how to talk to the bike hardware.

## Console State Machine (Hardware-Level)

The bike's FitPro microcontroller has its own state machine, controlled by writing to **BitField 12 (WORKOUT_MODE)**:

```
         +-------------+
         |   IDLE (1)  |<-----------------------+
         +------+------+                        |
                | Write WARM_UP                 | Write IDLE
                v                               |
         +-------------+                        |
         | WARM_UP (10)|--timeout--+            |
         +------+------+          |            |
                | Write RUNNING   |            |
                v                 v            |
         +-------------+   +-----------+       |
         | RUNNING (2) |   |COOL_DOWN  |-------+
         +--+---+---+--+   |   (11)    |
            |   |   |      +-----------+
    pause   |   |   | stop      ^
            v   |   v           |
    +-------+   |  +--------+  | Write COOL_DOWN
    |PAUSE  |   |  |RESULTS |  |
    |  (3)  |   |  |  (4)   |--+
    +---+---+   |  +--------+
        |       |
   resume       | DMK (safety key)
        |       v
        |  +--------+
        +->|DMK (8) |--key replaced--> IDLE or RUNNING
           +--------+
```

Other states: SLEEP (12), DEMO (9), LOCKED (14), RESUME (13).

### ConsoleState Enum (Proto)

```
DISCONNECTED = 0
CONSOLE_STATE_UNKNOWN = 1
IDLE = 2
WORKOUT = 3
PAUSED = 4
WORKOUT_RESULTS = 5
SAFETY_KEY_REMOVED = 6
WARM_UP = 7
COOL_DOWN = 8
RESUME = 9
LOCKED = 10
DEMO = 11
SLEEP = 12
ERROR = 13
```

Source: `glassos_service/.../resources/console/ConsoleState.proto`

## Workout Lifecycle (Software-Level)

### Internal Workout State Machine

```
InternalWorkoutState:
  UNKNOWN -> IDLE -> {DMK, RUNNING}
  DMK -> {IDLE, RUNNING}
  RUNNING -> {PAUSED, RESULTS}
  PAUSED -> {RUNNING, RESULTS}
  RESULTS -> IDLE
```

Transitions are enforced by `InternalWorkoutState.m16826a()` — throws `InvalidWorkoutStateException` on illegal transitions. No concurrent workouts allowed (volatile boolean guard).

Source: `glassos_service/.../sources/glassos/workout/internal/InternalWorkoutState.java`

### Full Startup Sequence

```
1. UI calls ProgrammedWorkoutSessionService.AddAndStart()
   with WorkoutSessionItems: [WARM_UP, MAIN, COOL_DOWN]

2. WorkoutSessionManager.m617D():
   a. Validate: sessions not empty, not already running
   b. Set sessionId
   c. Enter m615B() orchestration (under mutex)

3. m618E() state machine:
   a. Load workout data (controls, targets, metadata)
   b. Get next WorkoutPackage from queue
   c. Determine phase (WARM_UP -> MAIN -> COOL_DOWN)
   d. Call m620G() to switch console to correct phase
   e. Call m622K() to setup control handlers

4. For each control type (RESISTANCE, INCLINE, etc.):
   a. Load control timeline from Workout.controls
   b. Apply scaled controls for difficulty level
   c. Initialize at starting level
   d. Begin "follow workout" mode

5. Set InternalWorkoutState -> RUNNING
6. Timer starts advancing GoalMetric.timestamp
7. Controls auto-applied at correct timestamps
```

Source: `glassos_service/.../sources/glassos/workout/session/WorkoutSessionManager.java` (3367 lines)

### gRPC Service Definitions

**Simple workout (manual/free ride):**

```protobuf
service WorkoutService {
  rpc StartNewWorkout(Empty) returns (StartWorkoutResponse);
  rpc StartLoggedWorkout(WorkoutID) returns (StartWorkoutResponse);
  rpc Pause(Empty) returns (WorkoutResult);
  rpc Resume(Empty) returns (WorkoutResult);
  rpc Stop(Empty) returns (WorkoutResult);
  rpc GetWorkoutState(Empty) returns (WorkoutStateMessage);
  rpc WorkoutStateChanged(Empty) returns (stream WorkoutStateMessage);
}
```

**Programmed workout (multi-segment):**

```protobuf
service ProgrammedWorkoutSessionService {
  rpc AddAndStart(AddAllWorkoutSegmentsRequest) returns (ProgrammedWorkoutServiceResponse);
  rpc AddAllWorkoutSegments(AddAllWorkoutSegmentsRequest) returns (ProgrammedWorkoutServiceResponse);
  rpc Start(Empty) returns (ProgrammedWorkoutServiceResponse);
  rpc Stop(Empty) returns (ProgrammedWorkoutServiceResponse);
  rpc Next(Empty) returns (ProgrammedWorkoutServiceResponse);
  rpc Pause(Empty) returns (ProgrammedWorkoutServiceResponse);
  rpc Resume(Empty) returns (ProgrammedWorkoutServiceResponse);
  rpc GetCurrentWorkoutSessionState(Empty) returns (WorkoutSessionState);
  rpc WorkoutSessionStateChanged(Empty) returns (stream WorkoutSessionState);
  rpc GetLatestUnfinishedWorkoutSession(Empty) returns (RecoveredSessionResponse);
  rpc RunUnfinishedSession(RecoveredSession) returns (Empty);
}
```

Source: `glassos_service/.../resources/workout/WorkoutService.proto`, `ProgrammedWorkoutSessionService.proto`

## Three-Phase Workout Structure

Every workout has up to 3 segments:

```protobuf
enum ItemType {
  ITEM_TYPE_WARM_UP = 0;    // Optional (Workout.skipWarmup=true skips it)
  ITEM_TYPE_MAIN = 1;       // Required
  ITEM_TYPE_COOL_DOWN = 2;  // Optional
}
```

**Warm-up:**
- Console state set to `WARM_UP (10)` via WORKOUT_MODE bitfield
- Duration from `WARM_UP_TIMEOUT` feature (writable, BitField 43, uint16 seconds)
- Has its own control timeline (usually gentle ramp-up)
- Auto-transitions to MAIN when timer expires or user skips
- Managed by `WarmUpTimeoutFacade`

**Cool-down:**
- Console state set to `COOL_DOWN (11)`
- Duration from `COOL_DOWN_TIMEOUT` feature (writable, BitField 65, uint16 seconds)
- Has its own control timeline (usually gentle ramp-down)
- Auto-transitions to RESULTS when timer expires
- Managed by `CoolDownTimeoutFacade`

**Manual ("Free Ride") workouts:** `WorkoutCategory.WORKOUT_CATEGORY_MANUAL` — empty control list, user controls resistance/incline directly. No auto-follow.

Source: `glassos_service/.../resources/workout/data/ItemType.proto`, `glassos_service/.../sources/glassos/facades/WarmUpTimeoutFacade.java`

## FitPro v1 Protocol — Hardware Commands

### Packet Format

```
[DeviceId] [Length] [Command] [Payload...] [Checksum]
  1 byte    1 byte   1 byte    N bytes      1 byte

Baud rate:    115200
Max packet:   58 bytes
Checksum:     sum of all bytes mod 256
Cmd timeout:  2500ms
Cmd delay:    400ms between commands
Read delay:   80ms after write before read
```

Source: `glassos_service/.../sources/glassos/fitpro1/V1CommandBase.java`

### Command Types

| Command | Value | Description |
|---------|-------|-------------|
| READ_WRITE_DATA | 0x02 | Primary command for feature read/write |
| CALIBRATE | 0x06 | Calibration command |
| DEVICE_INFO | 0x81 | Get device information |
| SYSTEM_INFO | 0x82 | Get system information |
| VERSION_INFO | 0x84 | Get firmware version |
| VERIFY_SECURITY | 0x90 | Security verification |
| SERIAL_NUMBER | 0x95 | Get serial number |

All bike control goes through `READ_WRITE_DATA (0x02)`. It reads/writes **bitfields** — indexed data slots that map to hardware features.

### Bitfield Index Numbering

The GlassOS `BitField` enum has two numbering schemes: the **enum ordinal** (position in the enum, 0-94) and the **fieldIndex** (wire protocol index used for bitmask encoding). For fields 0-17 these are identical, but they diverge starting at field 18. The tables below use **fieldIndex** — this is the value used on the wire for `section = fieldIndex / 8`, `bit = fieldIndex % 8`.

Source: `glassos_service/.../sources/glassos/bitfield/BitField.java` — constructor is `BitField(name, enumOrdinal, converter, fieldIndex, readOnly, featureId)`.

### Bitfields — Writable (Control the Bike)

| fieldIndex | Name | Feature | Encoding | Notes |
|------------|------|---------|----------|-------|
| 0 | KPH | Speed target | `value * 100` -> uint16 | Not writable on bikes (treadmills only) |
| 1 | GRADE | Incline % | `value * 100` -> int16 | S22i range: -6% to +20% |
| 2 | RESISTANCE | Resistance level | `value * 10000/max` -> uint16 | S22i range: 0-24 |
| 9 | VOLUME | Volume | raw -> uint8 | |
| 12 | WORKOUT_MODE | Console state | enum -> uint8 | IDLE/RUNNING/PAUSE/etc |
| 26 | GEAR | Gear selection | enum -> uint8 | (enum ordinal 25) |
| 35 | PAUSE_TIMEOUT | Pause timer (sec) | raw -> uint16 | (enum ordinal 32) |
| 46 | WARMUP_TIMEOUT | Warmup timer (sec) | raw -> uint16 | (enum ordinal 43) |
| 61 | WATT_GOAL | Target watts (ERG) | raw -> uint16 | (enum ordinal 56) |
| 64 | DISTANCE_GOAL | Distance goal (m) | raw -> uint32 | (enum ordinal 58) |
| 71 | COOLDOWN_TIMEOUT | Cooldown timer (sec) | raw -> uint16 | (enum ordinal 65) |
| 95 | IDLE_MODE_LOCKOUT | Idle lockout | enum -> uint8 | (enum ordinal 82) |
| 96 | START_REQUESTED | Start flag | 0/1 -> uint8 | (enum ordinal 83), readOnly |
| 98 | FAN_STATE | Fan speed | enum -> uint8 | (enum ordinal 84) |
| 108 | REQUIRE_START_REQUESTED | Start-required flag | 0/1 -> uint8 | (enum ordinal 87) |
| 119 | IS_CONSTANT_WATTS_MODE | ERG mode toggle | 0/1 -> uint8 | (enum ordinal 94) |

### Bitfields — Readable (Data from Bike)

| fieldIndex | Name | Feature | Encoding |
|------------|------|---------|----------|
| 3 | WATTS | Current power | uint16 raw |
| 4 | CURRENT_DISTANCE | Session distance (m) | uint32 raw |
| 5 | RPM | Cadence | uint16 raw |
| 6 | DISTANCE | Distance (m) | uint32 raw (same FeatureId as CURRENT_DISTANCE) |
| 10 | PULSE | Heart rate BPM | PulseConverter: `[userPulse, avg, reserved, sourceType]` |
| 11 | RUNNING_TIME | Running time | uint32 raw (no FeatureId — internal counter) |
| 13 | CALORIES | Calories | CaloriesConverter: `raw * 1024 / 100_000_000` (same FeatureId as CURRENT_CALORIES) |
| 15 | LAP_TIME | Lap time | uint16 raw |
| 16 | ACTUAL_KPH | Actual speed | `uint16 / 100` |
| 17 | ACTUAL_INCLINE | Actual incline % | `int16 / 100` |
| 20 | RECOVERABLE_CONSOLE_TIME | Session time | uint32 raw (enum ordinal 19) |
| 21 | CURRENT_CALORIES | Session calories | CaloriesConverter (enum ordinal 20) |
| 22 | GOAL_TIME | Goal time | uint32 raw (enum ordinal 21) |
| 27 | MAX_GRADE | Max incline | `int16 / 100` (enum ordinal 26) |
| 28 | MIN_GRADE | Min incline | `int16 / 100` (enum ordinal 27) |
| 30 | MAX_KPH | Max speed | `uint16 / 100` (enum ordinal 29) |
| 31 | MIN_KPH | Min speed | `uint16 / 100` (enum ordinal 30) |
| 42 | MAX_RESISTANCE_LEVEL | Max resistance | uint8 raw (enum ordinal 39) |
| 49 | MAX_PULSE | Max HR | uint8 raw (enum ordinal 46) |
| 52 | AVERAGE_GRADE | Avg incline | `int16 / 100` (enum ordinal 48) |
| 54 | AVERAGE_WATTS | Avg power | uint16 raw (enum ordinal 50) |
| 57 | MAX_RPM | Max cadence | uint16 raw (enum ordinal 53) |
| 69 | MOTOR_TOTAL_DISTANCE | Lifetime distance | uint32 raw (enum ordinal 63) |
| 70 | TOTAL_TIME | Lifetime time | uint32 raw (enum ordinal 64) |
| 75 | VERTICAL_METER_NET | Net elevation | `uint32 / 10000` (enum ordinal 69) |
| 76 | VERTICAL_METER_GAIN | Elevation gain | `uint32 / 10000` (enum ordinal 70) |
| 103 | RECOVERABLE_PAUSED_TIME | Paused time | uint32 raw (enum ordinal 86) |

Source: `glassos_service/.../sources/glassos/bitfield/BitField.java`, `glassos_service/.../sources/glassos/fitpro1/converters_v1/`

### Value Encoding Details

**Speed (KPH):**
```
To bytes:   (double_value * 100) -> 16-bit little endian
From bytes: uint16 / 100.0
Example:    12.5 km/h -> 0xE803 (1250 decimal)
```

**Incline/Grade (%):**
```
To bytes:   (double_value * 100) -> 16-bit little endian, signed
From bytes: int16 / 100.0
Example:    5.5% -> 0x2602 (550 decimal)
Example:   -3.0% -> 0xD4FE (-300 decimal, two's complement)
```

**Resistance:**
```
scale_factor = 10000 / max_resistance_level
To bytes:   (double_value * scale_factor - 1) -> 16-bit little endian
From bytes: (uint16 + 1) / scale_factor

S22i max_resistance_level = 24
scale_factor = 10000 / 24 = 416.67
Example: resistance 12 -> 12 * 416.67 - 1 = 4999 -> 0x8713
```

Source: `glassos_service/.../sources/glassos/fitpro1/converters_v1/ResistanceConverter.java`, `GradeConverter.java`, `KphConverter.java`

### Communication Stack

```
FitnessConsoleImpl
  -> FitPro1ConsoleImpl (equipmentConsole)
    -> writeFeatureValue(featureId, value)
      -> FeatureToBitFieldMap.getBitField(featureId)
        -> BitFieldCommItem(bitField, encodedValue)
          -> FitPro1CommandQueue.send()
            -> ReadWriteDataCommand.buildPacket()
              -> FitProCommunicationGroup.sendMessage()
                -> ICommunicationAdapter (USB bulk endpoints)
                  -> Endpoint 0: read, Endpoint 1: write
                    -> Hardware MCU
```

Source: `glassos_service/.../sources/glassos/console/FitnessConsoleImpl.java`, `glassos_service/.../sources/al/ICommunicationAdapter.java`

### Command Queue

- Batches multiple bitfield reads/writes into single READ_WRITE_DATA packets
- Exponential backoff on failure (up to 4 retries)
- Dynamic delay: 10-150ms range, adjusts based on success/failure
- Lock-based synchronization for USB access
- Clears queue on unexpected disconnect

Source: `standalone/.../Sindarin.FitPro1.Core/Sindarin.FitPro1.Equipment.Communication/QueueManager.cs`

## Control Timeline (Programmed Workouts)

Controls are time-indexed instructions sent to the bike as the workout progresses:

```protobuf
message Control {
  ControlType type = 1;   // INCLINE, RESISTANCE, WATTS, etc.
  double at = 2;          // seconds into workout
  double value = 3;       // target value at this time
}
```

### Control Types

```protobuf
enum ControlType {
  CONTROL_TYPE_UNKNOWN = 0;
  CONTROL_TYPE_INCLINE = 1;
  CONTROL_TYPE_MPS = 2;              // meters per second (speed)
  CONTROL_TYPE_RESISTANCE = 3;
  CONTROL_TYPE_GEAR = 4;
  CONTROL_TYPE_AMPLITUDE = 5;        // elliptical
  CONTROL_TYPE_CALORIES = 6;
  CONTROL_TYPE_FREQUENCY = 7;        // rowing
  CONTROL_TYPE_RELATIVERESISTANCE = 8;
  CONTROL_TYPE_RPM = 9;
  CONTROL_TYPE_SPM = 10;             // strokes per minute
  CONTROL_TYPE_WATTS = 11;
  CONTROL_TYPE_ZONE = 12;            // heart rate zone
}
```

### Difficulty Scaling

Workouts support 18 difficulty levels (minus-6 to plus-12), each with its own control list via `ScaledControls`. The `AbstractControlHandler` picks controls for the current difficulty level and applies them as the workout timer advances.

### "Follow Workout" Mode

`ResistanceService.FollowWorkout()` / `InclineService.FollowWorkout()` auto-applies programmed targets. `StopFollowing()` lets the user override manually. The `GoalMetric` tracks current position; `m12024q()` finds the next applicable control for the current timestamp.

Source: `glassos_service/.../sources/glassos/workout/controls/AbstractControlHandler.java` (607 lines), `glassos_service/.../resources/workout/data/Control.proto`

## ERG Mode (Constant Watts)

The bike natively supports constant-watt (ERG) mode:

```protobuf
enum ConstantWattsState {
  CONSTANT_WATTS_STATE_DISABLED = 0;
  CONSTANT_WATTS_STATE_ENABLED = 1;
  CONSTANT_WATTS_STATE_PAUSED = 2;
}

service ConstantWattsService {
  rpc IsSupported(Empty) returns (AvailabilityResponse);
  rpc SetConstantWatts(ConstantWattsMessage) returns (AvailabilityResponse);
  rpc Enable(Empty) returns (Empty);
  rpc Disable(Empty) returns (Empty);
  rpc Pause(Empty) returns (Empty);
  rpc Resume(Empty) returns (Empty);
}
```

The bike's MCU adjusts resistance automatically to maintain the target wattage. Written via **BitField 56 (WATT_GOAL)**.

Source: `glassos_service/.../resources/workout/ConstantWattsService.proto`

## DMK (Safety Key)

DMK = "Display Motion Kinetics" — triggered when the physical safety key is removed from the console. The bike MCU sends `ConsoleState.SAFETY_KEY_REMOVED` (state 6 in proto, WorkoutMode 8 in FitPro).

Behavior:
- Pauses workout immediately (hardware-enforced)
- Software must detect state change and update internal state to DMK
- User re-inserts key -> console returns to IDLE
- Can resume (DMK -> RUNNING) or stop (DMK -> RESULTS)

Source: `glassos_service/.../sources/glassos/console/ConsoleState.java`

## Metric Services

All metric services follow the same pattern — gRPC streams with per-second updates tagged with workoutID:

| Service | Key Metric Fields |
|---------|-------------------|
| ResistanceService | lastResistance, max, avg, min + FollowWorkout/SetResistance |
| InclineService | lastInclinePercent, max, avg, min + FollowWorkout/SetIncline |
| SpeedService | lastKph, max, avg, min + FollowWorkout/SetSpeed |
| WattsService | lastWatts, max, avg, min |
| HeartRateService | lastBpm, max, avg, min |
| CadenceService | lastStepsPerMinute |
| RpmService | lastRpm, max, avg, min, targetRpm |
| CaloriesBurnedService | lastCalories |
| DistanceService | lastDistanceKm, remainingDistanceKm |
| ElapsedTimeService | timeSeconds, timeRemaining |
| ElevationService | lastElevationMeters |
| GearService | lastGear |
| StepCountService | lastStepCount |

Source: `glassos_service/.../resources/workout/*Service.proto`

## Workout Data Structure

```protobuf
message Workout {
  optional string title = 1;
  optional string id = 2;
  optional string heroImageUrl = 3;
  optional StringList categories = 4;
  WorkoutFilterList workoutFilters = 5;
  ControlList controls = 6;              // time-indexed control instructions
  WorkoutTargetType targetType = 7;      // CALORIES, METERS, or SECONDS
  optional double targetValue = 8;       // goal amount
  WorkoutType workoutType = 9;           // CARDIO, CYCLE, etc.
  optional MusicRegionList videoMusicRegions = 10;
  optional Sources sources = 11;         // video streams
  optional LibraryCategoryMap libraryFilters = 12;
  optional ScaledControls scaledControls = 13;
  optional int32 startingLevel = 14;
  optional bool skipWarmup = 15;
  optional MapCoordinateList mapCoordinates = 16;
  optional string workoutDriverFQN = 17;
}

enum WorkoutCategory {
  WORKOUT_CATEGORY_MANUAL = 0;          // Free ride
  WORKOUT_CATEGORY_VIDEO = 1;
  WORKOUT_CATEGORY_MAP = 2;
  WORKOUT_CATEGORY_TIME_GOAL = 3;
  WORKOUT_CATEGORY_CALORIE_GOAL = 4;
  WORKOUT_CATEGORY_DISTANCE_GOAL = 5;
  WORKOUT_CATEGORY_CUSTOM = 6;
  WORKOUT_CATEGORY_THIRD_PARTY = 7;
}

enum WorkoutType {
  CARDIO, CYCLE, FUSION, ROW, RUN, STRENGTH, VIBRATION, CROSS_TRAINING
}

enum WorkoutTargetType {
  UNKNOWN = 0;
  CALORIES = 1;
  METERS = 2;
  SECONDS = 3;
}
```

Source: `glassos_service/.../resources/workout/data/Workout.proto`, `WorkoutCategory.proto`, `WorkoutType.proto`

## Console Hardware Metadata

```protobuf
enum ConsoleType {
  CONSOLE_TYPE_UNKNOWN = 0;
  TREADMILL = 1;
  INCLINE_TRAINER = 2;
  ELLIPTICAL = 3;
  BIKE = 4;                    // NordicTrack S22i
  STRIDER = 5;
  FREE_STRIDER = 6;
  VERTICAL_ELLIPTICAL = 7;
  SPIN_BIKE = 8;
  ROWER = 9;
  EQUIPMENTLESS = 10;
  MIRROR = 11;
  VIBRATION = 12;
  REFORMER = 13;
}
```

ConsoleInfo provides capabilities: canSetSpeed, canSetIncline, canSetResistance, canSetGear, supportsConstantWatts, supportsPulse, plus min/max ranges for all controllable parameters.

Source: `glassos_service/.../resources/console/ConsoleInfo.proto`, `ConsoleType.proto`

## IPC: Standalone <-> ERU

The legacy standalone app communicates with ERU via Android Messenger/Handler (not AIDL). ERU exposes a bound service:

```
Service: com.ifit.eru.IpcService
Action: "com.ifit.eru.IpcService"
```

Messages are JSON-serialized `IpcObject` (name, data, id, error). Available methods: `GetAllVersions`, `GetLastTouch`, `GetIsUpdateAvailable`, `GetCurrentScreenBrightness`, `SetScreenBrightness`, `SetSystemLanguage`, `SetTimeZone`, `GetAdbLogs`.

**Hardware control is NOT exposed via IPC.** The standalone app talks FitPro directly over USB. USB permission is obtained from ERU via broadcast:

```
Request:  action="com.ifit.eru.USB_PERMISSION_REQUEST", extra=UsbDevice
Response: action="com.ifit.eru.USB_PERMISSION_GRANTED"
```

Source: `eru/.../sources/com/ifit/shire/ipc/IpcService.java`, `standalone/.../Sindarin.Usb.Android.Permissions/EruUsbPermissionService.cs`

## Key Press Events

The console reports physical button presses:

```protobuf
enum KeyCode {
  STOP = 1;
  START = 2;
  SPEED_UP = 3;
  SPEED_DOWN = 4;
  INCLINE_UP = 5;
  INCLINE_DOWN = 6;
  RESISTANCE_UP = 7;
  RESISTANCE_DOWN = 8;
  MANUAL_WORKOUT = 11000;
  MAP_WORKOUT = 11001;
  TRAIN_WORKOUT = 11002;
  VIDEO_WORKOUT = 11006;
  // 300+ keycodes total
}
```

Source: `glassos_service/.../resources/console/KeyPress.proto`

## FitPro v2 Protocol

FitPro v2 is a complete protocol redesign used by newer hardware. Detection is automatic via USB product ID at connection time.

### Protocol Selection

| USB Product ID | Protocol |
|----------------|----------|
| 2 (FIT_PRO_1) | V1 — polling, bitmask-encoded bitfields |
| 3 (FIT_PRO_2) | V2 — event-driven, feature ID subscriptions |
| 4 (FIT_PRO_2_FTDI) | V2 (FTDI USB-serial variant) |

Source: `glassos_service/.../sources/glassos/usb/types/UsbProductType.java`, `glassos_service/.../sources/glassos/fitpro2/FitPro2Console.java`

### V2 Feature IDs

V2 uses numeric feature IDs in the 100-600 range instead of bitmask-indexed bitfields:

| Feature ID | Name | Direction | GlassOS FeatureId | Notes |
|-----------|------|-----------|-------------------|-------|
| 102 | SYSTEM_MODE | Read/Write | CONSOLE_STATE | Console state (IDLE/RUNNING/PAUSED — same values as V1 WORKOUT_MODE) |
| 103 | IDLE_SYSTEM_MODE_LOCK | Write | IDLE_MODE_LOCKOUT | |
| 161 | HEART_BEAT_INTERVAL | Write | HEART_BEAT_INTERVAL | Keepalive — write 720 every 720ms |
| 164 | HEART_BEAT_COUNT | Read | HEART_BEAT_COUNT | |
| 202 | CURRENT_CALORIES | Read | CURRENT_CALORIES | Workout calories |
| 222 | PULSE | Read/Write | PULSE | Heart rate |
| 252 | DISTANCE | Read | CURRENT_DISTANCE | Distance traveled |
| 256 | TOTAL_MACHINE_DISTANCE | Read | EQUIPMENT_TOTAL_DISTANCE | Lifetime distance |
| 301 | TARGET_KPH | Write | KPH | Target speed (treadmills) |
| 302 | CURRENT_KPH | Read | ACTUAL_KPH | Actual speed |
| 322 | RPM | Read | RPM | Cadence |
| 401 | TARGET_GRADE_PERCENT | Write | INCLINE | Target incline % |
| 402 | CURRENT_GRADE_PERCENT | Read | ACTUAL_INCLINE | Actual incline % |
| 503 | TARGET_RESISTANCE_LEVEL | Write | RESISTANCE | Resistance level |
| 504 | MAX_RESISTANCE | Read | MAX_RESISTANCE_LEVEL | Max resistance capability |
| 522 | WATTS | Read | WATTS | Power output |
| 523 | GOAL_WATTS | Write | WATT_GOAL | Native ERG — MCU adjusts resistance to maintain target watts |
| 604 | RUNNING_TIME | Read | RECOVERABLE_CONSOLE_TIME | Active workout time |

Source: `glassos_service/.../sources/glassos/features/v2/FeatureId.java`

### Subscribe/Event Model

V2 replaces V1's 100ms polling loop with an event-driven subscription model:

1. **Query**: Client sends `SupportedFeaturesCommand` to discover available features
2. **Subscribe**: Client sends `SubscribeCommand(features)` in batches of 8
3. **Events**: Console sends `EventResponse(featureId, value)` only when values change
4. **Unsubscribe**: `SubscribeCommand(false, [])` stops all subscriptions

Three subscription groups at init:
- **Basic** (always): `CONSOLE_TYPE`, `USB_HOST_BOARD_VERSION`, etc.
- **Extended** (diagnostics): `SYSTEM_ERROR`, `ANT_LICENSE_REQUEST`, etc.
- **Workout** (during exercise): `CONSOLE_STATE`, `TARGET_INCLINE`, `WATTS`, `RPM`, etc.

### Heartbeat Keepalive

V2 requires an explicit heartbeat to prevent MCU watchdog disconnect:

```
1. Write HEART_BEAT_INTERVAL = 720 (every 720ms in foreground)
2. Every 30 seconds: re-subscribe to all held features + collect latest values
3. Analytics event "Console_Heart_Beat" logged each cycle
```

V1 keeps the connection alive implicitly through continuous 100ms polling.

### V1 vs V2 Comparison

| Aspect | V1 | V2 |
|--------|----|----|
| Communication | Polling (100ms) | Event-driven (on-change) |
| Feature discovery | Hardcoded bitfield enum | Dynamic `SupportedFeaturesCommand` |
| Data format | Bitmask-indexed sections | Named feature IDs (100-600) |
| Keepalive | Implicit (continuous reads) | Explicit heartbeat (720ms write) |
| USB traffic | High (all fields every poll) | Low (only changed values) |
| Control writes | ReadWriteData with bitmask | WriteFeature with feature ID |

## Implications for Hyperborea

### Minimum viable (Zwift bridge):
1. **Read bike data** via READ_WRITE_DATA polling: RPM (bitfield 5), Watts (3), Actual Incline (17)
2. **Write resistance/incline** from FTMS control points: Resistance (bitfield 2), Grade (bitfield 1)
3. **Console state**: Write WORKOUT_MODE = RUNNING (2) to start, IDLE (1) to stop. Skip WARM_UP/COOL_DOWN — go straight to RUNNING.

### Full workout support:
4. **ERG mode**: Write WATT_GOAL (bitfield 56) when Zwift sends target power
5. **Warm-up/Cool-down**: Write appropriate WORKOUT_MODE + timeout bitfields
6. **DMK handling**: Monitor for SAFETY_KEY_REMOVED state, pause workout
7. **Pause/Resume**: Write WORKOUT_MODE = PAUSE (3) / RUNNING (2)

### What Hyperborea does NOT need:
- gRPC service layer (glassos_service's internal abstraction)
- Workout timeline/control scheduling (Zwift handles that)
- Activity logging (Zwift handles recording)
- Workout recovery (Zwift handles reconnection)
- Scaled controls / difficulty levels (Zwift sends exact values)

The bike MCU itself manages the state machine — we write the correct WORKOUT_MODE value and send resistance/incline/watt targets. The MCU handles motor control, safety, and feedback independently.
