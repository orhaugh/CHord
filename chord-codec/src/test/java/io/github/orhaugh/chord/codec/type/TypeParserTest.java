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
package io.github.orhaugh.chord.codec.type;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.orhaugh.chord.ChordTypeException;
import io.github.orhaugh.chord.codec.UnsupportedClickHouseTypeException;
import io.github.orhaugh.chord.codec.type.ClickHouseType.DateTime64Type;
import io.github.orhaugh.chord.codec.type.ClickHouseType.DecimalType;
import io.github.orhaugh.chord.codec.type.ClickHouseType.EnumType;
import io.github.orhaugh.chord.codec.type.ClickHouseType.MapType;
import io.github.orhaugh.chord.codec.type.ClickHouseType.TupleType;
import org.junit.jupiter.api.Test;

/** Parsing of server emitted type names, including malformed and hostile input. */
class TypeParserTest {

  private static ClickHouseType parsed(String name) {
    return TypeParser.parse(name);
  }

  @Test
  void parsesSimpleTypesAndRendersCanonicalNames() {
    for (String name :
        new String[] {
          "UInt8",
          "UInt16",
          "UInt32",
          "UInt64",
          "UInt128",
          "UInt256",
          "Int8",
          "Int16",
          "Int32",
          "Int64",
          "Int128",
          "Int256",
          "Float32",
          "Float64",
          "BFloat16",
          "Bool",
          "String",
          "Date",
          "Date32",
          "UUID",
          "IPv4",
          "IPv6",
          "Nothing",
          "DateTime",
          "IntervalSecond",
          "IntervalYear",
          "Dynamic",
          "JSON"
        }) {
      assertThat(parsed(name).name()).isEqualTo(name);
    }
  }

  @Test
  void parsesParameterisedTemporalTypes() {
    assertThat(parsed("DateTime('Europe/London')").name()).isEqualTo("DateTime('Europe/London')");
    DateTime64Type dt64 = (DateTime64Type) parsed("DateTime64(3, 'UTC')");
    assertThat(dt64.precision()).isEqualTo(3);
    assertThat(dt64.timezone()).contains("UTC");
    assertThat(parsed("DateTime64(9)").name()).isEqualTo("DateTime64(9)");
    assertThat(parsed("DateTime64( 6 , 'Asia/Tokyo' )").name())
        .isEqualTo("DateTime64(6, 'Asia/Tokyo')");
  }

  @Test
  void parsesDecimalFamilies() {
    DecimalType decimal = (DecimalType) parsed("Decimal(38, 10)");
    assertThat(decimal.precision()).isEqualTo(38);
    assertThat(decimal.scale()).isEqualTo(10);
    assertThat(decimal.storageBits()).isEqualTo(128);
    assertThat(((DecimalType) parsed("Decimal32(4)")).storageBits()).isEqualTo(32);
    assertThat(((DecimalType) parsed("Decimal64(4)")).storageBits()).isEqualTo(64);
    assertThat(((DecimalType) parsed("Decimal128(20)")).storageBits()).isEqualTo(128);
    assertThat(((DecimalType) parsed("Decimal256(40)")).storageBits()).isEqualTo(256);
    assertThat(parsed("Decimal(9, 4)").name()).isEqualTo("Decimal(9, 4)");
  }

  @Test
  void parsesEnumsWithQuotedAndEscapedLabels() {
    EnumType enum8 = (EnumType) parsed("Enum8('a' = 1, 'b' = 2)");
    assertThat(enum8.bits()).isEqualTo(8);
    assertThat(enum8.labelFor(2)).isEqualTo("b");

    EnumType escaped = (EnumType) parsed("Enum8('it\\'s' = 1, 'a\\\\b' = -2, 'tab\\t' = 3)");
    assertThat(escaped.labelFor(1)).isEqualTo("it's");
    assertThat(escaped.labelFor(-2)).isEqualTo("a\\b");
    assertThat(escaped.labelFor(3)).isEqualTo("tab\t");

    EnumType auto = (EnumType) parsed("Enum('x' = 1000)");
    assertThat(auto.bits()).isEqualTo(16);
  }

  @Test
  void parsesCompositesRecursively() {
    ClickHouseType type = parsed("Map(String, Array(Nullable(DateTime64(3, 'UTC'))))");
    assertThat(type.name()).isEqualTo("Map(String, Array(Nullable(DateTime64(3, 'UTC'))))");
    MapType map = (MapType) type;
    assertThat(map.key().name()).isEqualTo("String");
  }

  @Test
  void parsesNamedAndUnnamedTuples() {
    TupleType unnamed = (TupleType) parsed("Tuple(UInt8, String)");
    assertThat(unnamed.named()).isFalse();
    assertThat(unnamed.name()).isEqualTo("Tuple(UInt8, String)");

    TupleType named = (TupleType) parsed("Tuple(id UInt64, name String)");
    assertThat(named.named()).isTrue();
    assertThat(named.elements().get(0).elementName()).contains("id");
    assertThat(named.name()).isEqualTo("Tuple(id UInt64, name String)");

    TupleType quoted = (TupleType) parsed("Tuple(`weird name` UInt8, plain String)");
    assertThat(quoted.elements().get(0).elementName()).contains("weird name");
    assertThat(quoted.name()).isEqualTo("Tuple(`weird name` UInt8, plain String)");
  }

  @Test
  void parsesNestedAndAggregateForms() {
    assertThat(parsed("Nested(a UInt8, b String)").name()).isEqualTo("Nested(a UInt8, b String)");
    assertThat(parsed("SimpleAggregateFunction(sum, UInt64)").name())
        .isEqualTo("SimpleAggregateFunction(sum, UInt64)");
    assertThat(parsed("AggregateFunction(quantiles(0.5, 0.9), Float64)").name())
        .isEqualTo("AggregateFunction(quantiles(0.5, 0.9), Float64)");
    assertThat(parsed("Variant(String, UInt64)").name()).isEqualTo("Variant(String, UInt64)");
    assertThat(parsed("Dynamic(max_types=32)").name()).isEqualTo("Dynamic(max_types=32)");
    assertThat(parsed("JSON(max_dynamic_paths=100, a.b UInt32)").name())
        .isEqualTo("JSON(max_dynamic_paths=100, a.b UInt32)");
  }

  @Test
  void expandsGeometryAliases() {
    assertThat(parsed("Point").name()).isEqualTo("Tuple(Float64, Float64)");
    assertThat(parsed("Ring").name()).isEqualTo("Array(Tuple(Float64, Float64))");
    assertThat(parsed("Polygon").name()).isEqualTo("Array(Array(Tuple(Float64, Float64)))");
    assertThat(parsed("MultiPolygon").name())
        .isEqualTo("Array(Array(Array(Tuple(Float64, Float64))))");
  }

  @Test
  void parsesLowCardinalityAndFixedString() {
    assertThat(parsed("LowCardinality(String)").name()).isEqualTo("LowCardinality(String)");
    assertThat(parsed("FixedString(16)").name()).isEqualTo("FixedString(16)");
  }

  @Test
  void rejectsUnknownTypeNames() {
    assertThatThrownBy(() -> parsed("Mystery"))
        .isInstanceOf(UnsupportedClickHouseTypeException.class)
        .hasMessageContaining("Mystery");
    assertThatThrownBy(() -> parsed("IntervalFortnight"))
        .isInstanceOf(UnsupportedClickHouseTypeException.class);
  }

  @Test
  void rejectsRecognisedButUnsupportedTypes() {
    for (String name : new String[] {"QBit", "Object"}) {
      assertThatThrownBy(() -> parsed(name))
          .isInstanceOf(UnsupportedClickHouseTypeException.class)
          .hasMessageContaining("not supported");
    }
  }

  @Test
  void parsesTimeTypes() {
    assertThat(parsed("Time").name()).isEqualTo("Time");
    assertThat(parsed("Time64(3)").name()).isEqualTo("Time64(3)");
    assertThat(parsed("Time64(9)").name()).isEqualTo("Time64(9)");
    assertThatThrownBy(() -> parsed("Time64(10)")).hasMessageContaining("precision");
  }

  @Test
  void rejectsMalformedInput() {
    for (String bad :
        new String[] {
          "Array(",
          "Array(String",
          "Tuple()",
          "Map(String)",
          "Enum8('a')",
          "Enum8('a' = )",
          "Enum8(a = 1)",
          "FixedString(0)",
          "FixedString(-3)",
          "DateTime64(11)",
          "Decimal(80, 2)",
          "Decimal(5, 9)",
          "Nullable(String) trailing",
          "Enum8('a' = 200)",
          "String)",
          ""
        }) {
      assertThatThrownBy(() -> parsed(bad))
          .as("input %s", bad)
          .isInstanceOf(ChordTypeException.class);
    }
  }

  @Test
  void enforcesNestingDepthLimit() {
    String deep = "Array(".repeat(80) + "UInt8" + ")".repeat(80);
    assertThatThrownBy(() -> parsed(deep))
        .isInstanceOf(ChordTypeException.class)
        .hasMessageContaining("depth");
    // The same shape parses when it is within the limit.
    String ok = "Array(".repeat(20) + "UInt8" + ")".repeat(20);
    assertThat(parsed(ok).name()).isEqualTo(ok.replace("(", "(").trim());
  }

  @Test
  void enforcesLengthLimit() {
    assertThatThrownBy(() -> TypeParser.parse("UInt8", 3, 8))
        .isInstanceOf(ChordTypeException.class)
        .hasMessageContaining("length");
  }
}
