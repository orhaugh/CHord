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
import io.github.orhaugh.chord.codec.column.Column;
import io.github.orhaugh.chord.testkit.ClickHouseServerContainer;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Edge values against a real server: float special values, DateTime64 at its precision extremes and
 * the boundary values of every integer, date, decimal and enum width, each written through a native
 * INSERT and read back so both directions face the real implementation.
 */
@Testcontainers
@Timeout(180)
class TypeEdgeIT {

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

  private static void execute(NativeConnection connection, String sql) {
    try (QueryResult result = connection.query(QueryRequest.of(sql))) {
      result.nextBlock();
    }
  }

  @Test
  void floatSpecialValuesRoundTripAndArriveFromServerExpressions() {
    try (NativeConnection connection = connect()) {
      execute(
          connection,
          "CREATE TABLE float_edge (id UInt8, f32 Float32, f64 Float64) ENGINE = Memory");
      try (InsertStream insert =
          connection.insert(QueryRequest.of("INSERT INTO float_edge VALUES"))) {
        BlockBuilder builder = insert.newBlock();
        builder.addRow(0, Float.NaN, Double.NaN);
        builder.addRow(1, Float.POSITIVE_INFINITY, Double.POSITIVE_INFINITY);
        builder.addRow(2, Float.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY);
        builder.addRow(3, -0.0f, -0.0d);
        insert.send(builder.build());
        insert.finish();
      }
      try (QueryResult result =
          connection.query(QueryRequest.of("SELECT f32, f64 FROM float_edge ORDER BY id"))) {
        Block block = result.nextBlock().orElseThrow();
        assertThat(((Float) block.column(0).objectAt(0)).isNaN()).isTrue();
        assertThat(((Double) block.column(1).objectAt(0)).isNaN()).isTrue();
        assertThat(block.column(0).objectAt(1)).isEqualTo(Float.POSITIVE_INFINITY);
        assertThat(block.column(1).objectAt(1)).isEqualTo(Double.POSITIVE_INFINITY);
        assertThat(block.column(0).objectAt(2)).isEqualTo(Float.NEGATIVE_INFINITY);
        assertThat(block.column(1).objectAt(2)).isEqualTo(Double.NEGATIVE_INFINITY);
        assertThat(Float.floatToRawIntBits((Float) block.column(0).objectAt(3)))
            .isEqualTo(Float.floatToRawIntBits(-0.0f));
        assertThat(Double.doubleToRawLongBits((Double) block.column(1).objectAt(3)))
            .isEqualTo(Double.doubleToRawLongBits(-0.0d));
      }
      // Server generated specials decode the same way.
      try (QueryResult result = connection.query(QueryRequest.of("SELECT nan, inf, -inf"))) {
        Block block = result.nextBlock().orElseThrow();
        assertThat(((Double) block.column(0).objectAt(0)).isNaN()).isTrue();
        assertThat(block.column(1).objectAt(0)).isEqualTo(Double.POSITIVE_INFINITY);
        assertThat(block.column(2).objectAt(0)).isEqualTo(Double.NEGATIVE_INFINITY);
      }
    }
  }

  @Test
  void dateTime64PrecisionExtremesRoundTrip() {
    try (NativeConnection connection = connect()) {
      execute(
          connection,
          "CREATE TABLE dt64_edge (id UInt8, t0 DateTime64(0, 'UTC'), t6 DateTime64(6, 'UTC'),"
              + " t9 DateTime64(9, 'UTC')) ENGINE = Memory");
      Instant p0Low = Instant.parse("1900-01-01T00:00:00Z");
      Instant p0High = Instant.parse("2299-12-31T23:59:59Z");
      Instant p6Low = Instant.parse("1900-01-01T00:00:00.000001Z");
      Instant p6High = Instant.parse("2262-01-01T00:00:00.999999Z");
      Instant p9Low = Instant.parse("1969-12-31T23:59:59.999999999Z");
      Instant p9High = Instant.parse("2200-01-01T00:00:00.123456789Z");
      try (InsertStream insert =
          connection.insert(QueryRequest.of("INSERT INTO dt64_edge VALUES"))) {
        BlockBuilder builder = insert.newBlock();
        builder.addRow(0, p0Low, p6Low, p9Low);
        builder.addRow(1, p0High, p6High, p9High);
        insert.send(builder.build());
        insert.finish();
      }
      try (QueryResult result =
          connection.query(QueryRequest.of("SELECT t0, t6, t9 FROM dt64_edge ORDER BY id"))) {
        Block block = result.nextBlock().orElseThrow();
        assertThat(block.column(0).objectAt(0)).isEqualTo(p0Low);
        assertThat(block.column(1).objectAt(0)).isEqualTo(p6Low);
        assertThat(block.column(2).objectAt(0)).isEqualTo(p9Low);
        assertThat(block.column(0).objectAt(1)).isEqualTo(p0High);
        assertThat(block.column(1).objectAt(1)).isEqualTo(p6High);
        assertThat(block.column(2).objectAt(1)).isEqualTo(p9High);
      }
    }
  }

  @Test
  void integerDateDecimalAndEnumBoundariesRoundTrip() {
    try (NativeConnection connection = connect()) {
      execute(
          connection,
          "CREATE TABLE width_edge (id UInt8, u16 UInt16, i16 Int16, u32 UInt32, i32 Int32,"
              + " u128 UInt128, i256 Int256, d32 Decimal32(4),"
              + " e16 Enum16('lo' = -32768, 'hi' = 32767), d Date, d32d Date32,"
              + " dt DateTime('UTC')) ENGINE = Memory");
      BigInteger u128Max = BigInteger.TWO.pow(128).subtract(BigInteger.ONE);
      BigInteger i256Min = BigInteger.TWO.pow(255).negate();
      try (InsertStream insert =
          connection.insert(QueryRequest.of("INSERT INTO width_edge VALUES"))) {
        BlockBuilder builder = insert.newBlock();
        builder.addRow(
            0,
            0,
            -32_768,
            0L,
            Integer.MIN_VALUE,
            BigInteger.ZERO,
            i256Min,
            new BigDecimal("-99999.9999"),
            "lo",
            LocalDate.ofEpochDay(0),
            LocalDate.of(1900, 1, 1),
            Instant.EPOCH);
        builder.addRow(
            1,
            65_535,
            32_767,
            4_294_967_295L,
            Integer.MAX_VALUE,
            u128Max,
            BigInteger.TWO.pow(255).subtract(BigInteger.ONE),
            new BigDecimal("99999.9999"),
            "hi",
            LocalDate.ofEpochDay(65_535),
            LocalDate.of(2299, 12, 31),
            Instant.ofEpochSecond(4_294_967_295L));
        insert.send(builder.build());
        insert.finish();
      }
      try (QueryResult result =
          connection.query(
              QueryRequest.of(
                  "SELECT u16, i16, u32, i32, u128, i256, d32, e16, d, d32d, dt"
                      + " FROM width_edge ORDER BY id"))) {
        Block block = result.nextBlock().orElseThrow();
        Column u16 = block.column(0);
        assertThat(u16.objectAt(0)).isEqualTo(0);
        assertThat(u16.objectAt(1)).isEqualTo(65_535);
        assertThat(block.column(1).objectAt(0)).isEqualTo((short) -32_768);
        assertThat(block.column(1).objectAt(1)).isEqualTo((short) 32_767);
        assertThat(block.column(2).objectAt(1)).isEqualTo(4_294_967_295L);
        assertThat(block.column(3).objectAt(0)).isEqualTo(Integer.MIN_VALUE);
        assertThat(block.column(4).objectAt(1)).isEqualTo(u128Max);
        assertThat(block.column(5).objectAt(0)).isEqualTo(i256Min);
        assertThat((BigDecimal) block.column(6).objectAt(0)).isEqualByComparingTo("-99999.9999");
        assertThat((BigDecimal) block.column(6).objectAt(1)).isEqualByComparingTo("99999.9999");
        assertThat(block.column(7).objectAt(0)).isEqualTo("lo");
        assertThat(block.column(7).objectAt(1)).isEqualTo("hi");
        assertThat(block.column(8).objectAt(1)).isEqualTo(LocalDate.of(2149, 6, 6));
        assertThat(block.column(9).objectAt(0)).isEqualTo(LocalDate.of(1900, 1, 1));
        assertThat(block.column(9).objectAt(1)).isEqualTo(LocalDate.of(2299, 12, 31));
        assertThat(block.column(10).objectAt(1)).isEqualTo(Instant.ofEpochSecond(4_294_967_295L));
      }
      // The same widths as server generated literals, so decode is proven independently of
      // our own encoder.
      try (QueryResult result =
          connection.query(
              QueryRequest.of(
                  "SELECT toUInt16(65535), toInt16(-32768), toUInt32(4294967295),"
                      + " toInt32(-2147483648),"
                      + " toUInt128('340282366920938463463374607431768211455'),"
                      + " toInt256('-578960446186580977117854925043439539266349923328202820197287"
                      + "92003956564819968'), toDecimal32('9999.9999', 4)"))) {
        Block block = result.nextBlock().orElseThrow();
        assertThat(block.column(0).objectAt(0)).isEqualTo(65_535);
        assertThat(block.column(1).objectAt(0)).isEqualTo((short) -32_768);
        assertThat(block.column(2).objectAt(0)).isEqualTo(4_294_967_295L);
        assertThat(block.column(3).objectAt(0)).isEqualTo(Integer.MIN_VALUE);
        assertThat(block.column(4).objectAt(0)).isEqualTo(u128Max);
        assertThat(block.column(5).objectAt(0)).isEqualTo(i256Min);
        assertThat((BigDecimal) block.column(6).objectAt(0)).isEqualByComparingTo("9999.9999");
      }
    }
  }
}
