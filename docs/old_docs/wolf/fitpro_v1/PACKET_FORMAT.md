# FitPro v1 Packet Format

> Byte-level packet structure for all FitPro v1 communication.
> Source: `CommandBase.cs`, `EquipmentUtil.cs`, `FitProByteExtensions.cs`

---

## Request Packet Layout

Requests do NOT include a status byte. Content starts immediately after the command byte.

```
Offset  Size  Field       Description
──────  ────  ─────       ───────────
0       1     Device      Device ID (enum Device)
1       1     Length      Total packet length (4..64), includes all bytes
2       1     Command     Command ID (enum Command)
3       N     Content     Command-specific payload (0..59 bytes)
L-1     1     Checksum    Sum of bytes[0..L-2] & 0xFF
```

**Constraints:**
- `Length` = `ContentLength + 4` (3 header bytes + 1 checksum byte)
- Maximum `Length` = 64 (`MaxMsgLength`)
- Maximum content = 60 bytes (64 - 4 header/checksum)
- For ReadWriteData, usable payload = 58 bytes (`MaxPacketResponseSize` in QueueManager)
- Minimum `Length` = 4 (no content, just header + checksum)

### Minimum Request (No Content)

Commands like Connect, Disconnect, DeviceInfo use ContentLength=0:

```
[Device] [0x04] [Command] [Checksum]
   0       1       2          3
```

### Request With Content

Commands like SystemInfo (ContentLength=2):

```
[Device] [Length] [Command] [Content0] [Content1] [Checksum]
   0       1        2          3          4          5
```

---

## Response Packet Layout

Responses include a status byte at offset 3. Content starts at offset 4.

```
Offset  Size  Field       Description
──────  ────  ─────       ───────────
0       1     Device      Device ID of responder
1       1     Length      Total packet length (5..64)
2       1     Command     Echo of the command ID
3       1     Status      CmdStatus result code
4       N     Content     Response-specific data
L-1     1     Checksum    Sum of bytes[0..L-2] & 0xFF
```

**Minimum response:** 5 bytes (device + length + command + status + checksum)

### Key Status Codes

| Value | Name | Meaning |
|-------|------|---------|
| 0 | DevNotSupported | Device type not supported |
| 1 | CmdNotSupported | Command not supported |
| **2** | **Done** | **Success** |
| 3 | InProgress | Still processing (e.g., calibration) |
| 4 | Failed | General failure |
| 7 | UnknownFailure | Unknown error |
| 8 | SecurityBlock | Requires VerifySecurity first |
| 9 | CommFailed | Communication failure |

---

## Checksum Algorithm

The checksum is a simple unsigned byte sum of all preceding bytes in the packet.

```python
def calculate_checksum(packet, length):
    """Sum bytes[0] through bytes[length-2], truncated to uint8."""
    total = 0
    for i in range(length - 1):
        total = (total + packet[i]) & 0xFF
    return total
```

**Source (C#):**
```csharp
public static byte GetCheckSum(byte[] bytes)
{
    byte checksum = 0;
    byte length = bytes[1];  // Length field at index 1
    for (int i = 0; i < length - 1; i++)
        checksum += bytes[i];
    return checksum;
}
```

### Worked Example

DeviceInfo request for Main device:

```
Bytes:    [0x02] [0x04] [0x81]  [??]
           │       │      │      └── Checksum to calculate
           │       │      └── Command: DeviceInfo (129)
           │       └── Length: 4
           └── Device: Main (2)

Checksum = (0x02 + 0x04 + 0x81) & 0xFF
         = 0x87 & 0xFF
         = 0x87

Final packet: 02 04 81 87
```

### Another Example

ReadWriteData request (reading Watts + RPM):

```
Bytes: [0x07] [0x07] [0x02] [0x00] [0x01] [0x28]  [??]

Sum = 0x07 + 0x07 + 0x02 + 0x00 + 0x01 + 0x28 = 0x39

Final packet: 07 07 02 00 01 28 39
```

---

## Response Validation

Validate every response before parsing. Check all four conditions in order:

```python
def is_valid_response(response, expected_command_id):
    # 1. Non-null and minimum length
    if response is None or len(response) < 3:
        return False

    # 2. Device must not be None (0)
    if response[0] == 0x00:
        return False

    # 3. Length must not exceed MaxMsgLength (64)
    if response[1] > 64:
        return False

    # 4. Command must match what we sent
    if response[2] != expected_command_id:
        return False

    # 5. Checksum must match
    length = response[1]
    expected_checksum = sum(response[0:length-1]) & 0xFF
    if response[length - 1] != expected_checksum:
        return False

    return True
```

**For ReadWriteData**, also verify the response length matches expected:
```python
expected_length = 5  # 4 header + 1 checksum
for field in read_fields:
    expected_length += field.converter.size
if len(cleaned_response) != expected_length:
    retry()  # Length mismatch - retry the command
```

---

## USB Transport

For USB (the S22i's transport), packets are sent as raw 64-byte bulk transfers.
No framing layer is needed.

| Parameter | Value | Source |
|-----------|-------|--------|
| Buffer size | 64 bytes | `MaxMsgLength`, `BytesPerRequestLimit` |
| Write → Read delay | 25ms (transport), 80ms (ReadWriteData), 300ms (DeviceInfo) | Per command |
| Response timeout | 1000ms (USB default) | `FitProUsbConsoleCommunicationAdapter` |
| Max lost before fatal | 5 | `maxItemLostBeforeFatality` |
| Lost item decay | 2 seconds | `rateOfItemLostDecay` |

### Send/Receive Flow (USB)

1. Write 64 bytes to EP 0x02 OUT (pad unused bytes with 0x00)
2. Wait `ReadDelay` milliseconds
3. Read 64 bytes from EP 0x81 IN
4. Validate response (length, command match, checksum)
5. Parse response content

---

## BLE Transport (Multi-Packet Framing)

BLE connections use a framing layer to split packets into 18-byte payloads.
**Not used by S22i** (USB only), but documented here for completeness.

### Frame Structure

Each FitPro packet is split into 20-byte BLE messages:

**Init message (always first):**
```
[0xFE] [0x02] [total_data_length] [total_message_count]
```

**Data messages:**
```
[sequence] [payload_size] [payload: up to 18 bytes]
```

- `sequence` = 0, 1, 2, ... for intermediate messages
- `sequence` = 0xFF for the final message
- `payload_size` = number of valid bytes in this message (max 18)
- Total message size = 20 bytes (2 overhead + 18 payload)

### Splitting Algorithm

```python
def create_ble_messages(data):
    messages = []

    # Init message
    num_messages = ceil(len(data) / 18) if len(data) > 18 else 1
    messages.append(bytes([0xFE, 0x02, len(data), num_messages + 1]))

    # Data messages
    offset = 0
    for i in range(num_messages):
        payload_size = min(18, len(data) - offset)
        seq = 0xFF if (offset + payload_size >= len(data)) else i
        msg = bytes([seq, payload_size]) + data[offset:offset+payload_size]
        msg = msg.ljust(20, b'\x00')  # Pad to 20 bytes
        messages.append(msg)
        offset += payload_size

    return messages
```

### BLE Timing

| Parameter | Value |
|-----------|-------|
| Default timeout | 2 seconds |
| Max retry count | 3 |
| Response packets (read) | 6 expected |
| Response packets (write) | 11 expected |

---

**Last Updated:** 2026-02-10
**Source:** `CommandBase.cs`, `EquipmentUtil.cs`, `FitProCommunication.cs`, `FitProCommunicationGroup.cs`
