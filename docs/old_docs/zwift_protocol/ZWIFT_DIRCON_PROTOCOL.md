# Zwift/Wahoo Direct Connect (Dircon) Notes

This document captures the Dircon wire behavior implemented in `ZwiftDirconServer` and cross-checked against observed Zwift/KICKR behavior.

Canonical control-path policy lives in:
- `ZWIFT_KICKR_DIRECT_CONTROL_PATHS.md`

## Transport and discovery

- Transport: TCP server, typically starting at port `36866`.
- Service type: `_wahoo-fitness-tnp._tcp.local`.
- mDNS TXT keys observed:
  - `ble-service-uuids`
  - `mac-address`
  - `serial-number`
- Wahoo-like names are used for compatibility (`Wahoo KICKR XXXX`, `Wahoo TREAD XXXX`).

## Packet framing

- Header length is always `6` bytes:
  - `version` (`1`)
  - `identifier` (message type)
  - `sequence`
  - `response_code`
  - `length_hi`
  - `length_lo`
- Payload length is `length_hi << 8 | length_lo`.

## Message IDs (identifier byte)

- `0x01` Discover Services
- `0x02` Discover Characteristics
- `0x03` Read Characteristic
- `0x04` Write Characteristic
- `0x05` Enable Characteristic Notifications
- `0x06` Unsolicited Characteristic Notification
- `0x07` Unknown packet handled as success (compat behavior)
- `0xFF` Error

## Response codes (response_code byte)

- `0x00` Success
- `0x01` Unknown message type
- `0x02` Unexpected error
- `0x03` Service not found
- `0x04` Characteristic not found
- `0x05` Characteristic operation not supported
- `0x06` Characteristic write failed (reserved/known in ecosystem; not emitted by current server)
- `0x07` Unknown protocol (reserved/known in ecosystem; not emitted by current server)

## UUID encoding

Dircon payloads often carry full 128-bit UUID blobs using base:

`0000uuuu-0000-1000-8000-00805f9b34fb`

`uuuu` (16-bit short UUID) is read from bytes 2-3 of each 16-byte UUID blob.

## Message payload patterns

- Discover Services response: `N * 16-byte UUID blobs`.
- Discover Characteristics request: `16-byte service UUID`.
- Discover Characteristics response: service UUID + repeated `(16-byte characteristic UUID + 1-byte property)`.
- Read request: characteristic UUID.
- Read response: characteristic UUID + value bytes.
- Write request: characteristic UUID + write bytes.
- Notification enable request: characteristic UUID + one byte (`0` disable, nonzero enable).
- Unsolicited notification: characteristic UUID + value bytes.

## Exact server behavior by message ID

- `0x01` Discover Services:
  - response payload = UUID blob(`0x1826`) + UUID blob(`0x1818`)
- `0x02` Discover Characteristics:
  - request payload = UUID blob(service)
  - for `0x1826`, returns:
    - `0x2AD9` write+indicate
    - `0xE005` write
    - `0x2AD2` notify
    - `0x2ADA` notify
    - `0x2ACC` read
    - `0x2AD6` read
    - `0x2AD5` read
    - `0x2AD8` read
    - `0x2AD3` read
  - for `0x1818`, returns:
    - `0x2A63` notify
    - `0x2A65` read
    - `0x2A5D` read
- `0x03` Read Characteristic:
  - response payload = UUID blob(char) + value
- `0x04` Write Characteristic:
  - payload = UUID blob(char) + write bytes
  - `0x2AD9` -> FTMS CP processing path
  - `0xE005` -> Wahoo fallback path
  - any other char -> response code `0x05`
- `0x05` Enable Notifications:
  - payload = UUID blob(char) + `0x00|0x01`
  - supports: `0x2AD2`, `0x2A63`, `0x2AD9`, `0x2ADA`
  - enabling `0x2AD2` triggers implicit FTMS start `[0x07]`
- `0x06` Unsolicited Notification:
  - emitted by server for telemetry/status indications
- `0x07` Unknown:
  - accepted and answered with success

## Characteristics used for bike profile

Current implemented set:

- Service `0x1826` (Fitness Machine Service)
  - `0x2AD9` FTMS Control Point (`write` + `indicate`)
  - `0xE005` Wahoo fallback (`write`)
  - `0x2AD2` Indoor Bike Data (`notify`)
  - `0x2ADA` Fitness Machine Status (`notify`)
  - `0x2ACC` Fitness Machine Feature (`read`)
  - `0x2AD5` Supported Inclination Range (`read`)
  - `0x2AD6` Supported Resistance Range (`read`)
  - `0x2AD8` Supported Power Range (`read`)
  - `0x2AD3` Training Status (`read`)
- Service `0x1818` (Cycling Power Service)
  - `0x2A63` Cycling Power Measurement (`notify`)
  - `0x2A65` Cycling Power Feature (`read`)
  - `0x2A5D` Sensor Location (`read`)

## FitPro bridge implications

- Dircon support can be layered over the same FTMS command dispatcher used by BLE control point writes.
- If Dircon is enabled, network writes to `0x2AD9` must produce the same behavior/responses as BLE writes.
- Notifications should be emitted at roughly 10Hz for smooth Zwift updates.
- Wahoo `0xE005` writes are translated to FTMS control-point writes before native execution.
