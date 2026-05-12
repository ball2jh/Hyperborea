#!/bin/bash
#
# deploy.sh — Install Hyperborea on a NordicTrack/iFit console over ADB. No root required.
#
# Hyperborea is installed as a regular APK via `adb install`; iFit's competing
# apps are silenced with `adb shell pm disable-user --user 0 …`, which works as
# the unprivileged `shell` user on any console with ADB enabled.
#
# Place Hyperborea*.apk (and any additional APKs) in apps/, then run:
#   ./deploy.sh
#
# Privileged install to /system/priv-app/ (the old flow) lives on the
# `archive/priv-app-deployment` branch if you need to revive it.
#

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
APPS_DIR="$SCRIPT_DIR/apps"

VERBOSE=0
for arg in "$@"; do
    case "$arg" in
        -v|--verbose) VERBOSE=1 ;;
        -h|--help)
            echo "Usage: $(basename "$0") [-v|--verbose]"
            echo "  Place Hyperborea*.apk (and any extra APKs) in apps/, then run this script."
            echo "  -v, --verbose   echo full adb output for each install"
            exit 0
            ;;
        *) echo "Unknown option: $arg (try -h)" >&2; exit 2 ;;
    esac
done

GREEN='\033[0;32m'; RED='\033[0;31m'; YELLOW='\033[1;33m'; CYAN='\033[0;36m'; BOLD='\033[1m'; DIM='\033[2m'; NC='\033[0m'
ok()      { echo -e "  ${GREEN}✓${NC} $*"; }
fail()    { echo -e "  ${RED}✗${NC} $*"; }
info()    { echo -e "  ${CYAN}→${NC} $*"; }
warn()    { echo -e "  ${YELLOW}!${NC} $*"; }
die()     { fail "$@"; exit 1; }
step()    { echo -e "\n${CYAN}[$1/$TOTAL_STEPS]${NC} ${BOLD}$2${NC}"; }
indent()  { sed 's/^/      /'; }

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

# Wizard-style multi-step configuration.
# Uses global arrays: WIZ_SECTIONS, WIZ_LABELS, WIZ_STATES, WIZ_SEC_START, WIZ_SEC_COUNT
run_wizard() {
    local num_sections=${#WIZ_SECTIONS[@]}
    local current_step=0

    while [ "$current_step" -lt "$num_sections" ]; do
        local sec_start=${WIZ_SEC_START[$current_step]}
        local sec_count=${WIZ_SEC_COUNT[$current_step]}
        local is_done=0
        [ "${WIZ_SECTIONS[$current_step]}" = "Done" ] && is_done=1
        local cursor=0

        # Build summary lines for Done step
        local summary_lines=()
        if [ "$is_done" -eq 1 ]; then
            for ((s=0; s<num_sections-1; s++)); do
                summary_lines+=("${BOLD}${WIZ_SECTIONS[$s]}${NC}")
                local ss=${WIZ_SEC_START[$s]}
                local sc=${WIZ_SEC_COUNT[$s]}
                for ((j=0; j<sc; j++)); do
                    local idx=$((ss + j))
                    if [ "${WIZ_STATES[$idx]}" -eq 1 ]; then
                        summary_lines+=("  ${GREEN}✓${NC} ${WIZ_LABELS[$idx]}")
                    else
                        summary_lines+=("  ${DIM}✗ ${WIZ_LABELS[$idx]}${NC}")
                    fi
                done
            done
        fi

        # content = items + blank line + action button
        local content_lines=$((sec_count + 2))
        [ "$is_done" -eq 1 ] && content_lines=$((${#summary_lines[@]} + 2))
        local total_lines=$((4 + content_lines))

        _wiz_tab_bar() {
            local bar="  ←"
            for ((s=0; s<num_sections; s++)); do
                if [ "$s" -lt "$current_step" ]; then
                    bar+="  ${GREEN}✓${NC} ${WIZ_SECTIONS[$s]}"
                elif [ "$s" -eq "$current_step" ]; then
                    bar+="  ${CYAN}●${NC} ${BOLD}${WIZ_SECTIONS[$s]}${NC}"
                else
                    bar+="  ${DIM}○ ${WIZ_SECTIONS[$s]}${NC}"
                fi
            done
            bar+="  →"
            printf "\033[2K%b\n" "$bar"
        }

        _wiz_content() {
            if [ "$is_done" -eq 1 ]; then
                for ((i=0; i<${#summary_lines[@]}; i++)); do
                    printf "\033[2K    %b\n" "${summary_lines[$i]}"
                done
            else
                for ((i=0; i<sec_count; i++)); do
                    local idx=$((sec_start + i))
                    local arrow="  "
                    [ "$i" -eq "$cursor" ] && arrow="${CYAN}›${NC} "
                    if [ "${WIZ_STATES[$idx]}" -eq 1 ]; then
                        printf "\033[2K    %b ${GREEN}✓${NC} %s\n" "$arrow" "${WIZ_LABELS[$idx]}"
                    else
                        printf "\033[2K    %b ${DIM}✗ %s${NC}\n" "$arrow" "${WIZ_LABELS[$idx]}"
                    fi
                done
            fi
            echo ""
            local btn_label="Continue →"
            [ "$is_done" -eq 1 ] && btn_label="Confirm"
            local arrow="  "
            [ "$cursor" -eq "$sec_count" ] && arrow="${CYAN}›${NC} "
            printf "\033[2K    %b ${BOLD}%s${NC}\n" "$arrow" "$btn_label"
        }

        _wiz_draw() {
            _wiz_tab_bar
            echo ""
            local hint="↑↓ navigate  •  Space toggle  •  Enter continue  •  Esc cancel"
            [ "$is_done" -eq 1 ] && hint="Enter confirm  •  Esc cancel"
            printf "\033[2K    ${DIM}%s${NC}\n" "$hint"
            echo ""
            _wiz_content
            printf "\033[J"
        }

        _wiz_draw

        while true; do
            IFS= read -rsn1 key
            case "$key" in
                $'\x1b')
                    _read_esc_seq
                    if [ -z "$_seq" ]; then echo ""; exit 0; fi
                    case "$_seq" in
                        '[A') [ "$cursor" -gt 0 ] && cursor=$((cursor - 1)) ;;
                        '[B') [ "$cursor" -lt "$sec_count" ] && cursor=$((cursor + 1)) ;;
                    esac
                    ;;
                ' ')
                    if [ "$cursor" -eq "$sec_count" ]; then
                        break
                    elif [ "$sec_count" -gt 0 ]; then
                        local idx=$((sec_start + cursor))
                        if [ "${WIZ_STATES[$idx]}" -eq 1 ]; then
                            WIZ_STATES[$idx]=0
                        else
                            WIZ_STATES[$idx]=1
                        fi
                    fi
                    ;;
                '') break ;;
            esac
            if [ "$content_lines" -gt 0 ]; then
                printf "\033[${content_lines}A"
                _wiz_content
            fi
        done

        # Clear wizard area and reposition for next step
        printf "\033[${total_lines}A"
        for ((l=0; l<total_lines; l++)); do printf "\033[2K\n"; done
        printf "\033[${total_lines}A"

        current_step=$((current_step + 1))
    done
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
# Helpers
# =========================================================================
is_ip_connection() {
    [[ "${ANDROID_SERIAL:-}" == *:* ]]
}

# Wait for ADB to reconnect (handles both USB and IP). No root escalation —
# `adb root` was the original behaviour but is unsupported on production-build
# firmware, which is exactly the case this script targets.
adb_reconnect_wait() {
    if is_ip_connection; then
        sleep 2
        adb connect "$ANDROID_SERIAL" >/dev/null 2>&1 || true
        sleep 1
    fi
    adb wait-for-device 2>/dev/null
}

# Install one APK via `adb install`, surfacing the real failure reason.
# Returns 0 on success. $INSTALL_FLAGS is set after device discovery.
install_apk() {
    local apk="$1" name out
    name=$(basename "$apk" .apk)
    # `adb install` returns non-zero on failure on recent platform-tools, but
    # older builds print "Failure [...]" with exit 0 — check both.
    if out=$(adb install $INSTALL_FLAGS "$apk" 2>&1) && printf '%s\n' "$out" | grep -q "Success"; then
        [ "$VERBOSE" -eq 1 ] && printf '%s\n' "$out" | indent
        return 0
    fi
    # Always echo what adb actually said — the failure is otherwise opaque.
    printf '%s\n' "$out" | indent
    case "$out" in
        *INSTALL_FAILED_UPDATE_INCOMPATIBLE*|*"signatures do not match"*)
            warn "A different build of $name is already installed (signed with another key)."
            if [ "$name" = "Hyperborea" ] || [[ "$name" == Hyperborea* ]]; then
                warn "If it's the old /system/priv-app build (the pre-1.2 OTA flow), remove it with root ADB:"
                echo -e "      ${DIM}adb root && adb remount${NC}"
                echo -e "      ${DIM}adb shell rm -rf /system/priv-app/Hyperborea${NC}"
                echo -e "      ${DIM}adb reboot${NC}   ${DIM}# wait for boot, then re-run this script${NC}"
                warn "Or, if it's a normal install, uninstall it first: ${DIM}adb uninstall com.nettarion.hyperborea${NC}"
            else
                warn "Uninstall the existing copy first, then re-run this script."
            fi
            ;;
        *INSTALL_FAILED_VERSION_DOWNGRADE*)
            warn "A newer version of $name is already installed — uninstall it first to downgrade."
            ;;
        *INSTALL_FAILED_INSUFFICIENT_STORAGE*)
            warn "Not enough free space on the device for $name."
            ;;
        *INSTALL_FAILED_NO_MATCHING_ABIS*)
            warn "$name has no native libraries for this device's CPU architecture."
            ;;
    esac
    return 1
}

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
            adb_reconnect_wait
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
            adb_reconnect_wait
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

    # Wait for device to actually go offline (avoids false boot_completed=1
    # from the still-running system before reboot takes effect)
    while adb shell true 2>/dev/null; do sleep 1; done

    # Single poll loop: reconnect (if IP) → check boot_completed → retry
    while true; do
        local elapsed=$(( $(date +%s) - wait_start ))
        if [ "$elapsed" -gt "$max_wait" ]; then
            stop_timer
            die "Timed out after ${max_wait}s. Try reconnecting manually."
        fi

        # IP connections need periodic reconnect attempts since ADB
        # won't auto-reconnect a TCP socket after the device reboots
        if is_ip_connection; then
            adb connect "$ANDROID_SERIAL" >/dev/null 2>&1 || true
            sleep 2
        fi

        if adb shell "getprop sys.boot_completed" 2>/dev/null | grep -q "1"; then
            adb_reconnect_wait
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

# iFit packages to disable after deployment.
# `com.ifit.launcher` is kept enabled so the device's home button still has
# something to land on; the launcher can't open the workout apps once those
# are disabled, but it remains as a benign navigation anchor.
IFIT_PACKAGES=(
    com.ifit.eru
    com.ifit.standalone
    com.ifit.arda
    com.ifit.glassos_service
    com.ifit.gandalf
    com.ifit.rivendell
    com.ifit.mithlond
)

# Collect other APKs
OTHER_APKS=()
for f in "$APPS_DIR"/*.apk; do
    [ -f "$f" ] || continue
    [ "$f" = "$HYPERBOREA_APK" ] && continue
    OTHER_APKS+=("$f")
done

ok "Found $(basename "$HYPERBOREA_APK")"

# Build wizard sections — only the additional-apps picker remains, gated
# on the apps/ directory containing anything beyond Hyperborea.
WIZ_SECTIONS=()
WIZ_LABELS=()
WIZ_STATES=()
WIZ_SEC_START=()
WIZ_SEC_COUNT=()

if [ ${#OTHER_APKS[@]} -gt 0 ]; then
    WIZ_SECTIONS+=("Additional apps")
    WIZ_SEC_START+=(${#WIZ_LABELS[@]})
    APP_COUNT=0
    for APK in "${OTHER_APKS[@]}"; do
        WIZ_LABELS+=("$(basename "$APK" .apk)")
        WIZ_STATES+=(1)
        APP_COUNT=$((APP_COUNT + 1))
    done
    WIZ_SEC_COUNT+=($APP_COUNT)

    WIZ_SECTIONS+=("Done")
    WIZ_SEC_START+=(${#WIZ_LABELS[@]})
    WIZ_SEC_COUNT+=(0)
fi

# =========================================================================
# Step 1: Connect to device
# =========================================================================
step 1 "Connect to device"
discover_device

# Quick sanity check that we have a working ADB shell. Running as the
# unprivileged `shell` user is expected and fine — the rest of the script
# never reaches for root.
if ! adb shell true >/dev/null 2>&1; then
    die "ADB shell unavailable. Make sure the console allows ADB connections."
fi
WHOAMI=$(adb shell "whoami" 2>/dev/null | tr -d '\r')
DEVICE_SDK=$(adb shell "getprop ro.build.version.sdk" 2>/dev/null | tr -d '\r')
DEVICE_RELEASE=$(adb shell "getprop ro.build.version.release" 2>/dev/null | tr -d '\r')
ok "ADB connected (user: ${WHOAMI:-shell}, Android ${DEVICE_RELEASE:-?} / API ${DEVICE_SDK:-?})"

# `adb install -g` (auto-grant runtime permissions) was introduced with the
# runtime-permission model in API 23. On API 22 the device's `pm` rejects -g
# and the install fails entirely. Runtime permissions don't exist on API 22
# anyway (everything is install-time auto-granted), so dropping -g is safe.
INSTALL_FLAGS="-r"
if [ -n "$DEVICE_SDK" ] && [ "$DEVICE_SDK" -ge 23 ]; then
    INSTALL_FLAGS="-r -g"
fi

# =========================================================================
# Step 2: Configure
# =========================================================================
step 2 "Configure"

APP_INSTALL_STATES=()
if [ ${#WIZ_SECTIONS[@]} -gt 0 ]; then
    echo ""
    run_wizard
    printf "\033[J"
    ok "Configuration saved"
    for ((i=0; i<${#OTHER_APKS[@]}; i++)); do
        APP_INSTALL_STATES+=("${WIZ_STATES[$i]}")
    done
else
    info "No optional configuration; continuing"
fi

# =========================================================================
# Step 3: Install Hyperborea (and any selected additional APKs)
# =========================================================================
step 3 "Install Hyperborea"

info "Installing $(basename "$HYPERBOREA_APK")..."
# $INSTALL_FLAGS = "-r" or "-r -g"; -g is added when the device is API 23+.
if install_apk "$HYPERBOREA_APK"; then
    ok "Hyperborea installed"
else
    die "adb install of Hyperborea failed — see the adb output above."
fi

INSTALLED=0
FAILED=0
for ((i=0; i<${#OTHER_APKS[@]}; i++)); do
    if [ "${APP_INSTALL_STATES[$i]:-0}" -ne 1 ]; then
        continue
    fi
    APK="${OTHER_APKS[$i]}"
    NAME=$(basename "$APK" .apk)
    info "Installing $NAME..."
    if install_apk "$APK"; then
        ok "$NAME"
        INSTALLED=$((INSTALLED + 1))
    else
        fail "$NAME"
        FAILED=$((FAILED + 1))
    fi
done
if [ "$INSTALLED" -gt 0 ] || [ "$FAILED" -gt 0 ]; then
    echo ""
    if [ "$FAILED" -eq 0 ]; then
        ok "$INSTALLED additional app(s) installed"
    else
        warn "$INSTALLED installed, $FAILED failed"
    fi
fi

# =========================================================================
# Step 4: Disable iFit packages
# =========================================================================
step 4 "Disable iFit"

# `pm disable-user --user 0` works as the unprivileged shell user; it
# doesn't kill running processes (the reboot in step 5 handles that), but
# it stops them from launching again.
DISABLED=0
ALREADY=0
ABSENT=0
for pkg in "${IFIT_PACKAGES[@]}"; do
    if ! adb shell "pm list packages" 2>/dev/null | grep -q "^package:$pkg$"; then
        ABSENT=$((ABSENT + 1))
        continue
    fi
    if adb shell "pm list packages -d" 2>/dev/null | grep -q "^package:$pkg$"; then
        ok "$pkg already disabled"
        ALREADY=$((ALREADY + 1))
        continue
    fi
    # PackageManagerShellCommand prints "Package $pkg new state: disabled-user".
    # Match the exact suffix to avoid colliding with `disable-until-used`.
    if adb shell "pm disable-user --user 0 $pkg" 2>&1 | grep -q "new state: disabled-user"; then
        ok "Disabled $pkg"
        DISABLED=$((DISABLED + 1))
    else
        warn "Could not disable $pkg"
    fi
done

echo ""
info "$DISABLED newly disabled, $ALREADY already disabled, $ABSENT not present"

# =========================================================================
# Step 5: Reboot, verify, and launch
# =========================================================================
step 5 "Reboot and launch"

info "Rebooting..."
REBOOT_START=$(date +%s)
start_timer "Waiting for device..." "$REBOOT_START"
adb reboot >/dev/null 2>&1 || true
wait_for_reboot 300
stop_timer
ok "Device back online ($(fmt_time $(( $(date +%s) - REBOOT_START ))))"

PKG_PATH=$(adb shell "pm path com.nettarion.hyperborea" 2>/dev/null | tr -d '\r')
if [ -z "$PKG_PATH" ]; then
    die "Hyperborea is not installed after reboot — something went wrong."
fi
ok "Install verified: $PKG_PATH"

info "Launching Hyperborea..."
adb shell am start -n com.nettarion.hyperborea/.MainActivity >/dev/null 2>&1 || true
ok "Hyperborea started"

echo ""
echo -e "  ${GREEN}${BOLD}Deployment complete!${NC}"
echo ""
