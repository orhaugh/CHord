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
  void serverStrictlyRequiringChunkedFramingIsRefused() {
    ScriptedTransport transport =
        new ScriptedTransport(
            script(w -> writeServerHelloWithCapabilities(w, "chunked", "chunked")));

    assertThatThrownBy(() -> NativeConnection.open(options(), transport))
        .isInstanceOf(ChordProtocolException.class)
        .hasMessageContaining("chunked");
    assertThat(transport.isOpen()).isFalse();
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
