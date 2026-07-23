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
  void variantDynamicAndJsonWriteThroughNativeBlocks() {
    try (NativeConnection connection = connect()) {
      for (String setting :
          java.util.List.of(
              "allow_experimental_variant_type",
              "allow_experimental_dynamic_type",
              "allow_experimental_json_type")) {
        try (QueryResult result = connection.query(QueryRequest.of("SET " + setting + " = 1"))) {
          while (result.nextBlock().isPresent()) {
            // Drain; unknown on newer servers where the types went stable is fine.
          }
        } catch (io.github.orhaugh.chord.ChordServerException e) {
          // Setting removed after stabilisation; proceed.
        }
      }
      execute(
          connection,
          "CREATE TABLE adv_writes (id UInt8, v Variant(Int64, String), d Dynamic, j JSON,"
              + " tj JSON(a Int64, b.c String)) ENGINE = Memory");
      try (InsertStream insert =
          connection.insert(QueryRequest.of("INSERT INTO adv_writes VALUES"))) {
        io.github.orhaugh.chord.codec.column.BlockBuilder builder = insert.newBlock();
        builder.addRow(
            1,
            42L,
            "dynamic-string",
            java.util.Map.of("a", 1L, "b.c", "x"),
            java.util.Map.of("a", 9L, "b.c", "typed", "extra", 3L));
        builder.addRow(2, "variant-string", 2.5d, java.util.Map.of("a", 2L), java.util.Map.of());
        builder.addRow(3, null, null, null, null);
        insert.send(builder.build());
        insert.finish();
      }
      // The server itself renders what it stored: definitive proof the wire bytes parsed.
      try (QueryResult result =
          connection.query(
              QueryRequest.of(
                  "SELECT toString(v), toString(d), toString(j), v IS NULL, d IS NULL,"
                      + " tj.a, tj.b.c FROM adv_writes ORDER BY id"))) {
        Block block = result.nextBlock().orElseThrow();
        assertThat(block.column(0).objectAt(0)).isEqualTo("42");
        assertThat(block.column(1).objectAt(0)).isEqualTo("dynamic-string");
        // Dotted paths reconstruct as nested objects; older servers render dynamic path
        // numbers quoted, newer ones typed, so both forms are accepted.
        assertThat((String) block.column(2).objectAt(0))
            .matches("\\{\"a\":\"?1\"?,\"b\":\\{\"c\":\"x\"}}");
        assertThat(block.column(0).objectAt(1)).isEqualTo("variant-string");
        assertThat(block.column(1).objectAt(1)).isEqualTo("2.5");
        // NULL renders differently across versions; the IS NULL semantics are stable.
        assertThat(block.column(3).objectAt(2)).isEqualTo(1);
        assertThat(block.column(4).objectAt(2)).isEqualTo(1);
        assertThat(block.column(3).objectAt(0)).isEqualTo(0);
        assertThat(block.column(2).objectAt(2)).isEqualTo("{}");
        // Typed path subcolumns come back as their concrete types, defaults when absent.
        assertThat(block.column(5).objectAt(0)).isEqualTo(9L);
        assertThat(block.column(6).objectAt(0)).isEqualTo("typed");
        assertThat(block.column(5).objectAt(1)).isEqualTo(0L);
        assertThat(block.column(6).objectAt(1)).isEqualTo("");
        while (result.nextBlock().isPresent()) {
          // Drain.
        }
      }
    }
  }

  @Test
  void sparseColumnsMaterialiseAcrossTypeFamilies() {
    try (NativeConnection connection = connect()) {
      execute(
          connection,
          "CREATE TABLE sparse_mixed (id UInt32, d Decimal64(4), dt Date, ts DateTime,"
              + " e Enum8('none' = 0, 'hit' = 1), u UUID, ip IPv6, f Float64)"
              + " ENGINE = MergeTree ORDER BY id"
              + " SETTINGS ratio_of_defaults_for_sparse_serialization = 0.5");
      execute(
          connection,
          "INSERT INTO sparse_mixed SELECT number,"
              + " if(number = 100, toDecimal64('12.3456', 4), toDecimal64(0, 4)),"
              + " if(number = 200, toDate('2024-05-01'), toDate(0)),"
              + " if(number = 300, toDateTime(1000000, 'UTC'), toDateTime(0, 'UTC')),"
              + " if(number = 400, 'hit', 'none'),"
              + " if(number = 500, toUUID('01234567-89ab-cdef-0123-456789abcdef'),"
              + " toUUID('00000000-0000-0000-0000-000000000000')),"
              + " if(number = 600, toIPv6('2001:db8::1'), toIPv6('::')),"
              + " if(number = 700, 3.5, 0)"
              + " FROM numbers(4000)");
      // The test only proves sparse decode if the server genuinely chose sparse serialisation.
      java.util.Set<String> sparseColumns = new java.util.HashSet<>();
      try (QueryResult result =
          connection.query(
              QueryRequest.of(
                  "SELECT DISTINCT column FROM system.parts_columns"
                      + " WHERE database = currentDatabase() AND table = 'sparse_mixed'"
                      + " AND serialization_kind = 'Sparse'"))) {
        Optional<Block> next;
        while ((next = result.nextBlock()).isPresent()) {
          Block block = next.orElseThrow();
          for (int i = 0; i < block.rows(); i++) {
            sparseColumns.add(String.valueOf(block.column(0).objectAt(i)));
          }
        }
      }
      assertThat(sparseColumns).contains("d", "dt", "ts", "e", "u", "ip", "f");

      try (QueryResult result =
          connection.query(
              QueryRequest.of("SELECT id, d, dt, ts, e, u, ip, f FROM sparse_mixed ORDER BY id"))) {
        long seen = 0;
        Optional<Block> next;
        while ((next = result.nextBlock()).isPresent()) {
          Block block = next.orElseThrow();
          for (int i = 0; i < block.rows(); i++) {
            long id = seen + i;
            java.math.BigDecimal d = (java.math.BigDecimal) block.column(1).objectAt(i);
            assertThat(d).isEqualByComparingTo(id == 100 ? "12.3456" : "0");
            assertThat(block.column(2).objectAt(i))
                .isEqualTo(
                    id == 200
                        ? java.time.LocalDate.of(2024, 5, 1)
                        : java.time.LocalDate.ofEpochDay(0));
            assertThat(block.column(3).objectAt(i))
                .isEqualTo(
                    id == 300
                        ? java.time.Instant.ofEpochSecond(1_000_000)
                        : java.time.Instant.EPOCH);
            assertThat(block.column(4).objectAt(i)).isEqualTo(id == 400 ? "hit" : "none");
            assertThat(block.column(5).objectAt(i))
                .isEqualTo(
                    id == 500
                        ? java.util.UUID.fromString("01234567-89ab-cdef-0123-456789abcdef")
                        : new java.util.UUID(0, 0));
            assertThat(String.valueOf(block.column(6).objectAt(i)))
                .contains(id == 600 ? "2001:db8" : ":");
            assertThat(block.column(7).objectAt(i)).isEqualTo(id == 700 ? 3.5d : 0.0d);
          }
          seen += block.rows();
        }
        assertThat(seen).isEqualTo(4000);
      }
    }
  }

  @Test
  void lowCardinalityDictionaryKeyTypesBeyondStringRoundTrip() {
    try (NativeConnection connection = connect()) {
      try (QueryResult result =
          connection.query(
              QueryRequest.builder(
                      "CREATE TABLE lc_keys (id UInt32, ld LowCardinality(Date),"
                          + " lf LowCardinality(FixedString(4)), ln LowCardinality(UInt16))"
                          + " ENGINE = MergeTree ORDER BY id")
                  .setting("allow_suspicious_low_cardinality_types", 1)
                  .build())) {
        result.nextBlock();
      }
      try (InsertStream insert = connection.insert(QueryRequest.of("INSERT INTO lc_keys VALUES"))) {
        BlockBuilder builder = insert.newBlock();
        for (int i = 0; i < 1000; i++) {
          builder.addRow(
              i, java.time.LocalDate.of(2024, 1, 1 + (i % 5)), "tag" + (i % 5), 100 + (i % 5));
        }
        insert.send(builder.build());
        insert.finish();
      }
      try (QueryResult result =
          connection.query(QueryRequest.of("SELECT id, ld, lf, ln FROM lc_keys ORDER BY id"))) {
        long seen = 0;
        Optional<Block> next;
        while ((next = result.nextBlock()).isPresent()) {
          Block block = next.orElseThrow();
          for (int i = 0; i < block.rows(); i++) {
            long id = seen + i;
            int cycle = (int) (id % 5);
            assertThat(block.column(1).objectAt(i))
                .isEqualTo(java.time.LocalDate.of(2024, 1, 1 + cycle));
            assertThat(block.column(2).objectAt(i)).isEqualTo("tag" + cycle);
            assertThat(block.column(3).objectAt(i)).isEqualTo(100 + cycle);
          }
          seen += block.rows();
        }
        assertThat(seen).isEqualTo(1000);
      }
    }
  }

  @Test
  void lowCardinalityWideDictionariesCrossTheFourByteIndexBoundary() {
    try (NativeConnection connection = connect()) {
      execute(
          connection,
          "CREATE TABLE lc_wide (v LowCardinality(String)) ENGINE = MergeTree ORDER BY tuple()");
      int distinct = 70_000;
      try (InsertStream insert = connection.insert(QueryRequest.of("INSERT INTO lc_wide VALUES"))) {
        // One block with more than 65536 distinct values forces four byte dictionary indexes
        // on our encode side; the server must accept them.
        BlockBuilder builder = insert.newBlock();
        for (int i = 0; i < distinct; i++) {
          builder.addRow("k" + i);
        }
        insert.send(builder.build());
        insert.finish();
      }
      try (QueryResult result =
          connection.query(
              QueryRequest.builder("SELECT v FROM lc_wide")
                  .setting("max_block_size", 100_000)
                  .build())) {
        java.util.Set<String> values = new java.util.HashSet<>();
        Optional<Block> next;
        while ((next = result.nextBlock()).isPresent()) {
          Block block = next.orElseThrow();
          for (int i = 0; i < block.rows(); i++) {
            values.add(String.valueOf(block.column(0).objectAt(i)));
          }
        }
        assertThat(values).hasSize(distinct).contains("k0", "k65535", "k65536", "k69999");
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
}
