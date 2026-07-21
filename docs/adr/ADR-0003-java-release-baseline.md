# ADR-0003: Java release baseline

Status: Accepted (2026-07-21)

## Context

The library should be usable by the broad ecosystem without giving up the concurrency model it is
designed around. Virtual threads are stable from Java 21, the current long term support baseline
that frameworks widely target.

## Decision

Published artifacts are compiled with `--release 21`. Development uses any JDK at 21 or newer;
CI builds and tests on Java 21, 23 and 25. Preview APIs are banned from published modules.
`Automatic-Module-Name` is set on every jar now; full `module-info` descriptors are deferred
until they stop creating friction. The baseline rises only with a documented decision, never as a
side effect of a dependency bump.

## Alternatives

- Java 17 baseline: rejected; it loses stable virtual threads, the core of ADR-0002.
- Java 23 bytecode: rejected; it excludes Java 21 and 22 applications for no needed feature.

## Consequences

- Language and API usage is capped at 21 even when building on newer JDKs; the compiler enforces
  it via `--release`.
- CI catches behavioural differences on newer runtimes early.
