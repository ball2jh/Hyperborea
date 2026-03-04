# Firmware Modification & Deployment

> Complete procedure for building and installing rooted MGA1_20210901 firmware on the NordicTrack S22i.
> Proven across 25 install attempts. Last successful: 2026-02-12.
> **v2 (2026-02-12):** OTA now includes disabled install-recovery.sh and creates .wolfDev during installation.

## Overview

The process: patch the OTA's boot.img ramdisk (4 property changes), modify system/build.prop (ADB + stealth), modify iFit Launcher APK (strip HOME intent), replace install-recovery.sh with boot script, repack the OTA zip preserving its structure, sign with our key, install via the Android framework.

**v4 (2026-02-12):** ERU APK reverted to original (requires platform signing key due to `sharedUserId="android.uid.system"`). ERU is handled entirely by boot script (`pm disable` receiver + service, `am force-stop`). iFit Launcher APK still modified (HOME removed). Deploy script pushes to `/data/local/tmp/ota_install.zip` (never touches immutable `/data/update.zip`). Recovery key patching done entirely on-device.

**What the modified firmware provides:**
- `adb root` without auth (via ramdisk properties + `buildvariant=userdebug` cmdline)
- SELinux permissive (from kernel cmdline `androidboot.selinux=permissive`)
- ADB on TCP 5555 from boot

**Stealth design:** Apps see a stock-looking device — `Build.TYPE=user`, `Build.TAGS=dev-keys`, no su binary. Root detection in Launcher/Arda returns `false`. Firebase Crashlytics reports a clean device. All root access comes from ramdisk properties (`ro.secure=0`, `ro.debuggable=1`, `ro.adb.secure=0`) and the kernel cmdline (`buildvariant=userdebug`), which no app inspects.

---

## Part 1: Build the OTA (on Mac)

### 1a. Patch the ramdisk

Extract boot.img from the OTA, unpack it, then binary-patch the ramdisk:

```bash
WORK=firmware/repack/MGA1_20210901/boot_work
MKBOOT=tools/mkbootimg
OTA_DIR=firmware/repack/MGA1_20210901/original

# Unpack boot.img
$MKBOOT/unpackbootimg -i $OTA_DIR/boot.img -o $WORK/

# Binary-patch ramdisk (changes 7 bytes, preserves cpio structure)
python3 tools/binpatch_ramdisk.py $WORK/boot.img-ramdisk $WORK/ramdisk-patched.gz
```

This changes 4 properties in `default.prop`:
| Original | Patched |
|----------|---------|
| `ro.secure=1` | `ro.secure=0` |
| `ro.adb.secure=1` | `ro.adb.secure=0` |
| `ro.debuggable=0` | `ro.debuggable=1` |
| `persist.sys.usb.config=none` | `persist.sys.usb.config=adb` |

**Do NOT** extract and rebuild the cpio archive. Only binary-patch in-place.

### 1b. Rebuild boot.img

```bash
$MKBOOT/mkbootimg \
  --kernel $WORK/boot.img-kernel \
  --ramdisk $WORK/ramdisk-patched.gz \
  --second $WORK/boot.img-second \
  --cmdline "buildvariant=userdebug androidboot.selinux=permissive" \
  --base 0x10000000 --pagesize 2048 \
  --kernel_offset 0x00008000 --ramdisk_offset 0x01000000 \
  --second_offset 0x00f00000 --tags_offset 0x00000100 \
  --os_version 7.1.2 --os_patch_level 2019-08 \
  --header_version 0 --hashtype sha1 \
  -o $WORK/boot-patched.img
```

Verify the output is the **exact same size** as the original boot.img (22,302,720 bytes).

### 1c. Prepare system modifications

Copy original to a working directory:

```bash
cp -a firmware/repack/MGA1_20210901/original firmware/repack/MGA1_20210901/modified
```

**Replace boot.img:**
```bash
cp $WORK/boot-patched.img firmware/repack/MGA1_20210901/modified/boot.img
```

**Edit `system/build.prop`** — only change these lines (keep stock `user` identity):
| Property | Original | Modified |
|----------|----------|----------|
| `persist.sys.usb.config` | `none` | `adb` |

**Add new line** after `persist.sys.usb.config=adb`:
```
ro.adb.secure=0
```

**Do NOT** change `ro.build.type`, `ro.build.flavor`, `ro.build.description`, or `ro.build.fingerprint`. Leave them as stock `user` values. The `buildvariant=userdebug` kernel cmdline handles ADB access independently.

**Do NOT** add an su binary. We have `adb root` via ramdisk properties. No su means root detection returns `false`.

**Replace otacerts.zip** (blocks future unsigned OTAs):
```bash
cp firmware/keys/otacerts_dummy.zip \
   firmware/repack/MGA1_20210901/modified/system/etc/security/otacerts.zip
```

**Replace `install-recovery.sh`** with boot script (see below for full contents):
```bash
# The boot script is already in the modified/ directory. It:
# - Disables ERU TabletStartupReceiver and KeepTheWolfAliveService
# - Force-stops ERU and standalone
# - Disables iFit Launcher
# - Re-enables 11 stock Android apps
# - Fixes user_setup_complete, dev settings, ADB, non-market apps
# - Clears immersive mode
```

**Modify iFit Launcher APK** (strip HOME intent):
```bash
# Launcher: remove HOME category from intent filter
# Stock Launcher3 becomes the only home screen
apktool d <launcher-apk> -o launcher_work
# Remove <category android:name="android.intent.category.HOME"/>
apktool b launcher_work -o launcher-modified.apk
zipalign -f 4 launcher-modified.apk launcher-aligned.apk
apksigner sign --ks firmware/keys/system-mod.jks --ks-pass pass:android launcher-aligned.apk

# Copy signed APK back to modified/system/priv-app/
```

**NOTE:** ERU APK is NOT modified. It uses `sharedUserId="android.uid.system"` which requires the platform signing key (we don't have it). Re-signing with any other key causes `INSTALL_FAILED_SHARED_USER_INCOMPATIBLE`. ERU is handled entirely by the boot script's `pm disable` + `am force-stop`.

**Modify `updater-script`** — add `.wolfDev` creation at the end (before `set_progress`):
```
mount("ext4", "EMMC", "/dev/block/mmcblk0p10", "/data", "");
run_program("/sbin/sh", "-c", "mkdir -p /data/media/0 && touch /data/media/0/.wolfDev && chown 1023:1023 /data/media/0/.wolfDev && chown 1023:1023 /data/media/0");
unmount("/data");
```
This creates `.wolfDev` during OTA install so ERU never disables ADB on first boot.

### 1d. Repack and sign the OTA

```bash
python3 tools/repack_ota_MGA1_20210901.py
```

This copies every entry from the original zip, replacing only the changed files. It also signs the output with our key and verifies the signature.

Output: `firmware/repack/MGA1_20210901/output/MGA1_20210901-rooted-signed.zip` (~558 MB)

---

## Part 2: Deploy to Device

### Prerequisites

The device must be reachable via ADB with root. After a hardware factory reset, the 2017 userdebug boot.img is restored and ADB works immediately.

### 2a. Connect and get root

```bash
adb connect 192.168.1.177:5555
adb root
# Reconnect after root restart
adb connect 192.168.1.177:5555
```

### 2b. Pre-install setup

```bash
# Create dev file (preserves ADB across reboots)
adb shell touch /sdcard/.wolfDev

# Push ADB public key (safety net for auth)
adb shell mkdir -p /data/misc/adb
adb push ~/.android/adbkey.pub /data/misc/adb/adb_keys
adb shell chmod 640 /data/misc/adb/adb_keys
adb shell chown system:shell /data/misc/adb/adb_keys

# Push tools
adb push tools/chattr_arm64 /data/local/tmp/chattr
adb shell chmod 755 /data/local/tmp/chattr
adb push tools/install_ota.dex /data/local/tmp/install_ota.dex
```

### 2c. Patch recovery signing key

Recovery must accept our OTA signature. Patch its `/res/keys` on-device:

```bash
# Push our stripped key (no comment line — recovery's load_keys() fails on comments)
adb push firmware/keys/recovery_res_keys_stripped /data/local/tmp/recovery_res_keys

# Mount recovery, extract ramdisk, patch key, reinstall
adb shell mkdir -p /data/local/tmp/recovery_mnt
adb shell mount -t ext4 /dev/block/mmcblk0p6 /data/local/tmp/recovery_mnt

adb shell "cd /data/local/tmp && \
  gzip -d -c recovery_mnt/ramdisk-recovery.img > recovery_ramdisk.cpio && \
  mkdir -p recovery_fs && cd recovery_fs && cpio -id < ../recovery_ramdisk.cpio"

adb shell "cp /data/local/tmp/recovery_res_keys /data/local/tmp/recovery_fs/res/keys && \
  cd /data/local/tmp/recovery_fs && \
  find . | cpio -o -H newc 2>/dev/null | gzip > /data/local/tmp/ramdisk-recovery-patched.img"

adb shell "cp /data/local/tmp/ramdisk-recovery-patched.img \
  /data/local/tmp/recovery_mnt/ramdisk-recovery.img && sync"

adb shell umount /data/local/tmp/recovery_mnt
```

### 2d. Disable flash_recovery (on factory system)

The factory system's `install-recovery.sh` would restore stock recovery before we install. Disable it:

```bash
adb shell mv /system/bin/install-recovery.sh /system/bin/install-recovery.sh.disabled
```

Note: The OTA itself includes a no-op `install-recovery.sh`, so this only needs to be done on the factory system. After OTA install, the new system's `install-recovery.sh` is already disabled.

### 2e. Push and install the OTA

```bash
# Push OTA to temp location (~558 MB, takes ~80 seconds)
# Never touches /data/update.zip — immutable protection stays active
adb push firmware/repack/MGA1_20210901/output/MGA1_20210901-rooted-signed.zip /data/local/tmp/ota_install.zip

# Install via framework (same API path as ERU)
adb shell "CLASSPATH=/data/local/tmp/install_ota.dex app_process / InstallOTA /data/local/tmp/ota_install.zip"
# Exit code 255 = expected (process killed during reboot)
```

The device reboots into recovery, installs the OTA (~2-3 minutes), then reboots to the new system.

---

## Part 3: Post-Install

### 3a. Verify

```bash
adb connect 192.168.1.177:5555
adb root
adb shell whoami                        # root
adb shell getenforce                    # Permissive
adb shell getprop ro.build.type         # user  (stealth — apps see stock)
adb shell getprop ro.build.display.id   # MGA1_20210901
adb shell getprop ro.debuggable         # 1     (from ramdisk)
adb shell getprop ro.secure             # 0     (from ramdisk)
adb shell getprop ro.adb.secure         # 0     (from ramdisk + build.prop)
adb shell getprop persist.sys.usb.config # adb
adb shell "ls /system/xbin/su 2>&1"    # No such file (stealth — no su binary)
adb shell ls /sdcard/.wolfDev           # present
```

### 3b. Apply OTA protection

Block future unwanted OTA installs with an immutable empty file:

```bash
adb shell "rm /data/update.zip 2>/dev/null; touch /data/update.zip"
adb shell /data/local/tmp/chattr +i /data/update.zip

# Verify — write must fail
adb shell "echo test > /data/update.zip"
# Expected: Permission denied
```

### 3c. Clean up temp files

```bash
adb shell "rm -rf /data/local/tmp/recovery_mnt /data/local/tmp/recovery_fs \
  /data/local/tmp/recovery_ramdisk.cpio /data/local/tmp/ramdisk-recovery-patched.img \
  /data/local/tmp/recovery_res_keys"
```

---

## How Stealth Works

The root access is split across two layers that apps never inspect:

| Layer | What it does | Apps see it? |
|-------|-------------|-------------|
| **Ramdisk** (`default.prop`) | `ro.secure=0`, `ro.debuggable=1`, `ro.adb.secure=0` — enables `adb root` | No — apps don't read ramdisk properties |
| **Kernel cmdline** | `buildvariant=userdebug` — enables `ALLOW_ADBD_NO_AUTH` in adbd | No — apps don't parse `/proc/cmdline` |
| **Kernel cmdline** | `androidboot.selinux=permissive` — disables SELinux enforcement | No — no app calls `getenforce` |
| **build.prop** | `persist.sys.usb.config=adb`, `ro.adb.secure=0` — belt-and-suspenders | `ro.adb.secure` not checked by any app |

What apps DO check (and what they see):
| Check | Result | Why |
|-------|--------|-----|
| `Build.TYPE` (`ro.build.type`) | `user` | We keep the stock value |
| `Build.TAGS` (`ro.build.tags`) | `dev-keys` | Stock value; apps check for `test-keys`, not `dev-keys` |
| `/system/xbin/su` exists | `false` | No su binary installed |
| `/system/app/Superuser.apk` exists | `false` | Never installed |
| `Build.FINGERPRINT` | Stock `user/dev-keys` | Unchanged from original |

---

## Critical Rules (from 25 attempts)

| Rule | Why |
|------|-----|
| Use `tools/binpatch_ramdisk.py` for ramdisk | Only method that preserves cpio structure. Never extract+rebuild. |
| Pad `adb` with `\n` not `\0` | Null byte kills Android init's property parser (`strchr` stops at `\0`). |
| Use Python gzip, not macOS gzip | macOS gzip produces different compressed output → wrong boot.img size → hang. |
| cmdline = `buildvariant=userdebug` | Required for `ALLOW_ADBD_NO_AUTH=1`. Keep `ro.build.type=user` in build.prop for stealth. |
| Keep `ro.build.type=user` in build.prop | Apps read `Build.TYPE` for telemetry. `user` = stock appearance. |
| Do NOT add su binary | Apps check `/system/xbin/su`. Without it, root detection returns `false`. Use `adb root` instead. |
| Push ADB key to `/data/misc/adb/adb_keys` | Safety net if auth is ever required. |
| Create `.wolfDev` before reboot | Prevents ERU from disabling ADB on boot. |
| Strip comments from recovery `/res/keys` | `load_keys()` fails if first char isn't `v`. |
| Disable `install-recovery.sh` | Restores stock recovery (and keys) on every normal boot. |
| Patch recovery on-device, not Mac | Avoids macOS cpio bugs (uid 501, `./` prefix, inode=0). |
| Use `repack_ota.py`, not `zip -r` | Must preserve original zip entry structure (flags, ordering, no dir entries). |

## File Inventory

| File | Purpose |
|------|---------|
| `tools/binpatch_ramdisk.py` | Binary-patches ramdisk default.prop (7 bytes) |
| `tools/mkbootimg/mkbootimg` | Packs boot.img from kernel + ramdisk + second |
| `tools/mkbootimg/unpackbootimg` | Unpacks boot.img into components |
| `tools/repack_ota_MGA1_20210901.py` | Rebuilds OTA zip with modified files |
| `tools/sign_ota_minimal_pkcs7.py` | Signs OTA (called by repack script) |
| `tools/verify_ota_aosp.py` | Verifies OTA signature matches our key |
| `tools/install_ota.dex` | Calls `RecoverySystem.installPackage()` on device |
| `tools/chattr_arm64` | Sets immutable flag on device (Android lacks chattr) |
| `firmware/keys/ota_signing.pk8` | RSA 2048 private key (DER) for signing |
| `firmware/keys/ota_signing.x509.pem` | Matching X.509 certificate |
| `firmware/keys/otacerts_dummy.zip` | Replaces system otacerts to block unsigned OTAs |
| `firmware/keys/recovery_res_keys_stripped` | Our key in mincrypt v2 format (no comment line) |
| `firmware/keys/system-mod.jks` | Signing key for modified Launcher APK (password: `android`) |
| `firmware/downloads/MGA1_20210616_MGA1_20210901.zip` | Original OTA from iFit (532 MB) |

---

**Established:** 2026-02-12 | **Attempts:** 25 | **Reference:** `docs/firmware/OTA_INSTALL_LOG.md`
