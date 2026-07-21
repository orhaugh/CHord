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

import io.github.orhaugh.chord.ChordProtocolException;

/**
 * Packet types a server sends to the client, mirroring {@code Protocol::Server} in {@code
 * src/Core/Protocol.h} of the ClickHouse sources (verified 2026-07-21).
 *
 * <p>Some packets only occur on connections that take part in distributed execution between
 * servers; {@link #expectedByExternalClients()} is {@code false} for those. CHord recognises them
 * so an unexpected arrival produces a precise error instead of a desynchronised stream, but it does
 * not implement inter-server behaviour.
 */
public enum ServerPacketType {

  /** Server identification, protocol revision and negotiated capabilities. */
  HELLO(0, true),
  /** A native data block, possibly compressed. */
  DATA(1, true),
  /** An error raised while processing the request. */
  EXCEPTION(2, true),
  /** Query execution progress counters. */
  PROGRESS(3, true),
  /** Answer to a client Ping. */
  PONG(4, true),
  /** All packets for the current request have been transmitted. */
  END_OF_STREAM(5, true),
  /** Profiling information about the executed query. */
  PROFILE_INFO(6, true),
  /** A block with total values. */
  TOTALS(7, true),
  /** A block with minimum and maximum values. */
  EXTREMES(8, true),
  /** Answer to a TablesStatusRequest. */
  TABLES_STATUS_RESPONSE(9, true),
  /** Server log entries for the running query. */
  LOG(10, true),
  /** Column descriptions for default value calculation. */
  TABLE_COLUMNS(11, true),
  /** Obsolete part UUID list; kept for protocol compatibility. */
  PART_UUIDS(12, false),
  /** Inverted request used by cluster table functions. */
  READ_TASK_REQUEST(13, false),
  /** Profile event counters from the server. */
  PROFILE_EVENTS(14, true),
  /** Parallel replicas range announcement. Inter-server coordination. */
  MERGE_TREE_ALL_RANGES_ANNOUNCEMENT(15, false),
  /** Parallel replicas read task request. Inter-server coordination. */
  MERGE_TREE_READ_TASK_REQUEST(16, false),
  /** The session default timezone changed. */
  TIMEZONE_UPDATE(17, true),
  /** Challenge for SSH key authentication. */
  SSH_CHALLENGE(18, true);

  private final long code;
  private final boolean expectedByExternalClients;

  ServerPacketType(long code, boolean expectedByExternalClients) {
    this.code = code;
    this.expectedByExternalClients = expectedByExternalClients;
  }

  /**
   * Returns the numeric packet identifier as read from the wire.
   *
   * @return the wire code
   */
  public long code() {
    return code;
  }

  /**
   * Reports whether an ordinary external client can legitimately receive this packet. Packets used
   * for inter-server coordination return {@code false}.
   *
   * @return {@code true} for packets external clients receive
   */
  public boolean expectedByExternalClients() {
    return expectedByExternalClients;
  }

  /**
   * Resolves a wire code to a packet type, failing explicitly on unknown values. Unknown packet
   * identifiers are never skipped or guessed at: the stream position after an unknown packet is
   * unknowable, so the connection must be abandoned.
   *
   * @param code numeric packet identifier read from the wire
   * @return the packet type
   */
  public static ServerPacketType fromCode(long code) {
    for (ServerPacketType type : values()) {
      if (type.code == code) {
        return type;
      }
    }
    throw new ChordProtocolException(
        "Unknown server packet type "
            + code
            + "; the connection is desynchronised or the server"
            + " speaks a newer protocol revision than it negotiated");
  }
}
