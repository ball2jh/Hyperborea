#!/bin/bash
#
# deploy.sh — Install Hyperborea as a privileged system app on a rooted NordicTrack console.
#
# Place Hyperborea*.apk (and any additional APKs) in apps/, then run:
#   ./deploy.sh
#

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
APPS_DIR="$SCRIPT_DIR/apps"

GREEN='\033[0;32m'; RED='\033[0;31m'; YELLOW='\033[1;33m'; CYAN='\033[0;36m'; BOLD='\033[1m'; DIM='\033[2m'; NC='\033[0m'
ok()      { echo -e "  ${GREEN}✓${NC} $*"; }
fail()    { echo -e "  ${RED}✗${NC} $*"; }
info()    { echo -e "  ${CYAN}→${NC} $*"; }
warn()    { echo -e "  ${YELLOW}!${NC} $*"; }
die()     { fail "$@"; exit 1; }
step()    { echo -e "\n${CYAN}[$1/$TOTAL_STEPS]${NC} ${BOLD}$2${NC}"; }

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

# =========================================================================
# Device Discovery
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
            adb wait-for-device
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
            adb wait-for-device
            ok "Connected to $DEVICE_IP"
            return
        fi
        warn "Couldn't connect. Check the IP and that ADB is enabled."
        echo ""
        echo -e "  ${DIM}Press Enter to try again, or Esc to exit.${NC}"
        wait_key
    done
}

# =========================================================================
# Wait for device to come back after reboot
# =========================================================================
wait_for_reboot() {
    local max_wait=${1:-300}
    local wait_start=$(date +%s)

    # Block until ADB reconnects to the device
    adb wait-for-device 2>/dev/null &
    local WAIT_PID=$!

    while kill -0 $WAIT_PID 2>/dev/null; do
        local elapsed=$(( $(date +%s) - wait_start ))
        if [ "$elapsed" -gt "$max_wait" ]; then
            kill $WAIT_PID 2>/dev/null || true
            stop_timer
            die "Timed out after ${max_wait}s. Try reconnecting manually."
        fi
        sleep 1
    done
    wait $WAIT_PID 2>/dev/null || true

    # Device is back, but boot may not be complete yet
    while true; do
        local elapsed=$(( $(date +%s) - wait_start ))
        if [ "$elapsed" -gt "$max_wait" ]; then
            stop_timer
            die "Timed out after ${max_wait}s waiting for boot."
        fi

        if adb shell "getprop sys.boot_completed" 2>/dev/null | grep -q "1"; then
            adb root >/dev/null 2>&1 || true
            adb wait-for-device
            break
        fi

        sleep 3
    done
}

TOTAL_STEPS=5

# =========================================================================
# Step 1: Pre-flight
# =========================================================================
command -v adb >/dev/null 2>&1 || die "ADB not found. Install Android platform-tools and add to PATH."
[ -d "$APPS_DIR" ] || die "apps/ folder not found. Place APKs in apps/ next to this script."

# Find Hyperborea APK
HYPERBOREA_APK=""
for f in "$APPS_DIR"/Hyperborea*.apk; do
    [ -f "$f" ] || continue
    HYPERBOREA_APK="$f"
    break
done
[ -n "$HYPERBOREA_APK" ] || die "No Hyperborea*.apk found in apps/. Place the APK there and try again."

# Collect other APKs
OTHER_APKS=()
for f in "$APPS_DIR"/*.apk; do
    [ -f "$f" ] || continue
    [ "$f" = "$HYPERBOREA_APK" ] && continue
    OTHER_APKS+=("$f")
done

ok "Found $(basename "$HYPERBOREA_APK")"
if [ ${#OTHER_APKS[@]} -gt 0 ]; then
    ok "${#OTHER_APKS[@]} additional APK(s) to install"
fi

# =========================================================================
# Step 2: Connect to device
# =========================================================================
discover_device

WHOAMI=$(adb shell "whoami" 2>/dev/null | tr -d '\r')
[ "$WHOAMI" = "root" ] || die "Failed to get root (got: $WHOAMI)"
ok "Root access confirmed"

# =========================================================================
# Step 3: Install Hyperborea as priv-app
# =========================================================================
step 2 "Install Hyperborea as system app"

info "Remounting /system read-write..."
adb shell "mount -o rw,remount /system" >/dev/null 2>&1
ok "System partition mounted read-write"

info "Pushing APK to /system/priv-app/Hyperborea/..."
adb shell "mkdir -p /system/priv-app/Hyperborea" >/dev/null 2>&1
adb push "$HYPERBOREA_APK" /system/priv-app/Hyperborea/Hyperborea.apk >/dev/null 2>&1
ok "APK pushed"

info "Setting permissions..."
adb shell "chmod 755 /system/priv-app/Hyperborea && chmod 644 /system/priv-app/Hyperborea/Hyperborea.apk" >/dev/null 2>&1
ok "Permissions set (755/644)"

info "Remounting /system read-only..."
adb shell "mount -o ro,remount /system" >/dev/null 2>&1
ok "System partition mounted read-only"

# =========================================================================
# Step 4: Reboot and wait
# =========================================================================
step 3 "Reboot device"

info "Rebooting (PackageManager scans /system/priv-app/ at boot)..."
REBOOT_START=$(date +%s)
adb reboot >/dev/null 2>&1

start_timer "Waiting for device..." "$REBOOT_START"
wait_for_reboot 300
stop_timer
ok "Device ready ($(fmt_time $(( $(date +%s) - REBOOT_START ))))"

# =========================================================================
# Step 5: Install other APKs
# =========================================================================
step 4 "Install additional apps"

if [ ${#OTHER_APKS[@]} -eq 0 ]; then
    info "No additional APKs to install"
else
    INSTALLED=0
    FAILED=0
    for APK in "${OTHER_APKS[@]}"; do
        NAME=$(basename "$APK" .apk)
        info "Installing $NAME..."
        if adb install -r "$APK" 2>&1 | grep -q "Success"; then
            ok "$NAME"
            INSTALLED=$((INSTALLED + 1))
        else
            fail "$NAME"
            FAILED=$((FAILED + 1))
        fi
    done

    echo ""
    if [ "$FAILED" -eq 0 ]; then
        ok "All $INSTALLED app(s) installed"
    else
        warn "$INSTALLED installed, $FAILED failed"
    fi
fi

# =========================================================================
# Step 6: Verify
# =========================================================================
step 5 "Verify"

VERIFY=$(adb shell "
    echo PATH=\$(pm path com.nettarion.hyperborea 2>/dev/null)
    echo FLAGS=\$(dumpsys package com.nettarion.hyperborea 2>/dev/null | grep 'pkgFlags=' | head -1)
    echo PRIVFLAGS=\$(dumpsys package com.nettarion.hyperborea 2>/dev/null | grep 'privateFlags=' | head -1)
" 2>/dev/null | tr -d '\r')

get() { echo "$VERIFY" | grep "^$1=" | cut -d= -f2-; }

PASS=0; TOTAL=0

# Check priv-app path
PKG_PATH=$(get PATH)
TOTAL=$((TOTAL + 1))
if echo "$PKG_PATH" | grep -q "/system/priv-app/"; then
    ok "Install path: $PKG_PATH"
    PASS=$((PASS + 1))
else
    fail "Install path: expected /system/priv-app/, got '$PKG_PATH'"
fi

# Check PRIVILEGED flag
PKG_PRIVFLAGS=$(get PRIVFLAGS)
TOTAL=$((TOTAL + 1))
if echo "$PKG_PRIVFLAGS" | grep -q "PRIVILEGED"; then
    ok "Privileged: yes"
    PASS=$((PASS + 1))
else
    fail "Privileged: not set (privateFlags='$PKG_PRIVFLAGS')"
fi

echo ""
if [ "$PASS" -eq "$TOTAL" ]; then
    echo -e "  ${GREEN}${BOLD}$PASS/$TOTAL checks passed${NC}"
else
    echo -e "  ${YELLOW}$PASS/$TOTAL checks passed${NC}"
fi

echo ""
echo -e "  ${GREEN}${BOLD}Deployment complete!${NC}"
echo ""
