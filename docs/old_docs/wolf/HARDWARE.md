# Wolf Hardware Controller - Device Specifications

> **USB HID Device: ICON Generic HID (ICON Fitness)**
> **Confirmed from decompiled source code (v2.6.88.4692)**

## Device Identification

### USB Descriptors (from kernel logs)
```
Bus: 001
Device: 003
Vendor ID: 213c (ICON Fitness / 8508 decimal)
Product ID: 0002 (Console/Brainboard)
Version: 1.00
Class: HID (Human Interface Device)
Subclass: 00
Protocol: 00
Max Packet Size: 8 bytes (control), 64 bytes (endpoints)
Power: 20mA (self-powered)
```

### Product IDs (from Eru.Android ExtensionMethods.cs)

| Product ID | Decimal | Mode | Purpose |
|-----------|---------|------|---------|
| 0x0002 | 2 | Console | Normal operation (Wolf MCU) |
| 0x0099 | 153 | Bootloader | Firmware update mode |

```csharp
// From decompiled source:
IsConsole() => VendorId == 8508 && ProductId == 2
IsBootloader() => VendorId == 8508 && ProductId == 153
```

### Kernel Detection Log
```
usb 1-1: new full-speed USB device number 3 using dwc2
usb 1-1: New USB device found, idVendor=213c, idProduct=0002
usb 1-1: New USB device strings: Mfr=1, Product=6, SerialNumber=0
usb 1-1: Product: ICON Generic HID
usb 1-1: Manufacturer: ICON Fitness
hid-generic 0003:213C:0002.0002: hiddev0,hidraw0:
    USB HID v1.11 Device [ICON Fitness ICON Generic HID]
    on usb-c0040000.dwc2otg-1/input0
```

## USB Interface Details

### Configuration
- **Configurations:** 1
- **Interfaces:** 1
- **Attributes:** Self-powered
- **Max Power:** 20mA

### Endpoints

| Endpoint | Address | Direction | Type | Size | Interval | Purpose |
|----------|---------|-----------|------|------|----------|---------|
| EP 0x81 | IN | Device → Host | Interrupt | 64 bytes | 1ms | Read sensor data, responses |
| EP 0x02 | OUT | Host → Device | Interrupt | 64 bytes | 1ms | Send commands |

### Software Access (from Sindarin.Usb.Android)
- Accessed via Android `UsbManager` API, not kernel HID driver
- Uses USBFS (userspace USB filesystem)
- **No kernel driver exists for VID 0x213C** — confirmed by searching `NexellCorp/linux_kernel-4.4.x/` source (zero matches for 0x213C). The `hid-generic` kernel module claims the device on attach but iFit uses userspace USBFS, not the kernel HID interface.
- Bulk transfers with 50ms timeout
- Read endpoint index: 0, Write endpoint index: 1
- Max 20 reconnection attempts
- Semaphore-based connection locking

## Permission Model

ERU (system service) controls USB access:

```
1. USB device attaches → UsbDeviceAttachedReceiver fires
2. ERU checks VID/PID → matches console (8508/2)
3. ERU grants permission via reflection:
   UsbManager.grantDevicePermission(device, uid)
4. Standalone app can now access device
```

**Authorized apps:**
- `com.ifit.standalone` (main iFit app)
- `com.ifit.launcher` (launcher app)

**Grant log:**
```
Granted USB privilege of ICON Generic HID to com.ifit.standalone - 10050
Granted USB privilege of ICON Generic HID to com.ifit.launcher - 10021
```

## Console Info (from IConsoleInfo interface)

The Wolf MCU reports these device properties:

| Property | Description | S22i Values |
|----------|-------------|-------------|
| ModelNumber | Equipment model | (device-specific) |
| PartNumber | Hardware part number | 392570 |
| SerialNumber | Unit serial | (device-specific) |
| SoftwareVersion | MCU firmware version | (device-specific) |
| HardwareVersion | Hardware revision | (device-specific) |
| FirmwareVersion | Combined version string | 83.245 |
| MasterLibraryVersion | Protocol library version | 83 |
| MasterLibraryBuild | Protocol library build | 245 |
| MachineType | ConsoleType enum | FitnessBike |
| ManufacturerId | Manufacturer enum | (ICON) |
| BrainboardSerialNumber | MCU board serial | (device-specific) |

### Capability Flags (S22i Bike)

| Capability | Value | Notes |
|-----------|-------|-------|
| CanSetKph | false | Bike has no treadmill motor |
| CanSetIncline | true | Supports incline adjustment |
| CanSetResistance | true | Magnetic resistance control |
| CanSetGear | false | No gear system |
| SupportsPulse | true | Heart rate input |
| SupportsVerticalGain | false | Not a treadmill |
| SupportsConstantWatts | true | Constant power mode |

### Range Limits

| Parameter | Min | Max | Units |
|-----------|-----|-----|-------|
| Resistance | 1 | 24 | levels |
| Incline | -10 | +20 | percent |
| Weight | - | 136.078 | kg (default fallback = 300 lbs; actual from hardware if reported) |

### Timeouts

| Timeout | Purpose |
|---------|---------|
| WarmUpTimeoutSeconds | Auto-exit warm-up |
| CoolDownTimeoutSeconds | Auto-exit cool-down |
| PauseTimeoutSeconds | Auto-resume from pause |
| IdleTimeoutSeconds | Enter sleep/idle |

## Hardware Functions

### Motor/Resistance Control
- **Resistance:** 24 levels of magnetic resistance
- **Incline:** -10% to +20% deck tilt
- **Control method:** FitPro protocol via ReadWriteData (v1) or Write feature (v2)

### Sensors
- **Cadence/RPM:** Pedal rotation sensor
- **Power (Watts):** Calculated from resistance and cadence
- **Distance:** Calculated from cadence and wheel circumference
- **Heart Rate:** BLE bridge to external HR monitors

### Console Management
- Device identification and capability reporting
- Workout state machine (Idle, WarmUp, Workout, CoolDown, etc.)
- Activation lock support
- Fan speed control

## Bootloader Mode

When firmware update is needed:

1. ERU sends `ResetBrainboard()` via FitPro
2. MCU re-enumerates as PID 153 (bootloader)
3. ERU detects bootloader via USB attach event
4. Firmware flashed row-by-row with checksums
5. MCU re-enumerates as PID 2 (normal console)

**Timeouts:**
- Wait for bootloader: 30 seconds max
- Wait for brainboard after flash: 60 seconds max

## BLE Hardware (Optional)

Some equipment supports BLE as alternative transport:

**FitPro Service UUID:** `00001533-1412-efde-1523-785feabcd123`
**DFU Service UUID:** `00001530-1212-efde-1523-785feabcd123`

BLE characteristics:
- DeviceRx (`00001534-...`): Write commands to device
- DeviceTx (`00001535-...`): Receive notifications from device

## System Paths

```bash
# USB device node
/dev/bus/usb/001/003

# HID raw device (not used by iFit - uses USBFS instead)
/dev/hidraw0

# Check device is present
adb shell ls -la /dev/bus/usb/001/003

# View USB descriptor
adb shell cat /sys/kernel/debug/usb/devices | grep -A 20 "Vendor=213c"

# Check ERU logs for Wolf activity
adb shell grep -i wolf /sdcard/eru/*.txt
```

## Safety Notes

**WARNING: Direct hardware access can cause physical movement!**

- Resistance changes cause sudden load on pedals
- Incline changes move heavy mechanical actuators
- Always have someone ready to dismount when testing

**Safe testing order:**
1. Read-only: Query ConsoleInfo, read sensors
2. State changes: Set WorkoutMode to Idle/WarmUp
3. Low values: Resistance 1-3, minimal incline
4. Monitor responses before increasing values

---

**Last Updated:** 2026-02-10
**Status:** Fully documented from decompiled source
**Source:** Sindarin.Usb.Android, Sindarin.Core.Console, Eru.Android
