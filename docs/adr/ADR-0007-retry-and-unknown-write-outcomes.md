# ADR-0007: Retry and unknown write outcomes

Status: Accepted (2026-07-21), implemented in Phase 5 (2026-07-22) as `RetryClass` on every
`ChordException`, with throw site refinement and the server code table; see also ADR-0013.

## Context

Blind retries turn transient network failures into duplicate data. An INSERT whose connection
died after data was sent may or may not have been applied; the client cannot know.

## Decision

Every failure is classified before any retry decision, using four categories:

```text
SAFE_TO_RETRY             nothing reached the server, or the operation is read only and undelivered
RETRY_ONLY_IF_IDEMPOTENT  the operation may have partially executed; replay needs a guarantee
OUTCOME_UNKNOWN           bytes were accepted but the result is unknowable; never auto retried
NOT_RETRYABLE             deterministic failure (authentication, SQL error, configuration)
```

Queries are retried automatically only before any result block has been exposed to the caller.
INSERT retries require one of: no row bytes were accepted by the server, an explicit caller
provided idempotency guarantee, or a ClickHouse deduplication token. `OUTCOME_UNKNOWN` is
surfaced, never absorbed. Retry decisions and attempts are observable through diagnostics.

This ADR fixes the semantics now, ahead of the Phase 5 implementation, so earlier phases (error
mapping, state machine) are built to carry the classification.

## Alternatives

- Retry on any `IOException`: rejected; it is exactly how drivers duplicate writes.
- No automatic retries at all: rejected as needlessly harsh for provably safe cases, but it
  remains the fallback whenever classification is uncertain.

## Consequences

- The exception mapping must preserve enough context (what had been sent, what had been
  received) to classify; this shapes transport and protocol error types from the start.
- Failover (Phase 5) consults the same classification; a node switch never converts an unknown
  outcome into a replay.
