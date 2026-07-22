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
import io.github.orhaugh.chord.annotations.Experimental;
import java.util.Objects;

/**
 * A ClickHouse server address: hostname and native protocol port.
 *
 * <p>The hostname is resolved at each connection attempt, never cached, so DNS changes such as
 * failover records and rolling replacements take effect without restarting.
 *
 * @param host server hostname or address
 * @param port native protocol port
 */
@Experimental
public record Endpoint(String host, int port) {

  /**
   * Validates the fields.
   *
   * @param host server hostname or address
   * @param port native protocol port
   */
  public Endpoint {
    Objects.requireNonNull(host, "host");
    if (host.isBlank()) {
      throw new ChordConfigurationException("host must not be blank");
    }
    if (port < 1 || port > 65535) {
      throw new ChordConfigurationException("port must be between 1 and 65535, was " + port);
    }
  }

  /**
   * Creates an endpoint.
   *
   * @param host server hostname or address
   * @param port native protocol port
   * @return the endpoint
   */
  public static Endpoint of(String host, int port) {
    return new Endpoint(host, port);
  }

  @Override
  public String toString() {
    return host + ":" + port;
  }
}
