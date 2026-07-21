# ADR-0002: Blocking I/O with virtual threads

Status: Accepted (2026-07-21)

## Context

A ClickHouse native connection is stateful and processes one protocol exchange at a time, so
per connection parallelism does not exist to exploit. Scalability comes from many connections.
Java 21 virtual threads make blocking code scale to that shape without an event loop.

## Decision

The protocol engine is blocking, layered over `java.net.Socket`. Concurrency is provided by
running connections on virtual threads; the asynchronous API planned for the client is a thin
layer over the blocking engine. No custom event loop, no Netty, no async state machines in the
protocol core.

`Socket` was chosen over blocking `SocketChannel` because `SO_TIMEOUT` gives read deadlines,
which blocking channel reads cannot provide; both park correctly on virtual threads. Hard
cancellation is performed by closing the socket from another thread, which unblocks a pending
read. The transport sits behind the `NativeTransport` SPI, so this choice is revisitable without
touching protocol code.

## Alternatives

- Netty or a custom event loop: rejected; complexity without benefit for one exchange per
  connection semantics, and it would force the public API asynchronous.
- Blocking `SocketChannel`: rejected for the missing read timeout; interruptibility is not worth
  losing deadlines, and close based aborts work for both.

## Consequences

- Read deadlines are socket level (`SO_TIMEOUT`) and enforced uniformly by the wire reader.
- Thread interruption does not abort socket reads; cancellation is close based plus the protocol
  level Cancel packet in Phase 5.
- Published modules must not depend on preview APIs; virtual threads are stable in the baseline.
