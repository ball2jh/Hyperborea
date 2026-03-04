#!/bin/bash
#
# deploy_ota.sh — Deploy Hyperborea OTA to the NordicTrack S22i.
#
# The OTA firmware handles:
#   - Patched boot.img (root, SELinux permissive, ADB enabled)
#   - Stealth build.prop (type=user, debuggable=1, adb.secure=0)
#   - Platform key replacement (all system APKs re-signed)
#   - Hyperborea as system/priv-app with UID 1000
#   - ERU removed entirely
#   - install-recovery.sh boot script (safety net)
#   - ota_postinstall (.wolfDev, immutable update.zip, IFW rules)
#   - Our signing key in otacerts.zip
#
# This script handles what CAN'T be baked into the OTA:
#   1. Patch recovery signing key (recovery partition not in updater-script)
#   2. Disable install-recovery.sh on CURRENT system (prevents stock recovery
#      restore between now and OTA reboot)
#   3. Push OTA and trigger install via InstallOTA DEX
#   4. Wait for reboot and verify
#
# Usage:
#   ./iFit/firmware/tools/deploy_ota.sh <ota-zip>
#

set -euo pipefail

DEVICE_IP="192.168.1.177:5555"
TOOLS_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$(dirname "$(dirname "$TOOLS_DIR")")")"
RECOVERY_KEY="$PROJECT_DIR/iFit/firmware/keys/recovery_res_keys"
INSTALL_DEX="$TOOLS_DIR/install_ota.dex"

GREEN='\033[0;32m'; RED='\033[0;31m'; YELLOW='\033[1;33m'; CYAN='\033[0;36m'; NC='\033[0m'
ok()   { echo -e "  ${GREEN}✓${NC} $*"; }
fail() { echo -e "  ${RED}✗${NC} $*"; }
info() { echo -e "  ${CYAN}→${NC} $*"; }
warn() { echo -e "  ${YELLOW}!${NC} $*"; }
die()  { fail "$@"; exit 1; }
step() { echo -e "\n${CYAN}[$1/$TOTAL_STEPS]${NC} $2"; }

TOTAL_STEPS=6

# --- Argument check ---
if [ $# -lt 1 ]; then
    echo "Usage: $0 <ota-zip>"
    echo "  $0 iFit/firmware/repack/MGA1_20210901/output/MGA1_20210901-signed.zip"
    exit 1
fi

OTA_ZIP="$1"
[ -f "$OTA_ZIP" ] || die "OTA file not found: $OTA_ZIP"
OTA_SIZE=$(stat -c%s "$OTA_ZIP" 2>/dev/null || stat -f%z "$OTA_ZIP" 2>/dev/null)
OTA_MB=$((OTA_SIZE / 1048576))

echo ""
echo "========================================"
echo "  NordicTrack S22i — Hyperborea OTA"
echo "  OTA: $(basename "$OTA_ZIP") (${OTA_MB} MB)"
echo "========================================"

# Step 1: Connect and get root
step 1 "Connecting to device"

adb connect "$DEVICE_IP" >/dev/null 2>&1 || true
sleep 1
adb shell true 2>/dev/null || die "Cannot connect to $DEVICE_IP"
adb root >/dev/null 2>&1
sleep 2

DEVICE_INFO=$(adb shell "whoami; getprop ro.build.display.id; getenforce" 2>/dev/null | tr -d '\r')
WHOAMI=$(echo "$DEVICE_INFO" | sed -n 1p)
CURRENT_FW=$(echo "$DEVICE_INFO" | sed -n 2p)
SELINUX=$(echo "$DEVICE_INFO" | sed -n 3p)

[ "$WHOAMI" = "root" ] || die "Failed to get root (got: $WHOAMI)"
ok "Connected — $CURRENT_FW, $SELINUX, root"

# Step 2: Push tools
step 2 "Pushing deployment tools"

[ -f "$INSTALL_DEX" ] || die "Missing $INSTALL_DEX"

adb push "$INSTALL_DEX" /data/local/tmp/install_ota.dex >/dev/null 2>&1
ok "Pushed install_ota.dex"

# Step 3: Patch recovery signing key
step 3 "Patching recovery signing key"

[ -f "$RECOVERY_KEY" ] || die "Missing $RECOVERY_KEY"

adb push "$RECOVERY_KEY" /data/local/tmp/recovery_res_keys >/dev/null 2>&1

adb shell "
    mkdir -p /data/local/tmp/rec_mount /data/local/tmp/rec_fs
    mount -t ext4 /dev/block/mmcblk0p6 /data/local/tmp/rec_mount 2>/dev/null
    cd /data/local/tmp && gzip -d -c rec_mount/ramdisk-recovery.img > rec.cpio
    cd rec_fs && cpio -id < ../rec.cpio 2>/dev/null
    grep -v '^#' /data/local/tmp/recovery_res_keys > /data/local/tmp/rec_fs/res/keys
    cd /data/local/tmp/rec_fs && find . | cpio -o -H newc 2>/dev/null | gzip > /data/local/tmp/rec_patched.img
    cp /data/local/tmp/rec_patched.img /data/local/tmp/rec_mount/ramdisk-recovery.img
    sync
    umount /data/local/tmp/rec_mount 2>/dev/null
    rm -rf /data/local/tmp/rec_mount /data/local/tmp/rec_fs /data/local/tmp/rec.cpio /data/local/tmp/rec_patched.img /data/local/tmp/recovery_res_keys
" 2>/dev/null
ok "Recovery signing key patched"

# Step 4: Disable install-recovery.sh on current system
step 4 "Disabling install-recovery.sh on current system"

HAS_APPLYPATCH=$(adb shell "grep -c applypatch /system/bin/install-recovery.sh 2>/dev/null || echo 0" | tr -d '\r')

if [ "$HAS_APPLYPATCH" != "0" ]; then
    adb shell "mount -o remount,rw /system 2>/dev/null; printf '#!/system/bin/sh\nexit 0\n' > /system/bin/install-recovery.sh; chmod 755 /system/bin/install-recovery.sh"
    ok "Disabled stock install-recovery.sh"
else
    ok "Already modified"
fi

# Step 5: Push OTA and install
step 5 "Pushing OTA and installing"

OTA_DEVICE_PATH="/data/local/tmp/ota_install.zip"

info "Pushing OTA (${OTA_MB} MB)..."
adb push "$OTA_ZIP" "$OTA_DEVICE_PATH" 2>&1 | tail -1
ok "OTA pushed to $OTA_DEVICE_PATH"

info "Triggering install (uncrypt -> BCB -> reboot -> recovery)..."
EXIT_CODE=0
adb shell "CLASSPATH=/data/local/tmp/install_ota.dex app_process / InstallOTA $OTA_DEVICE_PATH" 2>&1 || EXIT_CODE=$?

if [ "$EXIT_CODE" -eq 255 ]; then
    ok "Install triggered (exit 255 = reboot in progress)"
else
    warn "Unexpected exit code: $EXIT_CODE"
fi

# Step 6: Wait for install and verify
step 6 "Waiting for OTA install and reboot"

info "Waiting up to 7 minutes..."

WAIT_START=$(date +%s)
MAX_WAIT=420

sleep 15  # Let device go offline

adb disconnect "$DEVICE_IP" >/dev/null 2>&1 || true
sleep 2

while true; do
    ELAPSED=$(( $(date +%s) - WAIT_START ))
    if [ "$ELAPSED" -gt "$MAX_WAIT" ]; then
        die "Timed out after ${MAX_WAIT}s. Try: adb connect $DEVICE_IP && adb root"
    fi

    adb disconnect "$DEVICE_IP" >/dev/null 2>&1 || true
    sleep 1
    adb connect "$DEVICE_IP" >/dev/null 2>&1 || true
    sleep 2
    if adb shell "getprop sys.boot_completed" 2>/dev/null | grep -q "1"; then
        adb root >/dev/null 2>&1
        sleep 5
        break
    fi

    printf "\r  → %ds / %ds" "$ELAPSED" "$MAX_WAIT"
    sleep 5
done
echo ""
ok "Device back online"

info "Running verification checks..."

VERIFY=$(adb shell "
    echo FW=\$(getprop ro.build.display.id)
    echo ROOT=\$(whoami)
    echo SELINUX=\$(getenforce)
    echo TYPE=\$(getprop ro.build.type)
    echo DEBUG=\$(getprop ro.debuggable)
    echo ADBSEC=\$(getprop ro.adb.secure)
    echo USB=\$(getprop persist.sys.usb.config)
    echo WOLFDEV=\$(test -f /sdcard/.wolfDev && echo OK || echo MISSING)
    echo OTA=\$(echo x > /data/update.zip 2>&1 && echo WRITABLE || echo BLOCKED)
    echo IMMERSIVE=\$(settings get global policy_control)
    echo NONMARKET=\$(settings get secure install_non_market_apps)
    echo LAUNCHER=\$(pm list packages -e 2>/dev/null | grep -c com.android.launcher3)
    echo SETTINGS_APP=\$(pm list packages -e 2>/dev/null | grep -c com.android.settings)
    echo HYPERBOREA=\$(pm list packages 2>/dev/null | grep -c com.nettarion.hyperborea)
    echo HYPERBOREA_UID=\$(dumpsys package com.nettarion.hyperborea 2>/dev/null | grep 'userId=' | head -1 | tr -dc '0-9')
    echo HYPERBOREA_FLAGS=\$(dumpsys package com.nettarion.hyperborea 2>/dev/null | grep 'pkgFlags=' | head -1)
    echo ERU=\$(pm list packages 2>/dev/null | grep -c com.ifit.eru)
    echo IFIT_COUNT=\$(pm list packages 2>/dev/null | grep -c com.ifit)
" 2>/dev/null | tr -d '\r')

get() { echo "$VERIFY" | grep "^$1=" | cut -d= -f2-; }

PASS=0; TOTAL=0
check() {
    TOTAL=$((TOTAL + 1))
    if [ "$2" = "$3" ]; then ok "$1: $3"; PASS=$((PASS + 1))
    else fail "$1: expected '$2', got '$3'"; fi
}

check "Root"              "root"       "$(get ROOT)"
check "SELinux"           "Permissive" "$(get SELINUX)"
check "Build type"        "user"       "$(get TYPE)"
check "ro.debuggable"     "1"          "$(get DEBUG)"
check "ro.adb.secure"     "0"          "$(get ADBSEC)"
check ".wolfDev"          "OK"         "$(get WOLFDEV)"
check "OTA protection"    "BLOCKED"    "$(get OTA)"
check "Hyperborea installed" "1"       "$(get HYPERBOREA)"
check "Hyperborea UID"    "1000"       "$(get HYPERBOREA_UID)"
check "ERU removed"        "0"          "$(get ERU)"

# Hyperborea flags check (should contain SYSTEM and PRIVILEGED)
HYPERBOREA_FLAGS=$(get HYPERBOREA_FLAGS)
TOTAL=$((TOTAL + 1))
if echo "$HYPERBOREA_FLAGS" | grep -q "SYSTEM" && echo "$HYPERBOREA_FLAGS" | grep -q "PRIVILEGED"; then
    ok "Hyperborea flags: SYSTEM PRIVILEGED"; PASS=$((PASS + 1))
else
    fail "Hyperborea flags: expected SYSTEM PRIVILEGED, got '$HYPERBOREA_FLAGS'"
fi

USB=$(get USB)
TOTAL=$((TOTAL + 1))
if echo "$USB" | grep -q "adb"; then ok "USB config: $USB"; PASS=$((PASS + 1))
else fail "USB config: expected 'adb', got '$USB'"; fi

IFIT_COUNT=$(get IFIT_COUNT)
TOTAL=$((TOTAL + 1))
if [ "$IFIT_COUNT" = "0" ]; then
    ok "No iFit packages installed"; PASS=$((PASS + 1))
else
    warn "iFit packages still present: $IFIT_COUNT"
fi

IMMERSIVE=$(get IMMERSIVE)
TOTAL=$((TOTAL + 1))
if [ "$IMMERSIVE" = "null" ] || [ -z "$IMMERSIVE" ]; then
    ok "Immersive: cleared"; PASS=$((PASS + 1))
else warn "Immersive: '$IMMERSIVE' (boot script may still be running)"; fi

LAUNCHER=$(get LAUNCHER)
TOTAL=$((TOTAL + 1))
if [ "$LAUNCHER" = "1" ]; then
    ok "Stock launcher: enabled"; PASS=$((PASS + 1))
else warn "Stock launcher: disabled (boot script may still be running)"; fi

FW=$(get FW)
echo ""
echo "========================================"
echo "  Firmware: $FW"
if [ "$PASS" -eq "$TOTAL" ]; then
    echo -e "  ${GREEN}ALL $TOTAL CHECKS PASSED${NC}"
else
    echo -e "  ${YELLOW}$PASS / $TOTAL checks passed${NC}"
fi
echo "========================================"
echo ""
