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

import io.github.orhaugh.chord.ChordAuthenticationException;
import io.github.orhaugh.chord.ChordConfigurationException;
import io.github.orhaugh.chord.ChordProtocolException;
import io.github.orhaugh.chord.ChordServerException;
import io.github.orhaugh.chord.protocol.ProtocolRevisions;
import io.github.orhaugh.chord.protocol.handshake.ChunkedProtocolMode;
import io.github.orhaugh.chord.protocol.handshake.ClientHello;
import io.github.orhaugh.chord.protocol.handshake.HelloCodec;
import io.github.orhaugh.chord.protocol.state.ConnectionState;
import io.github.orhaugh.chord.protocol.state.IllegalStateTransitionException;
import io.github.orhaugh.chord.protocol.wire.WireWriter;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** Handshake and ping behaviour of {@link NativeConnection} over scripted transports. */
class NativeConnectionTest {

  private static final long SERVER_REVISION = 54488;

  private static byte[] script(Consumer<WireWriter> actions) {
    ByteArrayOutputStream sink = new ByteArrayOutputStream();
    WireWriter writer = new WireWriter(sink);
    actions.accept(writer);
    writer.flush();
    return sink.toByteArray();
  }

  private static void writeServerHello(WireWriter w) {
    writeServerHelloWithCapabilities(w, "notchunked_optional", "notchunked_optional");
  }

  private static void writeServerHelloWithCapabilities(WireWriter w, String send, String recv) {
    w.writeVarUInt(0); // Server::Hello
    w.writeString("ClickHouse");
    w.writeVarUInt(26);
    w.writeVarUInt(7);
    w.writeVarUInt(SERVER_REVISION);
    w.writeVarUInt(8); // parallel replicas protocol version
    w.writeString("UTC");
    w.writeString("scripted");
    w.writeVarUInt(1); // patch
    w.writeString(send);
    w.writeString(recv);
    w.writeVarUInt(0); // no password rules
    w.writeInt64Le(7); // nonce
    w.writeString(""); // empty settings block
    w.writeVarUInt(3); // query plan serialisation version
    w.writeVarUInt(8); // cluster function protocol version
  }

  private static void writePong(WireWriter w) {
    w.writeVarUInt(4);
  }

  private static ConnectionOptions options() {
    return ConnectionOptions.builder().host("scripted").build();
  }

  @Test
  void handshakesPingsAndProducesExactClientBytes() {
    ScriptedTransport transport =
        new ScriptedTransport(
            script(
                w -> {
                  writeServerHello(w);
                  writePong(w);
                  writePong(w);
                }));

    NativeConnection connection = NativeConnection.open(options(), transport);
    assertThat(connection.state()).isEqualTo(ConnectionState.READY);
    assertThat(connection.serverHello().serverName()).isEqualTo("ClickHouse");
    assertThat(connection.serverHello().timezone()).contains("UTC");
    assertThat(connection.negotiatedRevision())
        .isEqualTo(Math.min(ProtocolRevisions.CURRENT, SERVER_REVISION));

    connection.ping();
    connection.ping();
    connection.close();
    assertThat(connection.state()).isEqualTo(ConnectionState.CLOSED);
    assertThat(transport.isOpen()).isFalse();

    byte[] expected =
        script(
            w -> {
              HelloCodec.writeClientHello(
                  w,
                  new ClientHello(
                      "CHord Java",
                      NativeConnection.CLIENT_VERSION_MAJOR,
                      NativeConnection.CLIENT_VERSION_MINOR,
                      ProtocolRevisions.CURRENT,
                      "",
                      "default"),
                  new char[0]);
              HelloCodec.writeAddendum(
                  w,
                  SERVER_REVISION,
                  "",
                  ChunkedProtocolMode.NOTCHUNKED,
                  ChunkedProtocolMode.NOTCHUNKED);
              w.writeVarUInt(4); // first ping
              w.writeVarUInt(4); // second ping
            });
    assertThat(transport.clientBytes()).isEqualTo(expected);
  }

  @Test
  void toleratesStaleProgressBeforePong() {
    ScriptedTransport transport =
        new ScriptedTransport(
            script(
                w -> {
                  writeServerHello(w);
                  w.writeVarUInt(3); // Server::Progress
                  for (int i = 0; i < 7; i++) {
                    w.writeVarUInt(i); // progress counters at revision 54488
                  }
                  writePong(w);
                }));

    NativeConnection connection = NativeConnection.open(options(), transport);
    connection.ping();
    assertThat(connection.state()).isEqualTo(ConnectionState.READY);
    connection.close();
  }

  @Test
  void timezoneUpdatePacketsMoveTheDecodeContext() {
    // Real servers only emit TimezoneUpdate on the input() table function path (verified
    // against the 25.8 TCPHandler source), so the handler is proven here: a packet arriving
    // before a data block must move the connection's decode context in time for that block.
    ScriptedTransport transport =
        new ScriptedTransport(
            script(
                w -> {
                  writeServerHello(w);
                  w.writeVarUInt(17); // Server::TimezoneUpdate
                  w.writeString("Asia/Tokyo");
                  w.writeVarUInt(1); // Server::Data
                  w.writeString("");
                  io.github.orhaugh.chord.codec.column.BlockBuilder builder =
                      io.github.orhaugh.chord.codec.column.BlockBuilder.forSchema(
                          decodeSchema("dt", "DateTime"));
                  builder.addRow(java.time.Instant.EPOCH);
                  io.github.orhaugh.chord.codec.block.BlockWriter.write(
                      w, builder.build(), SERVER_REVISION);
                  w.writeVarUInt(5); // EndOfStream
                }));
    try (NativeConnection connection = NativeConnection.open(options(), transport)) {
      try (QueryResult result = connection.query(QueryRequest.of("SELECT now()"))) {
        var column = result.nextBlock().orElseThrow().column(0);
        assertThat(((io.github.orhaugh.chord.codec.column.Columns.DateTimeColumn) column).zone())
            .isEqualTo(java.time.ZoneId.of("Asia/Tokyo"));
        assertThat(result.nextBlock()).isEmpty();
      }
      assertThat(connection.sessionTimezone()).isEqualTo(java.time.ZoneId.of("Asia/Tokyo"));
      assertThat(connection.state()).isEqualTo(ConnectionState.READY);
    }
  }

  @Test
  void credentialSuppliersResolvePerConnection() {
    var counter = new java.util.concurrent.atomic.AtomicInteger();
    ConnectionOptions options =
        ConnectionOptions.builder()
            .host("scripted")
            .credentials(
                () -> ChordCredentials.of("user-" + counter.incrementAndGet(), new char[0]))
            .build();

    for (int round = 1; round <= 2; round++) {
      int expected = round;
      ScriptedTransport transport =
          new ScriptedTransport(script(NativeConnectionTest::writeServerHello));
      try (NativeConnection connection = NativeConnection.open(options, transport)) {
        assertThat(connection.state()).isEqualTo(ConnectionState.READY);
      }
      // The rotated username travelled in this connection's ClientHello.
      byte[] expectedHello =
          script(
              w ->
                  HelloCodec.writeClientHello(
                      w,
                      new ClientHello(
                          "CHord Java",
                          NativeConnection.CLIENT_VERSION_MAJOR,
                          NativeConnection.CLIENT_VERSION_MINOR,
                          ProtocolRevisions.CURRENT,
                          "",
                          "user-" + expected),
                      new char[0]));
      assertThat(java.util.Arrays.copyOf(transport.clientBytes(), expectedHello.length))
          .isEqualTo(expectedHello);
    }
    assertThat(counter.get()).isEqualTo(2);

    // The plaintext protection applies to supplier resolved passwords exactly as to static
    // ones: a password over an insecure transport without the opt in is refused.
    ConnectionOptions withPassword =
        ConnectionOptions.builder()
            .host("scripted")
            .credentials(() -> ChordCredentials.of("app", "secret".toCharArray()))
            .build();
    assertThatThrownBy(
            () ->
                NativeConnection.open(
                    withPassword,
                    new ScriptedTransport(script(NativeConnectionTest::writeServerHello))))
        .isInstanceOf(ChordConfigurationException.class)
        .hasMessageContaining("plaintext");
  }

  @Test
  void operationListenersObserveConnectQueryAndInsert() {
    var connects = new java.util.concurrent.atomic.AtomicInteger();
    var queries = new java.util.concurrent.CopyOnWriteArrayList<String>();
    var inserts = new java.util.concurrent.CopyOnWriteArrayList<String>();
    ChordOperationListener listener =
        new ChordOperationListener() {
          @Override
          public void connectFinished(boolean succeeded, java.time.Duration duration) {
            if (succeeded && !duration.isNegative()) {
              connects.incrementAndGet();
            }
          }

          @Override
          public void queryFinished(String outcome, java.time.Duration duration, long rowsRead) {
            queries.add(outcome);
          }

          @Override
          public void insertFinished(String outcome, java.time.Duration duration, long rowsSent) {
            inserts.add(outcome + ":" + rowsSent);
          }
        };
    ConnectionOptions options =
        ConnectionOptions.builder().host("scripted").operationListener(listener).build();

    ScriptedTransport queryTransport =
        new ScriptedTransport(
            script(
                w -> {
                  writeServerHello(w);
                  w.writeVarUInt(1); // Server::Data: header
                  w.writeString("");
                  io.github.orhaugh.chord.codec.block.BlockWriter.write(
                      w,
                      io.github.orhaugh.chord.codec.column.BlockBuilder.forSchema(
                              decodeSchema("n", "UInt8"))
                          .build(),
                      SERVER_REVISION);
                  w.writeVarUInt(5); // EndOfStream
                }));
    try (NativeConnection connection = NativeConnection.open(options, queryTransport)) {
      try (QueryResult result = connection.query(QueryRequest.of("SELECT n"))) {
        while (result.nextBlock().isPresent()) {
          // Drain.
        }
      }
    }
    assertThat(connects.get()).isEqualTo(1);
    assertThat(queries).containsExactly("finished");

    ScriptedTransport insertTransport =
        new ScriptedTransport(
            script(
                w -> {
                  writeServerHello(w);
                  w.writeVarUInt(1); // Server::Data: the insert schema
                  w.writeString("");
                  io.github.orhaugh.chord.codec.block.BlockWriter.write(
                      w,
                      io.github.orhaugh.chord.codec.column.BlockBuilder.forSchema(
                              decodeSchema("n", "UInt64"))
                          .build(),
                      SERVER_REVISION);
                  w.writeVarUInt(5); // EndOfStream
                }));
    try (NativeConnection connection = NativeConnection.open(options, insertTransport)) {
      InsertStream insert = connection.insert(QueryRequest.of("INSERT INTO t VALUES"));
      io.github.orhaugh.chord.codec.column.BlockBuilder builder = insert.newBlock();
      builder.addRow(java.math.BigInteger.ONE);
      insert.send(builder.build());
      insert.finish();
    }
    assertThat(connects.get()).isEqualTo(2);
    assertThat(inserts).containsExactly("committed:1");
  }

  @Test
  void insertExchangesDispatchProgressPacketsToListeners() {
    // Real servers do not send Progress for client fed inserts (verified against 25.8), but
    // the protocol allows it, so the dispatch and accumulation paths are proven here.
    ScriptedTransport transport =
        new ScriptedTransport(
            script(
                w -> {
                  writeServerHello(w);
                  w.writeVarUInt(1); // Server::Data: the insert schema block
                  w.writeString("");
                  io.github.orhaugh.chord.codec.block.BlockWriter.write(
                      w,
                      io.github.orhaugh.chord.codec.column.BlockBuilder.forSchema(
                              decodeSchema("n", "UInt64"))
                          .build(),
                      SERVER_REVISION);
                  w.writeVarUInt(3); // Server::Progress while consuming the stream
                  w.writeVarUInt(5); // read rows
                  w.writeVarUInt(40); // read bytes
                  w.writeVarUInt(0); // total rows to read
                  w.writeVarUInt(0); // total bytes to read
                  w.writeVarUInt(5); // written rows
                  w.writeVarUInt(40); // written bytes
                  w.writeVarUInt(1_000_000); // elapsed nanos
                  w.writeVarUInt(5); // EndOfStream
                }));
    java.util.List<io.github.orhaugh.chord.protocol.Progress> deltas =
        new java.util.concurrent.CopyOnWriteArrayList<>();
    try (NativeConnection connection = NativeConnection.open(options(), transport)) {
      InsertStream insert =
          connection.insert(
              QueryRequest.builder("INSERT INTO t VALUES").onProgress(deltas::add).build());
      io.github.orhaugh.chord.codec.column.BlockBuilder builder = insert.newBlock();
      builder.addRow(java.math.BigInteger.ONE);
      insert.send(builder.build());
      InsertStream.InsertSummary summary = insert.finish();

      assertThat(deltas).hasSize(1);
      assertThat(deltas.get(0).readRows()).isEqualTo(5);
      assertThat(deltas.get(0).writtenRows()).isEqualTo(5);
      assertThat(summary.progress().writtenRows()).isEqualTo(5); // accumulate ran
      assertThat(connection.state()).isEqualTo(ConnectionState.READY);
    }
  }

  @Test
  void authenticationFailureDuringHandshakeIsTypedAndClosesTransport() {
    ScriptedTransport transport =
        new ScriptedTransport(
            script(
                w -> {
                  w.writeVarUInt(2); // Server::Exception
                  w.writeInt32Le(516);
                  w.writeString("DB::Exception");
                  w.writeString("chord: Authentication failed");
                  w.writeString("");
                  w.writeBool(false);
                }));

    assertThatThrownBy(() -> NativeConnection.open(options(), transport))
        .isInstanceOf(ChordAuthenticationException.class)
        .satisfies(e -> assertThat(((ChordAuthenticationException) e).code()).isEqualTo(516));
    assertThat(transport.isOpen()).isFalse();
  }

  @Test
  void truncatedHandshakeClosesTransport() {
    byte[] full = script(NativeConnectionTest::writeServerHello);
    byte[] truncated = Arrays.copyOf(full, full.length / 2);
    ScriptedTransport transport = new ScriptedTransport(truncated);

    assertThatThrownBy(() -> NativeConnection.open(options(), transport))
        .isInstanceOf(ChordProtocolException.class);
    assertThat(transport.isOpen()).isFalse();
  }

  @Test
  void chunkedStrictServersNegotiateAndExchangeChunkedPackets() {
    ScriptedTransport transport =
        new ScriptedTransport(
            script(
                w -> {
                  // The hello itself is never chunked; framing starts after the addendum.
                  writeServerHelloWithCapabilities(w, "chunked", "chunked");
                  // Pong as one chunked message: [len 1][0x04][terminator 0].
                  w.writeUInt8(1);
                  w.writeUInt8(0);
                  w.writeUInt8(0);
                  w.writeUInt8(0);
                  w.writeUInt8(4);
                  w.writeInt32Le(0);
                }));

    NativeConnection connection = NativeConnection.open(options(), transport);
    connection.ping();
    assertThat(connection.state()).isEqualTo(ConnectionState.READY);
    connection.close();

    byte[] expected =
        script(
            w -> {
              HelloCodec.writeClientHello(
                  w,
                  new ClientHello(
                      "CHord Java",
                      NativeConnection.CLIENT_VERSION_MAJOR,
                      NativeConnection.CLIENT_VERSION_MINOR,
                      ProtocolRevisions.CURRENT,
                      "",
                      "default"),
                  new char[0]);
              HelloCodec.writeAddendum(
                  w, SERVER_REVISION, "", ChunkedProtocolMode.CHUNKED, ChunkedProtocolMode.CHUNKED);
              // Ping as one chunked message.
              w.writeInt32Le(1);
              w.writeUInt8(4);
              w.writeInt32Le(0);
            });
    assertThat(transport.clientBytes()).isEqualTo(expected);
  }

  @Test
  void compressedExchangesFrameBlockBodiesInBothDirections() {
    // Server script: hello, then a compressed SELECT response of header block, one data block
    // and EndOfStream, with block bodies framed.
    byte[] responseBlocks =
        script(
            w -> {
              io.github.orhaugh.chord.codec.column.BlockBuilder builder =
                  io.github.orhaugh.chord.codec.column.BlockBuilder.forSchema(
                      decodeSchema("v", "UInt8"));
              builder.addRow(7);
              io.github.orhaugh.chord.codec.block.BlockWriter.write(
                  w, builder.build(), SERVER_REVISION);
            });
    java.io.ByteArrayOutputStream framed = new java.io.ByteArrayOutputStream();
    var frameOut =
        new io.github.orhaugh.chord.codec.compress.FrameCompressingOutputStream(
            framed, io.github.orhaugh.chord.codec.compress.Compression.LZ4, 0);
    frameOut.write(responseBlocks, 0, responseBlocks.length);
    frameOut.flush();
    byte[] framedBlock = framed.toByteArray();

    ScriptedTransport transport =
        new ScriptedTransport(
            script(
                w -> {
                  writeServerHello(w);
                  w.writeVarUInt(1); // Server::Data
                  w.writeString("");
                  w.writeBytes(framedBlock, 0, framedBlock.length);
                  w.writeVarUInt(5); // EndOfStream
                }));

    ConnectionOptions compressed =
        ConnectionOptions.builder()
            .host("scripted")
            .compression(io.github.orhaugh.chord.codec.compress.Compression.LZ4)
            .build();
    NativeConnection connection = NativeConnection.open(compressed, transport);
    try (QueryResult result = connection.query(QueryRequest.of("SELECT 7"))) {
      var block = result.nextBlock().orElseThrow();
      assertThat(block.column(0).objectAt(0)).isEqualTo(7);
      assertThat(result.nextBlock()).isEmpty();
    }
    assertThat(connection.state()).isEqualTo(ConnectionState.READY);

    // The query packet must carry compression flag 1 and a framed external tables terminator.
    byte[] clientBytes = transport.clientBytes();
    byte[] plainQueryPrefix =
        script(w -> w.writeVarUInt(1)); // Client::Query marker, just to locate the query packet
    int queryStart = indexOf(clientBytes, plainQueryPrefix, 60);
    assertThat(queryStart).isPositive();
    // The terminator Data packet body is a compressed frame: method byte LZ4 after checksum.
    byte[] dataMarker = {0x02, 0x00}; // Client::Data followed by empty table name
    int dataStart = indexOf(clientBytes, dataMarker, queryStart);
    assertThat(dataStart).isPositive();
    assertThat(clientBytes[dataStart + 2 + 16] & 0xFF).isEqualTo(0x82);
    connection.close();
  }

  @Test
  void cancelSendsThePacketAndTheStreamConcludesReusable() {
    // Server script: hello, header block for the query, then EndOfStream answering the cancel.
    ScriptedTransport transport =
        new ScriptedTransport(
            script(
                w -> {
                  writeServerHello(w);
                  w.writeVarUInt(1); // Server::Data, the header block
                  w.writeString("");
                  io.github.orhaugh.chord.codec.block.BlockWriter.write(
                      w,
                      io.github.orhaugh.chord.codec.column.BlockBuilder.forSchema(
                              decodeSchema("n", "UInt8"))
                          .build(),
                      SERVER_REVISION);
                  w.writeVarUInt(5); // EndOfStream
                }));

    NativeConnection connection = NativeConnection.open(options(), transport);
    try (QueryResult result = connection.query(QueryRequest.of("SELECT n FROM t"))) {
      result.cancel();
      result.cancel(); // idempotent
      assertThat(result.nextBlock()).isEmpty();
    }
    assertThat(connection.state()).isEqualTo(ConnectionState.READY);

    // The last client byte before any further packets must be the Cancel packet id.
    byte[] clientBytes = transport.clientBytes();
    assertThat(clientBytes[clientBytes.length - 1]).isEqualTo((byte) 3);
    connection.close();
  }

  private static io.github.orhaugh.chord.codec.block.Block decodeSchema(String name, String type) {
    byte[] schemaBytes =
        script(
            w -> {
              w.writeVarUInt(1);
              w.writeBool(false);
              w.writeVarUInt(2);
              w.writeInt32Le(-1);
              w.writeVarUInt(3);
              w.writeVarUInt(0);
              w.writeVarUInt(0);
              w.writeVarUInt(1);
              w.writeVarUInt(0);
              w.writeString(name);
              w.writeString(type);
              w.writeUInt8(0);
            });
    return io.github.orhaugh.chord.codec.block.BlockReader.read(
        new io.github.orhaugh.chord.protocol.wire.WireReader(
            new java.io.ByteArrayInputStream(schemaBytes),
            io.github.orhaugh.chord.protocol.wire.WireLimits.DEFAULTS),
        new io.github.orhaugh.chord.codec.block.DecodeContext(
            io.github.orhaugh.chord.codec.block.BlockLimits.DEFAULTS,
            SERVER_REVISION,
            java.time.ZoneId.of("UTC")));
  }

  private static int indexOf(byte[] haystack, byte[] needle, int from) {
    outer:
    for (int i = Math.max(0, from); i <= haystack.length - needle.length; i++) {
      for (int j = 0; j < needle.length; j++) {
        if (haystack[i + j] != needle[j]) {
          continue outer;
        }
      }
      return i;
    }
    return -1;
  }

  @Test
  void unknownPacketDuringPingBreaksTheConnection() {
    ScriptedTransport transport =
        new ScriptedTransport(
            script(
                w -> {
                  writeServerHello(w);
                  w.writeVarUInt(99);
                }));

    NativeConnection connection = NativeConnection.open(options(), transport);
    assertThatThrownBy(connection::ping)
        .isInstanceOf(ChordProtocolException.class)
        .hasMessageContaining("99");
    assertThat(connection.state()).isEqualTo(ConnectionState.BROKEN);
    connection.close();
    assertThat(connection.state()).isEqualTo(ConnectionState.CLOSED);
  }

  @Test
  void serverExceptionDuringPingBreaksTheConnection() {
    ScriptedTransport transport =
        new ScriptedTransport(
            script(
                w -> {
                  writeServerHello(w);
                  w.writeVarUInt(2); // Server::Exception
                  w.writeInt32Le(394); // QUERY_WAS_CANCELLED, arbitrary non auth code
                  w.writeString("DB::Exception");
                  w.writeString("something failed out of band");
                  w.writeString("");
                  w.writeBool(false);
                }));

    NativeConnection connection = NativeConnection.open(options(), transport);
    assertThatThrownBy(connection::ping).isInstanceOf(ChordServerException.class);
    assertThat(connection.state()).isEqualTo(ConnectionState.BROKEN);
    connection.close();
  }

  @Test
  void pingAfterCloseFailsImmediately() {
    ScriptedTransport transport =
        new ScriptedTransport(
            script(
                w -> {
                  writeServerHello(w);
                }));

    NativeConnection connection = NativeConnection.open(options(), transport);
    connection.close();
    assertThatThrownBy(connection::ping).isInstanceOf(IllegalStateTransitionException.class);
  }

  @Test
  void plaintextPasswordIsRefusedWithoutOptIn() {
    ScriptedTransport transport = new ScriptedTransport(new byte[0]);
    ConnectionOptions withPassword =
        ConnectionOptions.builder().host("scripted").password("secret").build();

    assertThatThrownBy(() -> NativeConnection.open(withPassword, transport))
        .isInstanceOf(ChordConfigurationException.class)
        .hasMessageContaining("plaintext");
    assertThat(transport.clientBytes()).isEmpty();
    assertThat(transport.isOpen()).isFalse();
  }

  @Test
  void plaintextPasswordProceedsWithExplicitOptIn() {
    ScriptedTransport transport =
        new ScriptedTransport(script(NativeConnectionTest::writeServerHello));
    ConnectionOptions optedIn =
        ConnectionOptions.builder()
            .host("scripted")
            .username("chord")
            .password("secret")
            .allowPlaintextPassword(true)
            .build();

    NativeConnection connection = NativeConnection.open(optedIn, transport);
    assertThat(connection.state()).isEqualTo(ConnectionState.READY);
    connection.close();
  }

  @Test
  void secureTransportCarriesPasswordWithoutOptIn() {
    ScriptedTransport transport =
        new ScriptedTransport(script(NativeConnectionTest::writeServerHello), true);
    ConnectionOptions withPassword =
        ConnectionOptions.builder().host("scripted").username("chord").password("secret").build();

    NativeConnection connection = NativeConnection.open(withPassword, transport);
    assertThat(connection.state()).isEqualTo(ConnectionState.READY);
    connection.close();
  }
}
