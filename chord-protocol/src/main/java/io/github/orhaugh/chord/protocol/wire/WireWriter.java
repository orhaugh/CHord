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

import io.github.orhaugh.chord.ChordTimeoutException;
import io.github.orhaugh.chord.ChordTransportException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Buffered writer for the primitive encodings of the ClickHouse native protocol: LEB128 variable
 * length integers, little endian fixed width integers, and length prefixed strings.
 *
 * <p>Encodings follow {@code src/IO/VarInt.h} and {@code src/IO/WriteHelpers.h} in the ClickHouse
 * sources. Nothing is sent until {@link #flush()} is called or the internal buffer fills.
 *
 * <p>This class is not thread safe.
 */
public final class WireWriter {

  private final OutputStream out;
  private final byte[] buffer;
  private int position;

  /**
   * Creates a writer with an 8 KiB internal buffer.
   *
   * @param out stream to write to, typically a socket output stream
   */
  public WireWriter(OutputStream out) {
    this(out, 8192);
  }

  /**
   * Creates a writer.
   *
   * @param out stream to write to, typically a socket output stream
   * @param bufferSize internal buffer size in bytes, at least 64
   */
  public WireWriter(OutputStream out, int bufferSize) {
    this.out = Objects.requireNonNull(out, "out");
    if (bufferSize < 64) {
      throw new IllegalArgumentException("bufferSize must be at least 64, was " + bufferSize);
    }
    this.buffer = new byte[bufferSize];
  }

  /**
   * Writes a ClickHouse VarUInt. The argument is interpreted as an unsigned 64 bit value, so
   * negative {@code long} inputs encode the upper half of the unsigned range and occupy ten bytes.
   *
   * @param value value to encode, unsigned semantics
   */
  public void writeVarUInt(long value) {
    long v = value;
    while ((v & ~0x7FL) != 0) {
      writeByte((int) ((v & 0x7F) | 0x80));
      v >>>= 7;
    }
    writeByte((int) v);
  }

  /**
   * Writes a ClickHouse VarInt: the zigzag encoding of a signed value carried in a VarUInt.
   *
   * @param value signed value to encode
   */
  public void writeVarInt(long value) {
    writeVarUInt((value << 1) ^ (value >> 63));
  }

  /**
   * Writes one unsigned byte.
   *
   * @param value byte value; only the low 8 bits are used
   */
  public void writeUInt8(int value) {
    writeByte(value);
  }

  /**
   * Writes a boolean as a single byte, {@code 1} for true and {@code 0} for false.
   *
   * @param value value to write
   */
  public void writeBool(boolean value) {
    writeByte(value ? 1 : 0);
  }

  /**
   * Writes a little endian 32 bit integer.
   *
   * @param value value to write
   */
  public void writeInt32Le(int value) {
    writeByte(value);
    writeByte(value >>> 8);
    writeByte(value >>> 16);
    writeByte(value >>> 24);
  }

  /**
   * Writes a little endian 64 bit integer. For UInt64 fields pass the unsigned value in a {@code
   * long}.
   *
   * @param value value to write
   */
  public void writeInt64Le(long value) {
    writeInt32Le((int) value);
    writeInt32Le((int) (value >>> 32));
  }

  /**
   * Writes a length prefixed UTF-8 string.
   *
   * @param value string to write
   */
  public void writeString(String value) {
    writeString(value.getBytes(StandardCharsets.UTF_8));
  }

  /**
   * Writes a length prefixed byte string.
   *
   * @param value bytes to write
   */
  public void writeString(byte[] value) {
    writeVarUInt(value.length);
    writeBytes(value, 0, value.length);
  }

  /**
   * Writes raw bytes without a length prefix.
   *
   * @param value source array
   * @param offset first byte to write
   * @param length number of bytes to write
   */
  public void writeBytes(byte[] value, int offset, int length) {
    Objects.checkFromIndexSize(offset, length, value.length);
    int written = 0;
    while (written < length) {
      if (position == buffer.length) {
        drain();
      }
      int chunk = Math.min(length - written, buffer.length - position);
      System.arraycopy(value, offset + written, buffer, position, chunk);
      position += chunk;
      written += chunk;
    }
  }

  /** Flushes the internal buffer and the underlying stream. */
  public void flush() {
    drain();
    try {
      out.flush();
    } catch (SocketTimeoutException e) {
      throw new ChordTimeoutException("Write timed out while flushing", e);
    } catch (IOException e) {
      throw new ChordTransportException("I/O failure while flushing to the server", e);
    }
  }

  private void writeByte(int value) {
    if (position == buffer.length) {
      drain();
    }
    buffer[position++] = (byte) value;
  }

  private void drain() {
    if (position == 0) {
      return;
    }
    try {
      out.write(buffer, 0, position);
      position = 0;
    } catch (SocketTimeoutException e) {
      throw new ChordTimeoutException("Write timed out", e);
    } catch (IOException e) {
      throw new ChordTransportException("I/O failure while writing to the server", e);
    }
  }
}
