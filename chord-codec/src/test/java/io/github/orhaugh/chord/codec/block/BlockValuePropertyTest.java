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

import io.github.orhaugh.chord.codec.column.BlockBuilder;
import io.github.orhaugh.chord.codec.column.Column;
import io.github.orhaugh.chord.codec.type.TypeParser;
import io.github.orhaugh.chord.protocol.wire.WireLimits;
import io.github.orhaugh.chord.protocol.wire.WireReader;
import io.github.orhaugh.chord.protocol.wire.WireWriter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Randomised value round trips per column type: any generated column of any pooled type must decode
 * back to exactly the objects that were appended, at any row count including zero.
 */
class BlockValuePropertyTest {

  private static final long REVISION = 54488;

  record TypedColumn(String typeName, List<Object> values) {}

  @Property(tries = 400)
  void randomValuesSurviveTheBlockRoundTrip(@ForAll("typedColumns") TypedColumn column) {
    BlockBuilder builder =
        BlockBuilder.forColumnTypes(List.of(TypeParser.parse(column.typeName(), 10_000, 32)));
    for (Object value : column.values()) {
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

    assertThat(decoded.rows()).isEqualTo(column.values().size());
    Column read = decoded.column(0);
    for (int row = 0; row < column.values().size(); row++) {
      assertThat(read.objectAt(row))
          .as("%s row %d", column.typeName(), row)
          .isEqualTo(column.values().get(row));
    }
  }

  @Provide
  Arbitrary<TypedColumn> typedColumns() {
    return Arbitraries.oneOf(
        columnOf("UInt8", Arbitraries.integers().between(0, 255)),
        columnOf("Int16", Arbitraries.shorts()),
        columnOf("UInt16", Arbitraries.integers().between(0, 65_535)),
        columnOf("Int32", Arbitraries.integers()),
        columnOf("UInt32", Arbitraries.longs().between(0, 4_294_967_295L)),
        columnOf("Int64", Arbitraries.longs()),
        columnOf(
            "UInt64",
            Arbitraries.bigIntegers()
                .between(BigInteger.ZERO, BigInteger.TWO.pow(64).subtract(BigInteger.ONE))),
        columnOf(
            "Int128",
            Arbitraries.bigIntegers()
                .between(
                    BigInteger.TWO.pow(127).negate(),
                    BigInteger.TWO.pow(127).subtract(BigInteger.ONE))),
        columnOf("Float32", Arbitraries.floats()),
        columnOf("Float64", Arbitraries.doubles()),
        columnOf("Bool", Arbitraries.of(Boolean.TRUE, Boolean.FALSE)),
        columnOf("String", Arbitraries.strings().ofMaxLength(80)),
        columnOf("FixedString(8)", Arbitraries.strings().alpha().ofLength(8)),
        columnOf(
            "Date",
            Arbitraries.integers().between(0, 65_535).map(days -> LocalDate.ofEpochDay(days))),
        columnOf(
            "DateTime('UTC')",
            Arbitraries.longs().between(0, 4_294_967_295L).map(Instant::ofEpochSecond)),
        columnOf(
            "UUID", Combinators.combine(Arbitraries.longs(), Arbitraries.longs()).as(UUID::new)),
        columnOf(
            "Decimal(10, 2)",
            Arbitraries.longs()
                .between(-9_999_999_999L, 9_999_999_999L)
                .map(unscaled -> BigDecimal.valueOf(unscaled, 2))),
        columnOf(
            "IPv4",
            Arbitraries.integers()
                .map(
                    packed -> {
                      int raw = packed;
                      byte[] quad = {
                        (byte) (raw >>> 24), (byte) (raw >>> 16), (byte) (raw >>> 8), (byte) raw
                      };
                      return address(quad);
                    })),
        columnOf("Nullable(String)", Arbitraries.strings().ofMaxLength(40).injectNull(0.25)),
        columnOf(
            "Array(Int32)",
            Arbitraries.integers().list().ofMaxSize(12).map(list -> (Object) list)));
  }

  private static Arbitrary<TypedColumn> columnOf(String typeName, Arbitrary<?> value) {
    return value
        .map(v -> (Object) v)
        .list()
        .ofMinSize(0)
        .ofMaxSize(64)
        .map(values -> new TypedColumn(typeName, values));
  }

  private static InetAddress address(byte[] raw) {
    try {
      return InetAddress.getByAddress(raw);
    } catch (UnknownHostException e) {
      throw new AssertionError("a 4 byte address is always well formed", e);
    }
  }
}
