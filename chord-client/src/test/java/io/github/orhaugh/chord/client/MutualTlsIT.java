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

import io.github.orhaugh.chord.ChordException;
import io.github.orhaugh.chord.protocol.state.ConnectionState;
import io.github.orhaugh.chord.testkit.ClickHouseServerContainer;
import io.github.orhaugh.chord.testkit.TestCertificates;
import io.github.orhaugh.chord.transport.TlsOptions;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Mutual TLS against a real ClickHouse server configured with strict client certificate
 * verification: the handshake requires a client certificate signed by the test CA.
 */
@Testcontainers
class MutualTlsIT {

  private static final TestCertificates CERTS;
  private static final Path CA_PEM;
  private static final Path CLIENT_CERT_PEM;
  private static final Path CLIENT_KEY_PEM;

  @Container private static final ClickHouseServerContainer CLICKHOUSE;

  static {
    ClickHouseServerContainer container = new ClickHouseServerContainer();
    String dockerHost = container.getHost();
    List<String> dnsSans = new ArrayList<>(List.of("localhost"));
    List<String> ipSans = new ArrayList<>();
    if (dockerHost.chars().allMatch(c -> c == '.' || (c >= '0' && c <= '9'))) {
      ipSans.add(dockerHost);
    } else if (!dnsSans.contains(dockerHost)) {
      dnsSans.add(dockerHost);
    }
    CERTS = TestCertificates.generate(dnsSans, ipSans);
    Path directory = tempDirectory();
    CA_PEM = TestCertificates.write(directory, "ca.crt", CERTS.caCertificatePem());
    CLIENT_CERT_PEM = TestCertificates.write(directory, "client.crt", CERTS.clientCertificatePem());
    CLIENT_KEY_PEM = TestCertificates.write(directory, "client.key", CERTS.clientKeyPem());
    CLICKHOUSE =
        container.withSecureNativePort(
            CERTS, ClickHouseServerContainer.ClientCertificateMode.REQUIRED);
  }

  private static Path tempDirectory() {
    try {
      return Files.createTempDirectory("chord-mtls-it");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static ConnectionOptions.Builder secureOptions(TlsOptions tls) {
    return ConnectionOptions.builder()
        .host(CLICKHOUSE.getHost())
        .port(CLICKHOUSE.secureNativePort())
        .database(CLICKHOUSE.database())
        .username(CLICKHOUSE.username())
        .password(CLICKHOUSE.password())
        .tls(tls);
  }

  @Test
  void clientCertificateSatisfiesStrictServerVerification() {
    TlsOptions mutualTls =
        TlsOptions.builder()
            .trustedCertificates(CA_PEM)
            .clientCertificate(CLIENT_CERT_PEM, CLIENT_KEY_PEM, null)
            .build();

    try (NativeConnection connection = NativeConnection.open(secureOptions(mutualTls).build())) {
      assertThat(connection.state()).isEqualTo(ConnectionState.READY);
      connection.ping();
    }
  }

  @Test
  void missingClientCertificateFailsTheHandshake() {
    TlsOptions trustOnly = TlsOptions.builder().trustedCertificates(CA_PEM).build();

    // Depending on the TLS version the rejection can surface during the handshake or on the
    // first exchange, but it must fail before any successful protocol conversation.
    assertThatThrownBy(
            () -> {
              try (NativeConnection connection =
                  NativeConnection.open(secureOptions(trustOnly).build())) {
                connection.ping();
              }
            })
        .isInstanceOf(ChordException.class);
  }
}
