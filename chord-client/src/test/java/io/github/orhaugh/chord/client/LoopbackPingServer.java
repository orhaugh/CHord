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

import io.github.orhaugh.chord.protocol.wire.WireWriter;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A minimal loopback ClickHouse impostor for pool tests: answers the handshake and then serves
 * pings, counting connections. Real protocol behaviour is covered by integration tests; this exists
 * so pool mechanics are testable without Docker.
 */
final class LoopbackPingServer implements AutoCloseable {

  private static final long SERVER_REVISION = 54488;

  private final ServerSocket server;
  private final AtomicInteger accepted = new AtomicInteger();
  private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
  private volatile boolean refuseNewConnections;

  LoopbackPingServer() throws Exception {
    this.server = new ServerSocket(0, 64, InetAddress.getLoopbackAddress());
    Thread.ofVirtual()
        .name("loopback-ch-accept")
        .start(
            () -> {
              while (!server.isClosed()) {
                try {
                  Socket socket = server.accept();
                  sockets.add(socket);
                  accepted.incrementAndGet();
                  Thread.ofVirtual().start(() -> serve(socket));
                } catch (Exception e) {
                  return; // closed
                }
              }
            });
  }

  private void serve(Socket socket) {
    try (socket) {
      if (refuseNewConnections) {
        return;
      }
      OutputStream out = socket.getOutputStream();
      InputStream in = socket.getInputStream();
      out.write(helloBytes());
      out.flush();
      // Client hello and addendum arrive; scan the inbound stream and answer every Ping
      // (0x04). The handshake bytes contain no 0x04 at packet position, but scanning is
      // fine here: pings are the only packets pool tests send.
      int b;
      while ((b = in.read()) >= 0) {
        if (b == 4) {
          out.write(4); // Pong
          out.flush();
        }
      }
    } catch (Exception e) {
      // Connection torn down by the client or the test; nothing to do.
    } finally {
      sockets.remove(socket);
    }
  }

  private static byte[] helloBytes() {
    ByteArrayOutputStream sink = new ByteArrayOutputStream();
    WireWriter w = new WireWriter(sink);
    w.writeVarUInt(0); // Server::Hello
    w.writeString("ClickHouse");
    w.writeVarUInt(26);
    w.writeVarUInt(7);
    w.writeVarUInt(SERVER_REVISION);
    w.writeVarUInt(8); // parallel replicas protocol version
    w.writeString("UTC");
    w.writeString("loopback");
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

  int port() {
    return server.getLocalPort();
  }

  int acceptedConnections() {
    return accepted.get();
  }

  /** Abruptly closes every established connection, simulating a server failure. */
  void dropAllConnections() {
    for (Socket socket : sockets) {
      try {
        socket.close();
      } catch (Exception e) {
        // Already gone.
      }
    }
  }

  void refuseNewConnections(boolean refuse) {
    this.refuseNewConnections = refuse;
  }

  ConnectionOptions options() {
    return ConnectionOptions.builder().host("127.0.0.1").port(port()).build();
  }

  @Override
  public void close() {
    try {
      server.close();
    } catch (java.io.IOException e) {
      // The accept loop exits either way.
    }
    dropAllConnections();
  }
}
