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
import io.github.orhaugh.chord.ChordTypeException;
import io.github.orhaugh.chord.codec.block.Block;
import io.github.orhaugh.chord.codec.block.BlockReader;
import io.github.orhaugh.chord.codec.block.BlockWriter;
import io.github.orhaugh.chord.codec.block.DecodeContext;
import io.github.orhaugh.chord.protocol.ClientPacketType;
import io.github.orhaugh.chord.protocol.Progress;
import io.github.orhaugh.chord.protocol.ServerError;
import io.github.orhaugh.chord.protocol.ServerPacketType;
import io.github.orhaugh.chord.protocol.wire.WireReader;
import io.github.orhaugh.chord.protocol.wire.WireWriter;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The packet pump behind {@link InsertStream}, owned by a {@link NativeConnection}. */
final class NativeInsertStream implements InsertStream {

  private static final Logger LOG = LoggerFactory.getLogger(NativeInsertStream.class);

  private final NativeConnection connection;
  private final WireReader in;
  private final WireWriter out;
  private final String queryId;
  private final java.util.function.Consumer<Progress> progressListener;
  private final java.util.function.Consumer<ServerLogEntry> logListener;

  private Block schema;
  private String tableColumnsDescription;
  private long rowsSent;
  private long blocksSent;
  private boolean finished;
  private boolean closed;

  private long progressReadRows;
  private long progressReadBytes;
  private long progressWrittenRows;
  private long progressWrittenBytes;
  private long progressElapsedNanos;

  private final JfrEvents.InsertEvent jfrEvent = new JfrEvents.InsertEvent();
  private boolean jfrCommitted;

  NativeInsertStream(
      NativeConnection connection, WireReader in, WireWriter out, QueryRequest request) {
    this.connection = connection;
    this.in = in;
    this.out = out;
    this.queryId = request.queryId();
    this.progressListener = request.progressListener().orElse(null);
    this.logListener = request.logListener().orElse(null);
    jfrEvent.begin();
    jfrEvent.queryId = queryId;
    readSchema();
  }

  /** Concludes the JFR event exactly once, with final send counters. */
  private void commitJfr(String outcome) {
    if (jfrCommitted) {
      return;
    }
    jfrCommitted = true;
    jfrEvent.rowsSent = rowsSent;
    jfrEvent.blocksSent = blocksSent;
    jfrEvent.outcome = outcome;
    jfrEvent.commit();
  }

  /**
   * Reads packets until the schema header block: TableColumns, Log and TimezoneUpdate may precede
   * it, and an Exception means the server rejected the INSERT before data.
   */
  private void readSchema() {
    try {
      while (true) {
        long code = in.readVarUInt();
        ServerPacketType packet = ServerPacketType.fromCode(code);
        switch (packet) {
          case TABLE_COLUMNS -> {
            // Both strings travel on the compressed stream from revision 54481.
            WireReader body = connection.auxiliaryBodyReader();
            body.readString(); // external table name, empty for the main table
            tableColumnsDescription = body.readString();
          }
          case DATA -> {
            in.readString();
            schema = BlockReader.read(connection.dataBodyReader(), decodeContext());
            connection.beginInsertStreaming();
            return;
          }
          case LOG -> {
            in.readString();
            notifyLogs(BlockReader.read(connection.auxiliaryBodyReader(), decodeContext()));
          }
          case PROGRESS -> {
            Progress delta = Progress.read(in, connection.negotiatedRevision());
            accumulate(delta);
            notifyProgress(delta);
          }
          case TIMEZONE_UPDATE -> connection.updateSessionTimezone(in.readString());
          case EXCEPTION -> {
            finished = true;
            ChordException failure = ServerError.read(in).toException();
            connection.finishResponse();
            throw failure;
          }
          default ->
              throw new ChordProtocolException(
                  "Unexpected server packet "
                      + packet
                      + " while waiting for the INSERT schema; the connection is"
                      + " desynchronised");
        }
      }
    } catch (ChordProtocolException
        | io.github.orhaugh.chord.ChordTransportException
        | io.github.orhaugh.chord.ChordTimeoutException
        | ChordTypeException e) {
      finished = true;
      connection.markBrokenPublic();
      throw e;
    }
  }

  @Override
  public Block schema() {
    return schema;
  }

  @Override
  public Optional<String> tableColumnsDescription() {
    return Optional.ofNullable(tableColumnsDescription);
  }

  @Override
  public void send(Block block) {
    ensureStreaming("send");
    validateAgainstSchema(block);
    try {
      out.writeVarUInt(ClientPacketType.DATA.code());
      out.writeString(""); // main table stream
      out.flush();
      WireWriter body = connection.blockBodyWriter();
      BlockWriter.write(body, block, connection.negotiatedRevision());
      body.flush();
      connection.endPacket();
      rowsSent += block.rows();
      blocksSent++;
    } catch (RuntimeException e) {
      finished = true;
      connection.markBrokenPublic();
      throw e;
    }
  }

  private void validateAgainstSchema(Block block) {
    if (block.columnCount() != schema.columnCount()) {
      throw new ChordTypeException(
          "Block has "
              + block.columnCount()
              + " columns but the target table has "
              + schema.columnCount());
    }
    for (int i = 0; i < block.columnCount(); i++) {
      if (!block.columnType(i).equals(schema.columnType(i))) {
        throw new ChordTypeException(
            "Column \""
                + schema.columnName(i)
                + "\" expects "
                + schema.columnType(i).name()
                + " but the block carries "
                + block.columnType(i).name());
      }
    }
  }

  @Override
  public InsertSummary finish() {
    ensureStreaming("finish");
    try {
      connection.sendEmptyDataPacket();
      out.flush();
      connection.finishInsertStreaming();
      drainToEndOfStream();
      finished = true;
      LOG.debug(
          "connection {} insert {} committed {} rows in {} blocks",
          connection.id(),
          queryId,
          rowsSent,
          blocksSent);
      commitJfr("committed");
      return new InsertSummary(rowsSent, blocksSent, currentProgress());
    } catch (io.github.orhaugh.chord.ChordServerException e) {
      finished = true;
      commitJfr("server_error");
      throw e;
    } catch (ChordException e) {
      finished = true;
      commitJfr("failed");
      throw e;
    } catch (RuntimeException e) {
      finished = true;
      connection.markBrokenPublic();
      commitJfr("failed");
      throw e;
    }
  }

  private void drainToEndOfStream() {
    try {
      while (true) {
        long code = in.readVarUInt();
        ServerPacketType packet = ServerPacketType.fromCode(code);
        switch (packet) {
          case PROGRESS -> {
            Progress delta = Progress.read(in, connection.negotiatedRevision());
            accumulate(delta);
            notifyProgress(delta);
          }
          case LOG -> {
            in.readString();
            notifyLogs(BlockReader.read(connection.auxiliaryBodyReader(), decodeContext()));
          }
          case PROFILE_EVENTS -> {
            in.readString();
            BlockReader.read(connection.auxiliaryBodyReader(), decodeContext());
          }
          case TIMEZONE_UPDATE -> connection.updateSessionTimezone(in.readString());
          case END_OF_STREAM -> {
            connection.finishResponse();
            return;
          }
          case EXCEPTION -> {
            ChordException failure = ServerError.read(in).toException();
            connection.finishResponse();
            throw failure;
          }
          default ->
              throw new ChordProtocolException(
                  "Unexpected server packet "
                      + packet
                      + " while concluding an INSERT; the connection is desynchronised");
        }
      }
    } catch (ChordProtocolException
        | io.github.orhaugh.chord.ChordTransportException
        | io.github.orhaugh.chord.ChordTimeoutException
        | ChordTypeException e) {
      connection.markBrokenPublic();
      throw e;
    }
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
      LOG.warn("insert {} progress listener failed", queryId, e);
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
      LOG.warn("insert {} log listener failed", queryId, e);
    }
  }

  private void accumulate(Progress delta) {
    progressReadRows += delta.readRows();
    progressReadBytes += delta.readBytes();
    progressWrittenRows += delta.writtenRows();
    progressWrittenBytes += delta.writtenBytes();
    progressElapsedNanos += delta.elapsedNanos();
  }

  private Progress currentProgress() {
    return new Progress(
        progressReadRows,
        progressReadBytes,
        0,
        0,
        progressWrittenRows,
        progressWrittenBytes,
        progressElapsedNanos);
  }

  private void ensureStreaming(String operation) {
    if (closed) {
      throw new IllegalStateException("The insert stream is closed");
    }
    if (finished) {
      throw new IllegalStateException(
          "The insert has already concluded; " + operation + " is not possible");
    }
  }

  @Override
  public void close() {
    if (closed) {
      return;
    }
    closed = true;
    if (finished) {
      return;
    }
    // No finish: never commit partial data implicitly. Closing the connection tells the server
    // the stream is abandoned; per ADR-0007 the outcome is treated as a hard abort.
    LOG.warn(
        "connection {} insert {} closed without finish after {} rows; aborting the connection so"
            + " nothing is committed implicitly",
        connection.id(),
        queryId,
        rowsSent);
    connection.close();
    commitJfr("aborted");
  }
}
