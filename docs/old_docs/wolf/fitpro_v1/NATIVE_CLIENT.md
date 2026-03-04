# FitPro v1 Native Client

> C++17 implementation of the FitPro v1 protocol for direct MCU communication.
> Source: `src/trainer_stack/`

---

## Overview

The native client is a standalone C++ program that communicates with the Wolf MCU
over USB using the FitPro v1 protocol. It provides a CLI for device
identification, live sensor monitoring, and actuator control (resistance, incline,
fan, etc.), plus a library API for programmatic use and an async command queue
(`FitProQueue`) for integration into threaded applications.

The implementation was built from the protocol specification reverse-engineered
from `Sindarin.FitPro1.Core` (see [COMMANDS.md](COMMANDS.md),
[DATA_ENCODING.md](DATA_ENCODING.md), etc.) and validated against real hardware.

---

## Architecture

```
┌─────────────────────────────────────────────────────┐
│  main.cpp              CLI entry point              │
├─────────────────────────────────────────────────────┤
│  fitpro_queue.cpp      Async command queue           │
│    FitProQueue          Worker thread + state mgmt  │
│    set_field()          Fire-and-forget writes       │
│    set_field_sync()     Blocking writes              │
│    get_state()          Thread-safe state snapshot   │
├─────────────────────────────────────────────────────┤
│  fitpro_console.cpp    Synchronous console API       │
│    console_init()      Startup sequence (10 steps)  │
│    console_poll()      Periodic field read           │
│    console_set_*()     Control commands              │
├─────────────────────────────────────────────────────┤
│  fitpro_fields.cpp     BitField encoding/decoding   │
│    fields_build_*()    Bitmask + ReadWriteData       │
│    encode/decode_*()   Value converters              │
├─────────────────────────────────────────────────────┤
│  fitpro_packet.cpp     Packet framing               │
│    packet_build()      Header + checksum             │
│    packet_send_recv()  Send/read with retry          │
├─────────────────────────────────────────────────────┤
│  fitpro_security.cpp   Security hash + unlock        │
├─────────────────────────────────────────────────────┤
│  fitpro_usb.cpp        USB transport (Linux ioctl)  │
│    usb_open/close()    Device claim/release          │
│    usb_read/write()    Bulk transfers                │
│    usb_find_device()   VID/PID enumeration           │
└─────────────────────────────────────────────────────┘
```

Data flows top-down: CLI parses commands, calls console API, which builds
BitField packets, frames them with headers/checksums, and sends via USB bulk
transfers.

### Synchronous Console API

The console layer (`fitpro_console.cpp`) is synchronous and single-threaded.
Every call to `console_poll()` or `console_set_*()` blocks until the USB
transaction completes. The MCU sees identical packets regardless of whether the
caller is synchronous or asynchronous — the protocol is inherently
request-response at the USB level.

The CLI (`main.cpp`) uses this API directly for simple command-line operations.

### Async Command Queue (`FitProQueue`)

For integration into threaded applications (e.g., Kotlin/JNI workout app), the
`FitProQueue` class (`fitpro_queue.cpp`) wraps the synchronous console API in a
single-worker-thread command queue. This matches the C# app's `QueueManager`
pattern:

```
┌─────────────────────────────────────────────────────────────────┐
│  Application Layer (workout UI, settings, etc.)                 │
│    queue.set_resistance(12)  — fire-and-forget                  │
│    queue.set_field_sync()    — blocking write                   │
│    queue.get_state()         — thread-safe state snapshot       │
│    queue.on_state_change()   — register callbacks               │
├─────────────────────────────────────────────────────────────────┤
│  FitProQueue (C++ class, include/fitpro_queue.h)                │
│    std::deque<QueueItem> with mutex + condvar                   │
│    Worker thread: sole owner of USB fd + ConsoleState           │
│      1. Dequeue writes first (priority over reads)              │
│      2. Batch writes up to 56 bytes payload                     │
│      3. Poll when queue empty (configurable interval)           │
│      4. Adaptive delay (C# DynamicDelay: 10ms step, 150ms max) │
│      5. Snapshot state → fire callbacks                         │
├─────────────────────────────────────────────────────────────────┤
│  fitpro_console.cpp  (unchanged — called only by worker thread) │
│  fitpro_fields.cpp   (unchanged — pure encoding/decoding)       │
│  fitpro_packet.cpp   (unchanged — packet framing)               │
│  fitpro_security.cpp (unchanged — hash + unlock)                │
│  fitpro_usb.cpp      (unchanged — USB bulk I/O)                 │
└─────────────────────────────────────────────────────────────────┘
```

**Key design invariant**: Only the worker thread touches the USB fd and internal
`ConsoleState`. Callers interact through the thread-safe queue and state snapshots.

**Thread safety** uses three independent mutexes (never nested):
- `queue_mutex_` — protects the write queue (callers enqueue, worker dequeues)
- `state_mutex_` — protects the public state snapshot (worker publishes, callers read)
- `cb_mutex_` — protects the callback list (callers register/remove, worker fires)

**Features implemented** (matching C# `QueueManager`):
- **Write priority**: Writes are dequeued before any poll occurs
- **Deduplication**: At enqueue time, a new write for the same field replaces the pending one
- **Adaptive delay**: 10ms..150ms inter-command delay, increases on failure, decreases after 100 consecutive successes
- **Blocking writes**: `set_field_sync()` blocks the caller via condvar until the worker processes the write
- **Queue drain on shutdown**: Waits up to 5s for pending writes to complete before disconnect
- **State change callbacks**: Registered functions are invoked on the worker thread after each poll/write

**Not yet implemented**:
- Mismatch recovery (5s response cache for out-of-order matches)
- Auto-reconnect on USB drop

See [CONNECTION.md](CONNECTION.md) for full timing reference and error recovery
details.

---

## Observed MCU Behaviors

These behaviors were discovered through hardware testing and are not documented
in the original Sindarin source. Understanding them is critical for writing
correct client code.

### Device Type Mismatch

The S22i DeviceInfo response reports `device_type=0x08` (SpinBike), not `0x07`
(FitnessBike) as the Sindarin C# code expects. The DeviceInfo request must
target `DEV_MAIN` (0x02), and the MCU's response byte[0] becomes the device ID
for all subsequent commands.

### Manufacturer Field

The manufacturer field in DeviceInfo may be 0 (None) even on genuine
NordicTrack hardware. Don't assert a specific manufacturer value.

### Buffer Clearing

On connection, the USB buffer may contain stale data from previous sessions.
The clearing protocol is:
1. Send 64 bytes of `0xFF`
2. Read response
3. If response[0] == 0xFF, increment consecutive counter
4. Repeat until 2 consecutive `0xFF` responses
5. Discard any response where byte[0] != 0xFF (stale packet)

### Resistance Readback: Idle vs Running Mode

**Field 2 (Resistance) reports different values depending on workout mode:**

- **Idle mode**: Reports the *target/set* resistance level (e.g., "6" if last set to 6)
- **Running mode**: Reports the *actual sensed* resistance, which requires the
  flywheel to be spinning. With no pedaling, this reads 0.0

This means resistance readback verification requires someone physically pedaling
the bike. The `set_resistance` command is always accepted (STATUS_DONE) in both
modes — only the poll feedback differs.

### Workout Mode State Machine

The MCU enforces a strict mode transition order. Not all transitions are valid:

```
Idle ──► Running ──► Pause ──► Results ──► Idle
                       │                     ▲
                       └─────── ✗ ───────────┘
                       (Pause → Idle is REJECTED)
```

To return to Idle from Pause, you must go through Results first:
```c
set_mode(MODE_RESULTS);   // Pause → Results
usleep(200ms);
set_mode(MODE_IDLE);      // Results → Idle
```

### Resistance in Idle Mode

The MCU accepts resistance writes in Idle mode (returns STATUS_DONE) but does
not physically apply them to the magnetic brake. The target is stored internally
and takes effect when entering Running mode.

### Always-Approved Commands

Some commands are implicitly available and may not appear in the
SupportedCommands response:

- `CMD_DEVICE_INFO` (0x81)
- `CMD_SUPPORTED_DEVICES` (0x80)
- `CMD_SUPPORTED_COMMANDS` (0x88)
- `CMD_SYSTEM_INFO` (0x82)
- `CMD_READ_WRITE_DATA` (0x02)
- `CMD_VERIFY_SECURITY` (0x90)
- `CMD_CALIBRATE` (0x06)
- `CMD_DISCONNECT` (0x05)

### Calibration

Incline calibration (`CMD_CALIBRATE` to `DEV_GRADE`) physically drives the
incline through its full range to find endpoints. This takes 1-2 minutes and
involves audible motor movement. The MCU reports `STATUS_IN_PROGRESS` during
calibration and `STATUS_DONE` when complete.

### IdleModeLockout (Workout Start Gate)

Non-belt machines (SpinBike, FitnessBike, Rower, Elliptical) require an
explicit "unlock" before transitioning from Idle to Running mode. The
mechanism uses field 95 (IdleModeLockout):

1. **During init**: `console_init()` writes field 95 = true (locked) and
   field 108 = true (RequireStartRequested), matching the C# app's init
   sequence in `FitPro1Console.cs:218-222`
2. **Before starting workout**: Call `console_set_idle_lockout(fd, &state, false)`
   to unlock. This matches `PreparingWorkoutViewModel.cs:444`
3. **After workout ends**: Call `console_set_idle_lockout(fd, &state, true)`
   to re-lock. This matches `FitnessConsoleBase.cs:253-256`

Without step 2, the MCU returns `STATUS_FAILED` for Idle→Running mode writes.
The retry mechanism (4 attempts with exponential backoff) cannot overcome
this — it's a deliberate MCU gate, not a transient failure.

**Belt-based machines** (Treadmill, InclineTrainer) use different safety
mechanisms and may not require IdleModeLockout.

### Security

Devices with `sw_version > 75` require security verification. The hash
algorithm uses the device serial bytes and a constant key to produce a 4-byte
response. Without unlocking, write operations return `STATUS_SECURITY_BLOCK`.
The client automatically re-authenticates if a security block is detected
during polling.

---

## Initialization Sequence

The `console_init()` function performs these steps in order:

| Step | Command | Purpose |
|------|---------|---------|
| 1 | Buffer clear | Flush stale USB data |
| 2 | DeviceInfo | Get device type, supported fields bitmask |
| 3 | SupportedDevices | Learn available sub-devices (grade, resistance, etc.) |
| 4 | SupportedCommands | Learn available commands |
| 5 | SystemInfo | Model number, part number, CPU frequency |
| 6 | VersionInfo | Firmware version, BLE version |
| 7 | SerialNumber | Full serial string (e.g., "425516-NN83Z128237") |
| 8 | VerifySecurity | Unlock device for write operations |
| 9 | Init mode config | Write RequireStartRequested + IdleModeLockout (see below) |
| 10 | Read startup fields | Get limits (max resistance, grade range, etc.) |

After init, the client enters a polling loop using `console_poll()` which reads
up to 30 periodic fields per cycle at 100ms intervals.

---

## Console API

All functions return 0 on success, -1 on error. The `ConsoleState` struct
accumulates device info, capabilities, and live sensor data across calls.

### Synchronous API (Direct USB)

Used by the CLI and internally by `FitProQueue`'s worker thread.

```cpp
int fd = usb_open(path);              // Open USB device
console_init(fd, &state);             // Full startup sequence
console_poll(fd, &state);             // Read periodic fields
console_disconnect(fd, &state);       // Send disconnect
usb_close(fd);                        // Release USB
```

```cpp
console_set_resistance(fd, &state, 12);       // 1..max_resistance_level
console_set_incline(fd, &state, 5.0);         // min_grade..max_grade (%)
console_set_speed(fd, &state, 8.0);           // min_kph..max_kph
console_set_idle_lockout(fd, &state, false);  // Unlock before starting workout
console_set_workout_mode(fd, &state, MODE_RUNNING);
console_set_fan(fd, &state, 50);              // 0..100
console_set_volume(fd, &state, 75);           // 0..100
console_set_weight(fd, &state, 80.0);         // kg
console_set_age(fd, &state, 30);              // years
console_set_field(fd, &state, field_id, val); // Generic write
console_set_fields_batch(fd, &state, ids, vals, count); // Batch write
```

### Async API (`FitProQueue`)

Thread-safe interface for multi-threaded applications.

```cpp
FitProQueue queue;
queue.start("/dev/bus/usb/001/003");    // Blocks until init completes

// Fire-and-forget writes
queue.set_resistance(12);
queue.set_fan(50);
queue.set_idle_lockout(false);
queue.set_workout_mode(MODE_RUNNING);

// Blocking write (waits for worker to process)
int rc = queue.set_field_sync(8, 50.0, 3000);  // 3s timeout

// Thread-safe state snapshot
ConsoleState state = queue.get_state();

// State change callbacks (fired on worker thread)
int handle = queue.on_state_change([](const ConsoleState &s) {
    printf("resistance: %.1f\n", s.resistance);
});
queue.remove_callback(handle);

queue.stop();                           // Drains queue, disconnects
```

### Diagnostics

```cpp
console_calibrate(fd, &state);                  // Incline calibration
console_set_tach_override(fd, &state, true, 60, 2000, 1000); // Simulate pedaling
console_set_watts_mode(fd, &state, true);       // Constant watts mode
console_reset(fd, &state);                      // Reboot MCU
```

---

## Build

Requires Android NDK r29 for cross-compilation to ARM64.

```bash
cd src/trainer_stack

make                # Cross-compile for device (aarch64-linux-android21)
make deploy         # Push to /data/local/tmp/trainer-cli on device
make test           # Run host unit tests (macOS, no device needed)
make test-device    # Cross-compile, deploy, run full test suite on device
make clean          # Remove build artifacts
```

The host test suite uses a mock USB layer; the device test suite uses real
hardware. See [TESTING.md](../../../src/trainer_stack/TESTING.md) for details.

---

## S22i Observed Values

These are values observed from the NordicTrack S22i (serial NN73Z115616):

| Property | Value |
|----------|-------|
| Device type | 0x08 (SpinBike) |
| SW version | 83 |
| HW version | 1 |
| Serial | 425516-NN83Z128237 |
| Manufacturer | 0 (None) |
| Max resistance | 24 levels |
| Grade range | -10% to +20% |
| Supported fields | 79 |
| Supported devices | 13 |
| Supported commands | 8 |
| Master lib version | 83.245 |
| BLE | Not available |
| Model | 2117 |
| Part number | 392570 |
| CPU frequency | 48 MHz |

---

**Last Updated:** 2026-02-10
**Source:** `src/trainer_stack/` (native C++17 implementation)
**Protocol Spec:** [README.md](README.md) | [COMMANDS.md](COMMANDS.md) | [CONNECTION.md](CONNECTION.md)
