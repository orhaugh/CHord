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
import static io.github.orhaugh.chord.protocol.wire.WireTestUtil.written;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.orhaugh.chord.ChordConfigurationException;
import org.junit.jupiter.api.Test;

/**
 * Exact byte expectations for the ClientHello packet, mirroring {@code Connection::sendHello} in
 * the ClickHouse sources.
 */
class ClientHelloCodecTest {

  @Test
  void encodesClientHelloExactBytes() {
    ClientHello hello = new ClientHello("CHord Java", 0, 1, 54488, "default", "default");
    byte[] encoded = written(w -> HelloCodec.writeClientHello(w, hello, new char[0]));

    byte[] expected =
        bytes(
            // Packet type: Client::Hello = 0.
            0x00,
            // Client name "CHord Java", length 10.
            0x0A,
            'C',
            'H',
            'o',
            'r',
            'd',
            ' ',
            'J',
            'a',
            'v',
            'a',
            // Version major 0, minor 1.
            0x00,
            0x01,
            // Protocol revision 54488 as VarUInt.
            0xD8,
            0xA9,
            0x03,
            // Database "default", length 7.
            0x07,
            'd',
            'e',
            'f',
            'a',
            'u',
            'l',
            't',
            // Username "default", length 7.
            0x07,
            'd',
            'e',
            'f',
            'a',
            'u',
            'l',
            't',
            // Empty password.
            0x00);
    assertThat(encoded).isEqualTo(expected);
  }

  @Test
  void encodesPasswordBytes() {
    ClientHello hello = new ClientHello("c", 0, 1, 54488, "db", "u");
    byte[] encoded = written(w -> HelloCodec.writeClientHello(w, hello, "pw".toCharArray()));

    byte[] expected =
        bytes(
            0x00, 0x01, 'c', 0x00, 0x01, 0xD8, 0xA9, 0x03, 0x02, 'd', 'b', 0x01, 'u', 0x02, 'p',
            'w');
    assertThat(encoded).isEqualTo(expected);
  }

  @Test
  void rejectsControlCharactersInDatabaseAndUsername() {
    assertThatThrownBy(() -> new ClientHello("c", 0, 1, 54488, "db" + (char) 1, "user"))
        .isInstanceOf(ChordConfigurationException.class)
        .hasMessageContaining("database");
    assertThatThrownBy(() -> new ClientHello("c", 0, 1, 54488, "db", "user\n"))
        .isInstanceOf(ChordConfigurationException.class)
        .hasMessageContaining("username");
  }

  @Test
  void rejectsControlCharactersInPassword() {
    ClientHello hello = new ClientHello("c", 0, 1, 54488, "db", "u");
    assertThatThrownBy(
            () -> written(w -> HelloCodec.writeClientHello(w, hello, "a\tb".toCharArray())))
        .isInstanceOf(ChordConfigurationException.class)
        .hasMessageContaining("password");
  }

  @Test
  void rejectsUsernamesReservedForProtocolMarkers() {
    assertThatThrownBy(() -> new ClientHello("c", 0, 1, 54488, "db", " SSH KEY AUTHENTICATION x"))
        .isInstanceOf(ChordConfigurationException.class)
        .hasMessageContaining("space");
  }

  @Test
  void rejectsEmptyUsername() {
    assertThatThrownBy(() -> new ClientHello("c", 0, 1, 54488, "db", ""))
        .isInstanceOf(ChordConfigurationException.class)
        .hasMessageContaining("username");
  }
}
