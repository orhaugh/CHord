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

import io.github.orhaugh.chord.ChordException;
import io.github.orhaugh.chord.client.ConnectionOptions;
import io.github.orhaugh.chord.client.Endpoint;
import io.github.orhaugh.chord.client.FailoverConnector;
import io.github.orhaugh.chord.client.NativeConnection;
import io.github.orhaugh.chord.codec.compress.Compression;
import io.github.orhaugh.chord.transport.TlsOptions;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.time.Duration;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * The CHord JDBC driver, speaking the ClickHouse native TCP protocol through the CHord client.
 *
 * <p>URLs use the {@code jdbc:chord://} prefix; see {@link JdbcUrl} for the accepted form and
 * parameters. The driver registers itself through the service loader, so {@code
 * DriverManager.getConnection} finds it without an explicit {@code Class.forName}.
 */
public final class ChordDriver implements Driver {

  private static final int MAJOR_VERSION = 0;
  private static final int MINOR_VERSION = 1;

  static {
    try {
      DriverManager.registerDriver(new ChordDriver());
    } catch (SQLException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @Override
  public boolean acceptsURL(String url) {
    return JdbcUrl.accepts(url);
  }

  @Override
  public Connection connect(String url, Properties info) throws SQLException {
    if (!acceptsURL(url)) {
      return null; // Per the Driver contract: not ours, let another driver try.
    }
    JdbcUrl parsed = JdbcUrl.parse(url, info);
    ConnectionOptions options = optionsFor(parsed);
    Duration statementTimeout = statementTimeout(parsed);
    try {
      NativeConnection connection = openNative(parsed, options);
      return new ChordConnection(connection, parsed.database(), statementTimeout);
    } catch (ChordException e) {
      throw SqlExceptions.map(e);
    }
  }

  private static NativeConnection openNative(JdbcUrl parsed, ConnectionOptions options) {
    if (parsed.endpoints().size() == 1) {
      return NativeConnection.open(options);
    }
    FailoverConnector.Builder connector = FailoverConnector.builder(options);
    for (Endpoint endpoint : parsed.endpoints()) {
      connector.endpoint(endpoint);
    }
    return connector.build().connect();
  }

  private static ConnectionOptions optionsFor(JdbcUrl parsed) throws SQLException {
    Endpoint first = parsed.endpoints().get(0);
    ConnectionOptions.Builder builder =
        ConnectionOptions.builder()
            .host(first.host())
            .port(first.port())
            .database(parsed.database())
            .username(parsed.parameter("user", "default"))
            .clientName(parsed.parameter("client_name", "CHord JDBC"))
            .quotaKey(parsed.parameter("quota_key", ""));
    String password = parsed.parameter("password", "");
    if (!password.isEmpty()) {
      builder.password(password);
    }
    if (Boolean.parseBoolean(parsed.parameter("ssl", "false"))) {
      builder.tls(TlsOptions.builder().build());
    }
    if (Boolean.parseBoolean(parsed.parameter("allow_plaintext_password", "false"))) {
      builder.allowPlaintextPassword(true);
    }
    String compression = parsed.parameter("compression", "");
    if (!compression.isEmpty() && !compression.equals("none")) {
      builder.compression(
          switch (compression) {
            case "lz4" -> Compression.LZ4;
            case "lz4hc" -> Compression.LZ4HC;
            case "zstd" -> Compression.ZSTD;
            default ->
                throw new SQLException(
                    "Unknown compression \"" + compression + "\"; use lz4, lz4hc, zstd or none",
                    "08001");
          });
    }
    builder.connectTimeout(millis(parsed, "connect_timeout_ms", Duration.ofSeconds(10)));
    builder.readTimeout(millis(parsed, "read_timeout_ms", Duration.ZERO));
    return builder.build();
  }

  private static Duration statementTimeout(JdbcUrl parsed) throws SQLException {
    Duration timeout = millis(parsed, "query_timeout_ms", Duration.ZERO);
    return timeout.isZero() ? null : timeout;
  }

  private static Duration millis(JdbcUrl parsed, String key, Duration fallback)
      throws SQLException {
    String value = parsed.parameter(key, "");
    if (value.isEmpty()) {
      return fallback;
    }
    try {
      return Duration.ofMillis(Long.parseLong(value));
    } catch (NumberFormatException e) {
      throw new SQLException("Malformed " + key + ": " + value, "08001");
    }
  }

  @Override
  public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
    String[] keys = {
      "user",
      "password",
      "ssl",
      "compression",
      "connect_timeout_ms",
      "read_timeout_ms",
      "query_timeout_ms",
      "allow_plaintext_password",
      "client_name",
      "quota_key"
    };
    DriverPropertyInfo[] properties = new DriverPropertyInfo[keys.length];
    for (int i = 0; i < keys.length; i++) {
      properties[i] =
          new DriverPropertyInfo(keys[i], info != null ? info.getProperty(keys[i]) : null);
    }
    return properties;
  }

  @Override
  public int getMajorVersion() {
    return MAJOR_VERSION;
  }

  @Override
  public int getMinorVersion() {
    return MINOR_VERSION;
  }

  @Override
  public boolean jdbcCompliant() {
    // Honest: ClickHouse is not SQL-92 entry level and this driver does not pretend to be.
    return false;
  }

  @Override
  public Logger getParentLogger() throws SQLFeatureNotSupportedException {
    throw new SQLFeatureNotSupportedException(
        "java.util.logging is not used; CHord logs through SLF4J");
  }
}
