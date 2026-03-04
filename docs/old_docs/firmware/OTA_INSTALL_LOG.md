# OTA Install Attempts Log

> Chronological log of all OTA installation attempts, including the framework-path approach and diagnostic findings.

## Attempt History

| # | Date | Method | OTA Type | Boot.img | Progress | Result | Root Cause |
|---|------|--------|----------|----------|----------|--------|------------|
| 1 | 2026-02-10 | Manual BCB | Full | N/A | 0% | File not found | `/sdcard/` not mounted in recovery |
| 2 | 2026-02-10 | Manual BCB | Full | N/A | 0% | Wrong file | Stale `uncrypt_file` |
| 3 | 2026-02-10 | Manual BCB | Full | N/A | 0% | Wrong file | Stale `uncrypt_file` |
| 4 | 2026-02-10 | Direct BCB + reboot | Full | N/A | 0% | Recovery never ran | BCB not written |
| 5 | 2026-02-10 | Direct dd | N/A | 2021 kernel | N/A | Boot hang | Kernel/system mismatch |
| 6 | 2026-02-11 | `adb reboot recovery` | Full | N/A | 0% | Boot loop | BCB empty, no block.map |
| 7 | 2026-02-11 | `svc power reboot` | Full | N/A | 0% | Recovery never ran | Bootloader didn't recognize "recovery-update" |
| 8 | 2026-02-11 | `write_bcb` + `adb reboot` | Full | N/A | 0% | Boot loop | Recovery crash (likely OOM on 538MB mmap) |
| 9 | 2026-02-11 | `write_bcb` + `adb reboot` | Full | N/A | 0% | Boot loop | `flash_recovery` restored stock keys |
| 10 | 2026-02-11 | Framework (`installPackage`) | Full (zip -r) | Broken ramdisk | ~25% | Boot hang | Bad zip structure (dir entries, case collisions, wrong flags) |
| 11 | 2026-02-11 | Framework (`installPackage`) | Full (repack_ota.py) | Broken ramdisk | ~75% | Boot hang | boot.img content invalid (ramdisk uid/gid, path prefix bugs) |
| 12 | 2026-02-11 | Framework (`installPackage`) | **Sysonly** | **Original** | **100%** | **SUCCESS** | N/A — proved system changes work, boot.img is the problem |
| 13 | 2026-02-11 | dd (after sysonly) | N/A | Broken ramdisk | N/A | Boot hang | Same ramdisk bugs as #11 (uid/gid, ./ prefix, inode=0) |
| 14 | 2026-02-11 | Framework (`installPackage`) | Full (repack_ota.py v2) | Fixed ramdisk | ~100%? | Boot hang | Likely kernel/system mismatch (see #15-16) |
| 15 | 2026-02-11 | dd (factory system) | N/A | 2021 identity repack | N/A | Boot hang | Kernel/system mismatch — 2021 boot.img on 2019 factory system |
| 16 | 2026-02-11 | dd (factory system) | N/A | **Factory 2017** | N/A | **BOOTS** | Confirmed dd works; mismatch was the issue |
| 17 | 2026-02-11 | dd (2021 system) | N/A | **Original 2021** | N/A | **BOOTS** | dd via `su` pipe works; baseline confirmed |
| 18 | 2026-02-11 | dd (2021 system) | N/A | **Binary-patched** | N/A | **BOOTS** | Binary ramdisk patching works! adb root + Permissive |
| 19 | 2026-02-11 | Framework (`installPackage`) | **Full (v2)** | **Binary-patched** | **100%** | **SUCCESS** | Full OTA with binary-patched boot.img — complete root solution |
| 20 | 2026-02-12 | Framework (`installPackage`) | Full MGA1_20210901 | **mkcpioimg.py** | 100%? | **Boot hang** | mkcpioimg.py inode mismatch (1-43 vs 300000+) — confirmed on matching system |
| 21 | 2026-02-12 | Framework (`installPackage`) | Full MGA1_20210901 | **Binary-patched** | 100%? | **Boot hang** | cmdline `androidboot.selinux=permissive` — not present in any working build |
| 22 | 2026-02-12 | Framework (`installPackage`) | Full MGA1_20210901 | Binary-patched v2 | 100%? | **Boot hang** | Null byte padding (`\0`) + macOS gzip recompression (see #23) |
| 23 | 2026-02-12 | Framework (`installPackage`) | **Full MGA1_20210901** | **Binary-patched v3** | **100%** | **SUCCESS** | Fixed: newline padding (`\n`) + Python gzip recompression |
| 24 | 2026-02-12 | Framework (`installPackage`) | **Full MGA1_20210901** | **Binary-patched v3** | **100%** | **SUCCESS** | userdebug + selinux permissive cmdline — proves #21 was misdiagnosed |
| 25 | 2026-02-12 | Framework (`installPackage`) | **Full MGA1_20210901** | **Binary-patched v3** | **100%** | **SUCCESS** | Stealth: no su, `ro.build.type=user`, apps see clean device |

## Attempt Details

### Attempts #1-9: Direct Recovery Path

See [RECOVERY_DEBUG_ANALYSIS.md](RECOVERY_DEBUG_ANALYSIS.md) and [RECOVERY_OTA_INSTALL.md](RECOVERY_OTA_INSTALL.md) for full details.

Key lessons:
- BCB must be written to misc partition explicitly
- `flash_recovery` restores stock recovery on every normal boot
- Bypassing uncrypt causes hard crash (OOM on 538MB mmap)

### Attempt #10: Framework Path — Bad Zip Structure

**Method:** `RecoverySystem.installPackage()` via `install_ota.dex`

**OTA:** Created with `cd firmware/repack/modified && zip -r -9 ../output/ota.zip .`

**Result:** Progress bar reached ~25% then switched to boot logo. Device stuck.

**Root cause:** The `zip -r` command produced a fundamentally different zip structure:
- 1613 entries vs original 1490 (123 extra directory entries)
- macOS case-insensitive filesystem lost 5 ringtone files (e.g., `ANDROMEDA.ogg` vs `Andromeda.ogg`)
- Different flags: Python zip used `flags=0x0800` vs original `flags=0x0808`
- Different compression characteristics

### Attempt #11: Framework Path — Fixed Zip, Broken Boot.img

**Method:** `RecoverySystem.installPackage()` via `install_ota.dex`

**OTA:** Created with `tools/repack_ota.py` — copies entries from original zip, only replaces changed files.

**Result:** Progress bar reached ~75% then switched to boot logo. Significant improvement over #10.

**Analysis:** 75% = end of system extraction phase in updater-script. System was fully extracted successfully. The crash occurs during boot.img write or immediately after.

The boot.img was built with a ramdisk that had critical flaws (see [Boot.img Ramdisk Issues](#bootimg-ramdisk-issues) below).

### Attempt #12: Sysonly OTA — SUCCESS

**Method:** `RecoverySystem.installPackage()` via `install_ota.dex`

**OTA:** Created with `tools/repack_ota_sysonly.py` — same system modifications, but boot.img and bootloader are byte-identical to original.

**Result:** Installed to 100%. Device booted successfully.

**Post-install state:**
```
ro.build.display.id = MGA1_20210616
ro.build.type = userdebug
getenforce = Permissive
/system/xbin/su exists with SUID (04755)
```

**Limitation:** Original boot.img has `ro.debuggable=0` and `ro.adb.secure=1`, so `adb root` fails. Root access via `echo 'command' | /system/xbin/su`.

### Attempt #13: dd Flash After Sysonly

After sysonly success, attempted to flash modified boot.img via dd:

```bash
echo "dd if=/data/local/tmp/boot.img of=/dev/block/mmcblk0p1 bs=1M" | /system/xbin/su
```

**Result:** Boot hang on logo. Required factory reset.

**Root cause:** Same ramdisk bugs as attempt #11. The boot.img ramdisk was built on macOS with `find . | cpio -o -H newc | gzip`, producing an invalid cpio archive.

### Attempt #14: Full OTA with Fixed Ramdisk

**Method:** `RecoverySystem.installPackage()` via `install_ota.dex`

**OTA:** Created with `tools/repack_ota.py` using boot.img rebuilt with `tools/mkcpioimg.py` (fixed ramdisk tool).

**Ramdisk verification before install:**
- 43 entries (matches original)
- All uid=0, gid=0 (matches original)
- Unique inodes 1-43
- No `./` prefix, no `.` root entry
- Only `default.prop` content differs (4 property changes)
- Uncompressed cpio size matches original exactly (3,601,664 bytes)

**Result:** Boot hang on logo. Required factory reset.

**Status:** Root cause was kernel/system mismatch, same as attempt #5. See attempts #15-16 below.

### Attempt #15: dd Identity Repack on Factory System

**Method:** `adb root` → `dd if=boot.img of=/dev/block/mmcblk0p1`

**Boot.img:** Byte-identical to original 2021 OTA boot.img (identity repack verified in Phase 0A — 0 byte differences). Uses original ramdisk binary, original cmdline `buildvariant=user`, all original parameters.

**System:** Factory 2019 (restored by hardware reset from backup partition mmcblk0p9)

**Result:** Boot hang on logo. Required factory reset.

**Root cause:** Kernel/system mismatch. The 2021 kernel (built Jun 16 2021, `buildvariant=user`, patch 2019-08) cannot boot with the 2019 factory system partition. This is the same root cause as attempt #5. The sysonly OTA (#12) worked because it installed both the 2021 system AND 2021 boot.img together.

**Key learning:** Cannot test 2021 boot.img variants on the factory system. Must install sysonly OTA first to get matching 2021 system.

### Attempt #16: dd Factory Boot.img on Factory System (Control Test)

**Method:** `adb root` → `dd if=boot_factory_reset.img of=/dev/block/mmcblk0p1` (full 64MB partition dump)

**Boot.img:** Factory 2017 userdebug (`buildvariant=userdebug`, patch 2017-10, `ro.debuggable=1`)

**System:** Factory 2019

**Result:** Boots successfully. `ro.build.display.id = argon_20190521`.

**Conclusion:** dd flashing works correctly. The boot hang in #15 was purely due to kernel/system version mismatch, not a dd or tooling issue.

### Attempt #17: dd Original 2021 Boot.img on 2021 System (Baseline)

**Method:** Sysonly OTA → `echo 'dd ...' | su` → `adb reboot`

**Boot.img:** Original unmodified 2021 boot.img (same file recovery wrote during sysonly OTA). 22,302,720 bytes.

**System:** 2021 (via sysonly OTA)

**Result:** Boots successfully in ~10 seconds. `ro.build.type=userdebug`.

**Conclusion:** dd flashing via `su` pipe works correctly on the 2021 system. This was the missing baseline — previous dd tests (#15-16) were on the factory system with `adb root`.

### Attempt #18: dd Binary-Patched Boot.img on 2021 System — SUCCESS

**Method:** Same as #17, using binary-patched boot.img.

**Boot.img:** Built with `mkbootimg` using original kernel, DTB, cmdline (`buildvariant=user`), and a **binary-patched ramdisk**. The ramdisk was created by decompressing the original gzip, directly replacing 7 bytes in the cpio stream (the 4 property values), and recompressing. The cpio structure (inode numbers, modes, entry ordering, padding) is completely preserved from the original.

**Changes (7 bytes in decompressed cpio):**
- `ro.secure=1` → `ro.secure=0`
- `ro.adb.secure=1` → `ro.adb.secure=0`
- `ro.debuggable=0` → `ro.debuggable=1`
- `persist.sys.usb.config=none\n` → `persist.sys.usb.config=adb\n\n` (padded to same byte length)

**Result:** **BOOTS SUCCESSFULLY!**

**Post-boot state:**
```
ro.build.type = userdebug
ro.build.display.id = MGA1_20210616
ro.debuggable = 1
ro.secure = 0
ro.adb.secure = 0
adb root = works (already running as root)
getenforce = Permissive
```

**Root cause of previous failures confirmed:** The `mkcpioimg.py` cpio rebuild (attempts #11, #13, #14) produced archives with different inode numbers (1-43 vs original 300000-300042) and TRAILER mode (0 vs 0644). Despite identical file content, these structural differences cause the kernel to hang during initramfs extraction. Binary-patching the original ramdisk preserves the exact cpio structure and avoids this issue entirely.

### Attempt #19: Full OTA with Binary-Patched Boot.img — SUCCESS

**Method:** `RecoverySystem.installPackage()` via `install_ota.dex`

**OTA:** `20190521_MGA1_20210616-rooted-signed-v2.zip` (566,058,594 bytes). Built with `repack_ota.py` using binary-patched boot.img. Includes system changes (build.prop, su, otacerts, updater-script).

**Pre-install setup:**
1. Device running 2021 system (from sysonly OTA) + binary-patched boot.img (from attempt #18)
2. Disabled `flash_recovery` (`mv install-recovery.sh install-recovery.sh.disabled`)
3. Recovery already had our signing key (from earlier patching, survived sysonly OTA)

**Result:** **FULL SUCCESS.** Installed and booted in ~30 seconds.

**Post-boot state:**
```
ro.build.type = userdebug
ro.build.display.id = MGA1_20210616
ro.debuggable = 1
ro.secure = 0
ro.adb.secure = 0
adb root = works (already running as root)
getenforce = Permissive
/system/xbin/su = present with SUID (04755)
```

**This is the complete root solution.** A single OTA install provides: root access via `adb root`, permissive SELinux, su binary, and all system modifications.

### Attempt #20: MGA1_20210901 with mkcpioimg.py Ramdisk — BOOT HANG

**Method:** `RecoverySystem.installPackage()` via `install_ota.dex`

**OTA:** `MGA1_20210901-rooted-signed.zip` (560,001,989 bytes). MGA1_20210901 firmware (Sep 2021 build). Boot.img ramdisk rebuilt with `mkcpioimg.py` (TRAILER mode fixed to 0644). System modifications same as #19 (build.prop, su, otacerts, updater-script).

**Pre-install setup:**
1. Device running MGA1_20210616 system + binary-patched boot.img (from attempt #19)
2. Disabled `flash_recovery`
3. Recovery had our signing key (verified — survived because patched boot.img SHA1 doesn't match `install-recovery.sh` source, so `applypatch` fails silently)

**Result:** **Boot logo hang.** System and boot from MGA1_20210901 both installed (matching versions), but device stuck at boot logo. No ADB reachable.

**Root cause:** `mkcpioimg.py` inode numbers (1-43) vs original (300000-300042). **This definitively proves mkcpioimg.py causes boot hang** — attempt #14's failure was attributed to kernel/system mismatch, but both causes were present. Now with matching system, mkcpioimg.py alone causes the hang.

**Recovery:** Hardware factory reset required (hold Power during boot).

**Rebuilt OTA:** `MGA1_20210901-rooted-signed.zip` rebuilt with binary-patched ramdisk (preserving original inode numbers). Ready for re-install after recovery.

### Attempt #21: MGA1_20210901 with Binary-Patched Ramdisk — BOOT HANG

**Method:** `RecoverySystem.installPackage()` via `install_ota.dex`

**OTA:** `MGA1_20210901-rooted-signed.zip` rebuilt with binary-patched ramdisk (preserving original inode numbers 300000-300042). Same system modifications. Boot.img cmdline: `buildvariant=user androidboot.selinux=permissive`.

**Pre-install setup:**
1. Hardware factory reset (from attempt #20 failure)
2. Created `.wolfDev`, pushed `chattr` + `install_ota.dex`
3. Patched recovery (signing key), disabled `flash_recovery`
4. Pushed 560MB OTA, installed via framework (exit 255 = expected)

**Result:** **Boot logo hang.** Binary-patched ramdisk (proven in attempt #18/#19) still failed.

**Root cause:** Boot.img cmdline included `androidboot.selinux=permissive` — this was **never present in any working build**. Attempts #18/#19 used cmdline `buildvariant=user` only. SELinux was already permissive on the device from build.prop settings (`ro.build.type=userdebug`). The extra kernel parameter likely causes an init-time conflict.

**Recovery:** Hardware factory reset required.

**Fix:** Rebuilt OTA with cmdline `buildvariant=user` (no `androidboot.selinux=permissive`). New boot.img: `boot-binpatch-v2.img`.

### Attempt #22: MGA1_20210901 Binary-Patched v2 — BOOT HANG

**Method:** `RecoverySystem.installPackage()` via `install_ota.dex`

**OTA:** `MGA1_20210901-rooted-signed.zip` with corrected cmdline (`buildvariant=user` only). Boot.img: `boot-binpatch-v2.img` (22,298,624 bytes — 4,096 bytes smaller than original).

**Pre-install setup:**
1. Hardware factory reset (from attempt #21 failure)
2. Created `.wolfDev`, pushed `chattr` + `install_ota.dex`
3. Patched recovery (stripped key), disabled `flash_recovery`
4. Pushed 560MB OTA, installed via framework (exit 255 = expected)

**Result:** **Boot logo hang.**

**Root cause:** Two issues with the binary ramdisk patching:

1. **Null byte padding (0x00)**: `persist.sys.usb.config=none` was patched to `adb\0` (null byte filler). In Android init's `load_properties()`, `strchr(sol, '\n')` stops at `\0` (C string terminator), causing early exit from property parsing. The working attempt #18/#19 used `adb\n` (newline filler).

2. **macOS `gzip` recompression**: The ramdisk was recompressed using macOS's `gzip` command (OS byte=0x03), producing a 1,501,959-byte file (4,812 bytes smaller than original). The working attempt #18/#19 used Python's `gzip.compress()` (OS byte=0xff), producing a 1,506,772-byte file (just 1 byte larger than original). The different compression caused the boot.img to be 4,096 bytes (2 pages) smaller than the original.

**Comparison of working vs failing binary patches:**
| Property | Working (#18/#19) | Failing (#22) |
|----------|-------------------|---------------|
| `none` replacement | `adb\n` (0x61 0x64 0x62 0x0a) | `adb\0` (0x61 0x64 0x62 0x00) |
| Gzip tool | Python `gzip.compress(level=6)` | macOS `gzip` command |
| Gzip OS byte | 0xff (unknown) | 0x03 (Unix) |
| Compressed size | 1,506,772 (+1 from original) | 1,501,959 (-4,812 from original) |
| Boot.img size | 22,302,720 (same as original) | 22,298,624 (-4,096 from original) |

**Recovery:** Hardware factory reset required.

### Attempt #23: MGA1_20210901 Binary-Patched v3 — SUCCESS

**Method:** `RecoverySystem.installPackage()` via `install_ota.dex`

**OTA:** `MGA1_20210901-rooted-signed.zip` rebuilt with `tools/binpatch_ramdisk.py` — new tool that uses Python gzip (level 6) and newline padding. Boot.img: `boot-binpatch-v3.img` (22,302,720 bytes — exact same size as original).

**Pre-install setup:**
1. Hardware factory reset (from attempt #22 failure)
2. Created `.wolfDev`, pushed `chattr` + `install_ota.dex`
3. Patched recovery (stripped key), disabled `flash_recovery`
4. Pushed 560MB OTA, installed via framework (exit 255 = expected)

**Result:** **FULL SUCCESS.** MGA1_20210901 firmware installed with root access.

**What was fixed (codified in `tools/binpatch_ramdisk.py`):**
1. Use `\n` (0x0a) padding for `none` → `adb`, NOT `\0` (0x00)
2. Use Python `gzip.compress(compresslevel=6)`, NOT macOS `gzip` command
3. Cmdline is `buildvariant=user` only (no `androidboot.selinux=permissive`)

### Attempt #24: MGA1_20210901 userdebug + selinux permissive — SUCCESS

**Method:** `RecoverySystem.installPackage()` via `install_ota.dex`

**OTA:** `MGA1_20210901-rooted-signed.zip` rebuilt with `boot-userdebug-selinux.img`. Same binary-patched v3 ramdisk as #23 (Python gzip, newline padding). Boot.img cmdline: `buildvariant=userdebug androidboot.selinux=permissive`.

**Pre-install setup:**
1. Hardware factory reset (from attempt #23 ADB auth issue)
2. Created `.wolfDev`, pushed `chattr` + `install_ota.dex`
3. **Pushed ADB public key** to `/data/misc/adb/adb_keys` (safety net for auth)
4. Patched recovery (stripped key on-device), disabled `flash_recovery`
5. Pushed 560MB OTA, installed via framework (exit 255 = expected)

**Result:** **FULL SUCCESS.** MGA1_20210901 firmware installed with root access and userdebug build variant.

**Key findings:**
1. **`androidboot.selinux=permissive` does NOT cause boot hang** — attempt #21's failure was misdiagnosed. The actual cause was the null byte padding + macOS gzip (same as #22).
2. **`buildvariant=userdebug` fixes ADB auth** — adbd's `ALLOW_ADBD_NO_AUTH` is a compile-time flag set to 1 for userdebug builds (Android.mk line 330: `$(if $(filter userdebug eng,$(TARGET_BUILD_VARIANT)),1,0)`). With `buildvariant=user` (attempt #23), adbd ignores `ro.adb.secure=0` entirely.
3. **ADB key pre-install** — pushing `~/.android/adbkey.pub` to `/data/misc/adb/adb_keys` provides a fallback if auth is ever required.

**Why attempt #23 had ADB auth issues:**
The adbd binary checks `ALLOW_ADBD_NO_AUTH` before reading `ro.adb.secure`. On `user` builds, this constant is 0, so the `ro.adb.secure=0` property is never checked. Auth is always required regardless of property values. Source: `NexellCorp/aosp-7.1.2-analysis/core/adb/daemon/main.cpp:173`.

### Phase 0 Host-Side Diagnostics (completed before #15)

Before any flashing, three host-side validation tests were run:

**0A. Identity Repack:** Unpacked original 2021 boot.img, repacked with identical components and parameters using our mkbootimg. Result: **byte-identical** (0 byte differences). Tool is correct.

**0B. Ramdisk Round-Trip:** Extracted original ramdisk, rebuilt with `mkcpioimg.py` (no file changes). Result: All 43 entries match on name, mode, uid, gid, size, and content. Only inode numbers differ (300000-300042 → 1-43) and TRAILER!!! mode (0644 → 0). Both differences are cosmetic — kernel ignores them.

**0C. Gzip Level Check:** Original compressed at ~level 6 (1,506,773 bytes), rebuilt at level 9 (1,501,942 bytes). Both decompress to 3,601,664 bytes. Uncompressed content differs only in the inode/TRAILER fields identified in 0B.

### Factory Boot.img Discovery

The hardware factory reset restores from backup partition (mmcblk0p9), which contains a **different boot.img** than the 2021 OTA:

| Parameter | Factory (2017) | 2021 OTA |
|-----------|---------------|----------|
| Cmdline | `buildvariant=userdebug` | `buildvariant=user` |
| Patch level | 2017-10 | 2019-08 |
| `ro.debuggable` | 1 | 0 |
| `ro.secure` | 1 | 1 |
| `persist.sys.usb.config` | adb | none |
| Build date | 2019-05-21 09:38 | 2021-06-16 12:46 |
| `ALLOW_PERMISSIVE_SELINUX` | 1 (userdebug) | 0 (user) |

The factory boot.img already provides `adb root` (via `ro.debuggable=1` + userdebug variant). Saved to `firmware/backups/boot_factory_reset.img` (64MB partition dump).

### Recovery `package_extract_file` Analysis

Source: `NexellCorp/aosp-7.1.2-analysis/recovery/updater/install.cpp:566-580`

When the updater-script calls `package_extract_file("boot.img", "/dev/block/mmcblk0p1")`, recovery simply opens the block device with `O_WRONLY | O_CREAT | O_TRUNC | O_SYNC`, writes the zip entry bytes, fsyncs, and closes. **No MTD erase, no zero-padding.** Functionally identical to `dd`. The MTD code path (which does erase remaining blocks) is only used for named MTD partitions, not block devices like mmcblk0p1.

---

## Boot.img Ramdisk Issues

### Problems Found in Attempts #10-13

The modified boot.img ramdisk was extracted on macOS and repacked with:
```bash
cd ramdisk_dir && find . | cpio -o -H newc | gzip > ramdisk.img
```

This produced a cpio archive with **four critical bugs**:

| Issue | Original | Broken | Effect |
|-------|----------|--------|--------|
| **uid/gid** | 0:0 (root) | 501:20 (macOS user) | Kernel can't execute `/init` as root |
| **Path prefix** | `init` | `./init` | Files extracted to wrong paths |
| **Root entry** | None (43 entries) | `.` directory (44 entries) | Spurious root directory node |
| **Inode values** | Unique (0x493e0+) | All zero | Kernel treats all entries as hardlinks to first file |

### Why macOS Breaks cpio

1. **uid/gid**: macOS extracts cpio archives as the current user (uid 501). `cpio -o` records the actual filesystem uid/gid. GNU cpio has `--owner=root:root` but macOS bsdcpio does not.

2. **`./` prefix**: `find .` produces paths starting with `./`. AOSP mkbootfs produces paths without any prefix.

3. **Root entry**: `find .` includes `.` as the first entry. AOSP mkbootfs skips the root directory.

4. **inode=0**: When all inodes are zero, the Linux kernel's `init/initramfs.c` (`maybe_link()` function) treats every entry after the first as a hardlink to inode 0 — effectively making all files point to the same data as the first file.

### Fix: `tools/mkcpioimg.py`

Custom Python script that replicates AOSP mkbootfs behavior:
- Forces uid=0, gid=0 for all entries
- Uses `os.path.relpath()` without `./` prefix
- Skips root `.` directory entry
- Assigns unique incrementing inode numbers
- Sets nlink=1 for all entries (matching mkbootfs)
- Pads to 256-byte boundary (matching mkbootfs)
- Uses lowercase hex in headers (matching mkbootfs)

**Verification:** Rebuilt ramdisk from original extracted files produces a cpio archive that is byte-identical to the original except for inode start values (ours start at 1, original at 0x493e0).

---

## Framework Install Path

### Overview

Instead of writing BCB manually and rebooting into recovery (attempts #1-9), we call `RecoverySystem.installPackage()` via the Android framework — the same API that ERU uses.

### `tools/InstallOTA.java`

```java
// Compiled to tools/install_ota.dex
// Usage: CLASSPATH=/data/local/tmp/install_ota.dex app_process / InstallOTA
```

The program:
1. Gets a system context via `ActivityThread.systemMain().getSystemContext()`
2. Calls `RecoverySystem.installPackage(context, new File("/data/update.zip"))`
3. The framework handles: uncrypt_file → setupBcb → ShutdownThread → uncrypt → reboot

**Exit code 255** is expected — the process is killed during reboot.

### Why This Works Better Than Manual BCB

The framework path handles:
- Writing `uncrypt_file` to `/cache/recovery/`
- Calling `setupBcb()` via the RecoverySystemService
- Running `uncrypt` during shutdown (converts `/data/update.zip` to block map)
- Using `@/cache/recovery/block.map` prefix (block device path, not filesystem)
- Proper reboot via `PowerManager.reboot("recovery-update")`

This avoids the OOM crash from attempts #8-9 (which mmap'd 538MB from a mounted filesystem).

---

## OTA Zip Structure

### Original iFit OTA Structure

| Property | Value |
|----------|-------|
| Total entries | 1490 |
| Compression | Most files: DEFLATED (level 6). Small files: STORED |
| Flags | `0x0808` (bit 3 = data descriptors, bit 11 = UTF-8) |
| Create system | 0 (MS-DOS) |
| Create version | 10 |
| Extract version | 10 (stored) or 20 (deflated) |

### `tools/repack_ota.py`

Copies entries from the original zip, only replacing changed files. Preserves:
- Original zip entry ordering
- Compression type and level
- Flag bits and create/extract versions
- Date/time stamps
- External attributes

Replacements:
- `boot.img` — modified ramdisk + cmdline
- `system/build.prop` — userdebug, ADB settings
- `system/etc/security/otacerts.zip` — dummy cert
- `META-INF/com/google/android/updater-script` — su metadata line

Additions:
- `system/xbin/su` — SUID root binary

Removals (old signatures):
- `META-INF/MANIFEST.MF`, `META-INF/CERT.SF`, `META-INF/CERT.RSA`

### `tools/repack_ota_sysonly.py`

Same as `repack_ota.py` but does NOT replace `boot.img` or `bootloader`. Used for diagnostic testing to isolate whether boot.img or system changes cause the boot hang.

---

## Recovery Key Format

### CRITICAL: No Comment Lines

The recovery `/res/keys` file must NOT contain comment lines. AOSP's `load_keys()` in `verifier.cpp` reads the first character of each key entry — if it's not `v`, parsing fails.

The file `firmware/keys/recovery_res_keys` contains a comment line:
```
# Key info: 2048-bit RSA, exponent=65537
v2 {64,0x5ff0878d,...}
```

When patching recovery, strip the comment:
```bash
tail -1 firmware/keys/recovery_res_keys > /tmp/keys_nocomment
# Use /tmp/keys_nocomment as /res/keys in recovery ramdisk
```

---

## Current Status (2026-02-12)

### SOLVED — Full Root OTA for Both Firmware Versions

Both MGA1_20210616 and MGA1_20210901 firmware versions now have working rooted OTAs.

**Attempt #24 (MGA1_20210901) is the latest success.** The binary-patched boot.img provides:
- `adb root` — works natively (userdebug build variant)
- `getenforce` — Permissive (SELinux via cmdline + build.prop)
- `ro.debuggable=1`, `ro.secure=0`, `ro.adb.secure=0`
- Full root access on the Sep 2021 system with Sep 2021 kernel
- Boot.img cmdline: `buildvariant=userdebug androidboot.selinux=permissive`

### Root Cause Summary

Boot hangs across 23 attempts were caused by **three distinct classes of issues**:

**Class 1: macOS cpio bugs (attempts #11-13)**
Wrong uid/gid (501:20 instead of 0:0), `./` path prefix, extra `.` root entry, inode=0 hardlinks.

**Class 2: mkcpioimg.py inode mismatch (attempts #14, #20)**
Fixed macOS bugs but used inode numbers 1-43 instead of original 300000+. Kernel hangs during initramfs extraction. Confirmed on matching system (attempt #20).

**Class 3: Binary patching errors (attempts #21-22)**
- **Attempt #21**: Originally attributed to `androidboot.selinux=permissive` — **MISDIAGNOSED**. Actual cause was null byte padding + macOS gzip (same as #22). Proven by attempt #24 which boots with `androidboot.selinux=permissive`.
- **Attempt #22**: Null byte (`\0`) padding in `persist.sys.usb.config=adb\0` breaks init's property parser; macOS `gzip` recompression produces different compressed output

**Class 4: ADB auth with `buildvariant=user` (attempt #23)**
Attempt #23 booted successfully but ADB showed `unauthorized`. adbd's `ALLOW_ADBD_NO_AUTH` compile-time flag is 0 for `user` builds, making `ro.adb.secure=0` ineffective. Fixed in #24 by using `buildvariant=userdebug`.

**Solution:** `tools/binpatch_ramdisk.py` — Python script that:
1. Decompresses original ramdisk with `gzip.decompress()`
2. Replaces exactly 7 bytes in the cpio stream (4 property values)
3. Pads `adb` with `\n` (newline), NOT `\0` (null byte)
4. Recompresses with Python `gzip.compress(compresslevel=6)` — NOT macOS `gzip` command
5. Preserves exact cpio structure (inode numbers, modes, entry ordering, padding)

### What Works
- `tools/binpatch_ramdisk.py` — correct ramdisk patching (Python gzip, newline padding)
- `tools/repack_ota.py` / `repack_ota_MGA1_20210901.py` — correctly-structured OTA zips
- Framework install via `install_ota.dex` (`RecoverySystem.installPackage()`)
- Recovery key patching with `firmware/keys/recovery_res_keys_stripped`
- Boot.img cmdline: `buildvariant=userdebug androidboot.selinux=permissive`

### What Doesn't Work — DO NOT USE
- `mkcpioimg.py` for boot ramdisk (inode mismatch → boot hang)
- macOS `find . | cpio -o -H newc` (uid/gid/prefix/inode bugs → boot hang)
- macOS `gzip` command for ramdisk recompression (different compressed output)
- Null byte (`\0`) padding in property value replacements
- `buildvariant=user` in boot.img cmdline (causes ADB auth issues — use `userdebug`)

### Repacked OTAs
- **MGA1_20210616**: `firmware/repack/MGA1_20210616/output/` — binary-patched ramdisk (attempt #19)
- **MGA1_20210901**: `firmware/repack/MGA1_20210901/output/` — binary-patched v3 ramdisk, userdebug+permissive cmdline (attempt #24)

---

## Related Documentation

- [OTA_REPACK_GUIDE.md](OTA_REPACK_GUIDE.md) — Full repack procedure (needs updating for new tools)
- [RECOVERY_DEBUG_ANALYSIS.md](RECOVERY_DEBUG_ANALYSIS.md) — Attempts #8-#9 analysis
- [RECOVERY_OTA_INSTALL.md](RECOVERY_OTA_INSTALL.md) — Block map procedure
- [MANUAL_APPLY_PLAN.md](MANUAL_APPLY_PLAN.md) — Manual apply approaches
- [RAMDISK_PACKING.md](RAMDISK_PACKING.md) — Detailed ramdisk packing guide
