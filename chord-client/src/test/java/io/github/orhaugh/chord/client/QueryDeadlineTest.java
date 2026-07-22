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

import io.github.orhaugh.chord.ChordTimeoutException;
import io.github.orhaugh.chord.codec.block.BlockLimits;
import io.github.orhaugh.chord.codec.block.BlockReader;
import io.github.orhaugh.chord.codec.block.BlockWriter;
import io.github.orhaugh.chord.codec.block.DecodeContext;
import io.github.orhaugh.chord.codec.column.BlockBuilder;
import io.github.orhaugh.chord.protocol.state.ConnectionState;
import io.github.orhaugh.chord.protocol.wire.WireLimits;
import io.github.orhaugh.chord.protocol.wire.WireReader;
import io.github.orhaugh.chord.protocol.wire.WireWriter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Query timeouts against a controllable loopback server, exercising real socket waits: the graceful
 * cancel within the grace period and the abandonment when the server never concludes.
 */
@Timeout(30)
class QueryDeadlineTest {

  private static final long SERVER_REVISION = 54488;

  private static byte[] script(Consumer<WireWriter> actions) {
    ByteArrayOutputStream sink = new ByteArrayOutputStream();
    WireWriter writer = new WireWriter(sink);
    actions.accept(writer);
    writer.flush();
    return sink.toByteArray();
  }

  private static byte[] helloBytes() {
    return script(
        w -> {
          w.writeVarUInt(0); // Server::Hello
          w.writeString("ClickHouse");
          w.writeVarUInt(26);
          w.writeVarUInt(7);
          w.writeVarUInt(SERVER_REVISION);
          w.writeVarUInt(8); // parallel replicas protocol version
          w.writeString("UTC");
          w.writeString("deadline-test");
          w.writeVarUInt(1); // patch
          w.writeString("notchunked_optional");
          w.writeString("notchunked_optional");
          w.writeVarUInt(0); // no password rules
          w.writeInt64Le(7); // nonce
          w.writeString(""); // empty settings block
          w.writeVarUInt(3); // query plan serialisation version
          w.writeVarUInt(8); // cluster function protocol version
        });
  }

  private static byte[] headerDataPacket() {
    return script(
        w -> {
          w.writeVarUInt(1); // Server::Data
          w.writeString("");
          byte[] schemaBytes =
              script(
                  s -> {
                    s.writeVarUInt(1);
                    s.writeBool(false);
                    s.writeVarUInt(2);
                    s.writeInt32Le(-1);
                    s.writeVarUInt(3);
                    s.writeVarUInt(0);
                    s.writeVarUInt(0);
                    s.writeVarUInt(1);
                    s.writeVarUInt(0);
                    s.writeString("n");
                    s.writeString("UInt8");
                    s.writeUInt8(0);
                  });
          BlockWriter.write(
              w,
              BlockBuilder.forSchema(
                      BlockReader.read(
                          new WireReader(
                              new ByteArrayInputStream(schemaBytes), WireLimits.DEFAULTS),
                          new DecodeContext(
                              BlockLimits.DEFAULTS, SERVER_REVISION, java.time.ZoneId.of("UTC"))))
                  .build(),
              SERVER_REVISION);
        });
  }

  private static byte[] endOfStreamPacket() {
    return script(w -> w.writeVarUInt(5));
  }

  /** Reads and discards everything that arrives within the settle window. */
  private static void drainInbound(InputStream in, int settleMillis) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(settleMillis);
    byte[] scratch = new byte[4096];
    while (System.nanoTime() < deadline) {
      if (in.available() > 0) {
        int n = in.read(scratch, 0, Math.min(scratch.length, in.available()));
        if (n < 0) {
          return;
        }
      } else {
        Thread.sleep(10);
      }
    }
  }

  @Test
  void timeoutCancelsAndDrainsWithinGraceLeavingTheConnectionReusable() throws Exception {
    CountDownLatch sawCancel = new CountDownLatch(1);
    try (ServerSocket server = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
      Thread serverThread =
          Thread.ofVirtual()
              .start(
                  () -> {
                    try (Socket socket = server.accept()) {
                      OutputStream out = socket.getOutputStream();
                      InputStream in = socket.getInputStream();
                      out.write(helloBytes());
                      out.flush();
                      // Let the client hello, addendum, query and terminator all arrive, then
                      // discard them; the next byte on the wire must be the Cancel packet.
                      drainInbound(in, 250);
                      out.write(headerDataPacket());
                      out.flush();
                      int next = in.read(); // blocks until the deadline path sends Cancel
                      if (next == 3) {
                        sawCancel.countDown();
                      }
                      out.write(endOfStreamPacket());
                      out.flush();
                      drainInbound(in, 100);
                    } catch (Exception e) {
                      // The assertion on sawCancel reports the failure.
                    }
                  });

      ConnectionOptions options =
          ConnectionOptions.builder()
              .host("127.0.0.1")
              .port(server.getLocalPort())
              .cancelGrace(Duration.ofSeconds(5))
              .build();
      try (NativeConnection connection = NativeConnection.open(options)) {
        QueryRequest request =
            QueryRequest.builder("SELECT n FROM slow").timeout(Duration.ofMillis(400)).build();
        assertThatThrownBy(
                () -> {
                  try (QueryResult result = connection.query(request)) {
                    result.nextBlock();
                  }
                })
            .isInstanceOf(ChordTimeoutException.class)
            .hasMessageContaining("remains usable");
        assertThat(sawCancel.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(connection.state()).isEqualTo(ConnectionState.READY);
      }
      serverThread.join(Duration.ofSeconds(5));
    }
  }

  @Test
  void unansweredCancelAbandonsTheConnectionAfterTheGracePeriod() throws Exception {
    try (ServerSocket server = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
      CountDownLatch release = new CountDownLatch(1);
      Thread serverThread =
          Thread.ofVirtual()
              .start(
                  () -> {
                    try (Socket socket = server.accept()) {
                      OutputStream out = socket.getOutputStream();
                      out.write(helloBytes());
                      out.flush();
                      drainInbound(socket.getInputStream(), 250);
                      out.write(headerDataPacket());
                      out.flush();
                      // Never conclude the stream; the client must give up after the grace.
                      release.await();
                    } catch (Exception e) {
                      // Expected when the client abandons the connection.
                    }
                  });

      ConnectionOptions options =
          ConnectionOptions.builder()
              .host("127.0.0.1")
              .port(server.getLocalPort())
              .cancelGrace(Duration.ofMillis(300))
              .build();
      NativeConnection connection = NativeConnection.open(options);
      QueryRequest request =
          QueryRequest.builder("SELECT n FROM stuck").timeout(Duration.ofMillis(200)).build();
      assertThatThrownBy(
              () -> {
                try (QueryResult result = connection.query(request)) {
                  result.nextBlock();
                }
              })
          .isInstanceOf(ChordTimeoutException.class)
          .hasMessageContaining("did not conclude");
      assertThat(connection.state()).isEqualTo(ConnectionState.CLOSED);
      release.countDown();
      serverThread.join(Duration.ofSeconds(5));
    }
  }
}
