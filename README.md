# Hyperborea

Hyperborea is an Android app that bridges ICON Fitness exercise equipment to Zwift and other
fitness platforms. It reads live exercise data from the equipment's console over USB serial using
the FitPro protocol and re-broadcasts it as standard fitness protocols — Bluetooth Low
Energy [FTMS](https://www.bluetooth.com/specifications/specs/fitness-machine-service-1-0/) and a
WiFi TCP stream — so apps like Zwift see a normal smart trainer. It also forwards
resistance/incline targets back to the equipment for ERG-style control.

It runs on the equipment's own Android console (the primary target is the NordicTrack S22i, a 22"
landscape console running Android 7.1.2 / API 25) and works with any ICON Fitness device that
speaks the FitPro protocol over USB (USB vendor ID `0x213C`) — bikes, treadmills, and ellipticals.

> [!IMPORTANT]
> **Disclaimer.** This is an independent project. It is not affiliated with, authorized by, or
> endorsed by ICON Health & Fitness, iFit, NordicTrack, ProForm, FreeMotion, Schwinn, Zwift, or
> any other company. "iFit", "NordicTrack", "ProForm", "FitPro", "Zwift", and other names are
> trademarks of their respective owners and are used here only for identification. The software is
> provided "as is" with no warranty (see [LICENSE](LICENSE)). You are responsible for complying
> with the terms of service and warranties of your equipment and the platforms you connect to, and
> for any modifications you make to your device. Use at your own risk.

## Screenshots

| Dashboard | Profile picker | Ride detail | Settings |
|---|---|---|---|
| ![Dashboard](screenshots/01_dashboard_idle.png) | ![Profiles](screenshots/03_profile_picker.png) | ![Ride](screenshots/06_ride_detail.png) | ![Settings](screenshots/09_settings_system.png) |

More screenshots are in [`screenshots/`](screenshots/).

## Building

Requires the Android SDK (and JDK 17+). Create a `local.properties` in the repo root pointing at
your SDK (see [`local.properties.example`](local.properties.example)):

```properties
sdk.dir=/path/to/Android/Sdk
```

Then:

```bash
./gradlew :app:assembleStandardDebug   # debug APK -> app/build/outputs/apk/standard/debug/
./gradlew test                         # run all unit tests
./gradlew lint                         # static analysis
```

Always build the `standard` product flavor. Release builds (`./gradlew :app:assembleStandardRelease`)
are signed with `release.jks` if present, otherwise with the debug key — see
[`local.properties.example`](local.properties.example) for the optional release-signing and
self-update/diagnostics settings. The `./gradlew prepareRelease` task runs the full
clean → lint → test → build → package pipeline and requires a release keystore.

## Installing

Hyperborea needs to run as a privileged system app on the equipment's console (for USB device
management, overlay windows, and stopping the stock iFit foreground app while it owns the USB
port). The `release/Hyperborea/` directory contains deploy scripts (`deploy.sh`, `deploy.ps1`,
`deploy.cmd`) that install it via rooted ADB into `/system/priv-app/`. This requires a console
with root ADB access; consoles on newer iFit firmware are not rootable out of the box.

## Architecture

```
:app  →  :core  ←  :hardware:fitpro
  ↓                 :broadcast:ftms
  ↓                 :broadcast:wifi
  ↓                 :ecosystem:ifit
  ↓                 :sensor:hrm
  └── wires everything together via Hilt
```

- **`:core`** — pure Kotlin (no Android deps): domain interfaces, data types, orchestration logic.
- **`:hardware:fitpro`** — the FitPro USB-serial hardware adapter (115200 baud).
- **`:broadcast:ftms`** — BLE GATT server advertising FTMS (service `0x1826`).
- **`:broadcast:wifi`** — WiFi TCP server for fitness apps.
- **`:ecosystem:ifit`** — coexistence with the stock iFit apps (stop the standalone app, disable the
  crash-looping update receiver) while Hyperborea owns the USB device.
- **`:sensor:hrm`** — optional external BLE heart-rate monitor support.
- **`:app`** — Compose UI (Material 3, landscape), the foreground service, the orchestrator wiring,
  and the Android platform implementations of the `:core` interfaces.

Data flows hardware → orchestrator → each broadcast sink; control targets flow back the other way.
See [`AGENTS.md`](AGENTS.md) for the design conventions and module details.

## Optional server features

Two features are disabled unless you configure them at build time (see
[`local.properties.example`](local.properties.example)):

- **In-app self-update** — checks a manifest URL (`server.url`) and downloads APK updates from an
  allowed prefix (`r2.base.url`). With no `server.url` configured, auto-update is off.
- **Support-diagnostics upload** — POSTs a logs+system bundle (no personal data) to
  `${server.url}/api/support/upload` from the in-app "Get help" action. Off when `server.url` is
  empty.

Neither feature sends authentication; a fork hosting its own infrastructure can point these at its
own endpoints (and add certificate pinning in `app/src/main/res/xml/network_security_config.xml`).

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md).

## License

[MIT](LICENSE) © 2026 Jonathan Ball.
