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

import io.github.orhaugh.chord.ChordTimeoutException;
import io.github.orhaugh.chord.codec.column.Columns;
import io.github.orhaugh.chord.protocol.state.ConnectionState;
import io.github.orhaugh.chord.testkit.ClickHouseServerContainer;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Cancellation and client side timeouts against a real server. */
@Testcontainers
@Timeout(120)
class CancelIT {

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
  void cancelStopsAnUnboundedStreamAndTheConnectionStaysUsable() {
    try (NativeConnection connection = connect()) {
      try (QueryResult result =
          connection.query(
              QueryRequest.builder("SELECT number FROM system.numbers")
                  .setting("max_block_size", 4096)
                  .build())) {
        // Consume a little real data, then cancel mid stream.
        long seen = 0;
        for (int i = 0; i < 3; i++) {
          Optional<io.github.orhaugh.chord.codec.block.Block> block = result.nextBlock();
          assertThat(block).isPresent();
          seen += block.orElseThrow().rows();
        }
        assertThat(seen).isPositive();
        result.cancel();
        // The server concludes the stream on its own terms; drain to the end.
        while (result.nextBlock().isPresent()) {
          // Discard blocks that were already in flight.
        }
      }
      assertThat(connection.state()).isEqualTo(ConnectionState.READY);

      // The connection is provably reusable after the cancel.
      try (QueryResult result = connection.query(QueryRequest.of("SELECT 42"))) {
        assertThat(result.nextBlock().orElseThrow().column(0).objectAt(0)).isEqualTo(42);
      }
    }
  }

  @Test
  void timeoutCancelsServerSideWorkAndSurfacesAsTimeout() {
    try (NativeConnection connection = connect()) {
      QueryRequest request =
          QueryRequest.builder("SELECT count() FROM numbers(1000000000000)")
              .timeout(Duration.ofMillis(500))
              .build();
      assertThatThrownBy(
              () -> {
                try (QueryResult result = connection.query(request)) {
                  result.nextBlock();
                }
              })
          .isInstanceOf(ChordTimeoutException.class)
          .hasMessageContaining("remains usable");
      assertThat(connection.state()).isEqualTo(ConnectionState.READY);

      try (QueryResult result =
          connection.query(QueryRequest.of("SELECT sum(n) FROM (SELECT 1 AS n)"))) {
        assertThat(((Columns.UInt64Column) result.nextBlock().orElseThrow().column(0)).rawLongAt(0))
            .isEqualTo(1);
      }
    }
  }

  @Test
  void cancelFromAnotherThreadUnblocksTheConsumer() throws Exception {
    try (NativeConnection connection = connect()) {
      try (QueryResult result =
          connection.query(
              QueryRequest.builder("SELECT number FROM system.numbers")
                  .setting("max_block_size", 4096)
                  .build())) {
        java.util.concurrent.CountDownLatch firstBlock = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Throwable> consumerFailure =
            new java.util.concurrent.atomic.AtomicReference<>();
        // The consuming thread drains the unbounded stream; cancel() is documented as the one
        // result method callable from a different thread.
        Thread consumer =
            Thread.ofVirtual()
                .start(
                    () -> {
                      try {
                        while (result.nextBlock().isPresent()) {
                          firstBlock.countDown();
                        }
                      } catch (Throwable t) {
                        consumerFailure.set(t);
                      }
                    });
        assertThat(firstBlock.await(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        result.cancel();
        consumer.join(java.time.Duration.ofSeconds(30));
        assertThat(consumer.isAlive()).isFalse();
        assertThat(consumerFailure.get()).isNull();
      }
      assertThat(connection.state()).isEqualTo(ConnectionState.READY);
      try (QueryResult result = connection.query(QueryRequest.of("SELECT 7"))) {
        assertThat(result.nextBlock().orElseThrow().column(0).objectAt(0)).isEqualTo(7);
      }
    }
  }

  @Test
  void queriesFinishingWithinTheirTimeoutAreUnaffected() {
    try (NativeConnection connection = connect();
        QueryResult result =
            connection.query(
                QueryRequest.builder("SELECT sum(number) FROM numbers(100000)")
                    .timeout(Duration.ofSeconds(30))
                    .build())) {
      assertThat(((Columns.UInt64Column) result.nextBlock().orElseThrow().column(0)).rawLongAt(0))
          .isEqualTo(4999950000L);
      assertThat(result.nextBlock()).isEmpty();
      assertThat(connection.state()).isEqualTo(ConnectionState.READY);
    }
  }
}
