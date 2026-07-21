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

import static io.github.orhaugh.chord.protocol.handshake.ChunkedProtocolMode.CHUNKED;
import static io.github.orhaugh.chord.protocol.handshake.ChunkedProtocolMode.CHUNKED_OPTIONAL;
import static io.github.orhaugh.chord.protocol.handshake.ChunkedProtocolMode.NOTCHUNKED;
import static io.github.orhaugh.chord.protocol.handshake.ChunkedProtocolMode.NOTCHUNKED_OPTIONAL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.orhaugh.chord.ChordProtocolException;
import org.junit.jupiter.api.Test;

/** Negotiation matrix for chunked framing, mirroring {@code Connection::connect}. */
class ChunkedProtocolModeTest {

  @Test
  void optionalServerAdoptsClientPreference() {
    assertThat(ChunkedProtocolMode.resolveChunked(CHUNKED_OPTIONAL, NOTCHUNKED, "send")).isFalse();
    assertThat(ChunkedProtocolMode.resolveChunked(NOTCHUNKED_OPTIONAL, CHUNKED, "send")).isTrue();
  }

  @Test
  void optionalClientAdoptsServerRequirement() {
    assertThat(ChunkedProtocolMode.resolveChunked(CHUNKED, NOTCHUNKED_OPTIONAL, "recv")).isTrue();
    assertThat(ChunkedProtocolMode.resolveChunked(NOTCHUNKED, CHUNKED_OPTIONAL, "recv")).isFalse();
  }

  @Test
  void agreementBetweenStrictSidesResolves() {
    assertThat(ChunkedProtocolMode.resolveChunked(CHUNKED, CHUNKED, "send")).isTrue();
    assertThat(ChunkedProtocolMode.resolveChunked(NOTCHUNKED, NOTCHUNKED, "send")).isFalse();
  }

  @Test
  void strictDisagreementFailsExplicitly() {
    assertThatThrownBy(() -> ChunkedProtocolMode.resolveChunked(CHUNKED, NOTCHUNKED, "send"))
        .isInstanceOf(ChordProtocolException.class)
        .hasMessageContaining("send")
        .hasMessageContaining("chunked");
  }

  @Test
  void parsesWireTokens() {
    assertThat(ChunkedProtocolMode.fromWire("chunked")).isEqualTo(CHUNKED);
    assertThat(ChunkedProtocolMode.fromWire("notchunked")).isEqualTo(NOTCHUNKED);
    assertThat(ChunkedProtocolMode.fromWire("chunked_optional")).isEqualTo(CHUNKED_OPTIONAL);
    assertThat(ChunkedProtocolMode.fromWire("notchunked_optional")).isEqualTo(NOTCHUNKED_OPTIONAL);
  }

  @Test
  void rejectsUnknownWireTokens() {
    assertThatThrownBy(() -> ChunkedProtocolMode.fromWire("mystery"))
        .isInstanceOf(ChordProtocolException.class)
        .hasMessageContaining("mystery");
  }
}
