# ADR-0001: Native API before JDBC

Status: Accepted (2026-07-21)

## Context

Most Java ClickHouse access goes through JDBC, but JDBC's row oriented, synchronous, transaction
flavoured model is a poor fit for a columnar, streaming, mostly append only database. Drivers
that implement JDBC first tend to bake its assumptions into their internals and then expose a
"native" API as an afterthought.

## Decision

CHord's product is a stable, idiomatic Java API designed around the native protocol: columnar
blocks, streaming, explicit resource ownership, immutable request objects. JDBC arrives in Phase
7 as an adapter (`chord-jdbc`) that only delegates to the native client; it duplicates no wire
logic, no pooling and no codecs. JDBC is never used as an internal abstraction anywhere in the
codebase.

## Alternatives

- JDBC first: rejected; it distorts the internals and blocks columnar streaming design.
- JDBC never: rejected; the ecosystem (tools, frameworks) justifies an honest adapter.

## Consequences

- The native API carries the design burden of being the primary public surface.
- JDBC users get correct, if deliberately limited, behaviour: operations ClickHouse does not
  support fail with `SQLFeatureNotSupportedException` rather than pretending.
