# Manual OTA Apply Plan (Bypass Recovery)

> Alternative to recovery-based OTA install. Applies firmware changes directly via ADB root shell, bypassing recovery entirely.

## What the OTA Actually Changes

Our modified OTA (`20190521_MGA1_20210616-rooted-signed.zip`) is the stock 2021 iFit firmware with two additions:

### boot.img (ramdisk `default.prop`)

| Property | Stock 2021 | Modified |
|----------|-----------|----------|
| `ro.secure` | 1 | **0** |
| `ro.adb.secure` | 1 | **0** |
| `ro.debuggable` | 0 | **1** |
| `persist.sys.usb.config` | none | **adb** |

No other ramdisk files changed (init.rc, sepolicy, init binary — all identical to stock 2021).

### system/ directory

Only one file added: `/system/xbin/su` (2,139,000 bytes, static ARM64 ELF).
The updater-script already has the metadata for su (mode 04755 SUID, selabel `u:object_r:su_exec:s0`).

All other 1,474 system files are identical to the stock 2021 OTA.

### Bootloader

The updater-script calls `nexell.write_bootloader(package_extract_file("bootloader"), "mmc")`. This is a Nexell-specific edify function not available outside recovery. We may need to skip this step.

---

## Approach A: Minimal Root (Keep Current 2019 Firmware)

**Risk: Low** | **Time: ~5 minutes** | **Reversible: Yes (factory reset)**

Since we already have root on the 2019 firmware, we just need to make root persistent. This does NOT update to 2021 firmware — it just ensures root survives reboots.

### Prerequisites
- Device connected via ADB, root available (`adb root`)
- Modified boot.img: `firmware/repack/boot_work/boot-patched.img` (22,304,768 bytes)
- su binary: `firmware/repack/modified/system/xbin/su` (2,139,000 bytes)

### Steps

```bash
# 1. Verify connection
adb root
adb shell whoami  # Should say "root"

# 2. Back up current boot.img
adb shell "dd if=/dev/block/mmcblk0p1 of=/data/local/tmp/boot_backup.img bs=1M"
adb pull /data/local/tmp/boot_backup.img firmware/backups/boot_2019_backup.img

# 3. Push and install su binary
adb remount
adb push firmware/repack/modified/system/xbin/su /system/xbin/su
adb shell chown 0:0 /system/xbin/su
adb shell chmod 4755 /system/xbin/su
# Verify
adb shell ls -la /system/xbin/su
# Expected: -rwsr-xr-x root root 2139000 su
```

**STOP HERE** if you only want su on the current firmware. The boot.img step below changes ADB security settings and would write a 2021-era boot image to a device running 2019 system files. This mismatch may cause issues.

If you want to proceed with the full 2021 update, use Approach B instead.

---

## Approach B: Full Manual System Update (2019 → 2021 + Root)

**Risk: Medium** | **Time: ~30-60 minutes** | **Reversible: Yes (factory reset)**

Replicates what the OTA updater-script does, step by step.

### Prerequisites
- Device connected via ADB with root
- Modified OTA contents extracted at `firmware/repack/modified/`
- Modified boot.img at `firmware/repack/boot_work/boot-patched.img`
- Sufficient local disk space for staging

### Safety: Factory Reset Recovery

If anything goes wrong, factory reset restores the original 2019 firmware:
- Power off the bike
- Hold specific button combo during power-on (device-specific)
- OR: If system still boots, Settings → Factory Reset

### Phase 1: Backup Everything

```bash
adb root

# Back up current boot.img (critical!)
adb shell "dd if=/dev/block/mmcblk0p1 of=/data/local/tmp/boot_backup.img bs=1M"
adb pull /data/local/tmp/boot_backup.img firmware/backups/boot_2019_backup.img

# Back up current system partition (2GB, takes a while)
# Only if you have space and want full rollback capability
# adb shell "dd if=/dev/block/mmcblk0p2 of=/data/local/tmp/system_backup.img bs=1M"
# adb pull /data/local/tmp/system_backup.img firmware/backups/system_2019_backup.img

# Back up build.prop for reference
adb pull /system/build.prop firmware/backups/build_2019.prop
```

### Phase 2: Write boot.img

This is the safest step to do first — if it fails, the device still has the old system and can factory reset.

```bash
# Push modified boot.img
adb push firmware/repack/boot_work/boot-patched.img /data/local/tmp/boot.img

# Verify size (must be exactly 22304768 bytes)
adb shell "ls -la /data/local/tmp/boot.img"

# Write to boot partition
adb shell "dd if=/data/local/tmp/boot.img of=/dev/block/mmcblk0p1 bs=1M"

# Verify write
adb shell "dd if=/dev/block/mmcblk0p1 bs=1M count=22 | md5sum"
# Compare with local:
md5 -q firmware/repack/boot_work/boot-patched.img
```

**DO NOT REBOOT YET** — the new boot.img expects the 2021 system. Rebooting now with the 2019 system may cause issues.

### Phase 3: Format and Populate /system

```bash
# Unmount /system
adb shell umount /system

# Format /system as ext4 (DESTRUCTIVE — no going back without backup/factory reset)
adb shell "mkfs.ext4 /dev/block/mmcblk0p2"

# Remount
adb shell "mount -t ext4 -o rw,noatime,nosuid,nodev,nomblk_io_submit,errors=panic /dev/block/mmcblk0p2 /system"

# Push recovery files first
adb push firmware/repack/modified/recovery/ /system/

# Push all system files (this will take 10-20 minutes for ~1.5GB)
adb push firmware/repack/modified/system/ /system/
```

### Phase 4: Create Symlinks

The updater-script creates ~152 symlinks. Run this script on the device:

```bash
adb shell << 'SYMLINKS'
# App library symlinks
ln -sf /system/lib/libbluetooth_jni.so /system/app/Bluetooth/lib/arm/libbluetooth_jni.so
ln -sf /system/lib64/libWnnEngDic.so /system/app/OpenWnn/lib/arm64/libWnnEngDic.so
ln -sf /system/lib64/libWnnJpnDic.so /system/app/OpenWnn/lib/arm64/libWnnJpnDic.so
ln -sf /system/lib64/libappfuse_jni.so /system/priv-app/MtpDocumentsProvider/lib/arm64/libappfuse_jni.so
ln -sf /system/lib64/libdefcontainer_jni.so /system/priv-app/DefaultContainerService/lib/arm64/libdefcontainer_jni.so
ln -sf /system/lib64/libjni_eglfence.so /system/app/Gallery2/lib/arm64/libjni_eglfence.so
ln -sf /system/lib64/libjni_filtershow_filters.so /system/app/Gallery2/lib/arm64/libjni_filtershow_filters.so
ln -sf /system/lib64/libjni_jpegstream.so /system/app/Gallery2/lib/arm64/libjni_jpegstream.so
ln -sf /system/lib64/libjni_jpegutil.so /system/app/Camera2/lib/arm64/libjni_jpegutil.so
ln -sf /system/lib64/libjni_latinime.so /system/app/LatinIME/lib/arm64/libjni_latinime.so
ln -sf /system/lib64/libjni_pacprocessor.so /system/app/PacProcessor/lib/arm64/libjni_pacprocessor.so
ln -sf /system/lib64/libjni_tinyplanet.so /system/app/Camera2/lib/arm64/libjni_tinyplanet.so
ln -sf /system/lib64/libprintspooler_jni.so /system/app/PrintSpooler/lib/arm64/libprintspooler_jni.so
ln -sf /system/lib64/libserial_port.so /system/app/MalataEngineerMode/lib/arm64/libserial_port.so
ln -sf /system/lib64/libttscompat.so /system/app/PicoTts/lib/arm64/libttscompat.so
ln -sf /system/lib64/libttspico.so /system/app/PicoTts/lib/arm64/libttspico.so
ln -sf /system/lib64/libwnndict.so /system/app/OpenWnn/lib/arm64/libwnndict.so

# Font symlinks
ln -sf Roboto-Bold.ttf /system/fonts/DroidSans-Bold.ttf
ln -sf Roboto-Regular.ttf /system/fonts/DroidSans.ttf

# Binary symlinks
ln -sf app_process64 /system/bin/app_process
ln -sf dalvikvm64 /system/bin/dalvikvm
ln -sf grep /system/bin/egrep
ln -sf grep /system/bin/fgrep
ln -sf ip6tables /system/bin/ip6tables-restore
ln -sf ip6tables /system/bin/ip6tables-save
ln -sf iptables /system/bin/iptables-restore
ln -sf iptables /system/bin/iptables-save

# toolbox symlinks
for cmd in dd getevent iftop ioctl log nandread newfs_msdos prlimit ps sendevent start stop top; do
  ln -sf toolbox /system/bin/$cmd
done

# toybox symlinks (large set)
for cmd in acpi base64 basename blockdev bzcat cal cat chcon chgrp chmod chown chroot cksum clear cmp comm cp cpio cut date df dirname dmesg dos2unix du echo env expand expr fallocate false find flock free getenforce getprop groups head hostname hwclock id ifconfig inotifyd insmod ionice iorenice kill killall ln load_policy logname losetup ls lsmod lsof lsusb md5sum mkdir mknod mkswap mktemp modinfo more mount mountpoint mv netstat nice nl nohup od paste patch pgrep pidof pkill pmap printenv printf pwd readlink realpath renice restorecon rm rmdir rmmod route runcon sed seq setenforce setprop setsid sha1sum sleep sort split stat strings swapoff swapon sync sysctl tac tail tar taskset tee time timeout touch tr true truncate tty ulimit umount uname uniq unix2dos uptime usleep vmstat wc which whoami xargs xxd yes; do
  ln -sf toybox /system/bin/$cmd
done
SYMLINKS
```

### Phase 5: Set Permissions and SELinux Contexts

```bash
adb shell << 'PERMS'
# Base permissions
chown -R 0:0 /system
chmod -R 0755 /system
find /system -type f -exec chmod 0644 {} \;
find /system -type d -exec chmod 0755 {} \;

# /system/bin — owned by root:shell(2000), executable
chown -R 0:2000 /system/bin
chmod -R 0755 /system/bin

# /system/xbin — same
chown -R 0:2000 /system/xbin
chmod -R 0755 /system/xbin

# su binary — SUID root
chown 0:0 /system/xbin/su
chmod 4755 /system/xbin/su

# install-recovery.sh — special permissions
chmod 0750 /system/bin/install-recovery.sh

# run-as needs capabilities
chmod 0750 /system/bin/run-as

# vendor
chown -R 0:2000 /system/vendor
find /system/vendor -type f -exec chmod 0644 {} \;
find /system/vendor -type d -exec chmod 0755 {} \;

# ppp
chmod -R 0555 /system/etc/ppp/*
PERMS
```

SELinux contexts (since SELinux is permissive, these aren't strictly required but good practice):
```bash
adb shell "restorecon -R /system" 2>/dev/null
```

### Phase 6: Bootloader (OPTIONAL — May Skip)

The OTA calls `nexell.write_bootloader(bootloader, "mmc")`. Disassembly of the update-binary confirms this:
1. Opens `/dev/block/mmcblk0` (raw eMMC, not a partition)
2. `lseek(fd, 0x200, SEEK_SET)` — seeks to byte offset 512 (sector 1)
3. `write(fd, data, 4874294)` — writes the entire bootloader binary
4. Sector 0 (MBR/partition table) is preserved

See `docs/firmware/BOOTLOADER_ANALYSIS.md` for full disassembly details.

**To replicate manually (IF NEEDED — NOT RECOMMENDED):**
```bash
adb push firmware/repack/modified/bootloader /data/local/tmp/bootloader
adb shell "dd if=/data/local/tmp/bootloader of=/dev/block/mmcblk0 bs=512 seek=1"
```

**Recommendation: Skip this step.** The 2019 bootloader is compatible with 2021 system (same SoC, same Android 7.1.2). A failed/interrupted bootloader write would **brick the device** with no software recovery path. Factory reset does NOT restore the bootloader.

The bootloader binary is at: `firmware/repack/modified/bootloader` (4,874,294 bytes)

### Phase 7: Disable flash_recovery

Before rebooting, prevent flash_recovery from overwriting our recovery partition key:
```bash
adb shell mv /system/bin/install-recovery.sh /system/bin/install-recovery.sh.disabled
```

### Phase 8: Reboot and Verify

```bash
# Sync filesystem
adb shell sync

# Unmount /system cleanly
adb shell umount /system

# Reboot
adb reboot

# Wait for device to come back (~2-3 minutes for first boot with new system)
adb wait-for-device

# Verify
adb root
adb shell getprop ro.build.date           # Should show 2021 date
adb shell getprop ro.secure               # Should be 0
adb shell getprop ro.debuggable           # Should be 1
adb shell ls -la /system/xbin/su          # Should exist with SUID
adb shell su -c whoami                    # Should say "root"
```

### Phase 9: Re-apply OTA Protection

```bash
# Block future iFit OTA downloads
adb shell "rm -f /data/update.zip"
adb shell "/data/local/tmp/chattr +i /data/update.zip"
```

---

## Approach C: Minimal Root on 2021 (Hybrid)

**Risk: Low-Medium** | **Time: ~15 minutes**

If the full system push (Approach B Phase 3) is too slow or error-prone, we can do a hybrid:

1. Flash only boot.img (gets us the 2021 kernel + root properties)
2. Keep the 2019 system partition as-is
3. Push su binary to /system/xbin/

The risk is that the 2021 kernel might not be fully compatible with the 2019 system. But since both are Android 7.1.2 for the same device, compatibility is likely.

```bash
adb root

# Back up current boot
adb shell "dd if=/dev/block/mmcblk0p1 of=/data/local/tmp/boot_backup.img bs=1M"
adb pull /data/local/tmp/boot_backup.img firmware/backups/boot_2019_backup.img

# Push and write modified boot.img
adb push firmware/repack/boot_work/boot-patched.img /data/local/tmp/boot.img
adb shell "dd if=/data/local/tmp/boot.img of=/dev/block/mmcblk0p1 bs=1M"

# Push su binary
adb remount
adb push firmware/repack/modified/system/xbin/su /system/xbin/su
adb shell chown 0:0 /system/xbin/su
adb shell chmod 4755 /system/xbin/su

# Disable flash_recovery
adb shell mv /system/bin/install-recovery.sh /system/bin/install-recovery.sh.disabled

# Reboot
adb reboot
```

If this boots successfully, you have root on a hybrid 2021-kernel + 2019-system setup. If it doesn't boot, factory reset recovers to stock 2019.

---

## Risk Assessment

| Approach | Boot Risk | Data Risk | Rollback | Benefit |
|----------|-----------|-----------|----------|---------|
| A (su only) | None | None | Remove su | Root on 2019 |
| B (full update) | Medium | None (/data preserved) | Factory reset | Full 2021 + root |
| C (hybrid) | Low-Medium | None | Factory reset | Root + 2021 kernel |

**Factory reset always recovers the device** — it restores boot.img, system partition, and recovery from internal storage. This is our safety net for all approaches.

---

## Decision Checklist

Before proceeding, verify:
- [ ] Device backup of boot.img pulled to macOS
- [ ] ADB root working
- [ ] Factory reset button combo known (or Settings accessible)
- [ ] Clear the stale BCB from attempt #9: `write_bcb /dev/block/mmcblk0p7`
- [ ] OTA protection (immutable /data/update.zip) can be re-applied after
