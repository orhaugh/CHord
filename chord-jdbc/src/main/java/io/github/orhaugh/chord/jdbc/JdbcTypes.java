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

import io.github.orhaugh.chord.codec.type.ClickHouseType;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Types;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The mapping from ClickHouse types to {@link java.sql.Types} codes and Java classes, applied after
 * unwrapping Nullable, LowCardinality and SimpleAggregateFunction.
 */
final class JdbcTypes {

  private JdbcTypes() {}

  /** Strips wrappers that do not change the value domain a JDBC caller sees. */
  static ClickHouseType unwrap(ClickHouseType type) {
    ClickHouseType current = type;
    while (true) {
      switch (current) {
        case ClickHouseType.NullableType t -> current = t.inner();
        case ClickHouseType.LowCardinalityType t -> current = t.inner();
        case ClickHouseType.SimpleAggregateFunctionType t -> current = t.inner();
        default -> {
          return current;
        }
      }
    }
  }

  static boolean isNullable(ClickHouseType type) {
    ClickHouseType current = type;
    while (true) {
      switch (current) {
        case ClickHouseType.NullableType t -> {
          return true;
        }
        case ClickHouseType.LowCardinalityType t -> current = t.inner();
        case ClickHouseType.SimpleAggregateFunctionType t -> current = t.inner();
        default -> {
          return false;
        }
      }
    }
  }

  /** Returns the {@link Types} code for a column type. */
  static int sqlType(ClickHouseType type) {
    return switch (unwrap(type)) {
      case ClickHouseType.BoolType t -> Types.BOOLEAN;
      case ClickHouseType.IntegerType t -> integerSqlType(t);
      case ClickHouseType.FloatType t -> t.bits() == 32 ? Types.REAL : Types.DOUBLE;
      case ClickHouseType.BFloat16Type t -> Types.REAL;
      case ClickHouseType.DecimalType t -> Types.DECIMAL;
      case ClickHouseType.StringType t -> Types.VARCHAR;
      case ClickHouseType.FixedStringType t -> Types.VARCHAR;
      case ClickHouseType.EnumType t -> Types.VARCHAR;
      case ClickHouseType.DateType t -> Types.DATE;
      case ClickHouseType.Date32Type t -> Types.DATE;
      case ClickHouseType.DateTimeType t -> Types.TIMESTAMP;
      case ClickHouseType.DateTime64Type t -> Types.TIMESTAMP;
      case ClickHouseType.ArrayType t -> Types.ARRAY;
      case ClickHouseType.NothingType t -> Types.NULL;
      default -> Types.OTHER;
    };
  }

  private static int integerSqlType(ClickHouseType.IntegerType type) {
    if (!type.signed()) {
      // Unsigned types map one size up so every value fits the Java carrier; UInt64 and wider
      // have no primitive carrier and surface as NUMERIC BigIntegers.
      return switch (type.bits()) {
        case 8 -> Types.SMALLINT;
        case 16 -> Types.INTEGER;
        case 32 -> Types.BIGINT;
        default -> Types.NUMERIC;
      };
    }
    return switch (type.bits()) {
      case 8 -> Types.TINYINT;
      case 16 -> Types.SMALLINT;
      case 32 -> Types.INTEGER;
      case 64 -> Types.BIGINT;
      default -> Types.NUMERIC;
    };
  }

  /** Returns the class name {@code getObject} yields for a column type. */
  static String javaClassName(ClickHouseType type) {
    return switch (unwrap(type)) {
      case ClickHouseType.BoolType t -> Boolean.class.getName();
      case ClickHouseType.IntegerType t -> integerClassName(t);
      case ClickHouseType.FloatType t ->
          t.bits() == 32 ? Float.class.getName() : Double.class.getName();
      case ClickHouseType.BFloat16Type t -> Float.class.getName();
      case ClickHouseType.DecimalType t -> BigDecimal.class.getName();
      case ClickHouseType.StringType t -> String.class.getName();
      case ClickHouseType.FixedStringType t -> String.class.getName();
      case ClickHouseType.EnumType t -> String.class.getName();
      case ClickHouseType.DateType t -> java.time.LocalDate.class.getName();
      case ClickHouseType.Date32Type t -> java.time.LocalDate.class.getName();
      case ClickHouseType.DateTimeType t -> java.time.Instant.class.getName();
      case ClickHouseType.DateTime64Type t -> java.time.Instant.class.getName();
      case ClickHouseType.UuidType t -> UUID.class.getName();
      case ClickHouseType.ArrayType t -> List.class.getName();
      case ClickHouseType.MapType t -> Map.class.getName();
      default -> Object.class.getName();
    };
  }

  private static String integerClassName(ClickHouseType.IntegerType type) {
    if (type.bits() > 64 || (!type.signed() && type.bits() == 64)) {
      return BigInteger.class.getName();
    }
    if (!type.signed()) {
      return switch (type.bits()) {
        case 8, 16 -> Integer.class.getName();
        default -> Long.class.getName();
      };
    }
    return switch (type.bits()) {
      case 8 -> Byte.class.getName();
      case 16 -> Short.class.getName();
      case 32 -> Integer.class.getName();
      default -> Long.class.getName();
    };
  }

  /** Returns the decimal precision, or string length bound, JDBC metadata reports. */
  static int precision(ClickHouseType type) {
    return switch (unwrap(type)) {
      case ClickHouseType.DecimalType t -> t.precision();
      case ClickHouseType.FixedStringType t -> t.length();
      case ClickHouseType.IntegerType t -> t.signed() ? digitsFor(t.bits()) : digitsFor(t.bits());
      default -> 0;
    };
  }

  private static int digitsFor(int bits) {
    return switch (bits) {
      case 8 -> 3;
      case 16 -> 5;
      case 32 -> 10;
      case 64 -> 20;
      case 128 -> 39;
      default -> 78;
    };
  }

  static int scale(ClickHouseType type) {
    return unwrap(type) instanceof ClickHouseType.DecimalType t ? t.scale() : 0;
  }
}
