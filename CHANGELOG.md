# Changelog

## [Unreleased]
- **New "Screen Sleep" setting lets the console screen turn off after a period of inactivity.** The console screen previously stayed on indefinitely. Under Settings → Display you can now enable Screen Sleep and choose an idle timeout (2, 5, 10, or 30 minutes); the display stays on the whole time you're working out and only sleeps once you've stopped and stopped touching the screen. Off by default — existing behaviour is unchanged until you turn it on. Enabling it asks for the one-time "modify system settings" permission; turning it back off restores the screen timeout the console had before.

## [1.2.12] - 2026-06-04
- **Reworked the bike connection handshake to match how the console's own software brings the equipment up — fixes spin bikes (e.g. the NordicTrack S15i) that wouldn't connect.** 1.2.11 added a "which commands do you support?" query but still failed, because this controller didn't answer *that* either. The cause turned out to be a legacy `Connect` step we sent right after reading device info: that step isn't part of how the equipment is actually brought up, and on these controllers it makes them go silent to every following query — which then wedges the USB link. The handshake now drops that step entirely and, immediately after reading device info, asks the controller which commands it accepts and **only sends the ones it lists** (system info, firmware version, security unlock); if it lists none, the app goes straight to streaming live data. Consoles that already worked are unaffected — they report the full command set, so the same commands are sent as before, just without the unnecessary connect step.

## [1.2.11] - 2026-06-03
- **Bike consoles that only implement part of the FitPro command set now connect.** Building on 1.2.10: removing the `SupportedDevices` query let the NordicTrack S15i spin bike get one step further, but it then stalled at `SystemInfo` the same way — this controller answers `DeviceInfo` and `Connect` but not `SystemInfo`, and sending a controller any command it doesn't implement wedges the USB link (so the dashboard never showed the bike and no workout could start). The app now does what the stock console does: right after connecting it asks the controller which commands it accepts, then only sends `SystemInfo`/`VersionInfo`/security verification if the controller lists them — otherwise it skips straight to the live data stream. Power-curve and security details are simply left at their defaults for controllers that don't expose them; the per-second workout data (speed, cadence, power, resistance, incline) is unaffected. Consoles that already worked are unchanged — they report the full command set, so nothing is skipped.

## [1.2.10] - 2026-06-02
- **Spin-bike consoles that previously failed to connect now pair and stream.** On some bikes — e.g. the NordicTrack S15i Commercial Studio Cycle — Hyperborea would try to identify the equipment, fail, and retry forever: the dashboard never showed the device and no workout could start. During the connection handshake the app sent a diagnostic "supported devices" query that these consoles' controllers don't answer; the unanswered command left the USB link wedged, so every following command failed and the connection dropped, looping on each USB re-attach. That query only ever fed an unused diagnostic, so it's been removed — the handshake now goes straight from connect to reading the equipment's system info, which these consoles complete normally. Bikes that already worked are unaffected (and connect a little faster).

## [1.2.9] - 2026-05-21
- **A workout you end on the treadmill console can now be restarted without force-quitting the app.** After a run ended on the console (the long-beep stop), neither the app's Start nor the physical Start key would begin a new workout until Hyperborea was force-stopped and reopened. The cause was an abrupt USB teardown: on stop the app closed the connection the instant it sent the disconnect, while the equipment's controller was still finishing its end-of-workout housekeeping — leaving the link in a state that only a full reconnect (which force-quitting forced) could clear. Stopping now ends the workout cleanly (and drops incline to 0 on incline-capable machines) and waits for the controller to signal it's ready before releasing the USB link, so the next workout starts normally from the app's Start button.

## [1.2.8] - 2026-05-20
- **Setting treadmill speed over FTMS now works.** The Fitness Machine Control Point handled Set Target Inclination, Resistance, and Power but not **Set Target Speed** (opcode `0x02`) — so an app controlling a treadmill over WiFi/BLE FTMS could set incline but not speed (the write came back as unsupported). Speed-set is now parsed (uint16, 0.01 km/h) and forwarded to the console's belt-speed control, on both the WiFi and BLE paths.
- **Distance now shows 2 decimal places** on the dashboard and the profile / ride-summary screens — far more useful for short distances now that distance reads in real kilometres.
- **Equipment lifetime stats (Device settings) read sanely on treadmills.** The console's total-time and odometer fields use different units per machine: bikes report seconds and metres, but treadmills report milliseconds and millimetres, which were being shown raw as a ~13-year runtime and an 833,757 km odometer. Belt-machine values are now scaled to real hours/kilometres (e.g. 113 h / 834 km); bikes are unchanged. (The units can't be told apart by value alone — a lightly-used millisecond device looks like a heavily-used second device — so the scaling keys off device type.)
- **No more phantom speed target on bikes.** The dashboard speed cell was showing a blue "set" target of 0.0 on bikes (a bike has no commandable speed); that target is no longer surfaced for non-belt machines. Treadmills are unchanged.
- Internal: removed the temporary FitPro field-shape diagnostic logging added in 1.2.7 — the treadmill speed/distance/startup decode is confirmed, so it's no longer needed.

## [1.2.7] - 2026-05-20
- **Treadmill speed now reads the real belt speed instead of 0.** Belt machines report belt speed in the `KPH` field and leave `ACTUAL_KPH` at 0; bikes do the opposite (a virtual speed in `ACTUAL_KPH`, with `KPH` as the target read-back). The FitPro V1 decoder always took speed from `ACTUAL_KPH`, so on a treadmill the live speed sat at 0 — including the one metric Zwift shows for a treadmill on a free account, which is why it read 0 there too. Speed is now sourced per device type (new shared `DeviceType.isBeltBased`); bikes are unchanged.
- **Distance was reported roughly 1000× too large on every machine — bikes included.** The wire value is in metres, but `ExerciseData.distance` and everything downstream of it (FTMS total distance, the dashboard "KM" field, the recorded `distanceKm`, FIT export) is in kilometres, and the metres→km conversion was missing — so a short ride showed thousands of "KM" and saturated the FTMS distance field. Now converted at the source.
- **The app's Stop now actually halts a treadmill belt.** A bare `WORKOUT_MODE=IDLE` write does not stop the belt — it kept running until you pressed the physical Stop key, even after going through and discarding the ride. Stopping a belt machine now commands belt speed to 0 and pauses the workout, and confirms the console accepted it before disconnecting. (Pause already stopped the belt; Stop now matches.)
- **Built-in grip heart rate is now smoothed.** The raw hand-contact reading arrives every ~100 ms and swung wildly (users saw it bounce anywhere from 60 to 150 bpm). It's now validity-gated — implausible values and the contact-loss 0 are dropped, blanking the number when you let go — and run through a moving average. External BLE straps are already clean and bypass this entirely.
- **External Bluetooth heart-rate monitors now appear on the main dashboard, not just the sensor settings screen.** The dashboard was reading the raw hardware stream while the merged strap reading only fed the broadcasts, so a paired strap showed correctly in Settings but never on the dashboard. Both now read one shared stream, so the strap shows on the dashboard and in Zwift. A previously-paired strap also reconnects automatically at the start of a workout (e.g. after an app restart), not only when you open the sensor screen.
- **Treadmill "Press START on the console" prompt reworded** so it no longer claims the belt is stopped — `WARM_UP` is an active phase and on some consoles the belt is already moving by then. It now says Hyperborea is connected and broadcasting, and the workout begins when you press START on the console. Behaviour is unchanged: recording still begins when the console reports it is running.
- **`deploy.{sh,ps1}`: fall back to `pm hide` when `pm disable-user` is refused.** Some firmware rejects `pm disable-user` from an unprivileged shell for `com.ifit.eru` (and even the user-installed `com.ifit.standalone`), leaving them running and able to grab the USB device. `pm hide` needs a lighter permission set and has the same end effect (the package is invisible to `pm`, its receivers don't fire, and it can't autolaunch); the scripts now fall back to it and recognise hidden packages when reporting status.
- Internal: a temporary, bounded diagnostic log dumps the first few raw FitPro poll payloads (and the startup read) at I-level to help validate the decode on treadmill firmware we can't reproduce locally — it will be removed in a later release.

## [1.2.6] - 2026-05-13
- **Treadmills: the app's Start button no longer pretends the workout started while the belt is still parked.** On a treadmill the MCU itself gates belt motion on the physical Start key (rising edge of the read-only `START_REQUESTED` field) — writing `WORKOUT_MODE=RUNNING` from the app alone times out the confirmation poll and was surfacing as a (semantically wrong) "console didn't fully start" degraded warning. The FitPro V1 session now branches by equipment type captured from the MCU's own `Connect` device-id response: treadmills/incline trainers arm at `WARM_UP` and stop there, and the orchestrator parks in a new `AwaitingConsoleStart` state until the WORKOUT_MODE poll picks up `RUNNING` (the user pressing the physical Start key). The dashboard shows a prominent "Press START on the console to begin" prompt; the StatusBar pulses amber and offers a CANCEL button. Pressing the physical Stop key tears the workout down symmetrically with the app's Stop. Broadcasts (FTMS/WiFi) go live as soon as the app's Start button is tapped, so Zwift can pair while the user walks to the console.
- **FitPro V2 brought to parity** with the V1 treadmill split. V2 has no equipment-id in its handshake, so the device type is inferred from the supported-features set the console declares (treadmill = belt-speed + grade features, no flywheel resistance). On detected treadmills V2 also arms at `WARM_UP` and waits. V2's `WORKOUT_STATE` is now translated to V1's numbering on its way to the orchestrator so the same workout-mode monitor handles both protocols uniformly — including mapping `OFF_MACHINE` to the safety-pause path.
- **Fix garbage Distance / Calories values on treadmill firmware that doesn't expose every bike-style field** (e.g. NordicTrack 2950 Argon-firmware treadmill, which produced -10595 kcal and 139 km Distance after 2:38 with the same 139 also showing up as Cadence — the smoking gun). The V1 poll loop now intersects the requested field set with the device's self-declared `supportedBitFields` and decodes the response against that same intersection, so a field the MCU silently omits can't misalign every later field's offset in the positional decoder. A `DataResponse.isTruncated` flag and a new logcat warning surface the same class of bug going forward. V2 audited and immune by design (events arrive keyed by featureId).
- **Fix the USB-permission dialog reading "Allow" as "Deny"** on the second-or-later Start tap. The `PendingIntent` passed to `UsbManager.requestPermission()` was `FLAG_IMMUTABLE`, so the `EXTRA_PERMISSION_GRANTED` fill-in extra AOSP's `UsbPermissionActivity` adds to the result Intent was being silently dropped — the receiver saw it default to `false` and reported the prerequisite as failed even though the system had already persisted the grant. Now uses `FLAG_MUTABLE` on API 31+ and reads `UsbManager.hasPermission(device)` directly as the source of truth (matching what `SystemMonitor` and the transport factory use).
- **Self-update install actually waits for the system installer to finish.** The old `AppInstaller` fired the deprecated `ACTION_VIEW` install intent and returned `Success` the instant `startActivity()` returned — so when the stock package installer rejected the APK ("There was a problem parsing the package"), Hyperborea still showed "Update Installed / Restarting…" and left the console on the old version with no error. Rewritten around `PackageInstaller`: streams the APK into a `MODE_FULL_INSTALL` session, commits with a `PendingIntent`-backed `IntentSender`, and bridges the status `BroadcastReceiver` back to the suspend call. `STATUS_PENDING_USER_ACTION` launches the system confirm dialog; `install()` suspends until the real outcome, so `Installed` now means installed and a failure surfaces as a real error on the dashboard.
- **Settings: global Display section that works for the Guest user.** Units (mph / km/h), System Overlay, and Immersive Mode toggles are now in the Settings screen — backed by global `UserPreferences` rather than per-Profile — so a guest can pick mph/km/h without first creating an account. Fan mode also moved out of the AdminDrawer-only into the Settings → Device section. Distance values on the dashboard were never being unit-converted before — only the label was wrong — so this fixes a real bug alongside the new feature. Existing profile rows are migrated (`MIGRATION_2_3`).
- Internal: `ConsoleKey.START` and `STOP` are now mapped (codes 2 and 1) so physical Start/Stop key presses surface on the existing `consoleKeyPresses` stream alongside resistance/incline/speed — observe-only, the MCU still drives the state machine.

## [1.2.5] - 2026-05-12
- **Self-update now installs through `PackageInstaller` instead of a fire-and-forget install intent.** The old flow launched the deprecated `ACTION_VIEW` install intent and reported "Update Installed" the instant it was sent — so when a console's stock package installer rejected the APK ("There was a problem parsing the package"), Hyperborea still showed "Update Installed / Restarting…" and left the console on the old version with no error and no way forward. It now streams the downloaded APK into a `PackageInstaller` session, surfaces the system's confirmation dialog, waits for the actual result, and shows the real outcome on the dashboard if it fails (e.g. "Update install failed: the package could not be parsed (…)") instead of pretending it worked. (`ACTION_VIEW`/`ACTION_INSTALL_PACKAGE` for installing APKs have been deprecated since Android 10; `PackageInstaller` is the documented replacement and only needs the normal `REQUEST_INSTALL_PACKAGES` permission.) Drops the now-unused `FileProvider`.
- **Fix workouts not starting on newer ICON consoles** (e.g. some S22i revisions, the 2950 treadmill): the FitPro V1 session now brings the console up through the state path the firmware expects — `IDLE → WARM_UP → RUNNING`, confirming each step by reading the workout-mode field back — instead of jumping straight to `RUNNING` and cramming the console-init fields (`REQUIRE_START_REQUESTED`, `IDLE_MODE_LOCKOUT`) into that same packet. On stricter firmware the old shortcut left the console parked in a ready-but-not-running sub-state, so resistance (bikes) / belt speed (treadmills) wouldn't respond. The console-init fields are now written separately while the console is still idle (gated on what the device declares it supports, mirroring the stock service); the state transitions are logged (`Console state: IDLE → WARM_UP → RUNNING`); and if the console never confirms it started, the dashboard now shows a "Degraded — resistance/speed may not respond" warning instead of failing silently. Also fixed a rounding error in the resistance-level↔raw conversion that drifted slightly from the stock firmware's, and a divide-by-zero if a device reports `maxResistance = 0`.
- The same workout-start fix was applied to the **FitPro V2 protocol** (newer hardware): it now drives the console's `WORKOUT_STATE` feature `NONE → WARM_UP → RUNNING` with event-based confirmation and the same degraded-warning fallback, instead of writing a single (and, it turns out, mis-numbered) "running" value. Note V2 hasn't been verified on real V2 hardware — if it misbehaves the dashboard will say so.

## [1.2.4] - 2026-05-12
- Don't surface a USB-permission error on the dashboard at boot: `Orchestrator.probe()` is now passive — it doesn't pop the USB permission dialog or go into an error state when nothing's connecting yet; it just re-probes when the FitPro device becomes accessible (or when the user starts a workout).
- `deploy.{sh,ps1}`: hand the home screen to the device's own Android launcher (`com.android.launcher3`) instead of making Hyperborea the home app — pressing Home goes to the normal Android launcher, and Hyperborea keeps running in the background (its `BootReceiver` restarts the foreground service on boot, so it doesn't need to be the home app). Falls back to Hyperborea-as-home only on consoles that ship no other launcher. To switch a console already deployed with an older build: `adb shell cmd package set-home-activity com.android.launcher3/.Launcher`.
- `deploy.ps1`/`deploy.cmd`: adb's chatter on stderr (the "daemon not running; starting it now" notice, post-reboot transport messages, `su: not found`, …) no longer aborts the PowerShell deploy mid-run; `deploy.cmd` now returns the deploy's real exit code.
- Internal: the console membrane keypad (`KEY_OBJECT`) is now decoded and exposed as an observe-only event stream — groundwork for showing physical button presses in the UI; no behaviour change (the equipment's own controller still handles those keys).

## [1.2.3] - 2026-05-12
- **Fix the foreground-service crash-loop on consoles running Android 8.0+ (ICON consoles ship anything from Android 5.1 to 9+, not just the 7.1.2 the older code assumed):** the service notification now creates a `NotificationChannel` on API 26+ (a channel-less notification is rejected → `RemoteServiceException: Bad notification for startForeground` → crash every ~6 s), declares `android:foregroundServiceType="connectedDevice"` (mandatory on API 34+), `POST_NOTIFICATIONS` (API 33+), and is started via `startForegroundService()` from background contexts (boot receiver / `Application.onCreate()`) so it doesn't `IllegalStateException` on API 26+.
- **Fix the first-boot crash on file-based-encryption devices:** `MainActivity` is no longer `directBootAware`, so the OS doesn't launch it (and the Hilt graph → `SharedPreferences`) before the user is unlocked — that was throwing `IllegalStateException: credential encrypted storage are not available until after user is unlocked`.
- Bluetooth permissions now use the Android-12 dual model (`BLUETOOTH_SCAN`/`ADVERTISE`/`CONNECT` requested at runtime in `MainActivity`; legacy `BLUETOOTH`/`BLUETOOTH_ADMIN`/`ACCESS_FINE_LOCATION` capped at API 30; `BLUETOOTH_SCAN` flagged `neverForLocation`), so BLE FTMS and HRM scanning work on API 31+ instead of silently failing. Capability/scan code degrades gracefully if a permission is denied.
- Log / FIT exports write to the app-specific external dir (`getExternalFilesDir`) instead of the public `Downloads` folder — the latter is unwritable under scoped storage (API 29+) and `WRITE_EXTERNAL_STORAGE` is ignored for `targetSdk ≥ 30`. Retrieve with `adb pull /sdcard/Android/data/<pkg>/files/Download/<file>`.
- `usb-device-accessible`: when no FitPro USB device is attached, wait for one (within the existing 10-minute budget) instead of failing instantly — handles the console's ~20 s USB power-cycle.
- `deploy.{sh,ps1}`: on consoles with root ADB, push the BLE-peripheral overlay to `/vendor/overlay/` so it takes effect after the reboot; on firmware without root, skip it and note that only WiFi broadcasting is available (no more pointless `adb install` to `/data/app/`). Set `HYPERBOREA_SKIP_OVERLAY=1` to skip it.
- Settings and the broadcast picker now explain why BLE FTMS is unavailable (needs root or a firmware mod) and grey out the toggle, instead of a bare ✗.
- Release zip no longer bundles stray top-level files.

## [1.2.2] - 2026-05-12
- Fix the foreground-service notification crash-loop on stock iFit firmware: the
  notification small/action icons are now raster PNGs (the system process can't
  inflate a `<vector>` against a stripped `framework-res`), and the ineffective
  `try/catch` around `startForeground()` is removed.
- The `usb-device-accessible` prerequisite waits for the user to answer the
  USB-permission dialog (10-minute budget) instead of reporting success the
  moment it's shown; a denied dialog now surfaces a clear error.
- Deployment disables **every** `com.ifit.*` package, the iFit launcher
  included — leaving it enabled let it re-enable ERU on every boot, which then
  re-grabbed the USB device and resumed pushing firmware updates — and waits
  for PackageManager to persist the change before rebooting, so it survives.
- `MainActivity` registers as a HOME activity and the deploy points the HOME
  intent at it, so a plain reboot lands on Hyperborea (falls back to another
  launcher if Hyperborea is uninstalled).
- `deploy.{sh,ps1}`: enumerate iFit packages dynamically; fix the multi-minute
  hang waiting for the console to come back after the post-install reboot over
  ADB-over-TCP; stop double-printing the "Connect to device" header.

## [1.2.1] - 2026-05-11
- Fix a crash on launch on stock iFit console firmware (observed on the
  NordicTrack X22i): the foreground-service notification referenced framework
  drawables (`android.R.drawable.*`) for its small icon and actions. On
  firmware that ships a stripped `framework-res`, the small icon failed to
  resolve, so the system rejected the notification ("Bad notification for
  startForeground") and killed the process a couple of seconds after launch —
  every launch. The notification now uses app-owned vector icons, and
  `startForeground` is wrapped so a rejected notification degrades to a plain
  service instead of taking the process down.

## [1.2.0] - 2026-05-10
- Deployment no longer requires root. `release/Hyperborea/deploy.{sh,ps1,cmd}`
  now installs Hyperborea as a regular APK over plain ADB and disables iFit's
  competing apps with `pm disable-user --user 0`. Works on consoles where
  `adb root` was never available (e.g. NordicTrack X22i, MGA1 firmware), in
  addition to the rooted S22i. The previous privileged-system-app deploy is
  preserved on the `archive/priv-app-deployment` branch.
- Drop six `signature|privileged` permissions from the manifest:
  `MANAGE_USB`, `FORCE_STOP_PACKAGES`, `CHANGE_COMPONENT_ENABLED_STATE`,
  `WRITE_SETTINGS`, `WRITE_SECURE_SETTINGS`, `INSTALL_PACKAGES`. Add the
  normal-protection `REQUEST_INSTALL_PACKAGES` for self-update.
- USB device-attached intent filter on `MainActivity` so Android offers
  Hyperborea on FitPro attach with the standard "always" checkbox; once
  granted, permission persists across the device's ~20s reconnect cycle.
- In-app self-update now hands the APK to the system installer dialog via
  `Intent.ACTION_VIEW` + FileProvider, with a `canRequestPackageInstalls()`
  pre-flight on API 26+ that routes the user to *Install unknown apps*
  settings the first time.
- `<queries>` block + `directBootAware` on `MainActivity` for Android 11+
  package-visibility and post-reboot USB-permission restoration.
- BLE FTMS and WiFi broadcasts now stream notifications at a fixed 250 ms
  cadence while a client is connected. Previously the conflated StateFlow
  could go silent at idle, leaving Zwift wedged until the broadcast was
  toggled off/on; this matches real FTMS hardware behaviour.
- Check in the BLE-peripheral vendor overlay
  (`release/Hyperborea/apps/BluetoothPeripheralOverlay.apk`) for forks that
  install on consoles whose BSP disables LE peripheral mode.

## [1.1.0] - 2026-05-10
- Project is now open source under the MIT License.
- Removed the device-pairing license check entirely — the app runs without contacting any server.
- In-app self-update and support-diagnostics upload are now optional, build-time-configurable
  (`server.url` / `r2.base.url`), and disabled by default. They no longer send an auth token.
- Removed the signature self-check and the legacy `system` product flavor / `platform` signing
  config; release builds fall back to the debug signing key when `release.jks` is absent.
- Dropped unused dependencies (BouncyCastle, AndroidX Security-Crypto, ZXing) and the
  `SET_TIME_ZONE` permission.
- Added `README.md`, `CONTRIBUTING.md`, `local.properties.example`.
- No user-facing behavior changes to workout broadcasting (BLE FTMS, WiFi TCP) or device control.

## [1.0.0] - 2026-03-21
- Initial release
