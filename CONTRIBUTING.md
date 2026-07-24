# Contributing to CHord

Thanks for considering a contribution. CHord is a wire protocol implementation, so the bar for
correctness evidence is deliberately high; this document tells you how to clear it.

## Reporting bugs and requesting features

Use the [issue templates](https://github.com/orhaugh/CHord/issues/new/choose) for bugs and
feature requests. Suspected vulnerabilities never go in a public issue; follow
[SECURITY.md](SECURITY.md) instead.

## Toolchain

- JDK 21 or newer (CI tests 21, 23 and 25; published bytecode targets 21)
- Docker, for integration tests
- No local Maven needed; use the wrapper (`./mvnw`)

## Everyday commands

```bash
./mvnw verify                        # full build with unit tests and static analysis
./mvnw spotless:apply                # fix formatting (google-java-format)
./mvnw -Pintegration-tests verify    # integration tests against ClickHouse in Docker
./mvnw -Pcoverage verify             # JaCoCo coverage reports
./mvnw -Pbenchmarks package          # JMH benchmarks jar
```

To test against a specific server release:

```bash
./mvnw -Pintegration-tests verify -Dchord.testkit.clickhouse.image=clickhouse/clickhouse-server:26.6
```

## Commit messages

CHord uses [Conventional Commits](https://www.conventionalcommits.org/):

```
feat(protocol): decode TimezoneUpdate packets
fix(client): close transport when the addendum flush fails
docs: correct the revision table for 26.3
build: raise testcontainers to 2.0.5
ci: add java 25 to the build matrix
test(protocol): golden vectors for nested exceptions
refactor(transport): extract socket option application
chore: update licence years
```

Use scopes matching module short names (`protocol`, `codec`, `transport`, `client`, `jdbc`,
`testkit`, `observability`, `examples`, `benchmarks`) where one module dominates the change.

## The rules for protocol work

1. The ClickHouse source code is authoritative. Before touching a packet or codec, read the
   relevant reader and writer in the ClickHouse sources (start from `src/Core/ProtocolDefines.h`,
   `src/Core/Protocol.h`, `src/Client/Connection.cpp` and `src/Server/TCPHandler.cpp`) and cite
   what you verified in the pull request.
2. Every conditionally present field is gated through `ProtocolFeature`, never a bare integer
   comparison.
3. Every new encoding gets byte level golden tests with hand written expected bytes, plus
   negative tests for truncation and out of bounds lengths.
4. Every length read from the wire is bounded before allocation.
5. Unknown packets and unknown serialisation versions fail explicitly and poison the connection.
6. A feature is complete only with implementation, unit tests, integration tests against a real
   server, negative tests, Javadoc, documentation updates (`docs/protocol-compatibility.md`,
   `docs/type-support.md`), and a changelog entry.

## Code style

Formatting is owned by google-java-format via Spotless; structure is checked by Checkstyle and
SpotBugs, and warnings are errors in the core modules. Public API requires useful Javadoc; the
build fails without it. Follow the existing patterns: immutable value types, builders for
configuration, unchecked `Chord*` exceptions, no nulls across public API boundaries (use
`Optional` on decoded protocol fields that are revision gated).

## Tests are the product

If a change cannot be proven with a test, it does not merge. When a test fails, fix the cause;
never weaken or delete a test to make a build pass.

## Licensing

By contributing you agree that your contributions are licensed under the Apache License 2.0. Every
Java file carries the licence header; `./mvnw verify` checks it.
