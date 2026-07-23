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

import io.github.orhaugh.chord.ChordServerException;
import io.github.orhaugh.chord.RetryClass;
import io.github.orhaugh.chord.codec.column.BlockBuilder;
import io.github.orhaugh.chord.protocol.state.ConnectionState;
import io.github.orhaugh.chord.testkit.ClickHouseServerContainer;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Operational flows a production deployment hits: queries killed from another connection, readonly
 * users, server side execution timeouts, and schema evolution visible to already open pooled
 * connections. Every failure must be typed, honestly classified, and leave the connection usable.
 */
@Testcontainers
@Timeout(180)
class ProductionFlowsIT {

  @Container
  private static final ClickHouseServerContainer CLICKHOUSE = new ClickHouseServerContainer();

  private static ConnectionOptions.Builder optionsBuilder() {
    return ConnectionOptions.builder()
        .host(CLICKHOUSE.getHost())
        .port(CLICKHOUSE.nativePort())
        .database(CLICKHOUSE.database())
        .allowPlaintextPassword(true);
  }

  private static NativeConnection connect() {
    return NativeConnection.open(
        optionsBuilder().username(CLICKHOUSE.username()).password(CLICKHOUSE.password()).build());
  }

  private static void execute(NativeConnection connection, String sql) {
    try (QueryResult result = connection.query(QueryRequest.of(sql))) {
      while (result.nextBlock().isPresent()) {
        // Drain to the end of stream.
      }
    }
  }

  private static long scalarLong(NativeConnection connection, String sql) {
    try (QueryResult result = connection.query(QueryRequest.of(sql))) {
      Object value = result.nextBlock().orElseThrow().column(0).objectAt(0);
      while (result.nextBlock().isPresent()) {
        // Drain.
      }
      return value instanceof java.math.BigInteger big
          ? big.longValueExact()
          : ((Number) value).longValue();
    }
  }

  @Test
  void killQueryFromAnotherConnectionIsTypedAndTheVictimSurvives() throws Exception {
    String queryId = "flow-kill-" + java.util.UUID.randomUUID();
    AtomicReference<Throwable> victimFailure = new AtomicReference<>();
    try (NativeConnection victim = connect();
        NativeConnection killer = connect()) {
      Thread consumer =
          Thread.ofVirtual()
              .start(
                  () -> {
                    try (QueryResult result =
                        victim.query(
                            QueryRequest.builder("SELECT sleepEachRow(0.05) FROM numbers(2000)")
                                .setting("max_block_size", 1)
                                .queryId(queryId)
                                .build())) {
                      while (result.nextBlock().isPresent()) {
                        // Stream until the kill lands.
                      }
                    } catch (Throwable t) {
                      victimFailure.set(t);
                    }
                  });

      // Wait until the server registers the query, then kill it by id.
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
      while (scalarLong(
                  killer, "SELECT count() FROM system.processes WHERE query_id = '" + queryId + "'")
              == 0
          && System.nanoTime() < deadline) {
        Thread.sleep(50);
      }
      execute(killer, "KILL QUERY WHERE query_id = '" + queryId + "'");

      consumer.join(Duration.ofSeconds(60));
      assertThat(consumer.isAlive()).isFalse();
      assertThat(victimFailure.get())
          .as("the killed query surfaces a typed server exception")
          .isInstanceOf(ChordServerException.class);
      ChordServerException killed = (ChordServerException) victimFailure.get();
      assertThat(killed.code()).isEqualTo(394); // QUERY_WAS_CANCELLED
      // The victim connection concluded its exchange and stays usable.
      assertThat(victim.state()).isEqualTo(ConnectionState.READY);
      assertThat(scalarLong(victim, "SELECT 7")).isEqualTo(7);
    }
  }

  @Test
  void readonlyUsersGetTypedRefusalsAndKeepTheirConnection() {
    try (NativeConnection admin = connect()) {
      execute(admin, "CREATE TABLE flow_ro_target (n UInt8) ENGINE = Memory");
      execute(
          admin,
          "CREATE USER IF NOT EXISTS flow_ro IDENTIFIED WITH plaintext_password BY 'ro-pw'"
              + " SETTINGS readonly = 1");
      // Full grants: the refusals below must come from the readonly setting (code 164),
      // not from missing privileges (code 497), so the grant layer cannot mask it.
      execute(
          admin,
          "GRANT SELECT, INSERT, CREATE TABLE ON " + CLICKHOUSE.database() + ".* TO flow_ro");
    }
    try (NativeConnection restricted =
        NativeConnection.open(optionsBuilder().username("flow_ro").password("ro-pw").build())) {
      assertThat(scalarLong(restricted, "SELECT 1 + 1")).isEqualTo(2);

      assertThatThrownBy(
              () -> execute(restricted, "CREATE TABLE flow_ro_denied (n UInt8) ENGINE = Memory"))
          .isInstanceOf(ChordServerException.class)
          .satisfies(
              e -> {
                assertThat(((ChordServerException) e).code()).isEqualTo(164); // READONLY
                assertThat(((ChordServerException) e).retryClass())
                    .isEqualTo(RetryClass.NOT_RETRYABLE);
              });
      assertThatThrownBy(() -> execute(restricted, "INSERT INTO flow_ro_target VALUES (1)"))
          .isInstanceOf(ChordServerException.class)
          .satisfies(e -> assertThat(((ChordServerException) e).code()).isEqualTo(164));

      // Refusals are conversations, not connection failures.
      assertThat(restricted.state()).isEqualTo(ConnectionState.READY);
      assertThat(scalarLong(restricted, "SELECT 40 + 2")).isEqualTo(42);
    }
  }

  @Test
  void serverSideExecutionTimeoutsAreTypedAndTheConnectionSurvives() {
    try (NativeConnection connection = connect()) {
      assertThatThrownBy(
              () -> {
                try (QueryResult result =
                    connection.query(
                        QueryRequest.builder("SELECT count() FROM numbers(100000000000)")
                            .setting("max_execution_time", 1)
                            .build())) {
                  while (result.nextBlock().isPresent()) {
                    // The timeout lands mid execution.
                  }
                }
              })
          .isInstanceOf(ChordServerException.class)
          .satisfies(
              e -> {
                assertThat(((ChordServerException) e).code()).isEqualTo(159); // TIMEOUT_EXCEEDED
                assertThat(((ChordServerException) e).retryClass())
                    .isEqualTo(RetryClass.RETRY_ONLY_IF_IDEMPOTENT);
              });
      assertThat(connection.state()).isEqualTo(ConnectionState.READY);
      assertThat(scalarLong(connection, "SELECT 5")).isEqualTo(5);
    }
  }

  @Test
  void schemaEvolutionIsVisibleToAlreadyOpenPooledConnections() {
    try (ConnectionPool pool =
        ConnectionPool.builder(
                optionsBuilder()
                    .username(CLICKHOUSE.username())
                    .password(CLICKHOUSE.password())
                    .build())
            .maxSize(1)
            .build()) {
      // One underlying connection serves every lease, so a schema change between leases
      // proves the client caches nothing about table shapes.
      try (PooledConnection lease = pool.acquire()) {
        try (QueryResult result =
            lease.query(
                QueryRequest.of(
                    "CREATE TABLE flow_evo (a UInt32) ENGINE = MergeTree ORDER BY a"))) {
          result.nextBlock();
        }
        try (InsertStream insert = lease.insert(QueryRequest.of("INSERT INTO flow_evo VALUES"))) {
          BlockBuilder builder = insert.newBlock();
          builder.addRow(1L);
          insert.send(builder.build());
          insert.finish();
        }
      }
      try (PooledConnection lease = pool.acquire()) {
        try (QueryResult result =
            lease.query(
                QueryRequest.of("ALTER TABLE flow_evo ADD COLUMN b String DEFAULT 'old'"))) {
          result.nextBlock();
        }
      }
      try (PooledConnection lease = pool.acquire()) {
        // The same underlying connection now sees the widened schema on insert.
        try (InsertStream insert = lease.insert(QueryRequest.of("INSERT INTO flow_evo VALUES"))) {
          BlockBuilder builder = insert.newBlock();
          builder.addRow(2L, "new");
          insert.send(builder.build());
          insert.finish();
        }
        // The two rows live in different parts, so they may arrive in separate blocks.
        java.util.List<Object> values = new java.util.ArrayList<>();
        try (QueryResult result =
            lease.query(QueryRequest.of("SELECT b FROM flow_evo ORDER BY a"))) {
          java.util.Optional<io.github.orhaugh.chord.codec.block.Block> block;
          while ((block = result.nextBlock()).isPresent()) {
            for (int row = 0; row < block.get().rows(); row++) {
              values.add(block.get().column(0).objectAt(row));
            }
          }
        }
        assertThat(values).containsExactly("old", "new");
      }
    }
  }

  @Test
  void sessionSettingsPersistAcrossQueriesOnOneConnection() {
    try (NativeConnection connection = connect()) {
      execute(connection, "SET max_result_rows = 987654");
      // A later, unrelated query on the same connection still sees the session value.
      assertThat(scalarLong(connection, "SELECT count() FROM numbers(10)")).isEqualTo(10);
      assertThat(scalarLong(connection, "SELECT toUInt64(getSetting('max_result_rows'))"))
          .isEqualTo(987654);
      // A fresh connection does not.
      try (NativeConnection fresh = connect()) {
        assertThat(scalarLong(fresh, "SELECT toUInt64(getSetting('max_result_rows'))"))
            .isNotEqualTo(987654);
      }
    }
  }
}
