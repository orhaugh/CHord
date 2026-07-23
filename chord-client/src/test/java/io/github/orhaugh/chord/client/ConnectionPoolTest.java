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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Pool mechanics against a loopback server: reuse, bounds, validation, eviction and leaks. */
@Timeout(60)
class ConnectionPoolTest {

  @Test
  void releasedConnectionsAreReusedNewestFirst() throws Exception {
    try (LoopbackPingServer server = new LoopbackPingServer();
        ConnectionPool pool = ConnectionPool.builder(server.options()).maxSize(4).build()) {
      long firstId;
      try (PooledConnection lease = pool.acquire()) {
        firstId = lease.connection().id();
        lease.ping();
      }
      assertThat(pool.idleCount()).isEqualTo(1);
      try (PooledConnection lease = pool.acquire()) {
        assertThat(lease.connection().id()).isEqualTo(firstId);
      }
      assertThat(server.acceptedConnections()).isEqualTo(1);
    }
  }

  @Test
  void maxSizeBoundsConnectionsAndAcquireTimesOut() throws Exception {
    try (LoopbackPingServer server = new LoopbackPingServer();
        ConnectionPool pool =
            ConnectionPool.builder(server.options())
                .maxSize(2)
                .acquireTimeout(Duration.ofMillis(200))
                .build()) {
      PooledConnection first = pool.acquire();
      PooledConnection second = pool.acquire();
      assertThat(pool.activeCount()).isEqualTo(2);
      assertThatThrownBy(pool::acquire)
          .isInstanceOf(ChordTimeoutException.class)
          .hasMessageContaining("2 are in use");
      first.close();
      try (PooledConnection third = pool.acquire()) {
        assertThat(third.connection().state().name()).isEqualTo("READY");
      }
      second.close();
      assertThat(server.acceptedConnections()).isEqualTo(2);
    }
  }

  @Test
  void connectionsReturnedBrokenAreDiscardedAndReplaced() throws Exception {
    try (LoopbackPingServer server = new LoopbackPingServer();
        ConnectionPool pool =
            ConnectionPool.builder(server.options())
                .maxSize(2)
                .validationInterval(Duration.ZERO)
                .build()) {
      long firstId;
      try (PooledConnection lease = pool.acquire()) {
        firstId = lease.connection().id();
        // Simulate a mid exchange failure: the server drops the socket and the ping fails.
        server.dropAllConnections();
        assertThatThrownBy(lease::ping).isInstanceOf(RuntimeException.class);
      }
      // The broken connection must not be pooled.
      assertThat(pool.idleCount()).isZero();
      try (PooledConnection lease = pool.acquire()) {
        assertThat(lease.connection().id()).isNotEqualTo(firstId);
        lease.ping();
      }
    }
  }

  @Test
  void idleConnectionsAreValidatedByPingAndReplacedWhenDead() throws Exception {
    try (LoopbackPingServer server = new LoopbackPingServer();
        ConnectionPool pool =
            ConnectionPool.builder(server.options())
                .maxSize(2)
                .validationInterval(Duration.ZERO)
                .build()) {
      long firstId;
      try (PooledConnection lease = pool.acquire()) {
        firstId = lease.connection().id();
        lease.ping();
      }
      assertThat(pool.idleCount()).isEqualTo(1);
      // Kill the pooled connection's socket while it rests in the pool.
      server.dropAllConnections();
      // Zero validation interval forces a ping on borrow, which fails and opens a fresh one.
      try (PooledConnection lease = pool.acquire()) {
        assertThat(lease.connection().id()).isNotEqualTo(firstId);
        lease.ping();
      }
      assertThat(server.acceptedConnections()).isEqualTo(2);
    }
  }

  @Test
  void concurrentBorrowersNeverExceedTheBound() throws Exception {
    try (LoopbackPingServer server = new LoopbackPingServer();
        ConnectionPool pool = ConnectionPool.builder(server.options()).maxSize(3).build()) {
      int workers = 16;
      CountDownLatch done = new CountDownLatch(workers);
      List<Long> observedIds = java.util.Collections.synchronizedList(new ArrayList<>());
      try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
        for (int i = 0; i < workers; i++) {
          executor.submit(
              () -> {
                try (PooledConnection lease = pool.acquire()) {
                  observedIds.add(lease.connection().id());
                  lease.ping();
                } finally {
                  done.countDown();
                }
                return null;
              });
        }
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
      }
      assertThat(observedIds).hasSize(workers);
      assertThat(observedIds.stream().distinct().count()).isLessThanOrEqualTo(3);
      assertThat(server.acceptedConnections()).isLessThanOrEqualTo(3);
    }
  }

  @Test
  void lifetimeExpiredConnectionsAreNotReused() throws Exception {
    try (LoopbackPingServer server = new LoopbackPingServer();
        ConnectionPool pool =
            ConnectionPool.builder(server.options())
                .maxSize(2)
                .maxLifetime(Duration.ofMillis(50))
                .build()) {
      long firstId;
      try (PooledConnection lease = pool.acquire()) {
        firstId = lease.connection().id();
      }
      Thread.sleep(80);
      try (PooledConnection lease = pool.acquire()) {
        assertThat(lease.connection().id()).isNotEqualTo(firstId);
      }
    }
  }

  @Test
  void idleConnectionsAreEvictedAfterTheIdleTimeout() throws Exception {
    try (LoopbackPingServer server = new LoopbackPingServer();
        ConnectionPool pool =
            ConnectionPool.builder(server.options())
                .maxSize(2)
                .idleTimeout(Duration.ofMillis(200))
                .build()) {
      try (PooledConnection lease = pool.acquire()) {
        lease.ping();
      }
      assertThat(pool.idleCount()).isEqualTo(1);
      // The maintenance thread wakes on a period derived from the idle timeout; wait for it
      // to reclaim the resting connection.
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      while (pool.idleCount() > 0 && System.nanoTime() < deadline) {
        Thread.sleep(50);
      }
      assertThat(pool.idleCount()).isZero();
    }
  }

  @Test
  void closingThePoolWhileLeasesAreActiveIsSafe() throws Exception {
    try (LoopbackPingServer server = new LoopbackPingServer()) {
      ConnectionPool pool = ConnectionPool.builder(server.options()).maxSize(4).build();
      PooledConnection held = pool.acquire();
      // Racing closers and acquirers must neither deadlock nor corrupt the pool.
      CountDownLatch done = new CountDownLatch(3);
      try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
        for (int i = 0; i < 2; i++) {
          executor.submit(
              () -> {
                try {
                  try (PooledConnection lease = pool.acquire()) {
                    lease.ping();
                  }
                } catch (RuntimeException e) {
                  // Acquire may find the pool already closed; that is the documented path.
                } finally {
                  done.countDown();
                }
              });
        }
        executor.submit(
            () -> {
              pool.close();
              done.countDown();
            });
        assertThat(done.await(20, TimeUnit.SECONDS)).isTrue();
      }
      // The held lease survived the close; its connection is closed once returned.
      NativeConnection underlying = held.connection();
      held.ping();
      held.close();
      assertThat(underlying.state().name()).isEqualTo("CLOSED");
      assertThatThrownBy(pool::acquire).isInstanceOf(IllegalStateException.class);
    }
  }

  @Test
  void heldLeasesBeyondTheThresholdAreReportedAsLeaks() throws Exception {
    try (LoopbackPingServer server = new LoopbackPingServer();
        ConnectionPool pool =
            ConnectionPool.builder(server.options())
                .maxSize(2)
                .leakDetectionThreshold(Duration.ofMillis(50))
                .build()) {
      try (PooledConnection lease = pool.acquire()) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (pool.detectedLeaks() == 0 && System.nanoTime() < deadline) {
          Thread.sleep(100);
        }
        assertThat(pool.detectedLeaks()).isEqualTo(1);
        lease.ping(); // the lease still works; leak detection only diagnoses
      }
    }
  }

  @Test
  void closeDrainsIdleConnectionsAndRefusesFurtherAcquires() throws Exception {
    try (LoopbackPingServer server = new LoopbackPingServer()) {
      ConnectionPool pool = ConnectionPool.builder(server.options()).maxSize(2).build();
      PooledConnection held = pool.acquire();
      try (PooledConnection lease = pool.acquire()) {
        lease.ping();
      }
      assertThat(pool.idleCount()).isEqualTo(1);
      pool.close();
      assertThat(pool.idleCount()).isZero();
      assertThatThrownBy(pool::acquire).isInstanceOf(IllegalStateException.class);
      // A lease returned after close is closed, not pooled.
      NativeConnection underlying = held.connection();
      held.close();
      assertThat(underlying.state().name()).isEqualTo("CLOSED");
      assertThat(pool.idleCount()).isZero();
    }
  }

  @Test
  void usingALeaseAfterCloseFails() throws Exception {
    try (LoopbackPingServer server = new LoopbackPingServer();
        ConnectionPool pool = ConnectionPool.builder(server.options()).build()) {
      PooledConnection lease = pool.acquire();
      lease.close();
      assertThatThrownBy(lease::ping).isInstanceOf(IllegalStateException.class);
      lease.close(); // idempotent
    }
  }

  @Test
  void poolSurvivesAServerRestartUnderConcurrentLoad() throws Exception {
    try (LoopbackPingServer server = new LoopbackPingServer();
        ConnectionPool pool = ConnectionPool.builder(server.options()).maxSize(4).build()) {
      // Workers hammer acquire/ping/release; failures during the crash window are
      // expected and must never wedge the pool or leak a permit.
      int workers = 6;
      CountDownLatch running = new CountDownLatch(workers);
      java.util.concurrent.atomic.AtomicBoolean stop =
          new java.util.concurrent.atomic.AtomicBoolean();
      java.util.concurrent.atomic.AtomicLong successfulPings =
          new java.util.concurrent.atomic.AtomicLong();
      List<Thread> threads = new ArrayList<>();
      for (int i = 0; i < workers; i++) {
        threads.add(
            Thread.ofVirtual()
                .start(
                    () -> {
                      running.countDown();
                      while (!stop.get()) {
                        try (PooledConnection lease = pool.acquire()) {
                          lease.ping();
                          successfulPings.incrementAndGet();
                        } catch (io.github.orhaugh.chord.ChordException e) {
                          // The crash window: broken connections surface typed failures.
                        }
                      }
                    }));
      }
      assertThat(running.await(10, TimeUnit.SECONDS)).isTrue();

      // Two abrupt restarts while the workload runs: every live connection dies, the
      // listener itself stays up, exactly like a fast server restart.
      for (int restart = 0; restart < 2; restart++) {
        long before = successfulPings.get();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (successfulPings.get() < before + 25 && System.nanoTime() < deadline) {
          Thread.sleep(5);
        }
        server.dropAllConnections();
      }
      // The pool must recover: pings succeed again after the second restart.
      long afterRestart = successfulPings.get();
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      while (successfulPings.get() < afterRestart + 25 && System.nanoTime() < deadline) {
        Thread.sleep(5);
      }
      assertThat(successfulPings.get()).isGreaterThan(afterRestart);

      stop.set(true);
      for (Thread thread : threads) {
        thread.join(Duration.ofSeconds(10));
        assertThat(thread.isAlive()).isFalse();
      }
      // No permits leaked: the full pool capacity is still acquirable.
      List<PooledConnection> full = new ArrayList<>();
      for (int i = 0; i < 4; i++) {
        full.add(pool.acquire());
      }
      full.forEach(PooledConnection::close);
      assertThat(pool.activeCount()).isZero();
    }
  }
}
