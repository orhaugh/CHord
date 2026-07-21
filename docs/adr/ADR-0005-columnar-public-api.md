# ADR-0005: Columnar public API

Status: Accepted (2026-07-21)

## Context

ClickHouse is columnar on the wire: results and inserts move as blocks of columns, not rows.
Materialising one Java object per cell to present rows first destroys the throughput the native
protocol exists to provide.

## Decision

The columnar API is primary. Query results are consumed as a stream of decoded blocks with typed
primitive column accessors (`Int64Column`, `StringColumn` and so on, from Phase 2), without
allocating per cell objects on the main path. Row oriented access is a convenience layer built on
top of columns, never the substrate. Schema is available before row data. Streaming is
backpressure aware and bounded; whole result sets are never buffered implicitly.

## Alternatives

- Row first API with columnar internals: rejected; convenience layers hide costs and the
  internals leak into public behaviour anyway.
- Columnar only, no row helpers: rejected; row mapping is genuinely convenient for small results
  and adapters, provided it is honest about cost.

## Consequences

- The column type hierarchy and block lifetime rules (slices, views, reuse) must be designed
  carefully in Phase 2 and are recorded in ADR-0008.
- JDBC (row oriented by definition) pays a mapping cost at the adapter boundary, which is the
  correct place for it.
