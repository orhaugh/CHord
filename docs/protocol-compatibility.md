# Protocol compatibility

This document is the authoritative statement of what CHord implements on the ClickHouse native
TCP protocol. If it is not marked implemented and tested here, CHord does not support it,
whatever any other text implies.

## Revision, not version

A **protocol revision** (`DBMS_TCP_PROTOCOL_VERSION`, values like 54488) increments only when the
wire format changes. A **server version** (like 26.3.2.5) is a release of the server. Several
server releases can share one revision. Never treat the two as the same concept.

Both sides exchange revisions during the handshake and every conditionally present field is gated
on the **negotiated revision**: the minimum of what the client advertised and what the server
reported. CHord evaluates every gate through the `ProtocolFeature` registry in `chord-protocol`.

## Reference points (verified 2026-07-21 against the ClickHouse sources)

| ClickHouse release | `DBMS_TCP_PROTOCOL_VERSION` |
|---|---|
| 25.8 (LTS) | 54479 |
| 26.3 (LTS) | 54484 |
| 26.6 | 54485 |
| master | 54488 |

- CHord advertises revision **54488**.
- CHord refuses servers below revision **54458** (`DBMS_MIN_PROTOCOL_VERSION_WITH_ADDENDUM`);
  older handshakes have a structurally different shape that CHord neither implements nor tests.
- Support claims are limited to server releases exercised in CI: **25.8, 26.3, 26.6** and the
  newest builds in the nightly sweep. Servers between revision 54458 and the oldest tested
  release generally negotiate correctly through the same gates, but they are untested and
  therefore unsupported.

## Handshake structures (implemented and tested)

Field order and gating mirror `Connection::sendHello`, `Connection::receiveHello`,
`Connection::sendAddendum` and `TCPHandler::sendHello`. Every row below has byte level golden
tests in `chord-protocol` and runs against real servers in integration tests.

| Structure | Contents | Status |
|---|---|---|
| ClientHello | client name, version, advertised revision, database, username, password; ASCII control characters rejected, reserved username markers rejected | Write: yes |
| ServerHello | server name, version, revision, then revision gated fields listed below | Read: yes |
| Client addendum | quota key (54458), resolved chunked framing per channel (54470), parallel replicas protocol version (54471) | Write: yes |
| Exception packet | little endian Int32 code, name, message, stack trace, obsolete nested flag with bounded chain consumption | Read: yes |
| Progress packet | read rows and bytes, total rows (always); total bytes (54463); written rows and bytes (54420); elapsed nanoseconds (54460) | Read: yes |
| Ping and Pong | ping request, pong response, stale Progress packets tolerated before Pong | Yes |

ServerHello revision gated fields, in wire order:

| Revision | Feature constant (`DBMS_MIN_...`) | Field | Status |
|---|---|---|---|
| 54471 | `REVISION_WITH_VERSIONED_PARALLEL_REPLICAS_PROTOCOL` | parallel replicas protocol version (VarUInt) | Read: yes |
| 54058 | `REVISION_WITH_SERVER_TIMEZONE` | server timezone (string, max 4096) | Read: yes |
| 54372 | `REVISION_WITH_SERVER_DISPLAY_NAME` | display name (string, max 4096) | Read: yes |
| 54401 | `REVISION_WITH_VERSION_PATCH` | patch version (VarUInt) | Read: yes |
| 54470 | `PROTOCOL_VERSION_WITH_CHUNKED_PACKETS` | send and receive framing capabilities (strings) | Read: yes |
| 54461 | `PROTOCOL_VERSION_WITH_PASSWORD_COMPLEXITY_RULES` | rule count (max 256) and rules (strings, max 4096 each) | Read: yes |
| 54462 | `REVISION_WITH_INTERSERVER_SECRET_V2` | nonce (fixed 8 byte little endian) | Read: yes (retained, never used for authentication) |
| 54474 | `REVISION_WITH_SERVER_SETTINGS` | settings block in STRINGS_WITH_FLAGS format, empty name terminated | Read: yes (exposed, not yet applied to queries) |
| 54477 | `REVISION_WITH_QUERY_PLAN_SERIALIZATION` | query plan serialisation version (VarUInt) | Read: yes |
| 54479 | `REVISION_WITH_VERSIONED_CLUSTER_FUNCTION_PROTOCOL` | cluster function protocol version (VarUInt) | Read: yes |

The string and count caps mirror the server's own `DBMS_MAX_HELLO_STRING_SIZE` (4096) and
`DBMS_MAX_PASSWORD_COMPLEXITY_RULES` (256).

### Chunked framing (implemented and tested)

CHord parses the server capabilities and resolves them with the same rules as
`Connection::connect`, preferring `notchunked` but adopting `chunked` per channel when the server
requires it. The resolved framing is reported in the addendum and takes effect strictly after the
addendum, mirroring `TCPHandler`. Servers with default, optional and strict `proto_caps`
configurations all work, in every combination of directions.

On a chunked channel every protocol packet travels as one or more chunks of
`[little endian u32 length][payload]` closed by a zero length terminator. A zero length chunk at
the start of a message is a protocol violation on both sides. CHord emits each chunk header, its
payload and, at packet end, the terminator in a single transport write: the server completes a
chunk header that arrived partially with one further read and treats a short result as a clean
end of stream, so headers must never travel as lone TCP segments. This is an interoperability
requirement verified by unit tests on write boundaries and by integration tests against strict
chunked servers.

### Compression (implemented and tested)

When compression is enabled on a connection (`ConnectionOptions.compression`) or for a single
query (`QueryRequest.compression`), the Query packet sets the compression flag and block bodies
travel as ClickHouse compressed frames:

`[16 byte CityHash128 v1.0.2 checksum][method byte][compressed size u32][decompressed size u32][payload]`

- Methods: `NONE` (0x02), `LZ4` (0x82), `LZ4HC` (same byte, higher encoder effort), `ZSTD`
  (0x90). Unknown method bytes fail explicitly.
- The checksum covers the 9 byte header plus payload and is validated before decompression; the
  declared decompressed size must match exactly. Mismatches raise
  `ChordDataCorruptionException` and poison the connection.
- Declared sizes are validated against `CompressionLimits` (1 GiB defaults, matching the server's
  `DBMS_MAX_COMPRESSED_SIZE`) before any allocation, bounding hostile input.
- Frames align to packet boundaries. Packet identifiers and the Data, Log, ProfileEvents and
  TableColumns tag strings always travel raw; only block bodies are framed.
- Data, Totals and Extremes bodies are compressed at every supported revision when the query
  flag is set. Log, ProfileEvents and TableColumns bodies are compressed only from revision
  54481 (`PROTOCOL_VERSION_WITH_COMPRESSED_LOGS_PROFILE_EVENTS_COLUMNS`); below it they stay
  raw on an otherwise compressed exchange. TableColumns carries both of its strings on the
  compressed stream.
- Compression and chunked framing compose: frames travel inside chunks.

The checksum implementation is a pure Java port of CityHash 1.0.2 (the exact historical version
ClickHouse pins), cross validated against frames produced by the real `clickhouse-compressor`.

### Deliberate strictness deviations

- VarUInt decoding accepts non minimal encodings (as the server does) but rejects a tenth byte
  that sets the continuation bit or carries bits beyond bit 63; a compliant writer cannot produce
  either, and accepting them would silently lose data or desynchronise framing.
- Server settings entries are capped (name 4096 bytes, value bounded by the configured wire
  limits, 10000 entries by default) where the server itself reads unbounded strings; the caps are
  configurable through `WireLimits`.

## Packet coverage

Client packets (`Protocol::Client`):

| Code | Packet | Status |
|---|---|---|
| 0 | Hello | Implemented and tested |
| 1 | Query | Implemented and tested: query id, full ClientInfo at revision 54488, string settings, external roles placeholder, stage, compression flag, query text, parameters |
| 2 | Data | Implemented and tested: INSERT payload blocks for every writable type, the terminal empty block, and the empty external tables terminator after SELECT queries |
| 3 | Cancel | Planned, Phase 5 |
| 4 | Ping | Implemented and tested |
| 5 | TablesStatusRequest | Planned, after Phase 5 |
| 6 | KeepAlive | Not planned until a concrete need exists |
| 7 | Scalar | Not planned until scalar subqueries need client side data |
| 8 | IgnoredPartUUIDs | Obsolete upstream; not planned |
| 9 | ReadTaskResponse | Cluster function coordination; not planned |
| 10 | MergeTreeReadTaskResponse | Parallel replicas coordination; not planned |
| 11, 12 | SSHChallengeRequest and Response | Planned with SSH authentication, after Phase 5 |
| 13 | QueryPlan | Inter server concern; not planned |
| 14 | MergeTreeAllRangesAnnouncementResponse | Parallel replicas coordination; not planned |

Server packets (`Protocol::Server`):

| Code | Packet | Status |
|---|---|---|
| 0 | Hello | Implemented and tested |
| 1 | Data | Implemented and tested: native blocks raw and compressed, all Phase 2 types, header and multi block streams |
| 2 | Exception | Implemented and tested |
| 3 | Progress | Implemented and tested, including accumulation on query results |
| 4 | Pong | Implemented and tested |
| 5 | EndOfStream | Implemented and tested |
| 6 | ProfileInfo | Implemented and tested, including the rows before aggregation fields (54469) |
| 7 | Totals | Implemented and tested |
| 8 | Extremes | Implemented and tested |
| 9 | TablesStatusResponse | Planned, after Phase 5 |
| 10 | Log | Implemented and tested: consumed, bounded and logged, raw and compressed (54481) |
| 11 | TableColumns | Implemented and tested: default expressions metadata consumed before the INSERT schema block, raw and compressed (54481, both strings on the compressed stream) |
| 12 | PartUUIDs | Obsolete upstream; recognised, treated as a protocol error |
| 13 | ReadTaskRequest | Recognised, treated as a protocol error (not an external client packet) |
| 14 | ProfileEvents | Implemented and tested: accumulated into the typed `QueryResult.profileEvents()` counters, raw and compressed (54481) |
| 15 | MergeTreeAllRangesAnnouncement | Recognised, treated as a protocol error (inter server) |
| 16 | MergeTreeReadTaskRequest | Recognised, treated as a protocol error (inter server) |
| 17 | TimezoneUpdate | Implemented and applied to the session timezone |
| 18 | SSHChallenge | Planned with SSH authentication, after Phase 5 |

Unknown packet identifiers are never skipped: the stream position after one is unknowable, so
CHord raises `ChordProtocolException` and the connection is closed, never reused.

## Query and block structures (implemented and tested)

| Structure | Contents | Status |
|---|---|---|
| ClientInfo | every field to revision 54488, including quota key, distributed depth, OpenTelemetry flag, parallel replicas triple, script numbers, JWT flag, client agent, internal flag and current roles flag | Write: yes, byte level golden tests at revisions 54470 and 54488 |
| BlockInfo | field framed metadata: overflow flag, bucket number, out of order buckets (54480), unknown fields poison the connection | Read and write: yes |
| Native block | column and row counts, per column name, type name, custom serialisation flag (54454), column data | Read and write: yes for all supported types, round trip tested; custom or sparse serialisation is rejected explicitly until Phase 6 |
| Query settings | STRINGS_WITH_FLAGS name and string value pairs, empty name terminated | Write: yes |
| Query parameters | custom flagged settings entries carrying quoted field dumps (54459) | Write: yes |
| ProfileInfo | rows, blocks, bytes, limit flags, rows before limit and aggregation (54469) | Read: yes |

| INSERT flow | Query with pending data (no external tables terminator), TableColumns then schema header from the server, streamed Data blocks, terminal empty block, drain of Progress, Log and ProfileEvents to EndOfStream | Implemented and tested, including async insert settings; closing without finish hard aborts the connection so partial data is never committed implicitly |

Type coverage is tracked per type in [type-support.md](type-support.md).

## Feature gate registry

The complete registry of revision gates known to CHord, mirroring `ProtocolDefines.h` at master
revision 54488, lives in code as `io.github.orhaugh.chord.protocol.ProtocolFeature` with one
enum constant per gate and Javadoc describing what each gates. A gate being present in the
registry means CHord recognises it, not that the gated feature is implemented; implementation
status is this document.

## Maintenance rules

- Any change to handshake, packet or codec behaviour must update this document in the same
  change.
- New upstream revisions are adopted by re reading `ProtocolDefines.h`, `Protocol.h`,
  `Connection.cpp` and `TCPHandler.cpp`, extending `ProtocolFeature`, and only then raising the
  advertised revision, with golden tests for every new field.
- The nightly compatibility workflow runs the integration suite against every supported server
  release and the newest builds, so upstream wire changes surface as failures, not surprises.
