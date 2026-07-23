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

import io.github.orhaugh.chord.codec.UnsupportedClickHouseTypeException;
import io.github.orhaugh.chord.codec.type.ClickHouseType;
import io.github.orhaugh.chord.codec.type.ClickHouseType.ArrayType;
import io.github.orhaugh.chord.codec.type.ClickHouseType.BFloat16Type;
import io.github.orhaugh.chord.codec.type.ClickHouseType.BoolType;
import io.github.orhaugh.chord.codec.type.ClickHouseType.Date32Type;
import io.github.orhaugh.chord.codec.type.ClickHouseType.DateTime64Type;
import io.github.orhaugh.chord.codec.type.ClickHouseType.DateTimeType;
import io.github.orhaugh.chord.codec.type.ClickHouseType.DateType;
import io.github.orhaugh.chord.codec.type.ClickHouseType.DecimalType;
import io.github.orhaugh.chord.codec.type.ClickHouseType.EnumEntry;
import io.github.orhaugh.chord.codec.type.ClickHouseType.EnumType;
import io.github.orhaugh.chord.codec.type.ClickHouseType.FixedStringType;
import io.github.orhaugh.chord.codec.type.ClickHouseType.FloatType;
import io.github.orhaugh.chord.codec.type.ClickHouseType.IntegerType;
import io.github.orhaugh.chord.codec.type.ClickHouseType.IntervalType;
import io.github.orhaugh.chord.codec.type.ClickHouseType.Ipv4Type;
import io.github.orhaugh.chord.codec.type.ClickHouseType.Ipv6Type;
import io.github.orhaugh.chord.codec.type.ClickHouseType.LowCardinalityType;
import io.github.orhaugh.chord.codec.type.ClickHouseType.MapType;
import io.github.orhaugh.chord.codec.type.ClickHouseType.NullableType;
import io.github.orhaugh.chord.codec.type.ClickHouseType.SimpleAggregateFunctionType;
import io.github.orhaugh.chord.codec.type.ClickHouseType.StringType;
import io.github.orhaugh.chord.codec.type.ClickHouseType.TupleElement;
import io.github.orhaugh.chord.codec.type.ClickHouseType.TupleType;
import io.github.orhaugh.chord.codec.type.ClickHouseType.UuidType;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Default values per type, as the server defines them: the value a sparse column stores implicitly
 * for its unlisted rows.
 */
final class Defaults {

  private Defaults() {}

  /**
   * Returns the default value for a type, in a representation the block builder accepts.
   *
   * @param type the column type
   * @return the default value, possibly {@code null} for Nullable
   */
  static Object of(ClickHouseType type) {
    return switch (type) {
      case NullableType t -> null;
      case IntegerType t -> 0L;
      case FloatType t -> 0d;
      case BFloat16Type t -> 0f;
      case BoolType t -> false;
      case StringType t -> "";
      case FixedStringType t -> new byte[t.length()];
      case DateType t -> LocalDate.ofEpochDay(0);
      case Date32Type t -> LocalDate.ofEpochDay(0);
      case DateTimeType t -> Instant.EPOCH;
      case DateTime64Type t -> Instant.EPOCH;
      case ClickHouseType.TimeType t -> java.time.Duration.ZERO;
      case ClickHouseType.Time64Type t -> java.time.Duration.ZERO;
      case UuidType t -> new UUID(0, 0);
      case Ipv4Type t -> zeroAddress(4);
      case Ipv6Type t -> zeroAddress(16);
      case DecimalType t -> BigDecimal.ZERO;
      case IntervalType t -> 0L;
      case EnumType t -> zeroEnumLabel(t);
      case ArrayType t -> List.of();
      case MapType t -> Map.of();
      case TupleType t -> {
        List<Object> elements = new ArrayList<>(t.elements().size());
        for (TupleElement element : t.elements()) {
          elements.add(of(element.type()));
        }
        yield elements;
      }
      case LowCardinalityType t -> of(t.inner());
      case SimpleAggregateFunctionType t -> of(t.inner());
      default ->
          throw new UnsupportedClickHouseTypeException(
              "No default value is defined for sparse columns of type " + type.name());
    };
  }

  /** The all zeros address, in the {@link java.net.InetAddress} form the IP appenders accept. */
  private static java.net.InetAddress zeroAddress(int length) {
    try {
      return java.net.InetAddress.getByAddress(new byte[length]);
    } catch (java.net.UnknownHostException e) {
      throw new AssertionError("A " + length + " byte address literal cannot be malformed", e);
    }
  }

  private static String zeroEnumLabel(EnumType type) {
    for (EnumEntry entry : type.entries()) {
      if (entry.value() == 0) {
        return entry.label();
      }
    }
    throw new UnsupportedClickHouseTypeException(
        "Sparse enum column without a zero valued label cannot be materialised: " + type.name());
  }
}
