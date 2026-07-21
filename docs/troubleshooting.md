# Troubleshooting

## Connection failures

**`ChordTransportException: Failed to connect to host:9000`**. The server is not reachable on the
native TCP port. Confirm the port: 9000 is plain TCP, 9440 is conventionally TLS (not yet
supported by CHord), 8123 is HTTP and will not work with this client. Inside Docker, make sure
the native port is published, not only 8123.

**`ChordTimeoutException: Connecting ... timed out`**. Network path or a firewall. Raise
`connectTimeout` via `ConnectionOptions.builder().connectTimeout(...)` only when latency is
genuinely high.

**`ChordProtocolException: Server protocol revision ... older than the minimum`**. The server
predates protocol revision 54458 (roughly, releases older than 22.8). CHord refuses these instead
of guessing at an untested handshake shape. Upgrade the server; every currently supported
ClickHouse release works.

## Authentication

**`ChordAuthenticationException` with code 516, 193, 192 or 194**. The server rejected the
credentials. The message never contains the password. Verify the user exists and the password is
correct; in the official Docker image these come from `CLICKHOUSE_USER` and
`CLICKHOUSE_PASSWORD`.

**`ChordConfigurationException: Refusing to send a password over a plaintext connection`**. The
deliberate safe default: the native protocol carries passwords verbatim, so CHord refuses to send
one over plain TCP. Use TLS once available; until then opt in explicitly with
`allowPlaintextPassword(true)` when the network is secured by other means (development, a private
network with encryption at a lower layer).

**`ChordServerException` with code 81** (`UNKNOWN_DATABASE`). The configured default database
does not exist on the server.

## Protocol errors

**`ChordProtocolException: Unknown server packet type ...`** or messages about bounds and
truncated streams. The stream position is no longer trustworthy; CHord marks the connection
BROKEN and it must be closed, never reused. If this happens against an unmodified ClickHouse
release, it is a CHord bug: capture TRACE logs and open an issue with the server version.

**`ChordProtocolException: Incompatible chunked protocol ...`**. The server is configured with
strict `proto_caps` requiring chunked framing, which CHord implements in Phase 4. Until then set
the server capability to an optional or notchunked mode, or wait for Phase 4.

## Timeouts mid exchange

**`ChordTimeoutException: Read timed out after consuming N bytes`**. The read deadline
(`readTimeout`, default 300 seconds) elapsed mid exchange. The connection is broken by design
afterwards, because a partially read packet cannot be resumed.

## Logging and diagnostics

CHord logs through SLF4J under `io.github.orhaugh.chord`. Connection establishment and close are
DEBUG with a numeric connection id for correlation; pings are TRACE. Packet payloads are never
logged. Example Logback configuration:

```xml
<logger name="io.github.orhaugh.chord" level="TRACE"/>
```

## Testcontainers cannot find Docker

Integration tests need a working Docker environment. Two known pitfalls:

- A stale `~/.testcontainers.properties` pinning `docker.client.strategy` can force a strategy
  that no longer matches your Docker installation; delete the line and let detection run.
- Old docker-java versions cannot talk to current Docker engines (HTTP 400 responses with an
  empty info body during detection). CHord pins Testcontainers 2.x, which bundles a current
  docker-java; if you consume `chord-testkit`, do not downgrade Testcontainers below 2.0.
