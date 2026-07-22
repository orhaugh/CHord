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

import io.github.orhaugh.chord.codec.column.BlockBuilder;
import io.github.orhaugh.chord.codec.column.Columns;
import io.github.orhaugh.chord.testkit.ClickHouseServerContainer;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Progress and log listeners plus the deduplication token against a real server. */
@Testcontainers
@Timeout(120)
class ListenersIT {

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

  @Test
  void progressAndLogListenersFireDuringAQuery() {
    AtomicLong progressRows = new AtomicLong();
    List<ServerLogEntry> logEntries = new CopyOnWriteArrayList<>();
    try (NativeConnection connection = connect();
        QueryResult result =
            connection.query(
                QueryRequest.builder("SELECT count() FROM numbers(3000000)")
                    .setting("send_logs_level", "trace")
                    .setting("max_block_size", 65536)
                    .onProgress(delta -> progressRows.addAndGet(delta.readRows()))
                    .onLog(logEntries::add)
                    .build())) {
      assertThat(((Columns.UInt64Column) result.nextBlock().orElseThrow().column(0)).rawLongAt(0))
          .isEqualTo(3_000_000);
      assertThat(result.nextBlock()).isEmpty();
      assertThat(progressRows.get()).isEqualTo(3_000_000);
      assertThat(result.totalProgress().readRows()).isEqualTo(3_000_000);
      assertThat(logEntries).isNotEmpty();
      ServerLogEntry first = logEntries.get(0);
      assertThat(first.text()).isNotBlank();
      assertThat(first.priority()).isPositive();
      assertThat(first.queryId()).isNotBlank();
    }
  }

  @Test
  void aThrowingListenerNeverBreaksTheStream() {
    try (NativeConnection connection = connect();
        QueryResult result =
            connection.query(
                QueryRequest.builder("SELECT sum(number) FROM numbers(2000000)")
                    .onProgress(
                        delta -> {
                          throw new IllegalStateException("listener bug");
                        })
                    .build())) {
      assertThat(((Columns.UInt64Column) result.nextBlock().orElseThrow().column(0)).rawLongAt(0))
          .isEqualTo(1_999_999L * 2_000_000 / 2);
      assertThat(result.nextBlock()).isEmpty();
      assertThat(connection.state().name()).isEqualTo("READY");
    }
  }

  @Test
  void insertPathListenersReceiveServerLogs() {
    // Real servers send Log packets during client fed inserts but no Progress packets (the
    // TCP handler only emits progress from the SELECT pull loop); Progress dispatch on the
    // insert path is proven by the scripted NativeConnectionTest.
    List<ServerLogEntry> logEntries = new CopyOnWriteArrayList<>();
    try (NativeConnection connection = connect()) {
      try (QueryResult result =
          connection.query(
              QueryRequest.of(
                  "CREATE TABLE listen_ins (n UInt64) ENGINE = MergeTree ORDER BY n"))) {
        result.nextBlock();
      }
      try (InsertStream insert =
          connection.insert(
              QueryRequest.builder("INSERT INTO listen_ins VALUES")
                  .setting("send_logs_level", "trace")
                  .onLog(logEntries::add)
                  .build())) {
        for (int block = 0; block < 20; block++) {
          io.github.orhaugh.chord.codec.column.BlockBuilder builder = insert.newBlock();
          for (long n = 0; n < 1_000; n++) {
            builder.addRow(block * 1_000L + n);
          }
          insert.send(builder.build());
        }
        InsertStream.InsertSummary summary = insert.finish();
        assertThat(summary.rowsSent()).isEqualTo(20_000);
        assertThat(summary.blocksSent()).isEqualTo(20);
      }
      assertThat(logEntries).isNotEmpty();
      assertThat(logEntries.get(0).text()).isNotBlank();
      assertThat(logEntries.get(0).queryId()).isNotBlank();
      try (QueryResult result =
          connection.query(QueryRequest.of("SELECT count() FROM listen_ins"))) {
        assertThat(((Columns.UInt64Column) result.nextBlock().orElseThrow().column(0)).rawLongAt(0))
            .isEqualTo(20_000);
      }
    }
  }

  @Test
  void emptyInsertsCommitNothingAndLeaveTheConnectionClean() {
    try (NativeConnection connection = connect()) {
      try (QueryResult result =
          connection.query(QueryRequest.of("CREATE TABLE empty_ins (n UInt64) ENGINE = Memory"))) {
        result.nextBlock();
      }
      try (InsertStream insert =
          connection.insert(QueryRequest.of("INSERT INTO empty_ins VALUES"))) {
        InsertStream.InsertSummary summary = insert.finish();
        assertThat(summary.rowsSent()).isZero();
      }
      try (QueryResult result =
          connection.query(QueryRequest.of("SELECT count() FROM empty_ins"))) {
        assertThat(
                ((io.github.orhaugh.chord.codec.column.Columns.UInt64Column)
                        result.nextBlock().orElseThrow().column(0))
                    .rawLongAt(0))
            .isZero();
      }
    }
  }

  @Test
  void queryIdsReachTheServerQueryLog() {
    String queryId = "chord-audit-" + java.util.UUID.randomUUID();
    try (NativeConnection connection = connect()) {
      try (QueryResult result =
          connection.query(QueryRequest.builder("SELECT 1").queryId(queryId).build())) {
        result.nextBlock();
      }
      try (QueryResult result = connection.query(QueryRequest.of("SYSTEM FLUSH LOGS"))) {
        result.nextBlock();
      }
      try (QueryResult result =
          connection.query(
              QueryRequest.builder(
                      "SELECT count() FROM system.query_log WHERE query_id = {qid:String}")
                  .parameter("qid", queryId)
                  .build())) {
        assertThat(
                ((io.github.orhaugh.chord.codec.column.Columns.UInt64Column)
                        result.nextBlock().orElseThrow().column(0))
                    .rawLongAt(0))
            .isPositive();
      }
    }
  }

  @Test
  void deduplicationTokenMakesARepeatedInsertDropSilently() {
    try (NativeConnection connection = connect()) {
      // Deduplication on non replicated MergeTree requires the table level window setting.
      try (QueryResult result =
          connection.query(
              QueryRequest.of(
                  "CREATE TABLE dedup_t (n UInt64) ENGINE = MergeTree ORDER BY n"
                      + " SETTINGS non_replicated_deduplication_window = 100"))) {
        result.nextBlock();
      }
      for (int attempt = 0; attempt < 2; attempt++) {
        try (InsertStream insert =
            connection.insert(
                QueryRequest.builder("INSERT INTO dedup_t VALUES")
                    .insertDeduplicationToken("phase5-dedup-token")
                    .build())) {
          BlockBuilder builder = insert.newBlock();
          for (long n = 0; n < 100; n++) {
            builder.addRow(n);
          }
          insert.send(builder.build());
          insert.finish();
        }
      }
      try (QueryResult result = connection.query(QueryRequest.of("SELECT count() FROM dedup_t"))) {
        // The second INSERT carried the same token and was dropped, not applied twice.
        assertThat(((Columns.UInt64Column) result.nextBlock().orElseThrow().column(0)).rawLongAt(0))
            .isEqualTo(100);
      }
    }
  }
}
