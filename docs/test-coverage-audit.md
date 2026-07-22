# Test coverage audit

Date: 2026-07-22, at commit c72e13e (0.1.0 staged, unpublished). Method: JaCoCo line and branch
coverage across the full unit plus integration suite, a file by file catalogue of every test
class (assertion depth, edge values, failure modes), and a gap analysis against the feature
surface a production ClickHouse user exercises. This document is the burn down list; a row
leaves it only when a test lands.

## Quantitative snapshot

Updated 2026-07-22 after the P0 and P1 burn downs: 416 test executions, 329 unit (including
7 jqwik properties and 3 golden suites built from real server bytes) and 87 integration
tests, the latter swept across ClickHouse 25.8, 26.3 and 26.6 plus the newest nightly
builds, on JDK 21, 23 and 25. Original snapshot at commit c72e13e in parentheses.

| Module | Line coverage | Branch coverage |
|---|---|---|
| chord-protocol | 90.3% (88.1%) | 80.3% (79.2%) |
| chord-transport | 84.6% (81.7%) | 72.7% (68.2%) |
| chord-codec | 89.4% (85.1%) | 81.8% (73.0%) |
| chord-client | 90.3% (85.9%) | 75.3% (72.2%) |
| chord-jdbc | 57.1% (41.0%) | 62.8% (41.4%) |
| chord-observability | 100% | n/a |

The remaining cold JDBC lines are dominated by honest refusals: `ChordResultSet` (43% of
400 lines) throws `SQLFeatureNotSupportedException` from every updateXxx, stream and
positioning method, `ChordConnection` (35%) from savepoints, prepareCall and transaction
isolation, `ChordDatabaseMetaData` (51%) is capability flag constants. The functioning
surface (getters, setters, batches, metadata queries, URL parsing, exception mapping) is
covered by the P1 tables below; the refusal boilerplate is deliberate and uniform.

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

### P1: hardening a production adopter would expect (CLOSED)

All P1 findings are closed by the tests below. Two claims are documented rather than
tested, with reasons at the end of this section.

Types and codec

| Finding | Closed by |
| --- | --- |
| Interval values only tested for Day | `TypeEdgeValuesTest.everyIntervalKindCarriesValues` (all eleven kinds, negative/zero/positive) |
| Date and Date32 boundary values | Already closed by P0-10 (`TypeEdgeValuesTest.dateWidthsRoundTripTheirBoundaryValues`, `TypeEdgeIT.integerDateDecimalAndEnumBoundariesRoundTrip`) |
| Decimal scale extremes; Decimal128 write, Decimal256 both sides | `TypeEdgeValuesTest.everyDecimalWidthRoundTripsItsExtremes` (Decimal64(6), Decimal(38,10), Decimal(76,20), both directions) |
| Variant compact discriminators never decoded | `SerializationGuardsTest.variantCompactDiscriminatorGranulesDecode` (COMPACT and PLAIN granules mixed, null discriminator) |
| Serializations guard branches | `SerializationGuardsTest` (17 tests: LC keys version/global dictionary/missing additional keys/index width/dictionary bound/index beyond dictionary; Variant discriminator mode/range/granule format/granule size; Dynamic and JSON version and cap guards) |
| Dynamic/JSON V2 prefixes had no unit vectors | `SerializationGuardsTest.dynamicV2PrefixesDecodeAsTheTcpPathSendsThem`, `jsonV2PrefixesDecodeAsTheTcpPathSendsThem` (hand built wire bytes, shared data raw JSON asserted); `JsonTypeArgumentsTest` (6 tests: sorting, backquoted paths, SKIP/REGEXP, quoted commas, malformed) |
| ProfileInfo.read untested, Progress.read truncation | `ProfileInfoTest` (full read at 54488, gate below 54469, truncation for both) |
| Compression.checkLevel bounds | `CompressionLevelTest` (LZ4HC 1..12, ZSTD 1..22 bounds and extreme legal levels, level rejection for LZ4/NONE) |

Client and resilience

| Finding | Closed by |
| --- | --- |
| Connect timeout never driven | `TcpTransportTest.connectTimeoutsSurfaceAsChordTimeouts` (blackhole address, 300ms budget) |
| Pool idle eviction; close racing active leases | `ConnectionPoolTest.idleConnectionsAreEvictedAfterTheIdleTimeout`, `closingThePoolWhileLeasesAreActiveIsSafe` |
| Failover RANDOM policy, maxBackoff cap, failed over connection never queried | `FailoverConnectorTest.randomPolicyReachesEveryHealthyEndpoint`, `backoffIsCappedAtTheConfiguredMaximum`; `ConnectionPoolIT.poolOverFailoverReachesTheRealServerAndRunsQueries` (dead endpoint first, real queries after failover) |
| Retry classification phase stamping | `FaultInjectionTest.deathDuringTheHandshakeClassifiesSafeToRetry`, `deathDuringAPingClassifiesSafeToRetry`, `deathBeforeTheInsertSchemaClassifiesSafeToRetry` (with the P0 mid stream and mid insert cases this covers all four stamps) |
| INSERT path listeners never fire | `ListenersIT.insertPathListenersReceiveServerLogs` (trace logs across a 20 block insert against a real server; 25.8 sends no Progress packets for client fed inserts, its TCP handler only emits progress from the SELECT pull loop) plus `NativeConnectionTest.insertExchangesDispatchProgressPacketsToListeners` (scripted Progress packet through notifyProgress and accumulate) |
| Hostile chunk header | `ChunkedStreamsTest.hostileDeclaredChunkLengthsStreamWithoutUpfrontAllocation` (0xFFFFFFFF declared, streams without allocation) |
| TLS pinning and not yet valid certificates | `TlsTransportTest.protocolAndCipherPinningApplyToTheHandshake`, `reportsNotYetValidServerCertificates` |
| Empty INSERT undefined | `ListenersIT.emptyInsertsCommitNothingAndLeaveTheConnectionClean` |
| Query id round trip unverified | `ListenersIT.queryIdsReachTheServerQueryLog` (system.query_log after SYSTEM FLUSH LOGS) |
| Differential testing limited to one SELECT | `TypeEdgeIT.integerDateDecimalAndEnumBoundariesRoundTrip` differential tail (clickhouse-client TabSeparated output compared byte for byte, including Int256 and DateTime extremes) |

JDBC

| Finding | Closed by |
| --- | --- |
| ResultSet getter matrix | `JdbcIT.resultSetGettersCoverEveryCoercion` (getBoolean/getByte/getShort/getFloat/getBytes/getTimestamp/getBigDecimal(int,scale), index variants, getObject(Class) for UUID/LocalDate/Instant/Map/Integer with null path, findColumn case fallback, 42S22/07009/24000, unsupported conversion 0A000) |
| PreparedStatement surface | `JdbcIT.preparedStatementSettersAndBatchSemanticsCoverTheSurface` (twelve setter types, single row native executeUpdate, addBatch/clearBatch/reexecution/empty batch, 07002 unbound, 07009 bad index, clearParameters, addBatch refusal on SELECT, ParameterMetaData, getMetaData null before execute, substitution executeUpdate) |
| Statement lifecycle | `JdbcIT.statementLifecycleFollowsTheJdbcContract` (getUpdateCount transitions, getMoreResults closing the current result, closeOnCompletion, cross thread cancel and reuse) |
| DatabaseMetaData surface | `JdbcIT.databaseMetadataSurfaceAnswersTools` (getCatalogs, getTableTypes, getTypeInfo, getFunctions, VIEW and SYSTEM TABLE classification, types[] filter, getImportedKeys honestly empty, capability flags) |
| ResultSetMetaData matrix | `JdbcIT.resultSetMetadataDescribesEveryMappedType` (every mapped Types code, getColumnClassName, isSigned, precision/scale, labels, isReadOnly); `JdbcTypesTest` (6 unit tests: full sqlType/javaClassName/precision/scale/isNullable matrices including wrapper unwrapping) |
| SqlExceptions matrix | `SqlExceptionsTest` (5 unit tests: SQLStates for all mapped server codes, vendor codes, retry class into transient vs non transient, transport split, auth/timeout/configuration, 0A000 and 08003 helpers) |
| Driver URL and DataSource paths | `JdbcIT.driverUrlOptionsAndDataSourcePathsAreWired` (multi host failover URL against a real server, compression=zstd, query_timeout_ms to getQueryTimeout, unknown compression and malformed values as 08001, DataSource null/foreign URL, unwrap/isWrapperFor to NativeConnection) |

Observability

| Finding | Closed by |
| --- | --- |
| JFR events entirely untested | `JfrEventsTest.everyEventTypeCommitsWithItsOutcome` (Connect succeeded with negotiated revision, Query finished with query id, Insert committed and aborted with rows, PoolAcquire succeeded, parsed from a real recording dump) |
| Micrometer gauges only at zero | `ChordPoolMetricsTest.gaugesFollowRealLeaseTraffic` (active/idle deltas across acquire and release against a loopback server, closed pool still readable), `multiplePoolsKeepDistinctTagsInOneRegistry`, `bindRejectsNullArguments` |

Documented, not tested

- DNS re resolution on failover: verified by inspection. `FailoverConnector` builds a fresh
  `InetSocketAddress` for every dial, so each attempt re resolves through the JVM resolver.
  A deterministic test needs a controllable resolver; deferred to the fault injection
  scaffolding below.
- JDBC `ssl=true` cannot carry a custom truststore: the URL surface has no truststore
  parameters, so only system trusted certificates work through JDBC today. Recorded as a
  feature gap for the roadmap (native API callers can pass full `TlsOptions`).

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

1. P0-1 to P0-10: DONE (see the P0 table above; found and fixed the sparse IP defaults bug).
2. P1 JDBC block: DONE (chord-jdbc moved from 41% line coverage to the full surface tables
   above).
3. P1 resilience and codec remainder: DONE.
4. Remaining: P2 hygiene items, then fuzzing plus fault injection scaffolding and the soak
   suite (1.0 gates).
