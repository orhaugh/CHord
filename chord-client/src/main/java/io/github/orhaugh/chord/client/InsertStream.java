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
import io.github.orhaugh.chord.codec.column.BlockBuilder;
import io.github.orhaugh.chord.protocol.Progress;
import java.util.Optional;

/**
 * A streaming native INSERT: the server supplied schema, block sending and the explicit finish that
 * commits the stream.
 *
 * <p>The lifecycle is deliberate. {@link #schema()} is available immediately, decoded from the
 * header block the server sends when the INSERT begins. Each {@link #send(Block)} streams one
 * block; blocks must match the schema. {@link #finish()} sends the terminal empty block, drains the
 * server response and returns the summary; only then is the data committed and the connection
 * reusable.
 *
 * <p>Closing without {@link #finish()} is a hard abort: the connection is closed outright. Sending
 * the terminal block implicitly would commit whatever had been streamed, so an abandoned insert
 * never commits partially by accident. Per the retry rules (ADR-0007), a transport failure mid
 * stream has an unknown outcome and is never retried automatically.
 */
@Experimental
public interface InsertStream extends AutoCloseable {

  /**
   * Returns the target schema block the server sent: zero rows, one column definition per
   * insertable column.
   *
   * @return the schema block
   */
  Block schema();

  /**
   * Returns the parsed table columns description, present when the server sent the TableColumns
   * packet (default expressions metadata for omitted fields).
   *
   * @return the raw columns description text
   */
  Optional<String> tableColumnsDescription();

  /**
   * Creates a builder targeting this insert's schema.
   *
   * @return a new block builder
   */
  default BlockBuilder newBlock() {
    return BlockBuilder.forSchema(schema());
  }

  /**
   * Streams one block of rows. The block's column count, names and types must match the schema.
   *
   * @param block the block to send
   */
  void send(Block block);

  /**
   * Sends the terminal empty block, drains the server response to EndOfStream and returns the
   * insert summary. A server side rejection surfaces here as {@link
   * io.github.orhaugh.chord.ChordServerException}; the connection remains usable afterwards.
   *
   * @return the summary with progress counters
   */
  InsertSummary finish();

  /**
   * Releases the stream. A no op after {@link #finish()}; otherwise a hard abort that closes the
   * connection so partially streamed data is never committed implicitly.
   */
  @Override
  void close();

  /**
   * The outcome of a finished insert.
   *
   * @param rowsSent rows streamed by this client across all blocks
   * @param blocksSent blocks streamed by this client
   * @param progress server reported progress, including written rows and bytes
   */
  record InsertSummary(long rowsSent, long blocksSent, Progress progress) {}
}
