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

import io.github.orhaugh.chord.ChordException;
import io.github.orhaugh.chord.codec.column.BlockBuilder;
import io.github.orhaugh.chord.codec.column.Columns;
import io.github.orhaugh.chord.testkit.ClickHouseServerContainer;
import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The soak suite: a mixed workload of streaming selects, batched inserts, cross thread cancels and
 * pings over a shared pool for a configured duration, with leak and memory bound assertions at the
 * end. Opt in and duration scaled:
 *
 * <pre>./mvnw -Pintegration-tests verify -pl chord-client -am \
 *     -Dit.test=SoakIT -Dchord.soak.seconds=3600</pre>
 *
 * <p>Sixty seconds is a smoke; an hour or more is a release gate. The workload holds no references
 * across iterations, so heap used after collection must return to near its baseline whatever the
 * duration.
 */
@Testcontainers
@Timeout(value = 6, unit = TimeUnit.HOURS)
class SoakIT {

  @Container
  private static final ClickHouseServerContainer CLICKHOUSE = new ClickHouseServerContainer();

  private static final int WORKERS = 8;
  private static final int POOL_SIZE = 6;
  private static final long HEAP_HEADROOM_BYTES = 128L * 1024 * 1024;

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

  private static long heapUsedAfterGc() throws InterruptedException {
    System.gc();
    Thread.sleep(200);
    return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
  }

  @Test
  @EnabledIfSystemProperty(named = "chord.soak.seconds", matches = "\\d+")
  void mixedWorkloadHoldsSteadyForTheConfiguredDuration() throws Exception {
    long seconds = Long.getLong("chord.soak.seconds", 60);
    try (NativeConnection setup = NativeConnection.open(options())) {
      try (QueryResult result =
          setup.query(
              QueryRequest.of(
                  "CREATE TABLE soak_rows (id UInt64, payload String)" + " ENGINE = Memory"))) {
        result.nextBlock();
      }
    }

    long heapBaseline = heapUsedAfterGc();
    AtomicLong rowsInserted = new AtomicLong();
    AtomicLong operations = new AtomicLong();
    AtomicLong cancels = new AtomicLong();
    List<Throwable> unexpected = new CopyOnWriteArrayList<>();

    try (ConnectionPool pool = ConnectionPool.builder(options()).maxSize(POOL_SIZE).build()) {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
      List<Thread> workers = new ArrayList<>();
      for (int workerId = 0; workerId < WORKERS; workerId++) {
        int lane = workerId;
        workers.add(
            Thread.ofVirtual()
                .name("soak-" + workerId)
                .start(
                    () ->
                        workload(
                            pool, lane, deadline, rowsInserted, operations, cancels, unexpected)));
      }
      for (Thread worker : workers) {
        worker.join(Duration.ofSeconds(seconds + 120));
        assertThat(worker.isAlive()).as("worker wedged past the deadline").isFalse();
      }

      assertThat(unexpected).as("unexpected failures during the soak").isEmpty();
      assertThat(operations.get()).isGreaterThan(WORKERS); // the workload actually ran
      assertThat(cancels.get()).isPositive();

      // Leak assertions: every permit returns, the full pool is still acquirable and usable.
      assertThat(pool.activeCount()).isZero();
      List<PooledConnection> full = new ArrayList<>();
      for (int i = 0; i < POOL_SIZE; i++) {
        full.add(pool.acquire());
      }
      for (PooledConnection lease : full) {
        lease.ping();
        lease.close();
      }

      // Server side truth matches the client side count.
      try (PooledConnection lease = pool.acquire();
          QueryResult result = lease.query(QueryRequest.of("SELECT count() FROM soak_rows"))) {
        long counted =
            ((Columns.UInt64Column) result.nextBlock().orElseThrow().column(0)).rawLongAt(0);
        assertThat(counted).isEqualTo(rowsInserted.get());
      }
    }

    // Memory bound: heap after collection returns to near its baseline. Catches monotone
    // growth (listener registries, pooled buffers, leaked blocks) whatever the duration.
    long heapAfter = heapUsedAfterGc();
    assertThat(heapAfter)
        .as(
            "heap used after GC grew from %d to %d over %d seconds",
            heapBaseline, heapAfter, seconds)
        .isLessThan(heapBaseline + HEAP_HEADROOM_BYTES);
  }

  private static void workload(
      ConnectionPool pool,
      int lane,
      long deadline,
      AtomicLong rowsInserted,
      AtomicLong operations,
      AtomicLong cancels,
      List<Throwable> unexpected) {
    long iteration = 0;
    while (System.nanoTime() < deadline && unexpected.isEmpty()) {
      long op = (lane + iteration++) % 4;
      try (PooledConnection lease = pool.acquire()) {
        switch ((int) op) {
          case 0 -> {
            try (QueryResult result =
                lease.query(QueryRequest.of("SELECT sum(number) FROM numbers(100000)"))) {
              long sum =
                  ((Columns.UInt64Column) result.nextBlock().orElseThrow().column(0)).rawLongAt(0);
              if (sum != 4_999_950_000L) {
                unexpected.add(new AssertionError("wrong sum " + sum));
              }
              result.nextBlock();
            }
          }
          case 1 -> {
            try (InsertStream insert =
                lease.insert(QueryRequest.of("INSERT INTO soak_rows VALUES"))) {
              BlockBuilder builder = insert.newBlock();
              for (long n = 0; n < 1_000; n++) {
                builder.addRow(java.math.BigInteger.valueOf(n), "payload-" + n);
              }
              insert.send(builder.build());
              insert.finish();
              rowsInserted.addAndGet(1_000);
            }
          }
          case 2 -> {
            try (QueryResult result =
                lease.query(
                    QueryRequest.builder("SELECT number FROM system.numbers")
                        .setting("max_block_size", 8192)
                        .build())) {
              result.nextBlock();
              result.cancel();
              cancels.incrementAndGet();
              while (result.nextBlock().isPresent()) {
                // Drain to the server's conclusion.
              }
            }
          }
          default -> lease.ping();
        }
        operations.incrementAndGet();
      } catch (ChordException e) {
        // Typed failures are tolerated only on the cancel lane, where the race is inherent.
        if (op != 2) {
          unexpected.add(e);
        }
      } catch (Throwable t) {
        unexpected.add(t);
      }
    }
  }
}
