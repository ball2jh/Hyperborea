# FitPro v1 Connection Lifecycle

> Complete startup sequence, polling loop, error recovery, and timing.
> Source: `FitPro1Console.cs`, `QueueManager.cs`, `FitProCommunicationAdapter.cs`,
> `FitProUsbConsoleCommunicationAdapter.cs`

---

## Full Initialization Sequence

The S22i uses USB transport. Here are the exact steps performed on connect:

### Step 1: USB Connection

Open the USB device:
- Vendor ID: `0x213C` (ICON Fitness)
- Product ID: `0x0002` (console mode)
- Endpoints: EP 0x81 IN (64 bytes), EP 0x02 OUT (64 bytes)

### Step 2: Clear Buffer

Send 64 bytes of `0xFF` to flush stale data from the MCU's output buffer.
Read the response. Repeat if needed until the MCU responds predictably.

This is performed by the `IClearBuffer.ClearBuffer()` interface at the
transport layer before any FitPro commands are sent.

### Step 3: DeviceInfo (identify primary device)

```
Send:    02 04 81 87
         Device=Main(2), Command=DeviceInfo(0x81)

Wait:    300ms read delay, 1000ms timeout

Receive: 07 XX 81 02 [SW] [HW] [SN:4 bytes] [MFG:2 bytes] [SECTIONS] [bitmask...]
```

Parse the response to learn:
- Actual device type (expect FitnessBike = 7 for S22i)
- Software/hardware version
- Serial number (uint32)
- Manufacturer (uint16, expect NordicTrack = 3)
- Supported BitFields bitmask

### Step 4: SupportedDevices (get sub-device list)

```
Send:    07 04 80 8B
         Device=FitnessBike(7), Command=SupportedDevices(0x80)
```

Returns list of sub-device types the controller manages.

### Step 5: SupportedCommands (get command list)

```
Send:    07 04 88 93
         Device=FitnessBike(7), Command=SupportedCommands(0x88)
```

Returns set of command IDs this device supports. Used to gate which
subsequent commands to attempt.

### Step 6: SystemInfo (get model and part numbers)

Only sent if SupportedCommands includes SystemInfo (0x82).

```
Send:    02 06 82 00 00 8A
         Device=Main(2), Command=SystemInfo(0x82), fetchMcuName=0, reserved=0
```

Returns: Model number, Part number, CPU frequency, Configuration, etc.

### Step 7: VersionInfo (get firmware versions)

Only sent if SupportedCommands includes VersionInfo (0x84).

```
Send:    02 06 84 00 00 8C
         Device=Main(2), Command=VersionInfo(0x84)
```

Returns: MasterLibraryVersion, MasterLibraryBuild, BLE library version, etc.

### Step 8: SerialNumber (get brainboard serial)

Only sent if SupportedCommands includes SerialNumber (0x95).

```
Send:    02 04 95 9B
         Device=Main(2), Command=SerialNumber(0x95)
```

Returns: Raw serial number bytes.

### Step 9: VerifySecurity (unlock for writes)

Only required if SoftwareVersion > 75 (from DeviceInfo).

```python
# Calculate security hash
hash = calculate_security_hash(
    serial_number=device_info.serial_number,  # From DeviceInfo
    part_number=system_info.part_number,       # From SystemInfo
    model_number=system_info.model             # From SystemInfo
)

# Calculate secret key
secret_key = 8 * version_info.master_library_version  # From VersionInfo

# Build request: 32-byte hash + 4-byte secret key (LE)
content = hash + secret_key.to_bytes(4, 'little')
```

```
Send:    07 28 90 [32 bytes hash] [4 bytes secret_key] [checksum]
         ContentLength=36, total Length=40
```

Response status=Done means the device is unlocked for write operations.

#### Security Hash Algorithm

```python
def calculate_security_hash(serial_number: int, part_number: int, model_number: int) -> bytes:
    result = bytearray(32)
    for i in range(32):
        result[i] = (i + 1) & 0xFF
        if (serial_number >> i) & 1 == 1:
            if i < 16:
                rotated = ((part_number << 16) | (part_number >> 16)) & 0xFFFFFFFF
                result[i] ^= (rotated >> i) & 0xFF
            else:
                result[i] ^= (part_number >> i) & 0xFF
        else:
            result[i] ^= (result[i] * ((i + model_number) & 0xFF)) & 0xFF
    return bytes(result)
```

### Step 10: Init Mode Config (RequireStartRequested + IdleModeLockout)

After security unlock, write two control fields that govern workout startup
behavior. Source: `FitPro1Console.cs:218-222`.

```csharp
// C# source:
bool flag = IsBitFieldSupported(BitField.RequireStartRequested);
SetRequireStartRequested(flag);                    // Field 108 = true (if supported)
bool idleModeLockout = !flag || !PrimaryDevice.Device.IsBeltBasedMachine();
SetIdleModeLockout(idleModeLockout);               // Field 95 = true (for non-belt machines)
```

| Field | ID | Value for S22i | Purpose |
|-------|----|----------------|---------|
| RequireStartRequested | 108 | true (if supported) | Tells MCU to wait for explicit start command |
| IdleModeLockout | 95 | true (non-belt = locked) | Prevents Idle→Running until explicitly unlocked |

**IsBeltBasedMachine** returns true only for Treadmill and InclineTrainer. All
other device types (SpinBike, FitnessBike, Rower, Elliptical, etc.) are
non-belt, so IdleModeLockout is always set to true during init.

These writes use the standard ReadWriteData command with BoolConverter encoding
(1 byte: `0x01` = true, `0x00` = false). They are sent as individual packets,
not batched.

### Step 11: Read Startup Data

Read the 17 startup BitFields (see [BITFIELDS.md](BITFIELDS.md)) via ReadWriteData.
Only fields reported as supported by DeviceInfo are included.

This tells us the device's range limits (max/min speed, incline, resistance),
timeout settings, current gear, workout mode, and activation state.

### Step 12: Start Periodic Polling

Begin the 100ms polling loop (see below).

---

## Steady-State Polling Loop

After initialization, the console enters a continuous read loop:

```python
while periodic_data_enabled:
    try:
        # 1. Build ReadWriteData with all periodic fields
        fields = [f for f in PERIODIC_FIELDS if is_supported(f)]
        request = build_readwrite_request(device_id, read_fields=fields)

        # 2. Send and wait for response (with 3-second timeout)
        response = send_and_receive(request, timeout=3000)

        # 3. Parse response, update local state
        parse_readwrite_response(response, fields)

        # 4. Check for user-initiated control changes
        detect_control_value_changes()

    except TimeoutError:
        timeout_service.record_timeout()

    finally:
        # 5. Wait before next poll
        await sleep(100)  # sendInterval = 100ms
```

### Timing Parameters

| Parameter | Value | Source |
|-----------|-------|--------|
| Send interval | 100ms | `FitPro1Console` constructor default |
| Periodic timeout | 3000ms | `PeriodicDataTimeoutMillis` |
| ReadWriteData read delay | 80ms | `ReadWriteDataCmd.ReadDelayMs` |
| ReadWriteData timeout | 1000ms | `ReadWriteDataCmd.ResponseTimeoutMs` |
| USB default timeout | 1 second | `FitProUsbConsoleCommunicationAdapter` |

---

## Error Recovery

### Command Retry

On response mismatch or validation failure, the QueueManager retries:

```python
MAX_RETRIES = 4

if response_mismatch:
    retry_count += 1
    delay_ms = 2 ** retry_count * 500  # Exponential backoff

    if retry_count >= MAX_RETRIES:
        retry_count = 0
        fatality_service.report("Command failed too many retries")
    else:
        requeue_command()  # Insert at front of queue
```

| Retry | Backoff |
|-------|---------|
| 1 | 1000ms |
| 2 | 2000ms |
| 3 | 4000ms |
| 4 | Fatal event |

### Dynamic Delay

The QueueManager adjusts inter-command delay based on success/failure:

```python
dynamic_delay = DynamicDelay(min=10ms, max=150ms)

if success:
    dynamic_delay.decrease()
elif failure:
    dynamic_delay.increase()
```

### Mismatch Detection

Failed responses are cached for 5 seconds. If a later command fails, the
QueueManager checks old responses for a match (handles out-of-order responses):

```python
unmatched_responses = []  # (bytes, timestamp) pairs

# On mismatch: save response
unmatched_responses.append((response, now))

# On next mismatch: check old responses
for old_response in unmatched_responses:
    if matches(old_response, current_command):
        use(old_response)  # Recovered!
        break

# Cleanup: remove entries older than 5 seconds
```

### Fatality Thresholds

| Condition | Threshold | Result |
|-----------|-----------|--------|
| Command retries exhausted | 4 retries | Non-permanent fatal event |
| Mismatch rate | >20 per 10-second window | Permanent fatal (belt machines) |
| USB items lost | 5 within decay window | Permanent fatal, comm failure |
| USB item decay | Every 2 seconds | Decrement lost count |

### Security Block Recovery

If a ReadWriteData response returns `SecurityBlock` (status 8), the
console automatically re-runs the VerifySecurity command:

```python
if response.status == CmdStatus.SecurityBlock:
    await unlock()  # Re-send VerifySecurity
```

---

## Disconnect Handling

### Controlled Disconnect

```python
async def shutdown():
    # Clear RequireStartRequested (FitnessConsoleBase.cs:376)
    set_value(RequireStartRequested, False)  # Field 108 = false

    periodic_data_enabled = False
    await queue_manager.shutdown()  # Wait for queue to drain (200ms polls)
    # Send Disconnect command (optional)
    send(build_disconnect(device_id))
```

### Unexpected Disconnect

When the USB connection drops during an active workout:

1. QueueManager is cleared
2. If in Workout/WarmUp/CoolDown state, start a local timer to estimate time
3. Attempt to reinitialize the connection
4. On reconnect, resume periodic polling

```python
def on_disconnect():
    if state in (Workout, WarmUp, CoolDown, PauseOverride):
        queue_manager.clear()
        estimated_time = current_time
        start_local_timer()  # Tick every 1 second

def on_timer_tick():
    if state in (WarmUp, CoolDown):
        estimated_time -= 1  # Count down
    else:
        estimated_time += 1  # Count up
```

---

## Connection State Machine

```
                    ┌───────────┐
                    │   Locked  │
                    └─────┬─────┘
                          │ VerifySecurity
                          ▼
┌──────┐  DeviceInfo  ┌───────┐  WorkoutMode=Running  ┌─────────┐
│ Init ├─────────────►│ Idle  ├───────────────────────►│ Workout │
└──────┘              └───┬───┘                        └────┬────┘
                          │                                 │
                          │ WorkoutMode=WarmUp               │ WorkoutMode=Pause
                          ▼                                 ▼
                    ┌──────────┐                      ┌────────┐
                    │  WarmUp  │                      │ Paused │
                    └────┬─────┘                      └────┬───┘
                         │ WorkoutMode=Running              │ WorkoutMode=Resume
                         └──────────►Workout◄──────────────┘
                                        │
                                        │ WorkoutMode=CoolDown
                                        ▼
                                  ┌──────────┐
                                  │ CoolDown │
                                  └────┬─────┘
                                       │ WorkoutMode=Results
                                       ▼
                                  ┌─────────┐
                                  │ Results │
                                  └────┬────┘
                                       │ WorkoutMode=Idle
                                       ▼
                                    ┌──────┐
                                    │ Idle │
                                    └──────┘
```

---

## Workout Lifecycle (IdleModeLockout)

The C# app uses **IdleModeLockout** (field 95) to gate workout start transitions
on non-belt machines (spin bikes, ellipticals, rowers, etc.). This prevents
accidental Idle→Running transitions.

### Lifecycle Sequence

```
Init                    → Field 108 = true, Field 95 = true (LOCKED)
PreparingWorkoutVM      → Field 95 = false (UNLOCKED)
StartWorkoutAsync       → Field 12 = Running (+ control fields bundled)
... workout in progress ...
EndWorkoutAsync         → Field 12 = Results (mode-only)
StateChangeFilter       → Field 95 = true (RE-LOCKED, automatic)
Shutdown                → Field 108 = false
```

### Source Locations

| Step | Source File | Detail |
|------|------------|--------|
| Init lock | `FitPro1Console.cs:218-222` | After security unlock, before startup read |
| Unlock before workout | `PreparingWorkoutViewModel.cs:444` | `SetIdleModeLockout(false)` for aerobic machines |
| Start workout | `WorkoutFacade.cs` → `SetValuesAsync` | Bundles Kph, Grade, Resistance, Gear, WorkoutMode |
| Pause | `WorkoutFacade.cs` → `SetValue` | Mode-only write (no bundling) |
| End workout | `WorkoutFacade.cs` → `SetValueAsync` | Mode-only write |
| Re-lock after workout | `FitnessConsoleBase.cs:253-256` | `StateChangeFilter` when leaving workout state |
| Shutdown clear | `FitnessConsoleBase.cs:376` | `SetRequireStartRequested(false)` |

### Machine Type Classification

```
IsBeltBasedMachine():  Treadmill, InclineTrainer → true
IsAerobicMachine():    Everything else            → true  (= !IsBeltBased)
```

Source: `DeviceExtensions.cs:7-14`, `ConsoleTypeExtensions.cs:46-49`

The IdleModeLockout write during init and the unlock before workout are
conditional on `IsAerobicMachine()`, so they apply to spin bikes but NOT
to treadmills. Treadmills use belt-based safety mechanisms instead.

### Key Insight

Without the unlock step (`Field 95 = false`), the MCU returns
`STATUS_FAILED` for Idle→Running mode transitions on non-belt machines.
This was confirmed on the S22i (SpinBike, device type 0x08). The retry
mechanism (4 attempts with exponential backoff) cannot overcome this —
the MCU consistently rejects the transition while IdleModeLockout is
active.

### Application-Level Retry

All write commands use exponential backoff matching the C# QueueManager:

| Retry | Backoff |
|-------|---------|
| 1 | 500ms |
| 2 | 1000ms |
| 3 | 2000ms |
| 4 | 4000ms |

On `STATUS_SECURITY_BLOCK`, the client auto-re-authenticates and retries
without counting it as a failure.

---

## Key Timing Reference

| Operation | Timing | Notes |
|-----------|--------|-------|
| ClearBuffer | At connect | 64 bytes of 0xFF |
| DeviceInfo | 300ms delay, 1s timeout | First command |
| SupportedDevices | 300ms delay, 1s timeout | After DeviceInfo |
| SupportedCommands | 300ms delay, 1s timeout | After DeviceInfo |
| SystemInfo | 300ms delay, 1s timeout | If supported |
| VersionInfo | 400ms delay, 2.5s timeout | If supported |
| SerialNumber | 400ms delay, 2.5s timeout | If supported |
| VerifySecurity | 400ms delay, 2.5s timeout | If SW version > 75 |
| ReadWriteData | 80ms delay, 1s timeout | Main polling command |
| Polling interval | 100ms | Between ReadWriteData calls |
| Periodic timeout | 3000ms | Max wait for periodic read |
| Calibrate | 400ms delay, 2.5s timeout | Poll every 4 seconds |
| Retry backoff | 500ms * 2^n | n = retry attempt (1-4) |
| Queue shutdown poll | 200ms | Wait for queue to drain |
| Fatality mismatch window | 10 seconds | Sample mismatch count |
| USB item loss decay | 2 seconds | Per decrement |

---

**Last Updated:** 2026-02-11
**Source:** `FitPro1Console.cs`, `QueueManager.cs`, `FitProCommunicationAdapter.cs`,
`PreparingWorkoutViewModel.cs`, `FitnessConsoleBase.cs`, `DeviceExtensions.cs`
