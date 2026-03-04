# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Hyperborea is an Android app that bridges a NordicTrack S22i stationary bike to Zwift and other fitness platforms via BLE and WiFi. It reads bike data through the proprietary FitPro protocol over USB serial and rebroadcasts it as standard fitness protocols (BLE FTMS and Wahoo DIRCON).

- **Target device**: NordicTrack S22i console — Android 7.1.2 (API 25), 1920x1080 landscape, 22"
- **Stack**: Kotlin, Jetpack Compose + Material3, Hilt (KSP), Gradle — check `libs.versions.toml` and `build.gradle.kts` for current versions

## Build Commands

```bash
./gradlew assembleDebug          # Build debug APK
./gradlew :app:assembleDebug     # Build only the app module
./gradlew :core:build            # Build only the core module
./gradlew lint                   # Run lint checks
./gradlew test                   # Run all unit tests
./gradlew :core:test             # Run tests for a single module
```

## Architecture

### Module Dependency Graph

```
:app  →  :core  ←  :hardware:fitpro
  ↓                 :broadcast:ftms
  ↓                 :broadcast:dircon
  └── all modules
```

All feature modules depend only on `:core`. The `:app` module wires everything together via Hilt.

### Module Roles

- **`:core`** — Pure Kotlin module (no Android dependencies). Defines domain interfaces and data types. Uses `kotlinx-coroutines-core` for Flow/StateFlow.
- **`:hardware:fitpro`** — Android library. Implements the hardware adapter for the NordicTrack FitPro protocol over USB serial (115200 baud).
- **`:broadcast:ftms`** — Android library. Implements a broadcast adapter as a BLE GATT server advertising FTMS (UUID 0x1826).
- **`:broadcast:dircon`** — Android library. Implements a broadcast adapter as a TCP server for the Wahoo DIRCON protocol.
- **`:app`** — Application module. Contains Hilt DI wiring, Compose UI, foreground service, and Android platform implementations.

### Design Patterns

When modifying or extending the codebase, investigate the existing code to understand current interfaces, types, and conventions before making changes. The patterns below describe the architectural intent:

- **Pure-Kotlin core with dependency inversion.** All interfaces live in `:core` with zero Android dependencies. Feature modules depend on `:core` abstractions, never on each other.
- **One hardware source, many broadcast sinks.** Single hardware adapter instance. Broadcast adapters provided as a `Set` via Hilt `@IntoSet` multibinding. Adding a new broadcast protocol means: new module implementing the broadcast interface + one `@IntoSet` binding.
- **Push-based data piping.** Broadcast adapters receive a `Flow` of bike data rather than holding a reference to the hardware adapter. The orchestrator decides what to pipe and when.
- **Self-describing capability checks.** Each broadcast adapter declares its own system requirements. The orchestrator selectively starts only adapters whose requirements are met.
- **Declarative prerequisites.** Adapters declare what must be true before they can operate (e.g., a service must be stopped). The app layer fulfills those prerequisites.
- **Read/write split for system interaction.** Passive observation (monitoring) and active mutation (control) are separate interfaces with different permissions and failure modes.
- **Sealed types for exhaustive handling.** Commands and states use sealed interfaces so the compiler enforces exhaustive `when` handling.
- **Composition root in `:app`.** Only `:app` knows about concrete implementations. Feature modules never import each other. DI modules use `@Binds` for interface binding.
- **Logging convention.** Use a `TAG` companion constant per class. Inject the logger interface from `:core`. Logcat tags are prefixed `Hyperborea.`, filterable with `adb logcat -s "Hyperborea.*"`.

### Data Flow

```
Hardware adapter (USB serial)
  → bike data flow
    → orchestrator pipes to each broadcast adapter
      → BLE GATT → Zwift
      → TCP      → Wahoo apps

Zwift/Wahoo sends resistance/incline target
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

## Lint

AGP lint can analyze stale merged manifests if source files changed after a prior build. When modifying `AndroidManifest.xml` or resources, run `./gradlew clean lint` instead of just `lint`.
