# CHord

A Java client for [ClickHouse](https://clickhouse.com) built on the ClickHouse **native TCP wire
protocol**. The native protocol implementation is the product: no HTTP tunnelling, no JDBC as the
internal abstraction, no wrapping of `clickhouse-client`.

[![CI](https://github.com/orhaugh/CHord/actions/workflows/ci.yml/badge.svg)](https://github.com/orhaugh/CHord/actions/workflows/ci.yml)
[![ClickHouse compatibility](https://github.com/orhaugh/CHord/actions/workflows/compatibility.yml/badge.svg)](https://github.com/orhaugh/CHord/actions/workflows/compatibility.yml)
[![License](https://img.shields.io/badge/licence-Apache%202.0-blue.svg)](LICENSE)

## Status: early development

CHord is pre 0.1 and **not yet published to Maven Central**. The entire public API is
experimental and will change. What exists today is the foundation and the first slice of the
protocol, implemented against the current ClickHouse sources and tested against real servers:

| Capability | Status |
|---|---|
| Native TCP transport with connect and read deadlines | Done |
| Handshake at protocol revision 54488, including the server settings block, chunked capability negotiation and password complexity rules | Done, byte level and integration tested |
| Password authentication with typed failures | Done |
| Ping and Pong with stale Progress tolerance | Done |
| Connection state machine, no reuse after protocol violations | Done |
| Plaintext password protection (explicit opt in required) | Done |
| TLS and mutual TLS: hostname verification always on, SNI, system/JKS/PKCS#12/PEM trust, PEM and key store client material, expiry diagnostics | Done, tested against local TLS servers and real ClickHouse |
| Streaming SELECT: Query packet with full ClientInfo, native block decoding, typed columnar access, parameters, settings, totals, extremes, profile info, progress | Done, byte level and integration tested, values differentially checked against clickhouse-client |
| Native INSERT streaming: server driven schema, typed block building, multi block streaming, defaults metadata, async insert settings, no implicit partial commits | Done, round trip and integration tested |
| Compression (LZ4, LZ4HC, ZSTD) with checksum validation and bomb limits, per connection or per query | Done, golden and integration tested against real servers |
| Chunked packet framing in both directions, including strict `proto_caps` servers | Done, byte level and integration tested |
| Server logs and typed profile event counters, raw and compressed | Done, integration tested |
| Connection pool: validation by ping, lifetime and idle eviction, leak diagnostics | Done, tested against loopback and real servers |
| Multi endpoint failover: policies, health backoff with jitter, per attempt DNS resolution | Done, tested |
| Retry classification on every exception; never an automatic retry | Done, tested; see ADR-0007 and ADR-0013 |
| Cancel packet, per request timeouts, cancellation draining, cancel grace | Done, integration tested |
| Progress and server log listeners, insert deduplication token | Done, integration tested |
| JFR events (Connect, Query, Insert, PoolAcquire) and Micrometer pool gauges | Done |
| LowCardinality read and write, sparse column decode, Variant, Dynamic and JSON decode | Done, golden tested against real server output and integration tested |
| JDBC 4.3 adapter: Driver, DataSource, prepared statements with native block batching, honest metadata | Done, integration tested through DriverManager |

The full roadmap with per feature milestones lives in
[docs/unsupported-features.md](docs/unsupported-features.md). Protocol coverage is tracked in
[docs/protocol-compatibility.md](docs/protocol-compatibility.md) and type coverage in
[docs/type-support.md](docs/type-support.md). Nothing is claimed as supported without automated
tests.

## Supported ClickHouse servers

CHord is tested in CI against every currently supported ClickHouse release: **25.8 (LTS), 26.3
(LTS) and 26.6**, plus the newest builds in a nightly compatibility sweep. Servers older than
protocol revision 54458 are refused explicitly. A protocol revision is not a server version; see
[docs/protocol-compatibility.md](docs/protocol-compatibility.md).

## Requirements

- Java 21 or newer at runtime (artifacts are built with `--release 21`)
- ClickHouse 25.8 or newer

## Using it today

The first Maven Central release (0.1.0) ships under these coordinates, aligned by the BOM:

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>io.github.orhaugh</groupId>
      <artifactId>chord-bom</artifactId>
      <version>0.1.0</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>

<dependency>
  <groupId>io.github.orhaugh</groupId>
  <artifactId>chord-client</artifactId> <!-- or chord-jdbc for the JDBC driver -->
</dependency>
```

Until that release lands in the portal, build and install locally:

```bash
git clone https://github.com/orhaugh/CHord.git
cd CHord
./mvnw install
```

Then, with `chord-client` on the classpath:

```java
import io.github.orhaugh.chord.client.ConnectionOptions;
import io.github.orhaugh.chord.client.NativeConnection;
import io.github.orhaugh.chord.client.QueryRequest;
import io.github.orhaugh.chord.client.QueryResult;

ConnectionOptions options = ConnectionOptions.builder()
    .host("localhost")
    .port(9000)
    .username("default")
    .build();

try (NativeConnection connection = NativeConnection.open(options)) {
    connection.ping();

    QueryRequest request = QueryRequest.builder(
            "SELECT number, toString(number) AS s FROM system.numbers LIMIT {n:UInt64}")
        .parameter("n", 1000)
        .setting("max_block_size", 65536)
        .build();

    try (QueryResult result = connection.query(request)) {
        var header = result.header().orElseThrow(); // schema before any rows
        java.util.Optional<io.github.orhaugh.chord.codec.block.Block> next;
        while ((next = result.nextBlock()).isPresent()) {
            var block = next.orElseThrow();
            var numbers = (io.github.orhaugh.chord.codec.column.Columns.UInt64Column) block.column(0);
            for (int i = 0; i < block.rows(); i++) {
                long value = numbers.rawLongAt(i); // no per cell allocation
            }
        }
        System.out.println("read " + result.totalProgress().readRows() + " rows");
    }
}
```

Blocks stream one at a time with bounded memory; the columnar accessors are the primary API and
never allocate one object per cell on the main path.

Inserts are schema driven: the server supplies the target structure, values are validated
losslessly at append time, and nothing is committed until `finish()`:

```java
try (InsertStream insert = connection.insert(
        QueryRequest.of("INSERT INTO telemetry (ts, sensor, value) VALUES"))) {
    var builder = insert.newBlock(); // typed against the server supplied schema
    builder.addRow(Instant.now().truncatedTo(ChronoUnit.SECONDS), "s-1", 21.5d);
    builder.addRow(Instant.now().truncatedTo(ChronoUnit.SECONDS), "s-2", 19.0d);
    insert.send(builder.build());
    var summary = insert.finish(); // terminal empty block, drains to EndOfStream
    System.out.println("wrote " + summary.progress().writtenRows() + " rows");
}
```

Closing an insert without `finish()` hard aborts the connection instead of committing partial
data, and failed inserts leave the connection reusable because a server exception is a defined
stream terminator.

The production path for password authentication is TLS; the default port becomes 9440 and
hostname verification is always on:

```java
import io.github.orhaugh.chord.transport.TlsOptions;

ConnectionOptions options = ConnectionOptions.builder()
    .host("clickhouse.example.com")
    .username("app")
    .password(secretChars)
    .tls(TlsOptions.systemTrust()) // or trustedCertificates(caPem) for a private CA
    .build();
```

Mutual TLS presents a client certificate from PEM or a key store:

```java
TlsOptions mutualTls = TlsOptions.builder()
    .trustedCertificates(Path.of("ca.crt"))
    .clientCertificate(Path.of("client.crt"), Path.of("client.key"), null)
    .build();
```

Password authentication over plain TCP requires an explicit opt in, because the native protocol
carries the password verbatim:

```java
ConnectionOptions options = ConnectionOptions.builder()
    .host("localhost")
    .username("app")
    .password(secretChars)
    .allowPlaintextPassword(true) // development only; prefer tls(...)
    .build();
```

A runnable version of this is in
[chord-examples](chord-examples/src/main/java/io/github/orhaugh/chord/examples/PingExample.java).

## Modules

| Module | Purpose |
|---|---|
| `chord-protocol` | Wire primitives, packet models, revision registry, handshake codecs, state machine, exceptions. Zero dependencies. |
| `chord-codec` | The recursive type model and parser, column codecs, native block decoding and encoding, the typed `BlockBuilder`, and the compressed frame codec with CityHash 1.0.2 checksums. |
| `chord-transport` | Blocking TCP and TLS transports behind an SPI. |
| `chord-client` | The client API: `NativeConnection` with handshake, ping, streaming SELECT (`QueryResult`) and streaming INSERT (`InsertStream`). |
| `chord-observability` | The Micrometer binding for pool metrics. Per operation timing is emitted as JFR events by `chord-client` itself; OpenTelemetry is reachable through Micrometer registry bridges. |
| `chord-jdbc` | The JDBC 4.3 adapter over the native client: `jdbc:chord://` URLs, prepared statements that batch through native blocks, and metadata over the system tables. |
| `chord-testkit` | Testcontainers fixture for ClickHouse with native port access. |
| `chord-examples` | Runnable examples. Not published. |
| `chord-benchmarks` | JMH benchmarks, built with `-Pbenchmarks`. Not published. |
| `chord-bom` | Bill of materials aligning module versions. |

## Building and testing

```bash
./mvnw verify                       # build, unit tests, static analysis
./mvnw -Pcoverage verify            # adds JaCoCo reports
./mvnw -Pintegration-tests verify   # adds Testcontainers integration tests (needs Docker)
./mvnw -Pcompatibility-tests verify -Dchord.testkit.clickhouse.image=clickhouse/clickhouse-server:26.6
./mvnw -Pbenchmarks package         # builds the JMH jar
```

Formatting is enforced by google-java-format through Spotless; run `./mvnw spotless:apply` before
committing. See [CONTRIBUTING.md](CONTRIBUTING.md) for the full workflow and
[docs/architecture.md](docs/architecture.md) for how the modules fit together.

## Design rules that will not change

- The ClickHouse source code is the protocol authority; documentation comes second.
- Unknown packets, unknown serialisation versions and out of bounds lengths are explicit errors,
  never guesses. A connection that has seen one is never reused.
- Every length read from the wire is treated as hostile and bounded.
- Writes with unknown outcomes are not retried automatically.
- No feature is marked supported without byte level and real server tests.

## Security

See [SECURITY.md](SECURITY.md) for the vulnerability reporting process.

## Licence

[Apache License 2.0](LICENSE). Copyright 2026 Ross Haugh.
