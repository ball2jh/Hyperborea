# BLE-peripheral resource overlay

A tiny Runtime Resource Overlay (RRO) APK — package `com.nettarion.hyperborea.overlay.bluetooth`
— that overrides one framework resource:

```xml
<bool name="config_bluetooth_le_peripheral_mode_supported">true</bool>
```

Some device BSPs ship `framework-res.apk` with that boolean set to `false` even though the
Bluetooth controller supports advertising, which makes `BluetoothAdapter.getBluetoothLeAdvertiser()`
return `null` and breaks the BLE FTMS broadcast. Placing this overlay in `/vendor/overlay/` flips
it back to `true`.

## Files

- `AndroidManifest.xml`, `res/values/bools.xml` — the overlay sources.
- `build.sh` — builds the APK with `aapt2` and signs it with a throwaway key (the signature is
  irrelevant for overlays in `/vendor/overlay/`). Output goes to
  `../release/Hyperborea/apps/BluetoothPeripheralOverlay.apk`.

A prebuilt copy of the APK is checked in at `release/Hyperborea/apps/BluetoothPeripheralOverlay.apk`,
so you only need to run `build.sh` if you change the sources here.

## Deployment

`release/Hyperborea/deploy.sh` pushes the APK to `/vendor/overlay/BluetoothPeripheralOverlay.apk`
(via rooted ADB) and `chmod 644`s it; the Overlay Management Service picks it up on the next boot.
Only needed on devices whose BSP disables BLE peripheral mode.
