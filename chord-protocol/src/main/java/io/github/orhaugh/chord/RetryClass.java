/*
 * Copyright 2026 Ross Haugh
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.orhaugh.chord;

/**
 * How a failure relates to retrying the operation that raised it, exposed as {@link
 * ChordException#retryClass()}.
 *
 * <p>The classification answers one question honestly: what does the client provably know about
 * whether the failed operation took effect? CHord itself never retries anything based on this
 * value; it exists so callers and pooling layers can make retry decisions without re-deriving
 * protocol knowledge. In particular, CHord never retries an operation whose outcome is unknown, and
 * neither should callers unless they have made the operation idempotent themselves, for example
 * with {@code insert_deduplication_token}.
 */
public enum RetryClass {

  /**
   * Nothing reached execution: the failure occurred while connecting, during the handshake, or
   * before the server accepted the request. Retrying cannot duplicate work or data.
   */
  SAFE_TO_RETRY,

  /**
   * Execution may have started before the failure, so retrying repeats any side effects the
   * statement has. Safe to retry only when the caller knows the statement is idempotent.
   */
  RETRY_ONLY_IF_IDEMPOTENT,

  /**
   * The operation's effects may or may not have been applied, and the client cannot find out: for
   * example a connection lost after INSERT data was streamed, or the server reporting {@code
   * UNKNOWN_STATUS_OF_INSERT}. Retrying risks duplicating data; do not retry without an application
   * level idempotency mechanism.
   */
  OUTCOME_UNKNOWN,

  /**
   * The failure is deterministic: configuration, authentication, SQL or type errors that a retry
   * would reproduce exactly. Fix the cause instead of retrying.
   */
  NOT_RETRYABLE
}
