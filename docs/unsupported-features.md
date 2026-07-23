# Unsupported features and their milestones

The written list of everything CHord does not do yet, with the milestone that delivers it. This
is the honest counterpart to the README status table. Milestones follow the phase plan; a feature
moves off this list only when it lands with tests.

## Phase 1 remainder: authentication plumbing

TLS and mutual TLS shipped (hostname verification always on, SNI, system, JKS, PKCS#12 and PEM
trust material, PEM and key store client material, custom `SSLContext`, expiry diagnostics); see
ADR-0012. What remains from the Phase 1 authentication scope:

| Feature | Notes |
|---|---|
| Pluggable credential providers | Credential rotation on new connections |

## Phase 2 remainder: SELECT conveniences

Uncompressed streaming SELECT shipped: the Query packet with full ClientInfo, native block
decoding for every Phase 2 type, the pull based streaming result with bounded memory, server
side parameters, per query settings, totals, extremes, profile information and progress
accumulation. What remains from the Phase 2 scope:

| Feature | Notes |
|---|---|
| `Flow.Publisher` streaming adapter | The pull based `QueryResult` is primary; the reactive adapter follows |
| Row oriented convenience access | Columnar access is primary per ADR-0005 |
| Non empty external tables | Sending data along with SELECT |
| Progress and log callbacks | Accessors exist; push callbacks arrive with the async API in Phase 5 |

## Phase 3 remainder: INSERT conveniences

Schema driven streaming INSERT shipped: the pending data Query flow, TableColumns and schema
negotiation, the typed BlockBuilder with lossless validation, multi block streaming with the
terminal empty block, drain to EndOfStream, async insert settings and hard abort semantics for
abandoned streams. What remains from the Phase 3 scope:

| Feature | Notes |
|---|---|
| Batch sizing by rows and estimated bytes, flush thresholds, backpressure helpers | Callers currently control block boundaries directly |
| Explicit deduplication tokens | `insert_deduplication_token` can already be passed as a setting; a typed API arrives with retry classification in Phase 5 |
| Iterable object mapping onto inserts | Row object convenience per ADR-0005 |
| `input(...)` based streaming | |

## Phase 4 remainder: compression conveniences

Compression and chunked framing shipped: LZ4, LZ4HC, ZSTD and NONE frames with CityHash 1.0.2
checksum validation, corrupt frame detection and decompression bomb limits, per connection and
per query selection, chunked packet framing in both directions including strict servers, the
compressed Log, ProfileEvents and TableColumns bodies of revision 54481, and typed profile event
counters on query results. What remains from the Phase 4 scope:

| Feature | Notes |
|---|---|
| Log entry callbacks | Server logs are consumed, bounded and forwarded to SLF4J; push callbacks arrive with the async API in Phase 5 |
| Compression statistics accessors | Compressed and raw byte counters per exchange |

## Phase 5 remainder: async conveniences

Pooling and resilience shipped: the bounded `ConnectionPool` with ping validation, lifetime and
idle eviction, leak diagnostics and graceful close; `FailoverConnector` with in order, round
robin and random policies, exponential backoff with jitter and per attempt DNS resolution;
`RetryClass` on every exception with throw site refinement; the Cancel packet, per request
timeouts with cancellation draining and the cancel grace period; progress and server log
listeners; the typed insert deduplication token; JFR events and the Micrometer pool binder.
What remains from the Phase 5 scope:

| Feature | Notes |
|---|---|
| Async API and push callbacks | The blocking API on virtual threads is primary; a `CompletableFuture` facade is unscheduled |
| Micrometer timers for per operation latency | JFR events carry per operation timing today; registry timers need instrumentation hooks |
| OpenTelemetry tracing spans | Metrics are reachable through Micrometer bridges; trace propagation (`opentelemetry_trace_parent`) is not implemented |

## Phase 6 remainder: serialisation conveniences

LowCardinality read and write, sparse decode (including nullable sparse), Variant, Dynamic and
JSON decode shipped, validated against Native files from real servers and live integration
tests. What remains from the Phase 6 scope:

| Feature | Notes |
|---|---|
| Writing JSON columns with typed path declarations | Untyped JSON, Variant and Dynamic writes shipped; declared typed paths need their own write handling |
| Decoding shared variant and shared data values | Rows beyond a Dynamic column's type budget or a JSON column's path budget carry binary encoded type and value; CHord exposes the raw bytes and fails explicitly on typed access |
| Replicated serialisation (54482) | An inter server column form; recognised and rejected explicitly |
| Dynamic and JSON V3 and flattened serialisations | File format variants servers do not send over TCP; recognised and rejected explicitly |
| Binary type name encoding | Only used by V3 prefixes and format settings CHord does not enable |

## Phase 7 remainder: JDBC conveniences

The JDBC 4.3 adapter shipped: driver and service loader registration, `jdbc:chord://` URLs with
multiple hosts, connections, statements with timeouts and cancellation, prepared statements
with native block batching for `INSERT ... VALUES (?, ...)`, streaming result sets with
lossless getter coercions, database metadata over the system tables, a plain DataSource, and
retry aware exception mapping (see ADR-0014). What remains from the Phase 7 scope:

| Feature | Notes |
|---|---|
| TIME getters and parameters | Follow the native Time/Time64 support |
| Scrollable or updatable result sets | Never planned: dishonest over a streaming columnar protocol |
| getObject for JSON, Variant and Dynamic beyond the native representations | Values surface as the native client's maps and objects today |

## After Phase 5, unscheduled

| Feature | Notes |
|---|---|
| SSH challenge response authentication | Client packets 11 and 12, server packet 18 |
| TablesStatus request and response | |

## Recognised but not planned

| Feature | Reason |
|---|---|
| Inter server secret authentication (v1 and v2) | Server to server concern; the nonce is read for wire correctness and never used |
| Parallel replicas coordination packets | Server to server concern; receiving one raises an explicit protocol error |
| QueryPlan client packet, cluster function read tasks | Server to server concern |
| JWT authentication marker | ClickHouse Cloud oriented; will be evaluated on demand |
| KeepAlive client packet | No concrete need identified |
| Obsolete part UUID deduplication packets | Obsolete upstream |
