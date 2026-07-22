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
import io.github.orhaugh.chord.ChordTransportException;
import io.github.orhaugh.chord.annotations.Experimental;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Opens connections across multiple endpoints with health tracking and failover.
 *
 * <p>Each {@link #connect()} walks the endpoint list in the order the {@link LoadBalancingPolicy}
 * dictates, skipping endpoints that are backing off after consecutive failures, and returns the
 * first connection that opens. An endpoint's backoff doubles with each consecutive failure, up to
 * the maximum, with jitter so a fleet of clients does not retry in lockstep; any success resets it.
 * When every endpoint is down or backing off, the attempt walks all endpoints regardless of backoff
 * rather than failing without trying, so recovery is discovered as soon as it happens.
 *
 * <p>Failover applies to opening connections, never to retrying operations on them: an operation
 * that failed mid exchange has a {@linkplain io.github.orhaugh.chord.RetryClass retry
 * classification} and retrying is the caller's decision.
 *
 * <p>Hostnames resolve at each attempt, so DNS based failover takes effect without restarts. Use
 * {@link #asFactory()} to plug a connector into {@link ConnectionPool#builder(Supplier)}.
 */
@Experimental
public final class FailoverConnector {

  private static final Logger LOG = LoggerFactory.getLogger(FailoverConnector.class);

  private final List<Endpoint> endpoints;
  private final ConnectionOptions template;
  private final LoadBalancingPolicy policy;
  private final Duration initialBackoff;
  private final Duration maxBackoff;
  private final AtomicInteger rotation = new AtomicInteger();
  private final ConcurrentHashMap<Endpoint, Health> health = new ConcurrentHashMap<>();

  private static final class Health {
    volatile int consecutiveFailures;
    volatile long retryAtNanos;
  }

  private FailoverConnector(Builder builder) {
    this.endpoints = List.copyOf(builder.endpoints);
    this.template = builder.template;
    this.policy = builder.policy;
    this.initialBackoff = builder.initialBackoff;
    this.maxBackoff = builder.maxBackoff;
  }

  /**
   * Creates a builder over connection options used for every endpoint; the host and port of the
   * options are replaced per endpoint, everything else applies unchanged.
   *
   * @param template connection configuration shared by all endpoints
   * @return a new builder
   */
  public static Builder builder(ConnectionOptions template) {
    return new Builder(Objects.requireNonNull(template, "template"));
  }

  /**
   * Opens a connection to the first healthy endpoint the policy yields.
   *
   * @return a connection in READY state
   * @throws ChordTransportException when every endpoint failed; the last failure is the cause and
   *     earlier ones are suppressed
   */
  public NativeConnection connect() {
    List<Endpoint> order = orderedEndpoints();
    List<Endpoint> skipped = new ArrayList<>();
    ChordException lastFailure = null;
    long now = System.nanoTime();
    for (Endpoint endpoint : order) {
      Health h = healthOf(endpoint);
      if (h.retryAtNanos - now > 0) {
        skipped.add(endpoint);
        continue;
      }
      NativeConnection connection = tryEndpoint(endpoint);
      if (connection != null) {
        return connection;
      }
      lastFailure = lastFailureOf(endpoint);
    }
    // Everything eligible failed; give backing off endpoints one desperate walk so an already
    // recovered endpoint is found now instead of after its backoff expires.
    for (Endpoint endpoint : skipped) {
      NativeConnection connection = tryEndpoint(endpoint);
      if (connection != null) {
        return connection;
      }
      lastFailure = lastFailureOf(endpoint);
    }
    ChordTransportException failure =
        new ChordTransportException("No endpoint accepted a connection: " + endpoints, lastFailure);
    failure.classifiedAs(io.github.orhaugh.chord.RetryClass.SAFE_TO_RETRY);
    throw failure;
  }

  /** Remembers the last failure per endpoint for the aggregate error message. */
  private final ConcurrentHashMap<Endpoint, ChordException> lastFailures =
      new ConcurrentHashMap<>();

  private ChordException lastFailureOf(Endpoint endpoint) {
    return lastFailures.get(endpoint);
  }

  private NativeConnection tryEndpoint(Endpoint endpoint) {
    try {
      NativeConnection connection =
          NativeConnection.open(template.withEndpoint(endpoint.host(), endpoint.port()));
      markSuccess(endpoint);
      return connection;
    } catch (ChordException e) {
      markFailure(endpoint);
      lastFailures.put(endpoint, e);
      LOG.debug("endpoint {} failed to connect: {}", endpoint, e.getMessage());
      return null;
    }
  }

  private void markSuccess(Endpoint endpoint) {
    Health h = healthOf(endpoint);
    h.consecutiveFailures = 0;
    h.retryAtNanos = 0;
  }

  private void markFailure(Endpoint endpoint) {
    Health h = healthOf(endpoint);
    int failures = Math.min(h.consecutiveFailures + 1, 20);
    h.consecutiveFailures = failures;
    long base = initialBackoff.toNanos() << Math.min(failures - 1, 10);
    long capped = Math.min(base, maxBackoff.toNanos());
    // Full jitter: a uniformly random wait up to the computed backoff.
    long jittered = capped <= 0 ? 0 : ThreadLocalRandom.current().nextLong(capped) + 1;
    h.retryAtNanos = System.nanoTime() + jittered;
  }

  private Health healthOf(Endpoint endpoint) {
    return health.computeIfAbsent(endpoint, e -> new Health());
  }

  private List<Endpoint> orderedEndpoints() {
    return switch (policy) {
      case IN_ORDER -> endpoints;
      case ROUND_ROBIN -> rotate(Math.floorMod(rotation.getAndIncrement(), endpoints.size()));
      case RANDOM -> rotate(ThreadLocalRandom.current().nextInt(endpoints.size()));
    };
  }

  private List<Endpoint> rotate(int start) {
    List<Endpoint> order = new ArrayList<>(endpoints.size());
    for (int i = 0; i < endpoints.size(); i++) {
      order.add(endpoints.get((start + i) % endpoints.size()));
    }
    return order;
  }

  /**
   * Reports whether an endpoint is currently backing off after failures.
   *
   * @param endpoint the endpoint to inspect
   * @return {@code true} while new attempts skip the endpoint
   */
  public boolean isBackingOff(Endpoint endpoint) {
    return healthOf(endpoint).retryAtNanos - System.nanoTime() > 0;
  }

  /**
   * Adapts this connector to the pool's factory contract.
   *
   * @return a supplier opening one connection per call
   */
  public Supplier<NativeConnection> asFactory() {
    return this::connect;
  }

  /** Builder for {@link FailoverConnector}. */
  public static final class Builder {

    private final ConnectionOptions template;
    private final List<Endpoint> endpoints = new ArrayList<>();
    private LoadBalancingPolicy policy = LoadBalancingPolicy.IN_ORDER;
    private Duration initialBackoff = Duration.ofSeconds(1);
    private Duration maxBackoff = Duration.ofSeconds(30);

    private Builder(ConnectionOptions template) {
      this.template = template;
    }

    /**
     * Adds an endpoint to try.
     *
     * @param endpoint the server address
     * @return this builder
     */
    public Builder endpoint(Endpoint endpoint) {
      endpoints.add(Objects.requireNonNull(endpoint, "endpoint"));
      return this;
    }

    /**
     * Adds an endpoint to try.
     *
     * @param host server hostname or address
     * @param port native protocol port
     * @return this builder
     */
    public Builder endpoint(String host, int port) {
      return endpoint(Endpoint.of(host, port));
    }

    /**
     * Sets the endpoint ordering policy. Defaults to {@link LoadBalancingPolicy#IN_ORDER}.
     *
     * @param policy the ordering policy
     * @return this builder
     */
    public Builder policy(LoadBalancingPolicy policy) {
      this.policy = Objects.requireNonNull(policy, "policy");
      return this;
    }

    /**
     * Sets the backoff applied after an endpoint's first consecutive failure; it doubles per
     * further failure up to the maximum, with jitter. Defaults to one second.
     *
     * @param backoff the initial backoff, positive
     * @return this builder
     */
    public Builder initialBackoff(Duration backoff) {
      this.initialBackoff = requirePositive(backoff, "initialBackoff");
      return this;
    }

    /**
     * Sets the backoff ceiling. Defaults to 30 seconds.
     *
     * @param backoff the maximum backoff, positive
     * @return this builder
     */
    public Builder maxBackoff(Duration backoff) {
      this.maxBackoff = requirePositive(backoff, "maxBackoff");
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
     * Builds the connector.
     *
     * @return the connector
     */
    public FailoverConnector build() {
      if (endpoints.isEmpty()) {
        throw new ChordConfigurationException("At least one endpoint is required");
      }
      return new FailoverConnector(this);
    }
  }
}
