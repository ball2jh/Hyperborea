# Hyperborea

Hyperborea is an Android app that bridges ICON Fitness exercise equipment to Zwift and other
fitness platforms. It reads live exercise data from the equipment's console over USB serial using
the FitPro protocol and re-broadcasts it as standard fitness protocols — Bluetooth Low
Energy [FTMS](https://www.bluetooth.com/specifications/specs/fitness-machine-service-1-0/) and a
WiFi TCP stream — so apps like Zwift see a normal smart trainer. It also forwards
resistance/incline targets back to the equipment for ERG-style control.

It runs on the equipment's own Android console (the primary target is the NordicTrack S22i, a 22"
landscape console) and works with any ICON Fitness device that speaks the FitPro protocol over USB
(USB vendor ID `0x213C`) — bikes, treadmills, and ellipticals. These consoles ship a range of
Android versions in the field — anything from Android 5.1 to Android 9+ — and Hyperborea supports
the whole range.

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

### Local treadmill simulator

Run the `standardDebug` build on an Android emulator to use the built-in demo treadmill;
no USB equipment is needed. A landscape tablet emulator at 1920×1080 is useful for matching
the console dashboard. With the emulator booted and selected by ADB:

```bash
./gradlew :app:assembleStandardDebug
adb install -r -g app/build/outputs/apk/standard/debug/app-standard-debug.apk
adb shell am start -n com.nettarion.hyperborea.debug/com.nettarion.hyperborea.MainActivity
```

Choose a profile or **Guest**, then press **START**. The simulator waits five seconds to mimic
the physical console Start key,
then ramps up to 8 km/h. Speed, incline, pause, and resume controls work against simulated
hardware, and the central track completes one lap per 400 m. Debug builds on real consoles
still use FitPro USB hardware; release builds always use real hardware.

To check track geometry and lap boundaries, including ten consecutive laps:

```bash
./gradlew :app:testStandardDebugUnitTest --tests '*TrackMathTest'
```

## Installing

Hyperborea installs as a regular Android APK over ADB — no root required. The `release/Hyperborea/`
directory contains deploy scripts (`deploy.sh`, `deploy.ps1`, `deploy.cmd`) that:

1. `adb install -r -g` Hyperborea onto the console.
2. `adb shell pm disable-user --user 0 …` the iFit apps that compete for the FitPro USB device
   (standalone, ERU, GlassOS service, and friends — `com.ifit.launcher` is left enabled so the home
   button still works).
3. `adb reboot` to make sure no iFit processes survive.

All of step 2 works as the unprivileged `shell` user; the only prerequisite is an ADB connection
to the console. On consoles where ADB isn't enabled out of the box, community tools like atvTools
can enable it without rooting the device.

**BLE caveat.** On consoles whose BSP ships with `config_bluetooth_le_peripheral_mode_supported=false`
in `framework-res.apk`, `getBluetoothLeAdvertiser()` returns null and FTMS broadcast over BLE won't
work without a `/vendor/overlay/` fix — and that overlay push *does* require root. WiFi broadcast
(TCP, port 36866) works on every device. The previous deployment flow installed Hyperborea as a
privileged system app at `/system/priv-app/Hyperborea/` and pushed the BLE-peripheral overlay
along with it; that path lives on the [`archive/priv-app-deployment`](https://github.com/ball2jh/Hyperborea/tree/archive/priv-app-deployment)
branch if you need to revive it for a rootable console.

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

## Treadmill programs

On the treadmill dashboard, choose **Programs** for ready-made runs or a custom session.
**Start program** arms the equipment; the physical console Start key still starts the belt.
While a manual walk is running, **Start program** offers to continue the same workout after
a 3–2–1 count-in. The warm-up stays in the recording; speed and incline stay unchanged until
the count-in finishes. An active program must be stopped before choosing another.
**Edit** opens aligned speed and incline graphs. Drag graph handles to adjust targets, drag
**⠿** on the bottom blocks to reorder, or use **Earlier / Later**. Blocks have a duration entered in minutes and seconds,
speed, incline, and an optional gradual ramp. Custom drafts are retained locally; **Save program**
adds a reusable plan to **My programs**. You can also save a plan after finishing or stopping.
Enable **Loop program** on the editor’s right side to repeat all blocks until you press Stop.
The setting is saved with the program; the last block counts down to the first block’s targets
when they change, with no automatic workout stop between loops.
The **All blocks** row under the block strip moves every block's speed (±0.1 / ±0.5 in your
units) or incline (±0.5 %) together, so one program can be made a notch harder or easier without
editing each block. A step is unavailable when it would push any block past the treadmill's limits.
**Ease to a stop** (on by default, saved with the program) adds a taper after the last block of a
single-pass program: the belt slows to 3.2 km/h (2.0 mph) at about 0.2 km/h per second, then stops.
It is skipped when the last block is already at walking pace, and never speeds the belt back up if
you had slowed it yourself.
Completed workouts continue to use the existing profile ride history.

Program timing, target changes, and audible 3–2–1 countdowns run in the foreground service.
The three beeps precede a distinct change tone; ramps are announced once at their start.
Incline block settings keep their usual steps, while ramp commands retain finer wire precision.
Held incline +/− controls select a destination immediately; the deck receives the final target
after release and a short quiet interval, avoiding a command for every intermediate selection.
Media sound must be available before automatic targets are applied. Pause freezes the program;
manual speed/incline adjustments (app, widget, physical console keys, or external commands) hold
only the adjusted axis for the rest of the current block. Program time and the other axis continue.
The next block restores automation after a full 3–2–1 warning; a late adjustment extends the current
block enough to give all three beeps. The dashboard strip shows block progress, separate live/next
speed and incline rows, and which axis is manual. Unavailable audio and connection/timing interruptions
still hold the whole program until **Resume program** gives a fresh countdown, rather than
fast-forwarding through missed changes. Single-pass programs stop the workout at
the end of the final block — or of the **Ease to a stop** taper that follows it — after a visible
and audible 3–2–1 stop warning. Looping programs
repeat until stopped. Programs do not
automatically resume after process death. A treadmill workout stays paused until you explicitly
resume or stop it. Supported V1/V2 controllers are initialized with a one-hour hardware pause timer, but if that
timer expires the app keeps the workout, program position and cumulative counters; the controller's
RESULTS state is cleared to idle without authorizing belt movement. Resume requires a person to
press Resume or physical Start, followed by controller confirmation before automation resumes.
Unsolicited start/running reports and safety-key reinsertion cannot resume a paused treadmill.
If Resume is not confirmed within ten seconds, the app reasserts pause and keeps the workout for
an explicit retry. A USB disconnect while a treadmill is paused still ends the session safely,
rather than automatically reconnecting with a fresh protocol state.

### Program files

Saved programs live in the app's private settings, but **My programs → Export files** writes each
one as a JSON file to the app's external files folder, and **Import files** reads every `.json`
file in that folder back in. The folder is shown on the My programs tab:
`/sdcard/Android/data/com.nettarion.hyperborea/files/programs/` (`…hyperborea.debug/…` for a debug
build). That makes it reachable with ADB, so a program can be written or edited on a laptop:

```bash
adb pull /sdcard/Android/data/com.nettarion.hyperborea/files/programs/ .   # after Export files
adb push ramps.json /sdcard/Android/data/com.nettarion.hyperborea/files/programs/
# then My programs → Import files
```

```json
{
  "name": "30 s ramps",
  "speedUnit": "mph",
  "loop": false,
  "easeToStop": true,
  "blocks": [
    { "name": "Warm-up", "seconds": 300, "speed": 4.0 },
    { "name": "Ramp", "seconds": 30, "speed": 6.5, "incline": 1.0, "ramp": true },
    { "name": "Hold", "seconds": 30, "speed": 6.5, "incline": 1.0 }
  ]
}
```

Only `blocks` with `seconds` and `speed` are required. `speedUnit` is `"kph"` (default) or `"mph"`;
`incline` is percent (default 0); `ramp` (default false) glides from the previous block's targets
across the block; a missing `name` falls back to the file name. Limits are the editor's: 1–100
blocks of 10 s–60 min each. An exported file also carries an `id` — keep it and Import replaces
that program; remove it (or copy the file under a new name without it) and Import adds a new one.
A file that can't be used is skipped and named on screen with the reason. Import and Export never
delete programs or files.

Enable **Background widget** in Programs (or **System Overlay** in Quick Settings). The Programs
toggle opens Android's overlay permission screen when needed. This shows
the next change countdown, separate speed/incline rows with live measurements and upcoming targets,
and Pause/Stop controls while another app is open. The program name and extra metrics are omitted.
Both overlay styles support program status; tap that status to return to Hyperborea.
