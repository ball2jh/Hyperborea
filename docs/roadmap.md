# Feature Roadmap

Competitive analysis against [qdomyos-zwift](https://github.com/cagnulein/qdomyos-zwift) (141+ device bridge, Qt/C++, cross-platform) and [thud](https://github.com/a-vikulin/thud) (iFIT 2.0 treadmill HUD, Kotlin, gRPC).

## What We Have That They Don't

- **Native on-console execution** — runs directly on the ICON Fitness console, no external phone/PC needed
- **Direct FitPro USB protocol** — native hardware communication vs qdomyos-zwift's ADB scraping or thud's gRPC dependency on GlassOS
- **Magnetic resistance control + ERG mode** — direct `SetResistance` and `SetTargetPower` commands over USB; treadmills (thud) have no equivalent
- **Full bidirectional control** — resistance, incline, target speed, target power (4 command types)
- **Platform-signed system app** — privileged permissions for USB, ecosystem management, OTA blocking
- **Ecosystem coexistence** — graceful iFit handoff (force-stop on start, clean release on stop)
- **Wahoo DirCon protocol** — our `:broadcast:wifi` module already implements the full DirCon wire protocol (same 6-byte header, message types, mDNS `_wahoo-fitness-tnp._tcp`, port 36866, FTMS characteristics). Both competitors implement this separately; we ship it out of the box.

## What They Have That We Don't

### Priority 1: FIT File Export

**Impact**: Critical — workout data is currently trapped in Room database
**Effort**: Medium
**Both competitors**: qdomyos-zwift (Garmin FIT SDK), thud (custom FIT writer)

We already record detailed per-second `WorkoutSample` data (power, cadence, speed, HR, resistance, incline) and compute NP/IF/TSS in `RideRecorder`. The data just has no way out.

**What's needed**:
- FIT file encoder (Garmin FIT SDK or lightweight custom encoder)
- Map `WorkoutSample` → FIT record messages (timestamp, power, cadence, speed, HR, distance)
- Map `RideSummary` → FIT session/lap messages
- Export trigger in UI (share intent or file save)
- Auto-export on ride completion (optional setting)

**FIT message structure**:
```
FileId (type=activity, manufacturer, product, serial)
  → Session (sport=cycling, start_time, total_elapsed_time, total_distance, avg/max power/hr/cadence)
    → Lap (one lap per session for now)
      → Record[] (one per second: timestamp, power, cadence, speed, heart_rate, distance, altitude)
```

**References**:
- qdomyos-zwift FIT implementation: `src/fit-sdk/`
- Garmin FIT SDK: https://developer.garmin.com/fit/
- FIT file format is binary with CRC — consider a lightweight Kotlin encoder in `:core` (no Android dependencies) to keep it testable

---

### Priority 2: External BLE Heart Rate Monitor

**Impact**: High — `ExerciseData.heartRate` is always null without an external sensor
**Effort**: Medium
**Both competitors**: qdomyos-zwift (BLE + ANT+), thud (BLE HR + DFA alpha1)

The bike console has BLE hardware (BCM4345c0). Almost every Zwift user wears a chest strap or optical sensor. Without HR, users lose HR zones, cardiac drift analysis, and calorie accuracy.

**What's needed**:
- New module: `:sensor:ble` (or add to `:app`)
- BLE scan for Heart Rate Service (UUID `0x180D`)
- Subscribe to Heart Rate Measurement characteristic (`0x2A37`)
- Parse HR value (flags byte + uint8/uint16 HR + optional RR intervals)
- Pipe HR into `ExerciseData.heartRate` via the orchestrator
- UI: sensor pairing/selection in settings, connection status indicator
- Persist paired sensor address for auto-reconnect

**HR characteristic format** (per Bluetooth SIG):
```
Byte 0: Flags
  Bit 0: 0 = uint8 HR, 1 = uint16 HR
  Bit 1: Sensor contact status supported
  Bit 2: Sensor contact detected
  Bit 3: Energy expended present
  Bit 4: RR-interval present
Byte 1+: Heart rate value (uint8 or uint16)
Then: optional energy expended (uint16), optional RR intervals (uint16 each, 1/1024s resolution)
```

**Design considerations**:
- Keep BLE central (HR scan) separate from BLE peripheral (FTMS broadcast) — both can run concurrently on the same adapter
- RR intervals enable HRV/DFA alpha1 in the future (thud has this)
- Could also support BLE cadence/speed sensors (UUID `0x1816`) for devices without built-in sensors

---

### Priority 3: Strava / Garmin Connect Upload

**Impact**: High — users expect rides to appear in their training platform automatically
**Effort**: Medium (depends on FIT export being done first)
**Both competitors**: qdomyos-zwift (Strava + Garmin Connect + Intervals.icu), thud (Garmin FIT export)

**Strava**:
- OAuth2 authorization (redirect URI or manual token entry given console limitations)
- `POST /api/v3/uploads` with FIT file, `data_type=fit`
- Poll upload status until processing complete
- Store refresh token in encrypted prefs (already have encrypted prefs infrastructure)

**Garmin Connect**:
- OAuth 1.0a (more complex than Strava)
- Upload via Garmin Connect API
- qdomyos-zwift has a working implementation to reference

**What's needed**:
- FIT export (Priority 1) as prerequisite
- OAuth flow — may need a WebView or external browser intent
- Upload service with retry logic (use existing `RetryPolicy`)
- Settings: account linking, auto-upload toggle
- Upload status in ride history UI

**API 25 constraint**: No Chrome Custom Tabs guarantee — may need embedded WebView for OAuth. Alternatively, support manual token paste for power users.

---

## Future Considerations (Lower Priority)

### Power/Speed Calibration
Both competitors offer calibration curves. qdomyos-zwift uses per-device lookup tables; thud uses polynomial regression with Stryd foot pod data. Useful for accuracy but not blocking core functionality.

### Structured Workout Import (ZWO)
Zwift handles structured workouts server-side and sends resistance/power targets via FTMS control point — which we already support. Local ZWO import would enable standalone structured training without Zwift.

### Additional BLE Sensors
Beyond HR, could support:
- Cadence/speed sensors (UUID `0x1816`) — for devices without built-in sensors
- Power meters (UUID `0x1818`) — for cross-referencing with FitPro power
- Stryd running power (for treadmill support)

### Multi-Device Profiles
As we expand beyond the S22i to other ICON Fitness equipment, device-specific calibration profiles and capability sets will become important. `DeviceCapabilities` already exists but may need expansion.

### MQTT / IoT Integration
qdomyos-zwift publishes metrics to MQTT brokers for smart home integration. Niche but interesting for home gym automation (fans, lights based on HR zone).

### Intervals.icu Integration
Direct API upload as an alternative to Strava/Garmin. Popular with data-focused athletes. Simple REST API with API key auth (no OAuth needed).
