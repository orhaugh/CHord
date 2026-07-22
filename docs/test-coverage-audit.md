# Test coverage audit

Date: 2026-07-22, at commit c72e13e (0.1.0 staged, unpublished). Method: JaCoCo line and branch
coverage across the full unit plus integration suite, a file by file catalogue of every test
class (assertion depth, edge values, failure modes), and a gap analysis against the feature
surface a production ClickHouse user exercises. This document is the burn down list; a row
leaves it only when a test lands.

## Quantitative snapshot

316 test executions: 248 unit (including 7 jqwik properties and 3 golden suites built from
real server bytes) and 68 integration tests, the latter swept across ClickHouse 25.8, 26.3
and 26.6 plus the newest nightly builds, on JDK 21, 23 and 25.

| Module | Line coverage | Branch coverage |
|---|---|---|
| chord-protocol | 88.1% | 79.2% |
| chord-transport | 81.7% | 68.2% |
| chord-codec | 85.1% | 73.0% |
| chord-client | 85.9% | 72.2% |
| chord-jdbc | 41.0% | 41.4% |
| chord-observability | 100% | n/a |

Coldest classes: codec `Defaults` 11% (sparse materialisation defaults), client
`NativeInsertStream` 60%, transport `PollBufferInputStream` 62%, and most of chord-jdbc
(`ChordResultSet` 34%, `ChordDatabaseMetaData` 35%, `ChordConnection` 30%, `JdbcTypes` 30%,
`ChordParameterMetaData` 0%). Part of the JDBC number is honest `SQLFeatureNotSupportedException`
boilerplate, but far from all of it.

## What can be trusted today

- The wire layer: byte exact goldens for VarInt, primitives, ClientHello, ServerHello,
  addendum and exceptions; hostile input for truncation, oversized declarations, tenth byte
  VarUInt overflow, unknown packets; 2700 jqwik property executions over the codecs.
- Chunked framing: byte grouping asserted per write (the interoperability property servers
  depend on), plus strict chunked servers end to end.
- Compression: goldens from the real `clickhouse-compressor`, corruption, truncation, bomb
  limits, every method swept both directions against real servers.
- Query streaming: one million rows verified row by row, mid stream and pre stream server
  errors with connection reuse proven, parameters injection safe, one differential case
  against the official client.
- INSERT happy paths: 24 column type round trip, multi block, async settings, constraint
  violations, abort without finish semantics.
- Pooling and failover mechanics at the unit level, plus a 12 thread real server workload.
- TLS trust and mTLS matrixes including expired certificates, PKCS1 and encrypted PKCS8
  diagnostics; a reflection guard that no trust-all switch exists.
- Advanced serialisations against golden files from a real server and live servers.

## Gap register

### P0: could hide a correctness or availability bug in a common production path

All ten P0 gaps were closed on 2026-07-22, the day the audit was written. Burning them down
surfaced and fixed two real defects: sparse IPv4 and IPv6 columns could not materialise at all,
because `Defaults` produced a `String` and a bare byte array where the IP appenders require
`InetAddress` values. Every other path proved correct once exercised.

| # | Gap | Closed by |
|---|---|---|
| P0-1 | Float NaN, Infinity, -0.0 untested | `TypeEdgeValuesTest` float suites, `TypeEdgeIT.floatSpecialValuesRoundTripAndArriveFromServerExpressions`, `JdbcIT.floatSpecialValuesSurfaceThroughTheGetters` |
| P0-2 | DateTime64 only at precision 3 | `TypeEdgeValuesTest.dateTime64RoundTripsAtEveryPrecisionExtreme` (+ sub tick rejection), `TypeEdgeIT.dateTime64PrecisionExtremesRoundTrip` |
| P0-3 | Server death mid INSERT never exercised | `FaultInjectionTest.connectionLostBeforeInsertFinishHasUnknownOutcome`, `connectionLostWhileStreamingInsertBlocksHasUnknownOutcome` (OUTCOME_UNKNOWN + BROKEN asserted) |
| P0-4 | Mid SELECT stream socket kill never exercised | `FaultInjectionTest.socketDeathMidSelectStreamIsATypedFailureNeverAShortResult` (RETRY_ONLY_IF_IDEMPOTENT + BROKEN asserted) |
| P0-5 | Cross thread cancel untested | `CancelIT.cancelFromAnotherThreadUnblocksTheConsumer` |
| P0-6 | Sparse defaults 11% covered; IP defaults broken | `SparseDecodeTest.everyTypeFamilyMaterialisesItsDefaultsAroundSparseValues` (22 families), `sparseDecimalsMaterialiseZeroDefaults`, `sparseIpColumnsMaterialiseZeroAddressDefaults`, `sparseEnumWithoutAZeroLabelFailsExplicitly`, `AdvancedTypesIT.sparseColumnsMaterialiseAcrossTypeFamilies` (server verified Sparse kind per column); fix in `Defaults.zeroAddress` |
| P0-7 | LC wide dictionaries and non String keys | `LowCardinalityRoundTripTest.dictionariesBeyondSixteenBitsUseFourByteIndexes`, `AdvancedTypesIT.lowCardinalityWideDictionariesCrossTheFourByteIndexBoundary` (70000 distinct, both directions), `lowCardinalityDictionaryKeyTypesBeyondStringRoundTrip` (Date, FixedString, UInt16) |
| P0-8 | Deadline polling unproven over TLS | `TlsHandshakeIT.queryTimeoutsCancelCleanlyOverTls` |
| P0-9 | BlockLimits caps never routed through decode | `BlockLimitsEnforcementTest` (string value cap, per column payload cap, array element cap, type name length, type depth, dimensions with custom limits) |
| P0-10 | Missing widths and boundary values | `TypeEdgeValuesTest.integerWidthsRoundTripTheirBoundaryValues` (+ rejection cases, dates, Decimal32, Enum16), `TypeEdgeIT.integerDateDecimalAndEnumBoundariesRoundTrip` (native write + server literals) |

### P1: hardening a production adopter would expect

Types and codec
- Interval values only tested for Day; the other ten kinds parse but never carry values.
- Date and Date32 boundary values (0, 65535 days; full Date32 range) untested.
- Decimal scale extremes and Decimal256 read side; Decimal128 write side.
- Variant compact discriminator mode implemented but never decoded in a test.
- Serializations guard branches untested: LC global dictionary rejection, missing additional
  keys, unknown index width, index beyond dictionary, Variant discriminator beyond variants,
  unknown compact granule, Dynamic/JSON unsupported version throws, MAX_DYNAMIC_TYPES and
  MAX_DYNAMIC_PATHS caps.
- Dynamic/JSON V2 prefixes covered only implicitly by ITs; no unit vectors (goldens are V1
  file layout). Shared variant raw access and JsonTypeArguments (69%) lightly covered.
- `ProfileInfo.read` has no test at all; the below 54469 gate branch never runs on any tested
  server. `Progress.read` has no truncation case.
- Compression.checkLevel error bounds (LZ4HC 1..12, ZSTD 1..22) untested.

Client and resilience
- Connect timeout never driven anywhere; read timeout only at transport unit level.
- Pool idleTimeout eviction untested; concurrent close() racing active leases untested.
- Failover RANDOM policy and maxBackoff untested; DNS re resolution claim untested; a failed
  over connection is only ever pinged, never queried.
- Retry classification asserted only for SAFE_TO_RETRY at the client level; phase stamping
  (query mid stream RETRY_ONLY_IF_IDEMPOTENT and insert SAFE_TO_RETRY sites) untested.
- Progress and log listeners never fire on the INSERT path (notifyProgress, notifyLogs,
  accumulate: zero lines run).
- Hostile chunk header (0xFFFFFFFF declared length) untested.
- TLS protocol and cipher pinning never applied in a real handshake; not yet valid
  certificate direction untested.
- Empty INSERT (finish with zero blocks) undefined by test.
- Query id round trip to system.query_log unverified.
- Differential testing against clickhouse-client limited to one SELECT case.

JDBC (largest block; drives the 41% number)
- ResultSet getters never called: getBoolean, getByte, getShort, getFloat, getBytes,
  getTimestamp (write side exists, read back never), getBigDecimal(int), all direct index
  variants, getObject(Class) for everything except List, wasNullOr null path,
  findColumn case insensitive fallback, cursor state errors (24000, 07009, 42S22).
- PreparedStatement: only setInt/setString/setTimestamp ever used; unbound parameter 07002,
  clearParameters, setObject/setArray/setNull and friends, single row native executeUpdate()
  path, multiple sequential batches, clearBatch, batch after error, addBatch refusal on non
  INSERT shapes, ParameterMetaData (0%).
- Statement: getMoreResults, closeOnCompletion, getUpdateCount transitions, cancel() direct.
- DatabaseMetaData: getCatalogs, getTypeInfo, getFunctions, getTableTypes, VIEW and SYSTEM
  TABLE classification, types[] filter, capability flags beyond supportsTransactions.
- ResultSetMetaData: getColumnClassName (JdbcTypes.javaClassName never runs), isSigned,
  getPrecision, unsigned type mappings (SMALLINT/INTEGER/BIGINT/NUMERIC), TIMESTAMP/BOOLEAN/
  REAL/TINYINT mappings.
- SqlExceptions matrix: only 42000 asserted; 42S02, 28000, 42501, 53200, 57014, 08000
  transient vs non transient, 08003, 0A000 states unasserted.
- Driver URL paths: ssl=true to TLS options, compression mapping, query_timeout_ms,
  connect/read timeout params, malformed values, multi host URL against a real failover,
  DataSource error paths, unwrap/isWrapperFor everywhere.

Observability
- JFR events entirely untested (no smoke test that Connect/Query/Insert/PoolAcquire commit,
  no outcome string verification: finished, server_error, cancelled_timeout, failed,
  committed, aborted).
- Micrometer gauges only asserted at zero on an unstarted pool; no under load deltas, no
  multi pool tags.

### P2: hygiene and polish

- Direct unit tests for ServerPacketType.fromCode and ClientPacketType (behaviour covered
  indirectly today), WireReader.readFully/asInputStream/hasBufferedBytes, WireWriter byte
  array writeString overload, ServerSetting FLAG_IMPORTANT, TransportOptions
  withConnectTimeout, remoteAddress accessors, ServerErrorCodes boundary.
- Geometry alias value decode asserted as their tuple and array shapes.
- Property based block round trips (generate random typed values per column type) to widen
  the 7 existing properties.
- Testkit itself has no tests (acceptable: it is test infrastructure).

## Missing infrastructure (roadmap scale, already 1.0 gates)

- Fault injection suite: scripted mid stream socket kills per protocol phase (partially
  delivered by P0-3/P0-4), server restart under pool load, TLS teardown mid session.
- Soak suite: hours long mixed workload with leak and memory bound assertions.
- Decoder fuzzing (for example Jazzer over BlockReader/Serializations/FrameDecompressing
  InputStream with corpus seeded from the golden vectors).
- Benchmarks execute nothing today (compile only); no performance regression net.

## Recommended burn down order

1. P0-1 to P0-10 (roughly one focused test class each; several share fixtures: a
   FaultInjectingServer helper covers P0-3/P0-4, a type edge matrix covers P0-1/P0-2/P0-10).
2. P1 JDBC block (one comprehensive JdbcSurfaceIT plus SqlExceptions/JdbcTypes unit tests
   moves chord-jdbc from 41% to a defensible number).
3. P1 resilience and codec remainder.
4. Fuzzing plus fault injection scaffolding, then the soak suite (1.0 gates).
