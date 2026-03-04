# Root Persistence Plan: Surviving Firmware Updates

> **Goal:** Modify the OTA firmware so that applying it maintains root access, ADB, and SELinux permissive mode — eliminating the need to block firmware updates entirely.

## Current Situation

### What We Have (device as-is)

| Property | Value | Source |
|----------|-------|--------|
| `ro.build.type` | `userdebug` | boot.img default.prop |
| `ro.debuggable` | `1` | boot.img default.prop |
| `ro.adb.secure` | `0` | system build.prop |
| `ro.secure` | `1` | boot.img default.prop |
| SELinux | **Permissive** | boot.img sepolicy / kernel cmdline |
| `su` binary | `/system/xbin/su` (SUID root, 2.1 MB) | Manually installed 2026-02-09 |
| ADB | Force-enabled via `init.avn_ref.rc` | boot.img ramdisk |
| `service.adb.tcp.port` | `5555` | system build.prop |
| `adb root` | Works | `ro.debuggable=1` enables this |

### What the OTA Would Change

| Property | Current (userdebug) | OTA (user) | Impact |
|----------|---------------------|------------|--------|
| `ro.build.type` | `userdebug` | **`user`** | `adb root` disabled |
| `ro.debuggable` | `1` | **`0`** | `adb root` disabled, no debug |
| `ro.adb.secure` | `0` | **`1`** | ADB requires authorization |
| `su` binary | Present | **Missing** | No `su` command |
| `/system/xbin/` | 34 binaries | **1 binary** (dexlist only) | All debug tools removed |
| SELinux | Permissive | **Unknown** (new sepolicy + kernel) | May enforce, blocking root |
| `otacerts.zip` | Modified (blocks updates) | **Original** (allows future updates) | Uncontrolled updates possible |

**Bottom line:** The OTA converts a rooted `userdebug` build into a locked-down `user` build. Every root mechanism is stripped.

## What Needs to Be Modified

There are **two independent images** that need changes: the **boot.img** (ramdisk) and the **system partition** contents (in the zip). The **bootloader** does not need modification.

### Modification 1: boot.img Ramdisk

The ramdisk contains `default.prop` and `sepolicy` — both critical for root.

#### 1a. default.prop Changes

```diff
- ro.secure=1
+ ro.secure=0

- ro.adb.secure=1
+ ro.adb.secure=0

- ro.debuggable=0
+ ro.debuggable=1

- persist.sys.usb.config=none
+ persist.sys.usb.config=adb
```

These changes:
- `ro.debuggable=1` — enables `adb root` command
- `ro.secure=0` — adbd starts as root by default (no need for `adb root`)
- `ro.adb.secure=0` — no RSA key authorization prompt
- `persist.sys.usb.config=adb` — ADB enabled from first boot (don't rely on init.rc)

#### 1b. sepolicy (SELinux Policy)

Two options:

**Option A (simple):** Add `androidboot.selinux=permissive` to kernel cmdline in boot.img header. This forces SELinux permissive regardless of the sepolicy binary.

**Option B (thorough):** Patch the sepolicy binary to be permissive. Tools like `sepolicy-inject` can add permissive domains. This is more fragile and version-dependent.

**Recommendation:** Option A. The boot.img header has a cmdline field (currently just `buildvariant=user`). Change it to:
```
buildvariant=user androidboot.selinux=permissive
```

#### 1c. Repack boot.img

The boot.img format is well-documented:
1. Extract kernel, ramdisk, second stage (DTB) from current boot.img
2. Decompress ramdisk (gzip), extract cpio
3. Modify `default.prop`
4. Repack cpio, compress gzip
5. Rebuild boot.img with modified cmdline and ramdisk using `mkbootimg`

Tools needed: `mkbootimg` / `unpackbootimg` (from Android build tools or [osm0sis/mkbootimg](https://github.com/nickhacker000/mkbootimg))

### Modification 2: System Partition Contents (ZIP)

#### 2a. Add `su` Binary

The OTA zip needs a `su` binary added at `system/xbin/su`.

Our current `su` binary is a statically-linked NDK build at `/system/xbin/su` (2.1 MB, SUID root). We need to:

1. Pull it from the device: `adb pull /system/xbin/su`
2. Add it to the OTA zip at `system/xbin/su`

#### 2b. Modify system/build.prop

```diff
- ro.build.type=user
+ ro.build.type=userdebug

  # Ensure these are present:
  service.adb.tcp.port=5555
+ ro.adb.secure=0
```

`ro.build.type=userdebug` is critical — it's checked by `adbd` to decide if `adb root` is allowed.

#### 2c. Modify updater-script

The updater-script needs changes to:

1. **Set `su` permissions** — add SUID bit and correct ownership:
```
set_metadata("/system/xbin/su", "uid", 0, "gid", 0, "mode", 04755, "capabilities", 0x0, "selabel", "u:object_r:su_exec:s0");
```

2. **Preserve debug tools in `/system/xbin/`** — the OTA only ships `dexlist`. We should add back the useful ones (strace, tcpdump, sqlite3, etc.) or at minimum ensure `su` is there.

3. **Modify otacerts.zip** — replace with our own key or empty it to prevent future uncontrolled OTA installs. Without this, the device could auto-apply a subsequent OTA that re-locks everything.

#### 2d. Preserve otacerts.zip Protection

The current device has a modified `otacerts.zip` (with a backup of the original). The OTA would overwrite this with the original cert, re-enabling automatic OTA verification.

**Options:**
- Replace `system/etc/security/otacerts.zip` in the OTA with a dummy cert (blocks future OTAs)
- Or add a post-install script that re-modifies it

## Implementation Approaches

### Approach A: Repack the OTA ZIP (Recommended)

Modify the OTA zip directly and apply it via `adb sideload` or push to recovery.

**Steps:**

1. **Unpack boot.img**
   ```bash
   # Install tools
   pip3 install mkbootimg  # or build from AOSP source

   # Unpack
   unpackbootimg -i boot.img -o boot_unpacked/
   ```

2. **Modify ramdisk**
   ```bash
   cd boot_unpacked/
   mkdir ramdisk && cd ramdisk
   gunzip -c ../boot.img-ramdisk.gz | cpio -id

   # Edit default.prop
   sed -i 's/ro.secure=1/ro.secure=0/' default.prop
   sed -i 's/ro.adb.secure=1/ro.adb.secure=0/' default.prop
   sed -i 's/ro.debuggable=0/ro.debuggable=1/' default.prop
   sed -i 's/persist.sys.usb.config=none/persist.sys.usb.config=adb/' default.prop

   # Repack ramdisk
   find . | cpio -o -H newc | gzip > ../boot.img-ramdisk-patched.gz
   ```

3. **Repack boot.img**
   ```bash
   mkbootimg \
     --kernel boot.img-kernel \
     --ramdisk boot.img-ramdisk-patched.gz \
     --second boot.img-second \
     --cmdline "buildvariant=user androidboot.selinux=permissive" \
     --base 0x10000000 \
     --kernel_offset 0x00008000 \
     --ramdisk_offset 0x01000000 \
     --second_offset 0x00f00000 \
     --tags_offset 0x00000100 \
     --pagesize 2048 \
     -o boot-patched.img
   ```

4. **Modify system files in the OTA zip**
   ```bash
   # Extract OTA
   mkdir ota_work && cd ota_work
   unzip ../20190521_MGA1_20210616.zip

   # Replace boot.img
   cp ../boot-patched.img boot.img

   # Add su binary
   cp /path/to/su system/xbin/su

   # Modify build.prop
   sed -i 's/ro.build.type=user/ro.build.type=userdebug/' system/build.prop
   echo 'ro.adb.secure=0' >> system/build.prop

   # Modify updater-script — add su permissions after xbin metadata line
   # (add set_metadata for su with SUID bit)

   # Replace otacerts.zip with dummy
   # (create a zip with a self-signed cert)
   ```

5. **Re-sign the OTA**
   ```bash
   # Generate signing keys (or use AOSP test keys)
   # The OTA is signed with iFit's key, but we control recovery
   # Option 1: Replace /res/keys in recovery to accept our signature
   # Option 2: Flash boot.img directly (bypasses recovery entirely)
   ```

6. **Apply**
   ```bash
   # Option 1: Direct flash (no signature needed)
   adb push boot-patched.img /data/local/tmp/
   adb shell dd if=/data/local/tmp/boot-patched.img of=/dev/block/mmcblk0p1

   # Option 2: Apply full OTA via recovery after replacing keys
   adb push modified-ota.zip /data/local/tmp/update.zip
   adb shell "echo '--update_package=/data/local/tmp/update.zip' > /cache/recovery/command"
   adb reboot recovery
   ```

### Approach B: Pre/Post-Flash Script (Simpler)

Instead of modifying the OTA, apply changes after the OTA completes.

**Problem:** The OTA replaces both boot.img AND system. After reboot, we have no root access to fix things.

**Solution:** Chain a second operation:

1. Let the OTA flash normally (system + bootloader updated)
2. **Before rebooting**, flash our patched boot.img directly to mmcblk0p1
3. On first boot, the patched boot.img gives us root
4. Then fix system partition (add su, modify build.prop, etc.)

**Steps:**

1. Prepare patched boot.img in advance (as above)
2. Apply OTA via recovery (updates system + bootloader)
3. Boot into recovery again (or use fastboot)
4. Flash patched boot.img: `fastboot flash boot boot-patched.img`
5. Boot normally — root is preserved
6. Fix system files:
   ```bash
   adb root
   adb remount
   adb push su /system/xbin/su
   adb shell chmod 4755 /system/xbin/su
   adb shell chown root:root /system/xbin/su
   # Modify build.prop, otacerts, etc.
   ```

### Approach C: Flash boot.img Only (Minimal)

If we only care about the kernel/bootloader update and not the system partition:

1. Patch just the boot.img from the OTA
2. Flash it directly: `dd if=boot-patched.img of=/dev/block/mmcblk0p1`
3. Don't apply the full OTA at all
4. System partition stays as-is (already rooted)

**Limitation:** System partition stays at old version. Only useful if the goal is just to get the kernel update.

## Recommended Approach

**Approach A (Repack OTA)** is the most thorough and creates a reusable artifact — a "rooted OTA" that can be applied cleanly via recovery.

However, **Approach B (Post-Flash)** is simpler and less error-prone since it doesn't require re-signing the OTA.

### Decision Matrix

| Factor | Approach A (Repack) | Approach B (Post-Flash) | Approach C (Boot Only) |
|--------|-------------------|----------------------|---------------------|
| Complexity | High | Medium | Low |
| Requires re-signing | Yes (or key replacement) | No | No |
| System updated | Yes | Yes | No |
| Bootloader updated | Yes | Yes | No |
| Reusable artifact | Yes | No | No |
| Risk of brick | Low (can recover via fastboot) | Low | Very low |
| Time to implement | 2-3 hours | 1-2 hours | 30 minutes |

## Signature Verification Problem

The biggest challenge with Approach A is OTA signature verification. Recovery verifies the zip against `/res/keys` (baked into the recovery partition).

### Options to Bypass

1. **Flash recovery partition** with a modified recovery that accepts our key (or skips verification). The current recovery is at `/dev/block/mmcblk0p6`.

2. **Use fastboot** to flash boot.img directly (bypasses recovery entirely). The bootloader has fastboot support built in.

3. **Replace `/res/keys`** in the recovery ramdisk with our own public key, then sign the OTA with our private key.

4. **Use `adb sideload` with `--no-verify`** — some recovery implementations support this.

5. **Direct dd** — since we have root, we can `dd` images directly to partitions without going through recovery at all:
   ```bash
   # Flash boot directly
   dd if=boot-patched.img of=/dev/block/mmcblk0p1

   # Flash system directly (more complex — need to write ext4 image)
   # Or: mount system RW and rsync the differences
   ```

### Recommended: Direct Partition Write

Since we have root access and the system is mounted RW, the simplest path is:

1. **boot.img** → `dd` directly to `/dev/block/mmcblk0p1`
2. **System files** → copy directly to `/system/` (it's already mounted RW)
3. **Bootloader** → use the Nexell-specific write mechanism (needs research, or skip)

This avoids recovery, signature verification, and OTA complexity entirely.

## Implementation Checklist

### Phase 1: Prepare Modified boot.img
- [ ] Install `mkbootimg` / `unpackbootimg` tools on Mac
- [ ] Extract kernel, ramdisk, DTB from OTA boot.img
- [ ] Patch `default.prop` (ro.debuggable=1, ro.secure=0, ro.adb.secure=0)
- [ ] Add `androidboot.selinux=permissive` to cmdline
- [ ] Repack boot.img
- [ ] Verify repacked image header is correct

### Phase 2: Prepare System Modifications
- [ ] Pull current `su` binary from device
- [ ] Prepare modified `build.prop` (ro.build.type=userdebug)
- [ ] Prepare modified `otacerts.zip` (dummy cert to block future OTAs)
- [ ] List all `/system/xbin/` binaries to preserve

### Phase 3: Test on Device
- [ ] Back up current boot partition: `dd if=/dev/block/mmcblk0p1 of=/sdcard/boot_backup.img`
- [ ] Back up current system state
- [ ] Flash patched boot.img via dd
- [ ] Reboot and verify: `adb root`, `getenforce`, `getprop ro.debuggable`
- [ ] If boot fails: restore backup via fastboot

### Phase 4: Full Update (if desired)
- [ ] Apply system partition changes from OTA (manually or via recovery)
- [ ] Re-apply root modifications to system
- [ ] Verify all functionality (ADB, root, su, SELinux permissive)
- [ ] Re-apply update protection (otacerts, firewall rules)

## Risk Mitigation

### Brick Recovery

The device has multiple recovery paths if something goes wrong:

1. **Fastboot mode** — bootloader supports USB fastboot (can flash any partition)
2. **Recovery partition** — separate from boot, can be used to reflash
3. **SD card recovery** — bootloader has `sd_recovery` command
4. **UART console** — ttySAC0 at 115200 provides U-Boot shell access for manual recovery

### Backup Before Any Changes

```bash
# Back up ALL critical partitions before starting
adb root
adb shell dd if=/dev/block/mmcblk0p1 of=/sdcard/backup_boot.img
adb shell dd if=/dev/block/mmcblk0p6 of=/sdcard/backup_recovery.img
adb shell dd if=/dev/block/mmcblk0p7 of=/sdcard/backup_misc.img
adb pull /sdcard/backup_boot.img firmware/backups/
adb pull /sdcard/backup_recovery.img firmware/backups/
adb pull /sdcard/backup_misc.img firmware/backups/

# Back up system files we'll modify
adb pull /system/build.prop firmware/backups/
adb pull /system/etc/security/otacerts.zip firmware/backups/
```

## Related Documentation

- [OTA_FIRMWARE_ANALYSIS.md](OTA_FIRMWARE_ANALYSIS.md) — Full firmware analysis
- [PROTECTION.md](../guides/PROTECTION.md) — Current update protection setup
- [OTA_UPDATES.md](../security/OTA_UPDATES.md) — OTA update mechanism analysis
- [DEVICE.md](../DEVICE.md) — Hardware specifications
