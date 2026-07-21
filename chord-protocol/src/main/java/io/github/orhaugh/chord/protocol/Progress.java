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

import io.github.orhaugh.chord.protocol.wire.WireReader;

/**
 * The decoded body of a Progress packet: incremental execution counters the client accumulates.
 *
 * <p>Wire format, per {@code ProgressValues::read} in {@code src/IO/Progress.cpp}: VarUInt read
 * rows, read bytes and total rows to read, then revision gated VarUInt fields for total bytes to
 * read ({@link ProtocolFeature#TOTAL_BYTES_IN_PROGRESS}), written rows and bytes ({@link
 * ProtocolFeature#CLIENT_WRITE_INFO}) and elapsed nanoseconds ({@link
 * ProtocolFeature#SERVER_QUERY_TIME_IN_PROGRESS}). Fields the negotiated revision does not carry
 * are zero.
 *
 * @param readRows rows read since the previous Progress packet
 * @param readBytes bytes read since the previous Progress packet
 * @param totalRowsToRead estimated total rows to read, zero when unknown
 * @param totalBytesToRead estimated total bytes to read, zero when unknown or not carried
 * @param writtenRows rows written since the previous Progress packet, zero when not carried
 * @param writtenBytes bytes written since the previous Progress packet, zero when not carried
 * @param elapsedNanos server side elapsed time in nanoseconds, zero when not carried
 */
public record Progress(
    long readRows,
    long readBytes,
    long totalRowsToRead,
    long totalBytesToRead,
    long writtenRows,
    long writtenBytes,
    long elapsedNanos) {

  /**
   * Reads a Progress packet body. The leading packet type identifier must already have been
   * consumed.
   *
   * @param in reader positioned at the progress body
   * @param negotiatedRevision the negotiated protocol revision of the connection
   * @return the decoded progress counters
   */
  public static Progress read(WireReader in, long negotiatedRevision) {
    long readRows = in.readVarUInt();
    long readBytes = in.readVarUInt();
    long totalRowsToRead = in.readVarUInt();
    long totalBytesToRead = 0;
    if (ProtocolFeature.TOTAL_BYTES_IN_PROGRESS.enabledFor(negotiatedRevision)) {
      totalBytesToRead = in.readVarUInt();
    }
    long writtenRows = 0;
    long writtenBytes = 0;
    if (ProtocolFeature.CLIENT_WRITE_INFO.enabledFor(negotiatedRevision)) {
      writtenRows = in.readVarUInt();
      writtenBytes = in.readVarUInt();
    }
    long elapsedNanos = 0;
    if (ProtocolFeature.SERVER_QUERY_TIME_IN_PROGRESS.enabledFor(negotiatedRevision)) {
      elapsedNanos = in.readVarUInt();
    }
    return new Progress(
        readRows,
        readBytes,
        totalRowsToRead,
        totalBytesToRead,
        writtenRows,
        writtenBytes,
        elapsedNanos);
  }
}
