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

import io.github.orhaugh.chord.client.Endpoint;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * The CHord JDBC URL: {@code jdbc:chord://host[:port][,host2[:port2]...][/database][?key=value]}.
 *
 * <p>The default port is 9000, or 9440 once {@code ssl=true}. Recognised query parameters, all
 * overridable by {@link java.util.Properties} passed to the driver (properties win):
 *
 * <ul>
 *   <li>{@code user}, {@code password}
 *   <li>{@code ssl}: {@code true} enables TLS with system trust
 *   <li>{@code compression}: {@code lz4}, {@code lz4hc}, {@code zstd} or {@code none}
 *   <li>{@code connect_timeout_ms}, {@code read_timeout_ms}, {@code query_timeout_ms}
 *   <li>{@code allow_plaintext_password}: required to send a password without TLS
 *   <li>{@code client_name}, {@code quota_key}
 * </ul>
 *
 * <p>Unknown parameters fail rather than being ignored, so a typo cannot silently weaken a security
 * setting.
 */
final class JdbcUrl {

  static final String PREFIX = "jdbc:chord://";

  private static final List<String> KNOWN_KEYS =
      List.of(
          "user",
          "password",
          "ssl",
          "ssl_ca",
          "ssl_truststore",
          "ssl_truststore_password",
          "compression",
          "connect_timeout_ms",
          "read_timeout_ms",
          "query_timeout_ms",
          "allow_plaintext_password",
          "client_name",
          "quota_key");

  private final List<Endpoint> endpoints;
  private final String database;
  private final Map<String, String> parameters;

  private JdbcUrl(List<Endpoint> endpoints, String database, Map<String, String> parameters) {
    this.endpoints = List.copyOf(endpoints);
    this.database = database;
    this.parameters = Map.copyOf(parameters);
  }

  static boolean accepts(String url) {
    return url != null && url.startsWith(PREFIX);
  }

  /**
   * Parses a URL and merges driver properties over its query parameters.
   *
   * @param url the JDBC URL
   * @param info driver properties; entries here override URL parameters
   * @return the parsed configuration
   * @throws SQLException when the URL is malformed or carries unknown parameters
   */
  static JdbcUrl parse(String url, Properties info) throws SQLException {
    if (!accepts(url)) {
      throw new SQLException("Not a CHord JDBC URL: expected prefix " + PREFIX, "08001");
    }
    String rest = url.substring(PREFIX.length());
    String query = "";
    int questionMark = rest.indexOf('?');
    if (questionMark >= 0) {
      query = rest.substring(questionMark + 1);
      rest = rest.substring(0, questionMark);
    }
    String database = "";
    int slash = rest.indexOf('/');
    if (slash >= 0) {
      database = rest.substring(slash + 1);
      rest = rest.substring(0, slash);
      if (database.contains("/")) {
        throw new SQLException("Malformed database segment in JDBC URL", "08001");
      }
    }
    if (rest.isBlank()) {
      throw new SQLException("JDBC URL carries no host", "08001");
    }

    Map<String, String> parameters = new LinkedHashMap<>();
    if (!query.isEmpty()) {
      for (String pair : query.split("&")) {
        if (pair.isEmpty()) {
          continue;
        }
        int equals = pair.indexOf('=');
        if (equals <= 0) {
          throw new SQLException("Malformed JDBC URL parameter: " + pair, "08001");
        }
        String key = decode(pair.substring(0, equals));
        String value = decode(pair.substring(equals + 1));
        if (!KNOWN_KEYS.contains(key)) {
          throw new SQLException(
              "Unknown JDBC URL parameter \"" + key + "\"; known parameters are " + KNOWN_KEYS,
              "08001");
        }
        parameters.put(key, value);
      }
    }
    if (info != null) {
      for (String name : info.stringPropertyNames()) {
        if (!KNOWN_KEYS.contains(name)) {
          throw new SQLException(
              "Unknown driver property \"" + name + "\"; known properties are " + KNOWN_KEYS,
              "08001");
        }
        parameters.put(name, info.getProperty(name));
      }
    }

    boolean ssl = Boolean.parseBoolean(parameters.getOrDefault("ssl", "false"));
    int defaultPort = ssl ? 9440 : 9000;
    List<Endpoint> endpoints = new ArrayList<>();
    for (String hostPort : rest.split(",")) {
      if (hostPort.isBlank()) {
        throw new SQLException("Empty host entry in JDBC URL", "08001");
      }
      String host = hostPort;
      int port = defaultPort;
      int colon = hostPort.lastIndexOf(':');
      if (colon >= 0) {
        host = hostPort.substring(0, colon);
        try {
          port = Integer.parseInt(hostPort.substring(colon + 1));
        } catch (NumberFormatException e) {
          throw new SQLException("Malformed port in JDBC URL host entry: " + hostPort, "08001");
        }
      }
      try {
        endpoints.add(Endpoint.of(host, port));
      } catch (RuntimeException e) {
        throw new SQLException("Invalid host entry in JDBC URL: " + e.getMessage(), "08001");
      }
    }
    return new JdbcUrl(endpoints, database, parameters);
  }

  private static String decode(String value) {
    return URLDecoder.decode(value, StandardCharsets.UTF_8);
  }

  List<Endpoint> endpoints() {
    return endpoints;
  }

  String database() {
    return database;
  }

  Map<String, String> parameters() {
    return parameters;
  }

  String parameter(String key, String fallback) {
    return parameters.getOrDefault(key, fallback);
  }
}
