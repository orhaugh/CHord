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
package io.github.orhaugh.chord.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.orhaugh.chord.codec.type.ClickHouseType;
import io.github.orhaugh.chord.codec.type.TypeParser;
import java.sql.Types;
import org.junit.jupiter.api.Test;

/** The ClickHouse to JDBC type mapping: codes, class names, precision, scale and nullability. */
class JdbcTypesTest {

  private static ClickHouseType parse(String name) {
    return TypeParser.parse(name, 10_000, 32);
  }

  @Test
  void signedIntegersMapToTheirNaturalJdbcCodes() {
    assertThat(JdbcTypes.sqlType(parse("Int8"))).isEqualTo(Types.TINYINT);
    assertThat(JdbcTypes.sqlType(parse("Int16"))).isEqualTo(Types.SMALLINT);
    assertThat(JdbcTypes.sqlType(parse("Int32"))).isEqualTo(Types.INTEGER);
    assertThat(JdbcTypes.sqlType(parse("Int64"))).isEqualTo(Types.BIGINT);
    assertThat(JdbcTypes.sqlType(parse("Int128"))).isEqualTo(Types.NUMERIC);
  }

  @Test
  void unsignedIntegersMapOneSizeUpSoEveryValueFits() {
    assertThat(JdbcTypes.sqlType(parse("UInt8"))).isEqualTo(Types.SMALLINT);
    assertThat(JdbcTypes.sqlType(parse("UInt16"))).isEqualTo(Types.INTEGER);
    assertThat(JdbcTypes.sqlType(parse("UInt32"))).isEqualTo(Types.BIGINT);
    assertThat(JdbcTypes.sqlType(parse("UInt64"))).isEqualTo(Types.NUMERIC);
    assertThat(JdbcTypes.sqlType(parse("UInt256"))).isEqualTo(Types.NUMERIC);
  }

  @Test
  void remainingTypesMapToTheirJdbcCodes() {
    assertThat(JdbcTypes.sqlType(parse("Bool"))).isEqualTo(Types.BOOLEAN);
    assertThat(JdbcTypes.sqlType(parse("Float32"))).isEqualTo(Types.REAL);
    assertThat(JdbcTypes.sqlType(parse("Float64"))).isEqualTo(Types.DOUBLE);
    assertThat(JdbcTypes.sqlType(parse("BFloat16"))).isEqualTo(Types.REAL);
    assertThat(JdbcTypes.sqlType(parse("Decimal(10, 2)"))).isEqualTo(Types.DECIMAL);
    assertThat(JdbcTypes.sqlType(parse("String"))).isEqualTo(Types.VARCHAR);
    assertThat(JdbcTypes.sqlType(parse("FixedString(4)"))).isEqualTo(Types.VARCHAR);
    assertThat(JdbcTypes.sqlType(parse("Enum8('a' = 1)"))).isEqualTo(Types.VARCHAR);
    assertThat(JdbcTypes.sqlType(parse("Date"))).isEqualTo(Types.DATE);
    assertThat(JdbcTypes.sqlType(parse("Date32"))).isEqualTo(Types.DATE);
    assertThat(JdbcTypes.sqlType(parse("DateTime"))).isEqualTo(Types.TIMESTAMP);
    assertThat(JdbcTypes.sqlType(parse("DateTime64(3)"))).isEqualTo(Types.TIMESTAMP);
    assertThat(JdbcTypes.sqlType(parse("Array(UInt8)"))).isEqualTo(Types.ARRAY);
    assertThat(JdbcTypes.sqlType(parse("Nothing"))).isEqualTo(Types.NULL);
    assertThat(JdbcTypes.sqlType(parse("UUID"))).isEqualTo(Types.OTHER);
    assertThat(JdbcTypes.sqlType(parse("Map(String, UInt8)"))).isEqualTo(Types.OTHER);
  }

  @Test
  void wrappersUnwrapBeforeMapping() {
    assertThat(JdbcTypes.sqlType(parse("Nullable(Int32)"))).isEqualTo(Types.INTEGER);
    assertThat(JdbcTypes.sqlType(parse("LowCardinality(String)"))).isEqualTo(Types.VARCHAR);
    assertThat(JdbcTypes.sqlType(parse("LowCardinality(Nullable(String))")))
        .isEqualTo(Types.VARCHAR);
    assertThat(JdbcTypes.sqlType(parse("SimpleAggregateFunction(sum, UInt64)")))
        .isEqualTo(Types.NUMERIC);

    assertThat(JdbcTypes.isNullable(parse("Nullable(Int32)"))).isTrue();
    assertThat(JdbcTypes.isNullable(parse("LowCardinality(Nullable(String))"))).isTrue();
    assertThat(JdbcTypes.isNullable(parse("LowCardinality(String)"))).isFalse();
    assertThat(JdbcTypes.isNullable(parse("Int32"))).isFalse();
  }

  @Test
  void javaClassNamesMatchWhatGetObjectYields() {
    assertThat(JdbcTypes.javaClassName(parse("Bool"))).isEqualTo("java.lang.Boolean");
    assertThat(JdbcTypes.javaClassName(parse("Int8"))).isEqualTo("java.lang.Byte");
    assertThat(JdbcTypes.javaClassName(parse("Int16"))).isEqualTo("java.lang.Short");
    assertThat(JdbcTypes.javaClassName(parse("Int32"))).isEqualTo("java.lang.Integer");
    assertThat(JdbcTypes.javaClassName(parse("Int64"))).isEqualTo("java.lang.Long");
    assertThat(JdbcTypes.javaClassName(parse("UInt8"))).isEqualTo("java.lang.Integer");
    assertThat(JdbcTypes.javaClassName(parse("UInt16"))).isEqualTo("java.lang.Integer");
    assertThat(JdbcTypes.javaClassName(parse("UInt32"))).isEqualTo("java.lang.Long");
    assertThat(JdbcTypes.javaClassName(parse("UInt64"))).isEqualTo("java.math.BigInteger");
    assertThat(JdbcTypes.javaClassName(parse("Int128"))).isEqualTo("java.math.BigInteger");
    assertThat(JdbcTypes.javaClassName(parse("Float32"))).isEqualTo("java.lang.Float");
    assertThat(JdbcTypes.javaClassName(parse("Float64"))).isEqualTo("java.lang.Double");
    assertThat(JdbcTypes.javaClassName(parse("Decimal(10, 2)"))).isEqualTo("java.math.BigDecimal");
    assertThat(JdbcTypes.javaClassName(parse("String"))).isEqualTo("java.lang.String");
    assertThat(JdbcTypes.javaClassName(parse("Date"))).isEqualTo("java.time.LocalDate");
    assertThat(JdbcTypes.javaClassName(parse("DateTime"))).isEqualTo("java.time.Instant");
    assertThat(JdbcTypes.javaClassName(parse("UUID"))).isEqualTo("java.util.UUID");
    assertThat(JdbcTypes.javaClassName(parse("Array(UInt8)"))).isEqualTo("java.util.List");
    assertThat(JdbcTypes.javaClassName(parse("Map(String, UInt8)"))).isEqualTo("java.util.Map");
    assertThat(JdbcTypes.javaClassName(parse("Variant(Int64, String)")))
        .isEqualTo("java.lang.Object");
  }

  @Test
  void precisionAndScaleComeFromTheDeclaredType() {
    assertThat(JdbcTypes.precision(parse("Decimal(10, 2)"))).isEqualTo(10);
    assertThat(JdbcTypes.scale(parse("Decimal(10, 2)"))).isEqualTo(2);
    assertThat(JdbcTypes.precision(parse("FixedString(16)"))).isEqualTo(16);
    assertThat(JdbcTypes.precision(parse("Int32"))).isEqualTo(10);
    assertThat(JdbcTypes.precision(parse("Int64"))).isEqualTo(20);
    assertThat(JdbcTypes.precision(parse("Int128"))).isEqualTo(39);
    assertThat(JdbcTypes.scale(parse("String"))).isZero();
  }
}
