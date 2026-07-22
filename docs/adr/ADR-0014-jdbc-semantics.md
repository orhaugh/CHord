# ADR-0014: JDBC adapter semantics

Status: Accepted (2026-07-22)

## Context

Phase 7 delivers the JDBC 4.3 adapter over the native client (ADR-0001 fixed the order: native
API first, JDBC as an adapter). JDBC carries thirty years of relational assumptions ClickHouse
does not share, so the mapping decisions and their honesty rules are recorded here.

## Decision

### Identity and URLs

- URL prefix `jdbc:chord://host[:port][,host2...][/database][?key=value]`; the driver registers
  through the service loader. The official driver's `jdbc:clickhouse:` prefix is not claimed.
- Unknown URL parameters and driver properties fail instead of being ignored, so a typo cannot
  silently weaken a security setting such as `ssl` or `allow_plaintext_password`.
- Multiple hosts use the native `FailoverConnector` when opening the connection.
- `jdbcCompliant()` returns false: ClickHouse is not SQL-92 entry level and the driver does not
  pretend otherwise.

### Semantics

- No transactions: the connection is permanently in auto commit; `setAutoCommit(false)`,
  savepoints and rollback raise honest errors. `Connection.isValid` is a protocol ping.
- Databases surface as JDBC catalogs; the schema level is unused (empty results, no op setters).
- Result sets are forward only and read only, streaming native blocks with bounded memory;
  scrolling and mutation raise `SQLFeatureNotSupportedException` ("0A000").
- `Statement.executeUpdate` returns the written rows accumulated from Progress packets, the
  closest ClickHouse equivalent of an update count; DDL returns 0.
- `setQueryTimeout` maps to the native per request timeout (Cancel plus bounded drain, ADR-0013)
  and surfaces as `SQLTimeoutException`; `Statement.cancel()` maps to the Cancel packet and is
  callable from another thread. `setMaxRows` caps rows client side without rewriting SQL.
- Getter coercions are lossless: values that do not fit the requested Java type raise SQLState
  22003 instead of truncating (UInt64 above `Long.MAX_VALUE` is the canonical case; use
  `getObject`, which yields `BigInteger`).

### PreparedStatement

- `INSERT INTO t [(cols)] VALUES (?, ...)` with only plain placeholders is recognised and
  batched through native blocks: `executeBatch` streams one INSERT with the server supplied
  schema validating every value. This is the fast path and the reason the adapter exists.
- Every other statement substitutes bound values as ClickHouse literals at placeholder
  positions found outside strings, quoted identifiers and comments, with full escaping. Binding
  an unsupported Java type fails; nothing falls back to `toString()`.
- Batches on non INSERT statements are not supported rather than silently degraded.

### Exceptions

- Server errors map to `SQLSyntaxErrorException`, `SQLInvalidAuthorizationSpecException` and
  friends with SQLStates for the well known codes; the ClickHouse error code is the vendor code.
- The native retry classification (ADR-0007) carries into JDBC's taxonomy: SAFE_TO_RETRY and
  RETRY_ONLY_IF_IDEMPOTENT failures surface as `SQLTransientException` subtypes, everything
  else as non transient, so standard pools and frameworks inherit CHord's judgement.

### Metadata

- `DatabaseMetaData` answers identity from the handshake and browses catalogs, tables, columns,
  primary keys and functions through the `system` tables; JDBC type codes are computed client
  side through the shared type parser. Foreign key queries return empty results (truthful);
  concepts ClickHouse lacks raise `SQLFeatureNotSupportedException`.

## Consequences

- Tools driving the adapter see honest capabilities and never a silent no op where they asked
  for a guarantee.
- The adapter adds no dependencies; `chord-jdbc` is chord-client plus `java.sql`.
- Features the native API gains later (row object mapping, Time types) lift the corresponding
  JDBC restrictions when they land.
