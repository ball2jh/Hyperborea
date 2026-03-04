# Zwift Bridge Test Report

Date: 2026-02-11

## Automated verification completed

### Host unit/integration suite

- Command: `make test-core`
- Result: pass
- Summary: `206 total, 206 passed, 0 failed`
- Includes bridge-specific suites:
  - `bridge-ftms`
  - `bridge-dircon`
  - `bridge-telemetry`

### Cross-compile outputs

- Command: `make bridge`
- Result: pass
- Output artifact: `src/trainer_stack/build/trainer-bridge`

### Hardware smoke run (S22i tablet)

- Forced USB ownership away from iFit:
  - `adb shell am force-stop com.ifit.standalone`
- Deployed and ran one-cycle bridge:
  - `adb push build/trainer-bridge /data/local/tmp/trainer-bridge`
  - `adb shell /data/local/tmp/trainer-bridge --once`
- Result: pass
  - Session connected, telemetry frames emitted:
    - `ftms.indoor_bike_data=4400000000003C00`
    - `cps.measurement=00000000`

### Control-point write smoke runs

- Resistance write test:
  - `adb shell "printf '045000\n' | /data/local/tmp/trainer-bridge --once"`
  - Response: `ftms.control_point_response=800401` (success)
- ERG/power write test:
  - `adb shell "printf '05C800\n' | /data/local/tmp/trainer-bridge --once"`
  - Response: `ftms.control_point_response=800504` (operation failed)
  - Expected on this console build, because `set_watts_mode` is unsupported.

## Manual Zwift device pairing checklist

Run this on one non-tablet Zwift client (PC/Mac/phone/Apple TV) once BLE transport wrapper is attached around bridge core:

1. Pair bridge as:
   - `Power Source`
   - `Cadence`
   - `Resistance`
2. Start free ride.
3. Confirm resistance responds to grade changes.
4. Start workout in ERG mode.
5. Confirm bridge returns success/failure responses correctly per supported capabilities.

## Current status

- Bridge core, command mapping, and telemetry encoding are verified.
- Dircon packet codec is verified by tests and ready for optional network layer integration.
- Full Zwift UI pairing requires BLE peripheral transport integration on the tablet app layer.
