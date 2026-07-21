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

### Chunked framing negotiation

CHord parses the server capabilities, resolves them with the same rules as
`Connection::connect`, and requests `notchunked` for both channels in the addendum. Chunked
framing itself is not implemented until Phase 4, so a server that strictly requires `chunked`
(non default `proto_caps` configuration) is refused with a clear error instead of a
desynchronised stream. Servers with default or optional capabilities work.

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
| 1 | Query | Planned, Phase 2 |
| 2 | Data | Planned, Phase 2 (external tables) and 3 (INSERT) |
| 3 | Cancel | Planned, Phase 5 |
| 4 | Ping | Implemented and tested |
| 5 | TablesStatusRequest | Planned, after Phase 5 |
| 6 | KeepAlive | Not planned until a concrete need exists |
| 7 | Scalar | Planned, Phase 2 |
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
| 1 | Data | Planned, Phase 2 |
| 2 | Exception | Implemented and tested |
| 3 | Progress | Implemented and tested (body decode; accumulation API in Phase 2) |
| 4 | Pong | Implemented and tested |
| 5 | EndOfStream | Planned, Phase 2 |
| 6 | ProfileInfo | Planned, Phase 4 |
| 7 | Totals | Planned, Phase 4 |
| 8 | Extremes | Planned, Phase 4 |
| 9 | TablesStatusResponse | Planned, after Phase 5 |
| 10 | Log | Planned, Phase 4 |
| 11 | TableColumns | Planned, Phase 3 |
| 12 | PartUUIDs | Obsolete upstream; recognised, treated as a protocol error |
| 13 | ReadTaskRequest | Recognised, treated as a protocol error (not an external client packet) |
| 14 | ProfileEvents | Planned, Phase 4 |
| 15 | MergeTreeAllRangesAnnouncement | Recognised, treated as a protocol error (inter server) |
| 16 | MergeTreeReadTaskRequest | Recognised, treated as a protocol error (inter server) |
| 17 | TimezoneUpdate | Planned, Phase 3 |
| 18 | SSHChallenge | Planned with SSH authentication, after Phase 5 |

Unknown packet identifiers are never skipped: the stream position after one is unknowable, so
CHord raises `ChordProtocolException` and the connection is closed, never reused.

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
