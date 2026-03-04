# NordicTrack S22i - Device Specifications

**Device**: NordicTrack S22i Exercise Bike
**Serial Number**: NN73Z115616
**Console GUID**: efe1dc2c-af08-4d03-8e5b-265e1b3cb112
**ADB Address**: 192.168.1.177:5555

## Hardware Specifications

### SoC & CPU
- **Platform**: Nexell S5P6818 (ARM64)
- **CPU**: 4x ARM Cortex-A53 @ ~1.4GHz
- **Architecture**: ARMv8-A (64-bit)
- **ISA**: arm64-v8a, armeabi-v7a, armeabi

### Memory & Storage
- **RAM**: 2GB (MemTotal: 2006868 kB)
- **Internal Storage**: ~8GB eMMC (mmcblk0)
  - `/system`: 1.9GB (43% used - 852MB)
  - `/data`: 2.8GB (74% used - 2.0GB)
  - `/cache`: 573MB (minimal usage)

### Display
- **Resolution**: 1920x1080 (Full HD)
- **Type**: Built-in touchscreen
- **Refresh Rate**: 19 fps (power optimized)
- **DPI**: 160 (320x320 dpi)
- **Touch Controller**: Pixcir I2C touchscreen

### Connectivity
- **WiFi**: 802.11 (connected)
- **Bluetooth**: Enabled (MAC: 22:22:B5:CC:ED:41)
- **ADB**: TCP port 5555 (network) + local port 5037

### Power
- **Power Source**: AC powered (always plugged in)
- **Battery**: Reports 100% (simulated)

### Sensors
- No accelerometer, gyroscope, or motion sensors

## Software Configuration

### Android Version
- **OS**: Android 7.1.2 (Nougat)
- **API Level**: 25
- **Build ID**: N2G48H
- **Security Patch**: October 5, 2017
- **Build Date**: May 21, 2019
- **Build Type**: userdebug with dev-keys

### Kernel
```
Linux version 4.4.83+ (xmlnb@server-22)
GCC 4.9 20150123 (prerelease)
#1 SMP PREEMPT Tue May 21 09:38:00 CST 2019
```

### System Properties
```properties
ro.product.manufacturer=NEXELL
ro.product.model=AOSP on avn_ref
ro.hardware=avn_ref
ro.board.platform=s5p6818
ro.build.type=userdebug
ro.debuggable=1
ro.secure=1
ro.adb.secure=0
persist.sys.usb.config=adb
ro.boot.serialno=NN73Z115616
```

### SELinux
- Status: Permissive mode (no enforcement)

## Installed Applications

### iFit Ecosystem

1. **com.ifit.standalone** (v2.2.8.364)
   - Location: `/data/app/com.ifit.standalone-1/base.apk`
   - Main user-facing application
   - Third-party app (user installed)

2. **com.ifit.eru** (v1.2.1.145) - System App
   - Location: `/system/priv-app/com.ifit.eru-1.2.1.145/`
   - Privileged system application
   - Runs as system user (uid 1000)
   - ERU = "Equipment Resource Unit" (device management)

3. **com.ifit.launcher** (v1.0.12) - System App
   - Location: `/system/priv-app/com.ifit.launcher-1.0.12/`
   - Custom launcher for the bike interface

4. **com.malata.icontest**
   - Hardware testing application

## File System Layout

### Partition Map (mmcblk0 - 7.6GB total)
```
mmcblk0p1  -  65MB  - Boot
mmcblk0p2  - 2GB    - /system (mounted RW)
mmcblk0p3  -  65MB  - Recovery
mmcblk0p5  - 606MB  - /cache
mmcblk0p6  -  65MB  - Unknown
mmcblk0p9  - 1.5GB  - Unknown
mmcblk0p10 - 3GB    - /data (user apps & data)
```

### Important Directories

#### System Apps
- `/system/app/` - Standard system applications
- `/system/priv-app/` - Privileged system applications (iFit apps here)

#### User Data
- `/sdcard/` or `/storage/emulated/0/` - User accessible storage
- `/sdcard/eru/` - ERU logs and data
- `/sdcard/iFit/workouts/` - Workout data

#### Development
- `/data/local/tmp/` - Temporary files, executable staging area

## iFit Data & Logs

### Log Files Location: `/sdcard/eru/`
- `Api_*.txt` - API communication logs
- `Combined_*.txt` - Combined system logs
- `DeviceAdmin_*.txt` - Admin/management logs
- `Error_*.txt` - Error logs
- `Startup_*.txt` - Startup/boot logs

### Workout Data: `/sdcard/iFit/workouts/`
- Local workout cache and history

### Console GUID
Location: `/sdcard/.ConsoleGuid`
Value: `efe1dc2c-af08-4d03-8e5b-265e1b3cb112`

## Hardware Interfaces

Available kernel interfaces at `/sys/class/`:
- `i2c-adapter` - I2C bus
- `spi_master` - SPI bus
- `gpio` - GPIO pins
- `pwm` - PWM outputs
- `backlight` - Display backlight control
- `leds` - LED control
- `input` - Touch input devices
- `graphics` - Display/graphics
- `sound` - Audio interfaces
- `bluetooth` - Bluetooth hardware
- `net` - Network interfaces
- `video4linux` - Video/camera interfaces
- `rtc` - Real-time clock

## Network Configuration

### Current Connection
- **WiFi SSID**: Shady Hollow Net
- **Signal**: 4/4 (excellent)
- **Network ADB**: Port 5555
- **Local ADB**: Port 5037

### Services
- `adbd` - ADB daemon running as root

## Build Information

**Builder**: xmlnb (developer username)
**Build Server**: server-22
**Build Location**: China (CST timezone)
**Build Timestamp**: May 21, 2019 09:38:00 CST
**OEM**: NEXELL (SoC manufacturer)
**Brand**: Android/AOSP (generic branding)
**Board**: avn_ref (Audio/Video/Navigation reference design)

This appears to be a Nexell reference board design repurposed for the NordicTrack bike with iFit software.
