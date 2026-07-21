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

## Phase 2: uncompressed SELECT

| Feature | Notes |
|---|---|
| Query packet | Query id, ClientInfo, settings, parameters, execution stage |
| Data packet decoding | Header and result blocks, BlockInfo, empty block semantics |
| Core type codecs | See [type-support.md](type-support.md) for the full matrix |
| Streaming columnar result API | Bounded memory, schema before rows, `Flow.Publisher` based streaming |
| Progress accumulation, cancellation of result streams | Progress body decoding already exists |
| External tables | Data packets alongside SELECT |
| Named query parameters | Server side substitution (revision 54459) |
| Scalar packets | Scalar subquery results |

## Phase 3: native INSERT

| Feature | Notes |
|---|---|
| INSERT schema negotiation | Server supplied header block drives validation |
| Block encoding and streaming, terminal empty block | |
| Batch sizing, flush thresholds, backpressure | |
| Asynchronous INSERT responses, deduplication tokens | |
| TableColumns and TimezoneUpdate handling | |
| Materialised and default column handling | |

## Phase 4: compression and advanced packets

| Feature | Notes |
|---|---|
| LZ4, LZ4HC, ZSTD, NONE framing | ClickHouse CityHash checksum validation, corrupt frame detection, decompression bomb limits |
| Chunked packet framing | Negotiation is already implemented; framing itself is not. Servers strictly requiring chunked framing are refused until this lands |
| Log, ProfileInfo, ProfileEvents, Totals, Extremes packets | Including compressed columns from revision 54481 |

## Phase 5: pooling and resilience

| Feature | Notes |
|---|---|
| Connection pool | Sizing, validation, eviction, leak diagnostics, graceful shutdown |
| Multiple endpoints, load balancing, failover | Policy SPI, health state, backoff with jitter, DNS re resolution |
| Retry classification | SAFE_TO_RETRY, RETRY_ONLY_IF_IDEMPOTENT, OUTCOME_UNKNOWN, NOT_RETRYABLE |
| Cancel packet, deadlines, cancellation draining | Hard abort by socket close exists today |
| Metrics, OpenTelemetry, JFR events | `chord-observability` |

## Phase 6: advanced serialisations

| Feature | Notes |
|---|---|
| LowCardinality, Variant, Dynamic, JSON | V2 Dynamic and JSON serialisation (revision 54473) |
| Custom serialisation metadata (54454), sparse serialisation (54465, 54483) | |
| Replicated serialisation (54482), parallel block marshalling (54478) | |
| Binary type name encoding | |

## Phase 7: JDBC

| Feature | Notes |
|---|---|
| JDBC 4.3 adapter | URL parser, Driver, Connection, Statement, PreparedStatement, ResultSet, DatabaseMetaData, DataSource, delegating to the native client; honest `SQLFeatureNotSupportedException` for what ClickHouse cannot do |

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
