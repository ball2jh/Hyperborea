# Ramdisk Packing Guide

> How to correctly extract, modify, and repack Android boot.img ramdisks on macOS.

## Background

Android boot.img contains a gzip-compressed cpio "newc" archive as its ramdisk. The ramdisk provides the initial root filesystem for the kernel, including `/init`, `/default.prop`, and SELinux policy.

AOSP uses `mkbootfs` (C program) to create ramdisks during the build. When modifying ramdisks on macOS, several pitfalls cause the kernel to fail to boot.

## The Problem: macOS cpio is Broken for Android

The commonly suggested approach:

```bash
# Extract
gunzip -c boot.img-ramdisk | cpio -id

# Modify files...

# Repack
find . | cpio -o -H newc | gzip > ramdisk.img
```

**This produces an invalid ramdisk on macOS.** Four bugs are introduced:

### Bug 1: Wrong uid/gid

macOS extracts files as the current user (e.g., uid 501, gid 20). When `cpio -o` repacks, it records these macOS-specific ownership values. The kernel expects uid 0 / gid 0 (root) for all ramdisk entries.

**Effect:** `/init` is owned by uid 501. The kernel may fail to execute it or init may fail permission checks.

### Bug 2: `./` Path Prefix

`find .` produces paths like `./init`, `./sbin/adbd`, `./default.prop`. AOSP mkbootfs produces paths without any prefix: `init`, `sbin/adbd`, `default.prop`.

**Effect:** Files are extracted to incorrect paths in the kernel's rootfs.

### Bug 3: Extra `.` Root Entry

`find .` includes `.` as the first entry, creating a root directory node. AOSP mkbootfs does not emit a root entry — it starts directly with the first sorted file/directory.

**Effect:** 44 entries instead of 43. May confuse the kernel's initramfs extractor.

### Bug 4: Inode Zero

If using a custom cpio builder, setting inode=0 for all entries causes the Linux kernel's `init/initramfs.c` to treat every file as a hardlink to the first file (the `maybe_link()` function checks inode numbers to detect hardlinks).

**Effect:** All files contain the same data as the first file in the archive.

## The Solution: `tools/mkcpioimg.py`

Custom Python script that replicates AOSP mkbootfs behavior:

```bash
python3 tools/mkcpioimg.py <source-dir> <output.img>
```

### What It Does

- Forces uid=0, gid=0 for all entries
- Paths have no prefix (uses `os.path.relpath` from source root)
- Skips root `.` directory entry
- Assigns unique incrementing inode numbers (1, 2, 3, ...)
- Sets nlink=1 for all entries (matching mkbootfs)
- Sets mtime=0 for all entries (matching mkbootfs)
- Sorts entries alphabetically (matching mkbootfs)
- Uses lowercase hex in cpio headers (matching mkbootfs)
- Gzip compresses with level 9
- Pads uncompressed archive to 256-byte boundary (matching mkbootfs)

### Verification

To verify the output matches AOSP format, compare against the original ramdisk:

```python
import gzip

def parse_cpio(data):
    """Parse cpio newc entries from raw data."""
    entries = []
    pos = 0
    while pos < len(data):
        magic = data[pos:pos+6].decode('ascii')
        if magic != '070701':
            break
        ino = int(data[pos+6:pos+14], 16)
        mode = int(data[pos+14:pos+22], 16)
        uid = int(data[pos+22:pos+30], 16)
        gid = int(data[pos+30:pos+38], 16)
        nlink = int(data[pos+38:pos+46], 16)
        filesize = int(data[pos+54:pos+62], 16)
        namesize = int(data[pos+94:pos+102], 16)
        name = data[pos+110:pos+110+namesize-1].decode('ascii')
        # Advance past header+name padding and content+padding
        hdr_total = 110 + namesize
        if hdr_total % 4: hdr_total += 4 - hdr_total % 4
        content_end = pos + hdr_total + filesize
        if filesize % 4: content_end += 4 - filesize % 4
        entries.append((name, ino, mode, uid, gid, nlink, filesize))
        if name == 'TRAILER!!!': break
        pos = content_end
    return entries

orig = parse_cpio(gzip.open('original-ramdisk.img', 'rb').read())
built = parse_cpio(gzip.open('rebuilt-ramdisk.img', 'rb').read())

# Should match on: entry count, names, modes, uid=0, gid=0, nlink=1, sizes
# Only inodes will differ (both should be unique)
```

## Original Ramdisk Properties (NordicTrack S22i)

From the stock 2021 boot.img:

| Property | Value |
|----------|-------|
| Entry count | 42 files/dirs + 1 TRAILER = 43 total |
| Inode range | 0x493e0 - 0x49409 (sequential) |
| All uid | 0 (root) |
| All gid | 0 (root) |
| All nlink | 1 |
| All mtime | 0 |
| Uncompressed size | 3,601,664 bytes |
| Compressed size | 1,506,773 bytes |
| Padding | 256-byte boundary |

### Entry List

```
040755 acct                  (directory)
120644 bugreports            (symlink → /data/user_de/0/com.android.shell/files/bugreports)
040770 cache                 (directory)
120644 charger               (symlink → /sbin/healthd)
040500 config                (directory)
120644 d                     (symlink → /sys/kernel/debug)
040771 data                  (directory)
100644 default.prop          (1128 bytes — the file we modify)
040755 dev                   (directory)
120644 etc                   (symlink → /system/etc)
100644 file_contexts.bin     (SELinux file contexts)
100640 fstab.avn_ref         (fstab)
100750 init                  (1,254,768 bytes — Android init binary)
100750 init.avn_ref.rc       (device-specific init script)
100750 init.avn_ref.usb.rc
100750 init.environ.rc
100750 init.rc               (main init script)
100750 init.recovery.avn_ref.rc
100750 init.usb.configfs.rc
100750 init.usb.rc
100750 init.zygote32.rc
100750 init.zygote64_32.rc
040755 mnt                   (directory)
040755 oem                   (directory)
040755 proc                  (directory)
100644 property_contexts
040750 sbin                  (directory)
100750 sbin/adbd             (1,136,224 bytes)
100750 sbin/healthd          (869,120 bytes)
120750 sbin/ueventd          (symlink → ../init)
120750 sbin/watchdogd        (symlink → ../init)
120644 sdcard                (symlink → /storage/self/primary)
100644 seapp_contexts
100644 selinux_version
100644 sepolicy              (SELinux policy binary)
100644 service_contexts
040751 storage               (directory)
040755 sys                   (directory)
040755 system                (directory)
100644 ueventd.avn_ref.rc
100644 ueventd.rc
120644 vendor                (symlink → /system/vendor)
000644 TRAILER!!!            (sentinel — ignored by kernel)
```

## Boot.img Repack Procedure (Corrected)

### Step 1: Unpack

```bash
MKBOOT=tools/mkbootimg

$MKBOOT/unpackbootimg -i firmware/repack/original/boot.img -o /tmp/boot_work/
```

### Step 2: Extract Ramdisk

```bash
mkdir -p /tmp/ramdisk && cd /tmp/ramdisk
gunzip -c /tmp/boot_work/boot.img-ramdisk | cpio -id
```

### Step 3: Modify

Edit `default.prop`:

| Property | Original | Modified |
|----------|----------|----------|
| `ro.secure` | `1` | `0` |
| `ro.adb.secure` | `1` | `0` |
| `ro.debuggable` | `0` | `1` |
| `persist.sys.usb.config` | `none` | `adb` |

### Step 4: Repack Ramdisk (USE mkcpioimg.py, NOT cpio)

```bash
# CORRECT — use mkcpioimg.py
python3 tools/mkcpioimg.py /tmp/ramdisk /tmp/ramdisk-patched.img

# WRONG — DO NOT USE on macOS
# find . | cpio -o -H newc | gzip > /tmp/ramdisk-patched.img
```

### Step 5: Repack Boot.img

```bash
$MKBOOT/mkbootimg \
  --kernel /tmp/boot_work/boot.img-kernel \
  --ramdisk /tmp/ramdisk-patched.img \
  --second /tmp/boot_work/boot.img-second \
  --cmdline "buildvariant=userdebug androidboot.selinux=permissive" \
  --base 0x10000000 \
  --pagesize 2048 \
  --kernel_offset 0x00008000 \
  --ramdisk_offset 0x01000000 \
  --second_offset 0x00f00000 \
  --tags_offset 0x00000100 \
  --os_version 7.1.2 \
  --os_patch_level 2019-08 \
  --hashtype sha1 \
  --header_version 0 \
  -o /tmp/boot-patched.img
```

### Step 6: Verify

```bash
# Check boot.img header
$MKBOOT/unpackbootimg -i /tmp/boot-patched.img -o /tmp/boot_verify/

# Verify ramdisk content
mkdir -p /tmp/verify_rd && cd /tmp/verify_rd
gunzip -c /tmp/boot_verify/boot-patched.img-ramdisk | cpio -it 2>&1 | wc -l
# Should show 42 (42 entries + block count line = 43 lines, minus TRAILER)

# Verify no ./ prefix
gunzip -c /tmp/boot_verify/boot-patched.img-ramdisk | cpio -it 2>&1 | head -5
# Should show: acct, bugreports, cache, charger, config (no ./ prefix)

# Verify properties
gunzip -c /tmp/boot_verify/boot-patched.img-ramdisk | cpio -id 2>/dev/null
grep -E "ro.secure|ro.adb.secure|ro.debuggable|persist.sys.usb.config" default.prop
```

## cpio newc Format Reference

Each entry has a 110-byte ASCII hex header:

```
Offset  Size  Field
0       6     Magic: "070701"
6       8     Inode (unique per entry)
14      8     Mode (file type + permissions)
22      8     UID (0 for root)
30      8     GID (0 for root)
38      8     Nlink (1 for mkbootfs)
46      8     Mtime (0 for mkbootfs)
54      8     File size
62      8     Dev major (0)
70      8     Dev minor (0)
78      8     Rdev major (0)
86      8     Rdev minor (0)
94      8     Name size (including null terminator)
102     8     Checksum (0 for newc)
```

After the header: name + null byte, padded to 4-byte boundary.
After the name: file content, padded to 4-byte boundary.

Archive ends with a `TRAILER!!!` entry. AOSP mkbootfs pads the entire archive to a 256-byte boundary.

## Related

- [OTA_REPACK_GUIDE.md](OTA_REPACK_GUIDE.md) — Full OTA build procedure
- [OTA_INSTALL_LOG.md](OTA_INSTALL_LOG.md) — Install attempt history
- `tools/mkcpioimg.py` — The ramdisk packing tool
- `tools/mkbootimg/` — Boot image pack/unpack tools
