#!/usr/bin/env python3
"""
Repack the stock NordicTrack S22i OTA zip into a modified, signed OTA.

Reads the stock OTA zip entry by entry and produces a new zip that:
  - Strips old signatures (META-INF/MANIFEST.MF, CERT.SF, CERT.RSA)
  - Removes the iFit ERU app (system/priv-app/com.ifit.eru-*)
  - Replaces specified files (boot.img, build.prop, otacerts, etc.)
  - Re-signs APKs that were signed with iFit's platform certificate
  - Adds new entries (ota_postinstall binary, Hyperborea APK)
  - Signs the final OTA zip with our OTA signing key

Usage: python3 iFit/firmware/tools/repack_ota.py
       (run from the project root)
"""

import io
import os
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

from cryptography import x509
from cryptography.hazmat.backends import default_backend
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.serialization import pkcs7

# ---------------------------------------------------------------------------
# Paths (all relative to project root)
# ---------------------------------------------------------------------------

STOCK_OTA = "iFit/firmware/downloads/MGA1_20210616_MGA1_20210901.zip"

PLATFORM_KEY = "iFit/firmware/keys/platform.pk8"
PLATFORM_CERT = "iFit/firmware/keys/platform.x509.pem"
OTA_SIGNING_KEY = "iFit/firmware/keys/ota_signing.pk8"
OTA_SIGNING_CERT = "iFit/firmware/keys/ota_signing.x509.pem"

OUTPUT_DIR = "iFit/firmware/repack/MGA1_20210901/output"
OUTPUT_UNSIGNED = os.path.join(OUTPUT_DIR, "MGA1_20210901-unsigned.zip")
OUTPUT_SIGNED = os.path.join(OUTPUT_DIR, "MGA1_20210901-signed.zip")

APKSIGNER = os.path.expanduser("~/Android/Sdk/build-tools/36.1.0/apksigner")
ZIPALIGN = os.path.expanduser("~/Android/Sdk/build-tools/36.1.0/zipalign")

# ---------------------------------------------------------------------------
# File tables
# ---------------------------------------------------------------------------

REPLACEMENTS = {
    "boot.img": "iFit/firmware/repack/MGA1_20210901/modified/boot.img",
    "system/build.prop": "iFit/firmware/repack/MGA1_20210901/modified/system/build.prop",
    "system/etc/security/otacerts.zip": "iFit/firmware/keys/otacerts.zip",
    "system/bin/install-recovery.sh": "iFit/firmware/repack/MGA1_20210901/modified/system/bin/install-recovery.sh",
    "META-INF/com/google/android/updater-script": "iFit/firmware/repack/MGA1_20210901/modified/META-INF/com/google/android/updater-script",
}

ADDITIONS = {
    "ota_postinstall": "iFit/firmware/tools/ota_postinstall_arm64",
    "system/priv-app/Hyperborea/Hyperborea.apk": "app/build/outputs/apk/system/debug/app-system-debug.apk",
}

SKIP = {
    "META-INF/MANIFEST.MF",
    "META-INF/CERT.SF",
    "META-INF/CERT.RSA",
}

ERU_PREFIX = "system/priv-app/com.ifit.eru-"

# Timestamp for new/added entries (matches original OTA vintage)
NEW_ENTRY_DATE = (2021, 9, 1, 0, 0, 0)

# iFit platform certificate SHA-1 fingerprint
IFIT_PLATFORM_CERT_SHA1 = "0C:47:91:B2:C6:2D:11:9A:73:3A:00:8F:E8:D6:23:2C:96:C2:9A:19"

# ---------------------------------------------------------------------------
# APK certificate inspection
# ---------------------------------------------------------------------------


def get_apk_cert_sha1(apk_data: bytes) -> str | None:
    """Extract the signing certificate SHA-1 fingerprint from an APK.

    Opens the APK as a zip, reads the first META-INF/*.RSA entry, parses the
    PKCS#7 structure, and returns the SHA-1 fingerprint of the first
    certificate as a colon-separated hex string.
    """
    try:
        with zipfile.ZipFile(io.BytesIO(apk_data)) as apk:
            cert_entries = [
                n for n in apk.namelist()
                if n.startswith("META-INF/") and n.endswith(".RSA")
            ]
            if not cert_entries:
                return None
            pkcs7_data = apk.read(cert_entries[0])
            certs = pkcs7.load_der_pkcs7_certificates(pkcs7_data)
            if not certs:
                return None
            fingerprint = certs[0].fingerprint(hashes.SHA1())
            return ":".join(f"{b:02X}" for b in fingerprint)
    except Exception:
        return None


# ---------------------------------------------------------------------------
# APK re-signing
# ---------------------------------------------------------------------------


def resign_apk(
    apk_data: bytes,
    platform_key: str,
    platform_cert: str,
    zipalign_path: str,
    apksigner_path: str,
) -> bytes:
    """Re-sign an APK with our platform key.

    Writes the APK to a temp file, zipaligns it, then signs with apksigner
    using v1 only (API 25 does not require v2 signatures).
    """
    with tempfile.NamedTemporaryFile(suffix=".apk", delete=False) as tmp:
        tmp.write(apk_data)
        tmp_path = tmp.name

    aligned_path = tmp_path + ".aligned"

    try:
        # Zipalign first (apksigner requires aligned input for v2 signing)
        subprocess.run(
            [zipalign_path, "-f", "4", tmp_path, aligned_path],
            check=True,
            capture_output=True,
        )

        # Sign with apksigner (v1 only for API 25)
        subprocess.run(
            [
                apksigner_path, "sign",
                "--key", platform_key,
                "--cert", platform_cert,
                "--v1-signing-enabled", "true",
                "--v2-signing-enabled", "false",
                aligned_path,
            ],
            check=True,
            capture_output=True,
        )

        with open(aligned_path, "rb") as f:
            return f.read()
    finally:
        for p in (tmp_path, aligned_path):
            try:
                os.unlink(p)
            except OSError:
                pass


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
# Validation
# ---------------------------------------------------------------------------


def validate_inputs(project_root: Path) -> None:
    """Verify that all required input files exist before starting."""
    missing = []

    stock = project_root / STOCK_OTA
    if not stock.exists():
        missing.append(str(stock))

    for label, path in [
        ("platform key", PLATFORM_KEY),
        ("platform cert", PLATFORM_CERT),
        ("OTA signing key", OTA_SIGNING_KEY),
        ("OTA signing cert", OTA_SIGNING_CERT),
    ]:
        if not (project_root / path).exists():
            missing.append(f"{label}: {project_root / path}")

    for entry_name, rel_path in REPLACEMENTS.items():
        full = project_root / rel_path
        if not full.exists():
            missing.append(f"replacement ({entry_name}): {full}")

    for entry_name, rel_path in ADDITIONS.items():
        full = project_root / rel_path
        if not full.exists():
            missing.append(f"addition ({entry_name}): {full}")

    if not os.path.isfile(APKSIGNER):
        missing.append(f"apksigner: {APKSIGNER}")
    if not os.path.isfile(ZIPALIGN):
        missing.append(f"zipalign: {ZIPALIGN}")

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

    # Ensure output directory exists
    output_path.parent.mkdir(parents=True, exist_ok=True)

    platform_key = str(project_root / PLATFORM_KEY)
    platform_cert = str(project_root / PLATFORM_CERT)

    apks_resigned = 0
    entries_skipped = 0
    entries_replaced = 0
    entries_copied = 0
    entries_added = 0
    eru_removed = 0

    with zipfile.ZipFile(stock_path, "r") as stock_zip, \
         zipfile.ZipFile(output_path, "w") as out_zip:

        for info in stock_zip.infolist():
            name = info.filename

            # 1. Skip old signatures
            if name in SKIP:
                print(f"  SKIP      {name}")
                entries_skipped += 1
                continue

            # 2. Skip ERU app entries
            if name.startswith(ERU_PREFIX):
                print(f"  REMOVE    {name}")
                eru_removed += 1
                continue

            # 3. Replace from REPLACEMENTS dict
            if name in REPLACEMENTS:
                replacement_path = project_root / REPLACEMENTS[name]
                data = replacement_path.read_bytes()
                new_info = copy_zipinfo(info, name)
                out_zip.writestr(new_info, data)
                print(f"  REPLACE   {name} <- {REPLACEMENTS[name]}")
                entries_replaced += 1
                continue

            # Read original data
            data = stock_zip.read(name)

            # 4. Re-sign platform-signed APKs
            if name.endswith(".apk"):
                sha1 = get_apk_cert_sha1(data)
                if sha1 == IFIT_PLATFORM_CERT_SHA1:
                    print(f"  RE-SIGN   {name}")
                    data = resign_apk(data, platform_key, platform_cert, ZIPALIGN, APKSIGNER)
                    apks_resigned += 1
                    new_info = copy_zipinfo(info, name)
                    out_zip.writestr(new_info, data)
                    continue

            # 5. Copy verbatim
            new_info = copy_zipinfo(info, name)
            out_zip.writestr(new_info, data)
            print(f"  COPY      {name}")
            entries_copied += 1

        # 6. Add new entries
        for entry_name, rel_path in ADDITIONS.items():
            source_path = project_root / rel_path
            data = source_path.read_bytes()
            executable = not entry_name.endswith(".apk")
            new_info = make_new_zipinfo(entry_name, executable=executable)
            out_zip.writestr(new_info, data)
            print(f"  ADD       {entry_name} <- {rel_path}")
            entries_added += 1

    print()
    print(f"Unsigned OTA written to {output_path}")
    print(f"  Copied:    {entries_copied}")
    print(f"  Replaced:  {entries_replaced}")
    print(f"  Re-signed: {apks_resigned}")
    print(f"  Added:     {entries_added}")
    print(f"  Skipped:   {entries_skipped}")
    print(f"  ERU removed: {eru_removed}")


def sign_and_verify(project_root: Path) -> None:
    """Sign the unsigned OTA zip and verify the result."""
    print()
    print("Signing OTA...")
    subprocess.run(
        [
            sys.executable, "iFit/firmware/tools/sign_ota.py",
            str(project_root / OTA_SIGNING_CERT),
            str(project_root / OTA_SIGNING_KEY),
            str(project_root / OUTPUT_UNSIGNED),
            str(project_root / OUTPUT_SIGNED),
        ],
        check=True,
        cwd=str(project_root),
    )

    print()
    print("Verifying OTA signature...")
    subprocess.run(
        [
            sys.executable, "iFit/firmware/tools/verify_ota.py",
            str(project_root / OUTPUT_SIGNED),
            str(project_root / OTA_SIGNING_CERT),
        ],
        check=True,
        cwd=str(project_root),
    )


def find_project_root() -> Path:
    """Walk up from this script's location to find the project root."""
    current = Path(__file__).resolve().parent
    while current != current.parent:
        if (current / "settings.gradle.kts").exists() or (current / "build.gradle.kts").exists():
            return current
        current = current.parent
    # Fallback: assume script is at iFit/firmware/tools/ relative to project root
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
