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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.orhaugh.chord.ChordServerException;
import io.github.orhaugh.chord.ChordTypeException;
import io.github.orhaugh.chord.codec.block.Block;
import io.github.orhaugh.chord.codec.column.BlockBuilder;
import io.github.orhaugh.chord.codec.column.Columns;
import io.github.orhaugh.chord.protocol.state.ConnectionState;
import io.github.orhaugh.chord.testkit.ClickHouseServerContainer;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.InetAddress;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Native INSERT against a real ClickHouse server. */
@Testcontainers
class InsertIT {

  @Container
  private static final ClickHouseServerContainer CLICKHOUSE = new ClickHouseServerContainer();

  private static final AtomicInteger TABLE_IDS = new AtomicInteger();

  private static NativeConnection connect() {
    return NativeConnection.open(
        ConnectionOptions.builder()
            .host(CLICKHOUSE.getHost())
            .port(CLICKHOUSE.nativePort())
            .database(CLICKHOUSE.database())
            .username(CLICKHOUSE.username())
            .password(CLICKHOUSE.password())
            .allowPlaintextPassword(true)
            .build());
  }

  private static String createTable(NativeConnection connection, String columnsClause) {
    String table = "insert_it_" + TABLE_IDS.incrementAndGet();
    execute(connection, "CREATE TABLE " + table + " (" + columnsClause + ") ENGINE = Memory");
    return table;
  }

  private static void execute(NativeConnection connection, String sql) {
    try (QueryResult result = connection.query(QueryRequest.of(sql))) {
      result.nextBlock();
    }
  }

  private static long count(NativeConnection connection, String table) {
    try (QueryResult result = connection.query(QueryRequest.of("SELECT count() FROM " + table))) {
      return ((Columns.UInt64Column) result.nextBlock().orElseThrow().column(0)).rawLongAt(0);
    }
  }

  @Test
  void roundTripsEveryWritableTypeThroughInsertAndSelect() throws Exception {
    try (NativeConnection connection = connect()) {
      String table =
          createTable(
              connection,
              """
              u8 UInt8, u64 UInt64, u256 UInt256, i8 Int8, i64 Int64, i128 Int128,
              f32 Float32, f64 Float64, b Bool, s String, fs FixedString(4),
              d Date, d32 Date32, dt DateTime('UTC'), dt64 DateTime64(3, 'UTC'),
              uuid UUID, ip4 IPv4, ip6 IPv6, e8 Enum8('red' = 1, 'blue' = -2),
              dec Decimal(18, 4), n Nullable(Int32), arr Array(UInt8),
              tup Tuple(id UInt8, name String), m Map(String, UInt16)
              """);

      UUID uuid = UUID.fromString("61f0c404-5cb3-11e7-907b-a6006ad3dba0");
      try (InsertStream insert =
          connection.insert(QueryRequest.of("INSERT INTO " + table + " VALUES"))) {
        assertThat(insert.schema().columnCount()).isEqualTo(24);
        assertThat(insert.schema().columnName(0)).isEqualTo("u8");

        BlockBuilder builder = insert.newBlock();
        builder.addRow(
            200,
            new BigInteger("18446744073709551615"),
            BigInteger.TWO.pow(200),
            (byte) -128,
            Long.MIN_VALUE,
            new BigInteger("-170141183460469231731687303715884105728"),
            1.5f,
            -0.25d,
            true,
            "text",
            "ab",
            LocalDate.of(2024, 1, 1),
            LocalDate.of(1969, 12, 31),
            Instant.parse("2024-01-01T12:00:00Z"),
            Instant.parse("2024-01-01T12:00:00.123Z"),
            uuid,
            InetAddress.getByName("1.2.3.4"),
            InetAddress.getByName("2001:db8::1"),
            "blue",
            new BigDecimal("-1.2345"),
            null,
            List.of(1, 2, 3),
            List.of(7, "x"),
            Map.of("k", 42));
        builder.addRow(
            0,
            0L,
            BigInteger.ZERO,
            (byte) 0,
            0L,
            BigInteger.ZERO,
            0f,
            0d,
            false,
            "",
            "",
            LocalDate.EPOCH,
            LocalDate.EPOCH,
            Instant.EPOCH,
            Instant.EPOCH,
            new UUID(0, 0),
            InetAddress.getByName("0.0.0.0"),
            InetAddress.getByName("::"),
            "red",
            BigDecimal.valueOf(0, 4),
            7,
            List.of(),
            List.of(0, ""),
            Map.of());
        insert.send(builder.build());
        InsertStream.InsertSummary summary = insert.finish();
        assertThat(summary.rowsSent()).isEqualTo(2);
        assertThat(summary.blocksSent()).isEqualTo(1);
        // Progress packets are interval throttled, so tiny inserts may report no written rows;
        // the SELECT below is the authoritative check that the rows landed.
      }
      assertThat(connection.state()).isEqualTo(ConnectionState.READY);

      try (QueryResult result =
          connection.query(QueryRequest.of("SELECT * FROM " + table + " ORDER BY u8 DESC"))) {
        Block block = result.nextBlock().orElseThrow();
        assertThat(block.rows()).isEqualTo(2);
        assertThat(((Columns.UInt8Column) block.columnByName("u8").orElseThrow()).intAt(0))
            .isEqualTo(200);
        assertThat(((Columns.UInt64Column) block.columnByName("u64").orElseThrow()).bigIntegerAt(0))
            .isEqualTo(new BigInteger("18446744073709551615"));
        assertThat(
                ((Columns.BigIntegerColumn) block.columnByName("u256").orElseThrow())
                    .bigIntegerAt(0))
            .isEqualTo(BigInteger.TWO.pow(200));
        assertThat(((Columns.StringColumn) block.columnByName("s").orElseThrow()).stringAt(0))
            .isEqualTo("text");
        assertThat(((Columns.FixedStringColumn) block.columnByName("fs").orElseThrow()).stringAt(0))
            .isEqualTo("ab");
        assertThat(
                ((Columns.DateTime64Column) block.columnByName("dt64").orElseThrow()).instantAt(0))
            .isEqualTo(Instant.parse("2024-01-01T12:00:00.123Z"));
        assertThat(((Columns.UuidColumn) block.columnByName("uuid").orElseThrow()).uuidAt(0))
            .isEqualTo(uuid);
        assertThat(((Columns.EnumColumn) block.columnByName("e8").orElseThrow()).labelAt(0))
            .isEqualTo("blue");
        assertThat(
                ((Columns.DecimalColumn) block.columnByName("dec").orElseThrow()).bigDecimalAt(0))
            .isEqualByComparingTo(new BigDecimal("-1.2345"));
        assertThat(block.columnByName("n").orElseThrow().isNullAt(0)).isTrue();
        assertThat(block.columnByName("n").orElseThrow().objectAt(1)).isEqualTo(7);
        assertThat(((Columns.ArrayColumn) block.columnByName("arr").orElseThrow()).listAt(0))
            .containsExactly(1, 2, 3);
        assertThat(((Columns.TupleColumn) block.columnByName("tup").orElseThrow()).tupleAt(0))
            .containsExactly(7, "x");
        assertThat(((Columns.MapColumn) block.columnByName("m").orElseThrow()).mapAt(0))
            .containsExactly(Map.entry("k", 42));
      }
    }
  }

  @Test
  void streamsMultipleBlocksAndCountsMatch() {
    try (NativeConnection connection = connect()) {
      String table = createTable(connection, "n UInt64, s String");

      try (InsertStream insert =
          connection.insert(QueryRequest.of("INSERT INTO " + table + " VALUES"))) {
        long expected = 0;
        for (int blockIndex = 0; blockIndex < 5; blockIndex++) {
          BlockBuilder builder = insert.newBlock();
          for (int row = 0; row < 10_000; row++) {
            builder.addRow(expected, "row-" + expected);
            expected++;
          }
          insert.send(builder.build());
        }
        InsertStream.InsertSummary summary = insert.finish();
        assertThat(summary.rowsSent()).isEqualTo(50_000);
        assertThat(summary.blocksSent()).isEqualTo(5);
      }

      assertThat(count(connection, table)).isEqualTo(50_000);

      // Verify content integrity, not just the count.
      try (QueryResult result =
          connection.query(
              QueryRequest.of(
                  "SELECT count() FROM " + table + " WHERE s = concat('row-', toString(n))"))) {
        assertThat(((Columns.UInt64Column) result.nextBlock().orElseThrow().column(0)).rawLongAt(0))
            .isEqualTo(50_000);
      }
    }
  }

  @Test
  void insertIntoUnknownTableFailsTypedAndTheConnectionRemainsUsable() {
    try (NativeConnection connection = connect()) {
      assertThatThrownBy(
              () -> connection.insert(QueryRequest.of("INSERT INTO no_such_table VALUES")))
          .isInstanceOf(ChordServerException.class)
          .satisfies(e -> assertThat(((ChordServerException) e).code()).isEqualTo(60));

      connection.ping();
      assertThat(connection.state()).isEqualTo(ConnectionState.READY);
    }
  }

  @Test
  void constraintViolationSurfacesAtFinishAndTheConnectionRemainsUsable() {
    try (NativeConnection connection = connect()) {
      String table = "constrained_" + TABLE_IDS.incrementAndGet();
      execute(
          connection,
          "CREATE TABLE "
              + table
              + " (n UInt64, CONSTRAINT positive CHECK n < 100) ENGINE = Memory");

      assertThatThrownBy(
              () -> {
                try (InsertStream insert =
                    connection.insert(QueryRequest.of("INSERT INTO " + table + " VALUES"))) {
                  BlockBuilder builder = insert.newBlock();
                  builder.addRow(500L);
                  insert.send(builder.build());
                  insert.finish();
                }
              })
          .isInstanceOf(ChordServerException.class)
          .satisfies(
              e ->
                  assertThat(((ChordServerException) e).code())
                      .isEqualTo(469)); // VIOLATED_CONSTRAINT

      connection.ping();
      assertThat(count(connection, table)).isZero();
    }
  }

  @Test
  void abandoningAnInsertClosesTheConnectionAndCommitsNothing() {
    String table;
    try (NativeConnection setup = connect()) {
      table = createTable(setup, "n UInt64");
    }

    NativeConnection connection = connect();
    InsertStream insert = connection.insert(QueryRequest.of("INSERT INTO " + table + " VALUES"));
    BlockBuilder builder = insert.newBlock();
    builder.addRow(1L);
    insert.send(builder.build());
    // No finish: closing must hard abort rather than commit implicitly.
    insert.close();
    assertThat(connection.state()).isEqualTo(ConnectionState.CLOSED);

    try (NativeConnection verifier = connect()) {
      assertThat(count(verifier, table)).isZero();
    }
  }

  @Test
  void blocksNotMatchingTheSchemaAreRejectedBeforeAnyBytes() {
    try (NativeConnection connection = connect()) {
      String table = createTable(connection, "n UInt64, s String");
      String other = createTable(connection, "x Int8");
      // Probe the other table's schema on its own connection: one exchange per connection.
      Block otherSchema;
      try (NativeConnection prober = connect()) {
        otherSchema = schemaOf(prober, other);
      }

      try (InsertStream insert =
          connection.insert(QueryRequest.of("INSERT INTO " + table + " VALUES"))) {
        BlockBuilder wrongShape = BlockBuilder.forSchema(otherSchema);
        wrongShape.addRow((byte) 1);
        Block block = wrongShape.build();
        assertThatThrownBy(() -> insert.send(block))
            .isInstanceOf(ChordTypeException.class)
            .hasMessageContaining("columns");
        // The stream is still intact: finish with zero rows.
        InsertStream.InsertSummary summary = insert.finish();
        assertThat(summary.rowsSent()).isZero();
      }
      assertThat(count(connection, table)).isZero();
    }
  }

  private static Block schemaOf(NativeConnection connection, String table) {
    try (InsertStream probe =
        connection.insert(QueryRequest.of("INSERT INTO " + table + " VALUES"))) {
      Block schema = probe.schema();
      probe.finish();
      return schema;
    }
  }

  @Test
  void defaultsMetadataArrivesThroughTableColumns() {
    try (NativeConnection connection = connect()) {
      String table = "defaults_" + TABLE_IDS.incrementAndGet();
      execute(
          connection,
          "CREATE TABLE " + table + " (n UInt64, twice UInt64 DEFAULT n * 2) ENGINE = Memory");

      try (InsertStream insert =
          connection.insert(QueryRequest.of("INSERT INTO " + table + " (n) VALUES"))) {
        // input_format_defaults_for_omitted_fields defaults to on, so the server sends the
        // TableColumns packet carrying default expressions before the schema block.
        assertThat(insert.tableColumnsDescription()).isPresent();
        assertThat(insert.tableColumnsDescription().orElseThrow()).contains("twice");
        assertThat(insert.schema().columnCount()).isEqualTo(1);

        BlockBuilder builder = insert.newBlock();
        builder.addRow(21L);
        insert.send(builder.build());
        insert.finish();
      }

      try (QueryResult result = connection.query(QueryRequest.of("SELECT twice FROM " + table))) {
        assertThat(((Columns.UInt64Column) result.nextBlock().orElseThrow().column(0)).rawLongAt(0))
            .isEqualTo(42);
      }
    }
  }

  @Test
  void asyncInsertSettingsKeepTheStreamInSync() {
    try (NativeConnection connection = connect()) {
      String table = createTable(connection, "n UInt64");

      try (InsertStream insert =
          connection.insert(
              QueryRequest.builder("INSERT INTO " + table + " VALUES")
                  .setting("async_insert", 1)
                  .setting("wait_for_async_insert", 1)
                  .build())) {
        BlockBuilder builder = insert.newBlock();
        builder.addRow(1L);
        builder.addRow(2L);
        insert.send(builder.build());
        insert.finish();
      }
      assertThat(connection.state()).isEqualTo(ConnectionState.READY);
      assertThat(count(connection, table)).isEqualTo(2);
    }
  }

  @Test
  void selectedBlocksCanBeForwardedIntoAnInsert() {
    try (NativeConnection reader = connect();
        NativeConnection writer = connect()) {
      String table = createTable(writer, "n UInt64");

      try (QueryResult source =
              reader.query(QueryRequest.of("SELECT number AS n FROM numbers(1000)"));
          InsertStream sink = writer.insert(QueryRequest.of("INSERT INTO " + table + " VALUES"))) {
        Optional<Block> next;
        while ((next = source.nextBlock()).isPresent()) {
          sink.send(next.orElseThrow());
        }
        sink.finish();
      }
      assertThat(count(writer, table)).isEqualTo(1000);
    }
  }
}
