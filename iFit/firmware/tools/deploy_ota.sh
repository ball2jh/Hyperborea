#!/bin/bash
#
# deploy_ota.sh — Deploy Hyperborea OTA to the NordicTrack S22i.
#
# The OTA firmware handles:
#   - Patched boot.img (root, SELinux permissive, ADB enabled)
#   - Stealth build.prop (type=user, debuggable=1, adb.secure=0)
#   - Platform key replacement (all system APKs re-signed)
#   - Hyperborea as system/priv-app with UID 1000
#   - ERU demoted to /system/app/, sharedUserId stripped, throwaway cert
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

TOOLS_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$(dirname "$(dirname "$TOOLS_DIR")")")"
RECOVERY_KEY="$PROJECT_DIR/iFit/firmware/keys/recovery_res_keys"
INSTALL_DEX="$TOOLS_DIR/install_ota.dex"

GREEN='\033[0;32m'; RED='\033[0;31m'; YELLOW='\033[1;33m'; CYAN='\033[0;36m'; BOLD='\033[1m'; DIM='\033[2m'; NC='\033[0m'
ok()      { echo -e "  ${GREEN}✓${NC} $*"; }
fail()    { echo -e "  ${RED}✗${NC} $*"; }
info()    { echo -e "  ${CYAN}→${NC} $*"; }
warn()    { echo -e "  ${YELLOW}!${NC} $*"; }
die()     { fail "$@"; exit 1; }
step()    { echo -e "\n${CYAN}[$1/$TOTAL_STEPS]${NC} ${BOLD}$2${NC}"; }
substep() { echo -e "\n  ${CYAN}[$1]${NC} $2"; }

fmt_time() { printf "%d:%02d" $(($1 / 60)) $(($1 % 60)); }

# Read remaining escape sequence bytes after \x1b
_read_esc_seq() {
    local _saved
    _saved=$(stty -g)
    stty -icanon -echo min 0 time 1
    _seq=$(dd bs=4 count=1 2>/dev/null)
    stty "$_saved"
}

# Wait for Enter; exit on Escape
wait_key() {
    while true; do
        IFS= read -rsn1 key || true
        if [ -z "$key" ]; then return; fi
        if [[ "$key" == $'\e' ]]; then _read_esc_seq; [ -z "$_seq" ] && exit 0; fi
    done
}

# Read a line with Escape-to-exit and backspace support
read_input() {
    INPUT=""
    while true; do
        IFS= read -rsn1 ch || true
        if [ -z "$ch" ]; then
            echo ""
            return
        elif [[ "$ch" == $'\e' ]]; then
            _read_esc_seq
            [ -z "$_seq" ] && exit 0
        elif [[ "$ch" == $'\x7f' || "$ch" == $'\b' ]]; then
            if [ -n "$INPUT" ]; then
                INPUT="${INPUT%?}"
                printf '\b \b'
            fi
        else
            INPUT="${INPUT}${ch}"
            printf '%s' "$ch"
        fi
    done
}

ALLOW_REFRESH=0

choose_option() {
    local options=("$@")
    local count=${#options[@]}
    local cursor=0

    local hint="↑↓ navigate  •  Enter select  •  Esc cancel"
    [ "$ALLOW_REFRESH" -eq 1 ] && hint="r refresh  •  $hint"
    echo -e "    ${DIM}${hint}${NC}"
    echo ""

    _draw_choices() {
        for ((i=0; i<count; i++)); do
            local arrow="  "
            [ "$i" -eq "$cursor" ] && arrow="${CYAN}›${NC} "
            printf "\033[2K    %b %s\n" "$arrow" "${options[$i]}"
        done
    }

    _draw_choices

    while true; do
        IFS= read -rsn1 key
        case "$key" in
            $'\x1b')
                _read_esc_seq
                if [ -z "$_seq" ]; then echo ""; exit 0; fi
                case "$_seq" in
                    '[A') [ "$cursor" -gt 0 ] && cursor=$((cursor - 1)) ;;
                    '[B') [ "$cursor" -lt $((count - 1)) ] && cursor=$((cursor + 1)) ;;
                esac
                ;;
            'r') [ "$ALLOW_REFRESH" -eq 1 ] && { CHOSEN=-2; return; } ;;
            '') break ;;
        esac
        printf "\033[${count}A"
        _draw_choices
    done

    CHOSEN=$cursor
}

# =========================================================================
# Timer (background spinner with elapsed time)
# =========================================================================
TIMER_PID=0
start_timer() {
    local label="$1"
    local start="$2"
    ( while true; do
        printf "\r  \033[0;36m→\033[0m %s %s  " "$label" "$(fmt_time $(( $(date +%s) - start )))"
        sleep 1
    done ) &
    TIMER_PID=$!
}

stop_timer() {
    if [ "$TIMER_PID" -ne 0 ]; then
        kill $TIMER_PID 2>/dev/null || true
        wait $TIMER_PID 2>/dev/null || true
    fi
    TIMER_PID=0
    printf "\r\033[2K"
}

cleanup() { stop_timer; }
trap cleanup EXIT

TOTAL_STEPS=5

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

# Pre-flight checks
command -v adb >/dev/null 2>&1 || die "ADB not found"
[ -f "$INSTALL_DEX" ] || die "Missing $INSTALL_DEX"
[ -f "$RECOVERY_KEY" ] || die "Missing $RECOVERY_KEY"

# =========================================================================
# Step 1: Device Discovery
# =========================================================================
discover_device() {
    while true; do
        step 1 "Connect to device"

        local DEVICES OPTIONS=() SERIALS=()
        DEVICES=$(adb devices 2>/dev/null | grep -w "device$" || true)

        if [ -n "$DEVICES" ]; then
            while IFS= read -r line; do
                local serial
                serial=$(echo "$line" | awk '{print $1}')
                SERIALS+=("$serial")
                OPTIONS+=("$serial")
            done <<< "$DEVICES"
        fi

        OPTIONS+=("Enter IP address...")

        echo ""
        ALLOW_REFRESH=1
        choose_option "${OPTIONS[@]}"
        ALLOW_REFRESH=0

        if [ "$CHOSEN" -eq -2 ]; then continue; fi

        local device_count=${#SERIALS[@]}

        # Selected a discovered device
        if [ "$CHOSEN" -lt "$device_count" ]; then
            export ANDROID_SERIAL="${SERIALS[$CHOSEN]}"
            echo ""
            info "Connecting..."
            adb root >/dev/null 2>&1 || true
            sleep 2
            ok "Connected to ${SERIALS[$CHOSEN]}"
            return
        fi

        # Enter IP address
        echo ""
        printf "  Enter device IP (e.g. 192.168.1.100): "
        read_input
        DEVICE_IP="$INPUT"
        [ -n "$DEVICE_IP" ] || continue
        [[ "$DEVICE_IP" == *:* ]] || DEVICE_IP="${DEVICE_IP}:5555"
        info "Connecting to $DEVICE_IP..."
        adb connect "$DEVICE_IP" >/dev/null 2>&1 || true
        sleep 2
        if adb -s "$DEVICE_IP" shell true 2>/dev/null; then
            export ANDROID_SERIAL="$DEVICE_IP"
            adb root >/dev/null 2>&1 || true
            sleep 2
            ok "Connected to $DEVICE_IP"
            return
        fi
        warn "Couldn't connect. Check the IP and that ADB is enabled."
        echo ""
        echo -e "  ${DIM}Press Enter to try again, or Esc to exit.${NC}"
        wait_key
    done
}

discover_device

DEVICE_INFO=$(adb shell "whoami; getprop ro.build.display.id; getenforce" 2>/dev/null | tr -d '\r')
WHOAMI=$(echo "$DEVICE_INFO" | sed -n 1p)
CURRENT_FW=$(echo "$DEVICE_INFO" | sed -n 2p)
SELINUX=$(echo "$DEVICE_INFO" | sed -n 3p)

[ "$WHOAMI" = "root" ] || die "Failed to get root (got: $WHOAMI)"
ok "Root access confirmed ($CURRENT_FW, $SELINUX)"

echo ""
echo "========================================"
echo "  NordicTrack S22i — Hyperborea OTA"
echo -e "  Device:   ${ANDROID_SERIAL} — ${CURRENT_FW}"
echo "  Firmware: $(basename "$OTA_ZIP") (${OTA_MB} MB)"
echo "========================================"

# =========================================================================
# Step 2: Prepare device
# =========================================================================
step 2 "Prepare device"

# --- Push tools ---
substep "2.1" "Pushing deployment tools"

adb push "$INSTALL_DEX" /data/local/tmp/install_ota.dex >/dev/null 2>&1
ok "Pushed install_ota.dex"

# --- Patch recovery signing key ---
substep "2.2" "Patching recovery signing key"

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
" >/dev/null 2>&1
ok "Recovery signing key patched"

# --- Disable install-recovery.sh on current system ---
substep "2.3" "Disabling install-recovery.sh on current system"

HAS_APPLYPATCH=$(adb shell "grep -c applypatch /system/bin/install-recovery.sh 2>/dev/null || echo 0" | tr -d '\r')

if [ "$HAS_APPLYPATCH" != "0" ]; then
    adb shell "mount -o remount,rw /system 2>/dev/null; printf '#!/system/bin/sh\nexit 0\n' > /system/bin/install-recovery.sh; chmod 755 /system/bin/install-recovery.sh"
    ok "Disabled stock install-recovery.sh"
else
    ok "Already modified"
fi

# =========================================================================
# Step 3: Push and install firmware
# =========================================================================
step 3 "Install firmware"

OTA_DEVICE_PATH="/data/local/tmp/ota_install.zip"

substep "3.1" "Pushing firmware (~2 min)"

PUSH_START=$(date +%s)
adb push "$OTA_ZIP" "$OTA_DEVICE_PATH" >/dev/null 2>&1 &
PUSH_PID=$!
while kill -0 $PUSH_PID 2>/dev/null; do
    PUSH_ELAPSED=$(( $(date +%s) - PUSH_START ))
    printf "\r  ${CYAN}→${NC} Pushing firmware (${OTA_MB} MB)... %s  " "$(fmt_time $PUSH_ELAPSED)"
    sleep 1
done
wait $PUSH_PID
PUSH_ELAPSED=$(( $(date +%s) - PUSH_START ))
printf "\r\033[2K"
ok "Firmware pushed ($(fmt_time $PUSH_ELAPSED))"

substep "3.2" "Installing firmware (~4 min)"

info "Triggering install (device will reboot into recovery)..."
EXIT_CODE=0
adb shell "CLASSPATH=/data/local/tmp/install_ota.dex app_process / InstallOTA $OTA_DEVICE_PATH" >/dev/null 2>&1 || EXIT_CODE=$?

if [ "$EXIT_CODE" -eq 255 ]; then
    ok "Install triggered"
else
    warn "Unexpected exit code: $EXIT_CODE"
fi

# =========================================================================
# Step 4: Wait for reboot
# =========================================================================
step 4 "Waiting for reboot"

REBOOT_START=$(date +%s)

sleep 15  # Let device go offline

DEVICE_TARGET="${ANDROID_SERIAL:-}"
if [ -n "$DEVICE_TARGET" ]; then
    adb disconnect "$DEVICE_TARGET" >/dev/null 2>&1 || true
fi
sleep 2

start_timer "Waiting for device..." "$REBOOT_START"

MAX_WAIT=420
while true; do
    ELAPSED=$(( $(date +%s) - REBOOT_START ))
    if [ "$ELAPSED" -gt "$MAX_WAIT" ]; then
        stop_timer
        die "Timed out after ${MAX_WAIT}s. Try reconnecting manually."
    fi

    if [ -n "$DEVICE_TARGET" ] && echo "$DEVICE_TARGET" | grep -q ":"; then
        adb disconnect "$DEVICE_TARGET" >/dev/null 2>&1 || true
        sleep 1
        adb connect "$DEVICE_TARGET" >/dev/null 2>&1 || true
        sleep 2
    fi

    if adb shell "getprop sys.boot_completed" 2>/dev/null | grep -q "1"; then
        adb root >/dev/null 2>&1 || true
        sleep 2
        break
    fi

    sleep 5
done

stop_timer
ok "Device ready ($(fmt_time $(( $(date +%s) - REBOOT_START ))))"

# =========================================================================
# Step 5: Verify
# =========================================================================
step 5 "Verify"

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
    echo LAUNCHER=\$(pm list packages -e 2>/dev/null | grep -c com.android.launcher3)
    echo SETTINGS_APP=\$(pm list packages -e 2>/dev/null | grep -c com.android.settings)
    echo HYPERBOREA=\$(pm list packages 2>/dev/null | grep -c com.nettarion.hyperborea)
    echo HYPERBOREA_UID=\$(dumpsys package com.nettarion.hyperborea 2>/dev/null | grep 'userId=' | head -1 | tr -dc '0-9')
    echo HYPERBOREA_FLAGS=\$(dumpsys package com.nettarion.hyperborea 2>/dev/null | grep 'pkgFlags=' | head -1)
    echo HYPERBOREA_PRIVFLAGS=\$(dumpsys package com.nettarion.hyperborea 2>/dev/null | grep 'privateFlags=' | head -1)
    echo ERU=\$(pm list packages 2>/dev/null | grep -c com.ifit.eru)
    echo ERU_FLAGS=\$(dumpsys package com.ifit.eru 2>/dev/null | grep 'pkgFlags=' | head -1)
    echo ERU_PRIVFLAGS=\$(dumpsys package com.ifit.eru 2>/dev/null | grep 'privateFlags=' | head -1)
    echo ERU_UID=\$(dumpsys package com.ifit.eru 2>/dev/null | grep 'userId=' | head -1 | tr -dc '0-9')
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
check "ERU present"        "1"          "$(get ERU)"

# ERU flags check (SYSTEM in pkgFlags, no PRIVILEGED in privateFlags)
ERU_FLAGS=$(get ERU_FLAGS)
ERU_PRIVFLAGS=$(get ERU_PRIVFLAGS)
TOTAL=$((TOTAL + 1))
if echo "$ERU_FLAGS" | grep -q "SYSTEM" && ! echo "$ERU_PRIVFLAGS" | grep -q "PRIVILEGED"; then
    ok "ERU flags: SYSTEM only (no PRIVILEGED)"; PASS=$((PASS + 1))
else
    fail "ERU flags: expected SYSTEM without PRIVILEGED, got pkgFlags='$ERU_FLAGS' privateFlags='$ERU_PRIVFLAGS'"
fi

# ERU UID should NOT be 1000 (system)
ERU_UID=$(get ERU_UID)
TOTAL=$((TOTAL + 1))
if [ -n "$ERU_UID" ] && [ "$ERU_UID" != "1000" ]; then
    ok "ERU UID: $ERU_UID (not system)"; PASS=$((PASS + 1))
else
    fail "ERU UID: expected non-1000, got '$ERU_UID'"
fi

# Hyperborea flags check (SYSTEM in pkgFlags, PRIVILEGED in privateFlags on API 25)
HYPERBOREA_FLAGS=$(get HYPERBOREA_FLAGS)
HYPERBOREA_PRIVFLAGS=$(get HYPERBOREA_PRIVFLAGS)
TOTAL=$((TOTAL + 1))
if echo "$HYPERBOREA_FLAGS" | grep -q "SYSTEM" && echo "$HYPERBOREA_PRIVFLAGS" | grep -q "PRIVILEGED"; then
    ok "Hyperborea flags: SYSTEM PRIVILEGED"; PASS=$((PASS + 1))
else
    fail "Hyperborea flags: expected SYSTEM+PRIVILEGED, got pkgFlags='$HYPERBOREA_FLAGS' privateFlags='$HYPERBOREA_PRIVFLAGS'"
fi

check "Hyperborea UID"     "1000"       "$(get HYPERBOREA_UID)"

USB=$(get USB)
TOTAL=$((TOTAL + 1))
if echo "$USB" | grep -q "adb"; then ok "USB config: $USB"; PASS=$((PASS + 1))
else fail "USB config: expected 'adb', got '$USB'"; fi

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
    echo -e "  ${GREEN}${BOLD}ALL $TOTAL CHECKS PASSED${NC}"
else
    echo -e "  ${YELLOW}$PASS / $TOTAL checks passed${NC}"
fi
echo "========================================"
echo ""
