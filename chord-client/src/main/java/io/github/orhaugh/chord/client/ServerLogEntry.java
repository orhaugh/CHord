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
package io.github.orhaugh.chord.client;

import io.github.orhaugh.chord.annotations.Experimental;
import io.github.orhaugh.chord.codec.block.Block;
import io.github.orhaugh.chord.codec.column.Column;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * One server side log line delivered through a Log packet when {@code send_logs_level} is set.
 *
 * <p>Fields mirror the server's text log block. {@code priority} is the server's syslog style
 * numeric level: 1 fatal, 2 critical, 3 error, 4 warning, 5 notice, 6 information, 7 debug, 8
 * trace, 9 test.
 *
 * @param eventTime when the server produced the line, or {@code null} when the block omitted it
 * @param hostName the reporting server host
 * @param queryId the query the line belongs to
 * @param threadId the server thread that produced the line
 * @param priority the numeric level, 0 when absent
 * @param source the server component, for example {@code executeQuery}
 * @param text the message
 */
@Experimental
public record ServerLogEntry(
    Instant eventTime,
    String hostName,
    String queryId,
    long threadId,
    int priority,
    String source,
    String text) {

  /**
   * Extracts entries from a Log packet block, tolerating unknown shapes: rows missing expected
   * columns produce entries with defaults rather than failures, because server log formats have
   * changed across releases and log delivery must never break a query.
   *
   * @param block the Log packet block
   * @return the entries in block order
   */
  static List<ServerLogEntry> fromBlock(Block block) {
    Column time = block.columnByName("event_time").orElse(null);
    Column host = block.columnByName("host_name").orElse(null);
    Column query = block.columnByName("query_id").orElse(null);
    Column thread = block.columnByName("thread_id").orElse(null);
    Column priority = block.columnByName("priority").orElse(null);
    Column source = block.columnByName("source").orElse(null);
    Column text = block.columnByName("text").orElse(null);
    List<ServerLogEntry> entries = new ArrayList<>(block.rows());
    for (int row = 0; row < block.rows(); row++) {
      entries.add(
          new ServerLogEntry(
              instantAt(time, row),
              stringAt(host, row),
              stringAt(query, row),
              longAt(thread, row),
              (int) longAt(priority, row),
              stringAt(source, row),
              stringAt(text, row)));
    }
    return entries;
  }

  private static String stringAt(Column column, int row) {
    return column == null ? "" : String.valueOf(column.objectAt(row));
  }

  private static long longAt(Column column, int row) {
    return column != null && column.objectAt(row) instanceof Number number ? number.longValue() : 0;
  }

  private static Instant instantAt(Column column, int row) {
    if (column == null) {
      return null;
    }
    Object value = column.objectAt(row);
    return switch (value) {
      case Instant instant -> instant;
      case java.time.ZonedDateTime zoned -> zoned.toInstant();
      case java.time.OffsetDateTime offset -> offset.toInstant();
      case Number epochSeconds -> Instant.ofEpochSecond(epochSeconds.longValue());
      default -> null;
    };
  }
}
