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

import io.github.orhaugh.chord.ChordTypeException;
import io.github.orhaugh.chord.codec.UnsupportedClickHouseTypeException;
import io.github.orhaugh.chord.codec.type.ClickHouseType.ArrayType;
import io.github.orhaugh.chord.codec.type.ClickHouseType.BFloat16Type;
import io.github.orhaugh.chord.codec.type.ClickHouseType.BoolType;
import io.github.orhaugh.chord.codec.type.ClickHouseType.Date32Type;
import io.github.orhaugh.chord.codec.type.ClickHouseType.DateTime64Type;
import io.github.orhaugh.chord.codec.type.ClickHouseType.DateTimeType;
import io.github.orhaugh.chord.codec.type.ClickHouseType.DateType;
import io.github.orhaugh.chord.codec.type.ClickHouseType.DecimalType;
import io.github.orhaugh.chord.codec.type.ClickHouseType.DynamicType;
import io.github.orhaugh.chord.codec.type.ClickHouseType.EnumEntry;
import io.github.orhaugh.chord.codec.type.ClickHouseType.EnumType;
import io.github.orhaugh.chord.codec.type.ClickHouseType.FixedStringType;
import io.github.orhaugh.chord.codec.type.ClickHouseType.FloatType;
import io.github.orhaugh.chord.codec.type.ClickHouseType.IntegerType;
import io.github.orhaugh.chord.codec.type.ClickHouseType.IntervalKind;
import io.github.orhaugh.chord.codec.type.ClickHouseType.IntervalType;
import io.github.orhaugh.chord.codec.type.ClickHouseType.Ipv4Type;
import io.github.orhaugh.chord.codec.type.ClickHouseType.Ipv6Type;
import io.github.orhaugh.chord.codec.type.ClickHouseType.JsonType;
import io.github.orhaugh.chord.codec.type.ClickHouseType.LowCardinalityType;
import io.github.orhaugh.chord.codec.type.ClickHouseType.MapType;
import io.github.orhaugh.chord.codec.type.ClickHouseType.NestedType;
import io.github.orhaugh.chord.codec.type.ClickHouseType.NothingType;
import io.github.orhaugh.chord.codec.type.ClickHouseType.NullableType;
import io.github.orhaugh.chord.codec.type.ClickHouseType.SimpleAggregateFunctionType;
import io.github.orhaugh.chord.codec.type.ClickHouseType.StringType;
import io.github.orhaugh.chord.codec.type.ClickHouseType.TupleElement;
import io.github.orhaugh.chord.codec.type.ClickHouseType.TupleType;
import io.github.orhaugh.chord.codec.type.ClickHouseType.UuidType;
import io.github.orhaugh.chord.codec.type.ClickHouseType.VariantType;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Parses ClickHouse type names into the {@link ClickHouseType} model.
 *
 * <p>The parser handles nested parentheses, quoted and escaped enum labels, named and back quoted
 * tuple elements, timezone parameters, decimal precision and scale, whitespace variants and
 * geometry aliases, all under configurable length and nesting limits. Unknown type names and
 * recognised but unsupported types raise {@link UnsupportedClickHouseTypeException}; malformed
 * input raises {@link ChordTypeException}. Nothing is ever guessed.
 */
public final class TypeParser {

  /** Default maximum length of a type name in characters. */
  public static final int DEFAULT_MAX_LENGTH = 262_144;

  /** Default maximum nesting depth. */
  public static final int DEFAULT_MAX_DEPTH = 64;

  private final String input;
  private final int maxDepth;
  private int position;
  private int depth;

  private TypeParser(String input, int maxDepth) {
    this.input = input;
    this.maxDepth = maxDepth;
  }

  /**
   * Parses a type name with default limits.
   *
   * @param typeName the type name as sent by the server
   * @return the parsed type
   */
  public static ClickHouseType parse(String typeName) {
    return parse(typeName, DEFAULT_MAX_LENGTH, DEFAULT_MAX_DEPTH);
  }

  /**
   * Parses a type name with explicit limits.
   *
   * @param typeName the type name as sent by the server
   * @param maxLength largest permitted name length in characters
   * @param maxDepth largest permitted nesting depth
   * @return the parsed type
   */
  public static ClickHouseType parse(String typeName, int maxLength, int maxDepth) {
    if (typeName.length() > maxLength) {
      throw new ChordTypeException(
          "Type name length " + typeName.length() + " exceeds the permitted " + maxLength);
    }
    TypeParser parser = new TypeParser(typeName, maxDepth);
    ClickHouseType type = parser.parseType();
    parser.skipWhitespace();
    if (parser.position != typeName.length()) {
      throw parser.error("unexpected trailing characters");
    }
    return type;
  }

  private ClickHouseType parseType() {
    enterDepth();
    try {
      skipWhitespace();
      String identifier = readIdentifier();
      return switch (identifier) {
        case "UInt8" -> new IntegerType(8, false);
        case "UInt16" -> new IntegerType(16, false);
        case "UInt32" -> new IntegerType(32, false);
        case "UInt64" -> new IntegerType(64, false);
        case "UInt128" -> new IntegerType(128, false);
        case "UInt256" -> new IntegerType(256, false);
        case "Int8" -> new IntegerType(8, true);
        case "Int16" -> new IntegerType(16, true);
        case "Int32" -> new IntegerType(32, true);
        case "Int64" -> new IntegerType(64, true);
        case "Int128" -> new IntegerType(128, true);
        case "Int256" -> new IntegerType(256, true);
        case "Float32" -> new FloatType(32);
        case "Float64" -> new FloatType(64);
        case "BFloat16" -> new BFloat16Type();
        case "Bool" -> new BoolType();
        case "String" -> new StringType();
        case "FixedString" -> parseFixedString();
        case "Date" -> new DateType();
        case "Date32" -> new Date32Type();
        case "DateTime" -> parseDateTime();
        case "DateTime64" -> parseDateTime64();
        case "UUID" -> new UuidType();
        case "IPv4" -> new Ipv4Type();
        case "IPv6" -> new Ipv6Type();
        case "Nothing" -> new NothingType();
        case "Decimal" -> parseDecimal();
        case "Decimal32" -> parseSizedDecimal(9);
        case "Decimal64" -> parseSizedDecimal(18);
        case "Decimal128" -> parseSizedDecimal(38);
        case "Decimal256" -> parseSizedDecimal(76);
        case "Enum8" -> parseEnum(8);
        case "Enum16" -> parseEnum(16);
        case "Enum" -> parseEnum(0);
        case "Nullable" -> new NullableType(parseSingleArgument());
        case "Array" -> new ArrayType(parseSingleArgument());
        case "LowCardinality" -> new LowCardinalityType(parseSingleArgument());
        case "Tuple" -> parseTuple();
        case "Map" -> parseMap();
        case "Nested" -> parseNested();
        case "Variant" -> parseVariant();
        case "SimpleAggregateFunction" -> parseSimpleAggregateFunction();
        case "AggregateFunction" -> new ClickHouseType.AggregateFunctionType(readRawArguments());
        case "Dynamic" -> new DynamicType(readOptionalRawArguments());
        case "JSON" -> new JsonType(readOptionalRawArguments());
        case "Point" -> point();
        case "Ring", "LineString" -> new ArrayType(point());
        case "Polygon", "MultiLineString" -> new ArrayType(new ArrayType(point()));
        case "MultiPolygon" -> new ArrayType(new ArrayType(new ArrayType(point())));
        case "Time", "Time64", "QBit", "Object" ->
            throw new UnsupportedClickHouseTypeException(
                "Type " + identifier + " is recognised but not supported by CHord yet");
        default -> parseIntervalOrFail(identifier);
      };
    } finally {
      depth--;
    }
  }

  private static ClickHouseType point() {
    return new TupleType(
        List.of(
            new TupleElement(Optional.empty(), new FloatType(64)),
            new TupleElement(Optional.empty(), new FloatType(64))));
  }

  private ClickHouseType parseIntervalOrFail(String identifier) {
    if (identifier.startsWith("Interval")) {
      String suffix = identifier.substring("Interval".length());
      for (IntervalKind kind : IntervalKind.values()) {
        if (kind.suffix().equals(suffix)) {
          return new IntervalType(kind);
        }
      }
    }
    throw new UnsupportedClickHouseTypeException(
        "Unknown or unsupported ClickHouse type name \"" + identifier + "\"");
  }

  private ClickHouseType parseFixedString() {
    expect('(');
    long length = readInteger();
    expect(')');
    if (length < 1 || length > Integer.MAX_VALUE) {
      throw error("FixedString length out of range: " + length);
    }
    return new FixedStringType((int) length);
  }

  private ClickHouseType parseDateTime() {
    if (!tryConsume('(')) {
      return new DateTimeType(Optional.empty());
    }
    skipWhitespace();
    if (tryConsume(')')) {
      return new DateTimeType(Optional.empty());
    }
    String timezone = readQuotedString('\'');
    expect(')');
    return new DateTimeType(Optional.of(timezone));
  }

  private ClickHouseType parseDateTime64() {
    expect('(');
    long precision = readInteger();
    skipWhitespace();
    Optional<String> timezone = Optional.empty();
    if (tryConsume(',')) {
      skipWhitespace();
      timezone = Optional.of(readQuotedString('\''));
    }
    expect(')');
    if (precision < 0 || precision > 9) {
      throw error("DateTime64 precision out of range: " + precision);
    }
    return new DateTime64Type((int) precision, timezone);
  }

  private ClickHouseType parseDecimal() {
    expect('(');
    long precision = readInteger();
    skipWhitespace();
    expect(',');
    long scale = readInteger();
    expect(')');
    validateDecimal(precision, scale);
    return new DecimalType((int) precision, (int) scale);
  }

  private ClickHouseType parseSizedDecimal(int precision) {
    expect('(');
    long scale = readInteger();
    expect(')');
    validateDecimal(precision, scale);
    return new DecimalType(precision, (int) scale);
  }

  private void validateDecimal(long precision, long scale) {
    if (precision < 1 || precision > 76) {
      throw error("Decimal precision out of range: " + precision);
    }
    if (scale < 0 || scale > precision) {
      throw error("Decimal scale out of range: " + scale);
    }
  }

  private ClickHouseType parseEnum(int declaredBits) {
    expect('(');
    List<EnumEntry> entries = new ArrayList<>();
    while (true) {
      skipWhitespace();
      String label = readQuotedString('\'');
      skipWhitespace();
      expect('=');
      long value = readInteger();
      entries.add(new EnumEntry(label, checkEnumValue(declaredBits, value)));
      skipWhitespace();
      if (tryConsume(')')) {
        break;
      }
      expect(',');
    }
    int bits = declaredBits;
    if (bits == 0) {
      bits = 8;
      for (EnumEntry entry : entries) {
        if (entry.value() < Byte.MIN_VALUE || entry.value() > Byte.MAX_VALUE) {
          bits = 16;
        }
      }
    }
    return new EnumType(bits, entries);
  }

  private int checkEnumValue(int declaredBits, long value) {
    long min = declaredBits == 8 ? Byte.MIN_VALUE : Short.MIN_VALUE;
    long max = declaredBits == 8 ? Byte.MAX_VALUE : Short.MAX_VALUE;
    if (declaredBits == 0) {
      min = Short.MIN_VALUE;
      max = Short.MAX_VALUE;
    }
    if (value < min || value > max) {
      throw error("Enum value out of range: " + value);
    }
    return (int) value;
  }

  private ClickHouseType parseSingleArgument() {
    expect('(');
    ClickHouseType inner = parseType();
    skipWhitespace();
    expect(')');
    return inner;
  }

  private ClickHouseType parseTuple() {
    expect('(');
    List<TupleElement> elements = new ArrayList<>();
    while (true) {
      elements.add(parseTupleElement());
      skipWhitespace();
      if (tryConsume(')')) {
        break;
      }
      expect(',');
    }
    boolean anyNamed = elements.stream().anyMatch(e -> e.elementName().isPresent());
    boolean allNamed = elements.stream().allMatch(e -> e.elementName().isPresent());
    if (anyNamed && !allNamed) {
      throw error("Tuple elements must be all named or all unnamed");
    }
    return new TupleType(elements);
  }

  private TupleElement parseTupleElement() {
    skipWhitespace();
    if (peek() == '`') {
      String elementName = readQuotedString('`');
      skipWhitespace();
      return new TupleElement(Optional.of(elementName), parseType());
    }
    // Either "name Type" or just "Type": read one identifier and decide by what follows.
    int savepoint = position;
    String first = readIdentifier();
    int afterFirst = position;
    skipWhitespace();
    boolean nameCandidate =
        position > afterFirst && position < input.length() && isIdentifierStart(peek());
    if (nameCandidate) {
      return new TupleElement(Optional.of(first), parseType());
    }
    position = savepoint;
    return new TupleElement(Optional.empty(), parseType());
  }

  private ClickHouseType parseMap() {
    expect('(');
    ClickHouseType key = parseType();
    skipWhitespace();
    expect(',');
    ClickHouseType value = parseType();
    skipWhitespace();
    expect(')');
    return new MapType(key, value);
  }

  private ClickHouseType parseNested() {
    expect('(');
    List<TupleElement> elements = new ArrayList<>();
    while (true) {
      skipWhitespace();
      String elementName = peek() == '`' ? readQuotedString('`') : readIdentifier();
      skipWhitespace();
      ClickHouseType type = parseType();
      elements.add(new TupleElement(Optional.of(elementName), type));
      skipWhitespace();
      if (tryConsume(')')) {
        break;
      }
      expect(',');
    }
    return new NestedType(elements);
  }

  private ClickHouseType parseVariant() {
    expect('(');
    List<ClickHouseType> alternatives = new ArrayList<>();
    while (true) {
      alternatives.add(parseType());
      skipWhitespace();
      if (tryConsume(')')) {
        break;
      }
      expect(',');
    }
    return new VariantType(alternatives);
  }

  private ClickHouseType parseSimpleAggregateFunction() {
    expect('(');
    skipWhitespace();
    String function = readIdentifier();
    skipWhitespace();
    expect(',');
    ClickHouseType inner = parseType();
    skipWhitespace();
    expect(')');
    return new SimpleAggregateFunctionType(function, inner);
  }

  /** Captures balanced parenthesis argument text verbatim, respecting quoted strings. */
  private String readRawArguments() {
    expect('(');
    int start = position;
    int level = 1;
    while (position < input.length()) {
      char c = input.charAt(position);
      if (c == '\'' || c == '`') {
        readQuotedString(c);
        continue;
      }
      position++;
      if (c == '(') {
        level++;
      } else if (c == ')') {
        level--;
        if (level == 0) {
          return input.substring(start, position - 1);
        }
      }
    }
    throw error("unbalanced parentheses");
  }

  private Optional<String> readOptionalRawArguments() {
    skipWhitespace();
    if (position < input.length() && peek() == '(') {
      return Optional.of(readRawArguments());
    }
    return Optional.empty();
  }

  private void enterDepth() {
    if (++depth > maxDepth) {
      throw new ChordTypeException(
          "Type nesting depth exceeds the permitted maximum of " + maxDepth);
    }
  }

  private void skipWhitespace() {
    while (position < input.length() && Character.isWhitespace(input.charAt(position))) {
      position++;
    }
  }

  private char peek() {
    if (position >= input.length()) {
      throw error("unexpected end of input");
    }
    return input.charAt(position);
  }

  private boolean tryConsume(char c) {
    skipWhitespace();
    if (position < input.length() && input.charAt(position) == c) {
      position++;
      return true;
    }
    return false;
  }

  private void expect(char c) {
    skipWhitespace();
    if (position >= input.length() || input.charAt(position) != c) {
      throw error("expected '" + c + "'");
    }
    position++;
  }

  private static boolean isIdentifierStart(char c) {
    return Character.isLetter(c) || c == '_' || c == '`';
  }

  private String readIdentifier() {
    skipWhitespace();
    int start = position;
    while (position < input.length()) {
      char c = input.charAt(position);
      if (Character.isLetterOrDigit(c) || c == '_') {
        position++;
      } else {
        break;
      }
    }
    if (position == start) {
      throw error("expected an identifier");
    }
    return input.substring(start, position);
  }

  private long readInteger() {
    skipWhitespace();
    int start = position;
    if (position < input.length() && (peek() == '-' || peek() == '+')) {
      position++;
    }
    while (position < input.length() && Character.isDigit(input.charAt(position))) {
      position++;
    }
    if (position == start) {
      throw error("expected a number");
    }
    try {
      return Long.parseLong(input, start, position, 10);
    } catch (NumberFormatException e) {
      throw error("number out of range");
    }
  }

  private String readQuotedString(char quote) {
    skipWhitespace();
    if (peek() != quote) {
      throw error("expected " + quote + " quoted string");
    }
    position++;
    StringBuilder builder = new StringBuilder();
    while (true) {
      if (position >= input.length()) {
        throw error("unterminated quoted string");
      }
      char c = input.charAt(position++);
      if (c == quote) {
        return builder.toString();
      }
      if (c == '\\') {
        if (position >= input.length()) {
          throw error("unterminated escape");
        }
        char escaped = input.charAt(position++);
        builder.append(
            switch (escaped) {
              case 'n' -> '\n';
              case 't' -> '\t';
              case 'r' -> '\r';
              case '0' -> '\0';
              case 'b' -> '\b';
              case 'f' -> '\f';
              default -> escaped;
            });
      } else {
        builder.append(c);
      }
    }
  }

  private ChordTypeException error(String message) {
    String context = input.length() <= 120 ? input : input.substring(0, 120) + "...";
    return new ChordTypeException(
        "Cannot parse type name at position "
            + position
            + ": "
            + message
            + " in \""
            + context
            + "\"");
  }
}
