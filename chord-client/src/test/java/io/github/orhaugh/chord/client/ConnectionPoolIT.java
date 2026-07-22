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

import io.github.orhaugh.chord.ChordServerException;
import io.github.orhaugh.chord.codec.column.BlockBuilder;
import io.github.orhaugh.chord.codec.column.Columns;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** The pool against a real server: concurrent query and insert traffic on shared connections. */
@Testcontainers
@Timeout(180)
class ConnectionPoolIT {

  @Container
  private static final io.github.orhaugh.chord.testkit.ClickHouseServerContainer CLICKHOUSE =
      new io.github.orhaugh.chord.testkit.ClickHouseServerContainer();

  private static ConnectionOptions options() {
    return ConnectionOptions.builder()
        .host(CLICKHOUSE.getHost())
        .port(CLICKHOUSE.nativePort())
        .database(CLICKHOUSE.database())
        .username(CLICKHOUSE.username())
        .password(CLICKHOUSE.password())
        .allowPlaintextPassword(true)
        .build();
  }

  @Test
  void concurrentWorkloadsShareABoundedSetOfRealConnections() throws Exception {
    try (ConnectionPool pool =
        ConnectionPool.builder(options()).maxSize(4).validationInterval(Duration.ZERO).build()) {
      try (PooledConnection lease = pool.acquire();
          QueryResult result =
              lease.query(
                  QueryRequest.of(
                      "CREATE TABLE pool_t (worker UInt32, n UInt64) ENGINE = Memory"))) {
        result.nextBlock();
      }

      int workers = 12;
      CountDownLatch done = new CountDownLatch(workers);
      AtomicLong failures = new AtomicLong();
      try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
        for (int workerId = 0; workerId < workers; workerId++) {
          int worker = workerId;
          executor.submit(
              () -> {
                try {
                  try (PooledConnection lease = pool.acquire()) {
                    try (InsertStream insert =
                        lease.insert(QueryRequest.of("INSERT INTO pool_t VALUES"))) {
                      BlockBuilder builder = insert.newBlock();
                      for (long n = 0; n < 1000; n++) {
                        builder.addRow(worker, n);
                      }
                      insert.send(builder.build());
                      insert.finish();
                    }
                  }
                  try (PooledConnection lease = pool.acquire();
                      QueryResult result =
                          lease.query(
                              QueryRequest.builder(
                                      "SELECT sum(n) FROM pool_t WHERE worker = {w:UInt32}")
                                  .parameter("w", worker)
                                  .build())) {
                    Columns.UInt64Column sums =
                        (Columns.UInt64Column) result.nextBlock().orElseThrow().column(0);
                    if (sums.rawLongAt(0) != 999L * 1000 / 2) {
                      failures.incrementAndGet();
                    }
                  }
                } catch (RuntimeException e) {
                  failures.incrementAndGet();
                } finally {
                  done.countDown();
                }
              });
        }
        assertThat(done.await(120, TimeUnit.SECONDS)).isTrue();
      }
      assertThat(failures.get()).isZero();
      assertThat(pool.activeCount()).isZero();
      assertThat(pool.idleCount()).isBetween(1, 4);

      // A server error on a leased connection must not poison the pool.
      try (PooledConnection lease = pool.acquire()) {
        try (QueryResult result = lease.query(QueryRequest.of("SELECT broken syntax here"))) {
          result.nextBlock();
        } catch (ChordServerException e) {
          // Expected; the connection concluded the stream cleanly and stays reusable.
        }
        try (QueryResult result = lease.query(QueryRequest.of("SELECT count() FROM pool_t"))) {
          Columns.UInt64Column counts =
              (Columns.UInt64Column) result.nextBlock().orElseThrow().column(0);
          assertThat(counts.rawLongAt(0)).isEqualTo(12_000);
        }
      }
    }
  }
}
