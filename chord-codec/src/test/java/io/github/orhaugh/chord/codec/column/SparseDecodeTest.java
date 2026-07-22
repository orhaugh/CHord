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

import io.github.orhaugh.chord.ChordProtocolException;
import io.github.orhaugh.chord.codec.block.BlockLimits;
import io.github.orhaugh.chord.codec.block.DecodeContext;
import io.github.orhaugh.chord.codec.type.TypeParser;
import io.github.orhaugh.chord.protocol.wire.WireLimits;
import io.github.orhaugh.chord.protocol.wire.WireReader;
import io.github.orhaugh.chord.protocol.wire.WireWriter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.ZoneId;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * Sparse column decoding against a reference encoder that follows {@code
 * SerializationSparse::serializeOffsets} literally: a varint gap of defaults before each value,
 * then a flagged varint carrying the trailing default count.
 */
class SparseDecodeTest {

  private static final long END_OF_GRANULE_FLAG = 1L << 62;

  private static DecodeContext context() {
    return new DecodeContext(BlockLimits.DEFAULTS, 54488, ZoneId.of("UTC"));
  }

  private static byte[] bytes(Consumer<WireWriter> actions) {
    ByteArrayOutputStream sink = new ByteArrayOutputStream();
    WireWriter writer = new WireWriter(sink);
    actions.accept(writer);
    writer.flush();
    return sink.toByteArray();
  }

  /** Encodes offsets exactly as the server does. */
  private static void writeOffsets(WireWriter out, long[] valueRows, long totalRows) {
    long start = 0;
    for (long valueRow : valueRows) {
      out.writeVarUInt(valueRow - start);
      start = valueRow + 1;
    }
    long trailing = start < totalRows ? totalRows - start : 0;
    out.writeVarUInt(trailing | END_OF_GRANULE_FLAG);
  }

  @Test
  void sparseValuesLandAtTheirRowsAndDefaultsFillTheRest() {
    byte[] wire =
        bytes(
            w -> {
              writeOffsets(w, new long[] {3, 7, 20}, 32);
              // Three UInt64 values through the nested serialisation.
              w.writeInt64Le(30);
              w.writeInt64Le(70);
              w.writeInt64Le(200);
            });
    Column column =
        ColumnReader.readSparse(
            new WireReader(new ByteArrayInputStream(wire), WireLimits.DEFAULTS),
            TypeParser.parse("UInt64", 100, 8),
            32,
            context());
    assertThat(column.size()).isEqualTo(32);
    for (int i = 0; i < 32; i++) {
      long expected =
          switch (i) {
            case 3 -> 30L;
            case 7 -> 70L;
            case 20 -> 200L;
            default -> 0L;
          };
      assertThat(((Columns.UInt64Column) column).rawLongAt(i)).isEqualTo(expected);
    }
  }

  @Test
  void allDefaultBlocksDecodeWithoutValues() {
    byte[] wire = bytes(w -> writeOffsets(w, new long[0], 10));
    Column column =
        ColumnReader.readSparse(
            new WireReader(new ByteArrayInputStream(wire), WireLimits.DEFAULTS),
            TypeParser.parse("String", 100, 8),
            10,
            context());
    for (int i = 0; i < 10; i++) {
      assertThat(column.objectAt(i)).isEqualTo("");
    }
  }

  @Test
  void valuesAtTheFirstAndLastRowsDecode() {
    byte[] wire =
        bytes(
            w -> {
              writeOffsets(w, new long[] {0, 4}, 5);
              w.writeString("first");
              w.writeString("last");
            });
    Column column =
        ColumnReader.readSparse(
            new WireReader(new ByteArrayInputStream(wire), WireLimits.DEFAULTS),
            TypeParser.parse("String", 100, 8),
            5,
            context());
    assertThat(column.objectAt(0)).isEqualTo("first");
    assertThat(column.objectAt(4)).isEqualTo("last");
    assertThat(column.objectAt(2)).isEqualTo("");
  }

  @Test
  void sparseNullableColumnsMaterialiseNullsAsDefaults() {
    // Nullable values inside sparse travel through the nullable serialisation: a null map for
    // the stored values (all zero, they are non default) then the inner values.
    byte[] wire =
        bytes(
            w -> {
              writeOffsets(w, new long[] {1, 3}, 6);
              w.writeUInt8(0);
              w.writeUInt8(0);
              w.writeString("a");
              w.writeString("b");
            });
    Column column =
        ColumnReader.readSparse(
            new WireReader(new ByteArrayInputStream(wire), WireLimits.DEFAULTS),
            TypeParser.parse("Nullable(String)", 100, 8),
            6,
            context());
    assertThat(column.isNullAt(0)).isTrue();
    assertThat(column.objectAt(1)).isEqualTo("a");
    assertThat(column.isNullAt(2)).isTrue();
    assertThat(column.objectAt(3)).isEqualTo("b");
    assertThat(column.isNullAt(5)).isTrue();
  }

  /**
   * One case per type family: a sparse column of five rows with one non default value at row two,
   * encoded exactly as the server encodes it (offsets, then the values through the nested
   * serialisation), must materialise the value at its row and the type's default everywhere else.
   * This is the register that keeps {@code Defaults} honest for every family.
   */
  @Test
  void everyTypeFamilyMaterialisesItsDefaultsAroundSparseValues() {
    record SparseCase(
        String typeName, Object sample, Object expectedSample, Object expectedDefault) {}
    java.util.List<SparseCase> cases =
        java.util.List.of(
            new SparseCase("UInt16", 7, 7, 0),
            new SparseCase("UInt32", 7L, 7L, 0L),
            new SparseCase("Int16", -5, (short) -5, (short) 0),
            new SparseCase("Int32", -5, -5, 0),
            new SparseCase("Int64", -5L, -5L, 0L),
            new SparseCase(
                "Int128",
                java.math.BigInteger.TEN.negate(),
                java.math.BigInteger.TEN.negate(),
                java.math.BigInteger.ZERO),
            new SparseCase("Float32", 1.5f, 1.5f, 0.0f),
            new SparseCase("Float64", 2.5d, 2.5d, 0.0d),
            new SparseCase("BFloat16", 1.5f, 1.5f, 0.0f),
            new SparseCase("Bool", true, true, false),
            new SparseCase("FixedString(3)", "abc", "abc", ""),
            new SparseCase(
                "Date",
                java.time.LocalDate.of(2024, 5, 1),
                java.time.LocalDate.of(2024, 5, 1),
                java.time.LocalDate.ofEpochDay(0)),
            new SparseCase(
                "Date32",
                java.time.LocalDate.of(1969, 12, 31),
                java.time.LocalDate.of(1969, 12, 31),
                java.time.LocalDate.ofEpochDay(0)),
            new SparseCase(
                "DateTime",
                java.time.Instant.ofEpochSecond(1_000_000),
                java.time.Instant.ofEpochSecond(1_000_000),
                java.time.Instant.EPOCH),
            new SparseCase(
                "DateTime64(3)",
                java.time.Instant.ofEpochSecond(1_000_000, 123_000_000),
                java.time.Instant.ofEpochSecond(1_000_000, 123_000_000),
                java.time.Instant.EPOCH),
            new SparseCase("IntervalDay", 5L, 5L, 0L),
            new SparseCase(
                "UUID",
                java.util.UUID.fromString("0195a6de-3b3b-7000-8000-000000000042"),
                java.util.UUID.fromString("0195a6de-3b3b-7000-8000-000000000042"),
                new java.util.UUID(0, 0)),
            new SparseCase("Enum8('none' = 0, 'hit' = 1)", "hit", "hit", "none"),
            new SparseCase("Enum16('none' = 0, 'big' = 1000)", "big", "big", "none"),
            new SparseCase(
                "Array(UInt8)",
                java.util.List.of(1, 2),
                java.util.List.of(1, 2),
                java.util.List.of()),
            new SparseCase(
                "Tuple(a UInt8, b String)",
                java.util.List.of(3, "x"),
                java.util.List.of(3, "x"),
                java.util.List.of(0, "")),
            new SparseCase(
                "Map(String, UInt8)",
                java.util.Map.of("k", 1),
                java.util.Map.of("k", 1),
                java.util.Map.of()));

    for (SparseCase sparseCase : cases) {
      Column column = decodeSparse(sparseCase.typeName(), sparseCase.sample());
      assertThat(column.size()).as(sparseCase.typeName()).isEqualTo(5);
      assertThat(column.objectAt(2))
          .as(sparseCase.typeName() + " value")
          .isEqualTo(sparseCase.expectedSample());
      for (int row : new int[] {0, 1, 3, 4}) {
        assertThat(column.objectAt(row))
            .as(sparseCase.typeName() + " default at row " + row)
            .isEqualTo(sparseCase.expectedDefault());
      }
    }
  }

  @Test
  void sparseDecimalsMaterialiseZeroDefaults() {
    Column column = decodeSparse("Decimal(10, 2)", new java.math.BigDecimal("12.34"));
    assertThat((java.math.BigDecimal) column.objectAt(2)).isEqualByComparingTo("12.34");
    assertThat((java.math.BigDecimal) column.objectAt(0)).isEqualByComparingTo("0");
    assertThat((java.math.BigDecimal) column.objectAt(4)).isEqualByComparingTo("0");
  }

  @Test
  void sparseIpColumnsMaterialiseZeroAddressDefaults() throws Exception {
    Column ipv4 = decodeSparse("IPv4", java.net.InetAddress.getByName("9.8.7.6"));
    assertThat(ipv4.objectAt(2)).isEqualTo(java.net.InetAddress.getByName("9.8.7.6"));
    assertThat(ipv4.objectAt(0)).isEqualTo(java.net.InetAddress.getByName("0.0.0.0"));

    Column ipv6 = decodeSparse("IPv6", java.net.InetAddress.getByName("2001:db8::1"));
    assertThat(ipv6.objectAt(2)).isEqualTo(java.net.InetAddress.getByName("2001:db8::1"));
    assertThat(ipv6.objectAt(0)).isEqualTo(java.net.InetAddress.getByName("::"));
  }

  @Test
  void sparseEnumWithoutAZeroLabelFailsExplicitly() {
    byte[] wire = bytes(w -> writeOffsets(w, new long[0], 5));
    assertThatThrownBy(
            () ->
                ColumnReader.readSparse(
                    new WireReader(new ByteArrayInputStream(wire), WireLimits.DEFAULTS),
                    TypeParser.parse("Enum8('a' = 1)", 100, 8),
                    5,
                    context()))
        .isInstanceOf(io.github.orhaugh.chord.codec.UnsupportedClickHouseTypeException.class)
        .hasMessageContaining("zero valued label");
  }

  /** Encodes a five row sparse column with one value at row two and decodes it. */
  private static Column decodeSparse(String typeName, Object sample) {
    io.github.orhaugh.chord.codec.type.ClickHouseType type = TypeParser.parse(typeName, 10_000, 32);
    BlockBuilder builder = BlockBuilder.forColumnTypes(java.util.List.of(type));
    builder.addRow(sample);
    Column values = builder.build().column(0);
    byte[] wire =
        bytes(
            w -> {
              writeOffsets(w, new long[] {2}, 5);
              ColumnWriter.write(w, values);
            });
    return ColumnReader.readSparse(
        new WireReader(new ByteArrayInputStream(wire), WireLimits.DEFAULTS), type, 5, context());
  }

  @Test
  void offsetsBeyondTheBlockFailExplicitly() {
    byte[] wire =
        bytes(
            w -> {
              writeOffsets(w, new long[] {40}, 10);
              w.writeInt64Le(1);
            });
    assertThatThrownBy(
            () ->
                ColumnReader.readSparse(
                    new WireReader(new ByteArrayInputStream(wire), WireLimits.DEFAULTS),
                    TypeParser.parse("UInt64", 100, 8),
                    10,
                    context()))
        .isInstanceOf(ChordProtocolException.class)
        .hasMessageContaining("Sparse offsets");
  }

  @Test
  void coverageMismatchFailsExplicitly() {
    byte[] wire =
        bytes(
            w -> {
              // Trailing count says 3 defaults after row 2, but the block has 10 rows.
              w.writeVarUInt(2);
              w.writeVarUInt(3 | END_OF_GRANULE_FLAG);
              w.writeInt64Le(5);
            });
    assertThatThrownBy(
            () ->
                ColumnReader.readSparse(
                    new WireReader(new ByteArrayInputStream(wire), WireLimits.DEFAULTS),
                    TypeParser.parse("UInt64", 100, 8),
                    10,
                    context()))
        .isInstanceOf(ChordProtocolException.class)
        .hasMessageContaining("cover");
  }
}
