# AGENTS.md

This file provides guidance to coding agents (and human contributors) working with this repository.

## Project Overview

Hyperborea is an Android app that bridges ICON Fitness equipment (NordicTrack, ProForm, FreeMotion, Schwinn, etc.) to Zwift and other fitness platforms via BLE and WiFi. It reads exercise data through the FitPro protocol over USB serial and rebroadcasts it as standard fitness protocols (BLE FTMS and WiFi TCP), and forwards resistance/incline targets back to the equipment.

It runs on the equipment's own Android console. It is an independent project, not affiliated with ICON Health & Fitness / iFit / Zwift — see [README.md](README.md) for the full disclaimer.

- **Primary target device**: NordicTrack S22i console, 1920x1080 landscape, 22" — but ICON consoles (bikes, treadmills, ellipticals) ship a **wide range of Android versions** in the field: the oldest supported is Android 5.1 (`minSdk = 22`), 6.x and 7.1.2 are common, and Android 9+ (API 28+) is out there too. **The app must behave correctly across its whole declared range, `minSdk = 22` … `compileSdk`/`targetSdk = 36`** — do not assume any single Android version in code, manifests, or comments (see "API Level Constraints" below).
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

## Publishing a release

GitHub Releases is the source of truth for what the fleet auto-updates to. The
website's `/api/device/manifest` endpoint reads `releases/latest` from
`ball2jh/Hyperborea` directly — no admin form upload, no R2 push, no "set
current" click. Bikes poll the manifest every ≤6 h and the in-app "Update Now"
button triggers it immediately.

The full flow lives in [`tools/publish-release.sh`](tools/publish-release.sh):

1. Bump `appVersionName` in `gradle.properties`.
2. Add a `## [X.Y.Z] - YYYY-MM-DD` section to `CHANGELOG.md` (under
   `## [Unreleased]`).
3. `git commit -m "release: X.Y.Z"`.
4. `tools/publish-release.sh` — validates pre-conditions, runs
   `./gradlew prepareRelease`, pushes main, tags `vX.Y.Z`, and `gh release
   create`s with the APK + ZIP and the extracted changelog as release notes.

The script has a `--dry-run` mode that prints exactly what would happen
(including the assembled release notes) without pushing. Coding agents:
the [`publish-release`](.claude/skills/publish-release/SKILL.md) skill walks
through the whole flow including how to assemble the CHANGELOG entry from
git log.

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
- **Logging convention.** Use a `TAG` companion constant per class. Inject the logger interface from `:core`. Logcat tags are prefixed `Hyperborea.`. Filter with `adb logcat -d | grep "Hyperborea\."` — the `-s` flag requires exact tag names (no tag wildcards). For diagnosing crashes, capture `adb logcat -d -b all -v threadtime` (the `crash`/`system` buffers carry the OS-side reason, e.g. `NotificationService: No Channel found …`, which a `Hyperborea.`-only filter would miss).

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

`minSdk = 22` (Android 5.1) … `compileSdk`/`targetSdk = 36`. The app runs on consoles anywhere in
that range, so every Android-version-sensitive API must be handled across it — use
`Build.VERSION.SDK_INT` guards (the codebase prefers raw framework APIs + guards over `*Compat`
wrappers; `androidx.core` is only available transitively). Notes:

- **Foreground service**: starts from background contexts (boot receiver, `Application.onCreate()`)
  go through `Context.startHyperboreaService()` → `startForegroundService()` on API 26+. The
  service creates a `NotificationChannel` (API 26+) before `startForeground`, builds the
  notification with `Notification.Builder(ctx, CHANNEL_ID)` on API 26+, and passes
  `FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE` to the 3-arg `startForeground` on API 29+. The
  manifest declares `foregroundServiceType="connectedDevice"` (mandatory on API 34+),
  `FOREGROUND_SERVICE_CONNECTED_DEVICE`, `CHANGE_NETWORK_STATE` (authorises that FGS type), and
  `POST_NOTIFICATIONS` (API 33+ runtime perm, requested in `MainActivity`).
- **No Direct Boot**: nothing is `directBootAware` — the Compose/Hilt/Room/SharedPreferences stack
  isn't safe to run while the user is locked. A "UI before unlock" feature would need a separate
  direct-boot-aware activity using `createDeviceProtectedStorageContext()`.
- **Bluetooth**: dual permission model. API 31+ runtime perms `BLUETOOTH_SCAN`/`ADVERTISE`/`CONNECT`
  (requested in `MainActivity`); legacy `BLUETOOTH`/`BLUETOOTH_ADMIN`/`ACCESS_FINE_LOCATION` capped
  at `maxSdkVersion="30"`; `BLUETOOTH_SCAN` is `neverForLocation`. BLE code (`FtmsBleServer`,
  `HrmAdapter`, `AndroidSystemMonitor`) checks the relevant perm and degrades gracefully if denied.
- **Scoped storage**: never write to public `Downloads` — `WRITE_EXTERNAL_STORAGE` is ignored for
  `targetSdk ≥ 30` and `getExternalStoragePublicDirectory()` is unwritable from API 29+. Exports use
  `context.getExternalFilesDir(...)` (no permission, all API levels).
- No dynamic color (Material You requires API 31+); the runtime works on all versions but the
  console theme stays static.
- Use `@Suppress("DEPRECATION")` for deprecated-but-still-needed pre-API-26 APIs as needed.

## Configuration & Secrets

- **`local.properties`** (gitignored) holds `sdk.dir`, the optional release-signing passwords, and the optional `server.url` / `r2.base.url` for the self-update and support-diagnostics features. See `local.properties.example`. None of these are required to build the debug APK or run the tests.
- **Certificate pinning**: not configured by default — there is no fixed server domain. A fork hosting its own infrastructure can add a `<domain-config>` with primary + backup pins in `app/src/main/res/xml/network_security_config.xml`.

## Lint

AGP lint can analyze stale merged manifests if source files changed after a prior build. When modifying `AndroidManifest.xml` or resources, run `./gradlew clean lint` instead of just `lint`.
