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

import io.github.orhaugh.chord.protocol.ProtocolRevisions;
import io.github.orhaugh.chord.protocol.state.ConnectionState;
import io.github.orhaugh.chord.protocol.state.IllegalStateTransitionException;
import io.github.orhaugh.chord.testkit.ClickHouseServerContainer;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The native handshake and ping against a real ClickHouse server. The server image is selected by
 * {@code chord.testkit.clickhouse.image}; CI sweeps every supported release.
 */
@Testcontainers
class HandshakePingIT {

  @Container
  private static final ClickHouseServerContainer CLICKHOUSE = new ClickHouseServerContainer();

  private static ConnectionOptions.Builder options() {
    return ConnectionOptions.builder()
        .host(CLICKHOUSE.getHost())
        .port(CLICKHOUSE.nativePort())
        .database(CLICKHOUSE.database())
        .username(CLICKHOUSE.username())
        .password(CLICKHOUSE.password())
        .allowPlaintextPassword(true);
  }

  @Test
  void handshakeExposesServerIdentityAndNegotiatedRevision() {
    try (NativeConnection connection = NativeConnection.open(options().build())) {
      assertThat(connection.state()).isEqualTo(ConnectionState.READY);
      assertThat(connection.serverHello().serverName()).contains("ClickHouse");
      assertThat(connection.serverHello().timezone()).isPresent();
      assertThat(connection.serverHello().displayName()).isPresent();
      assertThat(connection.serverHello().serverRevision())
          .isGreaterThanOrEqualTo(ProtocolRevisions.MIN_SUPPORTED_SERVER_REVISION);
      assertThat(connection.negotiatedRevision())
          .isEqualTo(
              ProtocolRevisions.negotiate(
                  ProtocolRevisions.CURRENT, connection.serverHello().serverRevision()));
    }
  }

  @Test
  void pingWorksRepeatedlyOnOneConnection() {
    try (NativeConnection connection = NativeConnection.open(options().build())) {
      for (int i = 0; i < 5; i++) {
        connection.ping();
        assertThat(connection.state()).isEqualTo(ConnectionState.READY);
      }
    }
  }

  @Test
  void closedConnectionsRefuseFurtherUse() {
    NativeConnection connection = NativeConnection.open(options().build());
    connection.close();
    assertThatThrownBy(connection::ping).isInstanceOf(IllegalStateTransitionException.class);
    // Closing again is harmless.
    connection.close();
    assertThat(connection.state()).isEqualTo(ConnectionState.CLOSED);
  }

  @Test
  void connectionWithoutExplicitDatabaseUsesServerDefault() {
    try (NativeConnection connection = NativeConnection.open(options().database("").build())) {
      connection.ping();
      assertThat(connection.state()).isEqualTo(ConnectionState.READY);
    }
  }
}
