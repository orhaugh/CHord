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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.orhaugh.chord.ChordTransportException;
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
 * The native handshake over TLS against a real ClickHouse server with certificates generated at
 * test run time. Hostname verification stays on throughout.
 */
@Testcontainers
class TlsHandshakeIT {

  private static final TestCertificates CERTS;
  private static final Path CA_PEM;

  @Container private static final ClickHouseServerContainer CLICKHOUSE;

  static {
    ClickHouseServerContainer container = new ClickHouseServerContainer();
    String dockerHost = container.getHost();
    List<String> dnsSans = new ArrayList<>(List.of("localhost"));
    List<String> ipSans = new ArrayList<>();
    if (isIpLiteral(dockerHost)) {
      ipSans.add(dockerHost);
    } else if (!dnsSans.contains(dockerHost)) {
      dnsSans.add(dockerHost);
    }
    CERTS = TestCertificates.generate(dnsSans, ipSans);
    CA_PEM = TestCertificates.write(tempDirectory(), "ca.crt", CERTS.caCertificatePem());
    CLICKHOUSE =
        container.withSecureNativePort(CERTS, ClickHouseServerContainer.ClientCertificateMode.NONE);
  }

  private static boolean isIpLiteral(String host) {
    return host.chars().allMatch(c -> c == '.' || (c >= '0' && c <= '9')) || host.contains(":");
  }

  private static Path tempDirectory() {
    try {
      return Files.createTempDirectory("chord-tls-it");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static ConnectionOptions.Builder secureOptions() {
    return ConnectionOptions.builder()
        .host(CLICKHOUSE.getHost())
        .port(CLICKHOUSE.secureNativePort())
        .database(CLICKHOUSE.database())
        .username(CLICKHOUSE.username())
        .password(CLICKHOUSE.password())
        .tls(TlsOptions.builder().trustedCertificates(CA_PEM).build());
  }

  @Test
  void tlsHandshakeCarriesPasswordWithoutPlaintextOptIn() {
    try (NativeConnection connection = NativeConnection.open(secureOptions().build())) {
      assertThat(connection.state()).isEqualTo(ConnectionState.READY);
      assertThat(connection.serverHello().serverName()).contains("ClickHouse");
      for (int i = 0; i < 3; i++) {
        connection.ping();
      }
    }
  }

  @Test
  void queryTimeoutsCancelCleanlyOverTls() {
    // The deadline machinery polls the transport for readability; this proves the poll path
    // over the TLS transport, where a read consumes decrypted bytes, not socket bytes.
    try (NativeConnection connection = NativeConnection.open(secureOptions().build())) {
      QueryRequest request =
          QueryRequest.builder("SELECT count() FROM numbers(1000000000000)")
              .timeout(java.time.Duration.ofMillis(500))
              .build();
      assertThatThrownBy(
              () -> {
                try (QueryResult result = connection.query(request)) {
                  result.nextBlock();
                }
              })
          .isInstanceOf(io.github.orhaugh.chord.ChordTimeoutException.class)
          .hasMessageContaining("remains usable");
      assertThat(connection.state()).isEqualTo(ConnectionState.READY);
      connection.ping();
    }
  }

  @Test
  void untrustedCertificateAuthorityIsRefused() {
    ConnectionOptions systemTrust = secureOptions().tls(TlsOptions.systemTrust()).build();

    assertThatThrownBy(() -> NativeConnection.open(systemTrust))
        .isInstanceOf(ChordTransportException.class)
        .hasMessageContaining("not trusted");
  }

  @Test
  void hostnameVerificationRejectsNamesOutsideTheCertificate() {
    // The server certificate has no IP subject alternative name when the Docker host is
    // localhost, so connecting by IP must fail verification.
    assumeTrue("localhost".equals(CLICKHOUSE.getHost()));
    ConnectionOptions byIp = secureOptions().host("127.0.0.1").build();

    assertThatThrownBy(() -> NativeConnection.open(byIp))
        .isInstanceOf(ChordTransportException.class)
        .hasMessageContaining("hostname verification");
  }
}
