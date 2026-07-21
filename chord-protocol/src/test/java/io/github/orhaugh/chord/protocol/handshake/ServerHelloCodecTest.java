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
package io.github.orhaugh.chord.protocol.handshake;

import static io.github.orhaugh.chord.protocol.wire.WireTestUtil.bytes;
import static io.github.orhaugh.chord.protocol.wire.WireTestUtil.reader;
import static io.github.orhaugh.chord.protocol.wire.WireTestUtil.written;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.orhaugh.chord.ChordAuthenticationException;
import io.github.orhaugh.chord.ChordProtocolException;
import io.github.orhaugh.chord.ChordServerException;
import io.github.orhaugh.chord.protocol.ProtocolRevisions;
import io.github.orhaugh.chord.protocol.wire.WireWriter;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * Decoding of ServerHello across protocol revisions, mirroring {@code TCPHandler::sendHello} and
 * {@code Connection::receiveHello} in the ClickHouse sources.
 */
class ServerHelloCodecTest {

  private static final long ADVERTISED = ProtocolRevisions.CURRENT;

  @Test
  void decodesMinimalRevisionHelloFromExactBytes() {
    // A server at revision 54458: no parallel replicas version, no chunked capabilities,
    // no password rules, no nonce, no settings block.
    byte[] hello =
        bytes(
            // Packet type: Server::Hello = 0.
            0x00,
            // Server name "ClickHouse", length 10.
            0x0A,
            'C',
            'l',
            'i',
            'c',
            'k',
            'H',
            'o',
            'u',
            's',
            'e',
            // Version major 25, minor 8.
            0x19,
            0x08,
            // Revision 54458 as VarUInt.
            0xBA,
            0xA9,
            0x03,
            // Timezone "UTC".
            0x03,
            'U',
            'T',
            'C',
            // Display name "test-host".
            0x09,
            't',
            'e',
            's',
            't',
            '-',
            'h',
            'o',
            's',
            't',
            // Version patch 3.
            0x03);

    ServerHello decoded = HelloCodec.readServerHello(reader(hello), ADVERTISED);

    assertThat(decoded.serverName()).isEqualTo("ClickHouse");
    assertThat(decoded.versionMajor()).isEqualTo(25);
    assertThat(decoded.versionMinor()).isEqualTo(8);
    assertThat(decoded.serverRevision()).isEqualTo(54458);
    assertThat(decoded.versionPatch()).isEqualTo(3);
    assertThat(decoded.versionString()).isEqualTo("25.8.3");
    assertThat(decoded.timezone()).contains("UTC");
    assertThat(decoded.displayName()).contains("test-host");
    assertThat(decoded.parallelReplicasProtocolVersion()).isEmpty();
    assertThat(decoded.chunkedSendCapability()).isEmpty();
    assertThat(decoded.chunkedReceiveCapability()).isEmpty();
    assertThat(decoded.passwordComplexityRules()).isEmpty();
    assertThat(decoded.interserverNonce()).isEmpty();
    assertThat(decoded.serverSettings()).isEmpty();
    assertThat(decoded.queryPlanSerializationVersion()).isEmpty();
    assertThat(decoded.clusterFunctionProtocolVersion()).isEmpty();
  }

  @Test
  void decodesCurrentRevisionHelloWithEveryField() {
    byte[] hello =
        written(
            w -> {
              w.writeVarUInt(0); // Server::Hello
              w.writeString("ClickHouse");
              w.writeVarUInt(26);
              w.writeVarUInt(7);
              w.writeVarUInt(54488);
              w.writeVarUInt(8); // parallel replicas protocol version
              w.writeString("Europe/London");
              w.writeString("prod");
              w.writeVarUInt(1); // patch
              w.writeString("notchunked_optional"); // server send capability
              w.writeString("chunked_optional"); // server receive capability
              w.writeVarUInt(1); // one password rule
              w.writeString(".{12}");
              w.writeString("at least 12 characters");
              w.writeInt64Le(0x0123456789ABCDEFL); // nonce
              // Settings block, STRINGS_WITH_FLAGS entries terminated by an empty name.
              w.writeString("max_threads");
              w.writeVarUInt(0);
              w.writeString("8");
              w.writeString("custom_x");
              w.writeVarUInt(ServerSetting.FLAG_CUSTOM);
              w.writeString("'y'");
              w.writeString("");
              w.writeVarUInt(3); // query plan serialisation version
              w.writeVarUInt(8); // cluster function protocol version
            });

    ServerHello decoded = HelloCodec.readServerHello(reader(hello), ADVERTISED);

    assertThat(decoded.serverRevision()).isEqualTo(54488);
    assertThat(decoded.parallelReplicasProtocolVersion()).hasValue(8);
    assertThat(decoded.timezone()).contains("Europe/London");
    assertThat(decoded.displayName()).contains("prod");
    assertThat(decoded.versionString()).isEqualTo("26.7.1");
    assertThat(decoded.chunkedSendCapability()).contains("notchunked_optional");
    assertThat(decoded.chunkedReceiveCapability()).contains("chunked_optional");
    assertThat(decoded.passwordComplexityRules())
        .containsExactly(new PasswordComplexityRule(".{12}", "at least 12 characters"));
    assertThat(decoded.interserverNonce()).hasValue(0x0123456789ABCDEFL);
    assertThat(decoded.serverSettings())
        .containsExactly(
            new ServerSetting("max_threads", 0, "8"),
            new ServerSetting("custom_x", ServerSetting.FLAG_CUSTOM, "'y'"));
    assertThat(decoded.serverSettings().get(1).isCustom()).isTrue();
    assertThat(decoded.queryPlanSerializationVersion()).hasValue(3);
    assertThat(decoded.clusterFunctionProtocolVersion()).hasValue(8);
  }

  @Test
  void gatesFieldsOnTheMinimumOfAdvertisedAndServerRevision() {
    // The client advertises 54470. The server is newer, but only writes fields the client
    // advertised, so nothing after the chunked capabilities may be parsed.
    long advertised = 54470;
    byte[] hello =
        written(
            w -> {
              w.writeVarUInt(0);
              w.writeString("ClickHouse");
              w.writeVarUInt(26);
              w.writeVarUInt(7);
              w.writeVarUInt(54488); // server revision higher than advertised
              // No parallel replicas version: gated at 54471 > advertised.
              w.writeString("UTC");
              w.writeString("prod");
              w.writeVarUInt(1);
              w.writeString("notchunked");
              w.writeString("notchunked");
              // Rules gated at 54461 <= 54470, so present.
              w.writeVarUInt(0);
              // Nonce gated at 54462 <= 54470, so present.
              w.writeInt64Le(42);
              // Nothing else: settings (54474), query plan (54477), cluster fn (54479).
            });

    ServerHello decoded = HelloCodec.readServerHello(reader(hello), advertised);

    assertThat(decoded.parallelReplicasProtocolVersion()).isEmpty();
    assertThat(decoded.interserverNonce()).hasValue(42);
    assertThat(decoded.serverSettings()).isEmpty();
    assertThat(decoded.queryPlanSerializationVersion()).isEmpty();
  }

  @Test
  void throwsAuthenticationExceptionForCredentialRejection() {
    byte[] response =
        written(
            w -> {
              w.writeVarUInt(2); // Server::Exception
              w.writeInt32Le(516);
              w.writeString("DB::Exception");
              w.writeString("default: Authentication failed");
              w.writeString("");
              w.writeBool(false);
            });

    assertThatThrownBy(() -> HelloCodec.readServerHello(reader(response), ADVERTISED))
        .isInstanceOf(ChordAuthenticationException.class)
        .satisfies(e -> assertThat(((ChordAuthenticationException) e).code()).isEqualTo(516))
        .hasMessageContaining("Authentication failed");
  }

  @Test
  void throwsServerExceptionForOtherHandshakeErrors() {
    byte[] response =
        written(
            w -> {
              w.writeVarUInt(2);
              w.writeInt32Le(81);
              w.writeString("DB::Exception");
              w.writeString("Database nope does not exist");
              w.writeString("");
              w.writeBool(false);
            });

    assertThatThrownBy(() -> HelloCodec.readServerHello(reader(response), ADVERTISED))
        .isInstanceOf(ChordServerException.class)
        .satisfies(e -> assertThat(((ChordServerException) e).code()).isEqualTo(81));
  }

  @Test
  void rejectsUnknownPacketDuringHandshake() {
    byte[] response = bytes(0x63); // packet type 99
    assertThatThrownBy(() -> HelloCodec.readServerHello(reader(response), ADVERTISED))
        .isInstanceOf(ChordProtocolException.class)
        .hasMessageContaining("99");
  }

  @Test
  void refusesServersOlderThanTheSupportedFloor() {
    byte[] hello =
        written(
            w -> {
              w.writeVarUInt(0);
              w.writeString("ClickHouse");
              w.writeVarUInt(21);
              w.writeVarUInt(8);
              w.writeVarUInt(54457); // one below the floor
            });

    assertThatThrownBy(() -> HelloCodec.readServerHello(reader(hello), ADVERTISED))
        .isInstanceOf(ChordProtocolException.class)
        .hasMessageContaining("54457")
        .hasMessageContaining("minimum");
  }

  @Test
  void rejectsOversizedHelloStrings() {
    char[] big = new char[ProtocolRevisions.MAX_HELLO_STRING_BYTES + 1];
    Arrays.fill(big, 'x');
    String oversized = new String(big);
    byte[] hello =
        written(
            w -> {
              w.writeVarUInt(0);
              w.writeString(oversized);
            });

    assertThatThrownBy(() -> HelloCodec.readServerHello(reader(hello), ADVERTISED))
        .isInstanceOf(ChordProtocolException.class)
        .hasMessageContaining("exceeds the permitted maximum");
  }

  @Test
  void rejectsExcessivePasswordRuleCounts() {
    byte[] hello =
        written(
            w -> {
              writeCommonPrefixAtCurrentRevision(w);
              w.writeVarUInt(ProtocolRevisions.MAX_PASSWORD_COMPLEXITY_RULES + 1);
            });

    assertThatThrownBy(() -> HelloCodec.readServerHello(reader(hello), ADVERTISED))
        .isInstanceOf(ChordProtocolException.class)
        .hasMessageContaining("password complexity rules");
  }

  @Test
  void rejectsTruncatedHello() {
    byte[] hello =
        written(
            w -> {
              w.writeVarUInt(0);
              w.writeString("ClickHouse");
              w.writeVarUInt(26);
              // Stream ends before the minor version.
            });

    assertThatThrownBy(() -> HelloCodec.readServerHello(reader(hello), ADVERTISED))
        .isInstanceOf(ChordProtocolException.class)
        .hasMessageContaining("ended");
  }

  private static void writeCommonPrefixAtCurrentRevision(WireWriter w) {
    w.writeVarUInt(0);
    w.writeString("ClickHouse");
    w.writeVarUInt(26);
    w.writeVarUInt(7);
    w.writeVarUInt(54488);
    w.writeVarUInt(8);
    w.writeString("UTC");
    w.writeString("prod");
    w.writeVarUInt(1);
    w.writeString("notchunked");
    w.writeString("notchunked");
  }
}
