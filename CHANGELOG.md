# Changelog

## [Unreleased]

## [1.2.2] - 2026-05-12
- The 1.2.1 fix for the "Bad notification for startForeground" crash-loop was
  incomplete. It switched the foreground-service notification's icons from
  `android.R.drawable.*` to app-owned **vector** drawables — but notification
  icons are rendered by the *system* process, and on firmware that ships a
  stripped `framework-res` (stock iFit consoles, e.g. NordicTrack S22i/X22i)
  the system process can't inflate a `<vector>` either, so the notification was
  still rejected and the process still killed ~2 s after launch, every launch.
  The notification small icon is now a **PNG** (white-on-transparent, all five
  density buckets); the two action icons reuse it (they aren't drawn by the
  standard template on API 24+ anyway). Also removed the `try/catch` 1.2.1 put
  around `startForeground()` — `RemoteServiceException` for a bad notification
  is delivered asynchronously on the main-thread Handler, not thrown out of the
  `startForeground()` call, so that catch never ran.
- The first launch after install no longer fails with "Failed to identify
  hardware" while the USB-permission dialog is still on screen. The
  `usb-device-accessible` prerequisite now suspends until the user actually
  answers the dialog (it was fire-and-forget, reporting success the instant the
  dialog was *shown*). Because the app auto-launches on the console as soon as
  the deploy finishes — when the user may still be at their computer — the
  dialog gets a 10-minute budget (vs. the 10 s the orchestrator gives the
  pm/am-call prerequisites); a denied dialog now surfaces "USB device permission
  was not granted" instead of a misleading hardware-probe error.

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
