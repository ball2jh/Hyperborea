# OTA Firmware Analysis: 20190521_MGA1_20210616.zip

> Full system OTA update for the NordicTrack S22i (Nexell S5P6818 / `avn_ref` board)

## Overview

| Field | Value |
|-------|-------|
| **Filename** | `20190521_MGA1_20210616.zip` |
| **Source URL** | `https://ifit-wolf.s3-cdn.ifit.com/android/builds/public/android-updates/20190521_MGA1_20210616.zip` |
| **Size** | 538 MB (890 MB uncompressed) |
| **Files** | 1,490 |
| **Build Date** | 2021-06-16 12:46:13 CST |
| **Build Target** | `Android/aosp_avn_ref/avn_ref:7.1.2/N2G48H/xmlnb06161246:user/dev-keys` |
| **Build Server** | `xmlnb@server-22` |
| **Signed By** | SignApk (standard Android OTA signing) |
| **Type** | Full destructive OTA (formats system partition) |
| **Local Path** | `firmware/downloads/20190521_MGA1_20210616.zip` |
| **Extracted To** | `firmware/downloads/extracted/` |

### Version Comparison

This OTA is a mid-life update — newer than the original factory firmware but older than what's currently running on the device via app-level updates.

| App | OTA Version | Device (system) | Device (user-updated) |
|-----|-------------|-----------------|----------------------|
| ERU | v2.0.2 (code 1153) | v1.2.1 (code 145) | **v2.13.9 (code 1852)** |
| Launcher | v1.0.17 (code 22) | v1.0 (code 12) | **v1.0.17 (code 22)** |
| Standalone | unknown | — | **v2.6.88 (code 4692)** |

## Updater Script

**Path:** `META-INF/com/google/android/updater-script`

The OTA uses a standard Android recovery updater script with Nexell-specific extensions.

### Install Process

1. **Version check** — refuses to install over builds newer than `1623813935` (2021-06-16 UTC)
2. **Device check** — requires `ro.product.device == "avn_ref"`
3. **Format system** — `format("ext4", "EMMC", "/dev/block/mmcblk0p2")` — wipes the entire system partition
4. **Extract system** — writes all system files from the zip to `/system`
5. **Set symlinks** — creates library symlinks for all system apps
6. **Set permissions** — sets ownership, modes, and SELinux labels for all system files
7. **Flash boot.img** — raw write to `/dev/block/mmcblk0p1`
8. **Flash bootloader** — `nexell.write_bootloader()` custom command to eMMC

### Notable Details

- `iproxy.sh` is given the `installd_exec` SELinux label
- Standalone APK placed at `/system/vendor/icon/` (unusual path, not in `/system/priv-app/`)
- `libserial_port.so` is symlinked for MalataEngineerMode (serial port JNI)

## boot.img

**Path:** `boot.img` (22.3 MB)

### Header

| Field | Value |
|-------|-------|
| Magic | `ANDROID!` |
| Kernel size | 19.8 MB |
| Kernel load addr | `0x10008000` |
| Ramdisk size | 1,471 KB |
| Ramdisk load addr | `0x11000000` |
| Second stage size | 62 KB (DTB) |
| Second stage addr | `0x10f00000` |
| Tags addr | `0x10000100` |
| Page size | 2,048 |
| Cmdline | `buildvariant=user` |

### Kernel

- **Version:** `Linux version 4.4.83+`
- **Built:** Wed Jun 16 12:45:02 CST 2021
- **Compiler:** GCC 4.9 20150123 (prerelease)
- **Config:** SMP PREEMPT, ARM64 (AArch64)
- **Builder:** `xmlnb@server-22`

### Ramdisk Contents

The ramdisk is gzip-compressed cpio, containing 22 files:

```
default.prop                 # Build properties
file_contexts.bin            # SELinux file contexts
fstab.avn_ref                # Filesystem mount table
init                         # Main init binary
init.avn_ref.rc              # Device-specific init script
init.avn_ref.usb.rc          # USB configuration
init.environ.rc              # Environment variables
init.rc                      # Main init script
init.recovery.avn_ref.rc     # Recovery mode init
init.usb.configfs.rc         # USB ConfigFS
init.usb.rc                  # USB gadget config
init.zygote32.rc             # 32-bit Zygote
init.zygote64_32.rc          # 64-bit Zygote (primary)
property_contexts            # SELinux property contexts
sbin/adbd                    # ADB daemon
sbin/healthd                 # Battery/health daemon
seapp_contexts               # SELinux app contexts
selinux_version              # SELinux version
sepolicy                     # SELinux policy binary
service_contexts             # SELinux service contexts
ueventd.avn_ref.rc           # Device-specific uevent rules
ueventd.rc                   # Main uevent rules
```

**Extracted to:** `firmware/downloads/extracted/boot_img_unpacked/ramdisk/`

### default.prop

```properties
ro.secure=1
ro.adb.secure=1
ro.debuggable=0
persist.sys.usb.config=none
ro.zygote=zygote64_32
ro.bootimage.build.date=2021年 06月 16日 星期三 12:46:13 CST
ro.bootimage.build.fingerprint=Android/aosp_avn_ref/avn_ref:7.1.2/N2G48H/xmlnb06161246:user/dev-keys
```

Despite `ro.adb.secure=1` and `persist.sys.usb.config=none`, the device init script **force-enables ADB on every boot** (see init.avn_ref.rc below).

### fstab.avn_ref (Partition Layout)

| Device | Mount Point | Filesystem | Flags |
|--------|-------------|------------|-------|
| `/dev/block/mmcblk0p1` | `/boot` | emmc | defaults |
| `/dev/block/mmcblk0p2` | `/system` | ext4 | **rw** |
| `/dev/block/mmcblk0p5` | `/cache` | ext4 | noatime,nosuid,nodev |
| `/dev/block/mmcblk0p6` | `/recovery` | emmc | defaults |
| `/dev/block/mmcblk0p7` | `/misc` | emmc | defaults |
| `/dev/block/mmcblk0p10` | `/data` | ext4 | noatime,nosuid,nodev |

External storage auto-mount:
- SD card via `c0062000.dw_mmc`
- USB via `c0030000.ehci` (USB 2.0 EHCI) and `c0040000.dwc2otg` (USB OTG)

**Key finding:** System partition is mounted **read-write** by default — no remount needed for modifications.

### init.avn_ref.rc Highlights

```
# Thermal management disabled on boot, enabled after boot_completed
write /sys/class/thermal/thermal_zone0/mode disabled

# Bluetooth UART
chmod 0660 /dev/ttySAC1
chown bluetooth net_bt_stack /dev/ttySAC1

# Backlight control — world-writable
chmod 0666 sys/class/backlight/pwm-backlight/brightness

# ADB force-enabled on boot complete (overrides default.prop)
setprop sys.usb.config adb

# usbmuxd runs as root (Apple USB muxing — for CarLife/iProxy)
service usbmuxd /system/bin/usbmuxd
    class late_start
    user root
    group root

# iproxy.sh — runs as root on boot
exec -- /system/bin/iproxy.sh

# preinstall.sh — runs as root with dedicated SELinux context
service preinstall /system/bin/preinstall.sh
    user root
    group root
    seclabel u:r:preinstall:s0
```

## Bootloader

**Path:** `bootloader` (4.65 MB)

### Structure

The bootloader is a composite image containing 4 stages in the Nexell S5P6818 boot chain:

| Stage | Component | Description |
|-------|-----------|-------------|
| **BL1** | Nexell 2ndboot | SoC-level init, DDR3 memory initialization |
| **BL2** | Secondary loader | Loaded by BL1, loads subsequent stages |
| **BL31/BL32** | ARM Trusted Firmware + OP-TEE | Secure world / TEE (authentication failures do not halt boot) |
| **U-Boot** | U-Boot 2016.01 | Main bootloader, built Jun 16 2021 |

### U-Boot Version

```
U-Boot 2016.01-00281-g6bf6a17cfc (Jun 16 2021 - 12:40:42 +0800)
Avn reference board based on Nexell s5p6818
```

### Boot Environment

```bash
# Default boot command — loads kernel + DTB from eMMC partition 1
bootcmd=run mmcboot
mmcboot=run boot_cmd_mmcboot
boot_cmd_mmcboot=ext4load mmc 0:1 0x40080000 Image;ext4load mmc 0:1 0x49000000 s5p6818-avn-ref-rev01.dtb;run dtb_reserve;booti 0x40080000 - 0x49000000

# Default kernel args
bootargs=console=ttySAC0,115200n8 root=/dev/mmcblk0p3 rw rootfstype=ext4 rootwait loglevel=4 quiet printk.time=1 consoleblank=0 systemd.log_level=info systemd.show_status=false

# Alternative: RAM filesystem boot
boot_cmd_ramfsboot=ext4load mmc 0:1 0x40080000 Image;ext4load mmc 0:1 0x48000000 uInitrd;ext4load mmc 0:1 0x49000000 s5p6818-avn-ref-rev01.dtb;run dtb_reserve;booti 0x40080000 0x48000000 0x49000000

# Splash screen from eMMC filesystem
splashsource=mmc_fs
```

### eMMC Partition Map (from bootloader)

| Partition | Offset | Size | Type | Image |
|-----------|--------|------|------|-------|
| bl1 (2ndboot) | `0x200` | ~4.7 MB | raw | `bootloader` |
| boot | `0x4B0000` | 64 MB | emmc | `boot.img` |
| system | `0x45B0000` | 2 GB | ext4 | `system.img` |
| vendor | `0x846B0000` | 64 MB | ext4 | `vendor.img` |
| cache | `0x887B0000` | 592 MB | ext4 | `cache.img` |
| recovery | `0xAD8B0000` | 64 MB | ext4 | `recovery.img` |
| misc | `0xB19B0000` | 1 MB | emmc | `misc.img` |
| key | `0xB1BB0000` | 4 MB | emmc | `key.img` |
| backup | `0xB20B0000` | 1.5 GB | ext4 | `backup.img` |
| fat ("wolfChina") | `0x1121B0000` | **52 GB** | ext4 | `wolfChina.img` |
| userdata | `0xE121B0000` | remainder | ext4 | `userdata.img` |

The "wolfChina" partition name confirms the Chinese ODM (Malata) origin and "Wolf" platform branding.

### Fastboot Support

The bootloader includes a full **Android Fastboot** implementation:

```
Android Fastboot
fastboot nexell
usb_dnl_fastboot — run as a fastboot usb device
```

Supports flash, erase, and reboot commands via USB. This means the device can potentially be flashed via USB without recovery mode using standard `fastboot` commands.

### Security Assessment

| Feature | Status |
|---------|--------|
| Secure Boot | **Not enforced** — `bootsecure` variable exists but "Secure boot command not specified" |
| BL32 Auth | **Fails gracefully** — "Failed to authenticate BL32" is a non-fatal error |
| OP-TEE | **Present** but unclear if actively used by Android |
| U-Boot Console | **Accessible** via UART (ttySAC0, 115200 baud) |
| Fastboot | **Enabled** — full partition flash/erase via USB |

### UART Debug Console

```
Console: ttySAC0 at 115200n8
```

If physical UART access is available on the board, this provides a full U-Boot and Linux kernel console.

### Memory Init

```
DDR3 POR Init Start
[DDR]Phy Initialize
Memory Initialize Done!
```

The system uses DDR3 memory (not LPDDR3).

## MalataEngineerMode

**Path:** `system/app/MalataEngineerMode/MalataEngineerMode.apk` (179 MB)
**Package:** `com.malata.icontest` v1.0.1
**Decompiled to:** `firmware/downloads/extracted/system/app/MalataEngineerMode/decompiled/`

### Overview

A factory QA test application made by **Malata** (the ODM that manufactured the hardware). It is a standard Android hardware test suite used on the assembly line to verify all hardware components before shipping.

### Size Breakdown

| File | Size | Purpose |
|------|------|---------|
| `res/raw/fukua.mp4` | **178 MB** | Factory test video (display/speaker) |
| `assets/for_speaker.mp3` | 964 KB | Speaker test audio |
| `classes.dex` | 194 KB | Application code (51 classes) |
| `res/raw/jingse.jpg` | 92 KB | Color test image |
| Everything else | ~500 KB | Layouts, icons, metadata |

The APK is 179 MB almost entirely because of a single test video file.

### Permissions

```xml
WRITE_EXTERNAL_STORAGE
ACCESS_WIFI_STATE / CHANGE_WIFI_STATE / INTERNET / ACCESS_NETWORK_STATE
RECORD_AUDIO
BLUETOOTH / BLUETOOTH_ADMIN
WRITE_SETTINGS / WRITE_SECURE_SETTINGS
CAMERA / FLASHLIGHT
ACCESS_FINE_LOCATION / ACCESS_COARSE_LOCATION
```

### Test Modules

| Test | Class | Serial Ports / Hardware |
|------|-------|------------------------|
| WiFi | `WifiTest` | Scans networks, checks signal >= -65 dBm |
| Bluetooth | `BTTest` | BT device discovery |
| Touchpad | `TouchPadTest` | Multi-touch / gesture test |
| **Serial** | `SerialTest` | `/dev/ttySAC4` and `/dev/ttySAC3` via `libserial_port.so` JNI |
| USB | `USBTest` | Checks `/storage/usbdisk1-3` mount points |
| SD Card | `SDcardTest` | Read/write on external SD |
| Audio | `AudioTest` | Speaker playback (uses `for_speaker.mp3`) |
| Microphone | `SoundRecorder` | Audio recording/playback |
| LCD | `LCDTest` | Color display test (uses test images) |
| Backlight | `BLTest` | PWM brightness control via sysfs |
| HDMI | `HDMITest` | External display output |
| Camera | `CameraTest` | Camera capture test |
| Key | `KeyTest` | Physical button input test |
| Ethernet | `EthernetTest` | Wired network test |
| QR Code | `QRCodeGenerateActivity` | Generates QR: MAC address + build info |
| Factory Reset | — | Broadcasts `com.ifit.action.FACTORY_DATA_RESET` to ERU |

### Serial Port Test Details

The `SerialTest` class uses a JNI library (`libserial_port.so`) to directly open serial devices:

- **Default ports:** `/dev/s3c2410_serial0` and `/dev/s3c2410_serial3`
- **On newer hardware (API > 10):** `/dev/ttySAC4` and `/dev/ttySAC3`
- **Default baud rate:** 9600 (configurable: 1200-115200)
- **Test pattern:** `1234567890abcdefghijklmnopqrstuvwxyz-=\[];',./`~!@#$%^&*()_+|{}:"<>?`
- Uses `su` to `chmod 666` the serial device if permissions are insufficient

### Factory Infrastructure

- **Reports results** to hardcoded factory server at `192.168.15.207:30001` via TCP socket
- **Generates QR code** with `mac:<address>;<build_display>` for factory tracking/inventory
- **Launches** `com.pixcir.tangoc.pro` — Pixcir capacitive touch controller calibration app
- **Sets** `device_provisioned=1` and `user_setup_complete=1` (bypasses Android setup wizard)
- **Factory reset** sends a broadcast intent (`com.ifit.action.FACTORY_DATA_RESET`) to iFit's ERU service rather than using Android's built-in factory reset

### Libraries

- `libserial_port.so` (ARM64) — JNI native library for serial port open/close
- ZXing (QR code generation) — embedded as source, not a library

## Key Security Findings

1. **ADB force-enabled on every boot** — despite `ro.adb.secure=1` in default.prop, `init.avn_ref.rc` sets `sys.usb.config adb` on `boot_completed`
2. **System partition mounted RW** — no remount required for permanent modifications
3. **Secure boot not enforced** — BL32 authentication failures are non-fatal
4. **Fastboot available** — full partition flash/erase capability via USB
5. **UART console accessible** — U-Boot + Linux on ttySAC0 at 115200
6. **usbmuxd runs as root** — Apple USB multiplexing daemon with full root privileges
7. **Factory reset via broadcast** — can be triggered by any app that can send the `com.ifit.action.FACTORY_DATA_RESET` intent
8. **Serial ports accessible** — factory test app uses `su` to chmod serial devices, confirming root availability

## File Locations

```
firmware/
└── downloads/
    ├── 20190521_MGA1_20210616.zip              # Original OTA zip
    └── extracted/
        ├── META-INF/com/google/android/
        │   └── updater-script                   # OTA install script
        ├── boot.img                             # Kernel + ramdisk
        ├── bootloader                           # Nexell BL1 + U-Boot composite
        ├── ramdisk.cpio                         # Decompressed ramdisk
        ├── boot_img_unpacked/
        │   └── ramdisk/                         # Extracted ramdisk filesystem
        │       ├── default.prop
        │       ├── fstab.avn_ref
        │       ├── init.avn_ref.rc
        │       └── ...
        └── system/app/MalataEngineerMode/
            ├── MalataEngineerMode.apk           # Factory test APK
            └── decompiled/                      # jadx output
                ├── sources/com/malata/icontest/  # Java source
                └── resources/AndroidManifest.xml
```

## Related Documentation

- [DEVICE.md](../DEVICE.md) — Hardware and software specifications
- [VERSIONS.md](../VERSIONS.md) — App and firmware version history
- [OTA_UPDATES.md](../security/OTA_UPDATES.md) — OTA update mechanism analysis
- [HARDWARE.md](../wolf/HARDWARE.md) — FitPro hardware protocol
- [CLAUDE.md](../../.claude/CLAUDE.md) — Development guide
