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
package io.github.orhaugh.chord.protocol.wire;

import static io.github.orhaugh.chord.protocol.wire.WireTestUtil.bytes;
import static io.github.orhaugh.chord.protocol.wire.WireTestUtil.reader;
import static io.github.orhaugh.chord.protocol.wire.WireTestUtil.written;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.orhaugh.chord.ChordProtocolException;
import org.junit.jupiter.api.Test;

/**
 * Golden vectors for the LEB128 VarUInt and zigzag VarInt codecs, matching {@code src/IO/VarInt.h}.
 * Encodings span at most ten bytes and carry the full unsigned 64 bit range.
 */
class VarIntCodecTest {

  @Test
  void encodesVarUIntGoldenVectors() {
    assertThat(written(w -> w.writeVarUInt(0))).isEqualTo(bytes(0x00));
    assertThat(written(w -> w.writeVarUInt(1))).isEqualTo(bytes(0x01));
    assertThat(written(w -> w.writeVarUInt(127))).isEqualTo(bytes(0x7F));
    assertThat(written(w -> w.writeVarUInt(128))).isEqualTo(bytes(0x80, 0x01));
    assertThat(written(w -> w.writeVarUInt(255))).isEqualTo(bytes(0xFF, 0x01));
    assertThat(written(w -> w.writeVarUInt(300))).isEqualTo(bytes(0xAC, 0x02));
    assertThat(written(w -> w.writeVarUInt(16384))).isEqualTo(bytes(0x80, 0x80, 0x01));
    // The protocol revision advertised by CHord.
    assertThat(written(w -> w.writeVarUInt(54488))).isEqualTo(bytes(0xD8, 0xA9, 0x03));
    // Largest nine byte value: 2^63 - 1.
    assertThat(written(w -> w.writeVarUInt(Long.MAX_VALUE)))
        .isEqualTo(bytes(0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0x7F));
    // 2^63 needs a tenth byte.
    assertThat(written(w -> w.writeVarUInt(Long.MIN_VALUE)))
        .isEqualTo(bytes(0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x01));
    // 2^64 - 1, the unsigned maximum, carried as -1 in a long.
    assertThat(written(w -> w.writeVarUInt(-1L)))
        .isEqualTo(bytes(0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0x01));
  }

  @Test
  void decodesVarUIntGoldenVectors() {
    assertThat(reader(bytes(0x00)).readVarUInt()).isEqualTo(0);
    assertThat(reader(bytes(0x7F)).readVarUInt()).isEqualTo(127);
    assertThat(reader(bytes(0x80, 0x01)).readVarUInt()).isEqualTo(128);
    assertThat(reader(bytes(0xAC, 0x02)).readVarUInt()).isEqualTo(300);
    assertThat(reader(bytes(0xD8, 0xA9, 0x03)).readVarUInt()).isEqualTo(54488);
    assertThat(reader(bytes(0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0x7F)).readVarUInt())
        .isEqualTo(Long.MAX_VALUE);
    assertThat(
            reader(bytes(0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x01)).readVarUInt())
        .isEqualTo(Long.MIN_VALUE);
    assertThat(
            reader(bytes(0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0x01)).readVarUInt())
        .isEqualTo(-1L);
  }

  @Test
  void acceptsNonMinimalEncodingsLikeTheServer() {
    // ClickHouse readVarUInt accepts padded encodings; they lose no data.
    assertThat(reader(bytes(0x80, 0x00)).readVarUInt()).isEqualTo(0);
    assertThat(reader(bytes(0x81, 0x00)).readVarUInt()).isEqualTo(1);
  }

  @Test
  void rejectsTenthByteCarryingDataBeyondBit63() {
    // A compliant writer's tenth byte is 0x00 or 0x01; 0x02 would silently lose bits.
    assertThatThrownBy(
            () ->
                reader(bytes(0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x02))
                    .readVarUInt())
        .isInstanceOf(ChordProtocolException.class)
        .hasMessageContaining("tenth byte");
  }

  @Test
  void rejectsTenthByteWithContinuationBit() {
    assertThatThrownBy(
            () ->
                reader(bytes(0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x81))
                    .readVarUInt())
        .isInstanceOf(ChordProtocolException.class)
        .hasMessageContaining("tenth byte");
  }

  @Test
  void rejectsTruncatedVarUInt() {
    assertThatThrownBy(() -> reader(bytes()).readVarUInt())
        .isInstanceOf(ChordProtocolException.class)
        .hasMessageContaining("ended");
    assertThatThrownBy(() -> reader(bytes(0x80)).readVarUInt())
        .isInstanceOf(ChordProtocolException.class)
        .hasMessageContaining("ended");
  }

  @Test
  void encodesVarIntZigZagGoldenVectors() {
    assertThat(written(w -> w.writeVarInt(0))).isEqualTo(bytes(0x00));
    assertThat(written(w -> w.writeVarInt(-1))).isEqualTo(bytes(0x01));
    assertThat(written(w -> w.writeVarInt(1))).isEqualTo(bytes(0x02));
    assertThat(written(w -> w.writeVarInt(-2))).isEqualTo(bytes(0x03));
    assertThat(written(w -> w.writeVarInt(2))).isEqualTo(bytes(0x04));
  }

  @Test
  void decodesVarIntZigZagGoldenVectors() {
    assertThat(reader(bytes(0x00)).readVarInt()).isEqualTo(0);
    assertThat(reader(bytes(0x01)).readVarInt()).isEqualTo(-1);
    assertThat(reader(bytes(0x02)).readVarInt()).isEqualTo(1);
    assertThat(reader(bytes(0x03)).readVarInt()).isEqualTo(-2);
    assertThat(reader(bytes(0x04)).readVarInt()).isEqualTo(2);
  }

  @Test
  void roundTripsSignedExtremes() {
    byte[] min = written(w -> w.writeVarInt(Long.MIN_VALUE));
    byte[] max = written(w -> w.writeVarInt(Long.MAX_VALUE));
    assertThat(reader(min).readVarInt()).isEqualTo(Long.MIN_VALUE);
    assertThat(reader(max).readVarInt()).isEqualTo(Long.MAX_VALUE);
  }
}
