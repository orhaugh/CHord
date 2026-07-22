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

import io.github.orhaugh.chord.ChordTypeException;
import io.github.orhaugh.chord.codec.type.ClickHouseType;
import io.github.orhaugh.chord.codec.type.ClickHouseType.JsonType;
import io.github.orhaugh.chord.codec.type.TypeParser;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Parses the argument list of a JSON type declaration.
 *
 * <p>Arguments are comma separated at the top level: {@code max_dynamic_paths=N} and {@code
 * max_dynamic_types=N} tuning parameters, {@code SKIP path} and {@code SKIP REGEXP '...'} hints,
 * and typed path declarations of the form {@code path Type} where the path may be back quoted. Only
 * typed paths affect decoding; the rest are recognised and ignored.
 */
final class JsonTypeArguments {

  private JsonTypeArguments() {}

  /**
   * Extracts the typed paths of a JSON type in sorted path order, matching the serialisation order
   * of {@code SerializationObject}.
   *
   * @param type the JSON type
   * @param maxTypeNameLength parser limit for path types
   * @param maxTypeDepth parser limit for path types
   * @return sorted path to parsed type
   */
  static Map<String, ClickHouseType> typedPaths(
      JsonType type, int maxTypeNameLength, int maxTypeDepth) {
    Map<String, ClickHouseType> typed = new TreeMap<>();
    if (type.rawArguments().isEmpty()) {
      return typed;
    }
    for (String argument : splitTopLevel(type.rawArguments().get())) {
      String trimmed = argument.trim();
      if (trimmed.isEmpty()
          || trimmed.startsWith("max_dynamic_paths")
          || trimmed.startsWith("max_dynamic_types")
          || trimmed.startsWith("SKIP")) {
        continue;
      }
      int split = pathEnd(trimmed);
      if (split <= 0 || split >= trimmed.length()) {
        throw new ChordTypeException("Malformed JSON type argument: " + trimmed);
      }
      String path = unquotePath(trimmed.substring(0, split).trim());
      String typeName = trimmed.substring(split).trim();
      typed.put(path, TypeParser.parse(typeName, maxTypeNameLength, maxTypeDepth));
    }
    return typed;
  }

  /** Splits at top level commas, respecting parentheses, back quotes and single quotes. */
  private static List<String> splitTopLevel(String arguments) {
    List<String> parts = new ArrayList<>();
    int depth = 0;
    boolean inBackQuote = false;
    boolean inSingleQuote = false;
    int start = 0;
    for (int i = 0; i < arguments.length(); i++) {
      char c = arguments.charAt(i);
      if (inBackQuote) {
        inBackQuote = c != '`';
      } else if (inSingleQuote) {
        if (c == '\\') {
          i++;
        } else if (c == '\'') {
          inSingleQuote = false;
        }
      } else if (c == '`') {
        inBackQuote = true;
      } else if (c == '\'') {
        inSingleQuote = true;
      } else if (c == '(') {
        depth++;
      } else if (c == ')') {
        depth--;
      } else if (c == ',' && depth == 0) {
        parts.add(arguments.substring(start, i));
        start = i + 1;
      }
    }
    parts.add(arguments.substring(start));
    return parts;
  }

  /** Returns the index just past the path token: a back quoted span or a run without spaces. */
  private static int pathEnd(String argument) {
    if (argument.charAt(0) == '`') {
      int close = argument.indexOf('`', 1);
      return close < 0 ? -1 : close + 1;
    }
    return argument.indexOf(' ');
  }

  private static String unquotePath(String path) {
    if (path.length() >= 2 && path.charAt(0) == '`' && path.charAt(path.length() - 1) == '`') {
      return path.substring(1, path.length() - 1);
    }
    return path;
  }
}
