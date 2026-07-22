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
package io.github.orhaugh.chord.client;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.orhaugh.chord.codec.block.Block;
import io.github.orhaugh.chord.codec.column.BlockBuilder;
import io.github.orhaugh.chord.codec.column.Columns;
import io.github.orhaugh.chord.codec.compress.Compression;
import io.github.orhaugh.chord.protocol.state.ConnectionState;
import io.github.orhaugh.chord.testkit.ClickHouseServerContainer;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Compressed exchanges against a real ClickHouse server, for every method CHord encodes. */
@Testcontainers
class CompressionIT {

  @Container
  private static final ClickHouseServerContainer CLICKHOUSE = new ClickHouseServerContainer();

  private static final AtomicInteger TABLE_IDS = new AtomicInteger();

  private static NativeConnection connect(Compression compression) {
    ConnectionOptions.Builder builder =
        ConnectionOptions.builder()
            .host(CLICKHOUSE.getHost())
            .port(CLICKHOUSE.nativePort())
            .database(CLICKHOUSE.database())
            .username(CLICKHOUSE.username())
            .password(CLICKHOUSE.password())
            .allowPlaintextPassword(true);
    if (compression != null) {
      builder.compression(compression);
    }
    return NativeConnection.open(builder.build());
  }

  @ParameterizedTest
  @EnumSource(Compression.class)
  void selectsLargeResultsCompressed(Compression compression) {
    try (NativeConnection connection = connect(compression);
        QueryResult result =
            connection.query(
                QueryRequest.builder(
                        "SELECT number, toString(number) AS s FROM system.numbers LIMIT 200000")
                    .setting("max_block_size", 32768)
                    .build())) {
      long expected = 0;
      Optional<Block> next;
      while ((next = result.nextBlock()).isPresent()) {
        Block block = next.orElseThrow();
        Columns.UInt64Column numbers = (Columns.UInt64Column) block.column(0);
        Columns.StringColumn strings = (Columns.StringColumn) block.column(1);
        for (int i = 0; i < block.rows(); i++) {
          if (numbers.rawLongAt(i) != expected
              || !strings.stringAt(i).equals(Long.toString(expected))) {
            throw new AssertionError("row " + expected + " corrupted");
          }
          expected++;
        }
      }
      assertThat(expected).isEqualTo(200_000);
      assertThat(connection.state()).isEqualTo(ConnectionState.READY);
    }
  }

  @ParameterizedTest
  @EnumSource(Compression.class)
  void insertsRoundTripCompressed(Compression compression) {
    try (NativeConnection connection = connect(compression)) {
      String table = "compressed_" + compression.name().toLowerCase() + TABLE_IDS.incrementAndGet();
      try (QueryResult result =
          connection.query(
              QueryRequest.of("CREATE TABLE " + table + " (n UInt64, s String) ENGINE = Memory"))) {
        result.nextBlock();
      }

      try (InsertStream insert =
          connection.insert(QueryRequest.of("INSERT INTO " + table + " VALUES"))) {
        BlockBuilder builder = insert.newBlock();
        for (long row = 0; row < 20_000; row++) {
          builder.addRow(row, "value-" + row);
        }
        insert.send(builder.build());
        insert.finish();
      }

      try (QueryResult result =
          connection.query(
              QueryRequest.of(
                  "SELECT count(), sum(n) FROM "
                      + table
                      + " WHERE s = concat('value-', toString(n))"))) {
        Block block = result.nextBlock().orElseThrow();
        assertThat(((Columns.UInt64Column) block.column(0)).rawLongAt(0)).isEqualTo(20_000);
        assertThat(((Columns.UInt64Column) block.column(1)).rawLongAt(0))
            .isEqualTo(19_999L * 20_000 / 2);
      }
    }
  }

  @Test
  void perQueryCompressionOverridesTheConnectionDefault() {
    try (NativeConnection connection = connect(null)) {
      // The connection default is uncompressed; this request opts into ZSTD.
      try (QueryResult result =
          connection.query(
              QueryRequest.builder("SELECT sum(number) AS s FROM numbers(100000)")
                  .compression(Compression.ZSTD)
                  .build())) {
        assertThat(((Columns.UInt64Column) result.nextBlock().orElseThrow().column(0)).rawLongAt(0))
            .isEqualTo(4999950000L);
      }
      // And the next request is uncompressed again.
      try (QueryResult result = connection.query(QueryRequest.of("SELECT 1"))) {
        assertThat(result.nextBlock().orElseThrow().column(0).objectAt(0)).isEqualTo(1);
      }
    }
  }

  @Test
  void compressedServerLogsAndProfileEventsStayInSync() {
    try (NativeConnection connection = connect(Compression.LZ4);
        QueryResult result =
            connection.query(
                QueryRequest.builder("SELECT count() FROM numbers(500000)")
                    .setting("send_logs_level", "trace")
                    .build())) {
      assertThat(((Columns.UInt64Column) result.nextBlock().orElseThrow().column(0)).rawLongAt(0))
          .isEqualTo(500_000);
      assertThat(result.nextBlock()).isEmpty();
      // The log and profile events packets travelled compressed (revision 54481) and were
      // consumed without desynchronising; profile events carry real counters.
      assertThat(result.profileEvents()).isNotEmpty();
      assertThat(result.profileEvents().keySet().toString()).contains("SelectedRows");
      assertThat(connection.state()).isEqualTo(ConnectionState.READY);
    }
    // The connection level check: a follow up exchange still works.
  }
}
