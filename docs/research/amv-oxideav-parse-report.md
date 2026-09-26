# oxideav-amv `src/parse.rs` — verbatim byte-layout constants & field offsets

Sources (all fetches via `web_fetch` only; shell/curl network is blocked):

* `https://cdn.jsdelivr.net/gh/OxideAV/oxideav-amv@master/src/parse.rs` — **HTTP 200**, this is the retrieval that worked.
  * `raw.githubusercontent.com/OxideAV/oxideav-amv/master/src/parse.rs` — **FAILED twice** (`TypeError: fetch failed`, host unreachable from this sandbox).
  * `raw.githack.com/...` — FAILED. `cdn.statically.io/gh/...` — FAILED (retried once).
* `https://cdn.jsdelivr.net/gh/OxideAV/oxideav-amv@master/src/demuxer.rs` — HTTP 200 (first 100,602 bytes).
* `https://cdn.jsdelivr.net/combine/gh/OxideAV/oxideav-amv@master/src/lib.rs,gh/OxideAV/oxideav-amv@master/src/muxer.rs,gh/OxideAV/oxideav-amv@master/src/rate.rs` — HTTP 200, **complete**.
* `https://api.github.com/repos/OxideAV/oxideav-amv/git/trees/master?recursive=1` — HTTP 200, `"truncated":false`.

## 0. Retrieval completeness (read this before trusting the negative results)

| file | size on master | retrieved |
|---|---|---|
| `src/parse.rs` | **159 003 B** (blob `2255ebef2c2d3d8536bbdd50af6ebb8d5bf2b9fd`) | **first 100 994 B = lines 1–2299** (63.5 %). Lines 2300→EOF **NOT retrieved** |
| `src/demuxer.rs` | 137 592 B | first 100 602 B (lines 1–2181) |
| `src/lib.rs`, `src/muxer.rs`, `src/rate.rs` | 16 539 / 38 427 / 11 458 B | **100 % complete** |

The fetch tool truncates any response body at ≈101 KB, head-first, so the **tail of any file larger than ~101 KB is unreachable by any whole-file URL** (mirrors, `raw.*`, docs.rs source view all return the same bytes and truncate identically). I verified the retained text is contiguous — no mid-file splice — and that the cut lands *inside* `pub(crate) mod tests` (declared at line 1701). Everything from line 1 through the end of the non-test code was retrieved.

Evidence files kept in this workspace (verbatim retrieved bytes):
`_spill_parse.txt` (100 994 B of parse.rs), `_spill_demux.txt`, `_spill_mux.txt` (lib.rs + muxer.rs + rate.rs).

---

## 1. All `const` / fourCC / length constants in parse.rs (verbatim, with line numbers)

```rust
 34: pub const AMV_FORM_TYPE: [u8; 4] = *b"AMV ";
 40: pub const AMV_END_TRAILER: [u8; 8] = *b"AMV_END_";
 45: pub const AMVH_BODY_LEN: u32 = 0x38;
 50: const AMVH_RESERVED_SPAN_OFFSET: usize = 0x04;
 53: const AMVH_RESERVED_SPAN_LEN: usize = 7 * 4;
 57: pub const VIDEO_CHUNK_TAG: [u8; 4] = *b"00dc";
 61: pub const AUDIO_CHUNK_TAG: [u8; 4] = *b"01wb";
 65: const TAG_RIFF: [u8; 4] = *b"RIFF";
 66: const TAG_LIST: [u8; 4] = *b"LIST";
 67: const TAG_HDRL: [u8; 4] = *b"hdrl";
 68: const TAG_AMVH: [u8; 4] = *b"amvh";
 69: const TAG_STRL: [u8; 4] = *b"strl";
 70: const TAG_STRH: [u8; 4] = *b"strh";
 71: const TAG_STRF: [u8; 4] = *b"strf";
 72: const TAG_MOVI: [u8; 4] = *b"movi";
 76: const HDRL_OFFSET: u64 = 0x0C;
 79: const AMVH_OFFSET: u64 = 0x18;
 82: const STRL_VIDEO_OFFSET: u64 = AMVH_OFFSET + 8 + AMVH_BODY_LEN as u64;   // = 0x58
 88: const STRH_VIDEO_BODY_LEN: u32 = 0x38;
 92: const STRF_VIDEO_BODY_LEN: u32 = 0x24;
 97: const STRH_AUDIO_BODY_LEN: u32 = 0x30;
101: const STRF_AUDIO_BODY_LEN: u32 = 0x14;
632: pub const JPEG_SOI: [u8; 2] = [0xFF, 0xD8];
637: pub const JPEG_EOI: [u8; 2] = [0xFF, 0xD9];
644: pub const AMV_AUDIO_PREAMBLE_LEN: usize = 8;
956: pub const IMA_STEP_INDEX_MAX: u8 = 88;
1638: pub(crate) const PRELUDE_MIN_LEN: usize = 0x13C;
```

Supporting doc comment for the form type / trailer (verbatim):

```rust
/// FORM type FOURCC — the four bytes immediately following the
/// `RIFF <size>` prefix. `b"AMV "` includes the trailing **space**
/// that distinguishes AMV from a conforming AVI file (which carries
/// `b"AVI "`).
pub const AMV_FORM_TYPE: [u8; 4] = *b"AMV ";

/// ASCII trailer that bounds the `movi` payload (§4c). The file ends
/// with this literal sequence immediately after the final `01wb`
/// leaf, taking the place of the `idx1` index that a conforming AVI
/// would carry.
pub const AMV_END_TRAILER: [u8; 8] = *b"AMV_END_";

/// `amvh` body length (§2). The constant `0x38 = 56` is repeated in
/// both observed fixtures and is recorded explicitly here so callers
/// can sanity-check it before deciding to trust the body fields.
pub const AMVH_BODY_LEN: u32 = 0x38;
```

### Answers to the two pointed questions

* **Form type = `b"AMV "` WITH the trailing space — not `b"AVI "`.** `AMV_FORM_TYPE: [u8; 4] = *b"AMV "`.
  Independent confirmation from `src/lib.rs` (verbatim): `const PROBE_MAGIC_AMV: &[u8; 4] = b"AMV ";` and the probe rejects `b"AVI "`. Also `lib.rs` crate docs: *"wrapped in a `RIFF` form whose type is `AMV ` (trailing space)"*.
  `"AVI "` occurs in parse.rs **only once as negative-test input**, line 1823: `buf[8..12].copy_from_slice(b"AVI ");` inside `fn prelude_parse_rejects_avi_form_type`.
* **Trailer = ONE 8-byte literal `*b"AMV_END_"`, not two fourCCs.** Declared as `[u8; 8]`, and demuxer.rs compares all 8 bytes at once: `if header_bytes == AMV_END_TRAILER {` (demuxer.rs, `build_chunk_index`), with `let mut header_bytes = [0u8; 8];`. There is no `AMV_` / `END_` pair anywhere.

### Complete inventory of every `b"..."` byte-string literal in parse.rs (lines 1–2299)

`b"AMV "`(34), `b"AMV_END_"`(40), `b"00dc"`(57), `b"01wb"`(61), `b"RIFF"`(65), `b"LIST"`(66), `b"hdrl"`(67), `b"amvh"`(68), `b"strl"`(69), `b"strh"`(70), `b"strf"`(71), `b"movi"`(72), `b"abcd"`(1784, test input), `b"AVI "`(1823, test input).
**There is no other tag/length constant in the file.** (Verified with a line-by-line scan for `b"`.)

Additional constant from **muxer.rs** (not parse.rs), for the header-region offset table:

```rust
/// File offset where the `amvh +0x34` packed-duration dword lives. Used
/// by [`AmvMuxer::write_trailer`] to patch in the final duration.
/// `0x18` (amvh FOURCC) + 8 (FOURCC+len) + 0x34 = 0x54.
const AMVH_DURATION_FILE_OFFSET: u64 = 0x54;
```

---

## 2. `AmvHeader` (the `amvh` body) — offsets and meanings

Verbatim struct (parse.rs lines 103–129):

```rust
/// Structured view of the `amvh` main header (§2). All multi-byte
/// integers are little-endian. The seven reserved dwords between
/// `dwMicroSecPerFrame` and `width` are not exposed.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct AmvHeader {
    /// `dwMicroSecPerFrame` (offset 0x00 within the body). Equal to
    /// `1_000_000 / fps`; the two fixtures hold `83_333` (12 fps) and
    /// `62_500` (16 fps).
    pub micros_per_frame: u32,
    /// Video width in pixels (offset 0x20 within the body).
    pub width: u32,
    /// Video height in pixels (offset 0x24 within the body).
    pub height: u32,
    /// Frames per second (offset 0x28 within the body).
    pub fps: u32,
    /// Constant `1` flag at offset 0x2C. Meaning not determinable
    /// from the bytes; surfaced for callers that want to validate it
    /// matches the observed convention.
    pub flag_one: u32,
    /// Reserved dword at offset 0x30. Always zero in observed
    /// fixtures.
    pub reserved_30: u32,
    /// Byte-packed total duration (§2). Encoded as
    /// `[seconds, minutes, hours, 0]` little-endian — see
    /// [`AmvDuration::from_packed`].
    pub duration_packed: u32,
}
```

Verbatim reader (parse.rs lines 131–160) — this is the authoritative offset list:

```rust
    pub fn parse(body: &[u8]) -> Result<Self, AmvDemuxerError> {
        if body.len() < AMVH_BODY_LEN as usize {
            return Err(AmvDemuxerError::InvalidData(format!(
                "amvh body must be {} bytes, got {}",
                AMVH_BODY_LEN,
                body.len()
            )));
        }
        Ok(Self {
            micros_per_frame: read_u32_le(body, 0x00),
            width: read_u32_le(body, 0x20),
            height: read_u32_le(body, 0x24),
            fps: read_u32_le(body, 0x28),
            flag_one: read_u32_le(body, 0x2C),
            reserved_30: read_u32_le(body, 0x30),
            duration_packed: read_u32_le(body, 0x34),
        })
    }
```

Reserved span (verbatim comments, lines 47–53):

```rust
/// Byte offset (within the `amvh` body) of the §2 reserved span that
/// the trace records as "reserved / zeroed (7 dwords)" — the 28 bytes
/// between `dwMicroSecPerFrame` (+0x00) and `width` (+0x20).
const AMVH_RESERVED_SPAN_OFFSET: usize = 0x04;
/// Length of the §2 reserved span — seven little-endian dwords
/// (`+0x04 … +0x1C` inclusive, i.e. `+0x04 .. +0x20`).
const AMVH_RESERVED_SPAN_LEN: usize = 7 * 4;
```

`validate_sentinels()` (verbatim conditions, lines ~186–216):

```rust
        if self.fps == 0 { ... "amvh +0x28 fps must be > 0" ... }
        let expected_micros = 1_000_000 / self.fps;
        if self.micros_per_frame != expected_micros {
            ... "amvh +0x00 dwMicroSecPerFrame={} does not match 1_000_000/fps={}" ...
        }
        if self.flag_one != 1 { ... "amvh +0x2C constant must be 1, got {}" ... }
        if self.reserved_30 != 0 { ... "amvh +0x30 reserved dword must be 0, got {}" ... }
```

**amvh body offset table (body-relative → file offset = body + 0x20):**

| body off | file off | field | type | value/meaning |
|---|---|---|---|---|
| +0x00 | 0x20 | `dwMicroSecPerFrame` | u32 LE | `1_000_000 / fps` (83 333 @12 fps, 62 500 @16 fps) |
| +0x04..+0x1C | 0x24..0x3C | reserved span | 7 × u32 LE | all zero ("reserved / zeroed (7 dwords)"), strict-parsed |
| +0x20 | 0x40 | `width` | u32 LE | 128 / 96 in fixtures |
| +0x24 | 0x44 | `height` | u32 LE | 96 / 64 |
| +0x28 | 0x48 | `fps` | u32 LE | 12 / 16 |
| +0x2C | 0x4C | `flag_one` | u32 LE | constant `1` |
| +0x30 | 0x50 | `reserved_30` | u32 LE | 0 |
| +0x34 | 0x54 | `duration_packed` | u32 LE | packed duration, `[sec, min, hr, 0]` |
| +0x38 | 0x58 | — end of body — | | = `AMVH_BODY_LEN` |

---

## 3. `AmvDuration` and its `to_packed()` / `from_frame_count()`

Verbatim (lines 224–236, 237–248, 262–269):

```rust
/// Decoded representation of the `amvh` packed duration (§2). The
/// bytes are laid out `[seconds, minutes, hours, 0]` — each value is a
/// raw little-endian byte, **not** BCD or any other encoding.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct AmvDuration {
    /// Seconds component (0..=255 in principle; observed values fit
    /// within 0..60).
    pub seconds: u8,
    /// Minutes component.
    pub minutes: u8,
    /// Hours component.
    pub hours: u8,
}

    /// Unpack the four bytes of `duration_packed` from `amvh +0x34`.
    pub fn from_packed(packed: u32) -> Self {
        let bytes = packed.to_le_bytes();
        Self {
            seconds: bytes[0],
            minutes: bytes[1],
            hours: bytes[2],
        }
    }

    pub fn to_packed(&self) -> u32 {
        u32::from_le_bytes([self.seconds, self.minutes, self.hours, 0])
    }
```

`to_packed` doc (verbatim, abbreviated to the byte facts):

```rust
    /// The fourth byte is always written as `0` per the trace doc — the
    /// two observed device profiles both leave it zero (`21 01 00 00`
    /// for the 12 fps profile, `02 03 00 00` for the 16 fps profile),
    /// and the trace records the field as `[seconds, minutes, hours, 0]`.
```

`from_frame_count` (verbatim, lines 297–319):

```rust
    pub fn from_frame_count(frame_count: u64, fps: u32) -> Self {
        if fps == 0 {
            return Self {
                seconds: 0,
                minutes: 0,
                hours: 0,
            };
        }
        let total_seconds = frame_count / fps as u64;
        let hours = (total_seconds / 3600).min(u8::MAX as u64) as u8;
        let after_hours = total_seconds.saturating_sub(hours as u64 * 3600);
        let minutes = (after_hours / 60).min(u8::MAX as u64) as u8;
        let seconds = (after_hours % 60).min(u8::MAX as u64) as u8;
        Self {
            seconds,
            minutes,
            hours,
        }
    }
```

Also present: `total_seconds()` = `hours*3600 + minutes*60 + seconds`; `is_consistent_with_frame_count()`; `consistency_with_frame_count()` returning `DurationConsistency::{Exact, TruncatedByOneSecond, Mismatch}` with `is_device_conformant()` true for the first two. Recorded worked values (from tests/docs, verbatim): comedian `0x0000_0121` = `21 01 00 00` = 33 s + 1 min → 1:33 (1116 ÷ 12 = 93 s); noel `0x0000_0302` = `02 03 00 00` = 2 s + 3 min → 3:02 (182 s, device truncates one second vs the 183 s derivation).

---

## 4. `AmvWaveFormat` (WAVEFORMATEX, 20-byte audio `strf`)

Verbatim reader (lines 442–466):

```rust
    pub fn parse(body: &[u8]) -> Result<Self, AmvDemuxerError> {
        if body.len() < 18 {
            return Err(AmvDemuxerError::InvalidData(format!(
                "audio strf body must be at least 18 bytes, got {}",
                body.len()
            )));
        }
        Ok(Self {
            format_tag: read_u16_le(body, 0x00),
            channels: read_u16_le(body, 0x02),
            samples_per_sec: read_u32_le(body, 0x04),
            avg_bytes_per_sec: read_u32_le(body, 0x08),
            block_align: read_u16_le(body, 0x0C),
            bits_per_sample: read_u16_le(body, 0x0E),
            cb_size: if body.len() >= 20 {
                read_u16_le(body, 0x10)
            } else {
                0
            },
        })
    }
```

Field docs (verbatim, lines 422–440):

```rust
    /// `wFormatTag`. Observed value: `1` (PCM declared; payload is
    /// actually ADPCM — header lies about the codec).
    pub format_tag: u16,
    /// `nChannels`. Always 1 (mono) in observed fixtures.
    pub channels: u16,
    /// `nSamplesPerSec`. Observed values: `22_050`.
    pub samples_per_sec: u32,
    /// `nAvgBytesPerSec`. Observed: `samples_per_sec * 2` — i.e. the
    /// rate of the decoded 16-bit PCM, not the on-disk byte rate.
    pub avg_bytes_per_sec: u32,
    /// `nBlockAlign`. Observed value: 2.
    pub block_align: u16,
    /// `wBitsPerSample`. Observed value: 16 (refers to decoded PCM
    /// width, not the on-disk nibble payload).
    pub bits_per_sample: u16,
    /// `cbSize`. Observed value: 0.
    pub cb_size: u16,
```

`validate_sentinels()` (verbatim body, lines 519–556):

```rust
        if self.format_tag != 1 {
            return Err(AmvDemuxerError::InvalidData(format!(
                "audio strf +0x00 wFormatTag must be 1 (declared PCM), got {}",
                self.format_tag
            )));
        }
        if self.channels != 1 {
            return Err(AmvDemuxerError::InvalidData(format!(
                "audio strf +0x02 nChannels must be 1 (mono), got {}",
                self.channels
            )));
        }
        let expected_avg = self.samples_per_sec.saturating_mul(2);
        if self.avg_bytes_per_sec != expected_avg {
            return Err(AmvDemuxerError::InvalidData(format!(
                "audio strf +0x08 nAvgBytesPerSec={} does not match \
                 nSamplesPerSec * 2 = {}",
                self.avg_bytes_per_sec, expected_avg
            )));
        }
        if self.block_align != 2 {
            return Err(AmvDemuxerError::InvalidData(format!(
                "audio strf +0x0C nBlockAlign must be 2, got {}",
                self.block_align
            )));
        }
        if self.bits_per_sample != 16 {
            return Err(AmvDemuxerError::InvalidData(format!(
                "audio strf +0x0E wBitsPerSample must be 16, got {}",
                self.bits_per_sample
            )));
        }
        if self.cb_size != 0 {
            return Err(AmvDemuxerError::InvalidData(format!(
                "audio strf +0x10 cbSize must be 0, got {}",
                self.cb_size
            )));
        }
```

Plus `frame_interval_samples(fps)` = `samples_per_sec / fps` (0 when fps == 0).

---

## 5. `AmvAudioPreamble` (8-byte per-`01wb` header)

Verbatim (lines 639–694):

```rust
/// Minimum size of an `01wb` audio chunk payload — the 8-byte §4b
/// preamble alone, with no compressed body. Real chunks are always
/// larger (the comedian profile's blocks are 927 bytes) but this
/// minimum is the smallest payload that satisfies the §4b structural
/// invariant.
pub const AMV_AUDIO_PREAMBLE_LEN: usize = 8;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct AmvAudioPreamble {
    pub state: u32,
    pub decoded_sample_count: u32,
}

    pub fn parse(body: &[u8]) -> Result<Self, AmvDemuxerError> {
        if body.len() < AMV_AUDIO_PREAMBLE_LEN {
            return Err(AmvDemuxerError::InvalidData(format!(
                "01wb payload preamble needs {AMV_AUDIO_PREAMBLE_LEN} bytes, got {}",
                body.len()
            )));
        }
        Ok(Self {
            state: read_u32_le(body, 0x00),
            decoded_sample_count: read_u32_le(body, 0x04),
        })
    }
```

The dword at `+0x00` is re-read as three packed fields (verbatim accessors, lines 876–929):

```rust
    pub fn initial_predictor(&self) -> i16 {
        (self.state & 0xFFFF) as u16 as i16
    }

    pub fn initial_step_index(&self) -> u8 {
        (self.state >> 16) as u8
    }

    pub fn device_constant_byte(&self) -> u8 {
        (self.state >> 24) as u8
    }

    pub fn step_index_in_ima_range(&self) -> bool {
        self.initial_step_index() <= IMA_STEP_INDEX_MAX
    }
```

Verbatim doc for the `+0x02` width ruling (lines 884–905):

```rust
    /// The field width is settled by the trace's §4b "`+2` is one byte,
    /// not the low half of an int16" subsection: `comedian.amv` cannot
    /// separate the two readings (its byte at `+0x03` is `0x00` in all
    /// 1116 blocks), but `noel-son-lumiere.amv` carries `0xAA` at
    /// `+0x03` in **every** one of its 2928 blocks — under a 16-bit
    /// reading the field would be `0xAA00 + step` (−21936…−21856),
    /// outside the IMA step-index domain `[0, 88]` in every block, which
    /// no valid step index can be. Under the 8-bit reading `+0x02` spans
    /// `0…80` there (`0…88` on comedian, saturating exactly at the
    /// 89-entry IMA table's last index) and `+0x03` is a separate
    /// per-file constant (see [`Self::device_constant_byte`]).
```

and for `+0x03` (lines 911–922, verbatim excerpt):

```rust
    /// Per trace §4b this byte "carries no per-block information in
    /// either sample — it is constant for the whole file and differs
    /// between files, so it reads as an encoder- or device-identifying
    /// byte rather than a codec field. A decoder must ignore it."
    /// Observed values: `0x00` in every `comedian.amv` block, `0xAA` in
    /// every `noel-son-lumiere.amv` block
```

**Preamble offset table (`01wb` payload-relative):**

| off | field | type | notes |
|---|---|---|---|
| +0x00 | `initialPredictor` (raw `state` low 16 bits) | i16 LE | seed; comedian blocks 0–7 = `0, 1, -9, 8, 1, -2, -4, 5` (verbatim doc) |
| +0x02 | `initialStepIndex` | **one byte** u8 | emitted but *never honoured* by the decoder; IMA range `[0, 88]` |
| +0x03 | device-constant byte | u8 | `0x00` in all comedian blocks, `0xAA` in all 2928 noel blocks |
| +0x04 | `decoded_sample_count` | u32 LE | comedian first block = 1837 = 22 050 ÷ 12 |
| +0x08 | compressed nibble body | bytes | see below |

Strict check: `decoded_sample_count > 0` only; `state` is deliberately **not** validated (verbatim: *"The `state` field is intentionally **not** validated"*).

**Nibble packing (verbatim, lines 753–777):**

```rust
    /// Per §4b the `comedian.amv` first audio block carries
    /// `decoded_sample_count = 1837` and a compressed body of `919`
    /// bytes — "1837 mono samples encoded in 919 bytes ≈ 0.5 byte/sample
    /// = 4 bits/sample", an IMA/DVI-ADPCM-style nibble codec where each
    /// mono sample occupies one 4-bit nibble. Two nibbles pack into one
    /// byte, so a block of `n` samples needs `ceil(n / 2)` body bytes —
    /// `ceil(1837 / 2) = 919`, matching the trace's recorded body length
    /// exactly.
    pub fn nibble_body_len(&self) -> u64 {
        (self.decoded_sample_count as u64).div_ceil(2)
    }
```

⚠️ **Not established from the retrieved files:** the *order* of the two nibbles inside a byte (low-nibble-first vs high-nibble-first). parse.rs states only 2 nibbles/byte and `ceil(n/2)` body bytes; no "low nibble"/"nibble order" text exists anywhere in parse.rs, demuxer.rs or muxer.rs (searched). That fact lives in the in-crate ADPCM decoder (`src/adpcm.rs` / `src/adpcm_encode.rs`), which I did **not** retrieve. Do not assert an order from this report.

Other helpers present: `is_consistent_with_frame_interval(samples_per_sec, fps)`, `is_consistent_with_body_len(total_payload_len)`, `body_padding_slack(total_payload_len)`; `IMA_STEP_INDEX_MAX: u8 = 88` (89-entry IMA table, indices `0..=88`).

---

## 6. Full prelude layout 0x00..0x13C

### 6a. As the parser walks it (verbatim, `AmvPrelude::parse`, lines 1535–1624)

```rust
    pub fn parse(slice: &[u8]) -> Result<Self, AmvDemuxerError> {
        // 1. Top-level: RIFF <size> 'AMV ' LIST <size> 'hdrl'.
        require_tag(slice, 0x00, TAG_RIFF, "top-level RIFF")?;
        require_tag(slice, 0x08, AMV_FORM_TYPE, "FORM type 'AMV '")?;
        require_tag(slice, HDRL_OFFSET as usize, TAG_LIST, "hdrl LIST opener")?;
        require_tag(
            slice,
            HDRL_OFFSET as usize + 8,
            TAG_HDRL,
            "hdrl list-type tag",
        )?;

        // 2. `amvh` leaf at AMVH_OFFSET.
        require_tag(slice, AMVH_OFFSET as usize, TAG_AMVH, "amvh leaf")?;
        let amvh_size = read_u32_le(slice, AMVH_OFFSET as usize + 4);
        if amvh_size != AMVH_BODY_LEN { ... "amvh body length must be {AMVH_BODY_LEN}, got {amvh_size}" ... }
        let amvh_body_start = AMVH_OFFSET as usize + 8;
        let amvh_body_end = amvh_body_start + AMVH_BODY_LEN as usize;
        ...
        let header = AmvHeader::parse(&slice[amvh_body_start..amvh_body_end])?;

        // 3a. Video strl (offsets per trace doc §3a):
        //   STRL_VIDEO_OFFSET    = LIST
        //   +0x08                = 'strl'
        //   +0x0C                = 'strh', size = 0x38
        //   +0x14                = strh body (56 bytes, all zero)
        //   +0x4C                = 'strf', size = 0x24
        //   +0x54                = strf body (36 bytes, all zero)
        //   +0x78                = end of video strl
        let v = STRL_VIDEO_OFFSET as usize;
        require_tag(slice, v, TAG_LIST, "video strl LIST")?;
        require_tag(slice, v + 8, TAG_STRL, "video strl type tag")?;
        require_tag(slice, v + 12, TAG_STRH, "video strh leaf")?;
        let v_strh_size = read_u32_le(slice, v + 16);
        ...
        let v_strf_at = v + 20 + STRH_VIDEO_BODY_LEN as usize;
        require_tag(slice, v_strf_at, TAG_STRF, "video strf leaf")?;
        let v_strf_size = read_u32_le(slice, v_strf_at + 4);
        ...
        // 3b. Audio strl — immediately follows the video strl.
        let a = v_strf_at + 8 + STRF_VIDEO_BODY_LEN as usize;
        require_tag(slice, a, TAG_LIST, "audio strl LIST")?;
        require_tag(slice, a + 8, TAG_STRL, "audio strl type tag")?;
        require_tag(slice, a + 12, TAG_STRH, "audio strh leaf")?;
        let a_strh_size = read_u32_le(slice, a + 16);
        ...
        let a_strf_at = a + 20 + STRH_AUDIO_BODY_LEN as usize;
        require_tag(slice, a_strf_at, TAG_STRF, "audio strf leaf")?;
        let a_strf_size = read_u32_le(slice, a_strf_at + 4);
        ...
        let a_strf_body_start = a_strf_at + 8;
        let a_strf_body_end = a_strf_body_start + STRF_AUDIO_BODY_LEN as usize;
        ...
        let audio_format = AmvWaveFormat::parse(&slice[a_strf_body_start..a_strf_body_end])?;

        // 4. `LIST <size> 'movi'` opener — immediately after the
        //    audio strl. `movi` payload starts 4 bytes after the
        //    `movi` FOURCC (LIST size precedes the type tag).
        let movi_list = a_strf_body_end;
        require_tag(slice, movi_list, TAG_LIST, "movi LIST opener")?;
        require_tag(slice, movi_list + 8, TAG_MOVI, "movi list-type tag")?;
        let movi_payload_start = (movi_list + 12) as u64;
```

Module-header doc (verbatim, top of file):

```rust
//! 1. Top-level `RIFF .... 'AMV ' LIST .... 'hdrl'` (§1, FOURCCs at
//!    file offsets `0x00..0x18`).
//! 2. `amvh` body of `0x38` bytes carrying `dwMicroSecPerFrame`, width,
//!    height, fps, and the byte-packed duration (§2).
//! 3. Two `strl` lists (video then audio), each `strh` + `strf`. The
//!    video `strh` / `strf` bodies are all-zero; the audio `strf` is a
//!    20-byte `WAVEFORMATEX` (§3).
//! 4. `movi` payload as a flat alternation of `00dc` (video) / `01wb`
//!    (audio) leaf chunks, **no even-byte alignment** (advance =
//!    `8 + size`), bounded by an `AMV_END_` ASCII trailer (§4).
```

### 6b. As the muxer writes it (verbatim, `muxer.rs::build_prelude_bytes`, definitive byte order)

```rust
/// Build the 0x13C-byte prelude verbatim per §1..§3 of the trace doc.
pub(crate) fn build_prelude_bytes(
    width: u32, height: u32, fps: u32, duration_packed: u32,
    samples_per_sec: u32, channels: u16,
) -> Vec<u8> {
    let mut buf = Vec::with_capacity(0x140);
    // ── §1 Top-level RIFF + FORM ──────────────────────────────────
    buf.extend_from_slice(b"RIFF");
    buf.extend_from_slice(&[0u8; 4]); // RIFF size — zeroed per §1 quirk #1.
    buf.extend_from_slice(&AMV_FORM_TYPE);
    // hdrl LIST opener.
    buf.extend_from_slice(b"LIST");
    buf.extend_from_slice(&[0u8; 4]); // LIST size — zeroed per §1 quirk #1.
    buf.extend_from_slice(b"hdrl");
    // ── §2 amvh leaf ──────────────────────────────────────────────
    buf.extend_from_slice(b"amvh");
    buf.extend_from_slice(&AMVH_BODY_LEN.to_le_bytes());
    let mut amvh_body = vec![0u8; AMVH_BODY_LEN as usize];
    let micros = 1_000_000u32.checked_div(fps).unwrap_or(0);
    amvh_body[0x00..0x04].copy_from_slice(&micros.to_le_bytes());
    amvh_body[0x20..0x24].copy_from_slice(&width.to_le_bytes());
    amvh_body[0x24..0x28].copy_from_slice(&height.to_le_bytes());
    amvh_body[0x28..0x2C].copy_from_slice(&fps.to_le_bytes());
    // Constant `1` flag at +0x2C — matches both observed fixtures.
    amvh_body[0x2C..0x30].copy_from_slice(&1u32.to_le_bytes());
    // Reserved zero at +0x30 (kept implicitly).
    // Packed duration at +0x34.
    amvh_body[0x34..0x38].copy_from_slice(&duration_packed.to_le_bytes());
    buf.extend_from_slice(&amvh_body);
    // ── §3a Video strl: LIST 0 strl strh <0x38 all-zero> strf <0x24 all-zero> ──
    buf.extend_from_slice(b"LIST");
    buf.extend_from_slice(&[0u8; 4]); // strl LIST size — zeroed.
    buf.extend_from_slice(b"strl");
    buf.extend_from_slice(b"strh");
    buf.extend_from_slice(&STRH_VIDEO_BODY_LEN.to_le_bytes());
    buf.extend_from_slice(&vec![0u8; STRH_VIDEO_BODY_LEN as usize]);
    buf.extend_from_slice(b"strf");
    buf.extend_from_slice(&STRF_VIDEO_BODY_LEN.to_le_bytes());
    buf.extend_from_slice(&vec![0u8; STRF_VIDEO_BODY_LEN as usize]);
    // ── §3b Audio strl: LIST 0 strl strh <0x30 all-zero> strf <0x14 WAVEFORMATEX> ──
    ...
    // WAVEFORMATEX layout from §3b:
    //   +0x00 u16 wFormatTag        = 1 (PCM declared; observed convention)
    //   +0x02 u16 nChannels         = `channels` (mono in fixtures)
    //   +0x04 u32 nSamplesPerSec    = `samples_per_sec`
    //   +0x08 u32 nAvgBytesPerSec   = samples_per_sec * 2 (decoded-PCM rate)
    //   +0x0C u16 nBlockAlign       = 2
    //   +0x0E u16 wBitsPerSample    = 16
    //   +0x10 u16 cbSize            = 0
    ...
    // ── §4 movi LIST opener ───────────────────────────────────────
    buf.extend_from_slice(b"LIST");
    buf.extend_from_slice(&[0u8; 4]); // movi LIST size — zeroed.
    buf.extend_from_slice(b"movi");
    debug_assert_eq!(buf.len(), 0x13C);
    buf
}
```

### 6c. Resulting absolute file offsets

| file off | bytes | meaning |
|---|---|---|
| 0x00 | `RIFF` | top-level RIFF |
| 0x04 | u32 = 0 | RIFF size, zeroed (§1 quirk #1) |
| 0x08 | `AMV ` | **FORM type** (`AMV_FORM_TYPE`) |
| 0x0C | `LIST` | `HDRL_OFFSET` |
| 0x10 | u32 = 0 | LIST size, zeroed |
| 0x14 | `hdrl` | list type |
| 0x18 | `amvh` | `AMVH_OFFSET` |
| 0x1C | u32 = 0x38 | `AMVH_BODY_LEN` |
| 0x20–0x57 | 56 B | `amvh` body (fields per §2 table above) |
| 0x58 | `LIST` | `STRL_VIDEO_OFFSET` (= 0x18+8+0x38) |
| 0x5C | u32 = 0 | LIST size |
| 0x60 | `strl` | video strl |
| 0x64 | `strh` | video strh leaf |
| 0x68 | u32 = 0x38 | `STRH_VIDEO_BODY_LEN` |
| 0x6C–0xA3 | 56 B all-zero | video `strh` body |
| 0xA4 | `strf` | video strf leaf |
| 0xA8 | u32 = 0x24 | `STRF_VIDEO_BODY_LEN` |
| 0xAC–0xCF | 36 B all-zero | video `strf` body (no BITMAPINFOHEADER) |
| 0xD0 | `LIST` | audio strl LIST |
| 0xD4 | u32 = 0 | LIST size |
| 0xD8 | `strl` | audio strl |
| 0xDC | `strh` | audio strh leaf |
| 0xE0 | u32 = 0x30 | `STRH_AUDIO_BODY_LEN` |
| 0xE4–0x113 | 48 B all-zero | audio `strh` body |
| 0x114 | `strf` | audio strf leaf |
| 0x118 | u32 = 0x14 | `STRF_AUDIO_BODY_LEN` |
| 0x11C–0x12F | 20 B | audio `WAVEFORMATEX` |
| 0x130 | `LIST` | `movi` LIST opener |
| 0x134 | u32 = 0 | LIST size |
| 0x138 | `movi` | list type |
| **0x13C** | — | **first `movi` leaf chunk** = `movi_payload_start` = `PRELUDE_MIN_LEN` |

Confirmed independently by the test comments/asserts (verbatim): `assert_eq!(prelude.movi_payload_start, 0x13C);`, *"The amvh body begins at file offset 0x20, so the reserved span occupies file 0x24..0x40"*, *"flag_one is at body +0x2C → file 0x4C"*, *"reserved_30 lives at body +0x30 → file 0x50"*, and muxer `AMVH_DURATION_FILE_OFFSET = 0x54`.

---

## 7. `ChunkKind` / `ChunkHeader`, trailer + JPEG-marker constants

Verbatim (lines 564–628):

```rust
/// Identifies which leaf-chunk tag was seen.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ChunkKind {
    /// `00dc` — video frame (JPEG SOI..EOI, tables stripped).
    Video,
    /// `01wb` — audio block (8-byte preamble + ~4-bit ADPCM body).
    Audio,
    /// Anything else encountered inside `movi`. Real AMV files never
    /// produce this in practice (only `00dc` and `01wb` are observed)
    /// but the parser tolerates it so a partial / unexpected chunk
    /// surfaces as data rather than aborting the walk.
    Other([u8; 4]),
}

impl ChunkKind {
    /// Classify a 4-byte FOURCC.
    pub fn classify(tag: [u8; 4]) -> Self {
        if tag == VIDEO_CHUNK_TAG {
            Self::Video
        } else if tag == AUDIO_CHUNK_TAG {
            Self::Audio
        } else {
            Self::Other(tag)
        }
    }
}

/// 8-byte leaf-chunk header (`FOURCC` + 4-byte little-endian size).
/// **Critically**, AMV does NOT pad chunks to an even byte boundary —
/// consumers walk by exactly `8 + size` bytes per chunk, even when
/// `size` is odd (§4 "Chunk framing and the no-padding rule").
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct ChunkHeader {
    /// Raw 4-byte tag (`b"00dc"` / `b"01wb"` / …).
    pub tag: [u8; 4],
    /// Body size in bytes. Excludes this 8-byte header.
    pub size: u32,
}

impl ChunkHeader {
    pub fn parse(slice: &[u8]) -> Result<Self, AmvDemuxerError> {
        if slice.len() < 8 { ... "chunk header needs 8 bytes, got {}" ... }
        let mut tag = [0u8; 4];
        tag.copy_from_slice(&slice[0..4]);
        let size = read_u32_le(slice, 4);
        Ok(Self { tag, size })
    }

    /// Number of bytes the cursor must advance to land on the next
    /// chunk: exactly `8 + size` (no even-byte padding — §4).
    pub fn advance_total(&self) -> u64 {
        8 + self.size as u64
    }

    pub fn kind(&self) -> ChunkKind {
        ChunkKind::classify(self.tag)
    }
}
```

JPEG markers (verbatim, lines 630–637):

```rust
/// JPEG Start-of-Image marker — the two bytes every `00dc` video chunk
/// payload begins with (§4a "self-contained JPEG bracketed by SOI…EOI").
pub const JPEG_SOI: [u8; 2] = [0xFF, 0xD8];

/// JPEG End-of-Image marker — the two bytes every `00dc` video chunk
/// payload ends with (§4a). The trace records both observed device
/// profiles' first frames hold SOI at offset 0 and EOI at `size - 2`.
pub const JPEG_EOI: [u8; 2] = [0xFF, 0xD9];
```

Trailer handling in **demuxer.rs** (verbatim, from the module header + `build_chunk_index`):

```rust
//! 4. On any non-`00dc` / non-`01wb` tag — most importantly the
//!    `AMV_END_` ASCII trailer that bounds the file — returns
//!    [`Error::Eof`] for subsequent calls. The trailer literally
//!    occupies the 8 bytes a header would, so a `read_exact` of the
//!    next "chunk header" lands on it cleanly.
...
            let mut header_bytes = [0u8; 8];
            if self.reader.read_exact(&mut header_bytes).is_err() { ... break; }
            if header_bytes == AMV_END_TRAILER {
                break;
            }
```

Real-fixture pin (verbatim, demuxer.rs test): comedian first video chunk is `1633` bytes, `AMV_END_` trailer at file offset `0x348E31`, file length `0x348E39` (= trailer is the final 8 bytes), 1116 `00dc` + 1116 `01wb`.

Also in parse.rs: `validate_video_payload_shape`, `validate_video_payload_no_internal_markers`, `validate_movi_interleave`, `MoviPayload` (`Video{chunk_offset, body}` / `Audio{chunk_offset, preamble, body}` / `Other{chunk_offset, tag, body}`), `MoviPayloadIter`. Parser helpers: `read_u16_le`, `read_u32_le`, `require_all_zero`, `require_tag`.

---

## 8. "AMVV" / "AMVA" — definitive result for what was retrievable

**Case-sensitive search (PowerShell `-CaseSensitive`) results:**

| file | coverage | `AMVV` | `AMVA` |
|---|---|---|---|
| `src/parse.rs` | lines 1–2299 (all non-test code + first 600 lines of `mod tests`) | **0** | **0** |
| `src/lib.rs` | 100 % | **0** | **0** |
| `src/muxer.rs` | 100 % | **0** | **0** |
| `src/rate.rs` | 100 % | **0** | **0** |
| `src/demuxer.rs` | first 100 602 B | **0** | **0** |

(An earlier case-*insensitive* count of 10 "AMVA" hits in parse.rs was an artefact: it matched the identifier `AmvAudioPreamble`/`AmvAudioDecoder`. Under `-CaseSensitive` the count is 0. Similarly `AMV[A-Z]` matches in the spills are all `AMVH`/`AMVV`-free identifiers like `AMVH_BODY_LEN`, `AMV_END_TRAILER`, `AmvRateController`.)

⚠️ **Not verified:** `src/parse.rs` lines **2300 → EOF** (~58 KB, entirely inside `pub(crate) mod tests` declared at line 1701) could not be retrieved — see §0. So strictly: *"AMVV"/"AMVA" do not appear in parse.rs lines 1–2299 nor in the complete lib.rs/muxer.rs/rate.rs."* I cannot make the absolute claim for the unretrieved parse.rs tail.

Independent (inference, clearly flagged as **not a quote**): no `AMVV`/`AMVA` constant could be live in that tail without being re-exported — `lib.rs`'s `pub use parse::{...}` list is complete and contains only `AmvAudioPreamble, AmvDuration, AmvHeader, AmvWaveFormat, ChunkHeader, ChunkKind, DurationConsistency, MoviPayload, MoviPayloadIter, AMVH_BODY_LEN, AMV_AUDIO_PREAMBLE_LEN, AMV_END_TRAILER, AMV_FORM_TYPE, AUDIO_CHUNK_TAG, IMA_STEP_INDEX_MAX, JPEG_EOI, JPEG_SOI, VIDEO_CHUNK_TAG` plus the three validators. And the muxer writes `AMV_FORM_TYPE` = `b"AMV "`, the demuxer requires `b"AMV "`, and `tests/container_amv.rs` (workspace, retrieved in full) asserts `assert_eq!(&out[8..12], b"AMV ", "{name}");`. So `AMVV`/`AMVA` cannot be the FORM type. The nine-hypothesis check therefore reduces to: **no evidence of `AMVV`/`AMVA` anywhere retrievable; the only tags in the format are `AMV `, `AMV_END_`, `00dc`, `01wb`, `RIFF`/`LIST`/`hdrl`/`amvh`/`strl`/`strh`/`strf`/`movi`.**

---

## 9. The trace document `docs/container/amv/amv-container-trace.md`

| check | result |
|---|---|
| `https://github.com/OxideAV/oxideav-workspace/blob/master/docs/container/amv/amv-container-trace.md` | **NOT observable directly** — `github.com` HTML is unreachable from this sandbox (`TypeError: fetch failed`, same as `raw.githubusercontent.com`). Not a 404. |
| `https://raw.githubusercontent.com/.../amv-container-trace.md` | **NOT observable** — same network failure. |
| `https://api.github.com/repos/OxideAV/oxideav-workspace/contents/docs/container/amv/amv-container-trace.md` | **HTTP 404** `{"message":"Not Found","documentation_url":"https://docs.github.com/rest/repos/contents#get-repository-content","status":"404"}` |
| `https://api.github.com/repos/OxideAV/oxideav-workspace/git/trees/master?recursive=1` | HTTP 200, `"truncated":false` — **there is NO `docs/` directory at all** in the repo (no `docs/…` path of any kind). Top level = `.github/`, `.gitignore`, `Cargo.lock`, `Cargo.toml`, `LICENSE`, `README.md`, `crates/`, `play.sh`, `run.sh`, `scripts/`. |
| `https://api.github.com/repos/OxideAV/docs` | **HTTP 404** (private or nonexistent — GitHub returns 404 for private repos to unauthenticated callers) |
| `https://api.github.com/repos/OxideAV/opendocs/git/trees/master?recursive=1` | HTTP 200 — public "opendocs" repo contains **only MagicYUV docs** (`codecs/magicyuv/*`); **no AMV trace**. |

**Why it 404s — verbatim from `oxideav-workspace/.gitignore` (retrieved in full):**

```
# Reference materials (OxideAV/docs + OxideAV/opendocs). `docs` is the
# internal/private trove (specs, traces, fixtures); `opendocs` is the
# public-facing documentation site. Both cloned here on demand by
# scripts/update-crates.sh — not tracked by the workspace repo.
/docs
/opendocs
```

**Conclusion: the trace document is NOT publicly accessible.** It is an untracked, gitignored clone of the **private `OxideAV/docs` trove**; the path is cited by every file in `oxideav-amv` (and by the `lib.rs`/`muxer.rs` doc comments) but has never been committed to a public repo. Note the relative link goes live only in a working tree where a maintainer has run `scripts/update-crates.sh` / staged the private clone.

### Docs inside the `oxideav-amv` repo itself

`https://api.github.com/repos/OxideAV/oxideav-amv/git/trees/master?recursive=1` → HTTP 200, `"truncated":false`, 44 entries. **There is no `docs/` directory and no trace file inside `oxideav-amv`.** Full tree:

```
.github/workflows/{ci.yml,release-plz.yml}  .gitignore  CHANGELOG.md  Cargo.toml  LICENSE  README.md
benches/{build_index.rs,common/mod.rs,demux_drain.rs,indexed_seek.rs,mux_save…,mux_write.rs,rate_control_encode.rs}
fuzz/{.gitignore,Cargo.toml,fuzz_targets/{codec_decode.rs,demuxer_open.rs,parse.rs}}
src/{adpcm.rs,adpcm_encode.rs,codec_audio.rs,codec_video.rs,demuxer.rs,jpeg_decode.rs,jpeg_encode.rs,
     jpeg_reconstruct.rs,lib.rs,muxer.rs,parse.rs,rate.rs,video.rs}
tests/{decode_audio_pcm.rs,decode_to_pixels.rs,device_profile_matrix.rs,encode_roundtrip.rs,noel_codec.rs,
       noel_encode_roundtrip.rs,noel_profile.rs,rate_control.rs,registry_codec_path.rs}
```
(No `docs/`, no `docs/container/amv/`, no `*.md` other than `README.md` and `CHANGELOG.md`.) The single `README.md` is the only Markdown in the repo that ships, and the AMV fixture files it refers to (`docs/container/amv/fixtures/comedian.amv`, `noel-son-lumiere.amv`) are likewise absent — `tests/container_amv.rs` in `oxideav-workspace` explicitly says *"Skips when `docs/container/amv/fixtures/` is not staged."*

---

## 10. Things I could NOT retrieve (explicit list)

1. `src/parse.rs` lines 2300 → EOF (~58 KB, tail of `#[cfg(test)] mod tests`) — blocked by the ~101 KB fetch cap; unreachable via any whole-file URL.
2. `raw.githubusercontent.com` / `github.com` / `raw.githack.com` / `cdn.statically.io` — network-unreachable from this sandbox (each retried once).
3. `README.md` of `oxideav-amv` as **plain text** — `cdn.jsdelivr.net/gh/.../README.md` answers with a cross-origin redirect to `raw.githubusercontent.com` (blocked). It *is* obtainable as base64 through `https://api.github.com/repos/OxideAV/oxideav-amv/contents/README.md` (HTTP 200, `"encoding":"base64"`, `size:26034`, blob `7d03da45…`) — decode that if the plain text is needed.
4. The within-byte nibble order for `01wb` ADPCM bodies (lives in `src/adpcm.rs`, not retrieved).
5. `grep.app` code search (`https://grep.app/api/search?q=AMVV&filter[repo][0]=OxideAV/oxideav-amv`) — **HTTP 429 Vercel Security Checkpoint**, unavailable, so it could not be used as a whole-repo string index.
