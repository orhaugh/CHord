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
package io.github.orhaugh.chord.transport;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Objects;

/**
 * Applies the chunked framing of the native protocol (revision 54470) to written bytes.
 *
 * <p>Bytes buffer until {@link #flush()} emits them as one chunk of {@code [little endian u32
 * length][payload]}; a packet is concluded with {@link #endMessage()}, which emits any buffered
 * bytes and the zero length terminator. Multiple chunks per message are valid continuations, so
 * intermediate flushes are safe. Callers mark every protocol packet boundary with {@code
 * endMessage()}, matching the server's expectations.
 *
 * <p>Each chunk reaches the underlying stream as a single {@code write} call carrying the header,
 * the payload and, on {@code endMessage()}, the terminator together. This is an interoperability
 * requirement, not an optimisation: the server completes a chunk header that its buffered read left
 * partial with one further {@code recv}, and treats a short result as the end of the stream,
 * closing the connection without error. Headers written separately from their payload can arrive as
 * lone TCP segments and trip exactly that path.
 */
public final class ChunkedOutputStream extends OutputStream {

  private static final int HEADER_SIZE = 4;

  private final OutputStream out;

  /** Layout: [4 byte header][payload capacity][4 byte terminator], emitted in one write. */
  private final byte[] buffer;

  private final int payloadCapacity;
  private int used;
  private boolean chunkWrittenSinceTerminator;

  /**
   * Creates the stream with a 64 KiB chunk buffer.
   *
   * @param out the raw stream to frame into
   */
  public ChunkedOutputStream(OutputStream out) {
    this(out, 64 * 1024);
  }

  /**
   * Creates the stream.
   *
   * @param out the raw stream to frame into
   * @param bufferSize chunk buffer size in bytes, at least 16
   */
  public ChunkedOutputStream(OutputStream out, int bufferSize) {
    this.out = Objects.requireNonNull(out, "out");
    if (bufferSize < 16) {
      throw new IllegalArgumentException("bufferSize must be at least 16");
    }
    this.payloadCapacity = bufferSize;
    this.buffer = new byte[HEADER_SIZE + bufferSize + HEADER_SIZE];
  }

  @Override
  public void write(int b) throws IOException {
    if (used == payloadCapacity) {
      emitChunk();
    }
    buffer[HEADER_SIZE + used] = (byte) b;
    used++;
  }

  @Override
  public void write(byte[] source, int offset, int length) throws IOException {
    Objects.checkFromIndexSize(offset, length, source.length);
    int written = 0;
    while (written < length) {
      if (used == payloadCapacity) {
        emitChunk();
      }
      int chunk = Math.min(length - written, payloadCapacity - used);
      System.arraycopy(source, offset + written, buffer, HEADER_SIZE + used, chunk);
      used += chunk;
      written += chunk;
    }
  }

  @Override
  public void flush() throws IOException {
    emitChunk();
    out.flush();
  }

  /**
   * Concludes the current protocol packet: emits buffered bytes as a chunk followed by the zero
   * length terminator in the same write, and flushes. A no op when nothing was written since the
   * last terminator, because an empty message is a protocol violation on the receiving side.
   *
   * @throws IOException when the underlying stream fails
   */
  public void endMessage() throws IOException {
    if (used > 0) {
      writeHeaderInto(0, used);
      writeHeaderInto(HEADER_SIZE + used, 0);
      out.write(buffer, 0, HEADER_SIZE + used + HEADER_SIZE);
      used = 0;
      chunkWrittenSinceTerminator = false;
      out.flush();
      return;
    }
    if (!chunkWrittenSinceTerminator) {
      return;
    }
    writeHeaderInto(0, 0);
    out.write(buffer, 0, HEADER_SIZE);
    chunkWrittenSinceTerminator = false;
    out.flush();
  }

  private void emitChunk() throws IOException {
    if (used == 0) {
      return;
    }
    writeHeaderInto(0, used);
    out.write(buffer, 0, HEADER_SIZE + used);
    used = 0;
    chunkWrittenSinceTerminator = true;
  }

  private void writeHeaderInto(int at, int value) {
    buffer[at] = (byte) (value & 0xFF);
    buffer[at + 1] = (byte) ((value >>> 8) & 0xFF);
    buffer[at + 2] = (byte) ((value >>> 16) & 0xFF);
    buffer[at + 3] = (byte) ((value >>> 24) & 0xFF);
  }
}
