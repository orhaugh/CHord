# ADR-0006: Connection reuse rules

Status: Accepted (2026-07-21)

## Context

The native protocol has no framing recovery. After an unknown packet, a length outside bounds, a
truncated read or a timeout mid exchange, the client cannot know where the next packet boundary
is. Reusing such a connection risks interpreting arbitrary bytes as protocol, the road to silent
data corruption.

## Decision

Every connection is guarded by an explicit state machine (`ConnectionStateMachine`). Any protocol
violation, timeout or transport failure during an exchange moves the connection to `BROKEN`,
whose only legal exit is `CLOSED`. Broken connections are never returned to a pool and never
reused; there is no resynchronisation heuristic by design. A connection is reusable only after a
protocol exchange concludes cleanly (for example Pong received, or later EndOfStream fully
drained). Cancellation (Phase 5) drains the server response completely before the connection can
be reused, and closes it when clean draining cannot be proven.

## Alternatives

- Resynchronisation by scanning for plausible packet boundaries: rejected outright; it converts
  protocol bugs into data corruption.
- Implicit state via flags: rejected; the enum state machine makes invalid transitions
  impossible to miss and cheap to test.

## Consequences

- Some recoverable looking situations (a timeout caused by a slow query) still cost the
  connection. That price is accepted; correctness beats connection churn.
- Pool implementations get a simple contract: only `READY` connections are eligible.
