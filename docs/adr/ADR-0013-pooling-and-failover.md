# ADR-0013: Pooling, failover and cancellation semantics

Status: Accepted (2026-07-22)

## Context

Phase 5 adds the resilience layer: a connection pool, multi endpoint failover, client side query
timeouts and the Cancel packet. Each of these can silently violate the project's core rules
(never reuse a desynchronised connection, never duplicate writes, never lie about outcomes) if
designed casually, so the semantics are fixed here.

## Decision

### Pool

- The pool leases `NativeConnection`s; a lease returning in any state other than `READY` is
  closed and discarded, never repooled. The pool cannot repair a connection, only replace it.
- Idle connections are validated with a real protocol ping when they have rested longer than the
  validation interval; validation failures discard the connection silently and open a fresh one.
- A maximum lifetime bounds every connection regardless of health, so server side session state
  and network path changes cannot accumulate; an idle timeout reclaims capacity.
- Leak diagnostics record the acquire site stack trace and report once per lease past the
  threshold. Diagnostics never revoke a lease: revocation would race the borrower mid exchange.
- The pool never retries any operation. It multiplexes connections, nothing else.

### Failover

- `FailoverConnector` orders endpoints by policy (in order, round robin, random), skips endpoints
  in backoff, and returns the first connection that opens. Backoff is exponential with full
  jitter, reset by any success.
- When every candidate failed or is backing off, one final walk ignores backoff so an already
  recovered endpoint is used immediately rather than after its backoff expires.
- Failover applies to opening connections only. Operations are never re-run: a failed operation
  carries a retry classification and retrying is the caller's decision.
- Hostnames are resolved at every attempt so DNS failover works without restarts.

### Retry classification

- Every `ChordException` reports a `RetryClass`. Type defaults are conservative
  (`OUTCOME_UNKNOWN` for transport, timeout, protocol and corruption failures;
  `NOT_RETRYABLE` for deterministic errors); throw sites refine with exchange phase knowledge
  (connect and handshake failures are `SAFE_TO_RETRY`; mid query failures are
  `RETRY_ONLY_IF_IDEMPOTENT`). The first classification wins so outer layers cannot weaken an
  inner judgement.
- Server error codes map through a deliberately small table (admission control rejections are
  safe; transient resource conditions require idempotence; `UNKNOWN_STATUS_OF_INSERT` is
  unknown; everything else deterministic). Codes verified against `ErrorCodes.cpp` at master.
- CHord itself never auto retries. `insertDeduplicationToken` provides the idempotence mechanism
  for callers who choose to retry inserts.

### Cancellation and deadlines

- `QueryResult.cancel()` sends the Cancel packet (client packet 3) at most once per exchange,
  serialised against the consuming thread; the stream then concludes on the server's terms and
  the connection returns to `READY` after the drain. The server answers a client cancel with
  EndOfStream, not an Exception, matching `TCPHandler::processCancel`.
- A per request `timeout` is enforced at packet boundaries by polling the transport for
  readability (one byte read under a temporary `SO_TIMEOUT`, buffered for the next read), never
  by interrupting a read mid value. On expiry the Cancel is sent and the connection's
  `cancelGrace` bounds the drain; a stream still unconcluded then is abandoned and the
  connection closed, because its protocol position is unknowable.
- A timed out query always surfaces `ChordTimeoutException`, even when the drain completed
  cleanly: a truncated result must never look like a legitimately short one.

## Alternatives

- Retrying inside the client (rejected: silently duplicates work and hides failures; the retry
  classification gives callers everything needed to decide).
- Cancelling by closing the socket (kept only as the last resort: it forfeits the connection and
  any buffered results; the Cancel packet keeps the connection reusable).
- A shared timer thread interrupting reads for deadlines (rejected: an interrupt mid value
  desynchronises the stream by construction; packet boundary polling never does).

## Consequences

- Deadline polling adds one `SO_TIMEOUT` manipulation per wait cycle on requests with timeouts;
  requests without timeouts take the untouched blocking path.
- The pool's maintenance thread is one virtual thread per pool.
- JFR events (`Connect`, `Query`, `Insert`, `PoolAcquire`, category CHord) are emitted
  unconditionally; they cost nothing measurable without an active recording and never carry SQL
  text. `chord-observability` binds pool gauges to Micrometer; OpenTelemetry is reachable through
  Micrometer bridges without a CHord dependency.
