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

import io.github.orhaugh.chord.ChordException;
import io.github.orhaugh.chord.RetryClass;
import io.github.orhaugh.chord.codec.column.BlockBuilder;
import io.github.orhaugh.chord.codec.type.TypeParser;
import io.github.orhaugh.chord.protocol.state.ConnectionState;
import io.github.orhaugh.chord.protocol.wire.WireWriter;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Abrupt connection loss at the protocol phases production networks actually break: mid SELECT
 * stream and mid INSERT stream. Every case must surface a typed failure with an honest retry
 * classification, leave the connection BROKEN and never look like a short but successful result.
 */
@Timeout(30)
class FaultInjectionTest {

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
          w.writeVarUInt(8);
          w.writeString("UTC");
          w.writeString("fault-injection");
          w.writeVarUInt(1);
          w.writeString("notchunked_optional");
          w.writeString("notchunked_optional");
          w.writeVarUInt(0);
          w.writeInt64Le(7);
          w.writeString("");
          w.writeVarUInt(3);
          w.writeVarUInt(8);
        });
  }

  /** A Server::Data packet with a UInt64 column of the given values (zero values = header). */
  private static byte[] dataPacket(long... values) {
    BlockBuilder builder =
        BlockBuilder.forColumnTypes(List.of(TypeParser.parse("UInt64", 100, 8)), List.of("n"));
    for (long value : values) {
      builder.addRow(java.math.BigInteger.valueOf(value));
    }
    return script(
        w -> {
          w.writeVarUInt(1); // Server::Data
          w.writeString("");
          io.github.orhaugh.chord.codec.block.BlockWriter.write(
              w, builder.build(), SERVER_REVISION);
        });
  }

  private static void drainInbound(InputStream in, int settleMillis) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(settleMillis);
    byte[] scratch = new byte[8192];
    while (System.nanoTime() < deadline) {
      if (in.available() > 0) {
        if (in.read(scratch, 0, Math.min(scratch.length, in.available())) < 0) {
          return;
        }
      } else {
        Thread.sleep(10);
      }
    }
  }

  private static ConnectionOptions options(int port) {
    return ConnectionOptions.builder().host("127.0.0.1").port(port).build();
  }

  @Test
  void socketDeathMidSelectStreamIsATypedFailureNeverAShortResult() throws Exception {
    try (ServerSocket server = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
      Thread serverThread =
          Thread.ofVirtual()
              .start(
                  () -> {
                    try (Socket socket = server.accept()) {
                      OutputStream out = socket.getOutputStream();
                      out.write(helloBytes());
                      out.flush();
                      drainInbound(socket.getInputStream(), 250);
                      out.write(dataPacket()); // schema header
                      out.write(dataPacket(1, 2, 3)); // one real block
                      out.flush();
                      // Then the server dies with the stream unconcluded.
                    } catch (Exception e) {
                      // The client side assertions carry the test.
                    }
                  });

      try (NativeConnection connection = NativeConnection.open(options(server.getLocalPort()))) {
        QueryResult result = connection.query(QueryRequest.of("SELECT n FROM t"));
        assertThat(result.nextBlock()).isPresent(); // the block that arrived intact
        assertThatThrownBy(result::nextBlock)
            .isInstanceOf(ChordException.class)
            .hasMessageContaining("Stream ended")
            .satisfies(
                e ->
                    assertThat(((ChordException) e).retryClass())
                        .isEqualTo(RetryClass.RETRY_ONLY_IF_IDEMPOTENT));
        assertThat(connection.state()).isEqualTo(ConnectionState.BROKEN);
      }
      serverThread.join(java.time.Duration.ofSeconds(5));
    }
  }

  @Test
  void deathDuringTheHandshakeClassifiesSafeToRetry() throws Exception {
    try (ServerSocket server = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
      Thread serverThread =
          Thread.ofVirtual()
              .start(
                  () -> {
                    try {
                      // Accept and die without a hello: nothing reached execution.
                      server.accept().close();
                    } catch (Exception e) {
                      // Client assertions carry the test.
                    }
                  });
      assertThatThrownBy(() -> NativeConnection.open(options(server.getLocalPort())))
          .isInstanceOf(ChordException.class)
          .satisfies(
              e ->
                  assertThat(((ChordException) e).retryClass())
                      .isEqualTo(RetryClass.SAFE_TO_RETRY));
      serverThread.join(java.time.Duration.ofSeconds(5));
    }
  }

  @Test
  void deathDuringAPingClassifiesSafeToRetry() throws Exception {
    try (ServerSocket server = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
      Thread serverThread =
          Thread.ofVirtual()
              .start(
                  () -> {
                    try (Socket socket = server.accept()) {
                      socket.getOutputStream().write(helloBytes());
                      socket.getOutputStream().flush();
                      drainInbound(socket.getInputStream(), 200);
                      // Die between exchanges; the next ping has no side effects to lose.
                    } catch (Exception e) {
                      // Client assertions carry the test.
                    }
                  });
      NativeConnection connection = NativeConnection.open(options(server.getLocalPort()));
      serverThread.join(java.time.Duration.ofSeconds(5));
      assertThatThrownBy(connection::ping)
          .isInstanceOf(ChordException.class)
          .satisfies(
              e ->
                  assertThat(((ChordException) e).retryClass())
                      .isEqualTo(RetryClass.SAFE_TO_RETRY));
      assertThat(connection.state()).isEqualTo(ConnectionState.BROKEN);
      connection.close();
    }
  }

  @Test
  void deathBeforeTheInsertSchemaClassifiesSafeToRetry() throws Exception {
    try (ServerSocket server = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
      Thread serverThread =
          Thread.ofVirtual()
              .start(
                  () -> {
                    try (Socket socket = server.accept()) {
                      socket.getOutputStream().write(helloBytes());
                      socket.getOutputStream().flush();
                      drainInbound(socket.getInputStream(), 200);
                      // Die before sending the schema: no data was streamed, the server
                      // aborts inserts whose data never arrived, so retrying is safe.
                    } catch (Exception e) {
                      // Client assertions carry the test.
                    }
                  });
      NativeConnection connection = NativeConnection.open(options(server.getLocalPort()));
      assertThatThrownBy(() -> connection.insert(QueryRequest.of("INSERT INTO t VALUES")))
          .isInstanceOf(ChordException.class)
          .satisfies(
              e ->
                  assertThat(((ChordException) e).retryClass())
                      .isEqualTo(RetryClass.SAFE_TO_RETRY));
      assertThat(connection.state()).isEqualTo(ConnectionState.BROKEN);
      connection.close();
      serverThread.join(java.time.Duration.ofSeconds(5));
    }
  }

  @Test
  void connectionLostBeforeInsertFinishHasUnknownOutcome() throws Exception {
    try (ServerSocket server = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
      Thread serverThread =
          Thread.ofVirtual()
              .start(
                  () -> {
                    try (Socket socket = server.accept()) {
                      OutputStream out = socket.getOutputStream();
                      out.write(helloBytes());
                      out.flush();
                      drainInbound(socket.getInputStream(), 250);
                      out.write(dataPacket()); // the INSERT schema block
                      out.flush();
                      // Accept the client's data for a moment, then die without concluding.
                      drainInbound(socket.getInputStream(), 300);
                    } catch (Exception e) {
                      // Client assertions carry the test.
                    }
                  });

      NativeConnection connection = NativeConnection.open(options(server.getLocalPort()));
      InsertStream insert = connection.insert(QueryRequest.of("INSERT INTO t VALUES"));
      BlockBuilder builder = insert.newBlock();
      builder.addRow(java.math.BigInteger.valueOf(42));
      insert.send(builder.build());
      assertThatThrownBy(insert::finish)
          .isInstanceOf(ChordException.class)
          .satisfies(
              e ->
                  assertThat(((ChordException) e).retryClass())
                      .isEqualTo(RetryClass.OUTCOME_UNKNOWN));
      assertThat(connection.state()).isEqualTo(ConnectionState.BROKEN);
      connection.close();
      serverThread.join(java.time.Duration.ofSeconds(5));
    }
  }

  @Test
  void connectionLostWhileStreamingInsertBlocksHasUnknownOutcome() throws Exception {
    try (ServerSocket server = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
      Thread serverThread =
          Thread.ofVirtual()
              .start(
                  () -> {
                    try (Socket socket = server.accept()) {
                      OutputStream out = socket.getOutputStream();
                      out.write(helloBytes());
                      out.flush();
                      drainInbound(socket.getInputStream(), 250);
                      out.write(dataPacket());
                      out.flush();
                      // Die immediately: the client is still streaming blocks.
                    } catch (Exception e) {
                      // Client assertions carry the test.
                    }
                  });

      NativeConnection connection = NativeConnection.open(options(server.getLocalPort()));
      InsertStream insert = connection.insert(QueryRequest.of("INSERT INTO t VALUES"));
      // Push blocks until the dead socket surfaces; the OS buffer absorbs the first writes.
      assertThatThrownBy(
              () -> {
                for (int i = 0; i < 200; i++) {
                  BlockBuilder builder = insert.newBlock();
                  for (int row = 0; row < 4096; row++) {
                    builder.addRow(java.math.BigInteger.valueOf(row));
                  }
                  insert.send(builder.build());
                }
                insert.finish();
              })
          .isInstanceOf(ChordException.class)
          .satisfies(
              e ->
                  assertThat(((ChordException) e).retryClass())
                      .isEqualTo(RetryClass.OUTCOME_UNKNOWN));
      assertThat(connection.state()).isEqualTo(ConnectionState.BROKEN);
      connection.close();
      serverThread.join(java.time.Duration.ofSeconds(5));
    }
  }
}
