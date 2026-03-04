#!/usr/bin/env python3
"""
Generate all signing keys for the NordicTrack S22i OTA firmware project.

Outputs to iFit/firmware/keys/ (configurable via --output-dir):

  Platform key (signs system APKs + Hyperborea app):
    platform.key          — PEM RSA 2048 private key
    platform.pk8          — PKCS#8 DER format (for apksigner CLI)
    platform.x509.pem     — X.509 certificate (CN=Hyperborea Platform, O=Nettarion)
    platform.p12          — PKCS#12 keystore (password: android) for Gradle signingConfig

  OTA signing key (signs the OTA zip):
    ota_signing.key        — PEM RSA 2048 private key
    ota_signing.pk8        — PKCS#8 DER format
    ota_signing.x509.pem   — X.509 certificate (CN=Hyperborea OTA, O=Nettarion)

  Derived artifacts:
    otacerts.zip           — Contains ota_signing.x509.pem; installed to
                             /system/etc/security/otacerts.zip on device
    recovery_res_keys      — AOSP mincrypt v2 text format; patched into
                             recovery ramdisk at /res/keys

Dependencies:
    - openssl CLI (for key generation)
    - Python 'cryptography' library (for mincrypt format conversion)
"""

import argparse
import os
import subprocess
import sys
import zipfile
from pathlib import Path


def find_project_root() -> Path:
    """Walk up from this script's location to find the project root (contains settings.gradle.kts)."""
    current = Path(__file__).resolve().parent
    while current != current.parent:
        if (current / "settings.gradle.kts").exists() or (current / "build.gradle.kts").exists():
            return current
        current = current.parent
    # Fallback: assume script is at iFit/firmware/tools/ relative to project root
    return Path(__file__).resolve().parent.parent.parent.parent


def run_openssl(args: list[str], description: str) -> None:
    """Run an openssl command, raising on failure."""
    cmd = ["openssl"] + args
    result = subprocess.run(cmd, capture_output=True, text=True)
    if result.returncode != 0:
        print(f"  ERROR: {description}", file=sys.stderr)
        print(f"  Command: {' '.join(cmd)}", file=sys.stderr)
        print(f"  stderr: {result.stderr.strip()}", file=sys.stderr)
        sys.exit(1)


def generate_key_pair(
    output_dir: Path,
    name: str,
    subject: str,
    validity_days: int = 3650,
) -> None:
    """
    Generate an RSA 2048 key pair with openssl:
      {name}.key          — PEM private key
      {name}.pk8          — PKCS#8 DER private key
      {name}.x509.pem     — Self-signed X.509 certificate
    """
    key_path = output_dir / f"{name}.key"
    pk8_path = output_dir / f"{name}.pk8"
    cert_path = output_dir / f"{name}.x509.pem"

    # Generate RSA 2048 private key
    run_openssl(
        ["genrsa", "-out", str(key_path), "2048"],
        f"generating {name} RSA private key",
    )
    # Restrict private key permissions
    os.chmod(key_path, 0o600)
    print(f"  {key_path.name}")

    # Convert to PKCS#8 DER (for apksigner and AOSP signing tools)
    run_openssl(
        [
            "pkcs8", "-topk8", "-nocrypt",
            "-inform", "PEM", "-outform", "DER",
            "-in", str(key_path),
            "-out", str(pk8_path),
        ],
        f"converting {name} to PKCS#8 DER",
    )
    os.chmod(pk8_path, 0o600)
    print(f"  {pk8_path.name}")

    # Generate self-signed X.509 certificate
    run_openssl(
        [
            "req", "-new", "-x509",
            "-key", str(key_path),
            "-out", str(cert_path),
            "-days", str(validity_days),
            "-subj", subject,
        ],
        f"generating {name} X.509 certificate",
    )
    print(f"  {cert_path.name}")


def generate_platform_p12(output_dir: Path) -> None:
    """Create PKCS#12 keystore from the platform key pair (for Gradle signingConfig)."""
    p12_path = output_dir / "platform.p12"
    run_openssl(
        [
            "pkcs12", "-export",
            "-in", str(output_dir / "platform.x509.pem"),
            "-inkey", str(output_dir / "platform.key"),
            "-out", str(p12_path),
            "-name", "platform",
            "-passout", "pass:android",
        ],
        "creating platform PKCS#12 keystore",
    )
    print(f"  {p12_path.name}")


def generate_otacerts_zip(output_dir: Path) -> None:
    """
    Create otacerts.zip containing ota_signing.x509.pem.
    Android framework reads /system/etc/security/otacerts.zip to verify OTA packages.
    """
    zip_path = output_dir / "otacerts.zip"
    cert_path = output_dir / "ota_signing.x509.pem"

    with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as zf:
        zf.write(cert_path, "ota_signing.x509.pem")

    print(f"  {zip_path.name}")


def generate_recovery_res_keys(output_dir: Path) -> None:
    """
    Generate AOSP mincrypt v2 text format from ota_signing.x509.pem.
    Recovery uses /res/keys to verify OTA zip signatures.

    Format (single line):
        v2 {numwords} {n0inv} {n[0]} {n[1]} ... {n[63]} {e} {rr[0]} {rr[1]} ... {rr[63]}

    Where:
        numwords  = RSA key size in 32-bit words (64 for RSA-2048)
        n0inv     = Montgomery inverse: 2^32 - (n^-1 mod 2^32)
        n[i]      = Modulus in little-endian 32-bit words
        e         = Public exponent (65537)
        rr[i]     = R^2 mod n in little-endian 32-bit words, R = 2^(numwords*32)
    """
    # Use the cryptography library for RSA public number extraction
    from cryptography import x509 as x509_mod
    from cryptography.hazmat.backends import default_backend

    cert_path = output_dir / "ota_signing.x509.pem"
    cert_pem_bytes = cert_path.read_bytes()

    # Parse the certificate and extract RSA public key numbers
    cert = x509_mod.load_pem_x509_certificate(cert_pem_bytes, default_backend())
    pub_numbers = cert.public_key().public_numbers()
    n = pub_numbers.n
    e = pub_numbers.e

    # RSA-2048: 256 bytes = 64 32-bit words
    num_words = 256 // 4  # 64

    # n0inv: Montgomery inverse
    # n0inv = 2^32 - (n^-1 mod 2^32)
    n0inv = (2**32) - pow(n, -1, 2**32)

    # Modulus as little-endian 32-bit words
    n_words = []
    tmp = n
    for _ in range(num_words):
        n_words.append(tmp & 0xFFFFFFFF)
        tmp >>= 32

    # R^2 mod n, where R = 2^(num_words * 32)
    R = 1 << (num_words * 32)
    rr = pow(R, 2, n)
    rr_words = []
    tmp = rr
    for _ in range(num_words):
        rr_words.append(tmp & 0xFFFFFFFF)
        tmp >>= 32

    # Assemble the mincrypt v2 line:
    #   v2 {numwords} {n0inv} {n_words...} {e} {rr_words...}
    parts = ["v2", str(num_words), str(n0inv)]
    parts.extend(str(w) for w in n_words)
    parts.append(str(e))
    parts.extend(str(w) for w in rr_words)
    recovery_keys_content = " ".join(parts) + "\n"

    keys_path = output_dir / "recovery_res_keys"
    keys_path.write_text(recovery_keys_content)
    print(f"  {keys_path.name}")


def keys_exist(output_dir: Path) -> bool:
    """Check if the primary key files already exist."""
    required = [
        "platform.key",
        "platform.pk8",
        "platform.x509.pem",
        "platform.p12",
        "ota_signing.key",
        "ota_signing.pk8",
        "ota_signing.x509.pem",
        "otacerts.zip",
        "recovery_res_keys",
    ]
    return all((output_dir / f).exists() for f in required)


def main() -> None:
    project_root = find_project_root()
    default_output = project_root / "iFit" / "firmware" / "keys"

    parser = argparse.ArgumentParser(
        description="Generate all signing keys for the NordicTrack S22i OTA firmware project.",
    )
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=default_output,
        help=f"Output directory for generated keys (default: {default_output})",
    )
    parser.add_argument(
        "--force",
        action="store_true",
        help="Overwrite existing keys if they already exist",
    )
    args = parser.parse_args()

    output_dir: Path = args.output_dir.resolve()

    # Idempotency: skip if all keys already exist (unless --force)
    if not args.force and keys_exist(output_dir):
        print(f"All keys already exist in {output_dir}")
        print("Use --force to regenerate.")
        return

    # Ensure output directory exists
    output_dir.mkdir(parents=True, exist_ok=True)

    # --- Platform key ---
    print(f"\nGenerating platform key pair in {output_dir}/")
    generate_key_pair(
        output_dir,
        name="platform",
        subject="/CN=Hyperborea Platform/O=Nettarion",
    )
    generate_platform_p12(output_dir)

    # --- OTA signing key ---
    print(f"\nGenerating OTA signing key pair in {output_dir}/")
    generate_key_pair(
        output_dir,
        name="ota_signing",
        subject="/CN=Hyperborea OTA/O=Nettarion",
    )

    # --- Derived: otacerts.zip ---
    print(f"\nGenerating derived artifacts in {output_dir}/")
    generate_otacerts_zip(output_dir)

    # --- Derived: recovery_res_keys (mincrypt v2) ---
    generate_recovery_res_keys(output_dir)

    print("\nDone. All keys generated successfully.")


if __name__ == "__main__":
    main()
