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

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SQL text utilities for the prepared statement: locating {@code ?} placeholders outside strings,
 * identifiers and comments, rendering bound values as ClickHouse literals, and recognising the
 * batched INSERT shape that routes through native blocks.
 */
final class SqlText {

  private SqlText() {}

  /**
   * Splits SQL at its {@code ?} placeholders. The returned list holds the fragments between
   * placeholders: for {@code n} placeholders there are {@code n + 1} fragments.
   */
  static List<String> splitAtPlaceholders(String sql) throws SQLException {
    List<String> fragments = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    int i = 0;
    int length = sql.length();
    while (i < length) {
      char c = sql.charAt(i);
      switch (c) {
        case '\'' -> i = consumeQuoted(sql, i, '\'', current);
        case '`' -> i = consumeQuoted(sql, i, '`', current);
        case '"' -> i = consumeQuoted(sql, i, '"', current);
        case '-' -> {
          if (i + 1 < length && sql.charAt(i + 1) == '-') {
            int end = sql.indexOf('\n', i);
            end = end < 0 ? length : end;
            current.append(sql, i, end);
            i = end;
          } else {
            current.append(c);
            i++;
          }
        }
        case '/' -> {
          if (i + 1 < length && sql.charAt(i + 1) == '*') {
            int end = sql.indexOf("*/", i + 2);
            if (end < 0) {
              throw new SQLException("Unterminated block comment in SQL", "42000");
            }
            current.append(sql, i, end + 2);
            i = end + 2;
          } else {
            current.append(c);
            i++;
          }
        }
        case '?' -> {
          fragments.add(current.toString());
          current.setLength(0);
          i++;
        }
        default -> {
          current.append(c);
          i++;
        }
      }
    }
    fragments.add(current.toString());
    return fragments;
  }

  private static int consumeQuoted(String sql, int start, char quote, StringBuilder current)
      throws SQLException {
    int i = start;
    current.append(sql.charAt(i));
    i++;
    while (i < sql.length()) {
      char c = sql.charAt(i);
      current.append(c);
      i++;
      if (c == '\\' && quote == '\'' && i < sql.length()) {
        current.append(sql.charAt(i));
        i++;
      } else if (c == quote) {
        return i;
      }
    }
    throw new SQLException("Unterminated quoted section in SQL", "42000");
  }

  /** Renders one bound value as a ClickHouse literal. */
  static String literal(Object value) throws SQLException {
    return switch (value) {
      case null -> "NULL";
      case Boolean b -> b ? "true" : "false";
      case Byte n -> n.toString();
      case Short n -> n.toString();
      case Integer n -> n.toString();
      case Long n -> n.toString();
      case Float n -> floatLiteral(n.doubleValue(), n.toString());
      case Double n -> floatLiteral(n, n.toString());
      case BigInteger n -> n.toString();
      case BigDecimal n -> n.toPlainString();
      case String s -> quote(s);
      case UUID uuid -> quote(uuid.toString());
      case LocalDate date -> quote(date.toString());
      case LocalDateTime dateTime -> quote(dateTime.toString().replace('T', ' '));
      case Instant instant -> quote(instant.toString());
      case java.sql.Date date -> quote(date.toLocalDate().toString());
      case java.sql.Timestamp timestamp -> quote(timestamp.toInstant().toString());
      case List<?> list -> arrayLiteral(list);
      case Object[] array -> arrayLiteral(List.of(array));
      case Map<?, ?> map -> mapLiteral(map);
      default ->
          throw new SQLException(
              "Cannot render a "
                  + value.getClass().getName()
                  + " as a SQL literal; bind a supported type",
              "22018");
    };
  }

  private static String floatLiteral(double value, String rendered) {
    if (Double.isNaN(value)) {
      return "nan";
    }
    if (Double.isInfinite(value)) {
      return value > 0 ? "inf" : "-inf";
    }
    return rendered;
  }

  private static String arrayLiteral(List<?> values) throws SQLException {
    StringBuilder builder = new StringBuilder("[");
    for (int i = 0; i < values.size(); i++) {
      if (i > 0) {
        builder.append(", ");
      }
      builder.append(literal(values.get(i)));
    }
    return builder.append(']').toString();
  }

  private static String mapLiteral(Map<?, ?> map) throws SQLException {
    StringBuilder builder = new StringBuilder("map(");
    boolean first = true;
    for (Map.Entry<?, ?> entry : map.entrySet()) {
      if (!first) {
        builder.append(", ");
      }
      first = false;
      builder.append(literal(entry.getKey())).append(", ").append(literal(entry.getValue()));
    }
    return builder.append(')').toString();
  }

  /** Escapes a string as a ClickHouse single quoted literal. */
  static String quote(String value) {
    StringBuilder builder = new StringBuilder(value.length() + 2);
    builder.append('\'');
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      switch (c) {
        case '\'' -> builder.append("\\'");
        case '\\' -> builder.append("\\\\");
        case '\n' -> builder.append("\\n");
        case '\r' -> builder.append("\\r");
        case '\t' -> builder.append("\\t");
        case '\0' -> builder.append("\\0");
        default -> builder.append(c);
      }
    }
    return builder.append('\'').toString();
  }

  private static final Pattern BATCH_INSERT =
      Pattern.compile(
          "^\\s*INSERT\\s+INTO\\s+(?<table>(?:`[^`]+`|\"[^\"]+\"|[\\w.]+))"
              + "\\s*(?<columns>\\([^)]*\\))?\\s*VALUES\\s*\\((?<params>[\\s?,]*)\\)\\s*;?\\s*$",
          Pattern.CASE_INSENSITIVE);

  /**
   * Recognises {@code INSERT INTO table [(cols)] VALUES (?, ?, ...)} with only plain placeholders,
   * the shape that batches through native blocks instead of text substitution.
   *
   * @return the INSERT statement without the VALUES tuple, or {@code null} when the SQL has a
   *     different shape
   */
  static String nativeInsertPrefix(String sql, int parameterCount) {
    Matcher matcher = BATCH_INSERT.matcher(sql);
    if (!matcher.matches()) {
      return null;
    }
    String params = matcher.group("params");
    long placeholders = params.chars().filter(c -> c == '?').count();
    long separators = params.chars().filter(c -> c == ',').count();
    if (placeholders == 0 || placeholders != parameterCount || separators != placeholders - 1) {
      return null;
    }
    String columns = matcher.group("columns");
    return "INSERT INTO "
        + matcher.group("table")
        + (columns != null ? " " + columns : "")
        + " VALUES";
  }
}
