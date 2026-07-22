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
package io.github.orhaugh.chord.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.orhaugh.chord.client.ConnectionOptions;
import io.github.orhaugh.chord.client.ConnectionPool;
import io.github.orhaugh.chord.client.PooledConnection;
import io.github.orhaugh.chord.protocol.wire.WireWriter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** The gauge binding contract, including gauges tracking a pool under real lease traffic. */
@Timeout(30)
class ChordPoolMetricsTest {

  @Test
  void bindRegistersTheGaugesWithThePoolTag() {
    // The pool is exercised against real servers in chord-client tests; here the subject is
    // the meter binding itself, so an unstarted pool with a failing factory is sufficient.
    try (ConnectionPool pool =
        ConnectionPool.builder(
                () -> {
                  throw new io.github.orhaugh.chord.ChordTransportException("never dialled");
                })
            .maxSize(3)
            .build()) {
      SimpleMeterRegistry registry = new SimpleMeterRegistry();
      ChordPoolMetrics.bind(pool, registry, "analytics");

      assertThat(
              registry
                  .get("chord.pool.connections.active")
                  .tag("pool", "analytics")
                  .gauge()
                  .value())
          .isEqualTo(0.0);
      assertThat(
              registry.get("chord.pool.connections.idle").tag("pool", "analytics").gauge().value())
          .isEqualTo(0.0);
    }
  }

  @Test
  void gaugesFollowRealLeaseTraffic() throws Exception {
    try (MiniClickHouse server = new MiniClickHouse()) {
      SimpleMeterRegistry registry = new SimpleMeterRegistry();
      try (ConnectionPool pool = ConnectionPool.builder(server.options()).maxSize(3).build()) {
        ChordPoolMetrics.bind(pool, registry, "load");

        PooledConnection first = pool.acquire();
        PooledConnection second = pool.acquire();
        assertThat(activeGauge(registry, "load")).isEqualTo(2.0);
        assertThat(idleGauge(registry, "load")).isEqualTo(0.0);

        first.close();
        assertThat(activeGauge(registry, "load")).isEqualTo(1.0);
        assertThat(idleGauge(registry, "load")).isEqualTo(1.0);

        second.close();
        assertThat(activeGauge(registry, "load")).isEqualTo(0.0);
        assertThat(idleGauge(registry, "load")).isEqualTo(2.0);
      }
      // A closed pool's meters stay readable and report its drained state.
      assertThat(activeGauge(registry, "load")).isEqualTo(0.0);
      assertThat(idleGauge(registry, "load")).isEqualTo(0.0);
    }
  }

  @Test
  void multiplePoolsKeepDistinctTagsInOneRegistry() throws Exception {
    try (MiniClickHouse server = new MiniClickHouse()) {
      SimpleMeterRegistry registry = new SimpleMeterRegistry();
      try (ConnectionPool busy = ConnectionPool.builder(server.options()).maxSize(2).build();
          ConnectionPool quiet = ConnectionPool.builder(server.options()).maxSize(2).build()) {
        ChordPoolMetrics.bind(busy, registry, "busy");
        ChordPoolMetrics.bind(quiet, registry, "quiet");

        try (PooledConnection lease = busy.acquire()) {
          assertThat(lease).isNotNull();
          assertThat(activeGauge(registry, "busy")).isEqualTo(1.0);
          assertThat(activeGauge(registry, "quiet")).isEqualTo(0.0);
        }
      }
    }
  }

  @Test
  void bindRejectsNullArguments() {
    try (ConnectionPool pool =
        ConnectionPool.builder(
                () -> {
                  throw new io.github.orhaugh.chord.ChordTransportException("never dialled");
                })
            .build()) {
      MeterRegistry registry = new SimpleMeterRegistry();
      assertThatThrownBy(() -> ChordPoolMetrics.bind(null, registry, "p"))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("pool");
      assertThatThrownBy(() -> ChordPoolMetrics.bind(pool, null, "p"))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("registry");
      assertThatThrownBy(() -> ChordPoolMetrics.bind(pool, registry, null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("poolName");
    }
  }

  private static double activeGauge(MeterRegistry registry, String pool) {
    return registry.get("chord.pool.connections.active").tag("pool", pool).gauge().value();
  }

  private static double idleGauge(MeterRegistry registry, String pool) {
    return registry.get("chord.pool.connections.idle").tag("pool", pool).gauge().value();
  }

  /**
   * A minimal loopback ClickHouse impostor: answers the handshake, then serves pings. The same
   * shape as chord-client's LoopbackPingServer, inlined because test classes do not travel across
   * modules.
   */
  private static final class MiniClickHouse implements AutoCloseable {

    private final ServerSocket server;

    MiniClickHouse() throws Exception {
      this.server = new ServerSocket(0, 16, InetAddress.getLoopbackAddress());
      Thread.ofVirtual()
          .name("mini-ch-accept")
          .start(
              () -> {
                while (!server.isClosed()) {
                  try {
                    Socket socket = server.accept();
                    Thread.ofVirtual().start(() -> serve(socket));
                  } catch (Exception e) {
                    return; // closed
                  }
                }
              });
    }

    private static void serve(Socket socket) {
      try (socket) {
        OutputStream out = socket.getOutputStream();
        InputStream in = socket.getInputStream();
        out.write(helloBytes());
        out.flush();
        // Scan the inbound stream and answer every Ping (0x04); pool traffic sends
        // nothing else after the handshake.
        int b;
        while ((b = in.read()) >= 0) {
          if (b == 4) {
            out.write(4); // Pong
            out.flush();
          }
        }
      } catch (Exception e) {
        // Torn down by the client or the test; nothing to do.
      }
    }

    private static byte[] helloBytes() {
      ByteArrayOutputStream sink = new ByteArrayOutputStream();
      WireWriter w = new WireWriter(sink);
      w.writeVarUInt(0); // Server::Hello
      w.writeString("ClickHouse");
      w.writeVarUInt(26);
      w.writeVarUInt(7);
      w.writeVarUInt(54488);
      w.writeVarUInt(8); // parallel replicas protocol version
      w.writeString("UTC");
      w.writeString("mini");
      w.writeVarUInt(1); // patch
      w.writeString("notchunked_optional");
      w.writeString("notchunked_optional");
      w.writeVarUInt(0); // no password rules
      w.writeInt64Le(7); // nonce
      w.writeString(""); // empty settings block
      w.writeVarUInt(3); // query plan serialisation version
      w.writeVarUInt(8); // cluster function protocol version
      w.flush();
      return sink.toByteArray();
    }

    ConnectionOptions options() {
      return ConnectionOptions.builder().host("127.0.0.1").port(server.getLocalPort()).build();
    }

    @Override
    public void close() throws java.io.IOException {
      server.close();
    }
  }
}
