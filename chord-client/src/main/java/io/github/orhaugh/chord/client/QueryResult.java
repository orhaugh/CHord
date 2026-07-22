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
import io.github.orhaugh.chord.protocol.ProfileInfo;
import io.github.orhaugh.chord.protocol.Progress;
import java.util.Optional;

/**
 * A streaming SELECT result: columnar blocks consumed one at a time with bounded memory.
 *
 * <p>The zero row header block carrying the schema is available through {@link #header()} before
 * any row data is consumed. {@link #nextBlock()} returns each data carrying block in order and
 * empty when the stream has concluded; Progress, ProfileInfo, ProfileEvents, Totals, Extremes, Log
 * and TimezoneUpdate packets interleaved with data are consumed transparently and exposed through
 * their accessors. A server Exception packet ends the stream by throwing the mapped {@link
 * io.github.orhaugh.chord.ChordServerException} from {@code nextBlock()}.
 *
 * <p>Results must be closed. Closing before exhaustion drains the remaining server response so the
 * connection can be reused; when draining cannot complete cleanly, the connection is closed instead
 * of being returned in an unknown state. One result may be open per connection at a time.
 */
@Experimental
public interface QueryResult extends AutoCloseable {

  /**
   * Returns the schema header block: zero rows, one column definition per result column. Absent for
   * statements that produce no result stream.
   *
   * @return the header block
   */
  Optional<Block> header();

  /**
   * Returns the next block carrying rows, or empty when the stream has concluded.
   *
   * @return the next data block
   */
  Optional<Block> nextBlock();

  /**
   * Returns the progress counters accumulated so far.
   *
   * @return accumulated progress
   */
  Progress totalProgress();

  /**
   * Returns the profile information, available once the server has sent it, typically at stream
   * end.
   *
   * @return the profile information
   */
  Optional<ProfileInfo> profileInfo();

  /**
   * Returns the profile event counters accumulated so far, keyed by event name: increments sum
   * across packets and gauges keep their latest value.
   *
   * @return event names to values
   */
  java.util.Map<String, Long> profileEvents();

  /**
   * Returns the totals block of a {@code WITH TOTALS} query, once received.
   *
   * @return the totals block
   */
  Optional<Block> totals();

  /**
   * Returns the extremes block when the {@code extremes} setting is enabled, once received.
   *
   * @return the extremes block
   */
  Optional<Block> extremes();

  /**
   * Requests cancellation of the running query: sends the Cancel packet and returns immediately.
   *
   * <p>The stream then concludes on the server's terms: packets already in flight, possibly
   * including partial data, keep arriving until the server answers the cancel with EndOfStream (it
   * sends no Exception packet for a client cancel). Keep consuming, or call {@link #close()} to
   * drain; after a clean drain the connection is reusable. A no op once the stream has concluded.
   *
   * <p>This is the one method of a result that may be called from a different thread than the one
   * consuming the stream, so timeout managers can cancel a blocked consumer.
   */
  void cancel();

  /** Closes the result, draining any remaining server packets so the connection can be reused. */
  @Override
  void close();
}
