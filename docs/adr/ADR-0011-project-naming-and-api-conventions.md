# ADR-0011: Project naming and API conventions

Status: Accepted (2026-07-21)

## Context

The project needs stable public names before anything is published: repository, Maven
coordinates, base package, exception naming and the exception model. Renames after publication
are breaking changes.

## Decision

- Project name: **CHord** (ClickHouse plus chord), repository `github.com/orhaugh/CHord`.
- Maven group id: `io.github.orhaugh`, publishable on Maven Central through GitHub namespace
  verification.
- Artifacts: `chord-*` (`chord-protocol`, `chord-client`, ...), version line starting at
  `0.1.0-SNAPSHOT`.
- Base package: `io.github.orhaugh.chord`; the shared exception family lives in that root
  package, subsystem packages beneath it (`protocol`, `transport`, `client`, `codec`, `testkit`).
- Exception prefix: `Chord*` rather than `ClickHouse*`, so CHord types are unambiguous next to
  the official `com.clickhouse` classes when both are on a classpath.
- Exceptions are unchecked, rooted at `ChordException`; API stability is signalled with the
  `@Experimental` and `@Internal` annotations until 1.0, and semantic versioning applies to
  unannotated API from 1.0.
- `Automatic-Module-Name` values mirror the package layout (`io.github.orhaugh.chord.protocol`
  and so on).

## Alternatives

- `ClickHouse*` class prefix: rejected for collision confusion with the official client.
- A bespoke domain based group id: rejected; no owned domain, and `io.github` verification is
  immediate and honest.

## Consequences

- All public names in this document are load bearing from the first push; changes require a new
  ADR and, after publication, a major version discussion.
