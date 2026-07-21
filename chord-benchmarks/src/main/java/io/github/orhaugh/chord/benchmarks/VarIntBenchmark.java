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

import io.github.orhaugh.chord.protocol.wire.WireLimits;
import io.github.orhaugh.chord.protocol.wire.WireReader;
import io.github.orhaugh.chord.protocol.wire.WireWriter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
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
 * Throughput of the VarUInt codec over a deterministic mix of magnitudes, batching 1024 values per
 * invocation. Record the hardware, JVM flags and JDK alongside any published numbers, as required
 * by {@code docs/performance.md}.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
@State(Scope.Thread)
public class VarIntBenchmark {

  private static final int BATCH = 1024;

  private long[] values;
  private byte[] encoded;
  private ByteArrayOutputStream sink;

  /** Prepares a deterministic value mix and its encoded form. */
  @Setup
  public void prepare() {
    Random random = new Random(54488);
    values = new long[BATCH];
    for (int i = 0; i < BATCH; i++) {
      int magnitude = random.nextInt(64);
      values[i] = random.nextLong() >>> (63 - magnitude);
    }
    sink = new ByteArrayOutputStream(BATCH * 10);
    WireWriter writer = new WireWriter(sink);
    for (long value : values) {
      writer.writeVarUInt(value);
    }
    writer.flush();
    encoded = sink.toByteArray();
  }

  /**
   * Encodes the batch.
   *
   * @return sink size, to defeat dead code elimination
   */
  @Benchmark
  public int encodeBatch() {
    sink.reset();
    WireWriter writer = new WireWriter(sink);
    for (long value : values) {
      writer.writeVarUInt(value);
    }
    writer.flush();
    return sink.size();
  }

  /**
   * Decodes the batch.
   *
   * @return sum of decoded values, to defeat dead code elimination
   */
  @Benchmark
  public long decodeBatch() {
    WireReader reader = new WireReader(new ByteArrayInputStream(encoded), WireLimits.DEFAULTS);
    long sum = 0;
    for (int i = 0; i < BATCH; i++) {
      sum += reader.readVarUInt();
    }
    return sum;
  }
}
