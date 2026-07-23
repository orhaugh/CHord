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
package io.github.orhaugh.chord.observability;

import io.github.orhaugh.chord.annotations.Experimental;
import io.github.orhaugh.chord.client.ConnectionPool;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.util.Objects;

/**
 * Binds a {@link ConnectionPool} to a Micrometer registry.
 *
 * <p>Registered meters, all tagged {@code pool}:
 *
 * <ul>
 *   <li>{@code chord.pool.connections.active}: leases currently borrowed
 *   <li>{@code chord.pool.connections.idle}: connections resting in the pool
 * </ul>
 *
 * <p>Per operation timing (connects, queries, inserts, pool acquire waits) is emitted as JDK Flight
 * Recorder events by {@code chord-client} itself, viewable in JDK Mission Control under the CHord
 * category; Micrometer here covers steady state gauges. OpenTelemetry export is available through
 * any Micrometer registry bridge, without a CHord dependency on OpenTelemetry.
 */
@Experimental
public final class ChordPoolMetrics {

  private ChordPoolMetrics() {}

  /**
   * Registers the pool's gauges.
   *
   * <p>Gauges hold a strong reference to the pool, so binding does not change its lifecycle; meters
   * of a closed pool simply report its final counts.
   *
   * @param pool the pool to expose
   * @param registry the registry to bind into
   * @param poolName value of the {@code pool} tag distinguishing multiple pools
   */
  public static void bind(ConnectionPool pool, MeterRegistry registry, String poolName) {
    Objects.requireNonNull(pool, "pool");
    Objects.requireNonNull(registry, "registry");
    Objects.requireNonNull(poolName, "poolName");
    Tags tags = Tags.of("pool", poolName);
    Gauge.builder("chord.pool.connections.active", pool, ConnectionPool::activeCount)
        .tags(tags)
        .description("Pooled connections currently leased out")
        .register(registry);
    Gauge.builder("chord.pool.connections.idle", pool, ConnectionPool::idleCount)
        .tags(tags)
        .description("Pooled connections resting in the pool")
        .register(registry);
    io.micrometer.core.instrument.FunctionCounter.builder(
            "chord.pool.acquires", pool, p -> p.stats().acquires())
        .tags(tags)
        .description("Leases handed out")
        .register(registry);
    io.micrometer.core.instrument.FunctionCounter.builder(
            "chord.pool.acquire.timeouts", pool, p -> p.stats().acquireTimeouts())
        .tags(tags)
        .description("Acquire attempts that timed out or were interrupted")
        .register(registry);
    io.micrometer.core.instrument.FunctionCounter.builder(
            "chord.pool.connections.opened", pool, p -> p.stats().connectionsOpened())
        .tags(tags)
        .description("Connections opened by borrows and warm up")
        .register(registry);
    io.micrometer.core.instrument.FunctionCounter.builder(
            "chord.pool.connections.discarded", pool, p -> p.stats().connectionsDiscarded())
        .tags(tags)
        .description("Connections closed by the pool")
        .register(registry);
    io.micrometer.core.instrument.FunctionCounter.builder(
            "chord.pool.leaks", pool, p -> p.stats().leaksDetected())
        .tags(tags)
        .description("Leases reported as leaked")
        .register(registry);
    io.micrometer.core.instrument.FunctionTimer.builder(
            "chord.pool.acquire.wait",
            pool,
            p -> p.stats().acquires() + p.stats().acquireTimeouts(),
            p -> p.stats().acquireWaitNanos(),
            java.util.concurrent.TimeUnit.NANOSECONDS)
        .tags(tags)
        .description("Time spent waiting for a pool permit")
        .register(registry);
  }
}
