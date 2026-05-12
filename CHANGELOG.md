# Changelog

## [Unreleased]

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
