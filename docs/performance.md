# Performance

## Current state

Preliminary comparative numbers against the official Java HTTP client exist below, measured on
a development laptop and labelled accordingly. Baseline grade numbers (quiet dedicated
hardware, native Linux, pinned cores) do not exist yet; nothing here graduates to marketing
material until they do.

## Preliminary: native TCP versus the official HTTP client

`HttpComparisonBenchmark` runs CHord and `com.clickhouse:client-v2` (HTTP, RowBinary) against
the same containerised server, LZ4 in both directions, single threaded, measuring the client
stacks as users consume them (encode and decode included).

Environment: Apple M2 Pro (12 cores, 32 GiB), macOS 26.3.1, Corretto 21.0.8, CHord commit
c97c3a2, client-v2 0.9.8, ClickHouse 25.8 in Docker Desktop. JMH fork 1; run A: 3 warmup and
5 measurement iterations of 3s; run B: 4 and 12 with forced GC between iterations. A
development laptop behind Docker Desktop's VM is not a rigorous environment: absolute numbers
moved by roughly 2x between runs as the machine heated and the VM contended, so the
within run ratios are the signal and the absolutes are indicative only.

| Shape | CHord | client-v2 | Ratio |
|---|---|---|---|
| SELECT 1M rows x 3 columns (run A) | 22.0 ops/s (~22M rows/s) | 8.6 ops/s | 2.6x |
| SELECT 1M rows x 3 columns (run B) | 12.1 ops/s | 4.0 ops/s | 3.1x |
| INSERT 100k rows, Null engine (run A) | 69.7 ops/s (~7.0M rows/s) | 58.1 ops/s | 1.2x |
| INSERT 100k rows, Null engine (run B) | 39.4 ops/s | 29.5 ops/s | 1.3x |
| Point query latency (run A) | 704 us | 923 us | inconclusive |
| Point query latency (run B) | 1149 us | 873 us | inconclusive |

What can honestly be said today:

- Streaming reads: CHord decodes the million row scan at roughly 2.5x to 3x the HTTP
  client's throughput, consistently across both runs. This is the shape where skipping the
  HTTP layer and decoding native blocks directly pays most.
- Batch inserts: a consistent but modest edge, roughly 1.2x to 1.3x. Both stacks bottleneck
  substantially on value encoding and the server's parse, not the transport.
- Point query latency: no claim. Sub millisecond round trips through Docker Desktop's
  virtualised network are dominated by VM jitter; the two runs disagree on the sign. This
  needs native Linux and a quiet machine.

Reproduce with:

```bash
java -jar chord-benchmarks/target/chord-benchmarks.jar HttpComparison -wi 4 -i 12 -r 3s -w 3s -gc true
```

## Preliminary: ClickHouse Cloud over the public internet

The same benchmark against a ClickHouse Cloud development instance (ap-south-1) from the
same laptop, TLS both stacks (native 9440 with system trust, https 8443), LZ4 both
directions, roughly 225ms round trip. All writes isolated in a scratch database created and
dropped by the harness. Config via `-Dchord.bench.config` (see the benchmark javadoc).

| Shape | CHord | client-v2 | Reading |
|---|---|---|---|
| Point query latency (same run) | 178.4 ms +-7.7 | 233.1 ms +-28.5 | CHord ~55 ms faster per request, non overlapping intervals |
| SELECT 1M rows (paired window) | 0.331 ops/s +-0.280 | 0.288 ops/s +-0.104 | indistinguishable; the path is the bottleneck |
| INSERT 100k rows (same run) | 0.480 ops/s +-0.135 | 0.592 ops/s +-0.179 | overlapping intervals; roughly par |

Findings, in order of confidence:

- The latency win is real and reproducible: a warm native TCP connection answers point
  queries roughly 24% faster than the HTTP client at WAN distance, with non overlapping
  confidence intervals measured in the same run. Per request HTTP overhead is a per query
  tax that the persistent native connection does not pay.
- WAN throughput is a property of the path, not the client. The same CHord benchmark
  measured 0.043 ops/s in one window and 0.332 ops/s an hour later; within any single
  window the two clients are statistically indistinguishable. Cross window comparisons over
  the public internet are meaningless, which is why the initial full run (which measured
  benchmarks minutes apart) briefly made the HTTP client look 5x faster at streaming.
- Socket buffer sizing is not a factor: forcing 4 MiB receive and send buffers moved CHord
  throughput from 0.332 to 0.330 ops/s. The OS default autotuning already does its job.

Cloud throughput claims need a runner co located with the instance (same region, ideally
same availability zone); that is the next environment on the list, and it doubles as the
baseline grade Linux environment the local section is waiting for.

## Running the benchmarks

```bash
./mvnw package -pl chord-benchmarks -am -DskipTests
java -jar chord-benchmarks/target/chord-benchmarks.jar                  # micro benchmarks
java -jar chord-benchmarks/target/chord-benchmarks.jar VarInt           # by name filter
java -jar chord-benchmarks/target/chord-benchmarks.jar HttpComparison   # vs the official HTTP client (needs Docker)
./mvnw -Pbench-smoke verify -pl chord-benchmarks -am -DskipTests        # minimal smoke of every micro benchmark
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
