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
package io.github.orhaugh.chord.codec.block;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.orhaugh.chord.ChordProtocolException;
import io.github.orhaugh.chord.ChordTypeException;
import io.github.orhaugh.chord.protocol.wire.WireLimits;
import io.github.orhaugh.chord.protocol.wire.WireReader;
import io.github.orhaugh.chord.protocol.wire.WireWriter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.ZoneId;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * Every {@link BlockLimits} cap enforced through real block decode with hostile wire input: the
 * failure must be explicit and must happen before the declared size is allocated. These are the
 * caps a malicious or corrupted server would push against.
 */
class BlockLimitsEnforcementTest {

  private static final long REVISION = 54488;

  private static byte[] block(Consumer<WireWriter> body) {
    ByteArrayOutputStream sink = new ByteArrayOutputStream();
    WireWriter w = new WireWriter(sink);
    // Minimal BlockInfo: overflow flag, bucket, terminator.
    w.writeVarUInt(1);
    w.writeBool(false);
    w.writeVarUInt(2);
    w.writeInt32Le(-1);
    w.writeVarUInt(0);
    body.accept(w);
    w.flush();
    return sink.toByteArray();
  }

  private static Block decode(byte[] wire, BlockLimits limits) {
    return BlockReader.read(
        new WireReader(new ByteArrayInputStream(wire), WireLimits.DEFAULTS),
        new DecodeContext(limits, REVISION, ZoneId.of("UTC")));
  }

  private static BlockLimits limitsWith(
      long maxArrayElements, int maxStringValueBytes, long maxStringDataPerColumn) {
    return new BlockLimits(
        16, 1000, maxArrayElements, maxStringValueBytes, maxStringDataPerColumn, 1024, 16);
  }

  @Test
  void stringValuesBeyondTheSingleValueCapFailBeforeAllocation() {
    byte[] wire =
        block(
            w -> {
              w.writeVarUInt(1); // columns
              w.writeVarUInt(1); // rows
              w.writeString("s");
              w.writeString("String");
              w.writeUInt8(0); // plain serialisation
              w.writeString("this value is twenty six!!"); // 26 bytes
            });
    assertThatThrownBy(() -> decode(wire, limitsWith(100, 10, 1000)))
        .isInstanceOf(ChordProtocolException.class)
        .hasMessageContaining("10");
  }

  @Test
  void stringColumnPayloadsBeyondThePerColumnCapFail() {
    byte[] wire =
        block(
            w -> {
              w.writeVarUInt(1);
              w.writeVarUInt(3);
              w.writeString("s");
              w.writeString("String");
              w.writeUInt8(0);
              w.writeString("abcdef");
              w.writeString("ghijkl");
              w.writeString("mnopqr");
            });
    assertThatThrownBy(() -> decode(wire, limitsWith(100, 100, 10)))
        .isInstanceOf(ChordProtocolException.class)
        .hasMessageContaining("column payload");
  }

  @Test
  void arrayOffsetsBeyondTheElementCapFailBeforeAllocation() {
    byte[] wire =
        block(
            w -> {
              w.writeVarUInt(1);
              w.writeVarUInt(1);
              w.writeString("a");
              w.writeString("Array(UInt8)");
              w.writeUInt8(0);
              w.writeInt64Le(1_000_000_000L); // one declared offset far beyond the cap
            });
    assertThatThrownBy(() -> decode(wire, limitsWith(100, 100, 1000)))
        .isInstanceOf(ChordProtocolException.class)
        .hasMessageContaining("exceeds the permitted");
  }

  @Test
  void typeNamesBeyondTheLengthCapFail() {
    byte[] wire =
        block(
            w -> {
              w.writeVarUInt(1);
              w.writeVarUInt(0);
              w.writeString("t");
              w.writeString("Nullable(Nullable(Nullable(String)))");
              w.writeUInt8(0);
            });
    BlockLimits tightName = new BlockLimits(16, 1000, 100, 100, 1000, 8, 16);
    assertThatThrownBy(() -> decode(wire, tightName)).isInstanceOf(ChordProtocolException.class);
  }

  @Test
  void typeNestingBeyondTheDepthCapFails() {
    byte[] wire =
        block(
            w -> {
              w.writeVarUInt(1);
              w.writeVarUInt(0);
              w.writeString("t");
              w.writeString("Array(Array(Array(Array(UInt8))))");
              w.writeUInt8(0);
            });
    BlockLimits shallow = new BlockLimits(16, 1000, 100, 100, 1000, 1024, 2);
    assertThatThrownBy(() -> decode(wire, shallow))
        .isInstanceOf(ChordTypeException.class)
        .hasMessageContaining("depth");
  }

  @Test
  void declaredDimensionsBeyondTheirCapsStillFail() {
    // The existing caps, re proven through a custom limits object rather than defaults.
    byte[] tooManyColumns =
        block(
            w -> {
              w.writeVarUInt(17);
              w.writeVarUInt(0);
            });
    assertThatThrownBy(() -> decode(tooManyColumns, limitsWith(100, 100, 1000)))
        .isInstanceOf(ChordProtocolException.class)
        .hasMessageContaining("columns");

    byte[] tooManyRows =
        block(
            w -> {
              w.writeVarUInt(1);
              w.writeVarUInt(1001);
            });
    assertThatThrownBy(() -> decode(tooManyRows, limitsWith(100, 100, 1000)))
        .isInstanceOf(ChordProtocolException.class)
        .hasMessageContaining("rows");
  }
}
