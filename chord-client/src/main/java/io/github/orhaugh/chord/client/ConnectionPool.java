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

import io.github.orhaugh.chord.ChordConfigurationException;
import io.github.orhaugh.chord.ChordException;
import io.github.orhaugh.chord.ChordTimeoutException;
import io.github.orhaugh.chord.annotations.Experimental;
import io.github.orhaugh.chord.protocol.state.ConnectionState;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A bounded pool of native connections with validation, eviction and leak diagnostics.
 *
 * <p>{@link #acquire()} blocks until a connection is available or the acquire timeout elapses; pair
 * it with try with resources so the lease always returns. Idle connections are validated with a
 * ping when they have rested longer than the validation interval, and are evicted once they exceed
 * the idle timeout or their maximum lifetime. Connections returned mid exchange or broken are
 * closed and discarded, never reused, preserving the connection reuse rules of the underlying
 * client. The pool never retries operations on behalf of callers.
 *
 * <p>Blocking waits park cheaply on virtual threads. All methods are thread safe.
 */
@Experimental
public final class ConnectionPool implements AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(ConnectionPool.class);

  private final Supplier<NativeConnection> factory;
  private final int maxSize;
  private final int minIdle;
  private final Duration acquireTimeout;
  private final Duration maxLifetime;
  private final Duration idleTimeout;
  private final Duration validationInterval;
  private final Duration leakDetectionThreshold;

  private final Semaphore permits;
  private final ConcurrentLinkedDeque<IdleEntry> idle = new ConcurrentLinkedDeque<>();
  private final ConcurrentHashMap<PooledConnection, Lease> leases = new ConcurrentHashMap<>();

  /** Tracks each pooled connection's open time across borrow and return cycles. */
  private final ConcurrentHashMap<NativeConnection, Long> opened = new ConcurrentHashMap<>();

  private final AtomicBoolean closed = new AtomicBoolean();
  private final AtomicLong detectedLeaks = new AtomicLong();
  private final AtomicLong acquires = new AtomicLong();
  private final AtomicLong acquireTimeouts = new AtomicLong();
  private final AtomicLong acquireWaitNanos = new AtomicLong();
  private final AtomicLong connectionsOpened = new AtomicLong();
  private final AtomicLong connectionsDiscarded = new AtomicLong();
  private final Thread evictor;

  private record IdleEntry(
      NativeConnection connection,
      long openedNanos,
      long idleSinceNanos,
      long lastValidatedNanos) {}

  private static final class Lease {
    final long acquiredNanos;
    final long openedNanos;
    final Throwable acquireSite;
    volatile boolean leakReported;

    Lease(long acquiredNanos, long openedNanos, Throwable acquireSite) {
      this.acquiredNanos = acquiredNanos;
      this.openedNanos = openedNanos;
      this.acquireSite = acquireSite;
    }
  }

  private ConnectionPool(Builder builder) {
    this.factory = builder.factory;
    this.maxSize = builder.maxSize;
    this.minIdle = builder.minIdle;
    this.acquireTimeout = builder.acquireTimeout;
    this.maxLifetime = builder.maxLifetime;
    this.idleTimeout = builder.idleTimeout;
    this.validationInterval = builder.validationInterval;
    this.leakDetectionThreshold = builder.leakDetectionThreshold;
    this.permits = new Semaphore(builder.maxSize, true);
    long shortestConcern = Math.min(idleTimeout.toMillis(), maxLifetime.toMillis());
    if (!leakDetectionThreshold.isZero()) {
      shortestConcern = Math.min(shortestConcern, leakDetectionThreshold.toMillis());
    }
    if (minIdle > 0 && !validationInterval.isZero()) {
      shortestConcern = Math.min(shortestConcern, validationInterval.toMillis());
    }
    long periodMillis = Math.clamp(shortestConcern / 4, 250, 30000);
    this.evictor =
        Thread.ofVirtual()
            .name("chord-pool-evictor")
            .start(
                () -> {
                  // Maintain first so a configured minIdle warms up right after build().
                  while (!closed.get()) {
                    try {
                      evictExpiredIdle();
                      keepAliveIdle();
                      topUpIdle();
                      reportLeaks();
                      Thread.sleep(periodMillis);
                    } catch (InterruptedException e) {
                      return;
                    } catch (RuntimeException e) {
                      LOG.warn("pool maintenance failed", e);
                    }
                  }
                });
  }

  /**
   * Creates a builder for a pool opening connections with the supplied options.
   *
   * @param options connection configuration used for every pooled connection
   * @return a new builder
   */
  public static Builder builder(ConnectionOptions options) {
    Objects.requireNonNull(options, "options");
    return new Builder(() -> NativeConnection.open(options));
  }

  /**
   * Creates a builder over a custom connection factory. Used by endpoint aware factories; most
   * callers want {@link #builder(ConnectionOptions)}.
   *
   * @param factory opens a new connection; every failure should throw a {@link ChordException}
   * @return a new builder
   */
  public static Builder builder(Supplier<NativeConnection> factory) {
    return new Builder(Objects.requireNonNull(factory, "factory"));
  }

  /**
   * Borrows a connection, opening one when the pool is below capacity and nothing idle is valid.
   *
   * @return a lease that must be closed to return the connection
   * @throws ChordTimeoutException when the acquire timeout elapses with the pool exhausted
   */
  public PooledConnection acquire() {
    JfrEvents.PoolAcquireEvent event = new JfrEvents.PoolAcquireEvent();
    event.begin();
    try {
      PooledConnection lease = doAcquire(event);
      event.succeeded = true;
      return lease;
    } finally {
      event.commit();
    }
  }

  private PooledConnection doAcquire(JfrEvents.PoolAcquireEvent event) {
    ensureOpen();
    boolean acquired;
    long waitStart = System.nanoTime();
    try {
      acquired = permits.tryAcquire(acquireTimeout.toNanos(), TimeUnit.NANOSECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      acquireWaitNanos.addAndGet(System.nanoTime() - waitStart);
      acquireTimeouts.incrementAndGet();
      throw new ChordTimeoutException("Interrupted while waiting for a pooled connection", e);
    }
    acquireWaitNanos.addAndGet(System.nanoTime() - waitStart);
    if (!acquired) {
      acquireTimeouts.incrementAndGet();
      throw new ChordTimeoutException(
          "Timed out after "
              + acquireTimeout
              + " waiting for a pooled connection; "
              + maxSize
              + " are in use");
    }
    try {
      ensureOpen();
      NativeConnection connection = pollValidIdle();
      long openedNanos;
      if (connection == null) {
        connection = factory.get();
        connectionsOpened.incrementAndGet();
        openedNanos = System.nanoTime();
        event.openedNewConnection = true;
      } else {
        openedNanos = openedNanosOf(connection);
      }
      acquires.incrementAndGet();
      PooledConnection lease = new PooledConnection(this, connection);
      Throwable acquireSite =
          leakDetectionThreshold.isZero()
              ? null
              : new Throwable("Acquire site of a possibly leaked lease");
      leases.put(lease, new Lease(System.nanoTime(), openedNanos, acquireSite));
      opened.put(connection, openedNanos);
      return lease;
    } catch (RuntimeException e) {
      permits.release();
      throw e;
    }
  }

  private long openedNanosOf(NativeConnection connection) {
    return opened.getOrDefault(connection, System.nanoTime());
  }

  /** Returns an idle connection that passed validation, or {@code null}. */
  private NativeConnection pollValidIdle() {
    IdleEntry entry;
    while ((entry = idle.pollFirst()) != null) {
      NativeConnection connection = entry.connection();
      long now = System.nanoTime();
      if (connection.state() != ConnectionState.READY) {
        discard(connection);
        continue;
      }
      if (now - entry.openedNanos() >= maxLifetime.toNanos()) {
        LOG.debug("connection {} exceeded its lifetime; evicting", connection.id());
        discard(connection);
        continue;
      }
      if (now - entry.lastValidatedNanos() >= validationInterval.toNanos()) {
        try {
          connection.ping();
        } catch (ChordException e) {
          LOG.debug("idle connection {} failed validation; discarding", connection.id());
          discard(connection);
          continue;
        }
      }
      return connection;
    }
    return null;
  }

  /** Called by a lease when it closes. */
  void release(PooledConnection lease, NativeConnection connection) {
    Lease info = leases.remove(lease);
    try {
      if (closed.get()) {
        discard(connection);
        return;
      }
      if (connection.state() != ConnectionState.READY) {
        LOG.debug(
            "connection {} returned in state {}; discarding", connection.id(), connection.state());
        discard(connection);
        return;
      }
      long openedNanos = info != null ? info.openedNanos : openedNanosOf(connection);
      if (System.nanoTime() - openedNanos >= maxLifetime.toNanos()) {
        discard(connection);
        return;
      }
      long now = System.nanoTime();
      // A returned connection just finished a successful exchange: it counts as validated.
      idle.addFirst(new IdleEntry(connection, openedNanos, now, now));
    } finally {
      permits.release();
    }
  }

  private void discard(NativeConnection connection) {
    connectionsDiscarded.incrementAndGet();
    opened.remove(connection);
    connection.close();
  }

  private void evictExpiredIdle() {
    long now = System.nanoTime();
    // Iterate a snapshot; entries borrowed meanwhile are simply skipped by remove failing.
    for (IdleEntry entry : idle) {
      boolean unusable =
          now - entry.openedNanos() >= maxLifetime.toNanos()
              || entry.connection().state() != ConnectionState.READY;
      // The idle timeout shrinks the pool only above the configured floor; lifetime and
      // state problems always evict (the top up puts fresh connections back).
      boolean pastIdleTimeout =
          now - entry.idleSinceNanos() >= idleTimeout.toNanos() && idle.size() > minIdle;
      if ((unusable || pastIdleTimeout) && idle.remove(entry)) {
        LOG.debug("evicting idle connection {}", entry.connection().id());
        discard(entry.connection());
      }
    }
  }

  /**
   * Pings idle connections whose last validation is older than the validation interval, so load
   * balancer and NAT idle timers never see a silent connection. Failures discard the connection;
   * the top up replaces it. Skipped when the interval is zero, which means validate on every borrow
   * instead.
   */
  private void keepAliveIdle() {
    if (validationInterval.isZero()) {
      return;
    }
    long now = System.nanoTime();
    for (IdleEntry entry : idle) {
      if (now - entry.lastValidatedNanos() < validationInterval.toNanos()) {
        continue;
      }
      if (!idle.remove(entry)) {
        continue; // borrowed meanwhile
      }
      try {
        entry.connection().ping();
        idle.addLast(
            new IdleEntry(
                entry.connection(),
                entry.openedNanos(),
                entry.idleSinceNanos(),
                System.nanoTime()));
      } catch (ChordException e) {
        LOG.debug("idle connection {} failed keep alive; discarding", entry.connection().id());
        discard(entry.connection());
      }
    }
  }

  /** Opens connections until the idle floor is met, bounded by the pool's capacity. */
  private void topUpIdle() {
    while (!closed.get() && idle.size() < minIdle && idle.size() + leases.size() < maxSize) {
      NativeConnection connection;
      try {
        connection = factory.get();
      } catch (RuntimeException e) {
        // The server may be unreachable right now; acquire() reports its own failures and
        // the next maintenance cycle tries again.
        LOG.debug("pool warm up could not open a connection", e);
        return;
      }
      connectionsOpened.incrementAndGet();
      long now = System.nanoTime();
      opened.put(connection, now);
      idle.addLast(new IdleEntry(connection, now, now, now));
    }
  }

  private void reportLeaks() {
    if (leakDetectionThreshold.isZero()) {
      return;
    }
    long thresholdNanos = leakDetectionThreshold.toNanos();
    long now = System.nanoTime();
    leases.forEach(
        (lease, info) -> {
          if (now - info.acquiredNanos >= thresholdNanos && !info.leakReported) {
            info.leakReported = true;
            detectedLeaks.incrementAndGet();
            LOG.warn(
                "A pooled connection lease has been held for over {}; acquired at",
                leakDetectionThreshold,
                info.acquireSite);
          }
        });
  }

  /**
   * Returns the number of leases currently borrowed.
   *
   * @return the active lease count
   */
  public int activeCount() {
    return leases.size();
  }

  /**
   * Returns the number of idle pooled connections.
   *
   * @return the idle connection count
   */
  public int idleCount() {
    return idle.size();
  }

  /** Returns how many leaked leases the maintenance thread has reported so far. */
  long detectedLeaks() {
    return detectedLeaks.get();
  }

  /**
   * A monotonic snapshot of the pool's lifetime counters, suitable for metric registries: counts
   * only ever grow, and {@code acquireWaitNanos} over {@code acquires} plus {@code acquireTimeouts}
   * gives the mean wait.
   *
   * @param acquires leases handed out
   * @param acquireTimeouts acquire attempts that timed out or were interrupted
   * @param acquireWaitNanos total time spent waiting for a permit, nanoseconds
   * @param connectionsOpened connections opened, by borrows and by warm up
   * @param connectionsDiscarded connections closed by the pool for any reason
   * @param leaksDetected leases reported as leaked by the maintenance thread
   */
  public record PoolStats(
      long acquires,
      long acquireTimeouts,
      long acquireWaitNanos,
      long connectionsOpened,
      long connectionsDiscarded,
      long leaksDetected) {}

  /**
   * Returns a snapshot of the pool's lifetime counters.
   *
   * @return the current counter values
   */
  public PoolStats stats() {
    return new PoolStats(
        acquires.get(),
        acquireTimeouts.get(),
        acquireWaitNanos.get(),
        connectionsOpened.get(),
        connectionsDiscarded.get(),
        detectedLeaks.get());
  }

  private void ensureOpen() {
    if (closed.get()) {
      throw new IllegalStateException("The pool is closed");
    }
  }

  /**
   * Closes the pool: idle connections are closed immediately, and borrowed connections are closed
   * as their leases return. Waiting acquirers fail. Idempotent.
   */
  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) {
      return;
    }
    evictor.interrupt();
    IdleEntry entry;
    while ((entry = idle.pollFirst()) != null) {
      discard(entry.connection());
    }
    LOG.debug("pool closed with {} leases outstanding", leases.size());
  }

  /** Builder for {@link ConnectionPool}. */
  public static final class Builder {

    private final Supplier<NativeConnection> factory;
    private int maxSize = 8;
    private int minIdle;
    private Duration acquireTimeout = Duration.ofSeconds(30);
    private Duration maxLifetime = Duration.ofMinutes(30);
    private Duration idleTimeout = Duration.ofMinutes(10);
    private Duration validationInterval = Duration.ofSeconds(30);
    private Duration leakDetectionThreshold = Duration.ZERO;

    private Builder(Supplier<NativeConnection> factory) {
      this.factory = factory;
    }

    /**
     * Sets the maximum number of connections, borrowed plus idle. Defaults to 8.
     *
     * @param maxSize the bound, at least 1
     * @return this builder
     */
    public Builder maxSize(int maxSize) {
      if (maxSize < 1) {
        throw new ChordConfigurationException("maxSize must be at least 1");
      }
      this.maxSize = maxSize;
      return this;
    }

    /**
     * Sets the idle connection floor. The maintenance thread opens connections in the background
     * until this many sit idle, both at start (so first requests skip the connection handshake) and
     * after evictions, and pings them on the validation cadence so load balancer and NAT idle
     * timers never fire. Defaults to zero: fully lazy.
     *
     * @param minIdle the floor, zero or positive and at most {@code maxSize}
     * @return this builder
     */
    public Builder minIdle(int minIdle) {
      if (minIdle < 0) {
        throw new ChordConfigurationException("minIdle must not be negative");
      }
      this.minIdle = minIdle;
      return this;
    }

    /**
     * Sets how long {@link #acquire()} waits for a connection before failing. Defaults to 30
     * seconds.
     *
     * @param timeout the wait bound, positive
     * @return this builder
     */
    public Builder acquireTimeout(Duration timeout) {
      this.acquireTimeout = requirePositive(timeout, "acquireTimeout");
      return this;
    }

    /**
     * Sets the maximum lifetime of a pooled connection; older connections are closed instead of
     * reused, bounding the impact of server side session state and network path changes. Defaults
     * to 30 minutes.
     *
     * @param lifetime the lifetime bound, positive
     * @return this builder
     */
    public Builder maxLifetime(Duration lifetime) {
      this.maxLifetime = requirePositive(lifetime, "maxLifetime");
      return this;
    }

    /**
     * Sets how long a connection may sit idle before eviction. Defaults to 10 minutes.
     *
     * @param timeout the idle bound, positive
     * @return this builder
     */
    public Builder idleTimeout(Duration timeout) {
      this.idleTimeout = requirePositive(timeout, "idleTimeout");
      return this;
    }

    /**
     * Sets how long a connection may rest before a borrow validates it with a ping. Zero pings on
     * every borrow. Defaults to 30 seconds.
     *
     * @param interval the validation interval, zero or positive
     * @return this builder
     */
    public Builder validationInterval(Duration interval) {
      Objects.requireNonNull(interval, "interval");
      if (interval.isNegative()) {
        throw new ChordConfigurationException("validationInterval must not be negative");
      }
      this.validationInterval = interval;
      return this;
    }

    /**
     * Enables leak diagnostics: leases held longer than the threshold are reported once, with the
     * stack trace of the acquire site, by the maintenance thread. Zero disables the diagnostics,
     * the default.
     *
     * @param threshold the hold duration considered a leak, zero to disable
     * @return this builder
     */
    public Builder leakDetectionThreshold(Duration threshold) {
      Objects.requireNonNull(threshold, "threshold");
      if (threshold.isNegative()) {
        throw new ChordConfigurationException("leakDetectionThreshold must not be negative");
      }
      this.leakDetectionThreshold = threshold;
      return this;
    }

    private static Duration requirePositive(Duration value, String name) {
      Objects.requireNonNull(value, name);
      if (value.isZero() || value.isNegative()) {
        throw new ChordConfigurationException(name + " must be positive");
      }
      return value;
    }

    /**
     * Builds the pool. Connections open lazily on first acquire unless {@link #minIdle(int)}
     * configures a warm floor, which the maintenance thread fills in the background.
     *
     * @return the pool
     */
    public ConnectionPool build() {
      if (minIdle > maxSize) {
        throw new ChordConfigurationException(
            "minIdle (" + minIdle + ") must not exceed maxSize (" + maxSize + ")");
      }
      return new ConnectionPool(this);
    }
  }
}
