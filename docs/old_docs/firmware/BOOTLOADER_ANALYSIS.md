# Nexell S5P6818 Bootloader Analysis

## Overview

The `nexell.write_bootloader()` edify function in the OTA update-binary writes the bootloader binary to raw eMMC. This document describes the exact mechanism, confirmed by disassembling the update-binary with radare2.

## Write Mechanism (Confirmed via Disassembly)

**Function**: `WriteBootloaderFn()` -> `UpdateBootloader()` -> `UpdateMMC()`

```
1. open("/dev/block/mmcblk0", O_WRONLY)    # Raw eMMC user area (NOT a partition)
2. lseek(fd, 0x200, SEEK_SET)              # Byte offset 512 = sector 1
3. write(fd, bootloader_data, 4874294)     # Entire bootloader binary
4. close(fd)
```

**Key findings from disassembly** (`fcn.00426d54` in update-binary):
- Opens `/dev/block/mmcblk0` (the whole eMMC device, not a partition)
- Seeks to offset `0x200` (512 bytes) — skips sector 0 (MBR/partition table)
- Writes the complete bootloader in a single `write()` call
- Verifies lseek returned `0x200` and write returned the expected size
- Logs: `"Succeed to update of /dev/block/mmcblk0, offset 512, size 4874294"`
- Only supports MMC type: `"Currently only support MMC type"`

## Manual Replication

To replicate what `nexell.write_bootloader()` does:

```bash
# Push bootloader to device
adb push firmware/repack/modified/bootloader /data/local/tmp/bootloader

# Write to eMMC at offset 512 (sector 1)
adb shell "dd if=/data/local/tmp/bootloader of=/dev/block/mmcblk0 bs=512 seek=1"

# Verify (read back and compare)
adb shell "dd if=/dev/block/mmcblk0 bs=512 skip=1 count=9521 of=/data/local/tmp/bootloader_readback"
adb pull /data/local/tmp/bootloader_readback /tmp/bootloader_readback
diff firmware/repack/modified/bootloader /tmp/bootloader_readback
```

**WARNING**: Writing to the wrong offset will brick the device. There is no recovery from a corrupt bootloader except JTAG or SD card boot (if the SoC supports it).

## eMMC Layout

| Region | eMMC Offset | Size | Content |
|--------|-------------|------|---------|
| MBR/Partition Table | `0x000` | 512 B | **Preserved** (not overwritten) |
| BL1 (2ndboot) | `0x200` | 64 KB | Nexell boot ROM loads this first |
| BL2/Loader | `0x10200` | 320 KB | Secondary bootloader |
| BL31/ATF (ARM TF) | `0x60200` | 1.5 MB | Secure world firmware + OP-TEE |
| U-Boot | `0x1E0200` | 2.8 MB | Main bootloader (2016.01) |
| *gap* | `0x4A6236` | ~39 KB | Unused padding |
| boot partition (p1) | `0x4B0000` | 64 MB | Android boot.img (kernel+ramdisk) |

The bootloader file ends at `0x4A6236` with a `~39 KB gap` before the boot partition at `0x4B0000`. No overlap risk.

## NSIH Header Format (512 bytes)

The Nexell System Information Header (NSIH) is prepended to each boot stage. "Peridot" is Nexell's internal code name for the S5P6818 SoC family. Full structure defined in `NexellCorp/bl1_s5p6818/src/nx_bootheader.h:211-254` (`struct nx_tbbinfo`).

| Offset | Size | Field | Description |
|--------|------|-------|-------------|
| `0x000`-`0x01F` | 32 B | Vector Code | ARM vector table (8 vectors) |
| `0x020`-`0x03F` | 32 B | Vector Address | ARM vector addresses (8 entries) |
| `0x040` | 4 B | Device Read Address | Storage offset for next stage (varies by boot media) |
| `0x044` | 4 B | Load Size | Bytes to load after header |
| `0x048` | 4 B | Load Address | SRAM/RAM destination address |
| `0x04C` | 4 B | Launch Address | Entry point after load |
| `0x050`-`0x058` | 12 B | Boot mode config | Boot device selection, flags |
| `0x05C`-`0x094` | 40 B | PLL registers | Clock/PLL configuration |
| `0x098`-`0x0BC` | 37 B | DDR init | DDR3 timing and initialization |
| `0x0C0`-`0x1B7` | ~248 B | DDR PHY | DDR driver/PHY calibration settings |
| `0x1F8` | 4 B | Build Info | BL1 build metadata |
| `0x1FC` | 4 B | **NSIH Magic** | `0x4849534E` ("NSIH" LE) |

**Note:** Earlier docs incorrectly described offsets 0x000-0x03F as "DRAM config, PLL, device init". The vectors are at 0x000-0x03F; PLL/DDR config begins at 0x05C. NSIH generator spreadsheets are at `NexellCorp/bl1_s5p6818/nsih-generator/`; reference configs at `NexellCorp/bl1_s5p6818/reference-nsih/`.

## Cross-Reference: FriendlyARM Fusing Script

The [sd-fuse_s5p6818](https://github.com/friendlyarm/sd-fuse_s5p6818/blob/master/fusing.sh) fusing script independently confirms our sector offsets:

```bash
dd if=bl1-mmcboot.bin   of=/dev/sdX bs=512 seek=1      # 0x200
dd if=fip-loader.img    of=/dev/sdX bs=512 seek=129     # 0x10200
dd if=fip-secure.img    of=/dev/sdX bs=512 seek=769     # 0x60200
dd if=fip-nonsecure.img of=/dev/sdX bs=512 seek=3841    # 0x1E0200
```

## Bootloader Binary Structure (4,874,294 bytes)

The bootloader is a composite image with 4 NSIH-delimited sections:

### Section 0: BL1 (Nexell 2ndboot)
- **File offset**: `0x00000000`
- **Size**: 64 KB (padded from actual 27 KB of code)
- **NSIH header at**: `0x000` (magic "NSIH" at `0x1FC`)
- **Purpose**: SoC-level init, DDR3 memory initialization
- **Content**: ARM32 code, "BL1 by Nexell Co. : (Ver%d.%d.%d)"
- **NSIH fields**: LoadSize=0x6A3C (27,196 bytes actual code)

### Section 1: BL2/Loader
- **File offset**: `0x00010000`
- **Size**: 320 KB (NSIH field[0x50] = 278,016 bytes)
- **NSIH header at**: `0x10000` (magic "NSIH" at `0x101FC`)
- **Purpose**: Secondary bootloader, loads BL31/BL32/U-Boot

### Section 2: BL31/ARM Trusted Firmware + OP-TEE
- **File offset**: `0x00060000`
- **Size**: ~1.5 MB (NSIH field[0x50] = 260,768 bytes + OP-TEE)
- **NSIH header at**: `0x60000` (magic "NSIH" at `0x601FC`)
- **Purpose**: Secure world firmware (ARM TF + OP-TEE)
- **Note**: Authentication failures do not halt boot (from strings: secure image load errors are non-fatal)

### Section 3: U-Boot
- **File offset**: `0x001E0000`
- **Size**: ~2.8 MB (NSIH field[0x50] = 498,608 bytes)
- **NSIH header at**: `0x1E0000` (magic "NSIH" at `0x1E01FC`)
- **Purpose**: Main bootloader, loads kernel from eMMC
- **Version**: `U-Boot 2016.01-00281-g6bf6a17cfc (Jun 16 2021)`
- **Board**: "Avn reference board based on Nexell s5p6818"

### Boot Entry Table (BOOTMAGICNUMBER)
- **File offset**: `0x00050804`
- **Magic**: `BOOTMAGICNUMBER!`
- **Entries**:
  - `ENTRYHDRloader`: stage=4, sectors=2, device=1
  - `ENTRYHDRbl1`: stage=8, sectors=25, device=1
- **SRAM addresses**: `0xBFD00800`, `0xBFD04200` (boot ROM load targets)

## Boot Chain

"Peridot 2ndboot" is Nexell's name for BL1 (source: `NexellCorp/bl1_s5p6818/`). ROM Boot is the actual first stage. Boot flow from `secondboot.c:BootMain()` (lines 154-406): EMA/affinity setup → UART debug init → PMIC init → PLL/clock switch → DDR3 training → multi-core bring-up → load next stage via SDMMC/USB.

```
Power On
  └─ Boot ROM (in SoC silicon, proprietary)
       └─ Reads "2ndboot"/BL1 from eMMC offset 0x200 into SRAM 0xBFD00800
            └─ BL1: CPU init, DDR3 memory training, PLL/clock setup, PMIC init
                 └─ BL1 loads BL2/Loader from eMMC offset 0x10200
                      └─ BL2: Loads BL31 (ATF) + BL32 (OP-TEE) + U-Boot
                           └─ U-Boot: ext4load kernel from mmcblk0p1
                                └─ Linux kernel boots Android
```

## U-Boot Boot Commands

```bash
# Default boot (from eMMC partition 1)
bootcmd=run mmcboot
mmcboot=run boot_cmd_mmcboot
boot_cmd_mmcboot=ext4load mmc 0:1 0x40080000 Image; \
                 ext4load mmc 0:1 0x49000000 s5p6818-avn-ref-rev01.dtb; \
                 run dtb_reserve; \
                 booti 0x40080000 - 0x49000000

# Kernel args
bootargs=console=ttySAC0,115200n8 root=/dev/mmcblk0p3 rw rootfstype=ext4 ...
```

## Risk Assessment for Manual Bootloader Update

| Factor | Assessment |
|--------|-----------|
| **Correct offset confirmed** | Yes — 0x200 (sector 1), verified via disassembly |
| **Fits before boot partition** | Yes — 39 KB gap |
| **MBR preserved** | Yes — write starts at sector 1, not sector 0 |
| **Reversible** | **YES** — hardware factory reset (Power button hold during boot) restores bootloader from backup partition (mmcblk0p9). See `docs/firmware/FACTORY_RESET_ANALYSIS.md` |
| **Brick risk** | **HIGH** if offset is wrong or write is interrupted |
| **Recovery options** | Hardware factory reset (hold Power during boot), JTAG, or SD card boot |

### Recommendation

**Skip the bootloader update for manual apply.** The 2019 bootloader is compatible with both the 2019 and 2021 system images (same SoC, same Android 7.1.2). The only difference is the U-Boot build date and minor fixes. There is no feature in the 2021 firmware that requires the 2021 bootloader.

If a bootloader update is ever needed, the `dd` command above will work — but power loss during the write would brick the device with no software recovery path.

## Source References

- **update-binary**: `firmware/repack/modified/META-INF/com/google/android/update-binary`
  - Function `fcn.00426d54` (WriteBootloaderFn) at VMA 0x426D54
  - Disassembled with radare2 (`r2 -c 'aaa; s fcn.00426d54; pdf'`)
- **Bootloader binary**: `firmware/repack/modified/bootloader` (4,874,294 bytes)
- **Partition map**: `docs/firmware/OTA_FIRMWARE_ANALYSIS.md` (line 219: bl1 at offset 0x200)
- **Nexell BSP**: [NexellCorp/android_nexell_tools](https://github.com/NexellCorp/android_nexell_tools) (fusing scripts)
- **Nexell board**: [NexellCorp/android_board_s5p6818_avn_ref_bt](https://github.com/NexellCorp/android_board_s5p6818_avn_ref_bt)

### Local Source (NexellCorp/)
- **BL1/2ndboot source**: `NexellCorp/bl1_s5p6818/` — NSIH header (`nx_bootheader.h`), boot main (`secondboot.c`), startup (`startup_aarch64.S`)
- **U-Boot source**: `NexellCorp/u-boot-2016.01/` — sd_recovery (`common/cmd_sd_recovery.c`), bootloader header (`tools/nexell/nx_bootheader.h`)
- **Kernel source**: `NexellCorp/linux_kernel-4.4.x/` — GPIO banks (`drivers/pinctrl/nexell/s5pxx18-gpio.h`), platform base (`include/dt-bindings/soc/s5p6818-base.h`)
- **AOSP recovery**: `NexellCorp/aosp-7.1.2-analysis/recovery/` — install flow, BCB handling, mount logic

**Note**: The NexellCorp repos are the **generic Nexell BSP** (artik710_raptor/drone boards). The actual ifit/NordicTrack device uses customized board files. Device-specific behaviors (GPIO power button check in board_late_init, BCB integration) were confirmed from **device binary analysis** (string extraction, r2 disassembly), not from this source repo.
