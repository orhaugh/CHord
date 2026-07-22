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
package io.github.orhaugh.chord.codec.compress;

import com.github.luben.zstd.Zstd;
import io.github.orhaugh.chord.ChordTransportException;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Objects;
import net.jpountz.lz4.LZ4Compressor;
import net.jpountz.lz4.LZ4Factory;

/**
 * Compresses written bytes into ClickHouse frames, mirroring {@code CompressedWriteBuffer}: the
 * buffer fills up to one frame's worth of bytes and each flush emits one frame of checksum, header
 * and payload. Callers flush at protocol packet boundaries so frames never span packets.
 */
public final class FrameCompressingOutputStream extends OutputStream {

  private static final int HEADER_BYTES = 9;

  /** The server's default working buffer, one MiB, giving frames of at most that many bytes. */
  public static final int DEFAULT_FRAME_BYTES = 1 << 20;

  private final OutputStream out;
  private final Compression compression;
  private final int level;
  private final LZ4Compressor lz4;
  private final byte[] buffer;
  private int used;

  /**
   * Creates the stream with the default frame size.
   *
   * @param out the raw stream to write frames to
   * @param compression the method to encode with
   * @param level encoder level; pass {@link Compression#defaultLevel()} when in doubt
   */
  public FrameCompressingOutputStream(OutputStream out, Compression compression, int level) {
    this(out, compression, level, DEFAULT_FRAME_BYTES);
  }

  /**
   * Creates the stream.
   *
   * @param out the raw stream to write frames to
   * @param compression the method to encode with
   * @param level encoder level
   * @param frameBytes maximum uncompressed bytes per frame
   */
  public FrameCompressingOutputStream(
      OutputStream out, Compression compression, int level, int frameBytes) {
    this.out = Objects.requireNonNull(out, "out");
    this.compression = Objects.requireNonNull(compression, "compression");
    this.level = compression.checkLevel(level);
    if (frameBytes < 64) {
      throw new IllegalArgumentException("frameBytes must be at least 64");
    }
    this.buffer = new byte[frameBytes];
    this.lz4 =
        switch (compression) {
          case LZ4 -> LZ4Factory.fastestInstance().fastCompressor();
          case LZ4HC -> LZ4Factory.fastestInstance().highCompressor(level);
          default -> null;
        };
  }

  @Override
  public void write(int b) {
    if (used == buffer.length) {
      emitFrame();
    }
    buffer[used++] = (byte) b;
  }

  @Override
  public void write(byte[] source, int offset, int length) {
    Objects.checkFromIndexSize(offset, length, source.length);
    int written = 0;
    while (written < length) {
      if (used == buffer.length) {
        emitFrame();
      }
      int chunk = Math.min(length - written, buffer.length - used);
      System.arraycopy(source, offset + written, buffer, used, chunk);
      used += chunk;
      written += chunk;
    }
  }

  /** Emits any buffered bytes as a frame and flushes the underlying stream. */
  @Override
  public void flush() {
    emitFrame();
    try {
      out.flush();
    } catch (IOException e) {
      throw new ChordTransportException("I/O failure flushing compressed frames", e);
    }
  }

  private void emitFrame() {
    if (used == 0) {
      return;
    }
    byte[] payload;
    int payloadLength;
    switch (compression) {
      case NONE -> {
        payload = buffer;
        payloadLength = used;
      }
      case LZ4, LZ4HC -> {
        payload = new byte[lz4.maxCompressedLength(used)];
        payloadLength = lz4.compress(buffer, 0, used, payload, 0, payload.length);
      }
      case ZSTD -> {
        payload = new byte[(int) Zstd.compressBound(used)];
        long produced = Zstd.compressByteArray(payload, 0, payload.length, buffer, 0, used, level);
        if (Zstd.isError(produced)) {
          throw new IllegalStateException(
              "ZSTD compression failed: " + Zstd.getErrorName(produced));
        }
        payloadLength = (int) produced;
      }
      default -> throw new IllegalStateException();
    }

    byte[] frame = new byte[HEADER_BYTES + payloadLength];
    frame[0] = (byte) compression.methodByte();
    writeLittleEndianInt(frame, 1, HEADER_BYTES + payloadLength);
    writeLittleEndianInt(frame, 5, used);
    System.arraycopy(payload, 0, frame, HEADER_BYTES, payloadLength);

    long[] checksum = CityHash102.cityHash128(frame, 0, frame.length);
    byte[] checksumBytes = new byte[16];
    writeLittleEndianLong(checksumBytes, 0, checksum[0]);
    writeLittleEndianLong(checksumBytes, 8, checksum[1]);

    try {
      out.write(checksumBytes);
      out.write(frame);
    } catch (IOException e) {
      throw new ChordTransportException("I/O failure writing a compressed frame", e);
    }
    used = 0;
  }

  private static void writeLittleEndianInt(byte[] target, int index, int value) {
    target[index] = (byte) value;
    target[index + 1] = (byte) (value >>> 8);
    target[index + 2] = (byte) (value >>> 16);
    target[index + 3] = (byte) (value >>> 24);
  }

  private static void writeLittleEndianLong(byte[] target, int index, long value) {
    for (int i = 0; i < 8; i++) {
      target[index + i] = (byte) (value >>> (8 * i));
    }
  }
}
