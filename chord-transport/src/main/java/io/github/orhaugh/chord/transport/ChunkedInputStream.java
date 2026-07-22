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

import io.github.orhaugh.chord.ChordProtocolException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/**
 * Strips the chunked framing of the native protocol (revision 54470) from a byte stream.
 *
 * <p>On the wire, each protocol packet travels as one or more chunks of {@code [little endian u32
 * length][payload]} followed by a zero length terminator. This stream delivers the concatenated
 * payload bytes; chunk headers and terminators are consumed transparently. A zero length chunk at
 * the start of a message is a protocol violation, mirroring the server's own check.
 */
public final class ChunkedInputStream extends InputStream {

  private final InputStream in;
  private long chunkLeft;
  private boolean atMessageStart = true;

  /**
   * Creates the stream.
   *
   * @param in the raw stream carrying chunked framing
   */
  public ChunkedInputStream(InputStream in) {
    this.in = Objects.requireNonNull(in, "in");
  }

  @Override
  public int read() throws IOException {
    byte[] one = new byte[1];
    int n = read(one, 0, 1);
    return n < 0 ? -1 : one[0] & 0xFF;
  }

  @Override
  public int read(byte[] target, int offset, int length) throws IOException {
    Objects.checkFromIndexSize(offset, length, target.length);
    if (length == 0) {
      return 0;
    }
    while (chunkLeft == 0) {
      long header = readChunkHeader();
      if (header < 0) {
        return -1;
      }
      if (header == 0) {
        if (atMessageStart) {
          throw new ChordProtocolException(
              "Chunked framing violation: empty chunk at message start");
        }
        // Message terminator; the next chunk begins a new message.
        atMessageStart = true;
        continue;
      }
      chunkLeft = header;
      atMessageStart = false;
    }
    int toRead = (int) Math.min(length, chunkLeft);
    int n = in.read(target, offset, toRead);
    if (n < 0) {
      throw new ChordProtocolException("Stream ended in the middle of a chunk");
    }
    chunkLeft -= n;
    return n;
  }

  /** Returns the u32 header, or -1 on a clean end of stream at a message boundary. */
  private long readChunkHeader() throws IOException {
    long value = 0;
    for (int i = 0; i < 4; i++) {
      int b = in.read();
      if (b < 0) {
        if (i == 0 && atMessageStart) {
          return -1;
        }
        throw new ChordProtocolException("Stream ended in the middle of a chunk header");
      }
      value |= (long) b << (8 * i);
    }
    return value;
  }
}
