# Recovery-Based OTA Install — Corrected Procedure

> Based on AOSP 7.1.2 source code analysis (`NexellCorp/aosp-7.1.2-analysis/recovery/`) and lessons learned from failed attempts #6-#9.

## Why Previous Attempts Failed

Source code analysis revealed that our bypass of the Android framework created the conditions for a hard crash:

1. **We bypassed `uncrypt`**: The Android framework (`RecoverySystem.java:456-483`) always converts `/data/` paths to `@/cache/recovery/block.map` via the `uncrypt` service. We wrote BCB directly with `--update_package=/data/update.zip`, sending recovery down the regular file `mmap()` path instead of the block-map path.

2. **The boot loop means a hard crash, not a handled error**: `modified_flash=true` is set at the very top of `install_package()` (`install.cpp:521`), before any work begins. Every handled error (key mismatch, mount failure, corrupt zip) returns normally, writes logs via `copy_logs()`, and blocks in `prompt_and_wait()` showing an error screen. **A boot loop with zero logs means the recovery process is being killed by a signal** (SIGKILL from OOM, SIGSEGV, SIGBUS) before returning from `install_package()`.

3. **`flash_recovery` likely restored stock recovery between attempts**: `install-recovery.sh` runs on every normal boot and restores stock recovery from boot.img. But even with wrong keys, verification failure is a *handled* error — it would show logs and an error screen, not a boot loop. So flash_recovery may have contributed but was not the root cause of the loop.

4. **The `mmap()` path maps 538MB from a mounted filesystem**: `sysMapFD()` (`SysUtil.c:32`) calls `mmap(NULL, 538MB, PROT_READ, MAP_PRIVATE, fd, 0)`. When `verify_package()` reads sequentially through the mapping, every page fault loads from the ext4 filesystem. On a 1-2GB RAM device in minimal recovery, this may trigger OOM or interact poorly with the Nexell recovery binary.

### The Fix: Use the Block Map Path

The block map path (`sysMapBlockFile`) reads from the raw block device (`/dev/block/mmcblk0p10`) without mounting `/data`. This:
- Bypasses the ext4 filesystem layer entirely
- Eliminates dirty-journal mount issues
- Is the intended code path (what the framework does)

Since the system `uncrypt` binary requires a socket client (it's an init service), we use a standalone block-map generator tool: `tools/mkblockmap.c`.

---

## Prerequisites

| Item | Location | Notes |
|------|----------|-------|
| Custom-signed OTA | `firmware/repack/output/20190521_MGA1_20210616-rooted-signed.zip` | 538MB |
| Patched recovery ramdisk | `firmware/repack/recovery_work/ramdisk-recovery-patched.img` | Our key in `/res/keys` |
| `write_bcb` tool | `/data/local/tmp/write_bcb` on device | Writes BCB to misc partition |
| `mkblockmap` tool | `tools/mkblockmap.c` (build, then push) | Standalone block-map generator |
| ADB root access | `adb root` | Required for all steps |

### Build `mkblockmap`

```bash
NDK=/opt/homebrew/Caskroom/android-ndk/29/AndroidNDK14206865.app/Contents/NDK
$NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin/aarch64-linux-android21-clang \
  -static -o mkblockmap tools/mkblockmap.c
adb push mkblockmap /data/local/tmp/
adb shell chmod 755 /data/local/tmp/mkblockmap
```

---

## Procedure

### Phase 1: Prepare Recovery (do once)

**1. Disable `flash_recovery`** so stock recovery isn't restored on reboot:

```bash
adb root && adb remount
adb shell mv /system/bin/install-recovery.sh /system/bin/install-recovery.sh.disabled
```

**2. Patch recovery partition** with our signing key:

```bash
adb shell mkdir -p /data/local/tmp/rec_mnt
adb shell mount -t ext4 -o rw /dev/block/mmcblk0p6 /data/local/tmp/rec_mnt

# Backup current ramdisk
adb shell cp /data/local/tmp/rec_mnt/ramdisk-recovery.img \
  /data/local/tmp/rec_mnt/ramdisk-recovery.img.stock

# Push patched ramdisk
adb push firmware/repack/recovery_work/ramdisk-recovery-patched.img \
  /data/local/tmp/rec_mnt/ramdisk-recovery.img
adb shell chmod 644 /data/local/tmp/rec_mnt/ramdisk-recovery.img

adb shell umount /data/local/tmp/rec_mnt
```

**3. Verify patch survives** (optional but recommended):

```bash
# Reboot normally — flash_recovery is disabled, so patch should persist
adb reboot
# Wait for boot...
adb root
adb shell mount -t ext4 -o ro /dev/block/mmcblk0p6 /data/local/tmp/rec_mnt
adb shell ls -la /data/local/tmp/rec_mnt/ramdisk-recovery.img
# Size should match patched version (~3.6MB), not stock (~2.9MB)
adb shell umount /data/local/tmp/rec_mnt
```

### Phase 2: Stage the OTA

**4. Remove OTA protection temporarily:**

```bash
adb root
# Remove immutable flag
adb shell /data/local/tmp/chattr -i /data/update.zip
adb shell rm /data/update.zip
```

**5. Push OTA file:**

```bash
adb push firmware/repack/output/20190521_MGA1_20210616-rooted-signed.zip /data/update.zip
# Verify size
adb shell ls -la /data/update.zip
# Should be ~538MB (563,822,759 bytes)
```

**6. Generate block map:**

```bash
adb shell /data/local/tmp/mkblockmap /data/update.zip /cache/recovery/block.map
# Expected output:
#   File: /data/update.zip
#   Size: 563822759, Block size: 4096
#   Block device: /dev/block/mmcblk0p10
#   Wrote N ranges to /cache/recovery/block.map
```

**7. Verify block map:**

```bash
adb shell head -3 /cache/recovery/block.map
# Line 1: /dev/block/mmcblk0p10
# Line 2: 563822759 4096
# Line 3: <number of ranges>
```

### Phase 3: Install

**8. Write BCB** with the `@`-prefixed block map path:

```bash
# The @ prefix tells recovery to use the block map instead of mounting /data
adb shell /data/local/tmp/write_bcb /dev/block/mmcblk0p7 \
  --update_package=@/cache/recovery/block.map
```

If `write_bcb` doesn't support the argument format, write BCB manually. The BCB structure needs:
- `command` field (offset 0, 32 bytes): `boot-recovery\0`
- `recovery` field (offset 64, 768 bytes): `recovery\n--update_package=@/cache/recovery/block.map\n\0`

**9. Reboot to recovery:**

```bash
adb reboot recovery
```

**10. Monitor** (if USB ADB available in recovery):

```bash
# Recovery has ro.debuggable=1 — try USB ADB
adb devices
adb shell cat /tmp/recovery.log
```

### Phase 4: Post-Install

**11. Verify success** after device reboots:

```bash
adb root
adb shell getprop ro.build.display.id    # Should show 2021 build
adb shell ls -la /system/xbin/su         # su binary should exist
adb shell whoami                          # Should be root
```

**12. Re-apply OTA protection:**

```bash
adb shell rm /data/update.zip
adb shell touch /data/update.zip
adb shell /data/local/tmp/chattr +i /data/update.zip
# Verify
adb shell lsattr /data/update.zip        # Should show 'i' flag
```

**13. Re-enable `flash_recovery`** (optional — only if you want stock recovery back):

```bash
adb shell mv /system/bin/install-recovery.sh.disabled /system/bin/install-recovery.sh
```

If you leave it disabled, your patched recovery persists across reboots. This is useful if you ever need recovery again.

---

## Troubleshooting

### Boot loop recurs
The BCB persists across reboots. Clear it immediately:

```bash
# If ADB is available during normal boot:
adb root
adb shell dd if=/dev/zero of=/dev/block/mmcblk0p7 bs=2048 count=1
adb reboot
```

If the device is stuck in recovery loop and ADB isn't available, do a **hardware factory reset** (hold Power button during power-on). This restores all partitions from backup and clears BCB. You will need to re-root from scratch.

### `mkblockmap` fails with "FIBMAP: Operation not permitted"
FIBMAP requires root. Ensure `adb root` was run first.

### `mkblockmap` fails with "FIBMAP: Invalid argument"
The file may be on a filesystem that doesn't support FIBMAP (e.g., FUSE at `/mnt/sdcard`). Use the ext4 path: `/data/update.zip` (not `/sdcard/update.zip`).

### Recovery shows error screen (not a loop)
This is actually good — it means recovery is working. Check logs:
```bash
# After rebooting normally
adb shell cat /cache/recovery/last_log
```

Common errors:
- `signature verification failed`: Key mismatch. Re-patch recovery ramdisk.
- `failed to mount`: Filesystem issue. Try clearing /cache: `adb shell rm -rf /cache/recovery/*`
- `Installation aborted`: update-binary error. Check last_log for details.

### OTA succeeds but root is lost
The OTA's updater-script installs `/system/xbin/su`. If it's missing after install, push manually:
```bash
adb root && adb remount
adb push firmware/repack/modified/system/xbin/su /system/xbin/su
adb shell chown 0:0 /system/xbin/su
adb shell chmod 4755 /system/xbin/su
```

---

## Fallback: Manual Apply (No Recovery)

If recovery still crashes with the block-map approach, skip recovery entirely. See `docs/firmware/MANUAL_APPLY_PLAN.md` Approach A:

```bash
# 1. Flash modified boot.img (persistent root)
adb shell dd if=/data/local/tmp/boot-patched.img of=/dev/block/mmcblk0p1 bs=1M

# 2. Install su binary
adb remount
adb push firmware/repack/modified/system/xbin/su /system/xbin/su
adb shell chown 0:0 /system/xbin/su && adb shell chmod 4755 /system/xbin/su

# 3. Re-apply OTA protection
adb shell touch /data/update.zip
adb shell /data/local/tmp/chattr +i /data/update.zip
```

This keeps the 2019 firmware with persistent root. No 2021 system update, but the 2019 system works fine.

---

## How This Differs From Previous Attempts

| Aspect | Attempts #6-#9 | This Procedure |
|--------|----------------|----------------|
| OTA path | `/data/update.zip` (direct) | `@/cache/recovery/block.map` (block map) |
| Filesystem mount | Recovery mounts /data via ext4 | Recovery reads raw block device |
| `mmap()` method | `sysMapFD` — single 538MB mmap from filesystem | `sysMapBlockFile` — ranges from raw device |
| `uncrypt` | Bypassed (wrote BCB directly) | Custom `mkblockmap` tool generates block map |
| `flash_recovery` | Not disabled (restored stock recovery) | Disabled before patching |
| Recovery verification | Between attempts, stock keys restored | flash_recovery disabled, key persists |

---

## Source References

- **Recovery install flow**: `NexellCorp/aosp-7.1.2-analysis/recovery/install.cpp` (lines 460-530)
- **setup_install_mounts**: `NexellCorp/aosp-7.1.2-analysis/recovery/roots.cpp` (lines 280-303)
- **sysMapFile / sysMapBlockFile**: `NexellCorp/aosp-7.1.2-analysis/recovery/minzip/SysUtil.c`
- **uncrypt / block map format**: `NexellCorp/aosp-7.1.2-analysis/recovery/uncrypt/uncrypt.cpp` (lines 234-416)
- **RecoverySystem.java**: `NexellCorp/aosp-7.1.2-analysis/base/core/java/android/os/RecoverySystem.java` (lines 456-483)
- **BCB handling**: `NexellCorp/aosp-7.1.2-analysis/recovery/bootloader_message/bootloader_message.cpp`
- **Previous attempts**: `docs/firmware/RECOVERY_DEBUG_ANALYSIS.md`
