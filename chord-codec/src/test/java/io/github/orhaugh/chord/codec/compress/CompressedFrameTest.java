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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.orhaugh.chord.ChordDataCorruptionException;
import io.github.orhaugh.chord.ChordProtocolException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Random;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;
import org.junit.jupiter.api.Test;

/**
 * The compressed frame codec against golden frames produced by the real clickhouse-compressor
 * (which cross validates the CityHash 1.0.2 port), plus round trips and hostile input.
 */
class CompressedFrameTest {

  private static byte[] resource(String name) {
    try (InputStream in = CompressedFrameTest.class.getResourceAsStream("/compress/" + name)) {
      return in.readAllBytes();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static byte[] readAll(FrameDecompressingInputStream in, int expected) {
    byte[] out = new byte[expected];
    int done = 0;
    while (done < expected) {
      int n = in.read(out, done, expected - done);
      done += n;
    }
    return out;
  }

  @Test
  void decodesGoldenFramesFromClickhouseCompressor() {
    byte[] expected = resource("input.bin");
    for (String name : new String[] {"frame-lz4.bin", "frame-zstd.bin", "frame-none.bin"}) {
      FrameDecompressingInputStream in =
          new FrameDecompressingInputStream(
              new ByteArrayInputStream(resource(name)), CompressionLimits.DEFAULTS);
      assertThat(readAll(in, expected.length)).as(name).isEqualTo(expected);
    }
  }

  @Test
  void roundTripsEveryMethodAcrossFrameBoundaries() {
    Random random = new Random(54488);
    byte[] input = new byte[300_000];
    random.nextBytes(input);
    // Make part of it compressible.
    java.util.Arrays.fill(input, 100_000, 200_000, (byte) 42);

    for (Compression compression : Compression.values()) {
      ByteArrayOutputStream sink = new ByteArrayOutputStream();
      FrameCompressingOutputStream out =
          new FrameCompressingOutputStream(
              sink, compression, compression.defaultLevel(), 64 * 1024);
      out.write(input, 0, input.length);
      out.flush();

      FrameDecompressingInputStream in =
          new FrameDecompressingInputStream(
              new ByteArrayInputStream(sink.toByteArray()), CompressionLimits.DEFAULTS);
      assertThat(readAll(in, input.length)).as(compression.name()).isEqualTo(input);
    }
  }

  @Test
  void lz4hcFramesDecodeAsPlainLz4() {
    byte[] input = "repetition repetition repetition repetition".repeat(100).getBytes();
    ByteArrayOutputStream sink = new ByteArrayOutputStream();
    FrameCompressingOutputStream out = new FrameCompressingOutputStream(sink, Compression.LZ4HC, 9);
    out.write(input, 0, input.length);
    out.flush();

    byte[] framed = sink.toByteArray();
    assertThat(framed[16] & 0xFF).isEqualTo(0x82); // shares the LZ4 method byte
    FrameDecompressingInputStream in =
        new FrameDecompressingInputStream(
            new ByteArrayInputStream(framed), CompressionLimits.DEFAULTS);
    assertThat(readAll(in, input.length)).isEqualTo(input);
  }

  @Test
  void corruptPayloadFailsTheChecksumBeforeDecompression() {
    byte[] framed = resource("frame-lz4.bin").clone();
    framed[framed.length - 1] ^= 0x01;
    FrameDecompressingInputStream in =
        new FrameDecompressingInputStream(
            new ByteArrayInputStream(framed), CompressionLimits.DEFAULTS);
    assertThatThrownBy(() -> in.read())
        .isInstanceOf(ChordDataCorruptionException.class)
        .hasMessageContaining("checksum");
  }

  @Test
  void corruptChecksumFails() {
    byte[] framed = resource("frame-zstd.bin").clone();
    framed[3] ^= 0x40;
    FrameDecompressingInputStream in =
        new FrameDecompressingInputStream(
            new ByteArrayInputStream(framed), CompressionLimits.DEFAULTS);
    assertThatThrownBy(() -> in.read()).isInstanceOf(ChordDataCorruptionException.class);
  }

  @Test
  void truncatedFrameFailsExplicitly() {
    byte[] framed = resource("frame-lz4.bin");
    byte[] truncated = java.util.Arrays.copyOf(framed, framed.length - 5);
    FrameDecompressingInputStream in =
        new FrameDecompressingInputStream(
            new ByteArrayInputStream(truncated), CompressionLimits.DEFAULTS);
    assertThatThrownBy(() -> in.read())
        .isInstanceOf(ChordProtocolException.class)
        .hasMessageContaining("middle of a compressed frame");
  }

  @Test
  void oversizedDeclarationsAreRejectedBeforeAllocation() {
    // A frame declaring a huge decompressed size must fail on the declaration, not by
    // allocating. Limits are set small so the declaration itself trips the guard.
    byte[] input = new byte[128];
    ByteArrayOutputStream sink = new ByteArrayOutputStream();
    FrameCompressingOutputStream out = new FrameCompressingOutputStream(sink, Compression.NONE, 0);
    out.write(input, 0, input.length);
    out.flush();

    CompressionLimits tiny = new CompressionLimits(64, 64);
    FrameDecompressingInputStream in =
        new FrameDecompressingInputStream(new ByteArrayInputStream(sink.toByteArray()), tiny);
    assertThatThrownBy(() -> in.read())
        .isInstanceOf(ChordProtocolException.class)
        .hasMessageContaining("permitted");
  }

  @Test
  void decompressionBombIsRejectedByTheSizeMismatch() {
    // Craft a frame whose LZ4 payload expands to more than it declares: declared size is the
    // allocation bound, so the decompressor must fail rather than write past it.
    byte[] bomb = new byte[1000];
    java.util.Arrays.fill(bomb, (byte) 7);
    ByteArrayOutputStream sink = new ByteArrayOutputStream();
    FrameCompressingOutputStream out = new FrameCompressingOutputStream(sink, Compression.LZ4, 0);
    out.write(bomb, 0, bomb.length);
    out.flush();
    byte[] framed = sink.toByteArray();
    // Understate the declared decompressed size and refresh the checksum so only the size lies.
    framed[16 + 5] = 10;
    framed[16 + 6] = 0;
    long[] checksum = CityHash102.cityHash128(framed, 16, framed.length - 16);
    for (int i = 0; i < 8; i++) {
      framed[i] = (byte) (checksum[0] >>> (8 * i));
      framed[8 + i] = (byte) (checksum[1] >>> (8 * i));
    }

    FrameDecompressingInputStream in =
        new FrameDecompressingInputStream(
            new ByteArrayInputStream(framed), CompressionLimits.DEFAULTS);
    assertThatThrownBy(() -> in.read()).isInstanceOf(ChordDataCorruptionException.class);
  }

  @Test
  void unknownMethodByteIsRejected() {
    byte[] framed = resource("frame-lz4.bin").clone();
    framed[16] = (byte) 0x95; // Gorilla: a MergeTree column codec, not a network method
    long[] checksum = CityHash102.cityHash128(framed, 16, framed.length - 16);
    for (int i = 0; i < 8; i++) {
      framed[i] = (byte) (checksum[0] >>> (8 * i));
      framed[8 + i] = (byte) (checksum[1] >>> (8 * i));
    }
    FrameDecompressingInputStream in =
        new FrameDecompressingInputStream(
            new ByteArrayInputStream(framed), CompressionLimits.DEFAULTS);
    assertThatThrownBy(() -> in.read())
        .isInstanceOf(ChordProtocolException.class)
        .hasMessageContaining("method byte");
  }

  @Property(tries = 60)
  void roundTripsArbitraryPayloads(
      @ForAll @Size(min = 1, max = 4096) byte[] payload,
      @ForAll @IntRange(min = 0, max = 3) int methodIndex) {
    Compression compression = Compression.values()[methodIndex];
    ByteArrayOutputStream sink = new ByteArrayOutputStream();
    FrameCompressingOutputStream out =
        new FrameCompressingOutputStream(sink, compression, compression.defaultLevel(), 512);
    out.write(payload, 0, payload.length);
    out.flush();
    FrameDecompressingInputStream in =
        new FrameDecompressingInputStream(
            new ByteArrayInputStream(sink.toByteArray()), CompressionLimits.DEFAULTS);
    assertThat(readAll(in, payload.length)).isEqualTo(payload);
  }
}
