# Inter-Process Communication (IPC)

> **Communication flows between iFit apps (Standalone ↔ ERU)**

**Status:** 📋 To Be Documented
**Last Updated:** 2026-02-10

## Overview

iFit Standalone (user-level app) communicates with ERU (system-level service) via Android IPC mechanisms:
- **Intents** - Command messages from Standalone to ERU
- **Broadcasts** - Status updates from ERU to Standalone
- **Shared Preferences** - Possibly used for configuration

This IPC boundary is **security-critical**: any vulnerability allows privilege escalation from user app to system.

## Communication Patterns

### Standalone → ERU (Commands)

**Mechanism:** Explicit intents or broadcasts

**Purpose:** Request privileged operations
- Start workout (trigger motor/resistance control)
- Adjust hardware settings
- Request system information
- Install updates

**Analysis Needed:** Search Standalone code for:
```java
Intent intent = new Intent("com.ifit.eru.ACTION");
intent.setPackage("com.ifit.eru");
sendBroadcast(intent);
```

### ERU → Standalone (Status)

**Mechanism:** Broadcast intents

**Purpose:** Notify app of status changes
- Hardware status updates
- Sensor readings
- Error conditions
- Workout progress

**Analysis Needed:** Search ERU code for:
```java
Intent broadcast = new Intent("com.ifit.standalone.STATUS_UPDATE");
sendBroadcast(broadcast);
```

## Intent Actions (To Document)

| Intent Action | Direction | Purpose | Risk |
|---------------|-----------|---------|------|
| TBD | S→E | TBD | TBD |

## Security Analysis

**Key Questions:**
1. Is there authentication between apps?
2. Can any app send intents to ERU?
3. Are intent extras validated?
4. Can malicious intents trigger privileged operations?

**Related:** [../security/PRIVILEGE_ESCALATION.md](../security/PRIVILEGE_ESCALATION.md)

---

**TODO:** Analyze Standalone and ERU source for IPC patterns
