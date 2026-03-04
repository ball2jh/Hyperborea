# Recovery Boot Loop Analysis — Attempts #8 & #9

## Summary

Both attempt #8 (`/data/update.zip`) and #9 (`/cache/update.zip`) resulted in recovery boot loops with zero log output. This document analyzes verified root causes and proposes debugging steps.

## What Has Been Verified (NOT the Problem)

| Item | Status | Evidence |
|------|--------|----------|
| Key pair (pk8 ↔ x509.pem) | Correct | OpenSSL modulus matches |
| recovery_res_keys format | Correct | mincrypt v2 modulus matches OpenSSL key |
| Key exponent | Correct | Stock OTA cert uses e=65537, v2 maps to e=65537 in AOSP |
| OTA whole-file signature | Valid | `verify_ota_aosp.py` passes with SHA-1 digest |
| PKCS#7 format | Correct | No signed attributes (`-noattr`), matches AOSP `read_pkcs7()` |
| Ramdisk structure | Intact | Only `res/keys` differs; all other files byte-for-byte identical |
| cpio format | Correct | SVR4 newc, same magic `070701`, same uncompressed size |
| BCB format | Correct | Matches AOSP `write_bootloader_message()` exactly |
| /data encryption | None | No `forceencrypt`/`encryptable` in fstab for /data |
| Comment line in /res/keys | Not an issue | Stock key also has `# Key info:` comment — Nexell binary handles it |
| Recovery binary | Untouched | Stock AOSP, statically linked ARM64, 1.4MB |

## The `flash_recovery` Problem (HIGH PRIORITY)

### How It Works

`/system/bin/install-recovery.sh` runs on **every normal boot** as a `oneshot` service:

```bash
if ! applypatch -c EMMC:/dev/block/mmcblk0p6:23750656:SHA1_STOCK; then
  applypatch -b /system/etc/recovery-resource.dat \
    EMMC:/dev/block/mmcblk0p1:22302720:SHA1_BOOT \
    EMMC:/dev/block/mmcblk0p6 SHA1_STOCK 23750656 \
    SHA1_BOOT:/system/recovery-from-boot.p
fi
```

1. **Check**: SHA1 of first 23,750,656 bytes of recovery partition
2. **If mismatch**: Reconstruct stock recovery from boot.img + differential patch
3. **Write**: Overwrite recovery partition with stock image
4. **Script always exits 0** — even if patch fails, it just logs "failed"

### The Risk

Between attempt #8 (failure) and attempt #9:
1. Device booted normally
2. `flash_recovery` ran and "exited 0"
3. **We did NOT re-patch recovery for attempt #9**

If `flash_recovery` successfully restored stock recovery, attempt #9 ran with the **stock iFit key** in `/res/keys`. Our custom-signed OTA would fail verification (signed with our key, verified against iFit's key → INSTALL_CORRUPT).

### Why the OTA Guide Says It Survives

The guide notes our patched ramdisk (3.6MB) is larger than the original (2.9MB), and suggests `applypatch` "can't patch" due to the size difference. This claim was based on a single observation where the ramdisk appeared to survive.

**However**, `applypatch` works on the **raw partition** (23.7MB of block data), not individual files. It reconstructs the entire partition from boot.img via a differential patch. The ramdisk file size is irrelevant — what matters is whether boot.img's SHA1 matches the expected source. If boot.img is unmodified (which it should be since no OTA ever succeeded), `flash_recovery` **can and will** restore stock recovery.

**Conclusion**: The guide's claim that our patch survives `flash_recovery` is likely wrong. We must re-patch recovery before each install attempt.

## Boot Loop Mechanism

```
init starts recovery service (NOT oneshot in init.rc)
  → recovery reads BCB, gets --update_package=...
  → recovery crashes/errors BEFORE copy_logs()
  → recovery exits
init sees recovery exited, restarts it (not oneshot)
  → recovery reads BCB again (get_args() reads BCB on every start)
  → same crash/error
  → infinite loop
```

**Why no logs**: `copy_logs()` (recovery.cpp:484-516) writes `/tmp/recovery.log` → `/cache/recovery/last_log`. It IS called from `finish_recovery()` (line 551) and `prompt_and_wait()` (line 1256), but **returns early** when `modified_flash=false` (line 488). If recovery crashes before setting `modified_flash=true`, or if the install never starts, logs are skipped because the early-return guard prevents writing them — not because the function is never reached.

If recovery crashes inside `install_package()` (or before it reaches a point where `modified_flash` is set), logs are never copied to persistent storage.

## What Happens Inside `install_package()`

```
install_package()                          # install.cpp:526
├─ setup_install_mounts()                  # roots.cpp:280-303
│  ├─ ensure_path_mounted("/tmp")          # Mounts /tmp
│  ├─ ensure_path_mounted("/cache")        # Mounts /cache
│  └─ ensure_path_unmounted(everything)    # Unmounts ALL others (including /data)
├─ really_install_package()
│  ├─ ensure_path_mounted(path)            # install.cpp:470-476 — mounts package path
│  │   # Note: if path="/data/update.zip", re-mounts /data here
│  │   # if path starts with "@", strips prefix and mounts underlying path
│  ├─ sysMapFile(path)                     # mmap the 538MB OTA
│  ├─ verify_package()
│  │   ├─ load_keys("/res/keys")           # Parse mincrypt key file
│  │   └─ verify_file()                    # SHA-1 hash 538MB + RSA verify
│  ├─ mzOpenZipArchive()
│  └─ try_update_binary()                  # Fork + exec updater
```

If **any** of these steps causes a crash (segfault, abort, OOM), recovery exits without logs.

If they return an error, the caller does `copy_logs()` + `prompt_and_wait()` — which would produce logs and show the error screen (not a boot loop, since `prompt_and_wait` blocks).

The boot loop with no logs means recovery **crashes** (abnormal exit), not a handled error.

## Possible Crash Causes

1. **Stale/wrong key in /res/keys**: If `flash_recovery` restored stock key, `load_keys()` loads iFit's key. `verify_file()` returns VERIFY_FAILURE. This is a **handled error** (not a crash) — should produce logs. Unless the Nexell binary handles it differently.

2. **OOM during mmap**: mmap'ing 538MB on a device with ~1-2GB RAM could trigger OOM killer. This would kill the recovery process without any log output.

3. **Filesystem error**: If the ext4 journal on /data is dirty (from the previous crash), mounting /data could hang or fail in unexpected ways.

4. **Nexell recovery divergence**: The recovery binary may differ from AOSP source in error handling, potentially crashing where AOSP would handle gracefully.

## Debugging Plan (Next Session)

### Step 0: Clear stale BCB
```bash
adb root
adb shell /data/local/tmp/write_bcb /dev/block/mmcblk0p7
# Verify: dd if=/dev/block/mmcblk0p7 bs=2048 count=1 | strings (should be empty)
```

### Step 1: Check if flash_recovery restored stock
```bash
adb shell mount -t ext4 -o ro /dev/block/mmcblk0p6 /data/local/tmp/recovery_mnt
adb shell ls -la /data/local/tmp/recovery_mnt/ramdisk-recovery.img
# If size is ~2.9MB → stock was restored → flash_recovery works
# If size is ~3.6MB → our patch survived → flash_recovery failed
adb shell umount /data/local/tmp/recovery_mnt
```

### Step 2: Test recovery WITHOUT update command
This tests whether recovery itself boots at all, independent of OTA installation.

```bash
# Re-patch recovery if needed (see Step 3 if stock was restored)
# Write BCB with no update package — just boot into recovery
adb shell /data/local/tmp/write_bcb /dev/block/mmcblk0p7

# Actually, an empty BCB won't trigger recovery. Need boot-recovery command
# but no --update_package arg. Write BCB manually:
# command="boot-recovery", recovery="recovery\n"
# This should show the "no command" screen with the dead Android

# For this, modify write_bcb or use dd approach
```

**Expected result**: Recovery shows "No command" screen. If it shows this, recovery itself works and the issue is in the OTA install flow. If boot loop occurs, recovery is broken.

### Step 3: Re-patch recovery (if Step 1 shows stock was restored)
```bash
# Mount recovery
adb shell mkdir -p /data/local/tmp/recovery_mnt
adb shell mount -t ext4 -o rw /dev/block/mmcblk0p6 /data/local/tmp/recovery_mnt

# Backup current ramdisk
adb shell cp /data/local/tmp/recovery_mnt/ramdisk-recovery.img \
  /data/local/tmp/recovery_mnt/ramdisk-recovery.img.bak

# Push patched ramdisk
adb push firmware/repack/recovery_work/ramdisk-recovery-patched.img \
  /data/local/tmp/recovery_mnt/ramdisk-recovery.img
adb shell chmod 644 /data/local/tmp/recovery_mnt/ramdisk-recovery.img

# Unmount
adb shell umount /data/local/tmp/recovery_mnt

# IMPORTANT: Do NOT reboot normally after this — go directly to recovery
```

### Step 4: Try USB ADB in recovery
Recovery has `ro.debuggable=1` and adbd configured for USB (init.recovery.avn_ref.rc). If a USB cable can be connected during recovery:
```bash
# From macOS, with USB cable connected to bike's USB port:
adb devices
# If recovery's adbd is running, device should appear
adb shell cat /tmp/recovery.log
adb shell dmesg
```

### Step 5: Attempt OTA install (after Steps 1-3)
```bash
# Verify patched recovery is in place
# Push OTA if needed
# Write BCB
# Reboot into recovery
# IMMEDIATELY after reboot, try USB ADB to monitor
```

### Step 6: Alternative — disable flash_recovery
If `flash_recovery` is confirmed to be restoring stock recovery, disable it:
```bash
adb root
adb remount
adb shell mv /system/bin/install-recovery.sh /system/bin/install-recovery.sh.bak
# Now flash_recovery service will fail to start (missing binary)
# Re-patch recovery, then reboot normally to verify it persists
```

## Key Takeaways

1. **`flash_recovery` most likely restores stock recovery on every normal boot** — the OTA guide's claim it survives is suspect
2. **Must re-patch recovery immediately before each attempt** — no normal boots in between
3. **Boot loop = recovery crashes** — not a handled error; no logs produced
4. **Both /data and /cache paths trigger the same crash** — rules out mount-specific issues
5. **USB ADB in recovery is our best debugging tool** — connect before and during recovery boot

## Source Code Analysis Update (2026-02-11)

Analysis of AOSP 7.1.2 recovery source (`NexellCorp/aosp-7.1.2-analysis/recovery/`) revealed:

- **`modified_flash=true` is set at `recovery.cpp:1662` (before `install_package()` is called) and at `install.cpp:521` (first line of `install_package()`)**. This means ALL handled errors (verification failure, mount failure, corrupt zip) would produce logs via `copy_logs()` and block in `prompt_and_wait()` showing an error screen — NOT a boot loop. The flash_recovery/wrong-key theory cannot explain the boot loop behavior.
- **A boot loop with zero logs can ONLY result from a hard crash** (SIGKILL from OOM, SIGSEGV, SIGBUS) that kills the process before `install_package()` returns.
- **Our BCB bypass skipped `uncrypt`**, sending recovery down the `sysMapFD()` path (single 538MB mmap from mounted filesystem) instead of the `sysMapBlockFile()` path (block ranges from raw device). The framework (`RecoverySystem.java:456-483`) always uses uncrypt+block.map for /data paths.

**Corrected procedure**: See `docs/firmware/RECOVERY_OTA_INSTALL.md` — uses a custom `mkblockmap` tool to generate the block map, then writes BCB with `@/cache/recovery/block.map` prefix to use the intended code path.
