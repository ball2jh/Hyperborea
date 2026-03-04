# OTA Signing Keys

These keys are used to sign modified OTA packages and to configure recovery to trust our signatures.

## Files

| File | Format | Used By |
|------|--------|---------|
| `ota_signing.key` | PEM RSA private key | Reference / backup |
| `ota_signing.pk8` | PKCS#8 DER private key | `tools/sign_ota_minimal_pkcs7.py` |
| `ota_signing.x509.pem` | PEM X.509 certificate | Signing script + verification |
| `otacerts_dummy.zip` | Zip with our cert | Installed to `/system/etc/security/otacerts.zip` |
| `recovery_res_keys` | AOSP mincrypt v2 text | Installed to recovery ramdisk `/res/keys` |

## Key Details

- **Algorithm:** RSA 2048-bit, exponent 65537
- **Subject:** `CN=DummyOTABlocker, O=None`
- **Created:** 2026-02-10
- **Expires:** 2036-02-08 (10 years)

## Regeneration

See `docs/firmware/OTA_REPACK_GUIDE.md` section "Regenerating Keys" for the full procedure.

If regenerated, you must re-sign any OTA and re-patch the recovery partition.
