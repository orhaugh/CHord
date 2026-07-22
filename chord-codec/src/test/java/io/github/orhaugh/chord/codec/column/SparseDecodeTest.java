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
