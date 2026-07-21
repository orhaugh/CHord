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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.orhaugh.chord.ChordProtocolException;
import io.github.orhaugh.chord.codec.UnsupportedClickHouseTypeException;
import io.github.orhaugh.chord.codec.column.Columns;
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
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** Decoding of native blocks: a byte level golden vector, per type coverage and hostile input. */
class BlockCodecTest {

  private static final long REVISION = 54488;

  private static DecodeContext context() {
    return new DecodeContext(BlockLimits.DEFAULTS, REVISION, ZoneId.of("UTC"));
  }

  private static byte[] built(Consumer<WireWriter> body) {
    ByteArrayOutputStream sink = new ByteArrayOutputStream();
    WireWriter writer = new WireWriter(sink);
    body.accept(writer);
    writer.flush();
    return sink.toByteArray();
  }

  private static Block decode(byte[] bytes) {
    return BlockReader.read(
        new WireReader(new ByteArrayInputStream(bytes), WireLimits.DEFAULTS), context());
  }

  private static void writeDefaultInfo(WireWriter w) {
    w.writeVarUInt(1);
    w.writeBool(false);
    w.writeVarUInt(2);
    w.writeInt32Le(-1);
    w.writeVarUInt(3);
    w.writeVarUInt(0);
    w.writeVarUInt(0);
  }

  /** Writes a one column block at the current revision (custom serialisation flag zero). */
  private static Block decodeColumn(String type, int rows, Consumer<WireWriter> data) {
    return decode(
        built(
            w -> {
              writeDefaultInfo(w);
              w.writeVarUInt(1);
              w.writeVarUInt(rows);
              w.writeString("c");
              w.writeString(type);
              w.writeUInt8(0);
              data.accept(w);
            }));
  }

  @Test
  void decodesGoldenBlockFromExactBytes() {
    // One UInt8 column named "n" with rows 5 and 200, at revision 54488.
    byte[] golden = {
      0x01,
      0x00, // field 1: is_overflows false
      0x02,
      (byte) 0xFF,
      (byte) 0xFF,
      (byte) 0xFF,
      (byte) 0xFF, // field 2: bucket_num -1
      0x03,
      0x00, // field 3: empty out of order buckets
      0x00, // end of info
      0x01, // one column
      0x02, // two rows
      0x01,
      'n', // column name
      0x05,
      'U',
      'I',
      'n',
      't',
      '8', // type name
      0x00, // no custom serialisation
      0x05,
      (byte) 0xC8, // values 5, 200
    };

    Block block = decode(golden);

    assertThat(block.rows()).isEqualTo(2);
    assertThat(block.columnCount()).isEqualTo(1);
    assertThat(block.columnName(0)).isEqualTo("n");
    assertThat(block.columnType(0).name()).isEqualTo("UInt8");
    Columns.UInt8Column column = (Columns.UInt8Column) block.column(0);
    assertThat(column.intAt(0)).isEqualTo(5);
    assertThat(column.intAt(1)).isEqualTo(200);
    assertThat(block.info()).isEqualTo(BlockInfo.DEFAULT);
  }

  @Test
  void emptyBlockWriterOutputDecodesAsEmptyBlock() {
    Block block = decode(built(w -> BlockWriter.writeEmpty(w, REVISION)));
    assertThat(block.rows()).isZero();
    assertThat(block.columnCount()).isZero();
  }

  @Test
  void decodesSignedAndUnsignedIntegers() {
    Block block =
        decodeColumn(
            "Int64",
            2,
            w -> {
              w.writeInt64Le(Long.MIN_VALUE);
              w.writeInt64Le(42);
            });
    Columns.Int64Column int64 = (Columns.Int64Column) block.column(0);
    assertThat(int64.longAt(0)).isEqualTo(Long.MIN_VALUE);

    Block unsigned = decodeColumn("UInt64", 1, w -> w.writeInt64Le(-1L));
    Columns.UInt64Column uint64 = (Columns.UInt64Column) unsigned.column(0);
    assertThat(uint64.bigIntegerAt(0)).isEqualTo(new BigInteger("18446744073709551615"));

    Block int128 =
        decodeColumn(
            "Int128",
            1,
            w -> {
              w.writeInt64Le(-2L); // low 64 bits of -2
              w.writeInt64Le(-1L); // high 64 bits, sign extension
            });
    Columns.BigIntegerColumn big = (Columns.BigIntegerColumn) int128.column(0);
    assertThat(big.bigIntegerAt(0)).isEqualTo(BigInteger.valueOf(-2));

    Block uint256 =
        decodeColumn(
            "UInt256",
            1,
            w -> {
              w.writeInt64Le(-1L);
              w.writeInt64Le(-1L);
              w.writeInt64Le(-1L);
              w.writeInt64Le(-1L);
            });
    Columns.BigIntegerColumn u256 = (Columns.BigIntegerColumn) uint256.column(0);
    assertThat(u256.bigIntegerAt(0)).isEqualTo(BigInteger.TWO.pow(256).subtract(BigInteger.ONE));
  }

  @Test
  void decodesFloatsBoolAndBFloat16() {
    Block floats =
        decodeColumn(
            "Float64",
            2,
            w -> {
              w.writeInt64Le(Double.doubleToLongBits(1.5));
              w.writeInt64Le(Double.doubleToLongBits(-0.25));
            });
    Columns.Float64Column doubles = (Columns.Float64Column) floats.column(0);
    assertThat(doubles.doubleAt(0)).isEqualTo(1.5);
    assertThat(doubles.doubleAt(1)).isEqualTo(-0.25);

    Block bools = decodeColumn("Bool", 2, w -> w.writeBytes(new byte[] {1, 0}, 0, 2));
    assertThat(((Columns.BoolColumn) bools.column(0)).booleanAt(0)).isTrue();

    // BFloat16 of 1.5 is the top sixteen bits of the float pattern 0x3FC00000.
    Block bf16 =
        decodeColumn(
            "BFloat16",
            1,
            w -> {
              w.writeUInt8(0xC0);
              w.writeUInt8(0x3F);
            });
    assertThat(((Columns.BFloat16Column) bf16.column(0)).floatAt(0)).isEqualTo(1.5f);
  }

  @Test
  void decodesStringsAndFixedStrings() {
    Block strings =
        decodeColumn(
            "String",
            3,
            w -> {
              w.writeString("alpha");
              w.writeString("");
              w.writeString("Grüße");
            });
    Columns.StringColumn column = (Columns.StringColumn) strings.column(0);
    assertThat(column.stringAt(0)).isEqualTo("alpha");
    assertThat(column.stringAt(1)).isEmpty();
    assertThat(column.stringAt(2)).isEqualTo("Grüße");
    assertThat(column.bytesAt(2)).hasSize(7);

    Block fixed =
        decodeColumn(
            "FixedString(4)",
            2,
            w -> w.writeBytes(new byte[] {'a', 'b', 0, 0, 'w', 'x', 'y', 'z'}, 0, 8));
    Columns.FixedStringColumn fixedColumn = (Columns.FixedStringColumn) fixed.column(0);
    assertThat(fixedColumn.stringAt(0)).isEqualTo("ab");
    assertThat(fixedColumn.bytesAt(0)).containsExactly('a', 'b', 0, 0);
    assertThat(fixedColumn.stringAt(1)).isEqualTo("wxyz");
  }

  @Test
  void decodesTemporalTypes() {
    Block dates =
        decodeColumn(
            "Date",
            1,
            w -> {
              w.writeUInt8(19723 & 0xFF);
              w.writeUInt8(19723 >>> 8);
            });
    assertThat(((Columns.DateColumn) dates.column(0)).localDateAt(0))
        .isEqualTo(LocalDate.of(2024, 1, 1));

    Block date32 = decodeColumn("Date32", 1, w -> w.writeInt32Le(-1));
    assertThat(((Columns.Date32Column) date32.column(0)).localDateAt(0))
        .isEqualTo(LocalDate.of(1969, 12, 31));

    Block dateTime =
        decodeColumn("DateTime('Europe/London')", 1, w -> w.writeInt32Le(1_000_000_000));
    Columns.DateTimeColumn dt = (Columns.DateTimeColumn) dateTime.column(0);
    assertThat(dt.instantAt(0)).isEqualTo(Instant.ofEpochSecond(1_000_000_000L));
    assertThat(dt.zone()).isEqualTo(ZoneId.of("Europe/London"));

    Block dateTime64 = decodeColumn("DateTime64(3)", 1, w -> w.writeInt64Le(-1_500L));
    Columns.DateTime64Column dt64 = (Columns.DateTime64Column) dateTime64.column(0);
    assertThat(dt64.instantAt(0)).isEqualTo(Instant.ofEpochSecond(-2, 500_000_000));
    // No column timezone: the session zone from the decode context applies.
    assertThat(dt64.zone()).isEqualTo(ZoneId.of("UTC"));
  }

  @Test
  void decodesUuidWithClickHouseHalfOrdering() {
    UUID uuid = UUID.fromString("61f0c404-5cb3-11e7-907b-a6006ad3dba0");
    Block block =
        decodeColumn(
            "UUID",
            1,
            w -> {
              w.writeInt64Le(uuid.getMostSignificantBits());
              w.writeInt64Le(uuid.getLeastSignificantBits());
            });
    assertThat(((Columns.UuidColumn) block.column(0)).uuidAt(0)).isEqualTo(uuid);
  }

  @Test
  void decodesIpAddresses() throws Exception {
    Block v4 = decodeColumn("IPv4", 1, w -> w.writeInt32Le(0x01020304));
    assertThat(((Columns.Ipv4Column) v4.column(0)).inetAddressAt(0))
        .isEqualTo(java.net.InetAddress.getByName("1.2.3.4"));

    byte[] localhost6 = new byte[16];
    localhost6[15] = 1;
    Block v6 = decodeColumn("IPv6", 1, w -> w.writeBytes(localhost6, 0, 16));
    assertThat(((Columns.Ipv6Column) v6.column(0)).inetAddressAt(0))
        .isEqualTo(java.net.InetAddress.getByName("::1"));
  }

  @Test
  void decodesEnumsAndIntervals() {
    Block enum8 =
        decodeColumn(
            "Enum8('red' = 1, 'blue' = -2)", 2, w -> w.writeBytes(new byte[] {1, -2}, 0, 2));
    Columns.EnumColumn column = (Columns.EnumColumn) enum8.column(0);
    assertThat(column.labelAt(0)).isEqualTo("red");
    assertThat(column.labelAt(1)).isEqualTo("blue");
    // Unknown enum values decode into the column but fail explicitly on label access.
    Columns.EnumColumn unknownValue =
        (Columns.EnumColumn) decodeColumn("Enum8('red' = 1)", 1, w -> w.writeUInt8(9)).column(0);
    assertThat(unknownValue.valueAt(0)).isEqualTo(9);
    assertThatThrownBy(() -> unknownValue.labelAt(0))
        .isInstanceOf(io.github.orhaugh.chord.ChordTypeException.class)
        .hasMessageContaining("no label");

    Block interval = decodeColumn("IntervalDay", 1, w -> w.writeInt64Le(-3));
    assertThat(((Columns.IntervalColumn) interval.column(0)).longAt(0)).isEqualTo(-3);
  }

  @Test
  void decodesDecimals() {
    Block d64 = decodeColumn("Decimal(18, 4)", 1, w -> w.writeInt64Le(-12345));
    assertThat(((Columns.DecimalColumn) d64.column(0)).bigDecimalAt(0))
        .isEqualByComparingTo(new BigDecimal("-1.2345"));

    Block d128 =
        decodeColumn(
            "Decimal(38, 2)",
            1,
            w -> {
              w.writeInt64Le(123);
              w.writeInt64Le(0);
            });
    assertThat(((Columns.DecimalColumn) d128.column(0)).bigDecimalAt(0))
        .isEqualByComparingTo(new BigDecimal("1.23"));
  }

  @Test
  void decodesNullableColumns() {
    Block block =
        decodeColumn(
            "Nullable(Int32)",
            3,
            w -> {
              w.writeBytes(new byte[] {0, 1, 0}, 0, 3);
              w.writeInt32Le(7);
              w.writeInt32Le(0);
              w.writeInt32Le(-7);
            });
    Columns.NullableColumn column = (Columns.NullableColumn) block.column(0);
    assertThat(column.isNullAt(0)).isFalse();
    assertThat(column.isNullAt(1)).isTrue();
    assertThat(column.objectAt(0)).isEqualTo(7);
    assertThat(column.objectAt(1)).isNull();
    assertThat(column.objectAt(2)).isEqualTo(-7);
  }

  @Test
  void decodesArraysIncludingNested() {
    Block block =
        decodeColumn(
            "Array(UInt8)",
            3,
            w -> {
              w.writeInt64Le(2); // offsets: [2, 2, 4]
              w.writeInt64Le(2);
              w.writeInt64Le(4);
              w.writeBytes(new byte[] {1, 2, 3, 4}, 0, 4);
            });
    Columns.ArrayColumn column = (Columns.ArrayColumn) block.column(0);
    assertThat(column.listAt(0)).containsExactly(1, 2);
    assertThat(column.listAt(1)).isEmpty();
    assertThat(column.listAt(2)).containsExactly(3, 4);

    Block nested =
        decodeColumn(
            "Array(Array(UInt8))",
            1,
            w -> {
              w.writeInt64Le(2); // outer offsets: one row containing two inner arrays
              w.writeInt64Le(1); // inner offsets: [1, 3]
              w.writeInt64Le(3);
              w.writeBytes(new byte[] {9, 8, 7}, 0, 3);
            });
    Columns.ArrayColumn outer = (Columns.ArrayColumn) nested.column(0);
    assertThat(outer.listAt(0)).hasSize(2);
    assertThat(outer.listAt(0).get(0)).isEqualTo(List.of(9));
    assertThat(outer.listAt(0).get(1)).isEqualTo(List.of(8, 7));
  }

  @Test
  void decodesTuplesAndMaps() {
    Block tuple =
        decodeColumn(
            "Tuple(id UInt8, name String)",
            2,
            w -> {
              w.writeBytes(new byte[] {1, 2}, 0, 2);
              w.writeString("a");
              w.writeString("b");
            });
    Columns.TupleColumn tupleColumn = (Columns.TupleColumn) tuple.column(0);
    assertThat(tupleColumn.tupleAt(0)).containsExactly(1, "a");
    assertThat(tupleColumn.tupleAt(1)).containsExactly(2, "b");

    Block map =
        decodeColumn(
            "Map(String, UInt8)",
            2,
            w -> {
              w.writeInt64Le(2); // offsets [2, 3]
              w.writeInt64Le(3);
              w.writeString("x");
              w.writeString("y");
              w.writeString("z");
              w.writeBytes(new byte[] {1, 2, 3}, 0, 3);
            });
    Columns.MapColumn mapColumn = (Columns.MapColumn) map.column(0);
    assertThat(mapColumn.mapAt(0)).isEqualTo(Map.of("x", 1, "y", 2));
    assertThat(mapColumn.mapAt(1)).isEqualTo(Map.of("z", 3));
  }

  @Test
  void decodesSimpleAggregateFunctionAsInnerType() {
    Block block = decodeColumn("SimpleAggregateFunction(sum, UInt32)", 1, w -> w.writeInt32Le(41));
    assertThat(((Columns.UInt32Column) block.column(0)).longAt(0)).isEqualTo(41);
  }

  @Test
  void decodesNothingAndZeroRowHeaders() {
    Block nothing = decodeColumn("Nothing", 2, w -> w.writeBytes(new byte[] {0, 0}, 0, 2));
    assertThat(nothing.column(0).isNullAt(0)).isTrue();

    Block header =
        decode(
            built(
                w -> {
                  writeDefaultInfo(w);
                  w.writeVarUInt(1);
                  w.writeVarUInt(0);
                  w.writeString("value");
                  w.writeString("UInt64");
                  w.writeUInt8(0);
                }));
    assertThat(header.rows()).isZero();
    assertThat(header.columnType(0).name()).isEqualTo("UInt64");
  }

  @Test
  void rejectsCustomSerialisationExplicitly() {
    byte[] block =
        built(
            w -> {
              writeDefaultInfo(w);
              w.writeVarUInt(1);
              w.writeVarUInt(1);
              w.writeString("sparse");
              w.writeString("UInt64");
              w.writeUInt8(1);
            });
    assertThatThrownBy(() -> decode(block))
        .isInstanceOf(UnsupportedClickHouseTypeException.class)
        .hasMessageContaining("custom serialisation")
        .hasMessageContaining("sparse");
  }

  @Test
  void rejectsLowCardinalityDecodeExplicitly() {
    byte[] block =
        built(
            w -> {
              writeDefaultInfo(w);
              w.writeVarUInt(1);
              w.writeVarUInt(1);
              w.writeString("lc");
              w.writeString("LowCardinality(String)");
              w.writeUInt8(0);
            });
    assertThatThrownBy(() -> decode(block))
        .isInstanceOf(UnsupportedClickHouseTypeException.class)
        .hasMessageContaining("Phase 6");
  }

  @Test
  void rejectsUnknownBlockInfoFields() {
    byte[] block = built(w -> w.writeVarUInt(9));
    assertThatThrownBy(() -> decode(block))
        .isInstanceOf(ChordProtocolException.class)
        .hasMessageContaining("BlockInfo field");
  }

  @Test
  void rejectsNonMonotonicArrayOffsets() {
    assertThatThrownBy(
            () ->
                decodeColumn(
                    "Array(UInt8)",
                    2,
                    w -> {
                      w.writeInt64Le(5);
                      w.writeInt64Le(3);
                    }))
        .isInstanceOf(ChordProtocolException.class)
        .hasMessageContaining("monotonically");
  }

  @Test
  void rejectsExcessiveDimensions() {
    byte[] tooManyColumns =
        built(
            w -> {
              writeDefaultInfo(w);
              w.writeVarUInt(1_000_000_000);
            });
    assertThatThrownBy(() -> decode(tooManyColumns))
        .isInstanceOf(ChordProtocolException.class)
        .hasMessageContaining("columns");

    byte[] tooManyRows =
        built(
            w -> {
              writeDefaultInfo(w);
              w.writeVarUInt(1);
              w.writeVarUInt(-1L);
            });
    assertThatThrownBy(() -> decode(tooManyRows))
        .isInstanceOf(ChordProtocolException.class)
        .hasMessageContaining("rows");
  }

  @Test
  void rejectsTruncatedColumnData() {
    assertThatThrownBy(() -> decodeColumn("Int64", 2, w -> w.writeInt32Le(1)))
        .isInstanceOf(ChordProtocolException.class)
        .hasMessageContaining("ended");
  }
}
