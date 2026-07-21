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

/** Escaping helpers shared by the type model and parser. */
final class TypeNames {

  private TypeNames() {}

  /** Escapes a string for a single quoted ClickHouse literal. */
  static String escape(String value) {
    StringBuilder builder = new StringBuilder(value.length());
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      switch (c) {
        case '\\' -> builder.append("\\\\");
        case '\'' -> builder.append("\\'");
        case '\t' -> builder.append("\\t");
        case '\n' -> builder.append("\\n");
        default -> builder.append(c);
      }
    }
    return builder.toString();
  }

  /** Renders a tuple element name, quoting it with backticks when it is not a bare identifier. */
  static String maybeQuoteIdentifier(String name) {
    if (isBareIdentifier(name)) {
      return name;
    }
    StringBuilder builder = new StringBuilder(name.length() + 2).append('`');
    for (int i = 0; i < name.length(); i++) {
      char c = name.charAt(i);
      if (c == '`' || c == '\\') {
        builder.append('\\');
      }
      builder.append(c);
    }
    return builder.append('`').toString();
  }

  static boolean isBareIdentifier(String name) {
    if (name.isEmpty()) {
      return false;
    }
    char first = name.charAt(0);
    if (!Character.isLetter(first) && first != '_') {
      return false;
    }
    for (int i = 1; i < name.length(); i++) {
      char c = name.charAt(i);
      if (!Character.isLetterOrDigit(c) && c != '_') {
        return false;
      }
    }
    return true;
  }
}
