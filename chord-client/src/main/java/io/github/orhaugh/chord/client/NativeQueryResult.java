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
  private final java.util.function.Consumer<Progress> progressListener;
  private final java.util.function.Consumer<ServerLogEntry> logListener;

  /** Absolute expiry of the request's client side timeout, zero when none is set. */
  private final long deadlineNanos;

  /** Absolute expiry of the post cancel grace period, set when the deadline fires. */
  private long graceDeadlineNanos;

  /** Set when the request timeout fired; the stream is then drained and reported as timed out. */
  private boolean deadlineExpired;

  private Block header;
  private Block pending;
  private boolean finished;
  private boolean closed;

  private final java.util.Map<String, Long> profileEvents = new java.util.TreeMap<>();
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

  private final JfrEvents.QueryEvent jfrEvent = new JfrEvents.QueryEvent();
  private boolean jfrCommitted;

  NativeQueryResult(NativeConnection connection, WireReader in, QueryRequest request) {
    this.connection = connection;
    this.in = in;
    this.queryId = request.queryId();
    this.progressListener = request.progressListener().orElse(null);
    this.logListener = request.logListener().orElse(null);
    java.time.Duration timeout = request.timeout().orElse(null);
    this.deadlineNanos = timeout == null ? 0 : System.nanoTime() + timeout.toNanos();
    jfrEvent.begin();
    jfrEvent.queryId = queryId;
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
  public java.util.Map<String, Long> profileEvents() {
    return java.util.Map.copyOf(profileEvents);
  }

  /**
   * Folds a ProfileEvents block into the per name counters: increments (type 1) accumulate and
   * gauges (type 2) keep their latest value, matching the incremental packets of revision 54451.
   */
  private void accumulateProfileEvents(Block block) {
    var nameColumn = block.columnByName("name");
    var valueColumn = block.columnByName("value");
    var typeColumn = block.columnByName("type");
    if (nameColumn.isEmpty() || valueColumn.isEmpty() || typeColumn.isEmpty()) {
      return; // Unknown shape; ignore rather than guess.
    }
    for (int row = 0; row < block.rows(); row++) {
      String name = String.valueOf(nameColumn.get().objectAt(row));
      Object rawValue = valueColumn.get().objectAt(row);
      long value = rawValue instanceof Number number ? number.longValue() : 0;
      // The type column is Enum8('increment' = 1, 'gauge' = 2); older shapes carried Int8.
      Object rawType = typeColumn.get().objectAt(row);
      int type =
          switch (rawType) {
            case Number number -> number.intValue();
            case String label -> label.equals("increment") ? 1 : label.equals("gauge") ? 2 : 0;
            default -> 0;
          };
      if (type == 1) {
        profileEvents.merge(name, value, Long::sum);
      } else if (type == 2) {
        profileEvents.put(name, value);
      }
    }
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
        awaitNextPacketWithinDeadline();
        long code = in.readVarUInt();
        ServerPacketType packet = ServerPacketType.fromCode(code);
        switch (packet) {
          case DATA -> {
            in.readString(); // external table name, empty for the main stream
            Block block = BlockReader.read(connection.dataBodyReader(), decodeContext());
            if (!deadlineExpired) {
              return block;
            }
            // Past the deadline the stream is only drained; late data is discarded.
          }
          case PROGRESS -> {
            Progress delta = Progress.read(in, connection.negotiatedRevision());
            accumulate(delta);
            notifyProgress(delta);
          }
          case PROFILE_INFO -> profileInfo = ProfileInfo.read(in, connection.negotiatedRevision());
          case TOTALS -> {
            in.readString();
            totals = BlockReader.read(connection.dataBodyReader(), decodeContext());
          }
          case EXTREMES -> {
            in.readString();
            extremes = BlockReader.read(connection.dataBodyReader(), decodeContext());
          }
          case LOG -> {
            in.readString();
            Block logBlock = BlockReader.read(connection.auxiliaryBodyReader(), decodeContext());
            if (retainedLogBlocks < MAX_RETAINED_LOG_BLOCKS) {
              retainedLogBlocks++;
              LOG.debug(
                  "connection {} query {} server log block with {} rows",
                  connection.id(),
                  queryId,
                  logBlock.rows());
            }
            notifyLogs(logBlock);
          }
          case PROFILE_EVENTS -> {
            in.readString();
            accumulateProfileEvents(
                BlockReader.read(connection.auxiliaryBodyReader(), decodeContext()));
          }
          case TIMEZONE_UPDATE -> connection.updateSessionTimezone(in.readString());
          case END_OF_STREAM -> {
            finished = true;
            connection.finishResponse();
            if (deadlineExpired) {
              commitJfr("cancelled_timeout");
              // The cancel was answered within the grace period, so the connection is in
              // sync and reusable, but the caller must see the timeout: a truncated stream
              // must never look like a legitimately short result.
              throw new io.github.orhaugh.chord.ChordTimeoutException(
                  "Query "
                      + queryId
                      + " exceeded its timeout; it was cancelled and the stream drained, and"
                      + " the connection remains usable");
            }
            commitJfr("finished");
            return null;
          }
          case EXCEPTION -> {
            // A server exception is a defined stream terminator; the connection stays in
            // protocol sync and is reusable for the next query.
            finished = true;
            ChordException failure = ServerError.read(in).toException();
            connection.finishResponse();
            commitJfr("server_error");
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
      // A timeout whose cancel drained cleanly has already returned the connection to READY
      // and left it in protocol sync; every other failure here poisons the connection.
      if (connection.state() != io.github.orhaugh.chord.protocol.state.ConnectionState.READY) {
        connection.markBrokenPublic();
      }
      // The query was executing when the stream failed or timed out.
      NativeConnection.classifyIfTransportLevel(
          e, io.github.orhaugh.chord.RetryClass.RETRY_ONLY_IF_IDEMPOTENT);
      commitJfr("failed");
      throw e;
    }
  }

  /** Concludes the JFR event exactly once, with final progress counters. */
  private void commitJfr(String outcome) {
    if (jfrCommitted) {
      return;
    }
    jfrCommitted = true;
    jfrEvent.rowsRead = progressReadRows;
    jfrEvent.bytesRead = progressReadBytes;
    jfrEvent.outcome = outcome;
    jfrEvent.commit();
  }

  /**
   * Enforces the request timeout at packet boundaries, where waiting consumes nothing: while the
   * deadline holds, wait for the next packet; when it expires, send Cancel once and allow the
   * connection's grace period for the server to conclude the stream. A grace expiry abandons the
   * connection, because a stream that never concludes cannot be drained for reuse.
   */
  private void awaitNextPacketWithinDeadline() {
    if (deadlineNanos == 0) {
      return;
    }
    long remainingMillis = (deadlineNanos - System.nanoTime()) / 1_000_000;
    if (remainingMillis > 0 && connection.awaitResponseReadable(remainingMillis)) {
      return;
    }
    deadlineExpired = true;
    if (graceDeadlineNanos == 0) {
      // Whether this deadline sends the cancel or a manual cancel() already did, the grace
      // clock for concluding the stream starts at the first expiry.
      graceDeadlineNanos = System.nanoTime() + connection.cancelGraceMillis() * 1_000_000;
      if (connection.requestCancel()) {
        LOG.debug("query {} exceeded its timeout; cancel sent", queryId);
      }
    }
    long graceRemainingMillis = (graceDeadlineNanos - System.nanoTime()) / 1_000_000;
    if (graceRemainingMillis > 0 && connection.awaitResponseReadable(graceRemainingMillis)) {
      return;
    }
    finished = true;
    connection.markBrokenPublic();
    connection.close();
    throw new io.github.orhaugh.chord.ChordTimeoutException(
        "Query "
            + queryId
            + " exceeded its timeout and the server did not conclude the stream within the"
            + " cancel grace period; the connection is closed");
  }

  @Override
  public void cancel() {
    // The connection owns the once-per-exchange gate and the send serialisation, keeping this
    // result single threaded; finished or closed streams are covered by the connection no
    // longer being in the reading state.
    connection.requestCancel();
  }

  private DecodeContext decodeContext() {
    return new DecodeContext(
        connection.blockLimits(), connection.negotiatedRevision(), connection.sessionTimezone());
  }

  /** Listener failures are contained: a callback bug must never desynchronise the stream. */
  private void notifyProgress(Progress delta) {
    if (progressListener == null) {
      return;
    }
    try {
      progressListener.accept(delta);
    } catch (RuntimeException e) {
      LOG.warn("query {} progress listener failed", queryId, e);
    }
  }

  private void notifyLogs(Block logBlock) {
    if (logListener == null) {
      return;
    }
    try {
      for (ServerLogEntry entry : ServerLogEntry.fromBlock(logBlock)) {
        logListener.accept(entry);
      }
    } catch (RuntimeException e) {
      LOG.warn("query {} log listener failed", queryId, e);
    }
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
      commitJfr("finished");
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
