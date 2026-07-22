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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.orhaugh.chord.ChordConfigurationException;
import io.github.orhaugh.chord.ChordTransportException;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Behaviour of the TCP transport against local sockets. */
class TcpTransportTest {

  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

  @AfterEach
  void shutDown() {
    executor.shutdownNow();
  }

  @Test
  void connectsAndExchangesBytes() throws Exception {
    try (ServerSocket server = new ServerSocket(0)) {
      executor.submit(
          () -> {
            try (Socket accepted = server.accept()) {
              int received = accepted.getInputStream().read();
              accepted.getOutputStream().write(received + 1);
              accepted.getOutputStream().flush();
            }
            return null;
          });

      TcpTransport transport =
          TcpTransport.connect("127.0.0.1", server.getLocalPort(), TransportOptions.DEFAULTS);
      try {
        assertThat(transport.isOpen()).isTrue();
        assertThat(transport.isSecure()).isFalse();
        transport.outputStream().write(41);
        transport.outputStream().flush();
        assertThat(transport.inputStream().read()).isEqualTo(42);
      } finally {
        transport.close();
      }
      assertThat(transport.isOpen()).isFalse();
    }
  }

  @Test
  void awaitReadableDeliversThePolledByteAsAShortReadWithoutBlocking() throws Exception {
    try (ServerSocket server = new ServerSocket(0)) {
      executor.submit(
          () -> {
            try (Socket socket = server.accept()) {
              socket.getOutputStream().write(5);
              socket.getOutputStream().flush();
              Thread.sleep(3000);
            }
            return null;
          });
      try (TcpTransport transport =
          TcpTransport.connect("127.0.0.1", server.getLocalPort(), TransportOptions.DEFAULTS)) {
        assertThat(transport.awaitReadable(2000)).isTrue();
        // The polled byte was a complete message; a bulk read must deliver it alone as a
        // short read instead of blocking on the socket for bytes that never come.
        byte[] target = new byte[16];
        long start = System.nanoTime();
        int n = transport.inputStream().read(target, 0, target.length);
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
        assertThat(n).isEqualTo(1);
        assertThat(target[0]).isEqualTo((byte) 5);
        assertThat(elapsedMillis).isLessThan(1000);
        assertThat(transport.awaitReadable(50)).isFalse();
      }
    }
  }

  @Test
  void refusedConnectionRaisesTransportException() throws IOException {
    // A just released ephemeral port can be rebound by another process before the connect
    // attempt, so retry with fresh ports until one refuses; a connect that succeeds by that
    // race is discarded rather than failed.
    for (int attempt = 0; attempt < 5; attempt++) {
      int freePort;
      try (ServerSocket server = new ServerSocket(0)) {
        freePort = server.getLocalPort();
      }
      try {
        TcpTransport.connect("127.0.0.1", freePort, TransportOptions.DEFAULTS).close();
      } catch (ChordTransportException e) {
        assertThat(e).hasMessageContaining("Failed to connect");
        return;
      }
    }
    throw new AssertionError("Every probed ephemeral port accepted a connection");
  }

  @Test
  void readTimeoutSurfacesAsSocketTimeout() throws Exception {
    try (ServerSocket server = new ServerSocket(0)) {
      executor.submit(
          () -> {
            // Accept and stay silent so the client read must time out.
            Socket accepted = server.accept();
            try {
              Thread.sleep(5_000);
            } finally {
              accepted.close();
            }
            return null;
          });

      TransportOptions options = TransportOptions.DEFAULTS.withReadTimeout(Duration.ofMillis(100));
      TcpTransport transport = TcpTransport.connect("127.0.0.1", server.getLocalPort(), options);
      try {
        assertThatThrownBy(() -> transport.inputStream().read())
            .isInstanceOf(SocketTimeoutException.class);
      } finally {
        transport.close();
      }
    }
  }

  @Test
  void closeIsIdempotent() throws Exception {
    try (ServerSocket server = new ServerSocket(0)) {
      TcpTransport transport =
          TcpTransport.connect("127.0.0.1", server.getLocalPort(), TransportOptions.DEFAULTS);
      transport.close();
      transport.close();
      assertThat(transport.isOpen()).isFalse();
    }
  }

  @Test
  void optionsRejectInvalidValues() {
    assertThatThrownBy(
            () -> new TransportOptions(Duration.ZERO, Duration.ofSeconds(1), true, true, 0, 0))
        .isInstanceOf(ChordConfigurationException.class);
    assertThatThrownBy(
            () ->
                new TransportOptions(
                    Duration.ofSeconds(1), Duration.ofSeconds(-1), true, true, 0, 0))
        .isInstanceOf(ChordConfigurationException.class);
    assertThatThrownBy(
            () ->
                new TransportOptions(
                    Duration.ofSeconds(1), Duration.ofSeconds(1), true, true, -1, 0))
        .isInstanceOf(ChordConfigurationException.class);
  }
}
