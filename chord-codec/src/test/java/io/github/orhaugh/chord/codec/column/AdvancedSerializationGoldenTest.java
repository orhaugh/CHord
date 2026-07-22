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

import io.github.orhaugh.chord.codec.block.BlockLimits;
import io.github.orhaugh.chord.codec.block.DecodeContext;
import io.github.orhaugh.chord.codec.type.ClickHouseType;
import io.github.orhaugh.chord.codec.type.TypeParser;
import io.github.orhaugh.chord.protocol.wire.WireLimits;
import io.github.orhaugh.chord.protocol.wire.WireReader;
import java.io.InputStream;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Decodes columns from Native format files produced by a real ClickHouse 25.8 server ({@code
 * clickhouse local ... FORMAT Native}), the strongest available check that the decoders speak the
 * server's exact wire layout.
 *
 * <p>The files use the revision zero file layout: no block info and no custom serialisation flag,
 * with per column data identical to the TCP layout (prefix then body). Dynamic and JSON prefixes
 * are the V1 variants, which differ from the V2 the TCP path produces only by one legacy varint.
 */
class AdvancedSerializationGoldenTest {

  private static DecodeContext context() {
    return new DecodeContext(BlockLimits.DEFAULTS, 54488, ZoneId.of("UTC"));
  }

  /** Parses the single column of a revision zero Native file. */
  private static Column readSingleColumn(String resource) {
    try (InputStream stream =
        AdvancedSerializationGoldenTest.class.getResourceAsStream("/serialization/" + resource)) {
      assertThat(stream).as(resource).isNotNull();
      WireReader in = new WireReader(stream, WireLimits.DEFAULTS);
      long columns = in.readVarUInt();
      long rows = in.readVarUInt();
      assertThat(columns).isEqualTo(1);
      in.readString(); // column name
      String typeName = in.readString();
      ClickHouseType type = TypeParser.parse(typeName, 10_000, 32);
      return ColumnReader.read(in, type, (int) rows, context());
    } catch (java.io.IOException e) {
      throw new AssertionError(e);
    }
  }

  @Test
  void lowCardinalityStringDecodes() {
    Column column = readSingleColumn("lc-string.bin");
    assertThat(column).isInstanceOf(Columns.LowCardinalityColumn.class);
    assertThat(column.size()).isEqualTo(12);
    for (int i = 0; i < 12; i++) {
      assertThat(column.objectAt(i)).isEqualTo("v" + (i % 4));
    }
  }

  @Test
  void lowCardinalityNullableStringDecodes() {
    Column column = readSingleColumn("lc-nullable.bin");
    assertThat(column.size()).isEqualTo(12);
    for (int i = 0; i < 12; i++) {
      if (i % 3 == 0) {
        assertThat(column.isNullAt(i)).isTrue();
        assertThat(column.objectAt(i)).isNull();
      } else {
        assertThat(column.objectAt(i)).isEqualTo("n" + (i % 4));
      }
    }
  }

  @Test
  void arrayOfLowCardinalityDecodes() {
    Column column = readSingleColumn("array-lc.bin");
    assertThat(column.size()).isEqualTo(10);
    for (int i = 0; i < 10; i++) {
      List<?> array = (List<?>) column.objectAt(i);
      assertThat(array).hasSize(i % 4);
      for (int j = 0; j < array.size(); j++) {
        assertThat(array.get(j)).isEqualTo("a" + (j % 3));
      }
    }
  }

  @Test
  void variantDecodes() {
    Column column = readSingleColumn("variant.bin");
    assertThat(column).isInstanceOf(Columns.VariantColumn.class);
    assertThat(column.size()).isEqualTo(9);
    for (int i = 0; i < 9; i++) {
      if (i % 3 == 0) {
        assertThat(column.isNullAt(i)).isTrue();
      } else if (i % 3 == 1) {
        // CAST from a numeric string picks the Int64 variant.
        assertThat(column.objectAt(i)).isEqualTo((long) i);
      } else {
        assertThat(column.objectAt(i)).isEqualTo("s" + i);
      }
    }
  }

  @Test
  void dynamicDecodes() {
    Column column = readSingleColumn("dynamic.bin");
    assertThat(column).isInstanceOf(Columns.DynamicColumn.class);
    Columns.DynamicColumn dynamic = (Columns.DynamicColumn) column;
    assertThat(dynamic.size()).isEqualTo(6);
    assertThat(dynamic.objectAt(0)).isEqualTo(42L);
    assertThat(dynamic.objectAt(1)).isEqualTo("hello");
    assertThat(dynamic.isNullAt(2)).isTrue();
    assertThat(dynamic.objectAt(3)).isEqualTo(List.of(1L, 2L, 3L));
    assertThat(dynamic.objectAt(4)).isEqualTo(43L);
    assertThat(dynamic.objectAt(5)).isEqualTo("world");
    assertThat(dynamic.typeNameAt(0)).contains("Int64");
    assertThat(dynamic.typeNameAt(1)).contains("String");
    assertThat(dynamic.typeNameAt(2)).isEmpty();
    assertThat(dynamic.typeNameAt(3)).contains("Array(Int64)");
  }

  @Test
  void jsonDecodes() {
    Column column = readSingleColumn("json.bin");
    assertThat(column).isInstanceOf(Columns.JsonColumn.class);
    assertThat(column.size()).isEqualTo(3);
    @SuppressWarnings("unchecked")
    Map<String, Object> first = (Map<String, Object>) column.objectAt(0);
    assertThat(first).containsEntry("a.b", 42L).containsEntry("c", "str").hasSize(2);
    @SuppressWarnings("unchecked")
    Map<String, Object> second = (Map<String, Object>) column.objectAt(1);
    assertThat(second).containsEntry("a.b", 43L).hasSize(1);
    @SuppressWarnings("unchecked")
    Map<String, Object> third = (Map<String, Object>) column.objectAt(2);
    assertThat(third).hasSize(1).containsKey("x");
    assertThat(third.get("x"))
        .isEqualTo(java.util.Arrays.asList(1L, 2L, 3L)); // Array(Nullable(Int64))
  }

  @Test
  void jsonWithTypedPathDecodes() {
    Column column = readSingleColumn("json-typed.bin");
    Columns.JsonColumn json = (Columns.JsonColumn) column;
    assertThat(json.size()).isEqualTo(2);
    assertThat(json.typedPaths()).containsOnlyKeys("a");
    @SuppressWarnings("unchecked")
    Map<String, Object> first = (Map<String, Object>) json.objectAt(0);
    assertThat(first).containsEntry("a", 7L).containsEntry("b", "t").hasSize(2);
    @SuppressWarnings("unchecked")
    Map<String, Object> second = (Map<String, Object>) json.objectAt(1);
    assertThat(second).containsEntry("a", 8L).hasSize(1);
  }
}
