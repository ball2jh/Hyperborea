# Direct Deploy

Install Hyperborea using only root ADB — no OTA, no recovery, no key replacement.

## Why This Exists

The S22i ships with root ADB access and a writable system partition on its factory firmware (argon_20190521). Direct deploy uses this to install Hyperborea as a privileged system app with standard Android commands.

## Constraint: No System Firmware Updates

Direct deploy requires root ADB, which only the factory firmware provides. iFit's system firmware updates (e.g., 20190521 → MGA1_20210616 → MGA1_20210901) overwrite both the boot and system partitions, replacing `ro.debuggable=1` with `ro.debuggable=0` and removing root access. Once root is gone, Hyperborea cannot be reinstalled to `/system/priv-app/`.

**ERU app updates are safe** — they install to `/data/app/` and don't touch `/system` or the boot image. Only the system OTA is destructive.

Strategy: install Hyperborea, let ERU update its apps freely, but prevent ERU from applying system firmware updates. The Orchestrator blocks system updates at runtime by managing ERU's update flow.

## Prerequisites

- **Factory firmware** (argon_20190521) — hardware factory reset if needed (hold Power during boot)
- ADB connection to the S22i (`adb devices` shows the device)
- Root ADB works (`adb root` succeeds)
- Hyperborea APK built: `./gradlew :app:assembleStandardDebug`

The standard flavor is sufficient — privileged permissions come from the directory location on API 25, not from the signing key.

## Install

Do NOT run `adb remount` before ERU's initial setup completes — it interferes with ERU's `uncrypt` process for OTA updates. Complete the install in one shot before triggering any ERU updates, or after app updates finish.

### 1. Create .wolfDev

```bash
adb root
adb shell touch /sdcard/.wolfDev
```

ERU checks for this file on boot. When present, it skips ADB and developer mode lockdown. Without it, ERU uses `WRITE_SECURE_SETTINGS` to disable ADB — locking you out.

Survives reboot. Lost on software factory reset.

### 2. Install Hyperborea

```bash
adb remount
adb shell mkdir -p /system/priv-app/Hyperborea
adb shell chmod 755 /system/priv-app/Hyperborea
adb push app/build/outputs/apk/standard/debug/app-standard-debug.apk /system/priv-app/Hyperborea/Hyperborea.apk
adb shell chmod 644 /system/priv-app/Hyperborea/Hyperborea.apk
adb reboot
```

On API 25, apps in `/system/priv-app/` automatically receive privileged permissions — no whitelist XML needed.

> **Important**: The directory must be `755` (not `777`). PackageManager checks directory permissions and won't grant the `PRIVILEGED` flag if they're too open.

### What This Gives You

| | Direct Deploy | OTA Tooling |
|-|---------------|-------------|
| Hyperborea installed | Yes (`/system/priv-app/`) | Yes (`/system/priv-app/`) |
| Hyperborea privileged | Yes (from directory location) | Yes (from directory + platform key) |
| Hyperborea UID | Own UID (10xxx) | 1000 (system) via `sharedUserId` |
| Signature-level permissions | No | Yes (platform key) |
| ERU app updates | Yes (ERU keeps all permissions) | No (ERU demoted + re-signed) |
| System firmware updates | No (would remove root) | Yes (ramdisk patched to keep root) |
| ADB persistence | `.wolfDev` flag | Guaranteed (ramdisk patched) |
| ERU coexistence | Orchestrator manages at runtime | ERU demoted at filesystem level |

The Orchestrator handles ERU coexistence on every startup:
- Force-stops iFit standalone (transient, not disable)
- Disables ERU's USB receiver (prevents crash-loop)
- Blocks system firmware updates
- Manages ecosystem state going forward

## Verification (post-reboot)

```bash
# 1. Root ADB still works
adb root

# 2. Hyperborea is installed
adb shell pm list packages | grep hyperborea
# Expected: package:com.nettarion.hyperborea

# 3. Hyperborea is privileged
adb shell dumpsys package com.nettarion.hyperborea | grep privateFlags
# Expected: privateFlags=[ PRIVILEGED ... ]

# 4. Hyperborea permissions granted
adb shell dumpsys package com.nettarion.hyperborea | grep "FORCE_STOP_PACKAGES\|MANAGE_USB\|CHANGE_COMPONENT"
# Expected: all granted=true

# 5. ERU still privileged (can do app updates)
adb shell dumpsys package com.ifit.eru | grep privateFlags
# Expected: privateFlags=[ PRIVILEGED ]

# 6. ADB survived ERU boot
adb shell settings get global adb_enabled
# Expected: 1
```

## Updating Hyperborea

Push the new APK and reboot:

```bash
./gradlew :app:assembleStandardDebug
adb root
adb remount
adb push app/build/outputs/apk/standard/debug/app-standard-debug.apk /system/priv-app/Hyperborea/Hyperborea.apk
adb shell chmod 644 /system/priv-app/Hyperborea/Hyperborea.apk
adb reboot
```

## Recovery After Factory Reset

### Software Factory Reset (Settings menu)

Wipes `/data` + `/cache` only. System partition survives — Hyperborea is still installed. Re-create `.wolfDev` before ERU locks ADB:

```bash
adb root
adb shell touch /sdcard/.wolfDev
```

### Hardware Factory Reset (hold Power during boot)

Restores ALL partitions from backup image. Back to factory firmware with root. Full direct deploy required from step 1.

## Optional Hardening

Apply if the Orchestrator's runtime management proves insufficient.

### IFW Rules (block ERU broadcasts)

```bash
adb shell mkdir -p /data/system/ifw
adb push ifit_firewall.xml /data/system/ifw/
adb shell chmod 644 /data/system/ifw/ifit_firewall.xml
```

Blocks ERU broadcast receivers at framework level. Main concern without this: `STANDALONE_BOUNCE` can call `Runtime.exec("reboot")` which may succeed on permissive SELinux.

Survives reboot. Lost on factory reset.

### Disable ERU USB Receiver

```bash
adb shell pm disable com.ifit.eru/com.ifit.eru.receivers.UsbDeviceAttachedReceiver
```

Prevents ERU's USB receiver from crash-looping every ~20s when USB cycles. The Orchestrator already does this on startup, so this is only needed before first Hyperborea launch.

Survives reboot. Lost on factory reset. Self-heals via Orchestrator.

## DMCA Analysis

Every step uses manufacturer-provided root access and standard Android commands. Nothing is bypassed, patched, or cracked.

| Step | Command | Circumvention? |
|------|---------|----------------|
| Get root shell | `adb root` | No — manufacturer-provided |
| Remount /system | `adb remount` | No — filesystem is RW by design |
| Create .wolfDev | `touch` | No — writing a file with authorized access |
| Push APK | `adb push` | No — writing a file with authorized access |
| Reboot | `adb reboot` | No — standard command |

## Why Not System Firmware Updates?

The factory firmware (argon_20190521) provides root ADB (`ro.debuggable=1`, `ro.secure=0`). iFit's system OTAs overwrite both the boot image (removing root) and the system partition (removing Hyperborea). Once root is gone, there is no way to reinstall Hyperborea as a privileged app without either:

1. **Hardware factory reset** — restores the old firmware with root, but loses all data and app updates
2. **OTA tooling** — repacks the firmware with a patched ramdisk that preserves root (see `docs/firmware.md`)
3. **Kernel exploit** — the new firmware runs kernel 4.4.83+ with SELinux permissive, which may have exploitable CVEs, but this is unexplored

The tradeoff is clear: direct deploy is simple and legally clean, but you stay on old firmware. OTA tooling handles everything but is complex. There is no middle ground — the new firmware's removal of root is the fundamental constraint.
