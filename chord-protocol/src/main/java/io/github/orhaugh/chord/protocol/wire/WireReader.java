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
package io.github.orhaugh.chord.protocol.wire;

import io.github.orhaugh.chord.ChordProtocolException;
import io.github.orhaugh.chord.ChordTimeoutException;
import io.github.orhaugh.chord.ChordTransportException;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Buffered reader for the primitive encodings of the ClickHouse native protocol: LEB128 variable
 * length integers, little endian fixed width integers, and length prefixed strings.
 *
 * <p>Encodings follow {@code src/IO/VarInt.h} and {@code src/IO/ReadHelpers.h} in the ClickHouse
 * sources. A VarUInt spans at most ten bytes and carries a full unsigned 64 bit value; this reader
 * is stricter than the server in one deliberate way: a tenth byte that either sets the continuation
 * bit or carries bits beyond bit 63 is rejected, because no compliant writer can produce it and
 * accepting it would silently lose data or desynchronise framing.
 *
 * <p>Failure mapping: an elapsed socket timeout raises {@link ChordTimeoutException}, any other I/O
 * failure raises {@link ChordTransportException}, and end of stream in the middle of a value, an
 * oversized length or a malformed encoding raises {@link ChordProtocolException}. After any of
 * these the stream position is unspecified and the connection must not be reused.
 *
 * <p>This class is not thread safe.
 */
public final class WireReader {

  private static final int MAX_VAR_UINT_BYTES = 10;

  private final InputStream in;
  private final WireLimits limits;
  private final byte[] buffer;
  private int position;
  private int limit;
  private long consumed;

  /**
   * Creates a reader with an 8 KiB internal buffer.
   *
   * @param in stream to read from, typically a socket input stream
   * @param limits bounds applied to lengths read from the wire
   */
  public WireReader(InputStream in, WireLimits limits) {
    this(in, limits, 8192);
  }

  /**
   * Creates a reader.
   *
   * @param in stream to read from, typically a socket input stream
   * @param limits bounds applied to lengths read from the wire
   * @param bufferSize internal buffer size in bytes, at least 64
   */
  public WireReader(InputStream in, WireLimits limits, int bufferSize) {
    this.in = Objects.requireNonNull(in, "in");
    this.limits = Objects.requireNonNull(limits, "limits");
    if (bufferSize < 64) {
      throw new IllegalArgumentException("bufferSize must be at least 64, was " + bufferSize);
    }
    this.buffer = new byte[bufferSize];
  }

  /**
   * Returns the limits this reader enforces.
   *
   * @return the wire limits
   */
  public WireLimits limits() {
    return limits;
  }

  /**
   * Returns the number of bytes consumed so far, for diagnostics.
   *
   * @return total consumed bytes
   */
  public long bytesConsumed() {
    return consumed;
  }

  /**
   * Reads a ClickHouse VarUInt.
   *
   * <p>The full unsigned 64 bit range is supported; values above {@code Long.MAX_VALUE} are
   * returned as negative {@code long} values with unsigned semantics, matching how the rest of
   * CHord carries UInt64.
   *
   * @return the decoded value, as an unsigned 64 bit quantity in a {@code long}
   */
  public long readVarUInt() {
    long result = 0;
    for (int i = 0; i < MAX_VAR_UINT_BYTES; i++) {
      int b = readByte();
      if (i == MAX_VAR_UINT_BYTES - 1 && b > 0x01) {
        throw new ChordProtocolException(
            "Malformed VarUInt: tenth byte 0x"
                + Integer.toHexString(b)
                + " cannot be produced by a compliant writer (stream offset "
                + consumed
                + ")");
      }
      result |= (long) (b & 0x7F) << (7 * i);
      if ((b & 0x80) == 0) {
        return result;
      }
    }
    // Unreachable: the tenth byte is constrained to 0x00 or 0x01, which never sets the
    // continuation bit. Kept as a defensive fail-safe.
    throw new ChordProtocolException("Malformed VarUInt: more than ten bytes");
  }

  /**
   * Reads a ClickHouse VarInt: a zigzag encoded signed value carried in a VarUInt.
   *
   * @return the decoded signed value
   */
  public long readVarInt() {
    long encoded = readVarUInt();
    return (encoded >>> 1) ^ -(encoded & 1);
  }

  /**
   * Reads one unsigned byte.
   *
   * @return the byte value in the range 0 to 255
   */
  public int readUInt8() {
    return readByte();
  }

  /**
   * Reads one byte as a boolean, matching ClickHouse {@code readBinary(bool)}: any non zero value
   * is {@code true}.
   *
   * @return the decoded boolean
   */
  public boolean readBool() {
    return readByte() != 0;
  }

  /**
   * Reads a little endian signed 32 bit integer.
   *
   * @return the decoded value
   */
  public int readInt32Le() {
    int b0 = readByte();
    int b1 = readByte();
    int b2 = readByte();
    int b3 = readByte();
    return b0 | (b1 << 8) | (b2 << 16) | (b3 << 24);
  }

  /**
   * Reads a little endian 64 bit integer. For UInt64 fields the returned {@code long} carries
   * unsigned semantics.
   *
   * @return the decoded value
   */
  public long readInt64Le() {
    long low = readInt32Le() & 0xFFFF_FFFFL;
    long high = readInt32Le() & 0xFFFF_FFFFL;
    return low | (high << 32);
  }

  /**
   * Reads a length prefixed string bounded by {@link WireLimits#maxStringBytes()}.
   *
   * @return the decoded string, UTF-8 interpreted
   */
  public String readString() {
    return readString(limits.maxStringBytes());
  }

  /**
   * Reads a length prefixed string bounded by an explicit byte limit.
   *
   * @param maxBytes largest permitted encoded length in bytes
   * @return the decoded string, UTF-8 interpreted
   */
  public String readString(int maxBytes) {
    byte[] bytes = readLengthPrefixedBytes(maxBytes, "string");
    return bytes.length == 0 ? "" : new String(bytes, StandardCharsets.UTF_8);
  }

  /**
   * Reads a length prefixed byte string bounded by an explicit byte limit, without character
   * decoding. ClickHouse strings are byte strings; use this accessor when the payload is not text.
   *
   * @param maxBytes largest permitted encoded length in bytes
   * @return the raw bytes
   */
  public byte[] readStringBytes(int maxBytes) {
    return readLengthPrefixedBytes(maxBytes, "string");
  }

  private byte[] readLengthPrefixedBytes(int maxBytes, String what) {
    if (maxBytes <= 0) {
      throw new IllegalArgumentException("maxBytes must be positive, was " + maxBytes);
    }
    long declared = readVarUInt();
    if (Long.compareUnsigned(declared, maxBytes) > 0) {
      throw new ChordProtocolException(
          "Declared "
              + what
              + " length "
              + Long.toUnsignedString(declared)
              + " exceeds the permitted maximum of "
              + maxBytes
              + " bytes (stream offset "
              + consumed
              + ")");
    }
    int length = (int) declared;
    if (length == 0) {
      return new byte[0];
    }
    byte[] out = new byte[length];
    readFully(out);
    return out;
  }

  /**
   * Exposes this reader as an {@link InputStream} so layered decoders (for example compressed frame
   * readers) consume bytes through this reader's buffer instead of competing with it for the
   * underlying stream. Reads of {@code n} bytes block until exactly {@code n} bytes arrive,
   * mirroring {@link #readFully(byte[])}.
   *
   * @return a stream view over this reader
   */
  public InputStream asInputStream() {
    return new InputStream() {
      @Override
      public int read() {
        return readUInt8();
      }

      @Override
      public int read(byte[] target, int offset, int length) {
        Objects.checkFromIndexSize(offset, length, target.length);
        if (length == 0) {
          return 0;
        }
        byte[] exact = new byte[length];
        readFully(exact);
        System.arraycopy(exact, 0, target, offset, length);
        return length;
      }
    };
  }

  /**
   * Reads exactly {@code out.length} bytes into the supplied array.
   *
   * @param out destination array, filled completely
   */
  public void readFully(byte[] out) {
    int copied = Math.min(out.length, limit - position);
    System.arraycopy(buffer, position, out, 0, copied);
    position += copied;
    consumed += copied;
    int offset = copied;
    while (offset < out.length) {
      int n = readIntoArray(out, offset, out.length - offset);
      offset += n;
      consumed += n;
    }
  }

  private int readByte() {
    if (position == limit) {
      fill();
    }
    consumed++;
    return buffer[position++] & 0xFF;
  }

  private void fill() {
    int n = readIntoArray(buffer, 0, buffer.length);
    position = 0;
    limit = n;
  }

  private int readIntoArray(byte[] target, int offset, int length) {
    try {
      int n = in.read(target, offset, length);
      if (n < 0) {
        throw truncated();
      }
      return n;
    } catch (SocketTimeoutException e) {
      throw new ChordTimeoutException(
          "Read timed out after consuming " + consumed + " bytes; the connection is unusable", e);
    } catch (IOException e) {
      throw new ChordTransportException(
          "I/O failure while reading from the server after " + consumed + " bytes", e);
    }
  }

  private ChordProtocolException truncated() {
    return new ChordProtocolException(
        "Stream ended in the middle of a value after " + consumed + " bytes");
  }
}
