# iFit API Reference

> Reverse-engineered from decompiled ERU v1.2.1.145 and Standalone v2.6.88.4692 (Xamarin C# DLLs)

## Environments

| Environment | API Endpoint | Gateway | Juno |
|-------------|-------------|---------|------|
| **Production** | `https://api.ifit.com` | `https://gateway.ifit.com/` | `https://juno.svc.ifit.com/` |
| **Test** | `https://api.ifit-test.com` | `https://gateway.ifit-test.com/` | `https://juno.svc.ifit-test.com/` |
| **Dev** | `https://api.ifit-test.com` | `https://gateway.ifit-dev.com/` | `https://juno.svc.ifit-dev.com/` |

**CDN**: `https://ifit-wolf.s3-cdn.ifit.com/android/builds/public/`

Source: `Shire.Core/iFit.Api.Environments/IfitProdEnvironment.cs`, `IfitTestEnvironment.cs`, `IfitDevEnvironment.cs`

## Authentication

### Credentials

ERU v1.2.1.145 uses hardcoded plaintext credentials (`EruIfitApiEnvironment.cs`).
Newer versions (v2.6.88+) use obfuscated `string[]` arrays decoded via `TransformString()`.

Credentials are stored in `credentials/ifit_api.json` (gitignored).

### Auth Modes

The `AuthenticatedHttpClientHandler` supports three modes per endpoint:

| Mode | Header | When Used |
|------|--------|-----------|
| **Basic** | `Authorization: Basic base64(clientId:clientSecret)` | Anonymous/device-level calls |
| **Bearer** | `Authorization: Bearer <access_token>` | User-authenticated calls |
| **Both** | Bearer if logged in, Basic fallback | Endpoints that work either way |

### OAuth2 Flows

#### Password Login
```
POST https://api.ifit.com/oauth/token
Content-Type: application/json

{
    "grant_type": "password",
    "client_id": "<client_id>",
    "client_secret": "<client_secret>",
    "username": "<email>",
    "password": "<password>"
}

Response:
{
    "access_token": "...",
    "refresh_token": "...",
    "token_type": "bearer",
    "expires_in": 3600
}
```

#### Token Refresh
```
POST https://api.ifit.com/oauth/token

{
    "grant_type": "refresh_token",
    "client_id": "<client_id>",
    "client_secret": "<client_secret>",
    "refresh_token": "<refresh_token>"
}
```

If the access token is a JWT (matches `^[^. ]+\.[^.]+\.[^.]+$`), refresh goes through Gateway instead: `POST /cockatoo/v2/login/refresh` with Basic auth.

#### Third-Party Provider (Facebook/Google)
```
POST https://api.ifit.com/oauth/token

{
    "grant_type": "external",
    "client_id": "<client_id>",
    "client_secret": "<client_secret>",
    "provider": "facebook|google",
    "provider_token": "<provider_access_token>"
}
```

#### ERU Default Identity

ERU authenticates as `eru@ifit.com`. Anonymous operations use `anon@ifit.com`.

Source: `Eru.Android/EruIfitApiEnvironment.cs`, `Shire.Core/iFit.Api/IfitApiService.cs`

## Update Endpoints

### Firmware Update Check

```
GET https://api.ifit.com/v1/android_firmware_update/{Build.VERSION.Incremental}
Authorization: Basic <base64(clientId:clientSecret)>

Response (AndroidFirmwareUpdate):
{
    "name": "...",
    "version": "20190521",
    "update_version": "MGA1_20210616",
    "url": "https://ifit-wolf.s3-cdn.ifit.com/android/builds/public/android-updates/20190521_MGA1_20210616.zip"
}
```

For our device, `Build.VERSION.Incremental` = `20190521`.

Source: `Shire.Core/iFit.Api.DataObjects/AndroidFirmwareUpdate.cs`

### App Update Check

```
GET https://api.ifit.com/v1/console-apps-updates/{software_number}
Authorization: Basic <base64(clientId:clientSecret)>

Response (ConsoleAppsUpdates):
{
    "id": "...",
    "info": {
        "apps": {
            "updates": [
                {
                    "name": "iFit Admin",
                    "fqn": "com.ifit.eru",
                    "url": "https://ifit-wolf.s3-cdn.ifit.com/android/builds/public/app-updates/com.ifit.eru-2.1.1.1227.apk",
                    "version": "2.1.1.1227"
                }
            ]
        }
    },
    "software_numbers": [392570]
}
```

For our device, `software_number` = `392570`.

Source: `Shire.Core/iFit.Api.DataObjects/ConsoleAppsUpdates.cs`

### Brainboard Update Check

```
GET https://api.ifit.com/v1/brainboard-updates/{software_number}
Authorization: Basic <base64(clientId:clientSecret)>

Response (BrainboardUpdate):
{
    "id": "...",
    "name": "...",
    "url": "https://...",
    "version": "83.245",
    "software_number": 392570,
    "software_numbers": [392570]
}
```

### Workout Update Check

```
GET https://api.ifit.com/v1/consoles/{software_number}/workouts
Authorization: Basic <base64(clientId:clientSecret)>

Response (ConsoleWorkouts):
{
    "version": 93,
    "url": "https://...",
    "groups": [
        { "name": "...", "workout_ids": ["id1", "id2"] }
    ]
}
```

### Gateway Endpoints (Newer ERU)

Base: `https://gateway.ifit.com/`

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| GET | `/wolf-updates/android/{id}` | Bearer | Specific Android update |
| GET | `/wolf-updates/android` | Bearer | List Android updates |
| GET | `/wolf-updates/brainboard/{id}` | Bearer | Specific brainboard update |
| GET | `/wolf-updates/app/{id}` | Bearer | Specific app update |
| GET | `/wolf-updates/user/{userKey}/apps` | Bearer | App updates for user |
| GET | `/wolf-updates/user/{userKey}/android/{baseVersion}` | Bearer | Android update for user |
| GET | `/wolf-updates/user/{userKey}/brainboard/{softwareNumber}` | Bearer | Brainboard update for user |

Source: `Shire.Core/iFit.Api.Gateway/IIfitWolfUpdatesApi.cs`

## Device Identification

### Key Identifiers

| Identifier | Value (Our Device) | Used For |
|------------|-------------------|----------|
| **Software Number** | `392570` | App/brainboard update lookups |
| **Build.VERSION.Incremental** | `20190521` | Firmware update lookup |
| **Console GUID** | Read from `/sdcard/.ConsoleGuid` | Device tracking |
| **Android Device ID** | `CrossDeviceInfo.Current.Id` | Analytics anonymous ID |
| **Brainboard Info** | `{"MasterLibraryVersion":83,"MasterLibraryBuild":245,"PartNumber":392570}` | Hardware identification |

### Analytics Properties (Segment)

Sent with every analytics event:
```json
{
    "isBuiltIn": true,
    "softwareNumber": 392570,
    "appVersion": "<eru_version>",
    "partNumber": 392570,
    "masterLibraryVersion": 83,
    "masterLibraryBuild": 245,
    "osVersion": "AOSP on avn_ref 20190521"
}
```

Analytics IDs:
- **Prod Segment**: `EAA1V2rqapWI8Odlaku4KqKGvLvEEZoL`
- **Test Segment**: `JwiglfMVPNvp9phJNkyMg2hIj0pLDvGc`
- **HockeyApp**: `f1f3a8662dd94cd6bbec9d934ecffca9`

## ERU Update Flow

### Automatic Background Updates

1. `PeriodicIdleReceiver` fires every **30 minutes**
2. After **4+ hours idle**, triggers update check
3. Pings `https://api.ifit.com` up to **10 times** (1s delay) to verify connectivity
4. Checks all update types: Apps, Brainboard, Workouts, System

### Install Order

1. ERU (self-update, waits up to 5 min for restart)
2. Launcher
3. Standalone
4. Workouts
5. Brainboard
6. System firmware (triggers reboot into recovery)

### System Firmware Install Path

```
Download to /mnt/sdcard/iFit/system/<filename>.zip
    → Move to /data/update.zip
    → RecoverySystem.InstallPackage(context, new File("/data/", "update.zip"))
    → Framework: verifyPackage() → uncrypt → BCB → reboot recovery
    → Recovery: verify against /res/keys → apply OTA
```

Source: `Eru.Core/Eru.Core.Services.Updates.System/SystemUpdateInstallService.cs`

### Retry/Error Handling

- **API calls**: 3 retries with exponential backoff (2s, 4s, 8s) via Polly
- **Auth failures**: Auto-refresh token on 401/403, up to 3 retries
- **Connectivity**: 10 ping attempts with 1s delays before update check
- **No server-side rate limiting** observed (no 429 handling)

All JSON uses **snake_case** property names. POST/PUT bodies are gzip-compressed by default.

Source: `Shire.Core/iFit.Api.DataObjects/WebDataObject.cs`, `Shire.Core/iFit.Api/AuthenticatedHttpClientHandler.cs`

## USB Update System

### Detection

ERU scans these mount points for a folder named `console_updates_{software_number}`:
- `/storage/usbdisk1/` through `/storage/usbdisk3/`
- `/storage/usbcard0/` through `/storage/usbcard3/`
- All subdirectories of `/mnt/media_rw/`

### USB Folder Structure

```
console_updates_392570/
  apps/
    apps.json                    # Same format as API response
    com.ifit.eru-X.X.X.XXXX.apk
    com.ifit.standalone-X.X.X.XXXX.apk
    com.ifit.launcher-X.X.X.XX.apk
  brainboard/
    brainboard.json
    <firmware_file>
  system/
    system.json
    <ota_zip>
  workouts/
    workouts.json
    <workouts.zip>
```

### Validation

- Folder suffix must match device's software number (`392570`)
- Can be bypassed if `IsUsbUpdateUnrestricted = true` in SharedPrefs
- Manifest `software_numbers` list is also checked

### Flow

1. USB attached → `UsbDeviceAttachedReceiver`
2. `UsbStickService` scans for `console_updates_*` folders
3. Files copied from USB to `/mnt/sdcard/iFit/<type>/`
4. Reboot, then normal install flow applies updates

Source: `Eru.Core/Eru.Core.Util/UpdateFolders.cs`, `Eru.Core/Eru.Core.Services.Updates/UsbTransferService.cs`

## Local Manifest Storage

| Type | Device Path |
|------|-------------|
| Apps | `/mnt/sdcard/iFit/apps/apps.json` |
| Brainboard | `/mnt/sdcard/iFit/brainboard/brainboard.json` |
| System | `/mnt/sdcard/iFit/system/system.json` |
| Workouts | `/mnt/sdcard/iFit/workouts/workouts.json` |
| Temp | `/mnt/sdcard/iFit/tmp/` |

## Example: Checking for Firmware Updates

```bash
# Construct Basic auth header
CREDS=$(cat credentials/ifit_api.json | python3 -c "
import json, sys, base64
d = json.load(sys.stdin)
print(base64.b64encode(f\"{d['client_id']}:{d['client_secret']}\".encode()).decode())
")

# Check for firmware update
curl -s -H "Authorization: Basic $CREDS" \
  "https://api.ifit.com/v1/android_firmware_update/20190521" | python3 -m json.tool

# Check for app updates
curl -s -H "Authorization: Basic $CREDS" \
  "https://api.ifit.com/v1/console-apps-updates/392570" | python3 -m json.tool
```

## Related Documentation

- [OTA_UPDATES.md](../security/OTA_UPDATES.md) — Security analysis of update system
- [OTA_REPACK_GUIDE.md](../firmware/OTA_REPACK_GUIDE.md) — Building custom OTA packages
- [PROTECTION.md](../guides/PROTECTION.md) — Blocking unwanted updates

---

**Source**: Decompiled from ERU v1.2.1.145 (`Eru.Core.dll`, `Eru.Android.dll`) and Standalone v2.6.88.4692 (`Shire.Core.dll`)
**Last Updated**: 2026-02-10
