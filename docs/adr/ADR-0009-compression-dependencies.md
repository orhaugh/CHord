# ADR-0009: Compression dependencies

Status: Accepted (2026-07-21), implementation lands in Phase 4

## Context

The native protocol compresses payloads in ClickHouse specific frames: a CityHash 1.0.2 checksum
over a header plus compressed body, then the codec byte, sizes and data. This framing is not the
LZ4 frame format, not HTTP content encoding and not the MergeTree on disk codec layout, and Java
has no drop in implementation of the checksum variant.

## Decision

- LZ4 block compression and decompression: `org.lz4:lz4-java` (mature, block API matches the
  frame layout; LZ4HC is an encoder mode of the same codec byte).
- ZSTD: `com.github.luben:zstd-jni` (the de facto Java binding, streaming capable).
- CityHash 1.0.2: implemented inside `chord-codec` in pure Java from the original CityHash
  reference sources (MIT licensed) and validated with golden vectors captured from ClickHouse
  traffic. No dependency exists that implements the exact variant ClickHouse froze.
- Both compression libraries are dependencies of `chord-codec` only. Wire safety rules apply:
  checksum validation before decompression, frame size and ratio caps, corrupt frames poison the
  connection.

## Alternatives

- `aircompressor` (pure Java LZ4 and ZSTD): attractive for a no native dependency build; parked
  as a potential alternative behind the codec SPI, pending benchmarks in Phase 4.
- Shipping without ZSTD: rejected; servers negotiate it routinely.

## Consequences

- `zstd-jni` brings native binaries for common platforms; platform coverage is documented and
  the NONE and LZ4 paths keep working where it is unavailable.
- The CityHash implementation carries its own golden tests independent of compression.
