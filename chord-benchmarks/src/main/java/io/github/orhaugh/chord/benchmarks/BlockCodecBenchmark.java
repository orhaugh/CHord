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
package io.github.orhaugh.chord.benchmarks;

import io.github.orhaugh.chord.codec.block.Block;
import io.github.orhaugh.chord.codec.block.BlockLimits;
import io.github.orhaugh.chord.codec.block.BlockReader;
import io.github.orhaugh.chord.codec.block.BlockWriter;
import io.github.orhaugh.chord.codec.block.DecodeContext;
import io.github.orhaugh.chord.codec.column.BlockBuilder;
import io.github.orhaugh.chord.codec.type.TypeParser;
import io.github.orhaugh.chord.protocol.wire.WireLimits;
import io.github.orhaugh.chord.protocol.wire.WireReader;
import io.github.orhaugh.chord.protocol.wire.WireWriter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.time.ZoneId;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Decode and encode throughput for a realistic mixed block: UInt64 keys, short strings and a
 * nullable column across 8192 rows, the hot path of every SELECT stream and INSERT batch. Record
 * the hardware, JVM flags and JDK alongside any published numbers, as required by {@code
 * docs/performance.md}.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
@State(Scope.Thread)
public class BlockCodecBenchmark {

  private static final long REVISION = 54488;
  private static final int ROWS = 8192;

  private Block block;
  private byte[] encoded;

  /** Builds the block once and its encoded form once. */
  @Setup
  public void prepare() {
    Random random = new Random(54488);
    BlockBuilder builder =
        BlockBuilder.forColumnTypes(
            List.of(
                TypeParser.parse("UInt64", 10_000, 32),
                TypeParser.parse("String", 10_000, 32),
                TypeParser.parse("Nullable(Float64)", 10_000, 32)));
    for (int row = 0; row < ROWS; row++) {
      builder.addRow(
          BigInteger.valueOf(random.nextLong() & Long.MAX_VALUE),
          "value-" + random.nextInt(100_000),
          random.nextInt(10) == 0 ? null : random.nextDouble());
    }
    block = builder.build();
    encoded = encode(block);
  }

  private static byte[] encode(Block toEncode) {
    ByteArrayOutputStream sink = new ByteArrayOutputStream();
    WireWriter out = new WireWriter(sink);
    BlockWriter.write(out, toEncode, REVISION);
    out.flush();
    return sink.toByteArray();
  }

  /**
   * Decodes the encoded block.
   *
   * @return the decoded block, returned so the JIT cannot elide the work
   */
  @Benchmark
  public Block decode() {
    return BlockReader.read(
        new WireReader(new ByteArrayInputStream(encoded), WireLimits.DEFAULTS),
        new DecodeContext(BlockLimits.DEFAULTS, REVISION, ZoneId.of("UTC")));
  }

  /**
   * Encodes the prepared block.
   *
   * @return the encoded bytes, returned so the JIT cannot elide the work
   */
  @Benchmark
  public byte[] encode() {
    return encode(block);
  }
}
