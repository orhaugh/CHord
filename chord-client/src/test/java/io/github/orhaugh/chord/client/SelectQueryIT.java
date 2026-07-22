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
import io.github.orhaugh.chord.codec.block.Block;
import io.github.orhaugh.chord.codec.column.Columns;
import io.github.orhaugh.chord.protocol.state.ConnectionState;
import io.github.orhaugh.chord.testkit.ClickHouseServerContainer;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Streaming SELECT against a real ClickHouse server. */
@Testcontainers
class SelectQueryIT {

  @Container
  private static final ClickHouseServerContainer CLICKHOUSE = new ClickHouseServerContainer();

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

  @Test
  void selectsEveryPhaseTwoTypeWithCorrectValues() {
    String sql =
        """
        SELECT
          toUInt8(200) AS u8,
          toUInt64(18446744073709551615) AS u64,
          toUInt256('115792089237316195423570985008687907853269984665640564039457584007913129639935') AS u256,
          toInt8(-128) AS i8,
          toInt64(-9223372036854775808) AS i64,
          toInt128('-170141183460469231731687303715884105728') AS i128,
          toFloat32(1.5) AS f32,
          toFloat64(-0.25) AS f64,
          true AS b,
          'text' AS s,
          toFixedString('ab', 4) AS fs,
          toDate('2024-01-01') AS d,
          toDate32('1969-12-31') AS d32,
          toDateTime('2024-01-01 12:00:00', 'UTC') AS dt,
          toDateTime64('2024-01-01 12:00:00.123', 3, 'UTC') AS dt64,
          toUUID('61f0c404-5cb3-11e7-907b-a6006ad3dba0') AS uuid,
          toIPv4('1.2.3.4') AS ip4,
          toIPv6('::1') AS ip6,
          CAST('red', 'Enum8(\\'red\\' = 1, \\'blue\\' = 2)') AS e8,
          toDecimal64(-1.2345, 4) AS dec,
          toDecimal128(1.23, 2) AS dec128,
          [1, 2, 3] AS arr,
          [[1], [2, 3]] AS arr2,
          (1, 'x') AS tup,
          map('k1', 1, 'k2', 2) AS m,
          CAST(NULL, 'Nullable(Int32)') AS n,
          CAST(7, 'Nullable(Int32)') AS nv
        """;

    try (NativeConnection connection = connect();
        QueryResult result = connection.query(QueryRequest.of(sql))) {

      assertThat(result.header()).isPresent();
      Block header = result.header().orElseThrow();
      assertThat(header.rows()).isZero();
      assertThat(header.columnCount()).isEqualTo(27);
      assertThat(header.columnName(0)).isEqualTo("u8");

      Block block = result.nextBlock().orElseThrow();
      assertThat(block.rows()).isEqualTo(1);

      assertThat(((Columns.UInt8Column) block.columnByName("u8").orElseThrow()).intAt(0))
          .isEqualTo(200);
      assertThat(((Columns.UInt64Column) block.columnByName("u64").orElseThrow()).bigIntegerAt(0))
          .isEqualTo(new BigInteger("18446744073709551615"));
      assertThat(
              ((Columns.BigIntegerColumn) block.columnByName("u256").orElseThrow()).bigIntegerAt(0))
          .isEqualTo(BigInteger.TWO.pow(256).subtract(BigInteger.ONE));
      assertThat(((Columns.Int8Column) block.columnByName("i8").orElseThrow()).byteAt(0))
          .isEqualTo((byte) -128);
      assertThat(((Columns.Int64Column) block.columnByName("i64").orElseThrow()).longAt(0))
          .isEqualTo(Long.MIN_VALUE);
      assertThat(
              ((Columns.BigIntegerColumn) block.columnByName("i128").orElseThrow()).bigIntegerAt(0))
          .isEqualTo(new BigInteger("-170141183460469231731687303715884105728"));
      assertThat(((Columns.Float32Column) block.columnByName("f32").orElseThrow()).floatAt(0))
          .isEqualTo(1.5f);
      assertThat(((Columns.Float64Column) block.columnByName("f64").orElseThrow()).doubleAt(0))
          .isEqualTo(-0.25);
      assertThat(((Columns.BoolColumn) block.columnByName("b").orElseThrow()).booleanAt(0))
          .isTrue();
      assertThat(((Columns.StringColumn) block.columnByName("s").orElseThrow()).stringAt(0))
          .isEqualTo("text");
      assertThat(((Columns.FixedStringColumn) block.columnByName("fs").orElseThrow()).stringAt(0))
          .isEqualTo("ab");
      assertThat(((Columns.DateColumn) block.columnByName("d").orElseThrow()).localDateAt(0))
          .isEqualTo(LocalDate.of(2024, 1, 1));
      assertThat(((Columns.Date32Column) block.columnByName("d32").orElseThrow()).localDateAt(0))
          .isEqualTo(LocalDate.of(1969, 12, 31));
      assertThat(((Columns.DateTimeColumn) block.columnByName("dt").orElseThrow()).instantAt(0))
          .isEqualTo(Instant.parse("2024-01-01T12:00:00Z"));
      Columns.DateTime64Column dt64 =
          (Columns.DateTime64Column) block.columnByName("dt64").orElseThrow();
      assertThat(dt64.instantAt(0)).isEqualTo(Instant.parse("2024-01-01T12:00:00.123Z"));
      assertThat(((Columns.UuidColumn) block.columnByName("uuid").orElseThrow()).uuidAt(0))
          .isEqualTo(UUID.fromString("61f0c404-5cb3-11e7-907b-a6006ad3dba0"));
      assertThat(
              ((Columns.Ipv4Column) block.columnByName("ip4").orElseThrow())
                  .inetAddressAt(0)
                  .getHostAddress())
          .isEqualTo("1.2.3.4");
      assertThat(
              ((Columns.Ipv6Column) block.columnByName("ip6").orElseThrow())
                  .inetAddressAt(0)
                  .getHostAddress())
          .contains(":");
      assertThat(((Columns.EnumColumn) block.columnByName("e8").orElseThrow()).labelAt(0))
          .isEqualTo("red");
      assertThat(((Columns.DecimalColumn) block.columnByName("dec").orElseThrow()).bigDecimalAt(0))
          .isEqualByComparingTo(new BigDecimal("-1.2345"));
      assertThat(
              ((Columns.DecimalColumn) block.columnByName("dec128").orElseThrow()).bigDecimalAt(0))
          .isEqualByComparingTo(new BigDecimal("1.23"));
      assertThat(((Columns.ArrayColumn) block.columnByName("arr").orElseThrow()).listAt(0))
          .containsExactly(1, 2, 3);
      Columns.ArrayColumn nested = (Columns.ArrayColumn) block.columnByName("arr2").orElseThrow();
      assertThat(nested.listAt(0)).hasSize(2);
      assertThat(((Columns.TupleColumn) block.columnByName("tup").orElseThrow()).tupleAt(0))
          .containsExactly(1, "x");
      assertThat(((Columns.MapColumn) block.columnByName("m").orElseThrow()).mapAt(0))
          .containsExactly(Map.entry("k1", 1), Map.entry("k2", 2));
      Columns.NullableColumn nullColumn =
          (Columns.NullableColumn) block.columnByName("n").orElseThrow();
      assertThat(nullColumn.isNullAt(0)).isTrue();
      assertThat(nullColumn.objectAt(0)).isNull();
      assertThat(block.columnByName("nv").orElseThrow().objectAt(0)).isEqualTo(7);

      assertThat(result.nextBlock()).isEmpty();
      assertThat(connection.state()).isEqualTo(ConnectionState.READY);
    }
  }

  @Test
  void streamsLargeResultsAcrossMultipleBlocksWithBoundedMemory() {
    try (NativeConnection connection = connect();
        QueryResult result =
            connection.query(
                QueryRequest.builder("SELECT number FROM system.numbers LIMIT 1000000")
                    .setting("max_block_size", 65536)
                    .build())) {

      long expected = 0;
      long rows = 0;
      int blocks = 0;
      Optional<Block> next;
      while ((next = result.nextBlock()).isPresent()) {
        Block block = next.orElseThrow();
        Columns.UInt64Column column = (Columns.UInt64Column) block.column(0);
        for (int i = 0; i < block.rows(); i++) {
          if (column.rawLongAt(i) != expected) {
            throw new AssertionError(
                "row " + rows + " expected " + expected + " got " + column.rawLongAt(i));
          }
          expected++;
          rows++;
        }
        blocks++;
      }
      assertThat(rows).isEqualTo(1_000_000);
      assertThat(blocks).isGreaterThan(1);
      assertThat(result.totalProgress().readRows()).isGreaterThanOrEqualTo(1_000_000);
      assertThat(result.profileInfo()).isPresent();
      assertThat(connection.state()).isEqualTo(ConnectionState.READY);
    }
  }

  @Test
  void serverParametersSubstituteTypedPlaceholders() {
    try (NativeConnection connection = connect();
        QueryResult result =
            connection.query(
                QueryRequest.builder(
                        "SELECT {v:UInt64} + 1 AS plus, {s:String} AS echo, {d:Date} AS d")
                    .parameter("v", 41)
                    .parameter("s", "it's quoted \\ safely")
                    .parameter("d", "2024-06-01")
                    .build())) {

      Block block = result.nextBlock().orElseThrow();
      assertThat(((Columns.UInt64Column) block.columnByName("plus").orElseThrow()).rawLongAt(0))
          .isEqualTo(42);
      assertThat(((Columns.StringColumn) block.columnByName("echo").orElseThrow()).stringAt(0))
          .isEqualTo("it's quoted \\ safely");
      assertThat(((Columns.DateColumn) block.columnByName("d").orElseThrow()).localDateAt(0))
          .isEqualTo(LocalDate.of(2024, 6, 1));
    }
  }

  @Test
  void totalsAndExtremesArriveAlongsideData() {
    try (NativeConnection connection = connect();
        QueryResult result =
            connection.query(
                QueryRequest.builder(
                        "SELECT number % 3 AS g, sum(number) AS s FROM numbers(100) GROUP BY g"
                            + " WITH TOTALS ORDER BY g")
                    .setting("extremes", 1)
                    .build())) {

      long groups = 0;
      Optional<Block> next;
      while ((next = result.nextBlock()).isPresent()) {
        groups += next.orElseThrow().rows();
      }
      assertThat(groups).isEqualTo(3);
      assertThat(result.totals()).isPresent();
      assertThat(result.totals().orElseThrow().rows()).isEqualTo(1);
      assertThat(
              ((Columns.UInt64Column) result.totals().orElseThrow().columnByName("s").orElseThrow())
                  .rawLongAt(0))
          .isEqualTo(4950);
      assertThat(result.extremes()).isPresent();
      assertThat(result.extremes().orElseThrow().rows()).isEqualTo(2);
    }
  }

  @Test
  void serverErrorsSurfaceTypedAndTheConnectionRemainsUsable() {
    try (NativeConnection connection = connect()) {
      assertThatThrownBy(
              () -> {
                try (QueryResult result =
                    connection.query(QueryRequest.of("SELECT * FROM no_such_table_anywhere"))) {
                  result.nextBlock();
                }
              })
          .isInstanceOf(ChordServerException.class)
          .satisfies(
              e -> assertThat(((ChordServerException) e).code()).isEqualTo(60)); // UNKNOWN_TABLE

      // The exception concluded the stream; the same connection must work again.
      try (QueryResult result = connection.query(QueryRequest.of("SELECT 1 AS one"))) {
        assertThat(result.nextBlock().orElseThrow().column(0).objectAt(0)).isEqualTo(1);
      }
      connection.ping();
      assertThat(connection.state()).isEqualTo(ConnectionState.READY);
    }
  }

  @Test
  void midStreamErrorsAlsoLeaveTheConnectionUsable() {
    try (NativeConnection connection = connect()) {
      assertThatThrownBy(
              () -> {
                try (QueryResult result =
                    connection.query(
                        QueryRequest.of(
                            "SELECT number, throwIf(number = 200000, 'boom') FROM"
                                + " system.numbers LIMIT 1000000"))) {
                  while (result.nextBlock().isPresent()) {
                    // Consume until the failure lands.
                  }
                }
              })
          .isInstanceOf(ChordServerException.class);

      connection.ping();
      try (QueryResult result = connection.query(QueryRequest.of("SELECT 2 AS two"))) {
        assertThat(result.nextBlock().orElseThrow().column(0).objectAt(0)).isEqualTo(2);
      }
    }
  }

  @Test
  void abandonedResultsAreDrainedOnCloseAndTheConnectionStaysUsable() {
    try (NativeConnection connection = connect()) {
      try (QueryResult result =
          connection.query(QueryRequest.of("SELECT number FROM system.numbers LIMIT 500000"))) {
        // Read one block, then abandon the rest; close() must drain.
        assertThat(result.nextBlock()).isPresent();
      }
      assertThat(connection.state()).isEqualTo(ConnectionState.READY);
      try (QueryResult result = connection.query(QueryRequest.of("SELECT 3 AS three"))) {
        assertThat(result.nextBlock().orElseThrow().column(0).objectAt(0)).isEqualTo(3);
      }
    }
  }

  @Test
  void settingsApplyPerQuery() {
    try (NativeConnection connection = connect()) {
      assertThatThrownBy(
              () -> {
                try (QueryResult result =
                    connection.query(
                        QueryRequest.builder("SELECT count() FROM system.numbers LIMIT 1000000")
                            .setting("max_rows_to_read", 1000)
                            .build())) {
                  result.nextBlock();
                }
              })
          .isInstanceOf(ChordServerException.class)
          .satisfies(
              e -> assertThat(((ChordServerException) e).code()).isEqualTo(158)); // TOO_MANY_ROWS
    }
  }

  @Test
  void lowCardinalityColumnsDecodeTransparently() {
    try (NativeConnection connection = connect()) {
      try (QueryResult result =
          connection.query(QueryRequest.of("SELECT toLowCardinality('x') AS lc"))) {
        assertThat(result.nextBlock().orElseThrow().column(0).objectAt(0)).isEqualTo("x");
      }
      assertThat(connection.state()).isEqualTo(ConnectionState.READY);
    }
  }

  @Test
  void zeroRowResultsExposeSchemaAndConcludeCleanly() {
    try (NativeConnection connection = connect();
        QueryResult result =
            connection.query(
                QueryRequest.of("SELECT number, toString(number) AS s FROM numbers(10) WHERE 0"))) {
      Block header = result.header().orElseThrow();
      assertThat(header.columnCount()).isEqualTo(2);
      assertThat(header.columnType(0).name()).isEqualTo("UInt64");
      assertThat(header.columnType(1).name()).isEqualTo("String");
      assertThat(result.nextBlock()).isEmpty();
      assertThat(connection.state()).isEqualTo(ConnectionState.READY);
    }
  }

  private static String tabSeparated(double value) {
    // clickhouse-client TabSeparated prints integral doubles without a decimal part.
    return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
  }

  @Test
  void rowValuesMatchTheOfficialClientOutput() throws Exception {
    String sql = "SELECT number, toString(number) AS s, number / 2.0 AS half FROM numbers(5)";

    StringBuilder chordRendering = new StringBuilder();
    try (NativeConnection connection = connect();
        QueryResult result = connection.query(QueryRequest.of(sql))) {
      Optional<Block> next;
      while ((next = result.nextBlock()).isPresent()) {
        Block block = next.orElseThrow();
        Columns.UInt64Column numbers = (Columns.UInt64Column) block.column(0);
        Columns.StringColumn strings = (Columns.StringColumn) block.column(1);
        Columns.Float64Column halves = (Columns.Float64Column) block.column(2);
        for (int i = 0; i < block.rows(); i++) {
          chordRendering
              .append(numbers.rawLongAt(i))
              .append('\t')
              .append(strings.stringAt(i))
              .append('\t')
              .append(tabSeparated(halves.doubleAt(i)))
              .append('\n');
        }
      }
    }

    var executed =
        CLICKHOUSE.execInContainer(
            "clickhouse-client",
            "--user",
            CLICKHOUSE.username(),
            "--password",
            CLICKHOUSE.password(),
            "--format",
            "TabSeparated",
            "--query",
            sql);
    assertThat(executed.getExitCode()).isZero();
    assertThat(chordRendering.toString()).isEqualTo(executed.getStdout());
  }
}
