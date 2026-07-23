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
import io.github.orhaugh.chord.codec.type.ClickHouseType;
import io.github.orhaugh.chord.codec.type.TypeParser;
import io.github.orhaugh.chord.protocol.wire.WireLimits;
import io.github.orhaugh.chord.protocol.wire.WireReader;
import io.github.orhaugh.chord.protocol.wire.WireWriter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The geometry aliases decode and encode as their underlying tuple and array shapes: Point is
 * Tuple(Float64, Float64) and the rest are arrays over it, exactly as the server serialises them.
 */
class GeometryAliasesTest {

  private static final long REVISION = 54488;

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
  void aliasesParseToTheirDocumentedShapes() {
    ClickHouseType point = TypeParser.parse("Point", 10_000, 32);
    assertThat(point).isInstanceOf(ClickHouseType.TupleType.class);
    ClickHouseType.TupleType tuple = (ClickHouseType.TupleType) point;
    assertThat(tuple.elements()).hasSize(2);
    assertThat(tuple.elements().get(0).type()).isInstanceOf(ClickHouseType.FloatType.class);

    for (String alias : List.of("Ring", "LineString")) {
      ClickHouseType parsed = TypeParser.parse(alias, 10_000, 32);
      assertThat(parsed).isInstanceOf(ClickHouseType.ArrayType.class);
      assertThat(((ClickHouseType.ArrayType) parsed).element())
          .isInstanceOf(ClickHouseType.TupleType.class);
    }
    for (String alias : List.of("Polygon", "MultiLineString")) {
      ClickHouseType parsed = TypeParser.parse(alias, 10_000, 32);
      ClickHouseType inner = ((ClickHouseType.ArrayType) parsed).element();
      assertThat(((ClickHouseType.ArrayType) inner).element())
          .isInstanceOf(ClickHouseType.TupleType.class);
    }
    ClickHouseType multiPolygon = TypeParser.parse("MultiPolygon", 10_000, 32);
    ClickHouseType level2 = ((ClickHouseType.ArrayType) multiPolygon).element();
    ClickHouseType level3 = ((ClickHouseType.ArrayType) level2).element();
    assertThat(((ClickHouseType.ArrayType) level3).element())
        .isInstanceOf(ClickHouseType.TupleType.class);
  }

  @Test
  void pointValuesRoundTripAsCoordinatePairs() {
    Column column =
        roundTripColumn("Point", List.of(1.5d, -2.5d), List.of(0.0d, 0.0d), List.of(1e308, -1e308));
    assertThat(column.objectAt(0)).isEqualTo(List.of(1.5d, -2.5d));
    assertThat(column.objectAt(1)).isEqualTo(List.of(0.0d, 0.0d));
    assertThat(column.objectAt(2)).isEqualTo(List.of(1e308, -1e308));
  }

  @Test
  void ringAndPolygonValuesRoundTripAsNestedArrays() {
    List<Object> triangle = List.of(List.of(0.0d, 0.0d), List.of(4.0d, 0.0d), List.of(0.0d, 3.0d));
    Column ring = roundTripColumn("Ring", triangle, List.of());
    assertThat(ring.objectAt(0)).isEqualTo(triangle);
    assertThat(ring.objectAt(1)).isEqualTo(List.of());

    List<Object> polygon = List.of(triangle, List.of(List.of(1.0d, 1.0d)));
    Column polygons = roundTripColumn("Polygon", polygon);
    assertThat(polygons.objectAt(0)).isEqualTo(polygon);

    List<Object> multiPolygon = List.of(polygon, List.of(triangle));
    Column multi = roundTripColumn("MultiPolygon", multiPolygon);
    assertThat(multi.objectAt(0)).isEqualTo(multiPolygon);
  }
}
