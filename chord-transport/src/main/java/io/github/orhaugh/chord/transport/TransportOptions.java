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
package io.github.orhaugh.chord.transport;

import io.github.orhaugh.chord.ChordConfigurationException;
import java.time.Duration;
import java.util.Objects;

/**
 * Socket level configuration for the TCP transport.
 *
 * @param connectTimeout time allowed to establish the TCP connection
 * @param readTimeout time a blocking read may wait for data before failing; zero means no limit,
 *     which is only appropriate for interactive tools
 * @param tcpNoDelay whether to disable Nagle's algorithm; the native protocol is request response,
 *     so this defaults to {@code true}
 * @param keepAlive whether to enable TCP keepalive probes
 * @param receiveBufferSize socket receive buffer in bytes, zero for the OS default
 * @param sendBufferSize socket send buffer in bytes, zero for the OS default
 */
public record TransportOptions(
    Duration connectTimeout,
    Duration readTimeout,
    boolean tcpNoDelay,
    boolean keepAlive,
    int receiveBufferSize,
    int sendBufferSize) {

  /** Default options: 10 second connect timeout, 300 second read timeout, TCP_NODELAY on. */
  public static final TransportOptions DEFAULTS =
      new TransportOptions(Duration.ofSeconds(10), Duration.ofSeconds(300), true, true, 0, 0);

  /**
   * Validates the options.
   *
   * @param connectTimeout must be positive
   * @param readTimeout must be zero or positive
   * @param tcpNoDelay whether to disable Nagle's algorithm
   * @param keepAlive whether to enable TCP keepalive probes
   * @param receiveBufferSize must be zero or positive
   * @param sendBufferSize must be zero or positive
   */
  public TransportOptions {
    Objects.requireNonNull(connectTimeout, "connectTimeout");
    Objects.requireNonNull(readTimeout, "readTimeout");
    if (connectTimeout.isNegative() || connectTimeout.isZero()) {
      throw new ChordConfigurationException("connectTimeout must be positive");
    }
    if (readTimeout.isNegative()) {
      throw new ChordConfigurationException("readTimeout must not be negative");
    }
    if (receiveBufferSize < 0 || sendBufferSize < 0) {
      throw new ChordConfigurationException("buffer sizes must not be negative");
    }
  }

  /**
   * Returns a copy with a different connect timeout.
   *
   * @param timeout the new connect timeout
   * @return the modified options
   */
  public TransportOptions withConnectTimeout(Duration timeout) {
    return new TransportOptions(
        timeout, readTimeout, tcpNoDelay, keepAlive, receiveBufferSize, sendBufferSize);
  }

  /**
   * Returns a copy with a different read timeout.
   *
   * @param timeout the new read timeout, zero for no limit
   * @return the modified options
   */
  public TransportOptions withReadTimeout(Duration timeout) {
    return new TransportOptions(
        connectTimeout, timeout, tcpNoDelay, keepAlive, receiveBufferSize, sendBufferSize);
  }
}
