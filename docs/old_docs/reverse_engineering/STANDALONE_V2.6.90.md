# Standalone v2.6.90 Analysis

> Compared against v2.6.88.4692 (previous version). Analysis date: 2026-02-12.

## Overview

Standalone v2.6.90 is an **incremental update** — same 268 .NET assemblies, same AndroidManifest structure, identical permissions. Changes are primarily within Wolf.Core and Wolf.Android DLLs. No FitPro protocol changes.

**Key additions:**
- AI Coach settings UI (SMS-based coaching with QR code pairing)
- iFIT2 "Valinor" upgrade prompt + ERU broadcast
- HLS adaptive video streaming support
- Subscription tier rebranding
- FitPro v2 `SetSubscribed` API (no-op for v1 devices)

## Assembly Structure

| Component | Files Changed | Nature of Changes |
|-----------|--------------|-------------------|
| **Wolf.Core** | 22 modified, 1 new dir | AI Coach, iFIT2 prompt, HLS video, analytics |
| **Wolf.Android** | ~5 files | iFIT2 broadcast, service registration, version tracking |
| **Sindarin.Core** | 3 files | New `SetSubscribed` interface method |
| **Sindarin.FitPro1.Core** | 1 file | Null-safe logging only |
| **Sindarin.FitPro2.Core** | 1 file | `SetSubscribed` implementation |
| **AndroidManifest** | 1 new activity | `AICoachSettingsView` added |
| All others | 0 | Unchanged |

## New Features

### 1. AI Coach Integration

**New directory:** `Wolf.Core.ViewModels.Settings.AICoachSettings/`

| File | Purpose |
|------|---------|
| `AICoachSettingsViewModel.cs` | Enable/disable AI Coach via SMS channel API |
| `TurnOnAICoachViewModel.cs` | QR code display for pairing |

**How it works:**
- Polls `apiService.SmsApi.GetSmsChannelStatus()` every 15 seconds
- Checks if `SmsChannel.AICoach` is `Active`
- Enable: shows QR code from `FeatureFlags.AICoachQrCodeUrl`
- Disable: calls `apiService.SmsApi.DisableChannel()`
- Feature-gated: `FeatureFlags.AICoachSettingsEnabled`

**Manifest:** New activity `crc64bba8e2bd0315e5ab.AICoachSettingsView`

**New resource strings:**
```
settings_ai_coach, settings_ai_coach_title, settings_ai_coach_title_beta
settings_ai_coach_header, settings_ai_coach_header_body
settings_ai_coach_body_accountability_{header,body}
settings_ai_coach_body_consistency_{header,body}
settings_ai_coach_body_goals_{header,body}
settings_ai_coach_button_enable, settings_ai_coach_button_disable
```

### 2. iFIT2 "Valinor" Update Prompt

**New file:** `Wolf.Android/UpdateToiFIT2Broadcast.cs`
- Sends broadcast: `com.ifit.eru.VALINOR_OPT_IN_FROM_WOLF`
- Targets ERU to initiate iFIT2 platform migration

**UI Integration:**
- "Update to iFIT2" button added to navigation drawer
- Gated by `FeatureFlags.ValinorEnabled`
- `NavigationContentViewModel.ShowUpdateiFIT2` property
- `OnUpdateToiFIT2()` → `sendBroadcastService.UpdateToiFIT2()` + analytics

**Architecture change:** `ISendBroadcastService` now registered for ALL device types (was built-in only in v2.6.88). This enables the iFIT2 update feature on standalone devices.

**IPC pattern:** Same as existing Wolf → ERU communication via Android broadcasts.

### 3. HLS Adaptive Video Streaming

**Changed files:**
- `Wolf.Core.Services/WorkoutSettings.cs` — VideoQuality getter
- `Wolf.Core.ViewModels.Settings/BaseEquipmentSettingsViewModel.cs` — quality picker

**How it works:**
- When `FeatureFlags.VideoHlsEnabled` is true:
  - "Auto" quality option hidden from picker (HLS handles adaptive bitrate)
  - If quality set to Auto, reads default from `FeatureFlags.VideoPlaybackQualityDefault`
  - Uses `BandwidthVideoQualityMapping` to convert string → VideoQuality enum
- Available quality options: 1080p, 720p, 540p, 360p, 270p (minus Auto when HLS)

### 4. Subscription Rebranding

**File:** `Wolf.Core/SettingsExtensions.cs`

| Tier | v2.6.88 | v2.6.90 |
|------|---------|---------|
| CoachPlus | `ifit_family` | `ifit_pro` |
| Premium (with Glass) | `ifit_individual` | `ifit_individual` (unchanged) |
| Premium (no Glass) | `ifit_individual` | `ifit_train` (NEW tier) |

"Glass access" likely refers to NordicTrack Vue or similar smart mirror devices.

## Protocol Changes

### Sindarin.FitPro1.Core (QueueManager.cs)

**Single change — null-safe logging:**
```csharp
// v2.6.88
fitProBytesLogger.LogBytes(request, PacketType.Request);
fitProBytesLogger.LogBytes(response, PacketType.Response);

// v2.6.90
fitProBytesLogger?.LogBytes(request, PacketType.Request);
fitProBytesLogger?.LogBytes(response, PacketType.Response);
```

**Impact on our native client: NONE.** Pure defensive logging fix.

### Sindarin.Core — New SetSubscribed API

**New interface method** in `IFitnessConsole.cs`:
```csharp
Task SetSubscribed(bool subscribed, FitnessValue fitnessValue);
```

**Base implementation** in `FitnessConsoleBase.cs`:
```csharp
public virtual Task SetSubscribed(bool subscribed, FitnessValue fitnessValue)
{
    return Task.CompletedTask;  // No-op for FitPro v1
}
```

**FitPro v2 implementation** in `FitPro2Console.cs`:
```csharp
public override async Task SetSubscribed(bool subscribe, FitnessValue type)
{
    FeatureId? featureId = type.ToFeatureId();
    if (featureId.HasValue)
    {
        if (subscribe)
            await SubscribeIfNeeded(featureId.Value);
        else
            await Unsubscribe(featureId.Value);
    }
}
```

**Impact on our native client: NONE.** FitPro v1 uses polling, not subscriptions. The base implementation is a no-op. This only affects FitPro v2 devices.

### IdleModeLockout

**No changes.** Our implementation remains correct:
- Write field 95=false before Idle→Running
- Write field 95=true after workout completion

## Analytics Changes

| Change | Detail |
|--------|--------|
| **NEW** | `OnUpdateToiFIT2Clicked` — tracks iFIT2 upgrade funnel |
| **REMOVED** | `OnStrengthMirrorDemoModeInitialized` — simplified demo tracking |
| **GATED** | `UnexpectedShutdown` — now behind `FeatureFlags.UnexpectedShutdown` |
| **RENAMED** | `MemoryStatusAlertEnabled` → `FeatureFlags.MemoryStatusAnalytics` |

## Other Changes

### FitPro Byte Logging
- `FitProBytesLogEnabledByFeatureFlag.Enabled` changed from stored field to computed property
- Now checks feature flag dynamically on every access (no stale state)

### Video Debug Overlay Fix
- `InWorkoutStreamingVideoViewModel.ShouldShowDebugOverlay` was hardcoded to `false`
- Now correctly reads from `developerSettings?.ShowMediaPlayerDebugOverlay`

### Settings Initialization
- Removed async preloading of `MotorTotalDistance` and `TotalTime` from settings constructor
- Simplifies startup, these values loaded on-demand elsewhere

### App Version Registration
- Changed from `async Task RegisterAppVersionAsync()` to synchronous `void RegisterAppVersion()`
- Removed `UpdatedApp()` analytics call on version change
- Faster startup (no await)

## Feature Flags Referenced

| Flag | Purpose |
|------|---------|
| `AICoachSettingsEnabled` | Show AI Coach in settings |
| `AICoachQrCodeUrl` | URL for QR code enrollment |
| `ValinorEnabled` | Show "Update to iFIT2" button |
| `VideoHlsEnabled` | Enable HLS adaptive streaming |
| `VideoPlaybackQualityDefault` | Default quality for HLS |
| `UnexpectedShutdown` | Gate shutdown analytics |
| `MemoryStatusAnalytics` | Gate memory analytics |

## Security & Research Notes

### New IPC Vector
- `com.ifit.eru.VALINOR_OPT_IN_FROM_WOLF` — Wolf → ERU broadcast for platform migration
- If ERU receives this, it likely triggers a major update sequence
- Worth investigating ERU v2.27.18's handling of this broadcast

### AI Coach SMS Channel
- Uses SMS API for coaching feature — implies phone number collection
- QR code enrollment flow suggests external service integration
- Status polling every 15s — observable network traffic

### Valinor = iFIT2 Codename
- Internal name for the new GlassOS-based platform
- Feature flag controls user opt-in to migration
- Confirms the GlassOS apps (gandalf, arda, rivendell, mithlond) are the "iFIT2" platform

### Our Native Client
**Zero changes required.** FitPro v1 protocol is completely unchanged. The `SetSubscribed` API is a no-op for FitPro v1 devices. IdleModeLockout behavior unchanged.

## File Locations

### Decompiled DLLs (ILSpy)
```
ifit_apps/standalone/v2.6.90/decompiled_dll/
├── Wolf.Core/                    # 22 changed files + 1 new dir
├── Wolf.Android/                 # ~5 changed files
├── Sindarin.Core/                # 3 changed files
├── Sindarin.FitPro1.Core/        # 1 changed file
├── Sindarin.FitPro2.Core/        # 1 changed file
├── Sindarin.Ble.Android/         # unchanged
├── Sindarin.Usb.Android/         # unchanged
├── Sindarin.FitPro1.Ble/         # unchanged
├── Sindarin.FitPro1.Tcp/         # unchanged
├── iFit.Video/                   # unchanged
└── iFit.Video.Core/              # unchanged
```

### JADX Decompiled (Java layer)
```
ifit_apps/standalone/v2.6.90/decompiled_jadx/   # 7,085 classes
```

### Key Files for Deep Dive
```
# AI Coach
Wolf.Core/Wolf.Core.ViewModels.Settings.AICoachSettings/AICoachSettingsViewModel.cs
Wolf.Core/Wolf.Core.ViewModels.Settings.AICoachSettings/TurnOnAICoachViewModel.cs

# iFIT2 Valinor
Wolf.Android/Wolf.Android/UpdateToiFIT2Broadcast.cs
Wolf.Android/Wolf.Android.Services.Broadcast/SendBroadcastService.cs

# HLS Video
Wolf.Core/Wolf.Core.Services/WorkoutSettings.cs
Wolf.Core/Wolf.Core.ViewModels.Settings/BaseEquipmentSettingsViewModel.cs

# FitPro v2 Subscription
Sindarin.Core/Sindarin.Core.Console/IFitnessConsole.cs
Sindarin.FitPro2.Core/Sindarin.FitPro2.Core/FitPro2Console.cs
```
