# AGENTS.md

This file provides guidance to coding agents (and human contributors) working with this repository.

## Project Overview

Hyperborea is an Android app that bridges ICON Fitness equipment (NordicTrack, ProForm, FreeMotion, Schwinn, etc.) to Zwift and other fitness platforms via BLE and WiFi. It reads exercise data through the FitPro protocol over USB serial and rebroadcasts it as standard fitness protocols (BLE FTMS and WiFi TCP), and forwards resistance/incline targets back to the equipment.

It runs on the equipment's own Android console. It is an independent project, not affiliated with ICON Health & Fitness / iFit / Zwift — see [README.md](README.md) for the full disclaimer.

- **Primary target device**: NordicTrack S22i console — Android 7.1.2 (API 25), 1920x1080 landscape, 22"
- **Hardware compatibility**: Any ICON Fitness device using the FitPro protocol over USB (vendor ID `0x213C`) — bikes, treadmills, ellipticals
- **Stack**: Kotlin, Jetpack Compose + Material3, Hilt (KSP), Gradle — check `gradle/libs.versions.toml` and the `build.gradle.kts` files for current versions

## Build Commands

```bash
./gradlew :app:assembleStandardDebug    # Build the debug APK
./gradlew :core:build                   # Build only the core module
./gradlew lint                          # Run lint checks
./gradlew test                          # Run all unit tests
./gradlew :core:test                    # Run tests for a single module
./gradlew prepareRelease                # Full release pipeline: clean, lint, test, build, package (needs a release keystore)
```

Always use the `standard` product flavor. Release builds use `release.jks` if present, otherwise fall back to the debug signing key (see `local.properties.example`).

## Device Commands

The console typically connects over WiFi (ADB over TCP). After rebooting, use `adb wait-for-device` to block until ADB reconnects, then poll `getprop sys.boot_completed` until it returns `1` (wait-for-device returns before boot finishes).

## Architecture

### Module Dependency Graph

```
:app  →  :core  ←  :hardware:fitpro
  ↓                 :broadcast:ftms
  ↓                 :broadcast:wifi
  ↓                 :ecosystem:ifit
  ↓                 :sensor:hrm
  └── wires everything together via Hilt
```

All feature modules depend only on `:core`, never on each other. The `:app` module is the only composition root.

### Module Roles

- **`:core`** — Pure Kotlin module (no Android dependencies). Domain interfaces, data types, orchestration logic. Uses `kotlinx-coroutines-core` for Flow/StateFlow.
- **`:hardware:fitpro`** — Android library. The hardware adapter for the ICON Fitness FitPro protocol over USB serial (115200 baud).
- **`:broadcast:ftms`** — Android library. Broadcast adapter: a BLE GATT server advertising FTMS (service UUID `0x1826`).
- **`:broadcast:wifi`** — Android library. Broadcast adapter: a WiFi TCP server for fitness apps.
- **`:ecosystem:ifit`** — Android library. Coexistence with the stock iFit apps (stop the standalone app, disable the crash-looping update receiver) while Hyperborea owns the USB device.
- **`:sensor:hrm`** — Android library. Optional external BLE heart-rate monitor support.
- **`:app`** — Application module. Hilt DI wiring, Compose UI, foreground service, and the Android platform implementations of the `:core` interfaces.

### Design Patterns

When modifying or extending the codebase, investigate the existing code to understand current interfaces, types, and conventions before making changes. The patterns below describe the architectural intent:

- **Pure-Kotlin core with dependency inversion.** All interfaces live in `:core` with zero Android dependencies. Feature modules depend on `:core` abstractions, never on each other.
- **One hardware source, many broadcast sinks.** Single hardware adapter instance. Broadcast adapters provided as a `Set` via Hilt `@IntoSet` multibinding. Adding a new broadcast protocol means: new module implementing the broadcast interface + one `@IntoSet` binding.
- **Push-based data piping.** Broadcast adapters receive a `Flow` of exercise data rather than holding a reference to the hardware adapter. The orchestrator decides what to pipe and when.
- **Self-describing capability checks.** Each broadcast adapter declares its own system requirements. The orchestrator selectively starts only adapters whose requirements are met.
- **Declarative prerequisites.** Adapters declare what must be true before they can operate (e.g., a service must be stopped). The app layer fulfills those prerequisites.
- **Read/write split for system interaction.** Passive observation (monitoring) and active mutation (control) are separate interfaces with different permissions and failure modes.
- **Sealed types for exhaustive handling.** Commands and states use sealed interfaces so the compiler enforces exhaustive `when` handling.
- **Composition root in `:app`.** Only `:app` knows about concrete implementations. Feature modules never import each other. DI modules use `@Binds` for interface binding.
- **Logging convention.** Use a `TAG` companion constant per class. Inject the logger interface from `:core`. Logcat tags are prefixed `Hyperborea.`. Filter with `adb logcat -d | grep "Hyperborea\."` — the `-s` flag requires exact tag names (no wildcards on API 25).

### Data Flow

```
Hardware adapter (USB serial)
  → exercise data flow
    → orchestrator pipes to each broadcast adapter
      → BLE GATT → Zwift
      → TCP      → fitness apps

Zwift/fitness app sends resistance/incline target
  → broadcast adapter incoming commands flow
    → orchestrator forwards to hardware adapter
      → encodes and sends over USB serial
```

## API Level Constraints

Target is API 25 (Android 7.1.2). Key restrictions:
- Use `startService()` + `startForeground()` — **not** `startForegroundService()` (API 26+)
- No dynamic color (Material You requires API 31+)
- Use `@Suppress("DEPRECATION")` for deprecated pre-API 26 methods as needed
- BLE permissions: `BLUETOOTH` + `BLUETOOTH_ADMIN` + `ACCESS_FINE_LOCATION` (pre-API 31 model)

## Configuration & Secrets

- **`local.properties`** (gitignored) holds `sdk.dir`, the optional release-signing passwords, and the optional `server.url` / `r2.base.url` for the self-update and support-diagnostics features. See `local.properties.example`. None of these are required to build the debug APK or run the tests.
- **Certificate pinning**: not configured by default — there is no fixed server domain. A fork hosting its own infrastructure can add a `<domain-config>` with primary + backup pins in `app/src/main/res/xml/network_security_config.xml`.

## Lint

AGP lint can analyze stale merged manifests if source files changed after a prior build. When modifying `AndroidManifest.xml` or resources, run `./gradlew clean lint` instead of just `lint`.
