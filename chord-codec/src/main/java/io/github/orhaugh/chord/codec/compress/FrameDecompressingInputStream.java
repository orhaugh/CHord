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
import io.github.orhaugh.chord.ChordDataCorruptionException;
import io.github.orhaugh.chord.ChordProtocolException;
import io.github.orhaugh.chord.ChordTimeoutException;
import io.github.orhaugh.chord.ChordTransportException;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.util.Objects;
import net.jpountz.lz4.LZ4Factory;
import net.jpountz.lz4.LZ4SafeDecompressor;

/**
 * Streams the decompressed bytes of a sequence of ClickHouse compressed frames.
 *
 * <p>Each frame is a 16 byte CityHash 1.0.2 checksum followed by a 9 byte header (method byte,
 * compressed size including the header, decompressed size, both little endian) and the payload. The
 * checksum covers header plus payload and is validated before any decompression; declared sizes are
 * bounded by {@link CompressionLimits} before any allocation, and the decompressor is required to
 * produce exactly the declared size. Any violation raises {@link ChordDataCorruptionException} or
 * {@link ChordProtocolException} and the connection carrying the stream must be abandoned.
 *
 * <p>Reading never consumes bytes beyond the frames it delivers: a new frame's checksum is only
 * read once a caller asks for bytes past the previous frame, so packet boundaries on the underlying
 * stream stay intact for the uncompressed parts of the protocol.
 */
public final class FrameDecompressingInputStream extends InputStream {

  private static final int CHECKSUM_BYTES = 16;
  private static final int HEADER_BYTES = 9;

  private final InputStream in;
  private final CompressionLimits limits;
  private final LZ4SafeDecompressor lz4 = LZ4Factory.fastestInstance().safeDecompressor();

  private byte[] decompressed = new byte[0];
  private int position;
  private int limit;

  /**
   * Creates the stream.
   *
   * @param in the raw stream carrying frames
   * @param limits bounds for declared frame sizes
   */
  public FrameDecompressingInputStream(InputStream in, CompressionLimits limits) {
    this.in = Objects.requireNonNull(in, "in");
    this.limits = Objects.requireNonNull(limits, "limits");
  }

  @Override
  public int read() {
    if (position == limit) {
      readFrame();
    }
    return decompressed[position++] & 0xFF;
  }

  @Override
  public int read(byte[] target, int offset, int length) {
    Objects.checkFromIndexSize(offset, length, target.length);
    if (length == 0) {
      return 0;
    }
    if (position == limit) {
      readFrame();
    }
    int available = Math.min(length, limit - position);
    System.arraycopy(decompressed, position, target, offset, available);
    position += available;
    return available;
  }

  private void readFrame() {
    byte[] checksumAndHeader = new byte[CHECKSUM_BYTES + HEADER_BYTES];
    readFully(checksumAndHeader, 0, checksumAndHeader.length);

    long expectedLow = littleEndianLong(checksumAndHeader, 0);
    long expectedHigh = littleEndianLong(checksumAndHeader, 8);
    int methodByte = checksumAndHeader[CHECKSUM_BYTES] & 0xFF;
    long compressedWithHeader = littleEndianInt(checksumAndHeader, CHECKSUM_BYTES + 1);
    long declaredDecompressed = littleEndianInt(checksumAndHeader, CHECKSUM_BYTES + 5);

    if (compressedWithHeader < HEADER_BYTES
        || compressedWithHeader > limits.maxCompressedFrameBytes()) {
      throw new ChordProtocolException(
          "Compressed frame declares "
              + compressedWithHeader
              + " bytes including its header, outside the permitted range up to "
              + limits.maxCompressedFrameBytes());
    }
    if (declaredDecompressed > limits.maxDecompressedFrameBytes()) {
      throw new ChordProtocolException(
          "Compressed frame declares "
              + declaredDecompressed
              + " decompressed bytes, maximum allowed is "
              + limits.maxDecompressedFrameBytes());
    }

    byte[] frame = new byte[(int) compressedWithHeader];
    System.arraycopy(checksumAndHeader, CHECKSUM_BYTES, frame, 0, HEADER_BYTES);
    readFully(frame, HEADER_BYTES, frame.length - HEADER_BYTES);

    long[] actual = CityHash102.cityHash128(frame, 0, frame.length);
    if (actual[0] != expectedLow || actual[1] != expectedHigh) {
      throw new ChordDataCorruptionException(
          "Compressed frame checksum mismatch: the frame was corrupted in transit, in memory or"
              + " by the peer");
    }

    int payloadLength = frame.length - HEADER_BYTES;
    int decompressedLength = (int) declaredDecompressed;
    ensureCapacity(decompressedLength);
    if (methodByte == Compression.NONE.methodByte()) {
      if (payloadLength != decompressedLength) {
        throw new ChordDataCorruptionException(
            "NONE frame sizes disagree: payload "
                + payloadLength
                + " bytes, declared "
                + decompressedLength);
      }
      System.arraycopy(frame, HEADER_BYTES, decompressed, 0, payloadLength);
    } else if (methodByte == Compression.LZ4.methodByte()) {
      int produced;
      try {
        produced =
            lz4.decompress(frame, HEADER_BYTES, payloadLength, decompressed, 0, decompressedLength);
      } catch (RuntimeException e) {
        throw new ChordDataCorruptionException("LZ4 payload is corrupt", e);
      }
      if (produced != decompressedLength) {
        throw new ChordDataCorruptionException(
            "LZ4 frame produced " + produced + " bytes, declared " + decompressedLength);
      }
    } else if (methodByte == Compression.ZSTD.methodByte()) {
      long produced =
          Zstd.decompressByteArray(
              decompressed, 0, decompressedLength, frame, HEADER_BYTES, payloadLength);
      if (Zstd.isError(produced)) {
        throw new ChordDataCorruptionException(
            "ZSTD payload is corrupt: " + Zstd.getErrorName(produced));
      }
      if (produced != decompressedLength) {
        throw new ChordDataCorruptionException(
            "ZSTD frame produced " + produced + " bytes, declared " + decompressedLength);
      }
    } else {
      throw new ChordProtocolException(
          "Unknown compression method byte 0x"
              + Integer.toHexString(methodByte)
              + "; CHord supports NONE, LZ4 and ZSTD on the wire");
    }
    position = 0;
    limit = decompressedLength;
    if (limit == 0) {
      throw new ChordProtocolException(
          "Compressed frame declares zero decompressed bytes; a compliant peer never sends"
              + " empty frames");
    }
  }

  private void ensureCapacity(int length) {
    if (decompressed.length < length) {
      decompressed = new byte[length];
    }
  }

  private void readFully(byte[] target, int offset, int length) {
    int done = 0;
    while (done < length) {
      int n;
      try {
        n = in.read(target, offset + done, length - done);
      } catch (SocketTimeoutException e) {
        throw new ChordTimeoutException("Read timed out inside a compressed frame", e);
      } catch (IOException e) {
        throw new ChordTransportException("I/O failure inside a compressed frame", e);
      }
      if (n < 0) {
        throw new ChordProtocolException("Stream ended in the middle of a compressed frame");
      }
      done += n;
    }
  }

  private static long littleEndianLong(byte[] data, int index) {
    long result = 0;
    for (int i = 7; i >= 0; i--) {
      result = (result << 8) | (data[index + i] & 0xFFL);
    }
    return result;
  }

  private static long littleEndianInt(byte[] data, int index) {
    long result = 0;
    for (int i = 3; i >= 0; i--) {
      result = (result << 8) | (data[index + i] & 0xFFL);
    }
    return result;
  }
}
