#!/usr/bin/env python3
"""
AOSP Recovery OTA Verifier.

Replicates the footer / EOCD / signed-length logic used by AOSP recovery to
locate the PKCS#7 signature inside a zip comment, then verifies it against
the provided trusted certificate.

Supports RSA and EC keys with SHA-1 or SHA-256 digests.

Usage: verify_ota.py <ota.zip> <cert.pem>
"""

import hashlib
import sys

from cryptography import x509
from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.backends import default_backend
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import ec, padding, rsa, utils

FOOTER_SIZE = 6
EOCD_HEADER_SIZE = 22

OID_SHA1 = "1.3.14.3.2.26"
OID_SHA256 = "2.16.840.1.101.3.4.2.1"


# ---------------------------------------------------------------------------
# Minimal DER parser — just enough to walk a PKCS#7 SignedData structure.
# ---------------------------------------------------------------------------

def _read_der_length(data, idx):
    """Read a DER length field starting at *idx*, return (length, next_idx)."""
    if idx >= len(data):
        raise ValueError("Invalid DER length index")
    first = data[idx]
    idx += 1
    if first < 0x80:
        return first, idx
    num_bytes = first & 0x7F
    if num_bytes == 0 or num_bytes > 4:
        raise ValueError("Unsupported DER length encoding")
    if idx + num_bytes > len(data):
        raise ValueError("Truncated DER length")
    length = int.from_bytes(data[idx : idx + num_bytes], "big")
    return length, idx + num_bytes


def _read_tlv(data, idx):
    """Read one TLV at *idx*, return (tag, value_start, value_end, next_idx)."""
    if idx >= len(data):
        raise ValueError("TLV out of bounds")
    tag = data[idx]
    idx += 1
    length, idx = _read_der_length(data, idx)
    end = idx + length
    if end > len(data):
        raise ValueError("Truncated TLV value")
    return tag, idx, end, end


def _iter_tlvs(data, start, end):
    """Yield (tag, value_start, value_end) for consecutive TLVs in a range."""
    pos = start
    while pos < end:
        tag, value_start, value_end, next_pos = _read_tlv(data, pos)
        yield tag, value_start, value_end
        pos = next_pos
    if pos != end:
        raise ValueError("Invalid DER structure length")


def _decode_oid(oid_bytes):
    """Decode a DER-encoded OID value to its dotted-decimal string."""
    if not oid_bytes:
        raise ValueError("Empty OID")
    first = oid_bytes[0]
    nodes = [first // 40, first % 40]
    value = 0
    for byte in oid_bytes[1:]:
        value = (value << 7) | (byte & 0x7F)
        if not (byte & 0x80):
            nodes.append(value)
            value = 0
    if value != 0:
        raise ValueError("Malformed OID")
    return ".".join(str(n) for n in nodes)


def _parse_pkcs7_signature(pkcs7_der):
    """Extract the raw signature bytes and digest OID from a PKCS#7 blob.

    Returns (signature_bytes, digest_oid_string).
    """
    # ContentInfo SEQUENCE
    tag, vstart, vend, _ = _read_tlv(pkcs7_der, 0)
    if tag != 0x30:
        raise ValueError("PKCS#7 ContentInfo is not a SEQUENCE")

    top_items = list(_iter_tlvs(pkcs7_der, vstart, vend))
    if len(top_items) < 2 or top_items[0][0] != 0x06 or top_items[1][0] != 0xA0:
        raise ValueError("Invalid PKCS#7 ContentInfo")

    # [0] EXPLICIT wrapper around SignedData
    _, cvstart, cvend = top_items[1]
    inner_items = list(_iter_tlvs(pkcs7_der, cvstart, cvend))
    if len(inner_items) != 1 or inner_items[0][0] != 0x30:
        raise ValueError("Invalid SignedData wrapper")

    # SignedData SEQUENCE
    _, sd_start, sd_end = inner_items[0]
    sd_items = list(_iter_tlvs(pkcs7_der, sd_start, sd_end))
    if len(sd_items) < 5:
        raise ValueError("SignedData too short")

    # Find the signerInfos SET (tag 0x31).
    signer_infos = None
    for tag, start, end in sd_items:
        if tag == 0x31:
            signer_infos = (start, end)
    if signer_infos is None:
        raise ValueError("No signerInfos SET found")

    # First (and only) SignerInfo SEQUENCE inside the SET.
    si_start, si_end = signer_infos
    signer_info_items = list(_iter_tlvs(pkcs7_der, si_start, si_end))
    if not signer_info_items or signer_info_items[0][0] != 0x30:
        raise ValueError("No SignerInfo SEQUENCE found")

    _, info_start, info_end = signer_info_items[0]
    info_items = list(_iter_tlvs(pkcs7_der, info_start, info_end))
    if len(info_items) < 5:
        raise ValueError("SignerInfo too short")

    # Fields: version, issuerAndSerialNumber, digestAlgorithm,
    #         [authenticatedAttributes], digestEncryptionAlgorithm, signature
    digest_idx = 2
    sig_alg_idx = 3
    sig_val_idx = 4

    # Skip optional authenticatedAttributes [0] IMPLICIT if present.
    if info_items[sig_alg_idx][0] == 0xA0:
        sig_alg_idx += 1
        sig_val_idx += 1
    if sig_val_idx >= len(info_items):
        raise ValueError("Missing signature value")

    # Digest algorithm OID
    digest_alg_tag, digest_alg_start, digest_alg_end = info_items[digest_idx]
    if digest_alg_tag != 0x30:
        raise ValueError("DigestAlgorithm is not a SEQUENCE")
    digest_alg_items = list(_iter_tlvs(pkcs7_der, digest_alg_start, digest_alg_end))
    if not digest_alg_items or digest_alg_items[0][0] != 0x06:
        raise ValueError("DigestAlgorithm OID missing")
    digest_oid = _decode_oid(pkcs7_der[digest_alg_items[0][1] : digest_alg_items[0][2]])

    # Signature value (OCTET STRING)
    sig_tag, sig_start, sig_end = info_items[sig_val_idx]
    if sig_tag != 0x04:
        raise ValueError("Signature value is not OCTET STRING")
    signature = pkcs7_der[sig_start:sig_end]

    return signature, digest_oid


# ---------------------------------------------------------------------------
# Public entry point
# ---------------------------------------------------------------------------

def verify_ota(ota_file, cert_file):
    """Verify *ota_file* against the certificate in *cert_file*.

    Returns (True, None) on success or (False, reason_string) on failure.
    """
    with open(cert_file, "rb") as f:
        cert = x509.load_pem_x509_certificate(f.read(), default_backend())
        public_key = cert.public_key()

    with open(ota_file, "rb") as f:
        data = f.read()

    length = len(data)
    print(f"File length: {length} bytes")

    if length < FOOTER_SIZE:
        return False, "not big enough to contain footer"

    # ---- Parse the 6-byte footer at EOF ----
    footer = data[length - FOOTER_SIZE :]
    if footer[2] != 0xFF or footer[3] != 0xFF:
        return False, "footer is wrong"

    comment_size = footer[4] + (footer[5] << 8)
    signature_start = footer[0] + (footer[1] << 8)

    if signature_start > comment_size:
        return False, f"signature start ({signature_start}) > comment size ({comment_size})"
    if signature_start <= FOOTER_SIZE:
        return False, "signature start is in the footer"

    # ---- Locate the EOCD ----
    eocd_size = comment_size + EOCD_HEADER_SIZE
    if length < eocd_size:
        return False, "not big enough to contain EOCD"

    eocd_pos = length - eocd_size
    eocd = data[eocd_pos : eocd_pos + eocd_size]

    if eocd[0:4] != b"\x50\x4b\x05\x06":
        return False, "signature length doesn't match EOCD marker"

    # Make sure EOCD marker does not appear again inside the comment.
    for i in range(4, eocd_size - 3):
        if eocd[i] == 0x50 and eocd[i + 1] == 0x4B and eocd[i + 2] == 0x05 and eocd[i + 3] == 0x06:
            return False, "EOCD marker occurs after start of EOCD"

    # ---- Extract the signature block ----
    signed_len = length - eocd_size + EOCD_HEADER_SIZE - 2

    signature_offset = length - signature_start
    signature_size = signature_start - FOOTER_SIZE
    pkcs7_signature = data[signature_offset : signature_offset + signature_size]

    try:
        raw_signature, digest_oid = _parse_pkcs7_signature(pkcs7_signature)
    except ValueError as e:
        return False, f"Could not parse PKCS#7 signer info: {e}"

    # ---- Hash the signed range ----
    if digest_oid == OID_SHA1:
        hash_name = "sha1"
        hash_alg = hashes.SHA1()
    elif digest_oid == OID_SHA256:
        hash_name = "sha256"
        hash_alg = hashes.SHA256()
    else:
        return False, f"Unsupported digest OID in PKCS#7: {digest_oid}"

    digest = hashlib.new(hash_name, data[:signed_len]).digest()

    # ---- Verify ----
    try:
        if isinstance(public_key, rsa.RSAPublicKey):
            public_key.verify(
                raw_signature,
                digest,
                padding.PKCS1v15(),
                utils.Prehashed(hash_alg),
            )
        elif isinstance(public_key, ec.EllipticCurvePublicKey):
            public_key.verify(
                raw_signature,
                digest,
                ec.ECDSA(utils.Prehashed(hash_alg)),
            )
        else:
            return False, f"Unsupported public key type: {type(public_key).__name__}"
    except InvalidSignature:
        return False, "Signature verification failed"

    print("SIGNATURE VERIFIED")
    return True, None


if __name__ == "__main__":
    if len(sys.argv) != 3:
        print("Usage: verify_ota.py <ota.zip> <cert.pem>")
        sys.exit(1)

    success, error = verify_ota(sys.argv[1], sys.argv[2])
    if success:
        sys.exit(0)
    print(f"VERIFICATION FAILED: {error}")
    sys.exit(1)
