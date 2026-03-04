# ReadWriteData Deep Dive

> The ReadWriteData command (0x02) is the most critical command in FitPro v1.
> It's the sole mechanism for reading sensor data and controlling the equipment.
> Source: `ReadWriteDataCmd.cs`, `QueueManager.cs`, `EquipmentUtil.cs`

---

## Overview

ReadWriteData carries two independent sections in a single packet:
1. **Write section** - Values to send TO the equipment (motor control, settings)
2. **Read section** - BitFields to request FROM the equipment (sensors, state)

The response contains only the read values. Write operations are acknowledged
by the response status code.

---

## Request Format

```
Offset  Field
──────  ─────
0       Device ID (e.g., 0x07 for FitnessBike)
1       Total Length
2       0x02 (ReadWriteData command)
3+      Write section
W+      Read section
L-1     Checksum
```

**Note:** There is no status byte in requests. Content starts at offset 3.

---

## Section Structure

Both write and read sections share the same header format. The only difference
is that write sections include data bytes after the header.

### Header Construction

```
Byte 0:     Header length (number of section bitmask bytes)
Bytes 1..N: Section bitmask bytes (one per section of 8 BitFields)
```

**Empty section:** A single byte `0x00` (header length = 0, meaning no fields).

### Bitmask Algorithm

BitField IDs are grouped into sections of 8. Each section has a bitmask byte
where each bit represents one BitField.

```python
def build_section_header(field_ids):
    """Build header bytes for a list of BitField IDs."""
    if not field_ids:
        return bytes([0x00])

    field_ids = sorted(field_ids)
    num_sections = max(field_ids) // 8 + 1  # Number of bitmask bytes needed

    header = [num_sections]
    for section in range(num_sections):
        bitmask = 0x00
        for field_id in field_ids:
            if field_id // 8 == section:
                bitmask |= (1 << (field_id % 8))
        header.append(bitmask)

    return bytes(header)
```

### Bitmask Example: Watts (3) + RPM (5)

```
Both are in section 0 (IDs 0-7):
  Watts = ID 3 → bit 3 → 0x08
  RPM   = ID 5 → bit 5 → 0x20
  Section 0 bitmask = 0x08 | 0x20 = 0x28

Header: [0x01, 0x28]
         │      └── Section 0: bits 3,5 set
         └── 1 section byte follows
```

### Bitmask Example: Grade (1) + WorkoutMode (12) + MaxGrade (27)

```
Grade      = ID 1  → section 0, bit 1 → 0x02
WorkoutMode = ID 12 → section 1, bit 4 → 0x10
MaxGrade   = ID 27 → section 3, bit 3 → 0x08

Need 4 sections (0, 1, 2, 3):
  Section 0: 0x02  (bit 1)
  Section 1: 0x10  (bit 4)
  Section 2: 0x00  (no fields)
  Section 3: 0x08  (bit 3)

Header: [0x04, 0x02, 0x10, 0x00, 0x08]
         │      │     │     │     └── Section 3
         │      │     │     └── Section 2 (empty but required)
         │      │     └── Section 1
         │      └── Section 0
         └── 4 section bytes follow
```

**Important:** All sections up to the highest used section must be included,
even if they're empty (0x00). The header length equals `max_field_id / 8 + 1`.

---

## Write Section Layout

```
[header_length] [section_bitmasks...] [value_bytes...]
```

After the bitmask header, include the encoded bytes for each field IN ORDER
of their BitField ID (lowest first).

```python
def build_write_section(fields_and_values):
    """fields_and_values: list of (field_id, encoded_bytes) sorted by field_id"""
    if not fields_and_values:
        return bytes([0x00])

    field_ids = [f[0] for f in fields_and_values]
    header = build_section_header(field_ids)
    data = b''.join(v for _, v in fields_and_values)
    return header + data
```

### Write Example: Set Resistance to 12

```
BitField Resistance = ID 2
ResistanceConverter encodes 12.0 (with S22i MaxResistance=24):
  stepSize = 10000.0 / 24 = 416.667
  raw = int(12.0 * 416.667) = 5000
  adjusted = max(0, 5000 - int(416.667 * 0.1)) = max(0, 5000 - 41) = 4959
  Wait, let's be precise: 4959 as uint16 LE = [0x5F, 0x13]

Actually:
  num = (int)(12 * stepSize) = (int)(5000.0) = 5000
  result = (int)Math.Max(0.0, 5000 - stepSize * 0.1) = (int)Math.Max(0, 4958.33) = 4958
  4958 = 0x135E → LE bytes: [0x5E, 0x13]

Header: field_id=2 → section 0, bit 2 → [0x01, 0x04]
Data: [0x5E, 0x13]

Write section: 01 04 5E 13
```

---

## Read Section Layout

```
[header_length] [section_bitmasks...]
```

The read section is just the header (no data bytes). The response will contain
the values in the same BitField order.

### Read Example: Read RPM and Watts

```
RPM   = ID 5 → section 0, bit 5
Watts = ID 3 → section 0, bit 3

Section 0 bitmask = (1<<3) | (1<<5) = 0x28
Header: [0x01, 0x28]

Read section: 01 28
```

---

## Complete Request Examples

### Example 1: Read-Only (Watts + RPM)

```
Write section: 00          (empty)
Read section:  01 28       (Watts=3, RPM=5)
Content:       00 01 28    (3 bytes)
Length:        3 + 4 = 7

Packet:  07 07 02 00 01 28 39
         ││ ││ ││ ││ ││ ││ └─ Checksum (0x07+0x07+0x02+0x00+0x01+0x28=0x39)
         ││ ││ ││ ││ └┴──── Read header: 1 section, bits 3+5
         ││ ││ ││ └──────── Write header: empty (0 sections)
         ││ ││ └─────────── Command: ReadWriteData
         ││ └────────────── Length: 7
         └───────────────── Device: FitnessBike
```

### Example 2: Write-Only (Set Resistance to 12)

```
Write section: 01 04 5E 13    (Resistance=2, value 4958)
Read section:  00              (empty)
Content:       01 04 5E 13 00 (5 bytes)
Length:        5 + 4 = 9

Packet:  07 09 02 01 04 5E 13 00 88
```

### Example 3: Write + Read (Set WorkoutMode=Running, Read Grade + Watts)

```
WorkoutMode = ID 12 → section 1, bit 4 → [0x02, 0x00, 0x10]
  ModeConverter encodes Running (2) → [0x02]
Write section: 02 00 10 02
               │  │  │  └── Value: WorkoutMode.Running = 2
               │  │  └── Section 1: bit 4
               │  └── Section 0: no write fields
               └── 2 section bytes

Grade = ID 1 → section 0, bit 1
Watts = ID 3 → section 0, bit 3
Read section: 01 0A
              │  └── Section 0: bits 1+3 = 0x02|0x08 = 0x0A
              └── 1 section byte

Content:  02 00 10 02 01 0A  (6 bytes)
Length:   6 + 4 = 10

Packet:   07 0A 02 02 00 10 02 01 0A CC
          (Checksum: 07+0A+02+02+00+10+02+01+0A = 0x28... let me recalculate)
          0x07+0x0A+0x02+0x02+0x00+0x10+0x02+0x01+0x0A = 0x28
          Hmm: 7+10+2+2+0+16+2+1+10 = 50 = 0x32

Packet:   07 0A 02 02 00 10 02 01 0A 32
```

---

## Response Format

The response contains only the READ values (writes are acknowledged via status).

```
Offset  Field
──────  ─────
0       Device
1       Length
2       0x02 (ReadWriteData)
3       Status (should be 0x02 = Done)
4+      Read values in BitField ID order (concatenated)
L-1     Checksum
```

### Expected Response Length

```python
expected_length = 5  # Device + Length + Command + Status + Checksum
for field in read_fields:
    expected_length += converter_size(field)  # Add each field's byte size
```

### Response Example: Watts=150, RPM=80

```
Watts: ShortConverter size=2, value 150 → [0x96, 0x00]
RPM:   ShortConverter size=2, value 80  → [0x50, 0x00]

Response: 07 09 02 02 96 00 50 00 FA
          ││ ││ ││ ││ │──┤ │──┤ └─ Checksum
          ││ ││ ││ ││ │   │   └── RPM: 0x0050 = 80
          ││ ││ ││ ││ └───────── Watts: 0x0096 = 150
          ││ ││ ││ └──────────── Status: Done
          ││ ││ └─────────────── Command: ReadWriteData
          ││ └────────────────── Length: 9
          └───────────────────── Device: FitnessBike
```

### Response Parsing Pseudocode

```python
def parse_readwrite_response(response, read_fields):
    """Parse a ReadWriteData response and extract field values."""
    status = response[3]
    if status != 0x02:  # Not Done
        return None

    offset = 4  # Skip header
    values = {}
    for field in read_fields:  # Must be in same order as request
        converter = get_converter(field)
        raw_bytes = response[offset : offset + converter.size]
        values[field] = converter.decode(raw_bytes)
        offset += converter.size

    return values
```

---

## Batch Packing Strategy

The maximum usable payload for ReadWriteData is **58 bytes** (64 max packet - 4 header - 1 checksum - 1 for the `0x00` write header when read-only).

The QueueManager uses this algorithm to fit fields into a single packet:

```python
def select_fields_that_fit(fields, is_write=False):
    """Select fields that fit within 58 bytes."""
    selected = []
    max_field_id = max(f.id for f in fields)
    header_size = max_field_id // 8 + 1  # Section bitmask bytes
    available = 58 - header_size         # Remaining for data

    used = 0
    for field in sorted(fields, key=lambda f: f.id):
        size = field.converter.size
        if used + size < available:  # Strict less-than (not <=)
            selected.append(field)
            used += size
        else:
            break  # Can't fit more

    return selected
```

**Fields are always processed in ascending BitField ID order.**

### Write vs Read Priority

When the queue contains both write and read items, writes take priority:
- If ANY write items exist, only writes are batched into the next packet
- Read items are deferred until no writes are pending
- This ensures motor commands are sent immediately

---

## Periodic Polling

During steady-state operation, the app reads 30 BitFields every ~100ms.
See [BITFIELDS.md](BITFIELDS.md) for the complete list of periodic fields.

All 30 periodic fields fit in a single ReadWriteData packet because the
total data size (sum of converter sizes) is within the 58-byte limit.

---

**Last Updated:** 2026-02-10
**Source:** `ReadWriteDataCmd.cs`, `QueueManager.cs`, `EquipmentUtil.cs`
