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
package io.github.orhaugh.chord.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.orhaugh.chord.testkit.ClickHouseServerContainer;
import io.github.orhaugh.chord.testkit.TestCertificates;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The JDBC TLS surface against a real secure server: a private authority carried as {@code ssl_ca}
 * PEM or as an {@code ssl_truststore}, and honest refusals for misconfiguration.
 */
@Testcontainers
@Timeout(180)
class JdbcTlsIT {

  private static final TestCertificates CERTS;
  private static final Path CA_PEM;
  private static final Path TRUST_STORE;
  private static final char[] TRUST_PASSWORD = "trust-pw".toCharArray();

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
    Path directory = tempDirectory();
    CA_PEM = TestCertificates.write(directory, "ca.crt", CERTS.caCertificatePem());
    TRUST_STORE = CERTS.writeTrustStore(directory.resolve("trust"), "PKCS12", TRUST_PASSWORD);
    CLICKHOUSE =
        container.withSecureNativePort(CERTS, ClickHouseServerContainer.ClientCertificateMode.NONE);
  }

  private static boolean isIpLiteral(String host) {
    return host.chars().allMatch(c -> c == '.' || (c >= '0' && c <= '9')) || host.contains(":");
  }

  private static Path tempDirectory() {
    try {
      return Files.createTempDirectory("chord-jdbc-tls-it");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static String secureUrl(String parameters) {
    return "jdbc:chord://"
        + CLICKHOUSE.getHost()
        + ":"
        + CLICKHOUSE.secureNativePort()
        + "/"
        + CLICKHOUSE.database()
        + "?ssl=true"
        + parameters;
  }

  private static Properties credentials() {
    Properties info = new Properties();
    info.setProperty("user", CLICKHOUSE.username());
    info.setProperty("password", CLICKHOUSE.password());
    return info;
  }

  private static void assertQueryWorks(String url) throws SQLException {
    try (Connection connection = DriverManager.getConnection(url, credentials());
        Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery("SELECT 40 + 2")) {
      assertThat(rows.next()).isTrue();
      assertThat(rows.getInt(1)).isEqualTo(42);
    }
  }

  @Test
  void privateAuthorityPemTravelsAsSslCa() throws SQLException {
    assertQueryWorks(secureUrl("&ssl_ca=" + CA_PEM));
  }

  @Test
  void privateAuthorityTrustStoreTravelsAsSslTruststore() throws SQLException {
    assertQueryWorks(
        secureUrl(
            "&ssl_truststore="
                + TRUST_STORE
                + "&ssl_truststore_password="
                + new String(TRUST_PASSWORD)));
  }

  @Test
  void misconfigurationsRefuseWithConnectionStates() {
    // System trust cannot verify the private authority.
    assertThatThrownBy(() -> assertQueryWorks(secureUrl("")))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("not trusted");
    // Both trust sources at once is ambiguous.
    assertThatThrownBy(
            () ->
                assertQueryWorks(secureUrl("&ssl_ca=" + CA_PEM + "&ssl_truststore=" + TRUST_STORE)))
        .isInstanceOf(SQLException.class)
        .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("08001"));
    // Trust parameters without ssl=true are a configuration mistake, not a silent no-op.
    assertThatThrownBy(
            () ->
                DriverManager.getConnection(
                    "jdbc:chord://"
                        + CLICKHOUSE.getHost()
                        + ":"
                        + CLICKHOUSE.secureNativePort()
                        + "/"
                        + CLICKHOUSE.database()
                        + "?ssl_ca="
                        + CA_PEM,
                    credentials()))
        .isInstanceOf(SQLException.class)
        .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("08001"));
    // A missing trust store file fails with the connection state, not a raw IO error.
    assertThatThrownBy(() -> assertQueryWorks(secureUrl("&ssl_truststore=/nonexistent/store.p12")))
        .isInstanceOf(SQLException.class);
  }
}
