#!/usr/bin/env python3
"""
Sign an OTA zip with a minimal PKCS#7 signature (no signed attributes).

Uses OpenSSL to produce the exact format AOSP recovery expects: a DER-encoded
CMS signature appended to the zip comment field with a 6-byte footer that
recovery uses to locate the signature block.

Usage: sign_ota.py <cert.pem> <key.pk8> <input.zip> <output.zip>
"""

import os
import struct
import subprocess
import sys
import tempfile

EOCD_MARKER = b"\x50\x4b\x05\x06"
EOCD_HEADER_SIZE = 22
MAX_ZIP_COMMENT = 0xFFFF


def find_eocd(data):
    """Find the End-of-Central-Directory record.

    Scans backwards from EOF for the EOCD signature and validates that the
    stored comment length places the comment exactly at EOF.
    """
    if len(data) < EOCD_HEADER_SIZE:
        raise ValueError("File too small to contain EOCD")

    search_start = max(0, len(data) - (EOCD_HEADER_SIZE + MAX_ZIP_COMMENT))
    for pos in range(len(data) - EOCD_HEADER_SIZE, search_start - 1, -1):
        if data[pos : pos + 4] != EOCD_MARKER:
            continue
        comment_len = struct.unpack("<H", data[pos + 20 : pos + 22])[0]
        if pos + EOCD_HEADER_SIZE + comment_len == len(data):
            return pos, comment_len

    raise ValueError("No valid EOCD found")


def sign_ota(cert_file, key_file, input_zip, output_zip):
    with open(input_zip, "rb") as f:
        ota_data = f.read()

    print(f"Read OTA package ({len(ota_data)} bytes)")

    eocd_pos, existing_comment_len = find_eocd(ota_data)

    # AOSP signs everything up to (but not including) the 2-byte comment-length
    # field at the end of the EOCD header.
    data_to_sign = ota_data[: len(ota_data) - existing_comment_len - 2]
    print(f"Data to sign: {len(data_to_sign)} bytes")

    with tempfile.NamedTemporaryFile(delete=False, suffix=".bin") as tmp:
        tmp.write(data_to_sign)
        tmp_path = tmp.name

    try:
        cmd = [
            "openssl", "cms", "-sign",
            "-binary", "-noattr",
            "-md", "sha1",
            "-outform", "DER",
            "-signer", cert_file,
            "-inkey", key_file,
            "-keyform", "DER",
            "-in", tmp_path,
        ]

        result = subprocess.run(cmd, capture_output=True, check=True)
        signature_block = result.stdout
        print(f"Created PKCS#7 signature ({len(signature_block)} bytes)")
    finally:
        os.unlink(tmp_path)

    # Build the zip comment: [signature_block][footer]
    # Footer layout (6 bytes, little-endian):
    #   u16  signature_start  — offset from end-of-file to start of signature
    #   u16  0xFFFF           — magic marker
    #   u16  comment_size     — total comment length (signature + footer)
    sig_len = len(signature_block)
    comment_size = sig_len + 6
    signature_start = comment_size

    footer = struct.pack("<H", signature_start)
    footer += b"\xff\xff"
    footer += struct.pack("<H", comment_size)

    comment = signature_block + footer

    # Patch the EOCD comment-length field to match.
    eocd = bytearray(ota_data[eocd_pos : eocd_pos + EOCD_HEADER_SIZE])
    eocd[20:22] = struct.pack("<H", len(comment))

    with open(output_zip, "wb") as f:
        f.write(ota_data[:eocd_pos])
        f.write(eocd)
        f.write(comment)

    print(f"Signed OTA written to {output_zip}")


if __name__ == "__main__":
    if len(sys.argv) != 5:
        print("Usage: sign_ota.py <cert.pem> <key.pk8> <input.zip> <output.zip>")
        sys.exit(1)

    try:
        sign_ota(sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4])
    except subprocess.CalledProcessError as e:
        print(f"OpenSSL error: {e.stderr.decode()}")
        sys.exit(1)
    except Exception as e:
        print(f"Error: {e}")
        sys.exit(1)
