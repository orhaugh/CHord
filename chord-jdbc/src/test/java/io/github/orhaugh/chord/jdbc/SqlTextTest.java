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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The SQL splitter, literal rendering and batched INSERT shape recognition. */
class SqlTextTest {

  @Test
  void placeholdersSplitOutsideStringsIdentifiersAndComments() throws SQLException {
    List<String> fragments =
        SqlText.splitAtPlaceholders(
            "SELECT '?', `a?b`, \"c?d\" -- trailing ? comment\n"
                + "FROM t /* block ? comment */ WHERE x = ? AND y = ?");
    assertThat(fragments).hasSize(3);
    assertThat(fragments.get(0))
        .contains("'?'")
        .contains("`a?b`")
        .contains("/* block ? comment */");
    assertThat(fragments.get(1)).isEqualTo(" AND y = ");
    assertThat(fragments.get(2)).isEmpty();
  }

  @Test
  void escapedQuotesStayInsideStrings() throws SQLException {
    List<String> fragments = SqlText.splitAtPlaceholders("SELECT 'it\\'s ? fine', ?");
    assertThat(fragments).hasSize(2);
    assertThat(fragments.get(0)).isEqualTo("SELECT 'it\\'s ? fine', ");
  }

  @Test
  void unterminatedStringsFail() {
    assertThatThrownBy(() -> SqlText.splitAtPlaceholders("SELECT 'oops"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("Unterminated");
  }

  @Test
  void literalsRenderWithClickHouseEscaping() throws SQLException {
    assertThat(SqlText.literal(null)).isEqualTo("NULL");
    assertThat(SqlText.literal(42)).isEqualTo("42");
    assertThat(SqlText.literal(true)).isEqualTo("true");
    assertThat(SqlText.literal("it's a \\ test\nline")).isEqualTo("'it\\'s a \\\\ test\\nline'");
    assertThat(SqlText.literal(LocalDate.of(2026, 7, 22))).isEqualTo("'2026-07-22'");
    assertThat(SqlText.literal(Instant.parse("2026-07-22T10:00:00Z")))
        .isEqualTo("'2026-07-22T10:00:00Z'");
    assertThat(SqlText.literal(List.of(1, "two"))).isEqualTo("[1, 'two']");
    assertThat(SqlText.literal(Map.of("k", 1))).isEqualTo("map('k', 1)");
    assertThat(SqlText.literal(Double.NaN)).isEqualTo("nan");
  }

  @Test
  void unknownBindTypesFailInsteadOfToString() {
    assertThatThrownBy(() -> SqlText.literal(new Object()))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("bind a supported type");
  }

  @Test
  void nativeInsertShapeIsRecognised() {
    assertThat(SqlText.nativeInsertPrefix("INSERT INTO t VALUES (?, ?)", 2))
        .isEqualTo("INSERT INTO t VALUES");
    assertThat(SqlText.nativeInsertPrefix("insert into db.t (a, b) values (?,?)", 2))
        .isEqualTo("INSERT INTO db.t (a, b) VALUES");
    assertThat(SqlText.nativeInsertPrefix("INSERT INTO `we?ird` VALUES (?)", 1))
        .isEqualTo("INSERT INTO `we?ird` VALUES");
  }

  @Test
  void nonBatchableShapesAreNotRecognised() {
    // An expression among the placeholders means real SQL evaluation, not a plain block.
    assertThat(SqlText.nativeInsertPrefix("INSERT INTO t VALUES (?, now())", 1)).isNull();
    assertThat(SqlText.nativeInsertPrefix("INSERT INTO t SELECT ?", 1)).isNull();
    assertThat(SqlText.nativeInsertPrefix("UPDATE t SET x = ?", 1)).isNull();
    assertThat(SqlText.nativeInsertPrefix("INSERT INTO t VALUES (?, ?), (?, ?)", 4)).isNull();
  }
}
