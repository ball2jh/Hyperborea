#!/usr/bin/env python3
"""
Repack the stock NordicTrack S22i OTA zip into a modified, signed OTA.

Reads the stock OTA zip entry by entry and produces a new zip that:
  - Strips old signatures (META-INF/MANIFEST.MF, CERT.SF, CERT.RSA)
  - Demotes ERU from priv-app/ to app/, strips sharedUserId, re-signs with throwaway key
  - Patches Bluetooth APK to enable BLE peripheral mode (advertising)
  - Replaces specified files (boot.img, build.prop, launcher, otacerts, etc.)
  - Re-signs APKs that were signed with iFit's platform key
  - Adds new entries (ota_postinstall binary, ADB key, Hyperborea APK, IFW XML)
  - Signs the final OTA zip with our OTA signing key

Usage: python3 iFit/firmware/tools/repack_ota.py
       (run from the project root)
"""

import os
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

# ---------------------------------------------------------------------------
# Paths (all relative to project root)
# ---------------------------------------------------------------------------

STOCK_OTA = "iFit/firmware/downloads/MGA1_20210616_MGA1_20210901.zip"
MODIFIED_DIR = "iFit/firmware/repack/MGA1_20210901/modified"

OTA_SIGNING_KEY = "iFit/firmware/keys/ota_signing.pk8"
OTA_SIGNING_CERT = "iFit/firmware/keys/ota_signing.x509.pem"

PLATFORM_KEY = "iFit/firmware/keys/platform.pk8"
PLATFORM_CERT = "iFit/firmware/keys/platform.x509.pem"
IFIT_PLATFORM_SHA1 = "0c4791b2c62d119a733a008fe8d6232c96c29a19"

THROWAWAY_KEY = "iFit/firmware/keys/throwaway.pk8"
THROWAWAY_CERT = "iFit/firmware/keys/throwaway.x509.pem"

ADB_KEY_SOURCE = os.path.expanduser("~/.android/adbkey.pub")

OUTPUT_DIR = "iFit/firmware/repack/MGA1_20210901/output"
OUTPUT_UNSIGNED = os.path.join(OUTPUT_DIR, "MGA1_20210901-unsigned.zip")
OUTPUT_SIGNED = os.path.join(OUTPUT_DIR, "MGA1_20210901-signed.zip")

# ---------------------------------------------------------------------------
# File tables
# ---------------------------------------------------------------------------

REPLACEMENTS = {
    "boot.img": os.path.join(MODIFIED_DIR, "boot.img"),
    "system/build.prop": os.path.join(MODIFIED_DIR, "system/build.prop"),
    "system/etc/security/otacerts.zip": "iFit/firmware/keys/otacerts.zip",
    "system/bin/install-recovery.sh": os.path.join(MODIFIED_DIR, "system/bin/install-recovery.sh"),
    "system/priv-app/com.ifit.launcher-1.0.17.22/com.ifit.launcher-1.0.17.22.apk": os.path.join(
        MODIFIED_DIR, "system/priv-app/com.ifit.launcher-1.0.17.22/com.ifit.launcher-1.0.17.22.apk"
    ),
    "META-INF/com/google/android/updater-script": os.path.join(
        MODIFIED_DIR, "META-INF/com/google/android/updater-script"
    ),
}

ADDITIONS = {
    "ota_postinstall": "iFit/firmware/tools/ota_postinstall_arm64",
    "adb_keys": ADB_KEY_SOURCE,
    "system/priv-app/Hyperborea/Hyperborea.apk": "app/build/outputs/apk/system/debug/app-system-debug.apk",
}

SKIP = {
    "META-INF/MANIFEST.MF",
    "META-INF/CERT.SF",
    "META-INF/CERT.RSA",
}

ERU_PREFIX = "system/priv-app/com.ifit.eru-"
BLUETOOTH_APK = "system/app/Bluetooth/Bluetooth.apk"

# Timestamp for new/added entries (matches original OTA vintage)
NEW_ENTRY_DATE = (2021, 9, 1, 0, 0, 0)

# ---------------------------------------------------------------------------
# Zip entry helpers
# ---------------------------------------------------------------------------


def copy_zipinfo(info: zipfile.ZipInfo, name: str) -> zipfile.ZipInfo:
    """Create a new ZipInfo preserving all attributes from the original."""
    new_info = zipfile.ZipInfo(name)
    new_info.compress_type = info.compress_type
    new_info.flag_bits = info.flag_bits
    new_info.create_system = info.create_system
    new_info.create_version = info.create_version
    new_info.extract_version = info.extract_version
    new_info.date_time = info.date_time
    new_info.external_attr = info.external_attr
    new_info.internal_attr = info.internal_attr
    return new_info


def make_new_zipinfo(name: str, executable: bool = False) -> zipfile.ZipInfo:
    """Create a ZipInfo for a brand-new entry with appropriate attributes."""
    info = zipfile.ZipInfo(name)
    info.compress_type = zipfile.ZIP_STORED
    info.date_time = NEW_ENTRY_DATE
    if executable:
        info.external_attr = 0o755 << 16  # rwxr-xr-x
    else:
        info.external_attr = 0o644 << 16  # rw-r--r--
    return info


# ---------------------------------------------------------------------------
# Android build tools
# ---------------------------------------------------------------------------


def find_build_tool(name: str) -> str:
    """Locate an Android SDK build tool (e.g. apksigner, zipalign).

    Searches $ANDROID_HOME/build-tools/ or ~/Android/Sdk/build-tools/,
    iterating version directories in reverse sorted order (newest first).

    Returns the absolute path to the tool.
    Raises FileNotFoundError if not found.
    """
    android_home = os.environ.get("ANDROID_HOME")
    if android_home:
        candidates = [Path(android_home) / "build-tools"]
    else:
        candidates = [Path.home() / "Android" / "Sdk" / "build-tools"]

    for build_tools_dir in candidates:
        if not build_tools_dir.is_dir():
            continue
        versions = sorted(build_tools_dir.iterdir(), reverse=True)
        for version_dir in versions:
            tool_path = version_dir / name
            if tool_path.is_file():
                return str(tool_path)

    raise FileNotFoundError(
        f"Could not find '{name}' in Android SDK build-tools. "
        f"Set $ANDROID_HOME or install build-tools under ~/Android/Sdk/"
    )


def get_apk_cert_sha1(apk_path: str) -> str:
    """Extract the SHA-1 certificate fingerprint from an APK.

    Runs apksigner verify --print-certs and parses the SHA-1 digest line.
    Returns lowercase hex SHA1 without colons, or empty string on failure.
    """
    try:
        apksigner = find_build_tool("apksigner")
        result = subprocess.run(
            [apksigner, "verify", "--print-certs", apk_path],
            capture_output=True, text=True, check=True,
        )
        for line in result.stdout.splitlines():
            if "SHA-1 digest:" in line:
                sha1 = line.split("SHA-1 digest:")[-1].strip()
                return sha1.replace(":", "").lower()
    except (FileNotFoundError, subprocess.CalledProcessError):
        pass
    return ""


def resign_apk(apk_data: bytes, platform_key: str, platform_cert: str) -> bytes:
    """Re-sign an APK with the given platform key and certificate.

    Writes the APK data to a temp file, zipaligns it, signs it with apksigner,
    and returns the re-signed APK bytes.
    """
    apksigner = find_build_tool("apksigner")
    zipalign = find_build_tool("zipalign")

    tmp_dir = tempfile.mkdtemp(prefix="resign_")
    input_path = os.path.join(tmp_dir, "input.apk")
    aligned_path = os.path.join(tmp_dir, "aligned.apk")
    output_path = os.path.join(tmp_dir, "output.apk")

    try:
        with open(input_path, "wb") as f:
            f.write(apk_data)

        subprocess.run(
            [zipalign, "-f", "4", input_path, aligned_path],
            check=True, capture_output=True,
        )

        subprocess.run(
            [
                apksigner, "sign",
                "--key", platform_key,
                "--cert", platform_cert,
                "--min-sdk-version", "25",
                "--out", output_path,
                aligned_path,
            ],
            check=True, capture_output=True,
        )

        with open(output_path, "rb") as f:
            return f.read()
    finally:
        for p in (input_path, aligned_path, output_path):
            if os.path.exists(p):
                os.unlink(p)
        # Also clean up .idsig files that apksigner may create
        for leftover in Path(tmp_dir).glob("*"):
            leftover.unlink()
        os.rmdir(tmp_dir)


def decompile_and_strip_eru(apk_data: bytes, key: str, cert: str) -> bytes:
    """Decompile ERU APK, strip sharedUserId and crashing receivers, rebuild, and re-sign.

    1. Decompile with apktool
    2. Remove android:sharedUserId from AndroidManifest.xml
    3. Remove receivers that crash without signature permissions:
       - TabletStartupReceiver (needs SET_TIME — crashes on BOOT_COMPLETED)
       - UsbDeviceAttachedReceiver (needs MANAGE_USB — crashes on USB_DEVICE_ATTACHED)
    4. Rebuild with apktool
    5. Zipalign and sign with the throwaway key
    """
    apksigner = find_build_tool("apksigner")
    zipalign = find_build_tool("zipalign")

    tmp_dir = tempfile.mkdtemp(prefix="eru_strip_")
    apk_path = os.path.join(tmp_dir, "eru.apk")
    decoded_dir = os.path.join(tmp_dir, "decoded")
    rebuilt_path = os.path.join(tmp_dir, "rebuilt.apk")
    aligned_path = os.path.join(tmp_dir, "aligned.apk")
    signed_path = os.path.join(tmp_dir, "signed.apk")

    try:
        with open(apk_path, "wb") as f:
            f.write(apk_data)

        # Decompile
        subprocess.run(
            ["apktool", "d", "-f", "-o", decoded_dir, apk_path],
            check=True, capture_output=True,
        )

        # Strip sharedUserId and crashing receivers from manifest
        manifest_path = os.path.join(decoded_dir, "AndroidManifest.xml")
        with open(manifest_path, "r") as f:
            manifest = f.read()

        manifest = re.sub(r'\s*android:sharedUserId="[^"]*"', "", manifest)

        # Remove receivers that crash without signature-level permissions.
        # These receivers have unhandled SecurityExceptions that kill the
        # entire ERU process on boot, preventing it from functioning at all.
        crash_receivers = [
            "TabletStartupReceiver",      # SET_TIME — crashes on BOOT_COMPLETED
            "UsbDeviceAttachedReceiver",   # MANAGE_USB — crashes on USB_DEVICE_ATTACHED
        ]
        for receiver in crash_receivers:
            # Match the full <receiver ...>...</receiver> block (or self-closing)
            manifest = re.sub(
                rf'<receiver\s[^>]*?\.{receiver}"[^>]*?/>\s*',
                "",
                manifest,
            )
            manifest = re.sub(
                rf'<receiver\s[^>]*?\.{receiver}"[^>]*?>.*?</receiver>\s*',
                "",
                manifest,
                flags=re.DOTALL,
            )

        with open(manifest_path, "w") as f:
            f.write(manifest)

        # Rebuild
        subprocess.run(
            ["apktool", "b", "-o", rebuilt_path, decoded_dir],
            check=True, capture_output=True,
        )

        # Zipalign
        subprocess.run(
            [zipalign, "-f", "4", rebuilt_path, aligned_path],
            check=True, capture_output=True,
        )

        # Sign with throwaway key
        subprocess.run(
            [
                apksigner, "sign",
                "--key", key,
                "--cert", cert,
                "--min-sdk-version", "25",
                "--out", signed_path,
                aligned_path,
            ],
            check=True, capture_output=True,
        )

        with open(signed_path, "rb") as f:
            return f.read()
    finally:
        shutil.rmtree(tmp_dir, ignore_errors=True)


def patch_bluetooth_peripheral_mode(apk_data: bytes, key: str, cert: str) -> bytes:
    """Patch the Bluetooth APK to enable BLE peripheral mode (advertising).

    The S22i's BCM4345c0 supports BLE advertising, but the BSP's
    framework-res.apk sets config_bluetooth_le_peripheral_mode_supported=false.
    AdapterService.isPeripheralModeSupported() reads this framework resource,
    causing getBluetoothLeAdvertiser() to return null.

    Fix: patch isPeripheralModeSupported() in AdapterService to return true.
    """
    apksigner = find_build_tool("apksigner")
    zipalign = find_build_tool("zipalign")

    tmp_dir = tempfile.mkdtemp(prefix="bt_patch_")
    apk_path = os.path.join(tmp_dir, "Bluetooth.apk")
    decoded_dir = os.path.join(tmp_dir, "decoded")
    rebuilt_path = os.path.join(tmp_dir, "rebuilt.apk")
    aligned_path = os.path.join(tmp_dir, "aligned.apk")
    signed_path = os.path.join(tmp_dir, "signed.apk")

    try:
        with open(apk_path, "wb") as f:
            f.write(apk_data)

        subprocess.run(
            ["apktool", "d", "-f", "-o", decoded_dir, apk_path],
            check=True, capture_output=True,
        )

        # Patch isPeripheralModeSupported() to return true
        smali_path = os.path.join(
            decoded_dir,
            "smali/com/android/bluetooth/btservice/AdapterService.smali",
        )
        with open(smali_path, "r") as f:
            smali = f.read()

        # Replace the method body: instead of reading the framework resource,
        # just return true (const/4 v0, 0x1)
        patched = re.sub(
            r'(\.method public isPeripheralModeSupported\(\)Z)\s*'
            r'\.locals \d+.*?'
            r'(\.end method)',
            r'\1\n'
            r'    .locals 1\n'
            r'\n'
            r'    const/4 v0, 0x1\n'
            r'\n'
            r'    return v0\n'
            r'\2',
            smali,
            flags=re.DOTALL,
        )

        if patched == smali:
            raise RuntimeError("Failed to patch isPeripheralModeSupported in AdapterService.smali")

        with open(smali_path, "w") as f:
            f.write(patched)

        subprocess.run(
            ["apktool", "b", "-o", rebuilt_path, decoded_dir],
            check=True, capture_output=True,
        )

        subprocess.run(
            [zipalign, "-f", "4", rebuilt_path, aligned_path],
            check=True, capture_output=True,
        )

        subprocess.run(
            [
                apksigner, "sign",
                "--key", key,
                "--cert", cert,
                "--min-sdk-version", "25",
                "--out", signed_path,
                aligned_path,
            ],
            check=True, capture_output=True,
        )

        with open(signed_path, "rb") as f:
            return f.read()
    finally:
        shutil.rmtree(tmp_dir, ignore_errors=True)


# ---------------------------------------------------------------------------
# Validation
# ---------------------------------------------------------------------------


def validate_inputs(project_root: Path) -> None:
    """Verify that all required input files exist before starting."""
    missing = []

    stock = project_root / STOCK_OTA
    if not stock.exists():
        missing.append(str(stock))

    for label, path in [
        ("OTA signing key", OTA_SIGNING_KEY),
        ("OTA signing cert", OTA_SIGNING_CERT),
        ("Platform key", PLATFORM_KEY),
        ("Platform cert", PLATFORM_CERT),
        ("Throwaway key", THROWAWAY_KEY),
        ("Throwaway cert", THROWAWAY_CERT),
    ]:
        if not (project_root / path).exists():
            missing.append(f"{label}: {project_root / path}")

    if not shutil.which("apktool"):
        missing.append("apktool: not found on PATH (install with: yay -S android-apktool)")

    if not os.path.exists(ADB_KEY_SOURCE):
        missing.append(f"ADB public key: {ADB_KEY_SOURCE}")

    for entry_name, rel_path in REPLACEMENTS.items():
        full = project_root / rel_path
        if not full.exists():
            missing.append(f"replacement ({entry_name}): {full}")

    for entry_name, rel_path in ADDITIONS.items():
        # adb_keys uses an absolute path, not relative to project root
        if os.path.isabs(rel_path):
            if not os.path.exists(rel_path):
                missing.append(f"addition ({entry_name}): {rel_path}")
        else:
            full = project_root / rel_path
            if not full.exists():
                missing.append(f"addition ({entry_name}): {full}")

    if missing:
        print("ERROR: Missing required files:", file=sys.stderr)
        for m in missing:
            print(f"  - {m}", file=sys.stderr)
        sys.exit(1)


# ---------------------------------------------------------------------------
# Main repack logic
# ---------------------------------------------------------------------------


def repack(project_root: Path) -> None:
    """Build the unsigned OTA zip from the stock OTA and modified files."""
    stock_path = project_root / STOCK_OTA
    output_path = project_root / OUTPUT_UNSIGNED

    platform_key_path = str(project_root / PLATFORM_KEY)
    platform_cert_path = str(project_root / PLATFORM_CERT)

    # Ensure output directory exists
    output_path.parent.mkdir(parents=True, exist_ok=True)

    throwaway_key_path = str(project_root / THROWAWAY_KEY)
    throwaway_cert_path = str(project_root / THROWAWAY_CERT)

    entries_skipped = 0
    entries_replaced = 0
    entries_copied = 0
    entries_added = 0
    entries_resigned = 0
    entries_patched = 0
    eru_demoted = 0

    # Collect ERU entries for deferred processing (demote priv-app → app)
    eru_entries: list[tuple[zipfile.ZipInfo, bytes]] = []

    with zipfile.ZipFile(stock_path, "r") as stock_zip, \
         zipfile.ZipFile(output_path, "w") as out_zip:

        for info in stock_zip.infolist():
            name = info.filename

            # 1. Skip old signatures
            if name in SKIP:
                print(f"  SKIP      {name}")
                entries_skipped += 1
                continue

            # 2. Collect ERU entries for deferred demotion
            if name.startswith(ERU_PREFIX):
                data = stock_zip.read(name)
                eru_entries.append((info, data))
                continue

            # 2b. Patch Bluetooth APK to enable BLE peripheral mode
            if name == BLUETOOTH_APK:
                data = stock_zip.read(name)
                original_size = len(data)
                data = patch_bluetooth_peripheral_mode(data, platform_key_path, platform_cert_path)
                new_info = copy_zipinfo(info, name)
                out_zip.writestr(new_info, data)
                print(f"  PATCH     {name} (BLE peripheral mode) ({original_size} -> {len(data)} bytes)")
                entries_patched += 1
                continue

            # 3. Replace from REPLACEMENTS dict
            if name in REPLACEMENTS:
                replacement_path = project_root / REPLACEMENTS[name]
                data = replacement_path.read_bytes()
                new_info = copy_zipinfo(info, name)
                out_zip.writestr(new_info, data)
                print(f"  REPLACE   {name} ({info.file_size} -> {len(data)} bytes)")
                entries_replaced += 1
                continue

            # 4. Re-sign platform APKs
            data = stock_zip.read(name)
            if name.endswith(".apk"):
                with tempfile.NamedTemporaryFile(suffix=".apk", delete=False) as tmp:
                    tmp.write(data)
                    tmp_path = tmp.name
                try:
                    sha1 = get_apk_cert_sha1(tmp_path)
                    if sha1 == IFIT_PLATFORM_SHA1:
                        resigned = resign_apk(data, platform_key_path, platform_cert_path)
                        new_info = copy_zipinfo(info, name)
                        out_zip.writestr(new_info, resigned)
                        print(f"  RE-SIGN   {name} ({len(data)} -> {len(resigned)} bytes)")
                        entries_resigned += 1
                        continue
                finally:
                    os.unlink(tmp_path)

            # 5. Copy verbatim
            new_info = copy_zipinfo(info, name)
            out_zip.writestr(new_info, data)
            entries_copied += 1

        # 6. Process ERU entries: demote priv-app/ → app/, strip sharedUserId, re-sign
        for info, data in eru_entries:
            old_name = info.filename
            new_name = old_name.replace("system/priv-app/", "system/app/", 1)

            if old_name.endswith(".apk"):
                # Decompile, strip sharedUserId, rebuild, sign with throwaway key
                original_size = len(data)
                data = decompile_and_strip_eru(data, throwaway_key_path, throwaway_cert_path)
                new_info = copy_zipinfo(info, new_name)
                out_zip.writestr(new_info, data)
                print(f"  DEMOTE    {old_name} -> {new_name} ({original_size} -> {len(data)} bytes)")
            else:
                # Directory entry or non-APK file — just rewrite path
                new_info = copy_zipinfo(info, new_name)
                out_zip.writestr(new_info, data)
                print(f"  DEMOTE    {old_name} -> {new_name}")

            eru_demoted += 1

        # 7. Add new entries
        for entry_name, rel_path in ADDITIONS.items():
            if os.path.isabs(rel_path):
                source_path = Path(rel_path)
            else:
                source_path = project_root / rel_path
            data = source_path.read_bytes()

            # Re-sign APK additions with the platform key
            if entry_name.endswith(".apk"):
                original_size = len(data)
                data = resign_apk(data, platform_key_path, platform_cert_path)
                print(f"  ADD+SIGN  {entry_name} <- {rel_path} ({original_size} -> {len(data)} bytes)")
                entries_resigned += 1
            else:
                print(f"  ADD       {entry_name} <- {rel_path} ({len(data)} bytes)")

            executable = not entry_name.endswith((".apk", ".xml"))
            new_info = make_new_zipinfo(entry_name, executable=executable)
            out_zip.writestr(new_info, data)
            entries_added += 1

    output_size = os.path.getsize(output_path)
    print()
    print(f"Unsigned OTA: {output_path} ({output_size:,} bytes)")
    print(f"  Copied:      {entries_copied}")
    print(f"  Replaced:    {entries_replaced}")
    print(f"  Re-signed:   {entries_resigned}")
    print(f"  Patched:     {entries_patched}")
    print(f"  Added:       {entries_added}")
    print(f"  Skipped:     {entries_skipped}")
    print(f"  ERU demoted: {eru_demoted}")


def sign_and_verify(project_root: Path) -> None:
    """Sign the unsigned OTA zip and verify the result."""
    print()
    print("Signing OTA...")
    subprocess.run(
        [
            sys.executable, str(project_root / "iFit/firmware/tools/sign_ota.py"),
            str(project_root / OTA_SIGNING_CERT),
            str(project_root / OTA_SIGNING_KEY),
            str(project_root / OUTPUT_UNSIGNED),
            str(project_root / OUTPUT_SIGNED),
        ],
        check=True,
    )

    print()
    print("Verifying OTA signature...")
    subprocess.run(
        [
            sys.executable, str(project_root / "iFit/firmware/tools/verify_ota.py"),
            str(project_root / OUTPUT_SIGNED),
            str(project_root / OTA_SIGNING_CERT),
        ],
        check=True,
    )


def find_project_root() -> Path:
    """Walk up from this script's location to find the project root."""
    current = Path(__file__).resolve().parent
    while current != current.parent:
        if (current / "settings.gradle.kts").exists():
            return current
        current = current.parent
    return Path(__file__).resolve().parent.parent.parent.parent


def main() -> None:
    project_root = find_project_root()
    print(f"Project root: {project_root}")
    print()

    print("Validating inputs...")
    validate_inputs(project_root)
    print("All inputs present.")
    print()

    print("Repacking OTA...")
    repack(project_root)

    sign_and_verify(project_root)

    signed_path = project_root / OUTPUT_SIGNED
    size_mb = signed_path.stat().st_size / (1024 * 1024)
    print()
    print(f"Done. Signed OTA: {signed_path} ({size_mb:.1f} MB)")


if __name__ == "__main__":
    main()
