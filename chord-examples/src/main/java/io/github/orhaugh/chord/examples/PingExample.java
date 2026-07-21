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
package io.github.orhaugh.chord.examples;

import io.github.orhaugh.chord.client.ConnectionOptions;
import io.github.orhaugh.chord.client.NativeConnection;

/**
 * Connects to a ClickHouse server over the native protocol, prints the negotiated handshake and
 * performs a few pings.
 *
 * <p>Configuration comes from environment variables: {@code CHORD_EXAMPLE_HOST} (default {@code
 * localhost}), {@code CHORD_EXAMPLE_PORT} (default {@code 9000}), {@code CHORD_EXAMPLE_USER}
 * (default {@code default}), {@code CHORD_EXAMPLE_PASSWORD} (default empty) and {@code
 * CHORD_EXAMPLE_DATABASE} (default empty, meaning the server default).
 */
public final class PingExample {

  private PingExample() {}

  /**
   * Runs the example.
   *
   * @param args unused
   */
  public static void main(String[] args) {
    ConnectionOptions.Builder builder =
        ConnectionOptions.builder()
            .host(env("CHORD_EXAMPLE_HOST", "localhost"))
            .port(Integer.parseInt(env("CHORD_EXAMPLE_PORT", "9000")))
            .username(env("CHORD_EXAMPLE_USER", "default"))
            .database(env("CHORD_EXAMPLE_DATABASE", ""));

    String password = env("CHORD_EXAMPLE_PASSWORD", "");
    if (!password.isEmpty()) {
      // TLS is the production path once it lands; this example explicitly opts in to
      // plaintext password authentication for local development servers.
      builder.password(password).allowPlaintextPassword(true);
    }

    try (NativeConnection connection = NativeConnection.open(builder.build())) {
      System.out.printf(
          "Connected to %s %s at %s%n",
          connection.serverHello().serverName(),
          connection.serverHello().versionString(),
          connection.remoteAddress());
      System.out.printf(
          "Server revision %d, negotiated revision %d, server timezone %s%n",
          connection.serverHello().serverRevision(),
          connection.negotiatedRevision(),
          connection.serverHello().timezone().orElse("unknown"));

      for (int i = 1; i <= 3; i++) {
        long start = System.nanoTime();
        connection.ping();
        long elapsedMicros = (System.nanoTime() - start) / 1_000;
        System.out.printf("Ping %d ok in %d us%n", i, elapsedMicros);
      }
    }
  }

  private static String env(String name, String fallback) {
    String value = System.getenv(name);
    return value == null || value.isBlank() ? fallback : value;
  }
}
