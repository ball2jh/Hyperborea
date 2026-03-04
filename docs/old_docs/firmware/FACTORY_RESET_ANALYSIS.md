# Factory Reset Mechanism — NordicTrack S22i (Nexell S5P6818)

## Summary

There are **two completely different factory reset mechanisms** on this device:

| Type | Trigger | Scope | Restores From |
|------|---------|-------|---------------|
| **Software reset** | iFit Settings menu | Wipes /data + /cache only | N/A (format only) |
| **Hardware reset** | Hold Power button during boot | Restores ALL partitions | Backup partition (mmcblk0p9) |

The hardware reset is what takes the device from 2021 firmware back to 2019 — it's a full partition restore, not just a data wipe.

## Software Factory Reset (Standard Android)

### Trigger
- iFit Settings → Factory Reset
- OR: `adb shell am broadcast -a android.intent.action.MASTER_CLEAR`

### Flow
```
iFit Settings
  → MasterClearReceiver.java
    → RecoverySystem.rebootWipeUserData()
      → Writes BCB to misc partition: command="boot-recovery", recovery="--wipe_data"
        → Reboot to recovery
          → recovery reads BCB
            → wipe_data(): format /data + format /cache
              → finish_recovery(): clear BCB
                → Reboot normally
```

### What It Does
- Formats `/data` (mmcblk0p10) — ext4 reformat
- Formats `/cache` (mmcblk0p5) — ext4 reformat
- Clears BCB in misc partition (mmcblk0p7)
- Removes `/cache/recovery/factory_mask`

### What It Does NOT Do
- Does NOT restore boot.img
- Does NOT restore system partition
- Does NOT restore bootloader
- Does NOT restore recovery partition
- Does NOT restore vendor partition

### Source
- AOSP `recovery.cpp` line 847: `wipe_data()` only calls `erase_volume("/data")` + `erase_volume("/cache")`
- Nexell recovery binary has no custom `PreWipeData()`/`PostWipeData()` overrides (confirmed via string analysis)

---

## Hardware Factory Reset (U-Boot sd_recovery)

### Trigger
**Hold the Power button during power-on.** U-Boot checks GPIO ALV0 (gpio-160 = Power button) during `board_late_init()`.

### Evidence
String sequence in U-Boot binary (offsets 0x57af0–0x57c30) shows the decision flow:

| Offset | String | Purpose |
|--------|--------|---------|
| 0x57af2 | `reboot recovery!!!!` | Logged when BCB triggers recovery boot |
| 0x57b07 | `bootcmd` | Env var being set |
| 0x57b0f | `run recoveryboot` | Value for recovery boot |
| 0x57b20 | `reboot fastboot!!!!` | Logged when fastboot mode detected |
| 0x57baa | `gpio_alv0` | GPIO Alive pin 0 being checked |
| 0x57bf5 | `sd_recovery mmc 0:9 0x40000000 /backup/backup_partmap.txt` | Factory restore command |

The code flow in `board_late_init()`:
1. Check reboot reason → if "recovery", set bootcmd to `run recoveryboot`
2. Check reboot reason → if "fastboot", enter fastboot mode
3. Configure serial number (iconid)
4. **Check GPIO ALV0** (Power button)
5. If GPIO active → **run sd_recovery** from backup partition
6. Load splash screen, continue boot

### sd_recovery Command

Source: `NexellCorp/u-boot-2016.01/common/cmd_sd_recovery.c`

```c
U_BOOT_CMD(
    sd_recovery, 5, 1, do_update_sdcard,
    "Image Update from SD Card",
    "<interface> [<dev[:part]>] <addr> <filename>"
);
```

Invoked as: `sd_recovery mmc 0:9 0x40000000 /backup/backup_partmap.txt`
- `mmc 0:9` = eMMC device 0, partition 9 (backup partition)
- `0x40000000` = RAM address for loading images
- `/backup/backup_partmap.txt` = partition map file

The command:
1. Mounts the backup partition (p9) as ext4
2. Reads `backup_partmap.txt`
3. Parses `flash=` entries
4. For each entry, reads the image file from the backup partition into RAM
5. Writes it to eMMC using:
   - `mmc write` for `2nd`/`boot` types (raw sector write)
   - `ext4_img_write` for `ext4` types (partition image write)

---

## Backup Partition (mmcblk0p9)

**Device**: `/dev/block/mmcblk0p9`
**Size**: 1.5 GB (1,572,864 sectors)
**Filesystem**: ext4
**Mount point**: Not normally mounted

### Contents

```
/backup/backup_partmap.txt     624 B     Partition restore manifest
/backup/boot.img           22,292,480 B  Boot image (kernel + ramdisk)
/backup/bootloader          4,874,294 B  Complete bootloader binary
/backup/cache.img          11,878,752 B  Clean cache partition
/backup/recovery.img       29,040,824 B  Recovery image
/backup/system.img        924,377,460 B  Full system partition (~924 MB)
/backup/userdata.img       65,865,304 B  Clean userdata partition
/backup/vendor.img          5,300,372 B  Vendor partition
```

All files dated **2019-05-21** — the original factory firmware.

### backup_partmap.txt

```
flash=mmc,0:bl1:2nd:0x200,0x4AFE00:/backup/bootloader;
flash=mmc,0:boot:emmc:0x4B0000,0x4000000:/backup/boot.img;
flash=mmc,0:system:ext4:0x45B0000,0x80000000:/backup/system.img;
flash=mmc,0:vendor:ext4:0x846B0000,0x4000000:/backup/vendor.img;
flash=mmc,0:cache:ext4:0x887B0000,0x25000000:/backup/cache.img;
flash=mmc,0:recovery:ext4:0xAD8B0000,0x4000000:/backup/recovery.img;
flash=mmc,0:misc:emmc:0xB19B0000,0x100000:/backup/misc.img;
flash=mmc,0:key:emmc:0xB1BB0000,0x00400000:/backup/key.img;
flash=mmc,0:backup:ext4:0xB20B0000,0x60000000:/backup/backup.img;
flash=mmc,0:userdata:ext4:0x1121B0000,0:/backup/userdata.img;
```

#### Format

```
flash=<device>,<dev_no>:<partition_name>:<fs_type>:<start_offset>,<size>:<source_file>;
```

| Field | Description |
|-------|-------------|
| `device` | Always `mmc` |
| `dev_no` | `0` (eMMC) |
| `partition_name` | Logical name (bl1, boot, system, etc.) |
| `fs_type` | Write method: `2nd` (raw bootloader), `emmc` (raw partition), `ext4` (filesystem image) |
| `start_offset` | Byte offset on eMMC for the partition start |
| `size` | Allocated size in bytes (0 = fill remaining) |
| `source_file` | Path on the backup partition |

#### What Gets Restored

| Entry | Type | Partition | Description |
|-------|------|-----------|-------------|
| `bl1` | `2nd` | raw eMMC @ 0x200 | **Bootloader** (written to sector 1, same as OTA) |
| `boot` | `emmc` | mmcblk0p1 | **Boot image** (kernel + ramdisk) |
| `system` | `ext4` | mmcblk0p2 | **System partition** (2 GB) |
| `vendor` | `ext4` | mmcblk0p3 | **Vendor partition** |
| `cache` | `ext4` | mmcblk0p5 | **Cache partition** |
| `recovery` | `ext4` | mmcblk0p6 | **Recovery partition** |
| `misc` | `emmc` | mmcblk0p7 | **Misc/BCB** (clears any pending commands) |
| `key` | `emmc` | mmcblk0p8 | **Key store** |
| `backup` | `ext4` | mmcblk0p9 | **Backup partition itself** (self-preserving) |
| `userdata` | `ext4` | mmcblk0p10 | **User data** (clean factory state) |

Note: `misc.img` and `key.img` are referenced in the partmap but were not found in the backup directory listing. They may be optional or created at manufacturing time.

---

## GPIO Mapping

GPIO bank layout confirmed from kernel source (`s5pxx18-gpio.h`):
```
PAD_GPIO_A   = 0*32  =   0-31
PAD_GPIO_B   = 1*32  =  32-63
PAD_GPIO_C   = 2*32  =  64-95
PAD_GPIO_D   = 3*32  =  96-127
PAD_GPIO_E   = 4*32  = 128-159
PAD_GPIO_ALV = 5*32  = 160-191  (ALIVE group, powered during deep sleep)
```

ALIVE register base: `0xC0010800` (from `s5p6818-base.h`).

```
gpio-160 = Power button (GPIO ALV0) → Factory restore trigger
```
Confirmed in device trees: `gpios = <&alive_0 0 1>` (alive_0 pin 0 = power button).

Other buttons (not involved in factory reset):
```
gpio-56  = Homepage   (GPIO_B, pin 24)
gpio-57  = Back       (GPIO_B, pin 25)
gpio-58  = Menu       (GPIO_B, pin 26)
gpio-62  = Volume Up  (GPIO_B, pin 30)
gpio-63  = Volume Down (GPIO_B, pin 31)
```

---

## Implications for Root Persistence

### Software factory reset
- **Root survives** if su is in /system/xbin (system partition not touched)
- **Root survives** if boot.img is modified (boot partition not touched)
- **OTA protection lost** (/data/update.zip immutable flag wiped with /data)
- **Must re-apply** OTA protection after software factory reset

### Hardware factory reset (Power button hold)
- **Root is completely removed** — ALL partitions restored to 2019 factory state
- **Bootloader restored** to 2019 version
- **OTA protection lost** — /data is reformatted
- **Must re-root from scratch** using ADB exploit

### Summary

| Modification | Software Reset | Hardware Reset |
|--------------|---------------|----------------|
| su binary in /system | Survives | **Removed** |
| Modified boot.img | Survives | **Removed** |
| Modified recovery | Survives | **Removed** |
| Immutable /data/update.zip | **Removed** | **Removed** |
| Disabled flash_recovery | Survives | **Removed** |

---

## Partition Map Reference

| Partition | Device | Offset | Size | Purpose |
|-----------|--------|--------|------|---------|
| MBR | raw | 0x000 | 512 B | Partition table |
| Bootloader | raw | 0x200 | ~4.9 MB | BL1+BL2+ATF+U-Boot |
| boot (p1) | mmcblk0p1 | 0x4B0000 | 64 MB | kernel + ramdisk |
| system (p2) | mmcblk0p2 | 0x45B0000 | 2 GB | Android system |
| vendor (p3) | mmcblk0p3 | 0x846B0000 | 64 MB | Vendor libs |
| cache (p5) | mmcblk0p5 | 0x887B0000 | 592 MB | Cache |
| recovery (p6) | mmcblk0p6 | 0xAD8B0000 | 64 MB | Recovery OS |
| misc (p7) | mmcblk0p7 | 0xB19B0000 | 1 MB | BCB/boot command |
| key (p8) | mmcblk0p8 | 0xB1BB0000 | 4 MB | Key store |
| backup (p9) | mmcblk0p9 | 0xB20B0000 | 1.5 GB | Factory images |
| userdata (p10) | mmcblk0p10 | 0x1121B0000 | ~3 GB | User data |

---

## Source References

- **U-Boot sd_recovery**: `NexellCorp/u-boot-2016.01/common/cmd_sd_recovery.c` (local source, lines 507-571)
- **U-Boot binary strings**: Extracted from eMMC sector 3841+ (U-Boot section at bootloader offset 0x1E0000)
- **GPIO mapping**: `/sys/kernel/debug/gpio` on device; kernel source `NexellCorp/linux_kernel-4.4.x/drivers/pinctrl/nexell/s5pxx18-gpio.h` (PAD_GPIO_ALV = 5*32 = 160)
- **GPIO in device trees**: `NexellCorp/linux_kernel-4.4.x/arch/arm64/boot/dts/nexell/s5p6818-artik710-explorer.dts` line 305: `gpios = <&alive_0 0 1>` (alive_0 pin 0 = power button)
- **ALIVE register base**: 0xC0010800 (from `NexellCorp/linux_kernel-4.4.x/include/dt-bindings/soc/s5p6818-base.h`)
- **Backup partition**: Mounted at `/dev/block/mmcblk0p9`
- **AOSP recovery**: `NexellCorp/aosp-7.1.2-analysis/recovery/` (install.cpp, roots.cpp, recovery.cpp)
- **AOSP RecoverySystem.java**: `NexellCorp/aosp-7.1.2-analysis/base/core/java/android/os/RecoverySystem.java`

**Note**: The NexellCorp U-Boot source is the generic Nexell BSP (artik710_raptor/drone boards). The GPIO power button check in `board_late_init()` and `sd_recovery` invocation were confirmed from **device binary string extraction** (U-Boot offsets 0x57af0-0x57c30), not from the generic source. The ifit build has custom board files not present in the public BSP.
