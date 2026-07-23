# Changelog

All notable changes to CHord are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project follows semantic
versioning once 1.0.0 is released; before that, any 0.x release may change the API.

## [Unreleased]

### Added

- A comparative benchmark against the official Java HTTP client
  (`HttpComparisonBenchmark`): both stacks against the same containerised server with LZ4
  in both directions, across a million row streaming SELECT, a hundred thousand row insert
  into a Null engine table, and point query latency. Preliminary laptop numbers and the
  honesty rules around them are in docs/performance.md: streaming reads run at roughly 2.5x
  to 3x the HTTP client's throughput, inserts carry a modest consistent edge, and latency
  needs a rigorous environment before any claim.

- A statement kind and production flow test matrix (docs/test-coverage-audit.md): at least
  one test per ClickHouse statement kind the client carries, covering the DDL lifecycle
  including DETACH/ATTACH, EXCHANGE and CHECK TABLE, introspection (SHOW, DESCRIBE, EXISTS),
  EXPLAIN, the SELECT clause zoo (PREWHERE, FINAL, SAMPLE, ARRAY JOIN, LIMIT BY, UNION,
  CTEs, joins, window functions, ROLLUP), lightweight and classic mutations, OPTIMIZE,
  materialized views, column subset inserts, SET/USE session state, session timezones and
  SYSTEM statements; plus operational flows: KILL QUERY from another connection, readonly
  users under full grants, server side execution timeouts, schema evolution visible to
  pooled connections, and session setting persistence. Every refusal asserted typed,
  honestly classified and connection preserving. Documented finding: as of the 25.8
  TCPHandler source, servers only emit TimezoneUpdate packets on the input() table function
  path, so the client's handler is proven by a scripted transport test.

## [0.1.0] - 2026-07-23

First public release: the native TCP protocol client and its JDBC adapter, tested against
ClickHouse 25.8, 26.3 and 26.6.

### Added

- Coverage for every gap in the test coverage audit (docs/test-coverage-audit.md), P0
  through P2, taking the suite to 443 executions swept across three server versions.
  Burning the register down surfaced and fixed two real defects before release: sparse IPv4
  and IPv6 columns could not materialise because the default value registry produced a
  string and a raw byte array where the column builders require address values, and a
  corrupt timezone name inside a wire type declaration escaped as a raw
  `java.time.DateTimeException` instead of a typed decode failure (found by the decoder
  fuzzer on its first run). Highlights: float special values and every integer, date,
  decimal and enum width at its boundaries through codec, server and JDBC paths; abrupt
  connection loss at every protocol phase with retry classification asserted; sparse
  materialisation across 22 type families; hand built wire vectors for the LowCardinality,
  Variant, Dynamic and JSON guard branches; TLS pinning; the full JDBC getter, setter,
  batch, metadata and SQLState matrices; JFR event outcomes from real recordings;
  Micrometer gauges under live pool traffic; and a differential check of decoded values
  against clickhouse-client output.

- The 1.0 gate test infrastructure: deterministic decoder mutation fuzzing seeded from the
  golden vectors (scales with `-Dchord.fuzz.iterations`), a duration scaled soak suite with
  permit leak and heap bound assertions (`-Dchord.soak.seconds`), server restart under
  concurrent pool load, TLS teardown mid session, and executing benchmarks: chord-benchmarks
  joined the reactor, gained a block codec benchmark for the decode and encode hot path, and
  runs end to end under `-Pbench-smoke`.

- Release readiness: `chord-bom` now lists every published module, including `chord-codec`,
  `chord-jdbc` and `chord-observability`, which shed their placeholder status in earlier
  phases; the release build produces sources, javadoc jars and CycloneDX SBOMs for all of
  them with reproducible timestamps.

- The JDBC 4.3 adapter (`chord-jdbc`). `jdbc:chord://host[:port][,host2...][/db][?params]` URLs
  with strict parameter validation, service loader registration, and a plain `ChordDataSource`.
  Connections are honestly transaction free; statements support query timeouts (mapped to the
  native Cancel based deadline), cross thread `cancel()`, client side `setMaxRows` and update
  counts from written rows. Prepared statements substitute bound values as escaped ClickHouse
  literals at placeholders found outside strings, quoted identifiers and comments, and
  recognise `INSERT INTO t [(cols)] VALUES (?, ...)` to batch through native blocks with server
  schema validation. Result sets stream native blocks with lossless getter coercions (out of
  range conversions raise SQLState 22003 instead of truncating). DatabaseMetaData answers
  identity from the handshake and browses catalogs, tables, columns, primary keys and functions
  through the system tables with client side JDBC type mapping. Server errors map to the JDBC
  exception hierarchy with SQLStates and vendor codes, and the native retry classification
  surfaces as transient versus non transient exception subtypes (ADR-0014). Features ClickHouse
  does not have raise SQLFeatureNotSupportedException rather than silently degrading.

- Advanced serialisations. LowCardinality columns decode and encode (dictionary and additional
  keys layout with the default slot, index width validation, NULL in slot zero for nullable
  inner types), so INSERT into LowCardinality tables works end to end. Sparse columns (custom
  serialisation kind 1, revision 54454) decode and materialise into full columns, including
  Nullable sparse from revision 54483; the detached, replicated and combination kind stacks are
  inter server forms that fail explicitly. Variant columns decode in basic and compact
  discriminator modes with values in name sorted global order. Dynamic columns decode with V1
  and V2 structure prefixes; rows that overflowed into the shared variant fail explicitly on
  typed access with raw bytes exposed. JSON columns decode with V1 and V2 object serialisation:
  typed paths from the type declaration, dynamic paths as Dynamic columns, and shared data
  entries exposed as raw values. Wire layouts were validated against Native format files
  produced by real servers and against live servers across 25.8, 26.3 and 26.6; building
  Variant, Dynamic or JSON values client side is rejected explicitly.

- Connection pooling and failover. `ConnectionPool` leases connections with a hard size bound,
  fair acquire with timeout, idle validation by protocol ping, maximum lifetime and idle
  eviction, leak diagnostics carrying the acquire site stack trace, and graceful close; a lease
  returned in any state other than READY is discarded, never reused. `FailoverConnector` opens
  connections across multiple endpoints with in order, round robin and random policies,
  exponential backoff with full jitter per endpoint, per attempt DNS resolution and a final
  walk that retries backing off endpoints when nothing else is left; it plugs into the pool as
  a connection factory. Neither the pool nor the connector ever retries an operation.
- Retry classification: every `ChordException` reports a `RetryClass` (SAFE_TO_RETRY,
  RETRY_ONLY_IF_IDEMPOTENT, OUTCOME_UNKNOWN, NOT_RETRYABLE) combining a conservative type
  default, throw site knowledge of the exchange phase, and a small verified table of server
  error codes; the first classification wins. CHord never retries automatically (ADR-0007,
  ADR-0013).
- Cancellation and deadlines. `QueryResult.cancel()` sends the Cancel packet (callable from
  another thread); the stream concludes with EndOfStream on the server's terms and the
  connection stays reusable after draining. `QueryRequest.timeout(...)` enforces a client side
  deadline at packet boundaries by polling the transport (never by interrupting a read mid
  value): on expiry the Cancel is sent, the connection's `cancelGrace` bounds the drain, and
  the query fails with `ChordTimeoutException` whether or not the drain succeeded; an
  unconcluded stream abandons the connection.
- `QueryRequest.onProgress(...)` and `onLog(...)` listeners, invoked on the consuming thread
  with per packet Progress deltas and typed `ServerLogEntry` rows; listener exceptions are
  contained. `insertDeduplicationToken(...)` sets `insert_deduplication_token` for callers who
  make retried inserts idempotent.
- Observability: JDK Flight Recorder events under the CHord category (Connect, Query, Insert,
  PoolAcquire) emitted by `chord-client` with no dependencies and no SQL text;
  `chord-observability` ships its first real content, a Micrometer binder for pool gauges
  (`chord.pool.connections.active`, `chord.pool.connections.idle`).

- Native protocol compression. `chord-codec` gains the ClickHouse compressed frame codec:
  LZ4, LZ4HC, ZSTD and NONE methods, CityHash128 v1.0.2 checksums (a pure Java port of the
  exact historical version ClickHouse pins, cross validated against frames produced by
  `clickhouse-compressor`), checksum validation before decompression, exact decompressed size
  enforcement and `CompressionLimits` bounding declared sizes before any allocation. Corrupt or
  oversized frames raise `ChordDataCorruptionException`.
- `chord-client`: compression per connection (`ConnectionOptions.compression`, with an optional
  tuned level) or per query (`QueryRequest.compression`). Data, Totals and Extremes bodies
  travel compressed in both directions; Log, ProfileEvents and TableColumns bodies follow from
  revision 54481. Verified against ClickHouse 25.8, 26.3 and 26.6 for large SELECT streams and
  multi block INSERT round trips with every method.
- Chunked packet framing (revision 54470) in both directions, negotiated per channel with the
  same rules as the official client, so servers strictly requiring `chunked` now work, composed
  freely with compression and TLS. Every chunk travels with its header, payload and terminator
  in a single transport write; the server treats a short read while completing a split chunk
  header as end of stream, so lone header segments would drop connections intermittently.
- `QueryResult.profileEvents()`: typed per query profile event counters accumulated from
  ProfileEvents packets, including increments and gauges.

- Native streaming INSERT. `chord-codec` gains `ColumnWriter` and full `BlockWriter` encoding
  (every supported type round trips byte exactly through encode and decode) and the typed
  `BlockBuilder`, which validates values losslessly at append time: range checked integers,
  exact decimal rescaling, sub tick DateTime64 precision checks, zero padded but never truncated
  FixedString, enum label validation, IPv4 mapping into IPv6 columns and recursive composites,
  with row and column context on every failure.
- `chord-client`: `NativeConnection.insert()` sends the Query with pending data, consumes
  TableColumns defaults metadata and the server supplied schema header, and returns an
  `InsertStream` for multi block streaming. `finish()` sends the terminal empty block, drains
  Progress, Log and ProfileEvents to EndOfStream and reports written rows; closing without
  finish hard aborts the connection so partially streamed data is never committed implicitly.
  Server rejections before or at finish surface as typed exceptions and leave the connection
  reusable. Verified against ClickHouse 25.8, 26.3 and 26.6, including constraint violations,
  async insert settings and forwarding SELECT result blocks straight into an INSERT.

- Streaming SELECT over the native protocol. `chord-codec` gains the sealed recursive type model
  and type name parser (nested composites, quoted and escaped enum labels, named and back quoted
  tuple elements, timezone and precision parameters, geometry aliases, configurable length and
  depth limits, round trip property tested), primitive backed column implementations without per
  cell allocation, and native block decoding with hostile input bounds for every Phase 2 type:
  the integer families through 256 bits, floats, BFloat16, Bool, String, FixedString, Date,
  Date32, DateTime, DateTime64 with timezone metadata, Interval, UUID, IPv4, IPv6, Enum8,
  Enum16, the Decimal family, Nothing, Nullable, Array, Tuple, Map and SimpleAggregateFunction
  aliases. Custom (sparse) serialisation, LowCardinality, Variant, Dynamic and JSON are rejected
  explicitly before any value bytes are consumed, until Phase 6.
- `chord-client`: `QueryRequest` (query id, string settings, server side parameters),
  `NativeConnection.query()` and the pull based `QueryResult` streaming columnar blocks with
  bounded memory, schema before rows, progress accumulation, ProfileInfo, Totals, Extremes,
  server log consumption and TimezoneUpdate handling. Server exceptions before or during the
  stream surface as typed exceptions and leave the connection reusable; abandoning a result
  drains it on close so a connection is never reused out of sync. The Query packet ClientInfo is
  byte level golden tested at revisions 54470 and 54488, and SELECT values are differentially
  compared against clickhouse-client inside the test container.

- `chord-transport`: TLS and mutual TLS for the native protocol. Hostname verification is always
  on (no disable switch, no trust-all option anywhere), SNI for hostnames, trust material from
  the system store, JKS or PKCS#12 files, PEM CA bundles or a custom `SSLContext`, client
  material from key stores or PEM certificate plus PKCS#8 key (encrypted keys supported),
  configurable protocols and cipher suites, diagnosed handshake failures (expiry, hostname
  mismatch, missing trust, rejected client certificate) and an expiry warning for certificates
  within thirty days of their end date.
- `chord-client`: `ConnectionOptions.tls(TlsOptions)`; with TLS configured the default port
  becomes 9440 and passwords need no plaintext opt in.
- `chord-testkit`: test run time certificate generation (`TestCertificates`, BouncyCastle
  confined to the testkit) and a secure native port for the ClickHouse fixture, including strict
  client certificate verification for mutual TLS tests.

- Maven multi module build (`chord-bom`, `chord-protocol`, `chord-codec`, `chord-transport`,
  `chord-client`, `chord-observability`, `chord-jdbc`, `chord-testkit`, `chord-examples`,
  `chord-benchmarks`) with reproducible builds, Spotless, Checkstyle, SpotBugs, JaCoCo, licence
  header checks and Maven Central publishing configuration.
- `chord-protocol`: wire primitives for the ClickHouse native protocol (VarUInt and zigzag VarInt
  over the full unsigned 64 bit range, little endian fixed width integers, length prefixed
  strings) with hostile input limits; the protocol revision registry verified against ClickHouse
  master revision 54488; client and server packet type registries; ClientHello, ServerHello and
  client addendum codecs covering every current handshake field including the server settings
  block, chunked capability negotiation and the interserver nonce; Exception and Progress packet
  decoding; the connection state machine; the CHord exception hierarchy.
- `chord-transport`: blocking TCP transport with connect and read deadlines behind a transport
  SPI that models transport security for credential policy decisions.
- `chord-client`: `NativeConnection` performing handshake, authentication, addendum and repeated
  Ping and Pong, refusing password authentication over plaintext transports without an explicit
  opt in, and never reusing a connection after a protocol violation.
- `chord-testkit`: Testcontainers ClickHouse fixture exposing the native port with deterministic
  credentials and image selection for the compatibility matrix.
- Integration tests exercising the handshake, repeated ping, authentication failure and unknown
  database paths against ClickHouse 25.8, 26.3 and 26.6.
- JMH benchmark scaffold for the VarUInt codec.
- GitHub Actions: build matrix on Java 21, 23 and 25, integration tests, nightly ClickHouse
  compatibility sweep, CodeQL and a Maven Central release workflow with a dry run mode.
