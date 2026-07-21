# ADR-0004: Protocol version registry

Status: Accepted (2026-07-21)

## Context

The native protocol gates dozens of conditionally present fields on the negotiated revision.
Scattered integer comparisons (`if (revision >= 54474)`) rot: they are unsearchable, easy to get
wrong when adopting a new upstream revision, and hide which features exist.

## Decision

Every revision gate lives in one registry, the `ProtocolFeature` enum, mirroring the
`DBMS_MIN_...` constants of `ProtocolDefines.h` with values verified against the ClickHouse
sources (master revision 54488 on 2026-07-21). All conditional wire logic asks
`feature.enabledFor(negotiatedRevision)`; raw revision comparisons are banned in review.

The negotiated revision is `min(advertised, serverReported)`. CHord gates on that minimum even
though the official client checks only the server revision, because CHord allows the advertised
revision to be configured downward for protocol debugging.

Two hard constants accompany the registry: `ProtocolRevisions.CURRENT` (54488), which is a
promise that every field gated at or below it is implemented for the structures CHord speaks, and
`MIN_SUPPORTED_SERVER_REVISION` (54458, the addendum gate), below which servers are refused
because the handshake shape is different and untested.

## Alternatives

- Scattered comparisons: rejected as unmaintainable.
- Code generation from `ProtocolDefines.h`: attractive later; manual mirroring with source
  verification and golden tests is sufficient and keeps Javadoc curated.

## Consequences

- Adopting a new upstream revision is a deliberate act: read the sources, extend the registry,
  implement the fields, add golden tests, then raise `CURRENT`.
- `docs/protocol-compatibility.md` and the registry must move together; review enforces it.
