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

import io.github.orhaugh.chord.codec.column.BlockBuilder;
import java.util.Collection;
import java.util.Map;

/**
 * Row at a time convenience over an {@link InsertStream}: rows accumulate into blocks that flush
 * automatically once either threshold is reached, so callers stop thinking about block boundaries.
 * Obtain through {@link InsertStream#buffered(int, long)}.
 *
 * <p>The byte threshold works on a rough client side estimate (primitive widths, string lengths,
 * container sums), intended for keeping blocks near a target size, not for exact accounting. {@link
 * #finish()} flushes the remainder and commits; closing without finishing keeps the underlying
 * stream's hard abort semantics, so nothing commits implicitly.
 */
public final class BufferedInsert implements AutoCloseable {

  private final InsertStream insert;
  private final int maxRows;
  private final long maxEstimatedBytes;
  private BlockBuilder builder;
  private long estimatedBytes;

  BufferedInsert(InsertStream insert, int maxRows, long maxEstimatedBytes) {
    this.insert = insert;
    this.maxRows = maxRows;
    this.maxEstimatedBytes = maxEstimatedBytes;
  }

  /**
   * Appends one row, flushing a block when a threshold is crossed.
   *
   * @param values one value per schema column
   * @return this inserter
   */
  public BufferedInsert addRow(Object... values) {
    if (builder == null) {
      builder = insert.newBlock();
      estimatedBytes = 0;
    }
    builder.addRow(values);
    for (Object value : values) {
      estimatedBytes += estimate(value);
    }
    if (builder.rows() >= maxRows || estimatedBytes >= maxEstimatedBytes) {
      flush();
    }
    return this;
  }

  /** Sends the buffered rows as a block now; a no op when nothing is buffered. */
  public void flush() {
    if (builder != null && builder.rows() > 0) {
      insert.send(builder.build());
    }
    builder = null;
    estimatedBytes = 0;
  }

  /**
   * Flushes the remainder and commits the insert.
   *
   * @return the insert summary
   */
  public InsertStream.InsertSummary finish() {
    flush();
    return insert.finish();
  }

  /** Closes the underlying stream; without {@link #finish()} the insert hard aborts. */
  @Override
  public void close() {
    insert.close();
  }

  private static long estimate(Object value) {
    if (value == null) {
      return 1;
    }
    if (value instanceof String s) {
      return 9L + s.length();
    }
    if (value instanceof byte[] b) {
      return 9L + b.length;
    }
    if (value instanceof Number || value instanceof Boolean) {
      return 8;
    }
    if (value instanceof Collection<?> collection) {
      long sum = 9;
      for (Object element : collection) {
        sum += estimate(element);
      }
      return sum;
    }
    if (value instanceof Map<?, ?> map) {
      long sum = 9;
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        sum += estimate(entry.getKey()) + estimate(entry.getValue());
      }
      return sum;
    }
    return 16; // temporal values, UUIDs and other fixed width shapes
  }
}
