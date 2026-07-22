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
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;
import org.junit.jupiter.api.Test;

/** LowCardinality encoding: build, write, read back, compare, at the negotiated revision. */
class LowCardinalityRoundTripTest {

  private static final long REVISION = 54488;

  private static DecodeContext context() {
    return new DecodeContext(BlockLimits.DEFAULTS, REVISION, ZoneId.of("UTC"));
  }

  private static Block schemaOf(String typeName) {
    // A zero row block carrying the schema, as the server sends before an INSERT.
    BlockBuilder builder =
        BlockBuilder.forColumnTypes(List.of(TypeParser.parse(typeName, 10_000, 32)));
    return builder.build();
  }

  private static Block roundTrip(Block block) {
    ByteArrayOutputStream sink = new ByteArrayOutputStream();
    WireWriter out = new WireWriter(sink);
    BlockWriter.write(out, block, REVISION);
    out.flush();
    WireReader in =
        new WireReader(new ByteArrayInputStream(sink.toByteArray()), WireLimits.DEFAULTS);
    return BlockReader.read(in, context());
  }

  @Test
  void stringsRoundTripThroughTheDictionary() {
    BlockBuilder builder =
        BlockBuilder.forColumnTypes(List.of(TypeParser.parse("LowCardinality(String)", 100, 8)));
    List<String> values = List.of("alpha", "beta", "alpha", "", "gamma", "beta", "alpha");
    for (String value : values) {
      builder.addRow(value);
    }
    Block decoded = roundTrip(builder.build());
    Columns.LowCardinalityColumn column = (Columns.LowCardinalityColumn) decoded.column(0);
    assertThat(column.size()).isEqualTo(values.size());
    for (int i = 0; i < values.size(); i++) {
      assertThat(column.objectAt(i)).isEqualTo(values.get(i));
    }
    // The dictionary holds the default slot plus each distinct value exactly once.
    assertThat(column.dictionary().size()).isEqualTo(1 + 4);
  }

  @Test
  void nullableStringsRoundTripWithNullsInSlotZero() {
    BlockBuilder builder =
        BlockBuilder.forColumnTypes(
            List.of(TypeParser.parse("LowCardinality(Nullable(String))", 100, 8)));
    List<String> values = new ArrayList<>();
    values.add("x");
    values.add(null);
    values.add("y");
    values.add(null);
    values.add("x");
    for (String value : values) {
      builder.addRow(value);
    }
    Block decoded = roundTrip(builder.build());
    Column column = decoded.column(0);
    for (int i = 0; i < values.size(); i++) {
      assertThat(column.objectAt(i)).isEqualTo(values.get(i));
      assertThat(column.isNullAt(i)).isEqualTo(values.get(i) == null);
    }
  }

  @Test
  void numbersRoundTrip() {
    BlockBuilder builder =
        BlockBuilder.forColumnTypes(List.of(TypeParser.parse("LowCardinality(UInt64)", 100, 8)));
    long[] values = {5, 5, 0, 9, 5, 0, 12345678901L};
    for (long value : values) {
      builder.addRow(value);
    }
    Block decoded = roundTrip(builder.build());
    Column column = decoded.column(0);
    for (int i = 0; i < values.length; i++) {
      // UInt64 values surface as unsigned BigIntegers, like the plain UInt64 column.
      assertThat(column.objectAt(i)).isEqualTo(java.math.BigInteger.valueOf(values[i]));
    }
  }

  @Test
  void wideDictionariesUseWiderIndexes() {
    BlockBuilder builder =
        BlockBuilder.forColumnTypes(List.of(TypeParser.parse("LowCardinality(String)", 100, 8)));
    int rows = 700; // more than 256 distinct values forces two byte indexes
    for (int i = 0; i < rows; i++) {
      builder.addRow("value-" + (i % 400));
    }
    Block decoded = roundTrip(builder.build());
    Column column = decoded.column(0);
    for (int i = 0; i < rows; i++) {
      assertThat(column.objectAt(i)).isEqualTo("value-" + (i % 400));
    }
  }

  @Test
  void decodedColumnsReEncodeByteExactly() {
    BlockBuilder builder =
        BlockBuilder.forColumnTypes(List.of(TypeParser.parse("LowCardinality(String)", 100, 8)));
    Random random = new Random(11);
    for (int i = 0; i < 200; i++) {
      builder.addRow("k" + random.nextInt(9));
    }
    ByteArrayOutputStream first = new ByteArrayOutputStream();
    WireWriter out = new WireWriter(first);
    BlockWriter.write(out, builder.build(), REVISION);
    out.flush();

    Block decoded =
        BlockReader.read(
            new WireReader(new ByteArrayInputStream(first.toByteArray()), WireLimits.DEFAULTS),
            context());
    ByteArrayOutputStream second = new ByteArrayOutputStream();
    WireWriter out2 = new WireWriter(second);
    BlockWriter.write(out2, decoded, REVISION);
    out2.flush();
    assertThat(second.toByteArray()).isEqualTo(first.toByteArray());
  }

  @Test
  void emptyLowCardinalityBlocksCarryNoColumnData() {
    Block empty = schemaOf("LowCardinality(String)");
    Block decoded = roundTrip(empty);
    assertThat(decoded.rows()).isZero();
    assertThat(decoded.column(0)).isInstanceOf(Columns.LowCardinalityColumn.class);
    assertThat(decoded.column(0).size()).isZero();
  }

  @Property(tries = 50)
  void randomValuesRoundTrip(
      @ForAll @Size(min = 1, max = 120) List<@IntRange(min = 0, max = 15) Integer> keys) {
    BlockBuilder builder =
        BlockBuilder.forColumnTypes(List.of(TypeParser.parse("LowCardinality(String)", 100, 8)));
    for (int key : keys) {
      builder.addRow("k" + key);
    }
    Block decoded = roundTrip(builder.build());
    Column column = decoded.column(0);
    for (int i = 0; i < keys.size(); i++) {
      assertThat(column.objectAt(i)).isEqualTo("k" + keys.get(i));
    }
  }
}
