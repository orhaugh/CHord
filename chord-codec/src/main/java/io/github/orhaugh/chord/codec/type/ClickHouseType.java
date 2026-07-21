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

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The recursive model of ClickHouse types, produced by {@link TypeParser} from the type names the
 * server sends in native blocks. Every variant renders its canonical name through {@link #name()};
 * parsing then rendering is loss free for supported types.
 *
 * <p>Presence in this model means the type is recognised; whether its values can be decoded is the
 * codec layer's decision, tracked in {@code docs/type-support.md}. Recognised but not yet decodable
 * types (for example {@code LowCardinality} until Phase 6) fail explicitly at decode time, never by
 * guessing sizes.
 */
public sealed interface ClickHouseType {

  /**
   * Returns the canonical ClickHouse name of this type, for example {@code Map(String,
   * Array(Nullable(Int64)))}.
   *
   * @return the canonical type name
   */
  String name();

  /** Fixed width integers, signed and unsigned, 8 to 256 bits. */
  record IntegerType(int bits, boolean signed) implements ClickHouseType {
    /** Validates the width. */
    public IntegerType {
      if (bits != 8 && bits != 16 && bits != 32 && bits != 64 && bits != 128 && bits != 256) {
        throw new IllegalArgumentException("unsupported integer width " + bits);
      }
    }

    @Override
    public String name() {
      return (signed ? "Int" : "UInt") + bits;
    }
  }

  /** IEEE 754 floats, 32 or 64 bits. */
  record FloatType(int bits) implements ClickHouseType {
    /** Validates the width. */
    public FloatType {
      if (bits != 32 && bits != 64) {
        throw new IllegalArgumentException("unsupported float width " + bits);
      }
    }

    @Override
    public String name() {
      return "Float" + bits;
    }
  }

  /** The bfloat16 truncated float. */
  record BFloat16Type() implements ClickHouseType {
    @Override
    public String name() {
      return "BFloat16";
    }
  }

  /** Bool, stored as one byte. */
  record BoolType() implements ClickHouseType {
    @Override
    public String name() {
      return "Bool";
    }
  }

  /** Variable length byte string. */
  record StringType() implements ClickHouseType {
    @Override
    public String name() {
      return "String";
    }
  }

  /** Fixed length byte string. */
  record FixedStringType(int length) implements ClickHouseType {
    /** Validates the length. */
    public FixedStringType {
      if (length <= 0) {
        throw new IllegalArgumentException("FixedString length must be positive");
      }
    }

    @Override
    public String name() {
      return "FixedString(" + length + ")";
    }
  }

  /** Date as unsigned days since the epoch, 16 bits. */
  record DateType() implements ClickHouseType {
    @Override
    public String name() {
      return "Date";
    }
  }

  /** Date as signed days since the epoch, 32 bits. */
  record Date32Type() implements ClickHouseType {
    @Override
    public String name() {
      return "Date32";
    }
  }

  /** DateTime with second precision and optional column timezone. */
  record DateTimeType(Optional<String> timezone) implements ClickHouseType {
    /** Validates components. */
    public DateTimeType {
      Objects.requireNonNull(timezone, "timezone");
    }

    @Override
    public String name() {
      return timezone.map(tz -> "DateTime('" + tz + "')").orElse("DateTime");
    }
  }

  /** DateTime64 with sub second precision and optional column timezone. */
  record DateTime64Type(int precision, Optional<String> timezone) implements ClickHouseType {
    /** Validates components. */
    public DateTime64Type {
      if (precision < 0 || precision > 9) {
        throw new IllegalArgumentException("DateTime64 precision must be 0..9");
      }
      Objects.requireNonNull(timezone, "timezone");
    }

    @Override
    public String name() {
      return timezone
          .map(tz -> "DateTime64(" + precision + ", '" + tz + "')")
          .orElse("DateTime64(" + precision + ")");
    }
  }

  /** Interval of a fixed kind, stored as Int64. */
  record IntervalType(IntervalKind kind) implements ClickHouseType {
    /** Validates components. */
    public IntervalType {
      Objects.requireNonNull(kind, "kind");
    }

    @Override
    public String name() {
      return "Interval" + kind.suffix();
    }
  }

  /** The kinds of {@link IntervalType}. */
  enum IntervalKind {
    /** Nanosecond interval. */
    NANOSECOND("Nanosecond"),
    /** Microsecond interval. */
    MICROSECOND("Microsecond"),
    /** Millisecond interval. */
    MILLISECOND("Millisecond"),
    /** Second interval. */
    SECOND("Second"),
    /** Minute interval. */
    MINUTE("Minute"),
    /** Hour interval. */
    HOUR("Hour"),
    /** Day interval. */
    DAY("Day"),
    /** Week interval. */
    WEEK("Week"),
    /** Month interval. */
    MONTH("Month"),
    /** Quarter interval. */
    QUARTER("Quarter"),
    /** Year interval. */
    YEAR("Year");

    private final String suffix;

    IntervalKind(String suffix) {
      this.suffix = suffix;
    }

    String suffix() {
      return suffix;
    }
  }

  /** UUID, stored as two little endian 64 bit halves. */
  record UuidType() implements ClickHouseType {
    @Override
    public String name() {
      return "UUID";
    }
  }

  /** IPv4, stored as UInt32. */
  record Ipv4Type() implements ClickHouseType {
    @Override
    public String name() {
      return "IPv4";
    }
  }

  /** IPv6, stored as sixteen network order bytes. */
  record Ipv6Type() implements ClickHouseType {
    @Override
    public String name() {
      return "IPv6";
    }
  }

  /** One label of an enum type. */
  record EnumEntry(String label, int value) {
    /** Validates components. */
    public EnumEntry {
      Objects.requireNonNull(label, "label");
    }
  }

  /** Enum8 or Enum16 with its label table. */
  record EnumType(int bits, List<EnumEntry> entries) implements ClickHouseType {
    /** Validates components and copies the entry list. */
    public EnumType {
      if (bits != 8 && bits != 16) {
        throw new IllegalArgumentException("Enum width must be 8 or 16");
      }
      entries = List.copyOf(entries);
      if (entries.isEmpty()) {
        throw new IllegalArgumentException("Enum must have at least one entry");
      }
    }

    /**
     * Resolves a wire value to its label.
     *
     * @param value the numeric value read from the wire
     * @return the label
     */
    public String labelFor(int value) {
      for (EnumEntry entry : entries) {
        if (entry.value() == value) {
          return entry.label();
        }
      }
      throw new io.github.orhaugh.chord.ChordTypeException(
          "Value " + value + " has no label in " + name());
    }

    @Override
    public String name() {
      StringBuilder builder = new StringBuilder("Enum").append(bits).append('(');
      for (int i = 0; i < entries.size(); i++) {
        if (i > 0) {
          builder.append(", ");
        }
        builder
            .append('\'')
            .append(TypeNames.escape(entries.get(i).label()))
            .append("' = ")
            .append(entries.get(i).value());
      }
      return builder.append(')').toString();
    }
  }

  /** Decimal with explicit precision and scale; storage width follows the precision. */
  record DecimalType(int precision, int scale) implements ClickHouseType {
    /** Validates precision and scale. */
    public DecimalType {
      if (precision < 1 || precision > 76) {
        throw new IllegalArgumentException("Decimal precision must be 1..76");
      }
      if (scale < 0 || scale > precision) {
        throw new IllegalArgumentException("Decimal scale must be 0..precision");
      }
    }

    /**
     * Returns the storage width in bits: 32, 64, 128 or 256 depending on precision.
     *
     * @return the storage width
     */
    public int storageBits() {
      if (precision <= 9) {
        return 32;
      }
      if (precision <= 18) {
        return 64;
      }
      if (precision <= 38) {
        return 128;
      }
      return 256;
    }

    @Override
    public String name() {
      return "Decimal(" + precision + ", " + scale + ")";
    }
  }

  /** The Nothing type; carries no values but occupies one filler byte per row. */
  record NothingType() implements ClickHouseType {
    @Override
    public String name() {
      return "Nothing";
    }
  }

  /** Nullable wrapper: a null map followed by the nested column. */
  record NullableType(ClickHouseType inner) implements ClickHouseType {
    /** Validates components. */
    public NullableType {
      Objects.requireNonNull(inner, "inner");
    }

    @Override
    public String name() {
      return "Nullable(" + inner.name() + ")";
    }
  }

  /** Array: cumulative offsets followed by the flattened element column. */
  record ArrayType(ClickHouseType element) implements ClickHouseType {
    /** Validates components. */
    public ArrayType {
      Objects.requireNonNull(element, "element");
    }

    @Override
    public String name() {
      return "Array(" + element.name() + ")";
    }
  }

  /** One element of a tuple, optionally named. */
  record TupleElement(Optional<String> elementName, ClickHouseType type) {
    /** Validates components. */
    public TupleElement {
      Objects.requireNonNull(elementName, "elementName");
      Objects.requireNonNull(type, "type");
    }
  }

  /** Tuple of element columns, either all named or all unnamed. */
  record TupleType(List<TupleElement> elements) implements ClickHouseType {
    /** Validates components and copies the element list. */
    public TupleType {
      elements = List.copyOf(elements);
      if (elements.isEmpty()) {
        throw new IllegalArgumentException("Tuple must have at least one element");
      }
    }

    /**
     * Reports whether the tuple elements carry names.
     *
     * @return {@code true} for named tuples
     */
    public boolean named() {
      return elements.get(0).elementName().isPresent();
    }

    @Override
    public String name() {
      StringBuilder builder = new StringBuilder("Tuple(");
      for (int i = 0; i < elements.size(); i++) {
        if (i > 0) {
          builder.append(", ");
        }
        TupleElement element = elements.get(i);
        element
            .elementName()
            .ifPresent(n -> builder.append(TypeNames.maybeQuoteIdentifier(n)).append(' '));
        builder.append(element.type().name());
      }
      return builder.append(')').toString();
    }
  }

  /** Map, serialised as offsets plus flattened key and value columns. */
  record MapType(ClickHouseType key, ClickHouseType value) implements ClickHouseType {
    /** Validates components. */
    public MapType {
      Objects.requireNonNull(key, "key");
      Objects.requireNonNull(value, "value");
    }

    @Override
    public String name() {
      return "Map(" + key.name() + ", " + value.name() + ")";
    }
  }

  /** LowCardinality wrapper. Recognised now; decoding arrives in Phase 6. */
  record LowCardinalityType(ClickHouseType inner) implements ClickHouseType {
    /** Validates components. */
    public LowCardinalityType {
      Objects.requireNonNull(inner, "inner");
    }

    @Override
    public String name() {
      return "LowCardinality(" + inner.name() + ")";
    }
  }

  /** Nested table type. Appears in schemas; SELECT results flatten it into arrays. */
  record NestedType(List<TupleElement> elements) implements ClickHouseType {
    /** Validates components and copies the element list. */
    public NestedType {
      elements = List.copyOf(elements);
      if (elements.isEmpty()) {
        throw new IllegalArgumentException("Nested must have at least one element");
      }
    }

    @Override
    public String name() {
      StringBuilder builder = new StringBuilder("Nested(");
      for (int i = 0; i < elements.size(); i++) {
        if (i > 0) {
          builder.append(", ");
        }
        TupleElement element = elements.get(i);
        builder
            .append(TypeNames.maybeQuoteIdentifier(element.elementName().orElseThrow()))
            .append(' ')
            .append(element.type().name());
      }
      return builder.append(')').toString();
    }
  }

  /** SimpleAggregateFunction; values serialise exactly as the inner type. */
  record SimpleAggregateFunctionType(String function, ClickHouseType inner)
      implements ClickHouseType {
    /** Validates components. */
    public SimpleAggregateFunctionType {
      Objects.requireNonNull(function, "function");
      Objects.requireNonNull(inner, "inner");
    }

    @Override
    public String name() {
      return "SimpleAggregateFunction(" + function + ", " + inner.name() + ")";
    }
  }

  /**
   * AggregateFunction state. The argument text is kept verbatim; states are opaque and not
   * decodable.
   */
  record AggregateFunctionType(String rawArguments) implements ClickHouseType {
    /** Validates components. */
    public AggregateFunctionType {
      Objects.requireNonNull(rawArguments, "rawArguments");
    }

    @Override
    public String name() {
      return "AggregateFunction(" + rawArguments + ")";
    }
  }

  /** Variant of alternatives. Recognised now; decoding arrives in Phase 6. */
  record VariantType(List<ClickHouseType> alternatives) implements ClickHouseType {
    /** Validates components and copies the list. */
    public VariantType {
      alternatives = List.copyOf(alternatives);
      if (alternatives.isEmpty()) {
        throw new IllegalArgumentException("Variant must have at least one alternative");
      }
    }

    @Override
    public String name() {
      StringBuilder builder = new StringBuilder("Variant(");
      for (int i = 0; i < alternatives.size(); i++) {
        if (i > 0) {
          builder.append(", ");
        }
        builder.append(alternatives.get(i).name());
      }
      return builder.append(')').toString();
    }
  }

  /** Dynamic type. Recognised now; decoding arrives in Phase 6. */
  record DynamicType(Optional<String> rawArguments) implements ClickHouseType {
    /** Validates components. */
    public DynamicType {
      Objects.requireNonNull(rawArguments, "rawArguments");
    }

    @Override
    public String name() {
      return rawArguments.map(a -> "Dynamic(" + a + ")").orElse("Dynamic");
    }
  }

  /** JSON type with its raw parameter text. Recognised now; decoding arrives in Phase 6. */
  record JsonType(Optional<String> rawArguments) implements ClickHouseType {
    /** Validates components. */
    public JsonType {
      Objects.requireNonNull(rawArguments, "rawArguments");
    }

    @Override
    public String name() {
      return rawArguments.map(a -> "JSON(" + a + ")").orElse("JSON");
    }
  }
}
