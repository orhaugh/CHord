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

import io.github.orhaugh.chord.ChordTransportException;
import io.github.orhaugh.chord.RetryClass;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Endpoint ordering, health backoff and failover across loopback servers. */
@Timeout(60)
class FailoverConnectorTest {

  private static ConnectionOptions template() {
    // Host and port are replaced per endpoint; these values are never dialled.
    return ConnectionOptions.builder().host("placeholder").port(9000).build();
  }

  private static String hostOf(NativeConnection connection) {
    return "127.0.0.1:" + ((InetSocketAddress) connection.remoteAddress()).getPort();
  }

  @Test
  void inOrderPolicyFailsOverToTheNextEndpointAndBacksOffTheDeadOne() throws Exception {
    try (LoopbackPingServer primary = new LoopbackPingServer();
        LoopbackPingServer standby = new LoopbackPingServer()) {
      FailoverConnector connector =
          FailoverConnector.builder(template())
              .endpoint("127.0.0.1", primary.port())
              .endpoint("127.0.0.1", standby.port())
              .initialBackoff(Duration.ofSeconds(30))
              .build();

      try (NativeConnection connection = connector.connect()) {
        assertThat(hostOf(connection)).endsWith(":" + primary.port());
      }

      // Break the primary at the protocol level (accept then close, no hello): deterministic,
      // unlike freeing its port, which another socket could reclaim mid test.
      primary.refuseNewConnections(true);
      try (NativeConnection connection = connector.connect()) {
        assertThat(hostOf(connection)).endsWith(":" + standby.port());
      }
      assertThat(connector.isBackingOff(Endpoint.of("127.0.0.1", primary.port()))).isTrue();
      assertThat(connector.isBackingOff(Endpoint.of("127.0.0.1", standby.port()))).isFalse();

      // While the primary backs off, connects go straight to the standby.
      try (NativeConnection connection = connector.connect()) {
        assertThat(hostOf(connection)).endsWith(":" + standby.port());
      }
    }
  }

  @Test
  void roundRobinRotatesAcrossHealthyEndpoints() throws Exception {
    try (LoopbackPingServer first = new LoopbackPingServer();
        LoopbackPingServer second = new LoopbackPingServer()) {
      FailoverConnector connector =
          FailoverConnector.builder(template())
              .endpoint("127.0.0.1", first.port())
              .endpoint("127.0.0.1", second.port())
              .policy(LoadBalancingPolicy.ROUND_ROBIN)
              .build();
      Set<String> seen = new HashSet<>();
      for (int i = 0; i < 4; i++) {
        try (NativeConnection connection = connector.connect()) {
          seen.add(hostOf(connection));
        }
      }
      assertThat(seen)
          .containsExactlyInAnyOrder("127.0.0.1:" + first.port(), "127.0.0.1:" + second.port());
    }
  }

  @Test
  void whenEveryEndpointIsDownTheFailureAggregatesAndClassifiesSafeToRetry() throws Exception {
    // The endpoint accepts and immediately closes without a hello: a deterministic handshake
    // failure, unlike a freed port, which another process can reclaim mid test.
    try (LoopbackPingServer broken = new LoopbackPingServer()) {
      broken.refuseNewConnections(true);
      FailoverConnector connector =
          FailoverConnector.builder(template()).endpoint("127.0.0.1", broken.port()).build();
      assertThatThrownBy(connector::connect)
          .isInstanceOf(ChordTransportException.class)
          .hasMessageContaining("No endpoint accepted a connection")
          .satisfies(
              e ->
                  assertThat(((ChordTransportException) e).retryClass())
                      .isEqualTo(RetryClass.SAFE_TO_RETRY));
    }
  }

  @Test
  void randomPolicyReachesEveryHealthyEndpoint() throws Exception {
    try (LoopbackPingServer first = new LoopbackPingServer();
        LoopbackPingServer second = new LoopbackPingServer()) {
      FailoverConnector connector =
          FailoverConnector.builder(template())
              .endpoint("127.0.0.1", first.port())
              .endpoint("127.0.0.1", second.port())
              .policy(LoadBalancingPolicy.RANDOM)
              .build();
      Set<String> seen = new HashSet<>();
      for (int i = 0; i < 24 && seen.size() < 2; i++) {
        try (NativeConnection connection = connector.connect()) {
          seen.add(hostOf(connection));
        }
      }
      assertThat(seen)
          .containsExactlyInAnyOrder("127.0.0.1:" + first.port(), "127.0.0.1:" + second.port());
    }
  }

  @Test
  void backoffIsCappedAtTheConfiguredMaximum() throws Exception {
    try (LoopbackPingServer only = new LoopbackPingServer()) {
      FailoverConnector connector =
          FailoverConnector.builder(template())
              .endpoint("127.0.0.1", only.port())
              .initialBackoff(Duration.ofMillis(50))
              .maxBackoff(Duration.ofMillis(120))
              .build();
      only.refuseNewConnections(true);
      // Eight consecutive failures would push uncapped exponential backoff into seconds;
      // with the cap plus full jitter every wait stays at or below 120 milliseconds.
      for (int i = 0; i < 8; i++) {
        try {
          connector.connect().close();
        } catch (RuntimeException e) {
          // Expected: the endpoint refuses handshakes.
        }
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(400);
        while (connector.isBackingOff(Endpoint.of("127.0.0.1", only.port()))
            && System.nanoTime() < deadline) {
          Thread.sleep(10);
        }
        assertThat(connector.isBackingOff(Endpoint.of("127.0.0.1", only.port())))
            .as("backoff after failure " + (i + 1) + " must clear within the 120ms cap")
            .isFalse();
      }
    }
  }

  @Test
  void backedOffEndpointsAreStillTriedWhenNothingElseIsLeft() throws Exception {
    try (LoopbackPingServer only = new LoopbackPingServer()) {
      FailoverConnector connector =
          FailoverConnector.builder(template())
              .endpoint("127.0.0.1", only.port())
              .initialBackoff(Duration.ofMinutes(5))
              .build();
      // Refusing the handshake fails the endpoint and starts a long backoff.
      only.refuseNewConnections(true);
      assertThatThrownBy(connector::connect).isInstanceOf(ChordTransportException.class);
      assertThat(connector.isBackingOff(Endpoint.of("127.0.0.1", only.port()))).isTrue();
      // The endpoint recovers; the desperate walk finds it despite the backoff.
      only.refuseNewConnections(false);
      try (NativeConnection connection = connector.connect()) {
        assertThat(connection.state().name()).isEqualTo("READY");
      }
      assertThat(connector.isBackingOff(Endpoint.of("127.0.0.1", only.port()))).isFalse();
    }
  }

  @Test
  void theConnectorPlugsIntoThePoolAsAFactory() throws Exception {
    try (LoopbackPingServer primary = new LoopbackPingServer();
        LoopbackPingServer standby = new LoopbackPingServer()) {
      FailoverConnector connector =
          FailoverConnector.builder(template())
              .endpoint("127.0.0.1", primary.port())
              .endpoint("127.0.0.1", standby.port())
              .build();
      try (ConnectionPool pool =
          ConnectionPool.builder(connector.asFactory())
              .maxSize(2)
              .validationInterval(Duration.ZERO)
              .build()) {
        try (PooledConnection lease = pool.acquire()) {
          lease.ping();
          assertThat(hostOf(lease.connection())).endsWith(":" + primary.port());
        }
        // The primary dies: established connections drop and new ones are refused at the
        // protocol level. The pool discards the dead connection on validation and the
        // factory fails over to the standby.
        primary.refuseNewConnections(true);
        primary.dropAllConnections();
        try (PooledConnection lease = pool.acquire()) {
          lease.ping();
          assertThat(hostOf(lease.connection())).endsWith(":" + standby.port());
        }
      }
    }
  }
}
