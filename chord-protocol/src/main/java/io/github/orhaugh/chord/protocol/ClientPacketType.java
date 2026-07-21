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
package io.github.orhaugh.chord.protocol;

/**
 * Packet types a client sends to the server, mirroring {@code Protocol::Client} in {@code
 * src/Core/Protocol.h} of the ClickHouse sources (verified 2026-07-21).
 */
public enum ClientPacketType {

  /** Client identification, protocol revision, default database and credentials. */
  HELLO(0),
  /** Query text, settings, parameters and execution stage. */
  QUERY(1),
  /** A native data block, used for INSERT payloads and external tables. */
  DATA(2),
  /** Request to cancel the running query. */
  CANCEL(3),
  /** Liveness check; the server answers with Pong. */
  PING(4),
  /** Status request for a set of tables. */
  TABLES_STATUS_REQUEST(5),
  /** Keep the connection alive. */
  KEEP_ALIVE(6),
  /** A data block carrying a scalar subquery result. */
  SCALAR(7),
  /** Obsolete part UUID deduplication list; kept for protocol compatibility. */
  IGNORED_PART_UUIDS(8),
  /** Response to a ReadTaskRequest in cluster table functions. */
  READ_TASK_RESPONSE(9),
  /** Parallel replicas coordination response. Not produced by CHord. */
  MERGE_TREE_READ_TASK_RESPONSE(10),
  /** Request for an SSH signature challenge. */
  SSH_CHALLENGE_REQUEST(11),
  /** Reply to an SSH signature challenge. */
  SSH_CHALLENGE_RESPONSE(12),
  /** A serialised query plan. Not produced by CHord. */
  QUERY_PLAN(13),
  /** Parallel replicas announcement response. Not produced by CHord. */
  MERGE_TREE_ALL_RANGES_ANNOUNCEMENT_RESPONSE(14);

  private final long code;

  ClientPacketType(long code) {
    this.code = code;
  }

  /**
   * Returns the numeric packet identifier written to the wire.
   *
   * @return the wire code
   */
  public long code() {
    return code;
  }
}
