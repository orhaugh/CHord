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
package io.github.orhaugh.chord.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.orhaugh.chord.codec.UnsupportedClickHouseTypeException;
import io.github.orhaugh.chord.codec.block.Block;
import io.github.orhaugh.chord.codec.column.BlockBuilder;
import io.github.orhaugh.chord.codec.column.Column;
import io.github.orhaugh.chord.codec.column.Columns;
import io.github.orhaugh.chord.testkit.ClickHouseServerContainer;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The Phase 6 serialisations against a real server over the actual TCP protocol: LowCardinality
 * both directions, sparse reads from genuinely sparse MergeTree tables, and Variant, Dynamic and
 * JSON reads with V2 prefixes.
 */
@Testcontainers
@Timeout(180)
class AdvancedTypesIT {

  @Container
  private static final ClickHouseServerContainer CLICKHOUSE = new ClickHouseServerContainer();

  private static NativeConnection connect() {
    return NativeConnection.open(
        ConnectionOptions.builder()
            .host(CLICKHOUSE.getHost())
            .port(CLICKHOUSE.nativePort())
            .database(CLICKHOUSE.database())
            .username(CLICKHOUSE.username())
            .password(CLICKHOUSE.password())
            .allowPlaintextPassword(true)
            .build());
  }

  private static void execute(NativeConnection connection, String sql) {
    try (QueryResult result = connection.query(QueryRequest.of(sql))) {
      result.nextBlock();
    }
  }

  @Test
  void lowCardinalityRoundTripsThroughInsertAndSelect() {
    try (NativeConnection connection = connect()) {
      execute(
          connection,
          "CREATE TABLE lc_t (id UInt32, tag LowCardinality(String),"
              + " maybe LowCardinality(Nullable(String))) ENGINE = MergeTree ORDER BY id");
      try (InsertStream insert = connection.insert(QueryRequest.of("INSERT INTO lc_t VALUES"))) {
        BlockBuilder builder = insert.newBlock();
        for (int i = 0; i < 5000; i++) {
          builder.addRow(i, "tag-" + (i % 7), i % 3 == 0 ? null : "m-" + (i % 4));
        }
        insert.send(builder.build());
        insert.finish();
      }
      try (QueryResult result =
          connection.query(QueryRequest.of("SELECT id, tag, maybe FROM lc_t ORDER BY id"))) {
        long seen = 0;
        Optional<Block> next;
        while ((next = result.nextBlock()).isPresent()) {
          Block block = next.orElseThrow();
          Column tags = block.column(1);
          Column maybes = block.column(2);
          for (int i = 0; i < block.rows(); i++) {
            long id = seen + i;
            assertThat(tags.objectAt(i)).isEqualTo("tag-" + (id % 7));
            if (id % 3 == 0) {
              assertThat(maybes.isNullAt(i)).isTrue();
            } else {
              assertThat(maybes.objectAt(i)).isEqualTo("m-" + (id % 4));
            }
          }
          seen += block.rows();
        }
        assertThat(seen).isEqualTo(5000);
      }
    }
  }

  @Test
  void sparseColumnsFromMergeTreeMaterialise() {
    try (NativeConnection connection = connect()) {
      execute(
          connection,
          "CREATE TABLE sparse_t (id UInt32, v UInt64, s String) ENGINE = MergeTree ORDER BY id"
              + " SETTINGS ratio_of_defaults_for_sparse_serialization = 0.5");
      execute(
          connection,
          "INSERT INTO sparse_t SELECT number, if(number IN (5, 100, 4999), number * 3, 0),"
              + " if(number = 77, 'rare', '') FROM numbers(5000)");
      // Confirm the server genuinely stores the columns sparse, so the test cannot silently
      // pass against plain serialisation.
      try (QueryResult result =
          connection.query(
              QueryRequest.of(
                  "SELECT count() FROM system.parts_columns WHERE database = currentDatabase()"
                      + " AND table = 'sparse_t' AND serialization_kind = 'Sparse'"))) {
        Columns.UInt64Column counts =
            (Columns.UInt64Column) result.nextBlock().orElseThrow().column(0);
        assertThat(counts.rawLongAt(0)).isGreaterThanOrEqualTo(2);
      }
      try (QueryResult result =
          connection.query(QueryRequest.of("SELECT id, v, s FROM sparse_t ORDER BY id"))) {
        long seen = 0;
        Optional<Block> next;
        while ((next = result.nextBlock()).isPresent()) {
          Block block = next.orElseThrow();
          Columns.UInt64Column values = (Columns.UInt64Column) block.column(1);
          Column strings = block.column(2);
          for (int i = 0; i < block.rows(); i++) {
            long id = seen + i;
            long expected = (id == 5 || id == 100 || id == 4999) ? id * 3 : 0;
            assertThat(values.rawLongAt(i)).isEqualTo(expected);
            assertThat(strings.objectAt(i)).isEqualTo(id == 77 ? "rare" : "");
          }
          seen += block.rows();
        }
        assertThat(seen).isEqualTo(5000);
      }
    }
  }

  @Test
  void variantColumnsDecodeOverTheWire() {
    try (NativeConnection connection = connect();
        QueryResult result =
            connection.query(
                QueryRequest.builder(
                        "SELECT CAST(multiIf(number % 3 = 0, NULL, number % 3 = 1,"
                            + " toString(number), concat('s', toString(number))),"
                            + " 'Variant(Int64, String)') AS v FROM numbers(1000)")
                    .setting("allow_experimental_variant_type", 1)
                    .setting("use_variant_as_common_type", 1)
                    .build())) {
      long seen = 0;
      Optional<Block> next;
      while ((next = result.nextBlock()).isPresent()) {
        Block block = next.orElseThrow();
        Column column = block.column(0);
        for (int i = 0; i < block.rows(); i++) {
          long n = seen + i;
          if (n % 3 == 0) {
            assertThat(column.isNullAt(i)).isTrue();
          } else if (n % 3 == 1) {
            assertThat(column.objectAt(i)).isEqualTo(n);
          } else {
            assertThat(column.objectAt(i)).isEqualTo("s" + n);
          }
        }
        seen += block.rows();
      }
      assertThat(seen).isEqualTo(1000);
    }
  }

  @Test
  void dynamicColumnsDecodeOverTheWire() {
    try (NativeConnection connection = connect()) {
      execute(connection, "SET allow_experimental_dynamic_type = 1");
      execute(connection, "CREATE TABLE dyn_t (d Dynamic) ENGINE = Memory");
      execute(
          connection,
          "INSERT INTO dyn_t VALUES (42), ('hello'), (NULL), ([1,2,3]), (43.5), ('world')");
      try (QueryResult result = connection.query(QueryRequest.of("SELECT d FROM dyn_t"))) {
        Block block = result.nextBlock().orElseThrow();
        Columns.DynamicColumn column = (Columns.DynamicColumn) block.column(0);
        assertThat(column.objectAt(0)).isEqualTo(42L);
        assertThat(column.objectAt(1)).isEqualTo("hello");
        assertThat(column.isNullAt(2)).isTrue();
        assertThat(column.objectAt(3)).isEqualTo(List.of(1L, 2L, 3L));
        assertThat(column.objectAt(4)).isEqualTo(43.5d);
        assertThat(column.objectAt(5)).isEqualTo("world");
      }
    }
  }

  @Test
  void jsonColumnsDecodeOverTheWire() {
    try (NativeConnection connection = connect()) {
      execute(connection, "SET allow_experimental_json_type = 1");
      execute(
          connection,
          "CREATE TABLE js_t (j JSON(price UInt32)) ENGINE = MergeTree ORDER BY tuple()");
      execute(
          connection,
          "INSERT INTO js_t VALUES ('{\"price\": 7, \"name\": \"a\", \"tags\": [1, 2]}'),"
              + " ('{\"price\": 8, \"nested\": {\"flag\": true}}')");
      try (QueryResult result = connection.query(QueryRequest.of("SELECT j FROM js_t"))) {
        Block block = result.nextBlock().orElseThrow();
        Columns.JsonColumn column = (Columns.JsonColumn) block.column(0);
        assertThat(column.size()).isEqualTo(2);
        @SuppressWarnings("unchecked")
        Map<String, Object> first = (Map<String, Object>) column.objectAt(0);
        assertThat(first).containsEntry("price", 7L).containsEntry("name", "a");
        assertThat(first.get("tags")).isEqualTo(List.of(1L, 2L));
        @SuppressWarnings("unchecked")
        Map<String, Object> second = (Map<String, Object>) column.objectAt(1);
        assertThat(second).containsEntry("price", 8L).containsEntry("nested.flag", true);
      }
    }
  }

  @Test
  void insertingIntoJsonColumnsFailsExplicitly() {
    try (NativeConnection connection = connect()) {
      execute(connection, "SET allow_experimental_json_type = 1");
      execute(connection, "CREATE TABLE js_reject (j JSON) ENGINE = Memory");
      assertThatThrownBy(
              () -> {
                try (InsertStream insert =
                    connection.insert(QueryRequest.of("INSERT INTO js_reject VALUES"))) {
                  insert.newBlock();
                }
              })
          .isInstanceOf(UnsupportedClickHouseTypeException.class)
          .hasMessageContaining("JSON");
    }
  }
}
