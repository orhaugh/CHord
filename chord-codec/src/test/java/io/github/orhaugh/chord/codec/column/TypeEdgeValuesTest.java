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
package io.github.orhaugh.chord.codec.column;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.orhaugh.chord.ChordTypeException;
import io.github.orhaugh.chord.codec.block.Block;
import io.github.orhaugh.chord.codec.block.BlockLimits;
import io.github.orhaugh.chord.codec.block.BlockReader;
import io.github.orhaugh.chord.codec.block.BlockWriter;
import io.github.orhaugh.chord.codec.block.DecodeContext;
import io.github.orhaugh.chord.codec.type.TypeParser;
import io.github.orhaugh.chord.protocol.wire.WireLimits;
import io.github.orhaugh.chord.protocol.wire.WireReader;
import io.github.orhaugh.chord.protocol.wire.WireWriter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Edge values that production data actually contains: float special values, every DateTime64
 * precision, and the minimum and maximum of every integer, date and decimal width. Each case round
 * trips through encode and decode, so a bit pattern mishandled on either side fails here.
 */
class TypeEdgeValuesTest {

  private static final long REVISION = 54488;

  @org.junit.jupiter.api.Test
  void variantValuesRoundTripByInference() {
    // Alternatives are name sorted (Int64 before String); inference offers values in that
    // order and NULL takes the null discriminator.
    Column column = roundTripColumn("Variant(Int64, String)", 42L, "hello", null, -7L, "", 0L);
    assertThat(column.objectAt(0)).isEqualTo(42L);
    assertThat(column.objectAt(1)).isEqualTo("hello");
    assertThat(column.objectAt(2)).isNull();
    assertThat(column.objectAt(3)).isEqualTo(-7L);
    assertThat(column.objectAt(4)).isEqualTo("");
    assertThat(column.objectAt(5)).isEqualTo(0L);

    // A value no alternative accepts is refused with the variant type named.
    assertThatThrownBy(() -> roundTripColumn("Variant(Int64, String)", java.time.Instant.EPOCH))
        .hasMessageContaining("No variant of Variant(Int64, String)");

    // Nested shapes survive: arrays inside a variant.
    Column nested =
        roundTripColumn("Variant(Array(Int64), String)", java.util.List.of(1L, 2L), "x", null);
    assertThat(nested.objectAt(0)).isEqualTo(java.util.List.of(1L, 2L));
    assertThat(nested.objectAt(1)).isEqualTo("x");
    assertThat(nested.objectAt(2)).isNull();
  }

  @org.junit.jupiter.api.Test
  void dynamicValuesRoundTripByDiscovery() {
    java.util.UUID uuid = java.util.UUID.fromString("01234567-89ab-cdef-0123-456789abcdef");
    Column column =
        roundTripColumn(
            "Dynamic",
            42L,
            "hello",
            null,
            2.5d,
            true,
            Instant.parse("2026-01-01T00:00:00.123456789Z"),
            uuid,
            LocalDate.of(2026, 7, 23),
            -42L);
    assertThat(column.objectAt(0)).isEqualTo(42L);
    assertThat(column.objectAt(1)).isEqualTo("hello");
    assertThat(column.objectAt(2)).isNull();
    assertThat(column.objectAt(3)).isEqualTo(2.5d);
    assertThat(column.objectAt(4)).isEqualTo(true);
    assertThat(column.objectAt(5)).isEqualTo(Instant.parse("2026-01-01T00:00:00.123456789Z"));
    assertThat(column.objectAt(6)).isEqualTo(uuid);
    assertThat(column.objectAt(7)).isEqualTo(LocalDate.of(2026, 7, 23));
    assertThat(column.objectAt(8)).isEqualTo(-42L);

    assertThatThrownBy(() -> roundTripColumn("Dynamic", new Object()))
        .hasMessageContaining("Cannot infer a Dynamic type");
  }

  @org.junit.jupiter.api.Test
  void jsonValuesRoundTripAsPathMaps() {
    Column column =
        roundTripColumn(
            "JSON",
            java.util.Map.of("a", 42L, "b", java.util.Map.of("c", "nested")),
            java.util.Map.of("a", 7L),
            null,
            java.util.Map.of("b", java.util.Map.of("c", "other"), "d", 2.5d));
    @SuppressWarnings("unchecked")
    java.util.Map<String, Object> first = (java.util.Map<String, Object>) column.objectAt(0);
    assertThat(first).containsEntry("a", 42L).containsEntry("b.c", "nested");
    @SuppressWarnings("unchecked")
    java.util.Map<String, Object> second = (java.util.Map<String, Object>) column.objectAt(1);
    assertThat(second).containsEntry("a", 7L).doesNotContainKey("b.c").doesNotContainKey("d");
    @SuppressWarnings("unchecked")
    java.util.Map<String, Object> third = (java.util.Map<String, Object>) column.objectAt(2);
    assertThat(third).isEmpty();
    @SuppressWarnings("unchecked")
    java.util.Map<String, Object> fourth = (java.util.Map<String, Object>) column.objectAt(3);
    assertThat(fourth).containsEntry("b.c", "other").containsEntry("d", 2.5d);

    assertThatThrownBy(() -> roundTripColumn("JSON", "not a map"))
        .hasMessageContaining("Cannot convert String to JSON");
  }

  @org.junit.jupiter.api.Test
  void timeAndTime64RoundTripTheirExtremes() {
    java.time.Duration max = java.time.Duration.ofSeconds(999L * 3600 + 59 * 60 + 59);
    Column time = roundTripColumn("Time", max, max.negated(), java.time.Duration.ZERO);
    assertThat(time.objectAt(0)).isEqualTo(max);
    assertThat(time.objectAt(1)).isEqualTo(max.negated());
    assertThat(time.objectAt(2)).isEqualTo(java.time.Duration.ZERO);

    java.time.Duration subSecond = max.plusMillis(0).minusSeconds(0).plusNanos(999_000_000);
    Column time64 =
        roundTripColumn(
            "Time64(3)",
            max.plusMillis(999),
            max.negated().minusMillis(999),
            java.time.Duration.ofNanos(1_000_000));
    assertThat(time64.objectAt(0)).isEqualTo(max.plusMillis(999));
    assertThat(time64.objectAt(1)).isEqualTo(max.negated().minusMillis(999));
    assertThat(time64.objectAt(2)).isEqualTo(java.time.Duration.ofNanos(1_000_000));

    Column nanos = roundTripColumn("Time64(9)", java.time.Duration.ofNanos(123456789));
    assertThat(nanos.objectAt(0)).isEqualTo(java.time.Duration.ofNanos(123456789));

    // Sub tick and out of range values are refused, never silently truncated.
    assertThatThrownBy(() -> roundTripColumn("Time", java.time.Duration.ofNanos(1)))
        .hasMessageContaining("more precision");
    assertThatThrownBy(() -> roundTripColumn("Time64(3)", java.time.Duration.ofNanos(1)))
        .hasMessageContaining("more precision");
    assertThatThrownBy(() -> roundTripColumn("Time", max.plusSeconds(1)))
        .hasMessageContaining("outside the Time range");
    assertThatThrownBy(() -> roundTripColumn("Time64(3)", subSecond.plusSeconds(10)))
        .hasMessageContaining("outside the Time64(3) range");
  }

  private static Column roundTripColumn(String typeName, Object... values) {
    BlockBuilder builder =
        BlockBuilder.forColumnTypes(List.of(TypeParser.parse(typeName, 10_000, 32)));
    for (Object value : values) {
      builder.addRow(value);
    }
    ByteArrayOutputStream sink = new ByteArrayOutputStream();
    WireWriter out = new WireWriter(sink);
    BlockWriter.write(out, builder.build(), REVISION);
    out.flush();
    Block decoded =
        BlockReader.read(
            new WireReader(new ByteArrayInputStream(sink.toByteArray()), WireLimits.DEFAULTS),
            new DecodeContext(BlockLimits.DEFAULTS, REVISION, ZoneId.of("UTC")));
    return decoded.column(0);
  }

  @Test
  void float64SpecialValuesKeepTheirExactBitPatterns() {
    Column column =
        roundTripColumn(
            "Float64",
            Double.NaN,
            Double.POSITIVE_INFINITY,
            Double.NEGATIVE_INFINITY,
            -0.0d,
            Double.MAX_VALUE,
            Double.MIN_VALUE);
    assertThat(((Double) column.objectAt(0)).isNaN()).isTrue();
    assertThat(column.objectAt(1)).isEqualTo(Double.POSITIVE_INFINITY);
    assertThat(column.objectAt(2)).isEqualTo(Double.NEGATIVE_INFINITY);
    assertThat(Double.doubleToRawLongBits((Double) column.objectAt(3)))
        .isEqualTo(Double.doubleToRawLongBits(-0.0d));
    assertThat(column.objectAt(4)).isEqualTo(Double.MAX_VALUE);
    assertThat(column.objectAt(5)).isEqualTo(Double.MIN_VALUE);
  }

  @Test
  void float32SpecialValuesKeepTheirExactBitPatterns() {
    Column column =
        roundTripColumn(
            "Float32",
            Float.NaN,
            Float.POSITIVE_INFINITY,
            Float.NEGATIVE_INFINITY,
            -0.0f,
            Float.MAX_VALUE,
            Float.MIN_VALUE);
    assertThat(((Float) column.objectAt(0)).isNaN()).isTrue();
    assertThat(column.objectAt(1)).isEqualTo(Float.POSITIVE_INFINITY);
    assertThat(column.objectAt(2)).isEqualTo(Float.NEGATIVE_INFINITY);
    assertThat(Float.floatToRawIntBits((Float) column.objectAt(3)))
        .isEqualTo(Float.floatToRawIntBits(-0.0f));
    assertThat(column.objectAt(4)).isEqualTo(Float.MAX_VALUE);
    assertThat(column.objectAt(5)).isEqualTo(Float.MIN_VALUE);
  }

  @Test
  void bfloat16SpecialValuesSurviveTruncationToTheStoredBits() {
    Column column =
        roundTripColumn(
            "BFloat16", Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -0.0f, 1.5f);
    assertThat(((Float) column.objectAt(0)).isNaN()).isTrue();
    assertThat(column.objectAt(1)).isEqualTo(Float.POSITIVE_INFINITY);
    assertThat(column.objectAt(2)).isEqualTo(Float.NEGATIVE_INFINITY);
    assertThat(Float.floatToRawIntBits((Float) column.objectAt(3)))
        .isEqualTo(Float.floatToRawIntBits(-0.0f));
    assertThat(column.objectAt(4)).isEqualTo(1.5f);
  }

  @Test
  void dateTime64RoundTripsAtEveryPrecisionExtreme() {
    // Precision 0: whole seconds across the documented ClickHouse range.
    Column p0 =
        roundTripColumn(
            "DateTime64(0)",
            Instant.parse("1900-01-01T00:00:00Z"),
            Instant.parse("2299-12-31T23:59:59Z"),
            Instant.EPOCH);
    assertThat(p0.objectAt(0)).isEqualTo(Instant.parse("1900-01-01T00:00:00Z"));
    assertThat(p0.objectAt(1)).isEqualTo(Instant.parse("2299-12-31T23:59:59Z"));
    assertThat(p0.objectAt(2)).isEqualTo(Instant.EPOCH);

    // Precision 6: microseconds, including a pre epoch value.
    Column p6 =
        roundTripColumn(
            "DateTime64(6)",
            Instant.parse("1900-01-01T00:00:00.000001Z"),
            Instant.parse("2299-12-31T23:59:59.999999Z"),
            Instant.parse("1969-12-31T23:59:59.500000Z"));
    assertThat(p6.objectAt(0)).isEqualTo(Instant.parse("1900-01-01T00:00:00.000001Z"));
    assertThat(p6.objectAt(1)).isEqualTo(Instant.parse("2299-12-31T23:59:59.999999Z"));
    assertThat(p6.objectAt(2)).isEqualTo(Instant.parse("1969-12-31T23:59:59.500000Z"));

    // Precision 9: nanoseconds, where the Int64 tick range is narrowest.
    Column p9 =
        roundTripColumn(
            "DateTime64(9)",
            Instant.parse("1970-01-01T00:00:00.000000001Z"),
            Instant.parse("1969-12-31T23:59:59.999999999Z"),
            Instant.parse("2200-01-01T00:00:00.123456789Z"));
    assertThat(p9.objectAt(0)).isEqualTo(Instant.parse("1970-01-01T00:00:00.000000001Z"));
    assertThat(p9.objectAt(1)).isEqualTo(Instant.parse("1969-12-31T23:59:59.999999999Z"));
    assertThat(p9.objectAt(2)).isEqualTo(Instant.parse("2200-01-01T00:00:00.123456789Z"));
  }

  @Test
  void dateTime64RejectsSubTickPrecisionAtEveryPrecision() {
    assertThatThrownBy(() -> roundTripColumn("DateTime64(0)", Instant.ofEpochSecond(1, 1)))
        .isInstanceOf(ChordTypeException.class)
        .hasMessageContaining("more precision");
    assertThatThrownBy(() -> roundTripColumn("DateTime64(6)", Instant.ofEpochSecond(1, 1_001)))
        .isInstanceOf(ChordTypeException.class)
        .hasMessageContaining("more precision");
  }

  @Test
  void integerWidthsRoundTripTheirBoundaryValues() {
    assertThat(roundTripColumn("UInt8", 0, 255).objectAt(1)).isEqualTo(255);
    assertThat(roundTripColumn("UInt16", 0, 65_535).objectAt(1)).isEqualTo(65_535);
    assertThat(roundTripColumn("UInt32", 0L, 4_294_967_295L).objectAt(1)).isEqualTo(4_294_967_295L);
    assertThat(
            roundTripColumn("UInt64", BigInteger.ZERO, new BigInteger("18446744073709551615"))
                .objectAt(1))
        .isEqualTo(new BigInteger("18446744073709551615"));
    assertThat(
            roundTripColumn(
                    "UInt128", BigInteger.ZERO, BigInteger.TWO.pow(128).subtract(BigInteger.ONE))
                .objectAt(1))
        .isEqualTo(BigInteger.TWO.pow(128).subtract(BigInteger.ONE));
    assertThat(
            roundTripColumn(
                    "UInt256", BigInteger.ZERO, BigInteger.TWO.pow(256).subtract(BigInteger.ONE))
                .objectAt(1))
        .isEqualTo(BigInteger.TWO.pow(256).subtract(BigInteger.ONE));

    assertThat(roundTripColumn("Int8", -128, 127).objectAt(0)).isEqualTo((byte) -128);
    assertThat(roundTripColumn("Int16", -32_768, 32_767).objectAt(0)).isEqualTo((short) -32_768);
    assertThat(roundTripColumn("Int32", Integer.MIN_VALUE, Integer.MAX_VALUE).objectAt(0))
        .isEqualTo(Integer.MIN_VALUE);
    assertThat(roundTripColumn("Int64", Long.MIN_VALUE, Long.MAX_VALUE).objectAt(0))
        .isEqualTo(Long.MIN_VALUE);
    assertThat(
            roundTripColumn(
                    "Int128",
                    BigInteger.TWO.pow(127).negate(),
                    BigInteger.TWO.pow(127).subtract(BigInteger.ONE))
                .objectAt(0))
        .isEqualTo(BigInteger.TWO.pow(127).negate());
    Column i256 =
        roundTripColumn(
            "Int256",
            BigInteger.TWO.pow(255).negate(),
            BigInteger.TWO.pow(255).subtract(BigInteger.ONE),
            BigInteger.valueOf(-1));
    assertThat(i256.objectAt(0)).isEqualTo(BigInteger.TWO.pow(255).negate());
    assertThat(i256.objectAt(1)).isEqualTo(BigInteger.TWO.pow(255).subtract(BigInteger.ONE));
    assertThat(i256.objectAt(2)).isEqualTo(BigInteger.valueOf(-1));
  }

  @Test
  void integerWidthsRejectValuesBeyondTheirBounds() {
    assertThatThrownBy(() -> roundTripColumn("UInt16", 65_536))
        .isInstanceOf(ChordTypeException.class);
    assertThatThrownBy(() -> roundTripColumn("Int16", 32_768))
        .isInstanceOf(ChordTypeException.class);
    assertThatThrownBy(() -> roundTripColumn("UInt32", 4_294_967_296L))
        .isInstanceOf(ChordTypeException.class);
    assertThatThrownBy(() -> roundTripColumn("Int128", BigInteger.TWO.pow(127)))
        .isInstanceOf(ChordTypeException.class);
    assertThatThrownBy(() -> roundTripColumn("UInt256", BigInteger.TWO.pow(256)))
        .isInstanceOf(ChordTypeException.class);
  }

  @Test
  void dateWidthsRoundTripTheirBoundaryValues() {
    Column date = roundTripColumn("Date", LocalDate.ofEpochDay(0), LocalDate.ofEpochDay(65_535));
    assertThat(date.objectAt(0)).isEqualTo(LocalDate.ofEpochDay(0));
    assertThat(date.objectAt(1)).isEqualTo(LocalDate.of(2149, 6, 6));

    Column date32 = roundTripColumn("Date32", LocalDate.of(1900, 1, 1), LocalDate.of(2299, 12, 31));
    assertThat(date32.objectAt(0)).isEqualTo(LocalDate.of(1900, 1, 1));
    assertThat(date32.objectAt(1)).isEqualTo(LocalDate.of(2299, 12, 31));

    Column dateTime =
        roundTripColumn("DateTime", Instant.EPOCH, Instant.ofEpochSecond(4_294_967_295L));
    assertThat(dateTime.objectAt(0)).isEqualTo(Instant.EPOCH);
    // 2106-02-07T06:28:15Z: the unsigned 32 bit ceiling must not wrap negative.
    assertThat(dateTime.objectAt(1)).isEqualTo(Instant.ofEpochSecond(4_294_967_295L));
  }

  @Test
  void everyIntervalKindCarriesValues() {
    for (String kind :
        new String[] {
          "Nanosecond",
          "Microsecond",
          "Millisecond",
          "Second",
          "Minute",
          "Hour",
          "Day",
          "Week",
          "Month",
          "Quarter",
          "Year"
        }) {
      Column column = roundTripColumn("Interval" + kind, -3L, 0L, 12345L);
      assertThat(column.objectAt(0)).as(kind).isEqualTo(-3L);
      assertThat(column.objectAt(2)).as(kind).isEqualTo(12345L);
    }
  }

  @Test
  void everyDecimalWidthRoundTripsItsExtremes() {
    Column d64 =
        roundTripColumn(
            "Decimal64(6)",
            new BigDecimal("999999999999.999999"),
            new BigDecimal("-999999999999.999999"));
    assertThat((BigDecimal) d64.objectAt(0)).isEqualByComparingTo("999999999999.999999");
    assertThat((BigDecimal) d64.objectAt(1)).isEqualByComparingTo("-999999999999.999999");

    Column d128 =
        roundTripColumn(
            "Decimal(38, 10)",
            new BigDecimal("9999999999999999999999999999.9999999999"),
            new BigDecimal("-9999999999999999999999999999.9999999999"));
    assertThat((BigDecimal) d128.objectAt(0))
        .isEqualByComparingTo("9999999999999999999999999999.9999999999");
    assertThat((BigDecimal) d128.objectAt(1))
        .isEqualByComparingTo("-9999999999999999999999999999.9999999999");

    String digits76 = "9".repeat(56) + "." + "9".repeat(20);
    Column d256 =
        roundTripColumn(
            "Decimal(76, 20)", new BigDecimal(digits76), new BigDecimal("-" + digits76));
    assertThat((BigDecimal) d256.objectAt(0)).isEqualByComparingTo(digits76);
    assertThat((BigDecimal) d256.objectAt(1)).isEqualByComparingTo("-" + digits76);
  }

  @Test
  void decimal32AndEnum16RoundTripTheirExtremes() {
    Column decimal =
        roundTripColumn(
            "Decimal32(4)",
            new BigDecimal("99999.9999"),
            new BigDecimal("-99999.9999"),
            BigDecimal.ZERO);
    assertThat((BigDecimal) decimal.objectAt(0)).isEqualByComparingTo("99999.9999");
    assertThat((BigDecimal) decimal.objectAt(1)).isEqualByComparingTo("-99999.9999");
    assertThat((BigDecimal) decimal.objectAt(2)).isEqualByComparingTo("0");

    Column enum16 = roundTripColumn("Enum16('lo' = -32768, 'hi' = 32767)", "lo", "hi");
    assertThat(enum16.objectAt(0)).isEqualTo("lo");
    assertThat(enum16.objectAt(1)).isEqualTo("hi");
  }
}
