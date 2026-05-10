#!/bin/bash
#
# Build the BLE-peripheral Runtime Resource Overlay (RRO) APK.
#
# Overrides config_bluetooth_le_peripheral_mode_supported = true in framework-res
# via a /vendor/overlay/ RRO. Some device BSPs set that boolean to false even
# though the Bluetooth hardware supports advertising; this overlay re-enables it
# so getBluetoothLeAdvertiser() returns non-null.
#
# Output: ../release/Hyperborea/apps/BluetoothPeripheralOverlay.apk
# (which is what release/Hyperborea/deploy.sh pushes to /vendor/overlay/).
#
# A prebuilt copy of the APK is already checked into that path; this script is
# only needed if you change the overlay sources here. The signature is
# irrelevant for overlays placed in /vendor/overlay/, so this signs with a
# throwaway key.
#
# Prerequisites: Android SDK with build-tools and at least one platform.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"

BUILD_TOOLS=$(ls -1d "$SDK/build-tools"/*/ 2>/dev/null | sort -V | tail -1)
AAPT2="$BUILD_TOOLS/aapt2"
ANDROID_JAR=$(ls -1 "$SDK"/platforms/*/android.jar 2>/dev/null | tail -1)

[ -x "$AAPT2" ] || { echo "aapt2 not found under $SDK/build-tools/"; exit 1; }
[ -f "$ANDROID_JAR" ] || { echo "android.jar not found under $SDK/platforms/"; exit 1; }

OUT="$SCRIPT_DIR/../release/Hyperborea/apps/BluetoothPeripheralOverlay.apk"
mkdir -p "$(dirname "$OUT")"

cd "$SCRIPT_DIR"
rm -rf compiled && mkdir compiled
"$AAPT2" compile res/values/bools.xml -o compiled/
"$AAPT2" link compiled/*.flat --manifest AndroidManifest.xml -I "$ANDROID_JAR" \
    --min-sdk-version 25 --target-sdk-version 25 -o "$OUT"

KEYSTORE="$(mktemp -d)/overlay.keystore"
keytool -genkeypair -keystore "$KEYSTORE" -alias overlay -keyalg RSA -keysize 2048 \
    -validity 10000 -storepass overlay123 -keypass overlay123 \
    -dname "CN=Hyperborea Overlay" 2>/dev/null
jarsigner -keystore "$KEYSTORE" -storepass overlay123 -keypass overlay123 "$OUT" overlay >/dev/null 2>&1

rm -rf compiled "$(dirname "$KEYSTORE")"
echo "Built: $OUT ($(wc -c < "$OUT") bytes)"
