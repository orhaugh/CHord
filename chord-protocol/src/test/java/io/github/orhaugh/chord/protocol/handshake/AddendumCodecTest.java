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

import io.github.orhaugh.chord.ChordProtocolException;
import org.junit.jupiter.api.Test;

/**
 * Exact byte expectations for the client addendum, mirroring {@code Connection::sendAddendum} in
 * the ClickHouse sources.
 */
class AddendumCodecTest {

  @Test
  void encodesFullAddendumAtCurrentRevision() {
    byte[] encoded =
        written(
            w ->
                HelloCodec.writeAddendum(
                    w, 54488, "", ChunkedProtocolMode.NOTCHUNKED, ChunkedProtocolMode.NOTCHUNKED));

    byte[] expected =
        bytes(
            // Empty quota key.
            0x00,
            // Resolved send framing "notchunked".
            0x0A,
            'n',
            'o',
            't',
            'c',
            'h',
            'u',
            'n',
            'k',
            'e',
            'd',
            // Resolved receive framing "notchunked".
            0x0A,
            'n',
            'o',
            't',
            'c',
            'h',
            'u',
            'n',
            'k',
            'e',
            'd',
            // Parallel replicas protocol version 8.
            0x08);
    assertThat(encoded).isEqualTo(expected);
  }

  @Test
  void encodesQuotaKeyOnlyAtRevision54458() {
    byte[] encoded =
        written(
            w ->
                HelloCodec.writeAddendum(
                    w,
                    54458,
                    "team-a",
                    ChunkedProtocolMode.NOTCHUNKED,
                    ChunkedProtocolMode.NOTCHUNKED));

    assertThat(encoded).isEqualTo(bytes(0x06, 't', 'e', 'a', 'm', '-', 'a'));
  }

  @Test
  void omitsParallelReplicasVersionBelowItsGate() {
    byte[] encoded =
        written(
            w ->
                HelloCodec.writeAddendum(
                    w, 54470, "", ChunkedProtocolMode.NOTCHUNKED, ChunkedProtocolMode.NOTCHUNKED));

    byte[] expected =
        bytes(
            0x00, 0x0A, 'n', 'o', 't', 'c', 'h', 'u', 'n', 'k', 'e', 'd', 0x0A, 'n', 'o', 't', 'c',
            'h', 'u', 'n', 'k', 'e', 'd');
    assertThat(encoded).isEqualTo(expected);
  }

  @Test
  void refusesRevisionsWithoutAddendumSupport() {
    assertThatThrownBy(
            () ->
                written(
                    w ->
                        HelloCodec.writeAddendum(
                            w,
                            54457,
                            "",
                            ChunkedProtocolMode.NOTCHUNKED,
                            ChunkedProtocolMode.NOTCHUNKED)))
        .isInstanceOf(ChordProtocolException.class)
        .hasMessageContaining("54458");
  }

  @Test
  void refusesUnresolvedOptionalFramingModes() {
    assertThatThrownBy(
            () ->
                written(
                    w ->
                        HelloCodec.writeAddendum(
                            w,
                            54488,
                            "",
                            ChunkedProtocolMode.NOTCHUNKED_OPTIONAL,
                            ChunkedProtocolMode.NOTCHUNKED)))
        .isInstanceOf(ChordProtocolException.class)
        .hasMessageContaining("resolved");
  }
}
