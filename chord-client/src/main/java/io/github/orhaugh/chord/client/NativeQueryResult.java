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

import io.github.orhaugh.chord.ChordException;
import io.github.orhaugh.chord.ChordProtocolException;
import io.github.orhaugh.chord.codec.block.Block;
import io.github.orhaugh.chord.codec.block.BlockReader;
import io.github.orhaugh.chord.codec.block.DecodeContext;
import io.github.orhaugh.chord.protocol.ProfileInfo;
import io.github.orhaugh.chord.protocol.Progress;
import io.github.orhaugh.chord.protocol.ServerError;
import io.github.orhaugh.chord.protocol.ServerPacketType;
import io.github.orhaugh.chord.protocol.wire.WireReader;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The packet pump behind {@link QueryResult}, owned by a {@link NativeConnection}. */
final class NativeQueryResult implements QueryResult {

  private static final Logger LOG = LoggerFactory.getLogger(NativeQueryResult.class);

  /** Server log packets are consumed and logged, but only a bounded number are retained. */
  private static final int MAX_RETAINED_LOG_BLOCKS = 64;

  private final NativeConnection connection;
  private final WireReader in;
  private final String queryId;

  private Block header;
  private Block pending;
  private boolean finished;
  private boolean closed;

  private long progressReadRows;
  private long progressReadBytes;
  private long progressTotalRows;
  private long progressTotalBytes;
  private long progressWrittenRows;
  private long progressWrittenBytes;
  private long progressElapsedNanos;
  private ProfileInfo profileInfo;
  private Block totals;
  private Block extremes;
  private int retainedLogBlocks;

  NativeQueryResult(NativeConnection connection, WireReader in, String queryId) {
    this.connection = connection;
    this.in = in;
    this.queryId = queryId;
    // Pull packets until the first data block (the header) or stream conclusion, so the schema
    // is available before any row consumption.
    Block first = pumpUntilData();
    if (first != null) {
      header = first;
      if (first.rows() > 0) {
        // A server sending rows without a zero row header still yields a usable schema.
        pending = first;
      }
    }
  }

  @Override
  public Optional<Block> header() {
    return Optional.ofNullable(header);
  }

  @Override
  public Optional<Block> nextBlock() {
    ensureOpen();
    if (pending != null) {
      Block block = pending;
      pending = null;
      return Optional.of(block);
    }
    while (true) {
      Block block = pumpUntilData();
      if (block == null) {
        return Optional.empty();
      }
      if (block.rows() > 0) {
        return Optional.of(block);
      }
      // Zero row blocks after the header are stream separators; skip them.
    }
  }

  @Override
  public Progress totalProgress() {
    return new Progress(
        progressReadRows,
        progressReadBytes,
        progressTotalRows,
        progressTotalBytes,
        progressWrittenRows,
        progressWrittenBytes,
        progressElapsedNanos);
  }

  @Override
  public Optional<ProfileInfo> profileInfo() {
    return Optional.ofNullable(profileInfo);
  }

  @Override
  public Optional<Block> totals() {
    return Optional.ofNullable(totals);
  }

  @Override
  public Optional<Block> extremes() {
    return Optional.ofNullable(extremes);
  }

  /**
   * Reads packets until a Data block, throwing on Exception, returning {@code null} on EndOfStream.
   * Every other packet type is consumed into its accessor.
   */
  private Block pumpUntilData() {
    if (finished) {
      return null;
    }
    try {
      while (true) {
        long code = in.readVarUInt();
        ServerPacketType packet = ServerPacketType.fromCode(code);
        switch (packet) {
          case DATA -> {
            in.readString(); // external table name, empty for the main stream
            return BlockReader.read(in, decodeContext());
          }
          case PROGRESS -> accumulate(Progress.read(in, connection.negotiatedRevision()));
          case PROFILE_INFO -> profileInfo = ProfileInfo.read(in, connection.negotiatedRevision());
          case TOTALS -> {
            in.readString();
            totals = BlockReader.read(in, decodeContext());
          }
          case EXTREMES -> {
            in.readString();
            extremes = BlockReader.read(in, decodeContext());
          }
          case LOG -> {
            in.readString();
            Block logBlock = BlockReader.read(in, decodeContext());
            if (retainedLogBlocks < MAX_RETAINED_LOG_BLOCKS) {
              retainedLogBlocks++;
              LOG.debug(
                  "connection {} query {} server log block with {} rows",
                  connection.id(),
                  queryId,
                  logBlock.rows());
            }
          }
          case PROFILE_EVENTS -> {
            in.readString();
            BlockReader.read(in, decodeContext());
          }
          case TIMEZONE_UPDATE -> connection.updateSessionTimezone(in.readString());
          case END_OF_STREAM -> {
            finished = true;
            connection.finishResponse();
            return null;
          }
          case EXCEPTION -> {
            // A server exception is a defined stream terminator; the connection stays in
            // protocol sync and is reusable for the next query.
            finished = true;
            ChordException failure = ServerError.read(in).toException();
            connection.finishResponse();
            throw failure;
          }
          default ->
              throw new ChordProtocolException(
                  "Unexpected server packet "
                      + packet
                      + " while reading a query response; the connection is desynchronised");
        }
      }
    } catch (ChordProtocolException
        | io.github.orhaugh.chord.ChordTransportException
        | io.github.orhaugh.chord.ChordTimeoutException
        | io.github.orhaugh.chord.ChordTypeException e) {
      finished = true;
      connection.markBrokenPublic();
      throw e;
    }
  }

  private DecodeContext decodeContext() {
    return new DecodeContext(
        connection.blockLimits(), connection.negotiatedRevision(), connection.sessionTimezone());
  }

  private void accumulate(Progress delta) {
    progressReadRows += delta.readRows();
    progressReadBytes += delta.readBytes();
    if (delta.totalRowsToRead() > 0) {
      progressTotalRows = delta.totalRowsToRead();
    }
    if (delta.totalBytesToRead() > 0) {
      progressTotalBytes = delta.totalBytesToRead();
    }
    progressWrittenRows += delta.writtenRows();
    progressWrittenBytes += delta.writtenBytes();
    progressElapsedNanos += delta.elapsedNanos();
  }

  private void ensureOpen() {
    if (closed) {
      throw new IllegalStateException("The result is closed");
    }
  }

  boolean isFinished() {
    return finished;
  }

  @Override
  public void close() {
    if (closed) {
      return;
    }
    closed = true;
    pending = null;
    if (finished) {
      return;
    }
    // Drain the rest of the response so the connection is provably in sync before reuse.
    try {
      while (pumpUntilData() != null) {
        // Discard remaining data blocks.
      }
    } catch (ChordException e) {
      // A server exception still concluded the stream cleanly; anything else already broke
      // the connection. Either way the connection state now reflects reality.
      LOG.debug(
          "connection {} query {} drain ended with {}",
          connection.id(),
          queryId,
          e.getClass().getSimpleName());
    }
  }
}
