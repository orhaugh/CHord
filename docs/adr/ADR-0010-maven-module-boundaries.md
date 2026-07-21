# ADR-0010: Maven module boundaries

Status: Accepted (2026-07-21)

## Context

A wire protocol client accumulates layers: primitives, codecs, transport, client orchestration,
adapters, test fixtures. Without enforced boundaries these layers merge into a ball where the
JDBC adapter reaches into socket code.

## Decision

Ten modules with a strict dependency direction (see `docs/architecture.md` for the graph):

- `chord-protocol` at the bottom, zero runtime dependencies, hosting the shared exception family
  in the root package `io.github.orhaugh.chord`.
- `chord-transport` and `chord-codec` depend only on `chord-protocol`.
- `chord-client` composes the three and is the only module application code needs.
- `chord-jdbc` and `chord-observability` depend only on `chord-client`.
- `chord-testkit` depends on no CHord module (only Testcontainers), so every module's tests can
  use it without cycles; helpers that need the client will live elsewhere rather than break this.
- `chord-examples` and `chord-benchmarks` are leaves and are never published; benchmarks build
  only under the `benchmarks` profile so JMH stays out of normal builds.
- `chord-bom` aligns consumer versions.

SLF4J API is the only logging dependency allowed in runtime modules; `chord-protocol` remains
dependency free even of that.

## Alternatives

- Single module: rejected; boundaries enforced only by discipline do not survive.
- Separate `chord-core` for exceptions: rejected for now as module proliferation; the root
  package inside `chord-protocol` serves the purpose. Revisited before 1.0 if protocol ever needs
  to be splittable from the API surface.

## Consequences

- Dependency direction violations fail at compile time, not in review.
- The transport SPI lives below the client, so a TLS transport (Phase 1) or a future alternative
  transport slots in without touching protocol code.
