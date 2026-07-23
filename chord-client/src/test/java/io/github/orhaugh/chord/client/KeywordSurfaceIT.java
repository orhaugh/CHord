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
import java.math.BigInteger;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * One test per ClickHouse statement kind and query clause a production user reaches for: the DDL
 * lifecycle, introspection, EXPLAIN, the SELECT clause zoo, mutations, materialized views, column
 * subset inserts, and session state statements. Each proves the statement executes through the
 * native protocol and its results or effects decode correctly.
 */
@Testcontainers
@Timeout(180)
class KeywordSurfaceIT {

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
      while (result.nextBlock().isPresent()) {
        // Drain to the end of stream.
      }
    }
  }

  /** Runs a query and returns every row of its first column as objects. */
  private static List<Object> columnValues(NativeConnection connection, String sql) {
    List<Object> values = new ArrayList<>();
    try (QueryResult result = connection.query(QueryRequest.of(sql))) {
      java.util.Optional<Block> block;
      while ((block = result.nextBlock()).isPresent()) {
        Column column = block.get().column(0);
        for (int row = 0; row < block.get().rows(); row++) {
          values.add(column.objectAt(row));
        }
      }
    }
    return values;
  }

  private static long scalarLong(NativeConnection connection, String sql) {
    Object value = columnValues(connection, sql).get(0);
    return value instanceof BigInteger big ? big.longValueExact() : ((Number) value).longValue();
  }

  @Test
  void ddlLifecycleFlowsEndToEnd() {
    try (NativeConnection connection = connect()) {
      execute(
          connection, "CREATE TABLE kw_life (a UInt32, b String) ENGINE = MergeTree ORDER BY a");
      execute(connection, "ALTER TABLE kw_life ADD COLUMN c UInt8 DEFAULT 7");
      execute(connection, "INSERT INTO kw_life VALUES (1, 'one', 10), (2, 'two', 20)");
      execute(connection, "ALTER TABLE kw_life MODIFY COLUMN c UInt16");
      execute(connection, "ALTER TABLE kw_life DROP COLUMN b");
      execute(connection, "RENAME TABLE kw_life TO kw_life_renamed");
      assertThat(scalarLong(connection, "SELECT count() FROM kw_life_renamed")).isEqualTo(2);
      assertThat(scalarLong(connection, "SELECT sum(c) FROM kw_life_renamed")).isEqualTo(30);
      execute(connection, "TRUNCATE TABLE kw_life_renamed");
      assertThat(scalarLong(connection, "SELECT count() FROM kw_life_renamed")).isZero();
      execute(connection, "DROP TABLE kw_life_renamed");
      assertThat(scalarLong(connection, "EXISTS TABLE kw_life_renamed")).isZero();
    }
  }

  @Test
  void introspectionStatementsDecode() {
    try (NativeConnection connection = connect()) {
      execute(connection, "CREATE TABLE kw_intro (id UInt64, name String) ENGINE = Memory");

      assertThat(columnValues(connection, "SHOW TABLES")).contains("kw_intro");
      assertThat((String) columnValues(connection, "SHOW CREATE TABLE kw_intro").get(0))
          .contains("CREATE TABLE")
          .contains("ENGINE = Memory");
      assertThat(scalarLong(connection, "EXISTS TABLE kw_intro")).isEqualTo(1);

      try (QueryResult result = connection.query(QueryRequest.of("DESCRIBE TABLE kw_intro"))) {
        Block block = result.nextBlock().orElseThrow();
        assertThat(block.rows()).isEqualTo(2);
        assertThat(block.columnByName("name").orElseThrow().objectAt(0)).isEqualTo("id");
        assertThat(block.columnByName("type").orElseThrow().objectAt(0)).isEqualTo("UInt64");
        while (result.nextBlock().isPresent()) {
          // Drain.
        }
      }
      assertThat(columnValues(connection, "SHOW DATABASES")).contains(CLICKHOUSE.database());
    }
  }

  @Test
  void explainStatementsReturnPlans() {
    try (NativeConnection connection = connect()) {
      List<Object> ast = columnValues(connection, "EXPLAIN AST SELECT 1 + 1");
      assertThat(ast).isNotEmpty();
      assertThat(String.join("\n", ast.stream().map(Object::toString).toList()))
          .contains("SelectQuery");
      List<Object> pipeline =
          columnValues(connection, "EXPLAIN PIPELINE SELECT sum(number) FROM numbers(10)");
      assertThat(pipeline).isNotEmpty();
    }
  }

  @Test
  void replacingMergeTreeFinalCollapsesDuplicates() {
    try (NativeConnection connection = connect()) {
      execute(
          connection,
          "CREATE TABLE kw_final (id UInt64, v UInt64) ENGINE = ReplacingMergeTree ORDER BY id");
      execute(connection, "INSERT INTO kw_final VALUES (1, 10), (2, 20)");
      execute(connection, "INSERT INTO kw_final VALUES (1, 11), (2, 21)");
      // Without FINAL both versions may surface; with FINAL exactly one per key must.
      assertThat(scalarLong(connection, "SELECT count() FROM kw_final FINAL")).isEqualTo(2);
      assertThat(scalarLong(connection, "SELECT sum(v) FROM kw_final FINAL")).isEqualTo(32);
      // OPTIMIZE FINAL folds the parts for keyword coverage of OPTIMIZE.
      execute(connection, "OPTIMIZE TABLE kw_final FINAL");
      assertThat(scalarLong(connection, "SELECT count() FROM kw_final")).isEqualTo(2);
    }
  }

  @Test
  void prewhereSampleAndLimitByFilter() {
    try (NativeConnection connection = connect()) {
      execute(
          connection,
          "CREATE TABLE kw_sample (id UInt64, tag String) ENGINE = MergeTree"
              + " ORDER BY (id, intHash32(id)) SAMPLE BY intHash32(id)");
      execute(
          connection,
          "INSERT INTO kw_sample SELECT number, concat('t', toString(number % 4))"
              + " FROM numbers(1000)");

      assertThat(scalarLong(connection, "SELECT count() FROM kw_sample PREWHERE id < 500"))
          .isEqualTo(500);
      long sampled = scalarLong(connection, "SELECT count() FROM kw_sample SAMPLE 1 / 2");
      assertThat(sampled).isBetween(1L, 999L); // a genuine subset, never all or nothing
      assertThat(
              columnValues(connection, "SELECT id FROM kw_sample ORDER BY tag, id LIMIT 2 BY tag")
                  .size())
          .isEqualTo(8); // four tags, two rows each
    }
  }

  @Test
  void arrayJoinUnionJoinAndCtesCompose() {
    try (NativeConnection connection = connect()) {
      assertThat(
              columnValues(
                  connection, "SELECT x FROM (SELECT [1, 2, 3] AS arr) ARRAY JOIN arr AS x"))
          .containsExactly(1, 2, 3);
      assertThat(
              columnValues(
                  connection, "SELECT x FROM (SELECT 1 AS x UNION ALL SELECT 2 AS x) ORDER BY x"))
          .containsExactly(1, 2);
      assertThat(
              scalarLong(
                  connection,
                  "WITH doubled AS (SELECT number * 2 AS d FROM numbers(4))"
                      + " SELECT sum(d) FROM doubled"))
          .isEqualTo(12);
      assertThat(
              scalarLong(
                  connection,
                  "SELECT count() FROM numbers(10) AS l"
                      + " INNER JOIN (SELECT number FROM numbers(5)) AS r"
                      + " ON l.number = r.number"))
          .isEqualTo(5);
    }
  }

  @Test
  void windowFunctionsAndRollupAggregate() {
    try (NativeConnection connection = connect()) {
      assertThat(
              columnValues(
                  connection,
                  "SELECT sum(number) OVER (ORDER BY number ROWS BETWEEN UNBOUNDED PRECEDING"
                      + " AND CURRENT ROW) FROM numbers(5)"))
          .containsExactly(
              BigInteger.ZERO,
              BigInteger.ONE,
              BigInteger.valueOf(3),
              BigInteger.valueOf(6),
              BigInteger.valueOf(10));
      // ROLLUP adds the grand total row to the three groups.
      assertThat(
              columnValues(
                      connection,
                      "SELECT number % 3 AS g, count() FROM numbers(9)"
                          + " GROUP BY g WITH ROLLUP ORDER BY g")
                  .size())
          .isEqualTo(4);
      assertThat(
              columnValues(connection, "SELECT DISTINCT number % 2 FROM numbers(10) ORDER BY 1")
                  .size())
          .isEqualTo(2);
    }
  }

  @Test
  void mutationsDeleteAndUpdateApply() {
    try (NativeConnection connection = connect()) {
      execute(
          connection, "CREATE TABLE kw_mut (id UInt64, v UInt64) ENGINE = MergeTree ORDER BY id");
      execute(connection, "INSERT INTO kw_mut SELECT number, number FROM numbers(10)");

      // Lightweight DELETE.
      execute(connection, "DELETE FROM kw_mut WHERE id < 3");
      assertThat(scalarLong(connection, "SELECT count() FROM kw_mut")).isEqualTo(7);

      // Classic mutation, waited on so the assertion is deterministic.
      try (QueryResult result =
          connection.query(
              QueryRequest.builder("ALTER TABLE kw_mut UPDATE v = 0 WHERE id = 5")
                  .setting("mutations_sync", 2)
                  .build())) {
        while (result.nextBlock().isPresent()) {
          // Drain.
        }
      }
      assertThat(scalarLong(connection, "SELECT v FROM kw_mut WHERE id = 5")).isZero();
    }
  }

  @Test
  void materializedViewsPopulateOnInsert() {
    try (NativeConnection connection = connect()) {
      execute(connection, "CREATE TABLE kw_mv_src (id UInt64) ENGINE = MergeTree ORDER BY id");
      execute(
          connection,
          "CREATE TABLE kw_mv_tgt (doubled UInt64) ENGINE = MergeTree ORDER BY doubled");
      execute(
          connection,
          "CREATE MATERIALIZED VIEW kw_mv TO kw_mv_tgt AS"
              + " SELECT id * 2 AS doubled FROM kw_mv_src");
      execute(connection, "INSERT INTO kw_mv_src VALUES (1), (2), (3)");
      assertThat(columnValues(connection, "SELECT doubled FROM kw_mv_tgt ORDER BY doubled"))
          .containsExactly(BigInteger.TWO, BigInteger.valueOf(4), BigInteger.valueOf(6));
    }
  }

  @Test
  void insertWithAColumnSubsetFillsDefaults() {
    try (NativeConnection connection = connect()) {
      execute(
          connection,
          "CREATE TABLE kw_subset (a UInt32, b String DEFAULT 'filled', c UInt8)"
              + " ENGINE = MergeTree ORDER BY a");
      // The server's schema block for a column subset carries exactly those columns.
      try (InsertStream insert =
          connection.insert(QueryRequest.of("INSERT INTO kw_subset (a, c) VALUES"))) {
        BlockBuilder builder = insert.newBlock();
        builder.addRow(1L, 9);
        builder.addRow(2L, 8);
        insert.send(builder.build());
        InsertStream.InsertSummary summary = insert.finish();
        assertThat(summary.rowsSent()).isEqualTo(2);
      }
      assertThat(columnValues(connection, "SELECT b FROM kw_subset ORDER BY a"))
          .containsExactly("filled", "filled");
      assertThat(scalarLong(connection, "SELECT sum(c) FROM kw_subset")).isEqualTo(17);
    }
  }

  @Test
  void setAndUseStatementsCarrySessionState() {
    try (NativeConnection connection = connect()) {
      execute(connection, "SET max_block_size = 4321");
      assertThat(scalarLong(connection, "SELECT toUInt64(getSetting('max_block_size'))"))
          .isEqualTo(4321);

      execute(connection, "CREATE DATABASE IF NOT EXISTS kw_other");
      execute(connection, "USE kw_other");
      execute(connection, "CREATE TABLE unqualified (n UInt8) ENGINE = Memory");
      execute(connection, "INSERT INTO unqualified VALUES (5)");
      assertThat(scalarLong(connection, "SELECT n FROM unqualified")).isEqualTo(5);
      assertThat(
              scalarLong(
                  connection,
                  "SELECT count() FROM system.tables"
                      + " WHERE database = 'kw_other' AND name = 'unqualified'"))
          .isEqualTo(1);
    }
  }

  @Test
  void sessionTimezoneUpdatesFollowTheServer() {
    try (NativeConnection connection = connect()) {
      // The SET statement changes the server side session: server functions and implicit
      // DateTime rendering see the new zone. Note the server does NOT announce this with a
      // TimezoneUpdate packet: as of 25.8, TCPHandler::sendTimezone has a single call site
      // on the input() table function path, so client decode contexts are only movable
      // through that flow (the handler is proven by scripted transport in
      // NativeConnectionTest.timezoneUpdatePacketsMoveTheDecodeContext).
      execute(connection, "SET session_timezone = 'Asia/Tokyo'");
      assertThat(columnValues(connection, "SELECT timezone()").get(0)).isEqualTo("Asia/Tokyo");
      // A column with an explicit zone in its declared type always carries it, whatever
      // the session state.
      try (QueryResult result =
          connection.query(QueryRequest.of("SELECT toDateTime(0, 'Europe/Berlin') AS dt"))) {
        Column column = result.nextBlock().orElseThrow().column(0);
        assertThat(((Columns.DateTimeColumn) column).zone()).isEqualTo(ZoneId.of("Europe/Berlin"));
        assertThat(column.objectAt(0)).isEqualTo(java.time.Instant.EPOCH);
        while (result.nextBlock().isPresent()) {
          // Drain.
        }
      }
    }
  }

  @Test
  void systemStatementsExecute() {
    try (NativeConnection connection = connect()) {
      execute(connection, "CREATE TABLE kw_sys (n UInt8) ENGINE = MergeTree ORDER BY n");
      execute(connection, "SYSTEM STOP MERGES kw_sys");
      execute(connection, "SYSTEM START MERGES kw_sys");
      execute(connection, "SYSTEM DROP MARK CACHE");
      assertThat(connection.state().name()).isEqualTo("READY");
    }
  }

  @Test
  void detachAttachExchangeAndCheckTableOperate() {
    try (NativeConnection connection = connect()) {
      execute(connection, "CREATE TABLE kw_ops (n UInt8) ENGINE = MergeTree ORDER BY n");
      execute(connection, "INSERT INTO kw_ops VALUES (1), (2)");

      execute(connection, "DETACH TABLE kw_ops");
      assertThat(scalarLong(connection, "EXISTS TABLE kw_ops")).isZero();
      execute(connection, "ATTACH TABLE kw_ops");
      assertThat(scalarLong(connection, "SELECT count() FROM kw_ops")).isEqualTo(2);

      // EXCHANGE swaps two tables atomically: the reload pattern behind zero downtime swaps.
      execute(connection, "CREATE TABLE kw_ops_next (n UInt8) ENGINE = MergeTree ORDER BY n");
      execute(connection, "INSERT INTO kw_ops_next VALUES (7), (8), (9)");
      execute(connection, "EXCHANGE TABLES kw_ops AND kw_ops_next");
      assertThat(scalarLong(connection, "SELECT count() FROM kw_ops")).isEqualTo(3);
      assertThat(scalarLong(connection, "SELECT count() FROM kw_ops_next")).isEqualTo(2);

      // 26.x defaults CHECK TABLE to per part rows; the setting pins the single value form.
      assertThat(
              scalarLong(
                  connection, "CHECK TABLE kw_ops SETTINGS check_query_single_value_result = 1"))
          .isEqualTo(1);
    }
  }

  @Test
  void classicMutationDeleteAndProcesslistOperate() {
    try (NativeConnection connection = connect()) {
      execute(connection, "CREATE TABLE kw_alter_del (id UInt64) ENGINE = MergeTree ORDER BY id");
      execute(connection, "INSERT INTO kw_alter_del SELECT number FROM numbers(10)");
      try (QueryResult result =
          connection.query(
              QueryRequest.builder("ALTER TABLE kw_alter_del DELETE WHERE id >= 8")
                  .setting("mutations_sync", 2)
                  .build())) {
        while (result.nextBlock().isPresent()) {
          // Drain.
        }
      }
      assertThat(scalarLong(connection, "SELECT count() FROM kw_alter_del")).isEqualTo(8);
      // SHOW PROCESSLIST decodes; it may legitimately be empty between queries.
      execute(connection, "SHOW PROCESSLIST");
      assertThat(connection.state().name()).isEqualTo("READY");
    }
  }
}
