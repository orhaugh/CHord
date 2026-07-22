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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.orhaugh.chord.ChordTypeException;
import io.github.orhaugh.chord.codec.type.ClickHouseType;
import io.github.orhaugh.chord.codec.type.ClickHouseType.JsonType;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Parsing of JSON type declaration arguments: typed paths, tuning parameters and SKIP hints. */
class JsonTypeArgumentsTest {

  private static Map<String, ClickHouseType> typedPaths(String rawArguments) {
    return JsonTypeArguments.typedPaths(new JsonType(Optional.of(rawArguments)), 10_000, 32);
  }

  @Test
  void typedPathsParseInSortedOrder() {
    Map<String, ClickHouseType> paths = typedPaths("b UInt32, a String");
    assertThat(paths.keySet()).containsExactly("a", "b"); // sorted, matching serialisation order
    assertThat(paths.get("a").name()).isEqualTo("String");
    assertThat(paths.get("b").name()).isEqualTo("UInt32");
  }

  @Test
  void backQuotedPathsAndNestedTypeArgumentsParse() {
    Map<String, ClickHouseType> paths =
        typedPaths("`weird path` Array(Nullable(String)), plain Decimal(10, 2)");
    assertThat(paths.keySet()).containsExactly("plain", "weird path");
    assertThat(paths.get("weird path").name()).isEqualTo("Array(Nullable(String))");
    assertThat(paths.get("plain").name()).isEqualTo("Decimal(10, 2)");
  }

  @Test
  void tuningParametersAndSkipHintsAreIgnored() {
    Map<String, ClickHouseType> paths =
        typedPaths(
            "max_dynamic_paths=64, max_dynamic_types=8, a UInt8,"
                + " SKIP b.c, SKIP REGEXP 'internal.*'");
    assertThat(paths.keySet()).containsExactly("a");
  }

  @Test
  void pathsContainingCommasInsideQuotesStayIntact() {
    Map<String, ClickHouseType> paths = typedPaths("a Enum8('x, y' = 1, 'z' = 2), b UInt8");
    assertThat(paths.keySet()).containsExactly("a", "b");
    assertThat(paths.get("a").name()).contains("x, y");
  }

  @Test
  void jsonWithoutArgumentsHasNoTypedPaths() {
    assertThat(JsonTypeArguments.typedPaths(new JsonType(Optional.empty()), 100, 8)).isEmpty();
  }

  @Test
  void malformedArgumentsFailExplicitly() {
    assertThatThrownBy(() -> typedPaths("justonetoken"))
        .isInstanceOf(ChordTypeException.class)
        .hasMessageContaining("Malformed");
  }
}
