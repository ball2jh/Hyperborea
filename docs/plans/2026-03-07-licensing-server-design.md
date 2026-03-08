# Hyperborea Licensing & Update Server Design

## Overview

A web server that handles user accounts, Stripe subscriptions, device linking, license enforcement, and APK update distribution for the Hyperborea bike-to-Zwift bridge app.

**Stack:** TanStack Start (TypeScript), Vercel, Stripe, Cloudflare R2 (APK hosting), database TBD (SQLite, Vercel Postgres, or Convex).

**Repo:** Separate repository (`hyperborea-server`), not in the Android app repo.

## Identity & Device Linking

Users pay on the website; the bike console has no user identity. Linking connects the two.

### Flow

1. User creates account on hyperborea.dev (email + password)
2. User subscribes via Stripe Checkout
3. User clicks "Link Device" on website dashboard — site shows a QR code and a 6-digit fallback code (both expire in 10 minutes)
4. On the bike, user opens Hyperborea → activation screen → scans QR with phone (which opens a linking URL) or types the 6-digit code on the bike
5. Server validates → links device UUID to the user's account → returns an auth token
6. Device is authorized — app starts normally

### Device Identity

The app generates a random UUID on first launch (stored in SharedPreferences). This is the device ID sent with every API call. Not tied to hardware serials.

### Limits

One active device per subscription. User can unlink from the website dashboard and link a new device.

## Auth Protocol & Bypass Protection

### Threat Model

The app runs as a system app on a rooted Android 7.1.2 device the user has physical access to. Any client-side check can be patched out. The server is the only authority.

### License Check

1. **Device auth token** — returned on successful linking, stored on device, included in every API call
2. **Periodic check** — on every app launch and every ~4 hours, the app calls `GET /api/device/status`. Server returns `{ active, expiresAt }`.
3. **Hard block** — if `active: false`, app locks immediately with a "subscription required" screen
4. **Offline tolerance** — response includes `expiresAt` (the subscription's paid-through date). App caches this and allows operation until that time. Not a grace period — it's the actual paid-through date.

### Anti-Tamper (Defense in Depth)

- **Signed server responses** — server signs status response with Ed25519. App verifies with embedded public key.
- **Certificate pinning** — pin server's TLS cert to prevent MITM replay
- **APK signature verification** — existing `SignatureVerifier` ensures the APK hasn't been re-signed
- **R8 obfuscation** — raises the effort bar (not a real defense, but free)

### What We Don't Do

- No root detection (the device is rooted by us)
- No complex client-side crypto
- No telemetry beyond the license check

## Data Model

Database-agnostic schema (final storage layer TBD):

```
users
  id              UUID (PK)
  email           TEXT (unique)
  password_hash   TEXT
  created_at      TIMESTAMP

subscriptions
  id                       UUID (PK)
  user_id                  UUID (FK -> users)
  stripe_customer_id       TEXT (unique)
  stripe_subscription_id   TEXT (unique)
  status                   ENUM (active, past_due, cancelled, expired)
  current_period_end       TIMESTAMP
  created_at               TIMESTAMP

devices
  id            UUID (PK)
  user_id       UUID (FK -> users)
  device_uuid   TEXT (unique)
  auth_token    TEXT (unique)
  name          TEXT
  linked_at     TIMESTAMP

linking_codes
  id          UUID (PK)
  user_id     UUID (FK -> users)
  code        TEXT (6-digit numeric)
  qr_token    TEXT (longer token for QR URL)
  expires_at  TIMESTAMP
  used        BOOLEAN
```

## API Surface

### Website (server-rendered)

```
GET  /                     -- landing page
GET  /login                -- login form
GET  /register             -- registration form
GET  /dashboard            -- account management, device list, subscription status
POST /dashboard/link       -- generate linking code + QR token
```

### Stripe

```
POST /api/stripe/checkout  -- create Stripe Checkout session
POST /api/stripe/webhook   -- Stripe webhook receiver (source of truth for subscription status)
```

### Device API

```
POST /api/device/link      -- { deviceUuid, code } or { deviceUuid, qrToken } -> { authToken }
GET  /api/device/status    -- Authorization: Bearer <authToken> -> { active, expiresAt, signature }
GET  /api/device/manifest  -- Authorization: Bearer <authToken> -> update manifest JSON
```

Status responses are signed with Ed25519 server key. App verifies with embedded public key.

### Admin

```
GET  /admin                -- admin dashboard
POST /admin/releases       -- upload APK + metadata (stored in R2)
PUT  /admin/releases/:id   -- edit release (mark current, update notes)
GET  /admin/subscribers    -- list users + subscription status
```

## APK Hosting & Updates

APK binaries stored in Cloudflare R2 (S3-compatible, free egress). Server stores metadata and returns download URLs via the manifest endpoint.

The manifest format matches the existing `UpdateManifest` parser in the Android app:

```json
{
  "app": {
    "versionCode": 2,
    "versionName": "1.1",
    "url": "https://r2-url/hyperborea-1.1.apk",
    "sha256": "...",
    "releaseNotes": "..."
  }
}
```

No changes to the Android app's `UpdateManager` — it already fetches, verifies SHA-256, and installs via `PackageInstaller`.

## App-Side Changes

### New Startup Gate

Before the existing ecosystem -> hardware -> broadcast lifecycle in the Orchestrator, a license check step. If unlicensed, the UI shows activation/locked screen instead of the dashboard.

### New Types in `:core`

```kotlin
sealed interface LicenseState {
    data class Licensed(val expiresAt: Instant) : LicenseState
    data object Unlicensed : LicenseState
    data object Checking : LicenseState
}
```

### New Components

- **Activation screen** — Compose screen with 6-digit code input and QR instructions
- **LicenseChecker** — interface in `:core`, implementation in `:app`. Calls `/api/device/status`, verifies Ed25519 signature, caches `expiresAt`, exposes `StateFlow<LicenseState>`
- **Certificate pinning** — added to `HttpUrlConnectionClient`
- **Auth token storage** — SharedPreferences (no AndroidX Security on API 25)

### What Doesn't Change

The entire broadcast/hardware/core stack is untouched. The license check wraps around the existing orchestrator lifecycle.
