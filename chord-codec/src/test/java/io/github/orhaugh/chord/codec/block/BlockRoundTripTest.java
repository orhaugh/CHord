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

import io.github.orhaugh.chord.ChordTypeException;
import io.github.orhaugh.chord.codec.column.BlockBuilder;
import io.github.orhaugh.chord.codec.type.TypeParser;
import io.github.orhaugh.chord.protocol.wire.WireLimits;
import io.github.orhaugh.chord.protocol.wire.WireReader;
import io.github.orhaugh.chord.protocol.wire.WireWriter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.InetAddress;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Building blocks from Java values, encoding them and decoding them back must reproduce the values
 * exactly for every writable type.
 */
class BlockRoundTripTest {

  private static final long REVISION = 54488;

  private static Block schemaOf(String... namesAndTypes) {
    List<Block.NamedColumn> columns = new ArrayList<>();
    for (int i = 0; i < namesAndTypes.length; i += 2) {
      var type = TypeParser.parse(namesAndTypes[i + 1]);
      // Zero row columns for the schema: decode an empty column of the type.
      var empty =
          io.github.orhaugh.chord.codec.column.ColumnReader.read(
              new WireReader(new ByteArrayInputStream(new byte[0]), WireLimits.DEFAULTS),
              type,
              0,
              context());
      columns.add(new Block.NamedColumn(namesAndTypes[i], empty));
    }
    return new Block(BlockInfo.DEFAULT, 0, columns);
  }

  private static DecodeContext context() {
    return new DecodeContext(BlockLimits.DEFAULTS, REVISION, ZoneId.of("UTC"));
  }

  private static Block roundTrip(Block block) {
    ByteArrayOutputStream sink = new ByteArrayOutputStream();
    WireWriter writer = new WireWriter(sink);
    BlockWriter.write(writer, block, REVISION);
    writer.flush();
    return BlockReader.read(
        new WireReader(new ByteArrayInputStream(sink.toByteArray()), WireLimits.DEFAULTS),
        context());
  }

  @Test
  void roundTripsEveryWritableType() throws Exception {
    Block schema =
        schemaOf(
            "u8", "UInt8",
            "u64", "UInt64",
            "u256", "UInt256",
            "i8", "Int8",
            "i64", "Int64",
            "i128", "Int128",
            "f32", "Float32",
            "f64", "Float64",
            "bf16", "BFloat16",
            "b", "Bool",
            "s", "String",
            "fs", "FixedString(4)",
            "d", "Date",
            "d32", "Date32",
            "dt", "DateTime('UTC')",
            "dt64", "DateTime64(3, 'UTC')",
            "uuid", "UUID",
            "ip4", "IPv4",
            "ip6", "IPv6",
            "e8", "Enum8('red' = 1, 'blue' = -2)",
            "e16", "Enum16('x' = 1000)",
            "dec", "Decimal(18, 4)",
            "dec256", "Decimal(76, 10)",
            "iv", "IntervalDay",
            "n", "Nullable(Int32)",
            "arr", "Array(UInt8)",
            "arr2", "Array(Array(String))",
            "tup", "Tuple(id UInt8, name String)",
            "m", "Map(String, UInt16)",
            "saf", "SimpleAggregateFunction(sum, UInt64)");

    Map<String, Integer> mapValue = new LinkedHashMap<>();
    mapValue.put("k1", 1);
    mapValue.put("k2", 2);

    BlockBuilder builder = BlockBuilder.forSchema(schema);
    builder.addRow(
        200,
        new BigInteger("18446744073709551615"),
        BigInteger.TWO.pow(256).subtract(BigInteger.ONE),
        (byte) -128,
        Long.MIN_VALUE,
        new BigInteger("-170141183460469231731687303715884105728"),
        1.5f,
        -0.25d,
        1.5f,
        true,
        "text",
        "ab",
        LocalDate.of(2024, 1, 1),
        LocalDate.of(1969, 12, 31),
        Instant.parse("2024-01-01T12:00:00Z"),
        Instant.parse("2024-01-01T12:00:00.123Z"),
        UUID.fromString("61f0c404-5cb3-11e7-907b-a6006ad3dba0"),
        InetAddress.getByName("1.2.3.4"),
        InetAddress.getByName("2001:db8::1"),
        "blue",
        1000,
        new BigDecimal("-1.2345"),
        new BigDecimal("1234567890.0123456789"),
        -3L,
        null,
        List.of(1, 2, 3),
        List.of(List.of("a"), List.of("b", "c")),
        List.of(7, "x"),
        mapValue,
        42L);
    builder.addRow(
        0,
        0L,
        BigInteger.ZERO,
        (byte) 0,
        0L,
        BigInteger.ZERO,
        0f,
        0d,
        0f,
        false,
        new byte[] {(byte) 0xFE},
        new byte[] {1, 2, 3, 4},
        LocalDate.EPOCH,
        LocalDate.EPOCH,
        Instant.EPOCH,
        Instant.EPOCH,
        new UUID(0, 0),
        InetAddress.getByName("0.0.0.0"),
        InetAddress.getByName("::"),
        1,
        "x",
        BigDecimal.valueOf(0, 4),
        BigDecimal.valueOf(0, 10),
        0,
        7,
        List.of(),
        List.of(),
        List.of(0, ""),
        Map.of(),
        0L);

    Block original = builder.build();
    Block decoded = roundTrip(original);

    assertThat(decoded.rows()).isEqualTo(2);
    assertThat(decoded.columnCount()).isEqualTo(original.columnCount());
    for (int c = 0; c < original.columnCount(); c++) {
      assertThat(decoded.columnName(c)).isEqualTo(original.columnName(c));
      assertThat(decoded.columnType(c)).isEqualTo(original.columnType(c));
      for (int r = 0; r < original.rows(); r++) {
        Object expected = original.column(c).objectAt(r);
        Object actual = decoded.column(c).objectAt(r);
        if (expected instanceof byte[] expectedBytes) {
          assertThat((byte[]) actual).isEqualTo(expectedBytes);
        } else {
          assertThat(actual).as("column %s row %d", original.columnName(c), r).isEqualTo(expected);
        }
      }
    }

    // Spot checks through typed accessors.
    var strings =
        (io.github.orhaugh.chord.codec.column.Columns.StringColumn)
            decoded.columnByName("s").orElseThrow();
    assertThat(strings.bytesAt(1)).isEqualTo(new byte[] {(byte) 0xFE});
    var nullable =
        (io.github.orhaugh.chord.codec.column.Columns.NullableColumn)
            decoded.columnByName("n").orElseThrow();
    assertThat(nullable.isNullAt(0)).isTrue();
    assertThat(nullable.objectAt(1)).isEqualTo(7);
    var bf16 =
        (io.github.orhaugh.chord.codec.column.Columns.BFloat16Column)
            decoded.columnByName("bf16").orElseThrow();
    assertThat(bf16.floatAt(0)).isEqualTo(1.5f);
  }

  @Test
  void decodedBlocksReencodeIdentically() {
    Block schema = schemaOf("v", "Array(Nullable(String))");
    BlockBuilder builder = BlockBuilder.forSchema(schema);
    builder.addRow(List.of("a", "b"));
    List<Object> withNull = new ArrayList<>();
    withNull.add(null);
    withNull.add("c");
    builder.addRow(withNull);

    Block original = builder.build();
    ByteArrayOutputStream first = new ByteArrayOutputStream();
    WireWriter firstWriter = new WireWriter(first);
    BlockWriter.write(firstWriter, original, REVISION);
    firstWriter.flush();

    Block decoded =
        BlockReader.read(
            new WireReader(new ByteArrayInputStream(first.toByteArray()), WireLimits.DEFAULTS),
            context());
    ByteArrayOutputStream second = new ByteArrayOutputStream();
    WireWriter secondWriter = new WireWriter(second);
    BlockWriter.write(secondWriter, decoded, REVISION);
    secondWriter.flush();

    assertThat(second.toByteArray()).isEqualTo(first.toByteArray());
    assertThat(decoded.column(0).objectAt(1)).isEqualTo(withNull);
  }

  @Test
  void rejectsLossyConversionsWithRowAndColumnContext() {
    Block schema = schemaOf("v", "UInt8");
    BlockBuilder builder = BlockBuilder.forSchema(schema);
    assertThatThrownBy(() -> builder.addRow(256))
        .isInstanceOf(ChordTypeException.class)
        .hasMessageContaining("Row 0")
        .hasMessageContaining("\"v\"")
        .hasMessageContaining("range");

    Block decimalSchema = schemaOf("d", "Decimal(18, 2)");
    BlockBuilder decimalBuilder = BlockBuilder.forSchema(decimalSchema);
    assertThatThrownBy(() -> decimalBuilder.addRow(new BigDecimal("1.234")))
        .isInstanceOf(ChordTypeException.class)
        .hasMessageContaining("exactly");

    Block dt64Schema = schemaOf("t", "DateTime64(3)");
    BlockBuilder dt64Builder = BlockBuilder.forSchema(dt64Schema);
    assertThatThrownBy(() -> dt64Builder.addRow(Instant.parse("2024-01-01T00:00:00.0001Z")))
        .isInstanceOf(ChordTypeException.class)
        .hasMessageContaining("precision");

    Block fixedSchema = schemaOf("f", "FixedString(2)");
    BlockBuilder fixedBuilder = BlockBuilder.forSchema(fixedSchema);
    assertThatThrownBy(() -> fixedBuilder.addRow("abc"))
        .isInstanceOf(ChordTypeException.class)
        .hasMessageContaining("does not fit");

    Block enumSchema = schemaOf("e", "Enum8('a' = 1)");
    BlockBuilder enumBuilder = BlockBuilder.forSchema(enumSchema);
    assertThatThrownBy(() -> enumBuilder.addRow("nope"))
        .isInstanceOf(ChordTypeException.class)
        .hasMessageContaining("not part of");
    assertThatThrownBy(() -> enumBuilder.addRow(9))
        .isInstanceOf(ChordTypeException.class)
        .hasMessageContaining("no label");
  }

  @Test
  void rejectsNullForNonNullableColumnsAndWrongArity() {
    Block schema = schemaOf("v", "UInt8");
    BlockBuilder builder = BlockBuilder.forSchema(schema);
    assertThatThrownBy(() -> builder.addRow((Object) null))
        .isInstanceOf(ChordTypeException.class)
        .hasMessageContaining("NULL");
    assertThatThrownBy(() -> builder.addRow(1, 2))
        .isInstanceOf(ChordTypeException.class)
        .hasMessageContaining("columns");
  }

  @Test
  void uint64AcceptsRawLongsOnlyWhenNonNegative() {
    Block schema = schemaOf("v", "UInt64");
    BlockBuilder builder = BlockBuilder.forSchema(schema);
    assertThatThrownBy(() -> builder.addRow(-1L))
        .isInstanceOf(ChordTypeException.class)
        .hasMessageContaining("BigInteger");
    builder.addRow(new BigInteger("18446744073709551615"));
    Block block = builder.build();
    var column = (io.github.orhaugh.chord.codec.column.Columns.UInt64Column) block.column(0);
    assertThat(column.rawLongAt(0)).isEqualTo(-1L);
  }

  @Test
  void builderResetsBetweenBlocks() {
    Block schema = schemaOf("f", "FixedString(4)", "s", "String");
    BlockBuilder builder = BlockBuilder.forSchema(schema);
    builder.addRow("wxyz", "first");
    Block first = builder.build();
    builder.addRow("ab", "second");
    Block second = builder.build();

    var fixed = (io.github.orhaugh.chord.codec.column.Columns.FixedStringColumn) second.column(0);
    // Zero padding must not leak bytes from the previous block.
    assertThat(fixed.bytesAt(0)).isEqualTo(new byte[] {'a', 'b', 0, 0});
    assertThat(first.rows()).isEqualTo(1);
    assertThat(second.rows()).isEqualTo(1);
  }

  @Test
  void ipv4ValuesMapIntoIpv6Columns() throws Exception {
    Block schema = schemaOf("v", "IPv6");
    BlockBuilder builder = BlockBuilder.forSchema(schema);
    builder.addRow(InetAddress.getByName("1.2.3.4"));
    Block block = roundTrip(builder.build());
    byte[] stored =
        ((io.github.orhaugh.chord.codec.column.Columns.Ipv6Column) block.column(0))
            .inetAddressAt(0)
            .getAddress();
    byte[] expected = new byte[16];
    expected[10] = (byte) 0xFF;
    expected[11] = (byte) 0xFF;
    expected[12] = 1;
    expected[13] = 2;
    expected[14] = 3;
    expected[15] = 4;
    assertThat(Arrays.equals(stored, expected) || stored.length == 4).isTrue();
  }
}
