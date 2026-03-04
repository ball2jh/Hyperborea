# OTA Repack Guide: Building a Rooted Firmware Update

> Step-by-step guide to building a modified OTA that preserves root access, ADB, and SELinux permissive mode through a firmware update.

## Prerequisites

### Tools Required

| Tool | Location | Purpose |
|------|----------|---------|
| `mkbootimg` / `unpackbootimg` | `tools/mkbootimg/` | Boot image pack/unpack (built from [osm0sis/mkbootimg](https://github.com/osm0sis/mkbootimg)) |
| `sign_ota_minimal_pkcs7.py` | `tools/sign_ota_minimal_pkcs7.py` | Signs OTA with AOSP-compatible PKCS#7 |
| `verify_ota_aosp.py` | `tools/verify_ota_aosp.py` | Verifies OTA signature locally |
| `strip_old_signature.py` | `tools/strip_old_signature.py` | Strips existing signature from OTA zip |
| `repack_ota.py` | `tools/repack_ota.py` | Rebuilds OTA preserving original zip structure |
| `repack_ota_MGA1_20210901.py` | `tools/repack_ota_MGA1_20210901.py` | Same for MGA1_20210901 OTA |
| `repack_ota_sysonly.py` | `tools/repack_ota_sysonly.py` | Sysonly variant (original boot.img) for testing |
| `mkcpioimg.py` | `tools/mkcpioimg.py` | Creates cpio ramdisks correctly on macOS |
| `install_ota.dex` | `tools/install_ota.dex` | Calls RecoverySystem.installPackage() |
| `ota_postinstall.c` | `tools/ota_postinstall.c` | Recovery postinstall binary (protection setup) |
| `deploy_ota.sh` | `tools/deploy_ota.sh` | End-to-end deployment script |
| Python 3 + `cryptography` | `.venv/` | Required by verify script |
| OpenSSL | System | Required by signing script |
| ADB | System | Device communication |
| NDK (aarch64) | See CLAUDE.md | Only if recompiling `su` binary |

### Signing Keys

All signing keys are stored in `firmware/keys/`:

| File | Format | Purpose |
|------|--------|---------|
| `ota_signing.key` | PEM RSA private key | Human-readable private key |
| `ota_signing.pk8` | PKCS#8 DER private key | Used by `sign_ota_minimal_pkcs7.py` |
| `ota_signing.x509.pem` | PEM X.509 certificate | Certificate embedded in OTA signature |
| `otacerts_dummy.zip` | Zip containing cert | Replaces system `otacerts.zip` to block future uncontrolled OTAs |
| `recovery_res_keys` | AOSP mincrypt v2 text | Installed into recovery ramdisk `/res/keys` |

Key details:
- **Type:** 2048-bit RSA, exponent 65537
- **Subject:** `CN=DummyOTABlocker, O=None`
- **Validity:** 10 years from 2026-02-10
- **Generated with:** `openssl req -x509 -newkey rsa:2048 -days 3650 -nodes`

### Backups

Original recovery partition images are in `firmware/backups/`:

| File | Description |
|------|-------------|
| `recovery_original.img` | Full 64MB ext4 recovery partition (`/dev/block/mmcblk0p6`) |
| `ramdisk-recovery_original.img` | Original gzip+cpio recovery ramdisk with iFit's `/res/keys` |

### Source OTAs

| Firmware | File | Size | Build Date |
|----------|------|------|------------|
| MGA1_20210616 | `firmware/downloads/20190521_MGA1_20210616.zip` | 538 MB | 2021-06-16 |
| MGA1_20210901 | `firmware/downloads/MGA1_20210616_MGA1_20210901.zip` | 532 MB | 2021-09-01 |

Both are full OTAs (not differential) with identical boot.img params.

## Workspace Layout

```
firmware/repack/
├── MGA1_20210616/         # First OTA
│   ├── original/          # Pristine OTA extraction (never modify)
│   ├── modified/          # Working copy with all changes applied
│   ├── boot_work/         # boot.img unpack/repack workspace
│   └── output/            # Signed OTA output
├── MGA1_20210901/         # Second OTA (same structure)
│   ├── original/
│   ├── modified/
│   ├── boot_work/
│   └── output/
├── recovery_work/         # Shared recovery workspace
└── keys/                  # Working copy of signing keys
```

Each `modified/` directory contains the OTA contents with all changes applied:
- `boot.img` — patched (root, ADB, SELinux permissive)
- `system/build.prop` — stealth (user build type, ADB enabled)
- `system/etc/security/otacerts.zip` — our signing cert
- `system/bin/install-recovery.sh` — boot script (disables iFit auto-start, enables stock apps, fixes settings)
- `system/etc/ifit_firewall.xml` — Intent Firewall backup (21 rules; boot script restores to `/data/system/ifw/` if missing)
- `system/priv-app/com.ifit.launcher-*/` — modified APK (HOME category removed from manifest)
- `recovery/bin/install-recovery.sh` — same boot script (copied during OTA)
- `META-INF/com/google/android/updater-script` — includes ota_postinstall execution

## Full Repack Procedure

### Phase 1: Setup

```bash
# Activate venv
source .venv/bin/activate

# Build mkbootimg tools (if not already built)
cd tools/mkbootimg && make && cd -

# Create clean workspace
rm -rf firmware/repack/{original,modified,boot_work,output}
mkdir -p firmware/repack/{original,modified,boot_work,output}

# Extract original OTA
cd firmware/repack/original
unzip ../../downloads/20190521_MGA1_20210616.zip
cd -
```

### Phase 2: Patch boot.img

#### 2a. Unpack

```bash
MKBOOT=tools/mkbootimg
BWORK=firmware/repack/boot_work

$MKBOOT/unpackbootimg \
  -i firmware/repack/original/boot.img \
  -o $BWORK/
```

Expected output — record these values, they must be used exactly when repacking:
```
BOARD_KERNEL_CMDLINE buildvariant=user
BOARD_KERNEL_BASE 0x10000000
BOARD_PAGE_SIZE 2048
BOARD_HASH_TYPE sha1
BOARD_KERNEL_OFFSET 0x00008000
BOARD_RAMDISK_OFFSET 0x01000000
BOARD_SECOND_OFFSET 0x00f00000
BOARD_TAGS_OFFSET 0x00000100
BOARD_OS_VERSION 7.1.2
BOARD_OS_PATCH_LEVEL 2019-08
BOARD_HEADER_VERSION 0
```

#### 2b. Binary-patch ramdisk (RECOMMENDED)

**Use `tools/binpatch_ramdisk.py`** — this is the only reliable method. It preserves the exact
cpio structure and uses Python gzip for recompression.

```bash
python3 tools/binpatch_ramdisk.py $BWORK/boot.img-ramdisk $BWORK/boot.img-ramdisk-patched.gz
```

**DO NOT** use any of these approaches (all cause boot hang):
- `mkcpioimg.py` — produces different inode numbers, kernel hangs
- `find . | cpio -o -H newc` on macOS — wrong uid/gid, path prefix, inodes
- macOS `gzip` command for recompression — different compressed output
- Null byte (`\0`) padding for `none` → `adb` — breaks init's property parser

The tool changes 4 properties in `default.prop` and patches the adbd binary (10 bytes total):

| Target | Change | Purpose |
|--------|--------|---------|
| `default.prop` `ro.secure` | `1` → `0` | adbd starts as root |
| `default.prop` `ro.adb.secure` | `1` → `0` | Matches expected state (see adbd patch below) |
| `default.prop` `ro.debuggable` | `0` → `1` | Enables `adb root` |
| `default.prop` `persist.sys.usb.config` | `none` → `adb\n` | ADB enabled from first boot |
| `sbin/adbd` auth check | `cbz w8, ...` → `b ...` | **Disables ADB authentication** (see below) |

**adbd auth bypass:** The stock adbd binary was compiled with `ALLOW_ADBD_NO_AUTH=0` (standard
for `user` builds). This `#ifdef`'s out the entire `ro.adb.secure` property check — auth is
always required regardless of property settings. The binary patch changes the conditional branch
at VA `0x40ce0c` from `cbz` (skip auth only if `auth_required == 0`) to an unconditional `b`
(always skip auth). This is the only way to disable auth without recompiling adbd from source.

**Verify:** Output compressed size should be within ~10 bytes of original.
Boot.img built from this ramdisk should be the **exact same size** as the original.

#### 2c. Repack boot.img

**Note on cmdline:** `buildvariant=userdebug` does NOT affect the adbd binary — `ALLOW_ADBD_NO_AUTH`
is a compile-time flag baked into the binary, not controlled by kernel cmdline. ADB auth is disabled
by the adbd binary patch in step 2b above. The `buildvariant` cmdline parameter is kept as
`userdebug` for historical reasons but has no runtime effect (it's not an `androidboot.*` parameter
so init doesn't map it to a system property). `androidboot.selinux=permissive` sets SELinux to
permissive mode (confirmed working, attempt #24).

```bash
$MKBOOT/mkbootimg \
  --kernel $BWORK/boot.img-kernel \
  --ramdisk $BWORK/boot.img-ramdisk-patched.gz \
  --second $BWORK/boot.img-second \
  --cmdline "buildvariant=userdebug androidboot.selinux=permissive" \
  --base 0x10000000 \
  --kernel_offset 0x00008000 \
  --ramdisk_offset 0x01000000 \
  --second_offset 0x00f00000 \
  --tags_offset 0x00000100 \
  --os_version 7.1.2 \
  --os_patch_level 2019-08 \
  --header_version 0 \
  --hashtype sha1 \
  --pagesize 2048 \
  -o $BWORK/boot-patched.img
```

#### 2d. Verify boot.img

```bash
$MKBOOT/unpackbootimg -i $BWORK/boot-patched.img -o /tmp/boot_verify/

# Expected: BOARD_KERNEL_CMDLINE buildvariant=userdebug androidboot.selinux=permissive
# Boot.img size should match original exactly

# Verify ramdisk round-trips
mkdir -p /tmp/boot_verify/rd && cd /tmp/boot_verify/rd
gunzip -c ../boot-patched.img-ramdisk | cpio -id 2>/dev/null
grep -E "ro.secure|ro.adb.secure|ro.debuggable|persist.sys.usb.config" default.prop

# Expected:
#   ro.secure=0
#   ro.adb.secure=0
#   ro.debuggable=1
#   persist.sys.usb.config=adb
```

### Phase 3: Modify System Files

#### 3a. Copy original to modified workspace

```bash
cp -a firmware/repack/original/ firmware/repack/modified/
```

#### 3b. Replace boot.img

```bash
cp $BWORK/boot-patched.img firmware/repack/modified/boot.img
```

#### 3c. Modify system/build.prop (stealth)

Only change the minimum needed. **Keep stock `user` identity** to avoid app-level root detection:

| Line | Original | Modified | Purpose |
|------|----------|----------|---------|
| 21 | `ro.product.model=MalataSamsungArgon1` | `ro.product.model=AOSP on avn_ref` | `DeviceInfo.IsBuiltIn()` detection |
| 32 | `ro.product.manufacturer=Malata` | `ro.product.manufacturer=NEXELL` | `DeviceInfo.IsBuiltIn()` detection |
| 72 | `persist.sys.usb.config=none` | `persist.sys.usb.config=adb` | ADB on from boot |
| (new) | — | `ro.adb.secure=0` (add after line 73) | No ADB auth needed |

The manufacturer/model change is required because `DeviceInfo.IsBuiltIn()` (in Standalone v2.6.88+) checks `Build.Manufacturer` and `Build.Model` against a hardcoded list. The stock values `Malata`/`MalataSamsungArgon1` don't match any known device, but `NEXELL`/`AOSP on avn_ref` does. Without this, ERU's ConsoleInfo IPC may not report correct hardware identity.

**Do NOT change** `ro.build.type`, `ro.build.flavor`, `ro.build.description`, or `ro.build.fingerprint`. Leave them as stock `user` values. Root access comes from the ramdisk (`ro.secure=0`, `ro.debuggable=1`) and kernel cmdline (`buildvariant=userdebug`), not from build.prop.

**Do NOT add an su binary.** Apps check `/system/xbin/su` for root detection (Launcher, Arda, Rivendell, Mithlond). Without it, detection returns `false`. Use `adb root` instead — it works via ramdisk properties.

**Do NOT modify updater-script** — no su means no metadata to add.

#### 3d. Replace otacerts.zip

```bash
cp firmware/keys/otacerts_dummy.zip firmware/repack/modified/system/etc/security/otacerts.zip
```

This replaces the iFit signing certificate with our dummy cert, preventing the device from accepting future unmodified OTAs automatically.

#### 3g. Remove old signatures

```bash
rm firmware/repack/modified/META-INF/CERT.RSA
rm firmware/repack/modified/META-INF/CERT.SF
rm firmware/repack/modified/META-INF/MANIFEST.MF
```

### Phase 4: Build and Sign OTA

#### 4a. Create, sign, and verify OTA

**WARNING:** Do NOT use `zip -r` to create the OTA. It produces a different zip structure
(extra directory entries, wrong flags, macOS case collisions on filenames).
Use `repack_ota.py` which copies entries from the original zip and only replaces changed files.

```bash
# Full OTA (includes modified boot.img)
python3 tools/repack_ota.py

# OR: Sysonly diagnostic (original boot.img, only system changes)
python3 tools/repack_ota_sysonly.py
```

The script automatically:
1. Copies all entries from the original OTA preserving exact zip structure
2. Replaces only the modified files (boot.img, build.prop, otacerts.zip)
3. Signs the output with our OTA key
4. Verifies the signature

Output: `firmware/repack/output/20190521_MGA1_20210616-rooted-signed-v2.zip` (~566MB)

Expected: `WHOLE-FILE SIGNATURE VERIFIED!`

### Phase 5: Update Recovery Keys (One-Time)

The device's recovery partition must trust our signing key. This only needs to be done once — the OTA does not overwrite the recovery partition's ramdisk key store.

**Important:** The OTA *does* flash a new `recovery.kernel` and `recovery.dtb` via the `recovery/` directory in the zip (see updater-script line 7: `package_extract_dir("recovery", "/system")`). However, this copies to `/system/`, not directly to the recovery partition. The actual recovery partition (`mmcblk0p6`) is only updated by the bootloader or manual dd.

#### 5a. Mount recovery partition

```bash
adb root
adb shell mkdir -p /data/local/tmp/recovery_mnt
adb shell mount -t ext4 -o rw /dev/block/mmcblk0p6 /data/local/tmp/recovery_mnt
```

The recovery partition is ext4 (not a raw boot image). Contents:
```
ramdisk-recovery.img    # gzip+cpio ramdisk (contains /res/keys)
recovery.dtb            # Device tree blob
recovery.kernel         # Kernel
```

#### 5b. Back up original ramdisk

```bash
adb shell cp /data/local/tmp/recovery_mnt/ramdisk-recovery.img \
              /data/local/tmp/recovery_mnt/ramdisk-recovery.img.bak
```

#### 5c. Build patched ramdisk

**CRITICAL:** The `firmware/keys/recovery_res_keys` file contains a comment line
(`# Key info: ...`). This **must be stripped** — AOSP's `load_keys()` in `verifier.cpp`
expects each line to start with `v` (version prefix). A `#` comment causes parsing to fail
and recovery rejects all OTA signatures.

```bash
# Use the pre-stripped key file (no comment line)
adb push firmware/keys/recovery_res_keys_stripped /data/local/tmp/our_key

# Extract, replace, and repack ON DEVICE (avoids macOS cpio issues)
adb shell "
mkdir -p /data/local/tmp/recovery_rd && cd /data/local/tmp/recovery_rd
gzip -d -c /data/local/tmp/recovery_mnt/ramdisk-recovery.img | cpio -id 2>/dev/null
cp /data/local/tmp/our_key res/keys
find . | cpio -o -H newc 2>/dev/null | gzip > /data/local/tmp/ramdisk-recovery-patched.img
"
```

#### 5d. Push to device

```bash
adb push firmware/repack/recovery_work/ramdisk-recovery-patched.img \
         /data/local/tmp/recovery_mnt/ramdisk-recovery.img
adb shell chmod 644 /data/local/tmp/recovery_mnt/ramdisk-recovery.img
adb shell sync
adb shell umount /data/local/tmp/recovery_mnt
```

### Phase 6: Apply OTA

#### Background: How Android OTA Install Works

Based on analysis of the actual AOSP 7.1.2 source code (`frameworks/base`, `system/core/init`, `bootable/recovery`), the full OTA install chain has three independent pieces that must all work:

**1. The BCB (Bootloader Control Block)** — stored on the misc partition (`/dev/block/mmcblk0p7`).
The BCB is a 2048-byte structure that tells the bootloader what to do on next boot.
When `command` = `"boot-recovery"`, the bootloader boots the recovery partition.
The `recovery` field carries arguments (e.g., `"recovery\n--update_package=/data/update.zip\n"`).

**2. The command file** (`/cache/recovery/command`) — a backup/fallback for the BCB.
Recovery's `get_args()` reads BCB first; if empty, falls back to this file.

**3. The reboot reason string** — passed from userspace to kernel to bootloader.
Init's `do_powerctl()` calls `syscall(RESTART2, reason_string)`. The kernel passes
this string to the bootloader (device-specific mechanism, e.g., PMU scratch register).
The bootloader interprets "recovery" → boot recovery partition.

**How `RecoverySystem.installPackage()` works (the ERU path):**
```
1. Delete /cache/recovery/log and /cache/recovery/uncrypt_file
2. If package is on /data/:
   a. Write package path to /cache/recovery/uncrypt_file
   b. Delete /cache/recovery/block.map (triggers uncrypt during shutdown)
   c. Set filename to "@/cache/recovery/block.map"
      (@ prefix = block-device mapping, avoids mounting /data in recovery)
   If package is on /cache/:
   a. Set filename to "/cache/update.zip" (direct access, no uncrypt needed)
3. Build command string: "--update_package=<filename>\n--locale=en_US\n"
4. Call setupBcb(command) → writes command to BCB on misc partition     ← CRITICAL
5. Call PowerManager.reboot("recovery-update")
   → ShutdownThread runs uncrypt (if needed) via socket to uncrypt daemon
   → lowLevelReboot() → init do_powerctl() → syscall(RESTART2, "recovery-update")
6. Bootloader reads reboot reason OR BCB → boots recovery
7. Recovery reads BCB → finds command → processes OTA
```

**Why uncrypt exists (and why we can skip it):**
The standard Java flow converts `/data/` paths to block maps because `/data` might be
encrypted on phones. But on this device, `/data` is unencrypted ext4. Recovery's fstab
defines `/data` at `/dev/block/mmcblk0p10`, so recovery CAN mount `/data` and read files
directly. We bypass uncrypt entirely by passing `--update_package=/data/update.zip`
(without the `@` prefix) to recovery.

#### Critical Discovery: The BCB Problem

**Root cause of failed attempts #6 and #7 (2026-02-11):**

Neither `adb reboot recovery` nor `svc power reboot recovery-update` write the BCB.
Confirmed by reading misc partition (`/dev/block/mmcblk0p7`) — it was all zeros after both attempts.

From `system/core/init/builtins.cpp` (`do_powerctl()`):
```cpp
// do_powerctl just passes reboot_target to the kernel — it does NOT write BCB
reboot_target = &command[len + 1];  // e.g., "recovery" or "recovery-update"
return android_reboot_with_callback(cmd, 0, reboot_target, ...);
```

From `system/core/libcutils/android_reboot.c`:
```cpp
// android_reboot_with_callback just does a kernel syscall — no BCB write
case ANDROID_RB_RESTART2:
    ret = syscall(__NR_reboot, LINUX_REBOOT_MAGIC1, LINUX_REBOOT_MAGIC2,
                   LINUX_REBOOT_CMD_RESTART2, arg);  // arg = "recovery-update"
```

The BCB is ONLY written by `RecoverySystemService.setupBcb()` (via the `setup-bcb`
init service) or by `wipe_data_via_recovery()` (for factory reset). Neither is called
by `svc power reboot` or `adb reboot`.

**This means `svc power reboot recovery-update` relies on the bootloader interpreting
the kernel's reboot reason string "recovery-update" as "boot recovery".** On this
device's Nexell S5P6818 bootloader, this may not work — the bootloader might only
recognize the exact string "recovery".

**The reliable approach is to write the BCB ourselves** using `write_bcb` tool, then
reboot with `adb reboot recovery` (the bootloader is known to handle "recovery").

#### How recovery processes the command

From `bootable/recovery/recovery.cpp`, `get_args()`:
1. Read BCB from misc partition
2. If `boot.recovery` starts with "recovery\n", parse args from BCB
3. Else if `/cache/recovery/command` exists, parse args from file
4. Write args BACK to BCB (ensures retry on crash — recovery loops until `finish_recovery()` clears BCB)

From `recovery.cpp` `main()`:
1. `redirect_stdio("/tmp/recovery.log")` — all output goes to /tmp first
2. `load_volume_table()` — reads `/etc/recovery.fstab` (confirmed: has /cache, /data, /misc)
3. `get_args()` — gets command from BCB or command file
4. If `--update_package=<path>`:
   - Calls `ensure_path_mounted(path)` — mounts the partition containing the OTA
   - Calls `install_package(path, ...)` — verifies signature then runs updater
5. `finish_recovery()` — copies `/tmp/recovery.log` to `/cache/recovery/last_log`, clears BCB, deletes command file
6. Reboots

**Key: recovery can mount ANY partition in its fstab.** Since `/data` is defined
(`/dev/block/mmcblk0p10 /data ext4`), passing `--update_package=/data/update.zip`
works — recovery mounts `/data` and reads the file directly. No uncrypt or block.map needed.

#### Why `handleAftermath()` confused our debugging

On every normal boot, `RecoverySystem.handleAftermath()` (called from `BootReceiver`)
cleans up `/cache/recovery/`:
- Preserves files starting with `last_` (logs from recovery)
- Preserves `block.map` and `uncrypt_file` if block.map exists
- **Deletes everything else** — including the `command` file

On attempt #7, the missing command file was NOT evidence that recovery consumed it.
Recovery never booted — the command file was deleted by `handleAftermath()` on normal boot.

#### Approach Comparison (Revised)

| Approach | Complexity | Reliability | Notes |
|----------|-----------|-------------|-------|
| **A: Direct `/data` path + `write_bcb`** | **Low** | **High** | Write BCB ourselves, pass `/data/update.zip` directly. No uncrypt needed. Recovery mounts /data and reads file. |
| **B: Push to `/cache` + `write_bcb`** | Low | High | Push OTA to `/cache/update.zip` (538MB fits in 592MB). Simplest — recovery natively accesses /cache. |
| **C: Block map + `svc power reboot recovery-update`** | High | Medium | The uncrypt/block.map path. Complex, many failure modes. Only needed if /data can't be mounted in recovery. |
| **D: `app_process` + DEX** | Medium | Medium | Calls `RecoverySystem.installPackage()` directly. May fail on service bindings. |

**We use Approach A** — write BCB with `write_bcb` tool, pass `/data/update.zip` directly,
reboot with `adb reboot recovery`. Recovery mounts `/data`, reads the file, verifies, installs.

Approach B is the backup if recovery can't mount `/data` for any reason.

#### Failure History

| # | Date | OTA Path | Reboot Method | BCB Written? | Recovery Booted? | Result | Root Cause |
|---|------|----------|---------------|-------------|-----------------|--------|------------|
| 1 | 2026-02-10 | `/sdcard/update.zip` | Manual command file | No | Unknown | File not found | `/sdcard/` not mounted in recovery |
| 2 | 2026-02-10 | `/data/media/0/update.zip` | Manual command + reboot | No | Unknown | Wrong file | Stale `uncrypt_file` from prior attempt |
| 3 | 2026-02-10 | `/cache/update.zip` | Manual command + BCB | No | Unknown | Same | Stale `uncrypt_file` still present |
| 4 | 2026-02-10 | `/cache/update.zip` | Direct BCB + raw reboot | No | No | Recovery never ran | Native reboot doesn't write BCB |
| 5 | 2026-02-10 | Direct `dd` of boot.img | N/A | N/A | N/A | Boot hang | 2021 kernel + 2019 system mismatch |
| 6 | 2026-02-11 | `/data/update.zip` | `adb reboot recovery` | **No** | Yes (boot loop) | No block.map | `adb reboot` bypasses Java — uncrypt never ran |
| 7 | 2026-02-11 | `/data/update.zip` | `svc power reboot recovery-update` | **No** | **No** | Recovery never booted | BCB empty — bootloader didn't recognize "recovery-update" reboot reason. `handleAftermath()` deleted command file on normal boot, making it look like recovery consumed it. |
| 8 | 2026-02-11 | `/data/update.zip` | `write_bcb` + `adb reboot recovery` | **Yes** | Yes (crash) | No logs, BCB still set | Recovery booted but crashed before `copy_logs()`. Device appeared stuck. Manual power cycle → normal boot. `flash_recovery` ran (exit 0). |
| 9 | 2026-02-11 | `/cache/update.zip` | `write_bcb` + `adb reboot recovery` | **Yes** | Yes (boot loop) | No logs, boot loop | Recovery did NOT re-patch after #8's normal boot. `flash_recovery` likely restored stock key. Visible boot loop until power off. |

**Key lessons:**
- Attempts 1-5: Various path and state issues (pre-BCB understanding)
- Attempt 6: `adb reboot recovery` booted recovery (bootloader understood "recovery" reason string), but block.map was missing because uncrypt never ran
- Attempt 7: `svc power reboot recovery-update` ran uncrypt (block.map created) but the bootloader didn't understand "recovery-update" — recovery never booted. BCB was confirmed empty (all zeros on misc partition).
- Attempt 8: BCB + `adb reboot recovery` worked (recovery booted), but recovery crashed with no log output. Cause unknown — could be OOM during 538MB mmap, Nexell-specific issue, or verification failure.
- Attempt 9: Device booted normally between #8 and #9 → `flash_recovery` likely restored stock recovery → our key was overwritten → stock key can't verify our custom-signed OTA. **Recovery was NOT re-patched before attempt #9.**

**See [RECOVERY_DEBUG_ANALYSIS.md](RECOVERY_DEBUG_ANALYSIS.md) for full root cause analysis and debugging plan.**

#### Device Partition Layout (Reference)

| Partition | Device | Mount | Size | Notes |
|-----------|--------|-------|------|-------|
| mmcblk0p1 | /boot | emmc | 64MB | Boot image |
| mmcblk0p2 | /system | ext4 | 2GB | System partition |
| mmcblk0p5 | /cache | ext4 | 592MB | Cache — fits 538MB OTA |
| mmcblk0p6 | /recovery | emmc | 64MB | Recovery image |
| mmcblk0p7 | /misc | emmc | 1MB | **BCB lives here** |
| mmcblk0p10 | /data | ext4 | 3GB | User data — OTA stored here |

#### Tools

| Tool | Location | Purpose |
|------|----------|---------|
| `write_bcb` | `tools/write_bcb.c` (compile with NDK) | Writes BCB directly to misc partition |
| `chattr` | `tools/chattr.c` | Sets/clears immutable flag (not on stock device) |

`write_bcb` usage:
```bash
# Write BCB to trigger recovery with update command
write_bcb /dev/block/mmcblk0p7 "--update_package=/data/update.zip" "--locale=en_US"

# Clear BCB (all zeros)
write_bcb /dev/block/mmcblk0p7
```

BCB structure written (2048 bytes):
```
command[32]  = "boot-recovery\0..."
status[32]   = "\0..."
recovery[768]= "recovery\n--update_package=/data/update.zip\n--locale=en_US\n\0..."
stage[32]    = "\0..."
reserved[1184]
```

#### Recommended: Framework Install Path (Proven Working)

The framework path via `RecoverySystem.installPackage()` is the most reliable method.
It handles uncrypt, BCB, and reboot automatically — the same path ERU uses.

**Prerequisites:**
1. Recovery patched with our key (Phase 5) — comment line stripped
2. `flash_recovery` disabled (see below)
3. OTA at `/data/update.zip`
4. `install_ota.dex` on device

```bash
# 1. Disable flash_recovery
adb root && adb remount
adb shell mv /system/bin/install-recovery.sh /system/bin/install-recovery.sh.disabled

# 2. Patch recovery (see Phase 5)

# 3. Push OTA
adb push firmware/repack/output/20190521_MGA1_20210616-rooted-signed-v2.zip /data/update.zip

# 4. Push and run installer
adb push tools/install_ota.dex /data/local/tmp/
adb shell "CLASSPATH=/data/local/tmp/install_ota.dex app_process / InstallOTA"
# Exit code 255 is expected (process killed during reboot)
# NOTE: Class name is just "InstallOTA" (no package). Do NOT use "com.android.InstallOTA".

# 5. Wait for install (~3-5 minutes) and verify
adb connect 192.168.1.177:5555
adb root
adb shell getprop ro.build.display.id    # Should show new build
```

See [OTA_INSTALL_LOG.md](OTA_INSTALL_LOG.md) for full attempt history.

#### Alternative: Manual BCB Path

#### 6a. Ensure prerequisites

**Recovery /res/keys** must have our mincrypt signing key installed. See Phase 5.

**CRITICAL:** `flash_recovery` runs on every normal boot and restores the recovery
partition from boot.img + `/system/recovery-from-boot.p` using `applypatch`. It operates
on the **raw partition** (first 23.7MB), not individual files. If boot.img source
matches its expected SHA1, `applypatch` reconstructs the stock recovery regardless of
ramdisk file sizes. **Phase 5 MUST be re-done before each install attempt if the device
has booted normally since the last patch.**

> Previous note claimed the ramdisk size difference prevented `applypatch` from restoring.
> This is likely wrong — `applypatch` works on raw partition data, not ext4 files.
> Attempt #9 failed because recovery was not re-patched after a normal boot.

To permanently prevent `flash_recovery` from overwriting our patch, rename the script:
```bash
adb root && adb remount
adb shell mv /system/bin/install-recovery.sh /system/bin/install-recovery.sh.disabled
```

#### 6b. Push OTA to /data/update.zip

```bash
adb root

# If immutable block is active, remove it first
adb shell "/data/local/tmp/chattr -i /data/update.zip" 2>/dev/null
adb shell "rm -f /data/update.zip"

# Push signed OTA
adb push firmware/repack/output/20190521_MGA1_20210616-rooted-signed.zip /data/update.zip

# Verify
adb shell "ls -la /data/update.zip"
# Expected: 563702339 bytes (~538MB)
```

#### 6c. Write BCB and command file

```bash
# Deploy write_bcb if not already on device
adb push tools/write_bcb /data/local/tmp/write_bcb
adb shell chmod 755 /data/local/tmp/write_bcb

# Write BCB to misc partition (THIS IS THE CRITICAL STEP)
adb shell /data/local/tmp/write_bcb /dev/block/mmcblk0p7 \
  "--update_package=/data/update.zip" "--locale=en_US"

# Also write command file as backup
adb shell "rm -rf /cache/recovery/*"
adb shell "mkdir -p /cache/recovery"
adb shell 'echo "--update_package=/data/update.zip" > /cache/recovery/command'
adb shell 'echo "--locale=en_US" >> /cache/recovery/command'

# Verify BCB was written
adb shell "dd if=/dev/block/mmcblk0p7 bs=2048 count=1 2>/dev/null | strings"
# Expected: boot-recovery, recovery, --update_package=/data/update.zip, --locale=en_US
```

#### 6d. Trigger install

```bash
# Reboot into recovery
adb reboot recovery
```

Why `adb reboot recovery` works here (unlike previous attempts):
- The BCB is already written by `write_bcb` — bootloader reads BCB, sees "boot-recovery", boots recovery
- Recovery reads BCB, finds `--update_package=/data/update.zip`
- Recovery mounts `/data` (defined in recovery's fstab), reads the OTA directly
- No uncrypt or block.map needed

What happens:
1. `adb reboot recovery` → init `do_powerctl("reboot,recovery")` → kernel reboot
2. Bootloader reads BCB → sees `"boot-recovery"` → boots recovery partition
3. Recovery `get_args()` reads BCB → finds `"--update_package=/data/update.zip"`
4. Recovery `ensure_path_mounted("/data/update.zip")` → mounts `/dev/block/mmcblk0p10` as `/data`
5. Recovery `install_package()` → reads OTA, verifies signature against `/res/keys`
6. If signature matches: applies OTA (formats /system, writes new system files)
7. `finish_recovery()` → copies log to `/cache/recovery/last_log`, clears BCB, reboots

**Expected time:** 2-5 minutes for verification + install.

#### 6e. Fallback: Push to /cache

If recovery can't mount `/data` for any reason, push the OTA to `/cache` instead:

```bash
adb root

# Verify space (need 538MB, /cache is 592MB)
adb shell "df -h /cache"

# Push to /cache
adb push firmware/repack/output/20190521_MGA1_20210616-rooted-signed.zip /cache/update.zip

# Write BCB
adb shell /data/local/tmp/write_bcb /dev/block/mmcblk0p7 \
  "--update_package=/cache/update.zip" "--locale=en_US"

# Write command file backup
adb shell "rm -rf /cache/recovery/*"
adb shell "mkdir -p /cache/recovery"
adb shell 'echo "--update_package=/cache/update.zip" > /cache/recovery/command'
adb shell 'echo "--locale=en_US" >> /cache/recovery/command'

# Reboot
adb reboot recovery
```

#### 6f. Fallback: app_process + DEX

If manual BCB approaches fail, call the Java API directly to let the framework handle everything:

```java
// tools/InstallUpdate.java
import android.os.Looper;
import android.os.RecoverySystem;
import java.io.File;

public class InstallUpdate {
    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: InstallUpdate <path-to-update.zip>");
            System.exit(1);
        }
        File pkg = new File(args[0]);
        if (!pkg.exists()) {
            System.err.println("File not found: " + args[0]);
            System.exit(1);
        }
        Looper.prepareMainLooper();
        Class<?> at = Class.forName("android.app.ActivityThread");
        Object thread = at.getMethod("systemMain").invoke(null);
        Object ctx = at.getMethod("getSystemContext").invoke(thread);
        RecoverySystem.installPackage(
            (android.content.Context) ctx, pkg);
    }
}
```

Compile and deploy:

```bash
ANDROID_JAR="/opt/homebrew/share/android-commandlinetools/platforms/android-34/android.jar"
D8="/opt/homebrew/share/android-commandlinetools/build-tools/34.0.0/d8"

javac -source 11 -target 11 -cp "$ANDROID_JAR" -d /tmp/ota_build tools/InstallUpdate.java
cd /tmp/ota_build && jar cf ../InstallUpdate.jar *.class && cd -
$D8 --lib "$ANDROID_JAR" --output /tmp/ /tmp/InstallUpdate.jar
cp /tmp/classes.dex tools/InstallUpdate.dex

adb push tools/InstallUpdate.dex /data/local/tmp/
adb shell "CLASSPATH=/data/local/tmp/InstallUpdate.dex \
  app_process /data/local/tmp InstallUpdate /data/update.zip"
```

**Caveats:** `ActivityThread.systemMain()` is designed for `system_server`. The system
context may not have `RECOVERY_SERVICE` registered, causing `setupBcb()` to fail with
NPE. If this happens, use Approach A or B instead.

#### 6g. After reboot — verify

After the device comes back up on the new firmware, protection is automatically applied by `ota_postinstall`:

```bash
adb connect 192.168.1.177:5555
adb root

# Quick verification
adb shell "whoami; getenforce; test -f /sdcard/.wolfDev && echo wolfDev=OK; echo x > /data/update.zip 2>&1 && echo OTA=WRITABLE || echo OTA=BLOCKED"
# Expected: root, Permissive, wolfDev=OK, OTA=BLOCKED
```

Or use the full automated deployment + verification: `./tools/deploy_ota.sh <ota-zip>`

### Phase 7: Post-Update Verification

After the device reboots:

```bash
adb connect 192.168.1.177:5555

# Verify root
adb root                      # Should succeed
adb shell whoami              # Should say "root"

# Verify properties
adb shell getprop ro.debuggable       # Should be 1
adb shell getprop ro.secure           # Should be 0
adb shell getprop ro.build.type       # Should be user (stealth)
adb shell getprop ro.adb.secure       # Should be 0

# Verify SELinux
adb shell getenforce                  # Should be Permissive

# Verify stealth (no su binary)
adb shell "ls /system/xbin/su 2>&1"  # Should say "No such file"

# Verify OTA protection
adb shell ls -la /system/etc/security/otacerts.zip  # Should be our dummy (~1KB)
```

## Regenerating Keys

If you need to generate new signing keys:

```bash
# 1. Generate new RSA key pair
openssl req -x509 -newkey rsa:2048 -keyout firmware/keys/ota_signing.key \
  -out firmware/keys/ota_signing.x509.pem -days 3650 -nodes \
  -subj "/CN=OTASigner/O=None"

# 2. Convert to PK8 DER (for signing script)
openssl pkey -in firmware/keys/ota_signing.key -outform DER \
  -out firmware/keys/ota_signing.pk8

# 3. Create otacerts.zip (for system partition)
cd firmware/keys && zip otacerts_dummy.zip ota_signing.x509.pem
mv otacerts_dummy.zip ../keys/ && cd -

# 4. Generate mincrypt key (for recovery /res/keys)
python3 << 'EOF'
from cryptography import x509
from cryptography.hazmat.backends import default_backend

with open("firmware/keys/ota_signing.x509.pem", "rb") as f:
    cert = x509.load_pem_x509_certificate(f.read(), default_backend())

nums = cert.public_key().public_numbers()
N, E = nums.n, nums.e
nwords = N.bit_length() // 32
B = 1 << 32
n0inv = B - pow(N % B, -1, B)
R = 1 << (nwords * 32)
RR = (R * R) % N

def to_words(v, count):
    return [str((v >> (32*i)) & 0xFFFFFFFF) for i in range(count)]

n_str = ",".join(to_words(N, nwords))
rr_str = ",".join(to_words(RR, nwords))

with open("firmware/keys/recovery_res_keys", "w") as f:
    # NOTE: Comment line is for human reference only.
    # When installing to recovery /res/keys, STRIP THIS LINE.
    # AOSP load_keys() fails if line doesn't start with 'v'.
    f.write(f"# Key info: {N.bit_length()}-bit RSA, exponent={E}\n")
    f.write(f"v2 {{{nwords},0x{n0inv:x},{{{n_str}}},{{{rr_str}}}}}\n")

print("Written firmware/keys/recovery_res_keys")
EOF

# 5. After regenerating, you MUST:
#    - Re-sign the OTA (Phase 4)
#    - Re-patch the recovery ramdisk (Phase 5)
```

## Recovery / Rollback

### Restore original recovery keys

```bash
adb root
adb shell mount -t ext4 -o rw /dev/block/mmcblk0p6 /data/local/tmp/recovery_mnt

# Option A: Use on-device backup
adb shell cp /data/local/tmp/recovery_mnt/ramdisk-recovery.img.bak \
              /data/local/tmp/recovery_mnt/ramdisk-recovery.img

# Option B: Use local backup
adb push firmware/backups/ramdisk-recovery_original.img \
         /data/local/tmp/recovery_mnt/ramdisk-recovery.img

adb shell "chmod 644 /data/local/tmp/recovery_mnt/ramdisk-recovery.img && sync"
adb shell umount /data/local/tmp/recovery_mnt
```

### Restore original recovery partition entirely

```bash
adb root
adb push firmware/backups/recovery_original.img /data/local/tmp/recovery.img
adb shell dd if=/data/local/tmp/recovery.img of=/dev/block/mmcblk0p6
adb shell sync
```

### If device won't boot after OTA

1. **Fastboot** — Bootloader has fastboot support. Hold volume buttons during boot to enter fastboot, then flash the backup boot.img.
2. **SD card recovery** — Bootloader supports `sd_recovery` for booting from SD.
3. **UART console** — ttySAC0 at 115200 baud provides U-Boot shell for manual recovery.

## Modification Summary

All changes made to the OTA, for reference:

| Component | File | Change | Purpose |
|-----------|------|--------|---------|
| boot.img | `default.prop` | `ro.secure=1` → `0` | adbd runs as root |
| boot.img | `default.prop` | `ro.adb.secure=1` → `0` | Matches expected state (property check compiled out in stock adbd) |
| boot.img | `default.prop` | `ro.debuggable=0` → `1` | `adb root` works |
| boot.img | `default.prop` | `persist.sys.usb.config=none` → `adb` | ADB on from boot |
| boot.img | `sbin/adbd` | `cbz w8,...` → `b ...` (VA 0x40ce0c) | **Disables ADB auth** — stock binary has `ALLOW_ADBD_NO_AUTH=0`, property check compiled out |
| boot.img | cmdline | `buildvariant=userdebug androidboot.selinux=permissive` | SELinux permissive (`buildvariant` has no runtime effect) |
| system | `build.prop` | `persist.sys.usb.config=none` → `adb` | ADB on from boot |
| system | `build.prop` | Added `ro.adb.secure=0` | No ADB auth needed (belt-and-suspenders) |
| system | `build.prop` | `ro.product.manufacturer=Malata` → `NEXELL` | Correct value for `DeviceInfo.IsBuiltIn()` |
| system | `build.prop` | `ro.product.model=MalataSamsungArgon1` → `AOSP on avn_ref` | Correct value for `DeviceInfo.IsBuiltIn()` |
| system | `etc/security/otacerts.zip` | Replaced with dummy cert | Blocks uncontrolled OTAs |
| system | `bin/install-recovery.sh` | Boot script | Disables iFit auto-start, enables stock apps, revokes overlays, fixes settings on every boot |
| system | `priv-app/com.ifit.eru-*/*.apk` | NOT modified | Cannot re-sign (`sharedUserId="android.uid.system"`). Handled by IFW + boot script instead |
| system | `priv-app/com.ifit.launcher-*/*.apk` | Modified APK | `HOME` category removed — stock Launcher3 is sole home |
| recovery | `bin/install-recovery.sh` | Boot script (copy) | Same — OTA copies recovery/ to /system/ |
| system | `etc/ifit_firewall.xml` | Added (backup) | IFW rules backup — boot script restores to `/data/system/ifw/` if missing |
| OTA | `ota_postinstall` | Postinstall binary | Creates .wolfDev, immutable update.zip, ERU prefs, IFW rules |
| OTA | `META-INF/CERT.*` | Removed old iFit signatures | Replaced with our signature |
| recovery | `/res/keys` | Replaced with our cert in mincrypt format | Recovery trusts our OTA signature |

### ota_postinstall (Recovery Postinstall)

`tools/ota_postinstall.c` is compiled as `ota_postinstall_arm64` and included in the OTA at `/ota_postinstall`. The updater-script runs it after system files are installed. It performs 7 steps:

1. **Mount `/data`** — mounts the data partition (ext4 on `/dev/block/mmcblk0p10`)
2. **Create `/sdcard/.wolfDev`** — empty file, triggers ERU developer mode (ADB persistence)
3. **Create `/data/misc/adb/`** — ensures ADB key storage directory exists (owner system:shell)
4. **Create immutable `/data/update.zip`** — blocks all ERU firmware downloads (FS_IMMUTABLE_FL via ioctl)
5. **Pre-seed ERU SharedPreferences** — writes `eru-shared-prefs.xml` with `isTabletConfigCompleteKey=true` and `inDeveloperModeKey=true` (owner system:system, mode 0660). Prevents ERU's `configureTabletAsNecessary()` from setting immersive mode, disabling stock apps, disabling dev mode.
6. **Install Intent Firewall rules** — writes 21 IFW rules (19 action-based + 2 component-filter) to `/data/system/ifw/ifit_firewall.xml` (owner system:system, mode 0600). This is the PRIMARY defense against iFit interference. The content is embedded in the C source as a string constant.
7. **Unmount `/data`** — syncs and unmounts after all operations complete.

### Modified System APKs

Two system APKs in `/system/priv-app/` are modified to prevent iFit auto-start at the source:

**ERU (`com.ifit.eru`):** NOT modified — it uses `sharedUserId="android.uid.system"` which requires the platform signing key we don't have. Re-signing with any other key causes `INSTALL_FAILED_SHARED_USER_INCOMPATIBLE`. Instead, ERU's harmful behaviors are blocked by the Intent Firewall (21 rules blocking broadcasts at framework level) and the boot script (disables `TabletStartupReceiver`, `KeepTheWolfAliveService`, and other components via `pm disable`).

The `TabletStartupReceiver` handles `BOOT_COMPLETED` and triggers the entire iFit startup chain:
1. Launches `com.ifit.rivendell` (media player)
2. Launches `com.ifit.glassos_service` (core services)
3. After 1s delay: runs `configureTabletAsNecessary()` (sets immersive mode, disables stock apps)
4. Runs `resetDeveloperOptionsIfNeeded()` (disables ADB unless `.wolfDev` exists)
5. Runs `launchWolfIfNotValinor()` (launches `com.ifit.standalone`, starts `KeepTheWolfAliveService`)

The IFW component-filter blocks delivery of BOOT_COMPLETED to TabletStartupReceiver. The boot script also `pm disable`s it as belt-and-suspenders.

**iFit Launcher (`com.ifit.launcher`):** The `android.intent.category.HOME` category is removed from the `MainActivity` intent filter. This prevents it from competing with `com.android.launcher3` as the home screen. The launcher app is still installed and can be opened manually.

Both APKs are re-signed with `firmware/keys/system-mod.jks` (password: `android`, 2048-bit RSA, 100-year validity).

### install-recovery.sh (Boot Script)

Replaces the stock `applypatch` script that restores recovery. Runs as `flash_recovery` service (`class main, oneshot`) on every boot. Forks a background process that waits for `sys.boot_completed=1` + 5s then:

- `pm disable com.ifit.eru/.receivers.TabletStartupReceiver` — disables boot receiver (belt-and-suspenders with manifest removal)
- `pm disable com.ifit.eru/.services.KeepTheWolfAliveService` — prevents auto-restart of standalone (Android persists "started" services across reboots)
- `pm disable com.ifit.launcher` — disables iFit launcher (belt-and-suspenders with manifest change)
- `am force-stop com.ifit.eru` + `am force-stop com.ifit.standalone` — stops iFit apps if they started before disables took effect
- `pm enable` on 11 stock apps — reverses any prior ERU lockdown (Launcher3, Settings, Browser, Camera, Gallery, Calculator, Sound Recorder, Calendar Provider, Downloads UI, OpenWnn, Pico TTS)
- `appops set SYSTEM_ALERT_WINDOW deny` on 7 iFit apps — revokes overlay/draw-over-other-apps permission from rivendell, glassos_service, eru, gandalf, arda, mithlond, launcher. Prevents GlassOS apps from drawing UI over the home screen.
- `settings put secure user_setup_complete 1` — enables Home/Recents buttons (ERU sets this to 0)
- `settings put global development_settings_enabled 1` — keeps Developer Options accessible
- `settings put global adb_enabled 1` — keeps ADB on
- `settings put secure install_non_market_apps 1` — allows sideloading APKs
- `settings delete global policy_control` — clears immersive mode

### Deployment (deploy_ota.sh)

`tools/deploy_ota.sh` automates the full deployment. It handles the 4 things that can't be baked into the OTA:

1. **Patch recovery signing key** — recovery partition is not written by updater-script
2. **Disable stock install-recovery.sh** — prevents `applypatch` from restoring stock recovery before reboot
3. **Push OTA + trigger install** — clears immutable, pushes zip, calls `RecoverySystem.installPackage()`
4. **Wait + verify** — 11 automated checks (root, SELinux, build type, .wolfDev, OTA block, ERU prefs, etc.)

Usage: `./tools/deploy_ota.sh <path-to-signed-ota.zip>`

**Stealth notes:** `ro.build.type`, `ro.build.flavor`, `ro.build.description`, `ro.build.fingerprint` are left as stock `user` values. No su binary is added. Apps see a clean, unmodified device. Root access comes from ramdisk properties (`ro.secure=0`, `ro.debuggable=1`) and ADB auth bypass comes from the adbd binary patch — neither is inspectable by apps.

## Related Documentation

- [OTA_FIRMWARE_ANALYSIS.md](OTA_FIRMWARE_ANALYSIS.md) — Detailed analysis of the original OTA contents
- [OTA_INSTALL_LOG.md](OTA_INSTALL_LOG.md) — Chronological log of all install attempts
- [RAMDISK_PACKING.md](RAMDISK_PACKING.md) — macOS ramdisk packing pitfalls and correct procedure
- [RECOVERY_DEBUG_ANALYSIS.md](RECOVERY_DEBUG_ANALYSIS.md) — Recovery boot loop analysis
- [RECOVERY_OTA_INSTALL.md](RECOVERY_OTA_INSTALL.md) — Block map install procedure
- [MANUAL_APPLY_PLAN.md](MANUAL_APPLY_PLAN.md) — Manual apply approaches (bypass recovery)
- [../guides/PROTECTION.md](../guides/PROTECTION.md) — Current update blocking setup
