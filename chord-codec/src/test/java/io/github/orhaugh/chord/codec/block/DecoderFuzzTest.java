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
package io.github.orhaugh.chord.codec.block;

import static org.assertj.core.api.Assertions.fail;

import io.github.orhaugh.chord.ChordException;
import io.github.orhaugh.chord.codec.column.BlockBuilder;
import io.github.orhaugh.chord.codec.compress.CompressionLimits;
import io.github.orhaugh.chord.codec.compress.FrameDecompressingInputStream;
import io.github.orhaugh.chord.codec.type.TypeParser;
import io.github.orhaugh.chord.protocol.wire.WireLimits;
import io.github.orhaugh.chord.protocol.wire.WireReader;
import io.github.orhaugh.chord.protocol.wire.WireWriter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Deterministic mutation fuzzing over the decoders, seeded from the golden vectors and freshly
 * encoded blocks. The oracle: hostile bytes either decode or raise a typed {@link ChordException};
 * any other throwable (index errors, negative allocations, NPEs) is a decoder bug, and the limits
 * must keep allocations bounded while getting there.
 *
 * <p>Iterations per seed scale with {@code -Dchord.fuzz.iterations} (default 300, giving a few
 * seconds in a normal build); raise it for deeper local or scheduled runs. Mutations draw from a
 * fixed PRNG seed, so every failure reproduces.
 */
@Timeout(300)
class DecoderFuzzTest {

  private static final int ITERATIONS = Integer.getInteger("chord.fuzz.iterations", 300);
  private static final long PRNG_SEED = 0xC0FFEE_CAFEL;

  /** Tight limits so inflated length fields fail fast instead of allocating gigabytes. */
  private static final WireLimits WIRE = new WireLimits(1 << 20, 16, 10_000);

  private static final BlockLimits BLOCKS =
      new BlockLimits(64, 1 << 20, 1 << 20, 1 << 20, 1 << 22, 512, 32);

  private static final CompressionLimits FRAMES = new CompressionLimits(1 << 22, 1 << 22);

  private static final List<String> GOLDEN_SERIALIZATIONS =
      List.of(
          "sparse-uint64.bin",
          "lc-string.bin",
          "lc-nullable.bin",
          "array-lc.bin",
          "variant.bin",
          "dynamic.bin",
          "json.bin",
          "json-typed.bin");

  @Test
  void mutatedGoldenSerializationsNeverEscapeTypedFailures() {
    Random random = new Random(PRNG_SEED);
    for (String resource : GOLDEN_SERIALIZATIONS) {
      byte[] seed = resource("/serialization/" + resource);
      fuzz(resource, seed, random, DecoderFuzzTest::decodeRevisionZeroNativeFile);
    }
  }

  @Test
  void mutatedWireBlocksNeverEscapeTypedFailures() {
    Random random = new Random(PRNG_SEED + 1);
    for (byte[] seed : encodedBlockSeeds()) {
      fuzz("encoded-block", seed, random, DecoderFuzzTest::decodeWireBlock);
    }
  }

  @Test
  void mutatedCompressedFramesNeverEscapeTypedFailures() {
    Random random = new Random(PRNG_SEED + 2);
    for (String resource : List.of("frame-lz4.bin", "frame-zstd.bin", "frame-none.bin")) {
      byte[] seed = resource("/compress/" + resource);
      fuzz(resource, seed, random, DecoderFuzzTest::decompressFully);
    }
  }

  private interface Decoder {
    void decode(byte[] bytes) throws IOException;
  }

  private static void fuzz(String label, byte[] seed, Random random, Decoder decoder) {
    // Every truncation point for short seeds, then random point mutations.
    int truncations = Math.min(seed.length, 128);
    for (int i = 0; i < truncations; i++) {
      int cut = seed.length * i / Math.max(1, truncations);
      byte[] mutant = new byte[cut];
      System.arraycopy(seed, 0, mutant, 0, cut);
      run(label + " truncated@" + cut, mutant, decoder);
    }
    for (int i = 0; i < ITERATIONS; i++) {
      byte[] mutant = seed.clone();
      switch (random.nextInt(4)) {
        case 0 -> { // single bit flip
          int at = random.nextInt(mutant.length);
          mutant[at] ^= (byte) (1 << random.nextInt(8));
        }
        case 1 -> { // byte substitution
          mutant[random.nextInt(mutant.length)] = (byte) random.nextInt(256);
        }
        case 2 -> { // varint inflation: a run of 0xFF continuation bytes
          int at = random.nextInt(mutant.length);
          for (int j = at; j < Math.min(mutant.length, at + 9); j++) {
            mutant[j] = (byte) 0xFF;
          }
        }
        default -> { // splice a random window elsewhere in the stream
          int from = random.nextInt(mutant.length);
          int to = random.nextInt(mutant.length);
          int length = Math.min(random.nextInt(16) + 1, mutant.length - Math.max(from, to));
          System.arraycopy(seed, from, mutant, to, Math.max(0, length));
        }
      }
      run(label + " mutation#" + i, mutant, decoder);
    }
  }

  private static void run(String what, byte[] mutant, Decoder decoder) {
    try {
      decoder.decode(mutant);
      // Decoding a mutant successfully is fine: many mutations land in value bytes.
    } catch (ChordException expected) {
      // The oracle: hostile input maps to typed, catchable failures.
    } catch (Throwable t) {
      fail("Decoder escaped the typed failure contract on " + what, t);
    }
  }

  private static void decodeRevisionZeroNativeFile(byte[] bytes) {
    WireReader in = new WireReader(new ByteArrayInputStream(bytes), WIRE);
    long columns = in.readVarUInt();
    long rows = in.readVarUInt();
    if (columns != 1 || rows < 0 || rows > (1 << 20)) {
      return; // the header mutation already diverged; nothing left to decode
    }
    in.readString(512);
    String typeName = in.readString(512);
    io.github.orhaugh.chord.codec.column.ColumnReader.read(
        in,
        TypeParser.parse(typeName, 512, 32),
        (int) rows,
        new DecodeContext(BLOCKS, 54488, ZoneId.of("UTC")));
  }

  private static void decodeWireBlock(byte[] bytes) {
    BlockReader.read(
        new WireReader(new ByteArrayInputStream(bytes), WIRE),
        new DecodeContext(BLOCKS, 54488, ZoneId.of("UTC")));
  }

  private static void decompressFully(byte[] bytes) throws IOException {
    try (InputStream frames =
        new FrameDecompressingInputStream(new ByteArrayInputStream(bytes), FRAMES)) {
      byte[] scratch = new byte[8192];
      long total = 0;
      int n;
      while ((n = frames.read(scratch)) >= 0) {
        total += n;
        if (total > (1 << 24)) {
          return; // decompression bomb guard for the test itself; limits are asserted elsewhere
        }
      }
    }
  }

  private static List<byte[]> encodedBlockSeeds() {
    List<byte[]> seeds = new ArrayList<>();
    seeds.add(
        encode(
            BlockBuilder.forColumnTypes(
                    List.of(
                        TypeParser.parse("UInt64", 512, 32),
                        TypeParser.parse("Nullable(String)", 512, 32),
                        TypeParser.parse("Array(Int32)", 512, 32)))
                .addRow(BigInteger.ONE, "hello", List.of(1, 2, 3))
                .addRow(BigInteger.TWO, null, List.of())));
    seeds.add(
        encode(
            BlockBuilder.forColumnTypes(
                    List.of(
                        TypeParser.parse("LowCardinality(String)", 512, 32),
                        TypeParser.parse("DateTime64(6, 'UTC')", 512, 32)))
                .addRow("dict", java.time.Instant.ofEpochSecond(1, 999_999_000))
                .addRow("dict", java.time.Instant.EPOCH)));
    return seeds;
  }

  private static byte[] encode(BlockBuilder builder) {
    ByteArrayOutputStream sink = new ByteArrayOutputStream();
    WireWriter out = new WireWriter(sink);
    BlockWriter.write(out, builder.build(), 54488);
    out.flush();
    return sink.toByteArray();
  }

  private static byte[] resource(String path) {
    try (InputStream stream = DecoderFuzzTest.class.getResourceAsStream(path)) {
      if (stream == null) {
        throw new AssertionError("Missing golden resource " + path);
      }
      return stream.readAllBytes();
    } catch (IOException e) {
      throw new AssertionError(e);
    }
  }
}
