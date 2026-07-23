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

import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.data_formats.ClickHouseBinaryFormatReader;
import com.clickhouse.client.api.insert.InsertResponse;
import com.clickhouse.client.api.query.QueryResponse;
import com.clickhouse.data.ClickHouseFormat;
import io.github.orhaugh.chord.client.ConnectionOptions;
import io.github.orhaugh.chord.client.InsertStream;
import io.github.orhaugh.chord.client.NativeConnection;
import io.github.orhaugh.chord.client.QueryRequest;
import io.github.orhaugh.chord.client.QueryResult;
import io.github.orhaugh.chord.codec.block.Block;
import io.github.orhaugh.chord.codec.column.BlockBuilder;
import io.github.orhaugh.chord.codec.column.Columns;
import io.github.orhaugh.chord.codec.compress.Compression;
import io.github.orhaugh.chord.testkit.ClickHouseServerContainer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * CHord's native TCP path against the official HTTP client (client-v2, RowBinary), both talking to
 * the same containerised server with LZ4 in both directions. Three shapes: streaming a wide million
 * row SELECT, inserting one hundred thousand rows into a Null engine table, and the round trip
 * latency of a point query, where per request overhead dominates.
 *
 * <p>This measures the two client stacks as users consume them, not HTTP versus TCP in isolation:
 * format encode and decode, buffering, connection handling and protocol framing all count. Record
 * the hardware, JVM flags and JDK alongside any published numbers, as required by {@code
 * docs/performance.md}.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 3)
@Measurement(iterations = 5, time = 3)
@Fork(1)
@State(Scope.Benchmark)
public class HttpComparisonBenchmark {

  private static final String SELECT_MILLION =
      "SELECT number, toString(number) AS s, number / 2.0 AS d FROM numbers(1000000)";
  private static final int INSERT_ROWS = 100_000;

  private ClickHouseServerContainer server;
  private NativeConnection chord;
  private Client http;

  /**
   * Starts both clients against either a local container (the default) or a remote server described
   * by a properties file named in {@code -Dchord.bench.config=<path>} with keys {@code host},
   * {@code nativePort}, {@code httpPort}, {@code user}, {@code password}, {@code database} and
   * {@code tls} (true for ClickHouse Cloud: native 9440, https 8443). Keeping credentials in a file
   * keeps them out of command lines and shell history.
   *
   * @throws Exception on startup failure
   */
  @Setup(Level.Trial)
  public void start() throws Exception {
    String configPath = System.getProperty("chord.bench.config", "");
    final String host;
    final int nativePort;
    final int httpPort;
    final String user;
    final String password;
    final String database;
    final boolean tls;
    if (configPath.isEmpty()) {
      server = new ClickHouseServerContainer();
      server.start();
      host = server.getHost();
      nativePort = server.nativePort();
      httpPort = server.httpPort();
      user = server.username();
      password = server.password();
      database = server.database();
      tls = false;
    } else {
      java.util.Properties config = new java.util.Properties();
      try (java.io.Reader reader =
          java.nio.file.Files.newBufferedReader(java.nio.file.Path.of(configPath))) {
        config.load(reader);
      }
      host = require(config, "host");
      nativePort = Integer.parseInt(config.getProperty("nativePort", "9440"));
      httpPort = Integer.parseInt(config.getProperty("httpPort", "8443"));
      user = config.getProperty("user", "default");
      password = require(config, "password");
      database = config.getProperty("database", "default");
      tls = Boolean.parseBoolean(config.getProperty("tls", "true"));
    }

    ConnectionOptions.Builder chordOptions =
        ConnectionOptions.builder()
            .host(host)
            .port(nativePort)
            .database(database)
            .username(user)
            .password(password)
            .compression(Compression.LZ4);
    if (tls) {
      chordOptions.tls(io.github.orhaugh.chord.transport.TlsOptions.systemTrust());
    } else {
      chordOptions.allowPlaintextPassword(true);
    }
    chord = NativeConnection.open(chordOptions.build());

    http =
        new Client.Builder()
            .addEndpoint((tls ? "https://" : "http://") + host + ":" + httpPort)
            .setUsername(user)
            .setPassword(password)
            .setDefaultDatabase(database)
            .compressServerResponse(true)
            .compressClientRequest(true)
            .build();
    try (QueryResult result =
        chord.query(
            QueryRequest.of(
                "CREATE TABLE IF NOT EXISTS bench_sink (n UInt64, s String, d Float64)"
                    + " ENGINE = Null"))) {
      while (result.nextBlock().isPresent()) {
        // Drain.
      }
    }
  }

  private static String require(java.util.Properties config, String key) {
    String value = config.getProperty(key);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(
          "Missing \"" + key + "\" in the chord.bench.config properties file");
    }
    return value;
  }

  /** Stops both clients and the server. */
  @TearDown(Level.Trial)
  public void stop() {
    if (chord != null) {
      chord.close();
    }
    if (http != null) {
      http.close();
    }
    if (server != null) {
      server.stop();
    }
  }

  /**
   * Streams a million rows over native TCP, touching every value.
   *
   * @param blackhole sink preventing dead code elimination
   */
  @Benchmark
  public void chordSelectMillionRows(Blackhole blackhole) {
    try (QueryResult result = chord.query(QueryRequest.of(SELECT_MILLION))) {
      Optional<Block> next;
      while ((next = result.nextBlock()).isPresent()) {
        Block block = next.get();
        Columns.UInt64Column numbers = (Columns.UInt64Column) block.column(0);
        Columns.StringColumn strings = (Columns.StringColumn) block.column(1);
        Columns.Float64Column doubles = (Columns.Float64Column) block.column(2);
        for (int row = 0; row < block.rows(); row++) {
          blackhole.consume(numbers.rawLongAt(row));
          blackhole.consume(strings.stringAt(row));
          blackhole.consume(doubles.doubleAt(row));
        }
      }
    }
  }

  /**
   * Streams a million rows over HTTP RowBinary, touching every value.
   *
   * @param blackhole sink preventing dead code elimination
   * @throws Exception on client failure
   */
  @Benchmark
  public void httpSelectMillionRows(Blackhole blackhole) throws Exception {
    try (QueryResponse response = http.query(SELECT_MILLION).get()) {
      ClickHouseBinaryFormatReader reader = http.newBinaryFormatReader(response);
      while (reader.next() != null) {
        blackhole.consume(reader.getLong(1));
        blackhole.consume(reader.getString(2));
        blackhole.consume(reader.getDouble(3));
      }
    }
  }

  /** Inserts one hundred thousand rows as native blocks, encoding included. */
  @Benchmark
  public void chordInsertHundredThousandRows() {
    try (InsertStream insert = chord.insert(QueryRequest.of("INSERT INTO bench_sink VALUES"))) {
      BlockBuilder builder = insert.newBlock();
      for (long n = 0; n < INSERT_ROWS; n++) {
        builder.addRow(BigInteger.valueOf(n), "value-" + n, n / 2.0);
      }
      insert.send(builder.build());
      insert.finish();
    }
  }

  /**
   * Inserts one hundred thousand rows as RowBinary over HTTP, encoding included.
   *
   * @throws Exception on client failure
   */
  @Benchmark
  public void httpInsertHundredThousandRows() throws Exception {
    ByteArrayOutputStream payload = new ByteArrayOutputStream(INSERT_ROWS * 24);
    for (long n = 0; n < INSERT_ROWS; n++) {
      writeLongLe(payload, n);
      byte[] text = ("value-" + n).getBytes(StandardCharsets.UTF_8);
      writeVarUInt(payload, text.length);
      payload.write(text, 0, text.length);
      writeLongLe(payload, Double.doubleToLongBits(n / 2.0));
    }
    try (InsertResponse response =
        http.insert(
                "bench_sink",
                new ByteArrayInputStream(payload.toByteArray()),
                ClickHouseFormat.RowBinary)
            .get()) {
      // The response body carries nothing further.
      Blackhole.consumeCPU(0);
    }
  }

  /**
   * The full round trip of a trivial query over native TCP.
   *
   * @param blackhole sink preventing dead code elimination
   */
  @Benchmark
  @BenchmarkMode(Mode.AverageTime)
  @OutputTimeUnit(TimeUnit.MICROSECONDS)
  public void chordPointQuery(Blackhole blackhole) {
    try (QueryResult result = chord.query(QueryRequest.of("SELECT 1"))) {
      Optional<Block> next;
      while ((next = result.nextBlock()).isPresent()) {
        blackhole.consume(next.get().rows());
      }
    }
  }

  /**
   * The full round trip of a trivial query over HTTP.
   *
   * @param blackhole sink preventing dead code elimination
   * @throws Exception on client failure
   */
  @Benchmark
  @BenchmarkMode(Mode.AverageTime)
  @OutputTimeUnit(TimeUnit.MICROSECONDS)
  public void httpPointQuery(Blackhole blackhole) throws Exception {
    try (QueryResponse response = http.query("SELECT 1").get()) {
      ClickHouseBinaryFormatReader reader = http.newBinaryFormatReader(response);
      while (reader.next() != null) {
        blackhole.consume(reader.getByte(1));
      }
    }
  }

  private static void writeLongLe(ByteArrayOutputStream out, long value) {
    for (int shift = 0; shift < 64; shift += 8) {
      out.write((int) (value >>> shift));
    }
  }

  private static void writeVarUInt(ByteArrayOutputStream out, long value) {
    long remaining = value;
    while ((remaining & ~0x7FL) != 0) {
      out.write((int) ((remaining & 0x7F) | 0x80));
      remaining >>>= 7;
    }
    out.write((int) remaining);
  }
}
