# Changelog

## [Unreleased]

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
