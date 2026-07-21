# Performance

## Current state

There are **no published performance numbers yet** and none will be published without the
methodology below. The benchmark scaffold exists (`chord-benchmarks`, JMH) and grows with the
codec and client work; end to end comparisons against the official Java HTTP client and native
baselines are part of Phase 8.

## Running the benchmarks

```bash
./mvnw -Pbenchmarks package -DskipTests
java -jar chord-benchmarks/target/chord-benchmarks.jar            # all benchmarks
java -jar chord-benchmarks/target/chord-benchmarks.jar VarInt     # by name filter
```

## Methodology rules

Numbers without context are noise. Any recorded result must state:

- Hardware (CPU model, core count, memory) and OS
- JDK vendor and version, and all JVM flags
- CHord commit
- ClickHouse server version and where it ran (local container, remote host)
- Schema, data volume and query shape for end to end runs
- JMH configuration (forks, warmup, measurement)

Optimisation follows measurement, never intuition: JFR and allocation profiling come before any
object pooling or off heap scheme. Planned benchmark areas match the roadmap: primitive and
string decoding, nullable columns, arrays and maps, LowCardinality, compression, query result
streaming, INSERT block construction, type parsing and pool acquire and release.

## Regression tracking

Once stable baselines exist (Phase 8), scheduled CI runs the JMH suite and compares against
recorded baselines with realistic thresholds; regressions fail the scheduled job and are triaged
like bugs. Baselines are stored in the repository alongside the environment description that
produced them.
