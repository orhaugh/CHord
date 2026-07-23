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

/** Golden vectors for fixed width integers, booleans and length prefixed strings. */
class WirePrimitivesTest {

  @Test
  void writesLittleEndianFixedIntegers() {
    assertThat(written(w -> w.writeInt32Le(0x12345678))).isEqualTo(bytes(0x78, 0x56, 0x34, 0x12));
    assertThat(written(w -> w.writeInt32Le(-1))).isEqualTo(bytes(0xFF, 0xFF, 0xFF, 0xFF));
    assertThat(written(w -> w.writeInt64Le(0x0123456789ABCDEFL)))
        .isEqualTo(bytes(0xEF, 0xCD, 0xAB, 0x89, 0x67, 0x45, 0x23, 0x01));
    assertThat(written(w -> w.writeInt64Le(-1L)))
        .isEqualTo(bytes(0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF));
  }

  @Test
  void readsLittleEndianFixedIntegers() {
    assertThat(reader(bytes(0x78, 0x56, 0x34, 0x12)).readInt32Le()).isEqualTo(0x12345678);
    assertThat(reader(bytes(0xFF, 0xFF, 0xFF, 0xFF)).readInt32Le()).isEqualTo(-1);
    assertThat(reader(bytes(0xEF, 0xCD, 0xAB, 0x89, 0x67, 0x45, 0x23, 0x01)).readInt64Le())
        .isEqualTo(0x0123456789ABCDEFL);
    assertThat(reader(bytes(0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF)).readInt64Le())
        .isEqualTo(-1L);
  }

  @Test
  void readsUnsignedByteAndBool() {
    assertThat(reader(bytes(0xFE)).readUInt8()).isEqualTo(254);
    assertThat(reader(bytes(0x00)).readBool()).isFalse();
    assertThat(reader(bytes(0x01)).readBool()).isTrue();
    // ClickHouse readBinary(bool) treats any non zero byte as true.
    assertThat(reader(bytes(0x7F)).readBool()).isTrue();
  }

  @Test
  void writesBoolAndUInt8() {
    assertThat(written(w -> w.writeBool(true))).isEqualTo(bytes(0x01));
    assertThat(written(w -> w.writeBool(false))).isEqualTo(bytes(0x00));
    assertThat(written(w -> w.writeUInt8(200))).isEqualTo(bytes(0xC8));
  }

  @Test
  void writesStringsWithVarUIntLengthPrefix() {
    assertThat(written(w -> w.writeString(""))).isEqualTo(bytes(0x00));
    assertThat(written(w -> w.writeString("hello")))
        .isEqualTo(bytes(0x05, 'h', 'e', 'l', 'l', 'o'));
    // Multi byte UTF-8: G r u-umlaut sharp-s e.
    assertThat(written(w -> w.writeString("Grüße")))
        .isEqualTo(bytes(0x07, 0x47, 0x72, 0xC3, 0xBC, 0xC3, 0x9F, 0x65));
  }

  @Test
  void readsStringsWithVarUIntLengthPrefix() {
    assertThat(reader(bytes(0x00)).readString()).isEmpty();
    assertThat(reader(bytes(0x05, 'h', 'e', 'l', 'l', 'o')).readString()).isEqualTo("hello");
    assertThat(reader(bytes(0x07, 0x47, 0x72, 0xC3, 0xBC, 0xC3, 0x9F, 0x65)).readString())
        .isEqualTo("Grüße");
  }

  @Test
  void rejectsStringLongerThanTheLimit() {
    byte[] fiveByteString = bytes(0x05, 'h', 'e', 'l', 'l', 'o');
    assertThatThrownBy(() -> reader(fiveByteString).readString(4))
        .isInstanceOf(ChordProtocolException.class)
        .hasMessageContaining("exceeds the permitted maximum");
  }

  @Test
  void rejectsStringLengthAboveTheGlobalLimit() {
    WireLimits limits = new WireLimits(8, 16, 100);
    assertThatThrownBy(() -> reader(bytes(0x09, 'a'), limits).readString())
        .isInstanceOf(ChordProtocolException.class)
        .hasMessageContaining("exceeds the permitted maximum");
  }

  @Test
  void treatsHugeDeclaredLengthAsUnsignedAndRejectsIt() {
    // Declared length 2^64 - 1 encoded as a VarUInt must not wrap into a small allocation.
    byte[] data = bytes(0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0x01);
    assertThatThrownBy(() -> reader(data).readString())
        .isInstanceOf(ChordProtocolException.class)
        .hasMessageContaining("18446744073709551615");
  }

  @Test
  void rejectsTruncatedStringPayload() {
    assertThatThrownBy(() -> reader(bytes(0x05, 'h', 'i')).readString())
        .isInstanceOf(ChordProtocolException.class)
        .hasMessageContaining("ended");
  }

  @Test
  void readsRawStringBytesWithoutDecoding() {
    byte[] raw = reader(bytes(0x02, 0xFE, 0xFF)).readStringBytes(16);
    assertThat(raw).isEqualTo(bytes(0xFE, 0xFF));
  }

  @Test
  void tracksConsumedBytesForDiagnostics() {
    WireReader in = reader(bytes(0x05, 'h', 'e', 'l', 'l', 'o', 0x01));
    in.readString();
    assertThat(in.bytesConsumed()).isEqualTo(6);
    in.readUInt8();
    assertThat(in.bytesConsumed()).isEqualTo(7);
  }

  @Test
  void writesRawBytesAcrossBufferBoundaries() {
    byte[] payload = new byte[300];
    for (int i = 0; i < payload.length; i++) {
      payload[i] = (byte) i;
    }
    byte[] out =
        WireTestUtil.written(
            w -> {
              w.writeBytes(payload, 0, payload.length);
            });
    assertThat(out).isEqualTo(payload);
  }

  @Test
  void byteArrayStringsWriteIdenticallyToTheirDecodedForm() {
    byte[] viaString = WireTestUtil.written(w -> w.writeString("héllo"));
    byte[] viaBytes =
        WireTestUtil.written(
            w -> w.writeString("héllo".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    assertThat(viaBytes).isEqualTo(viaString);
    // Raw bytes need no UTF-8 validity: arbitrary payloads travel length prefixed.
    assertThat(WireTestUtil.written(w -> w.writeString(bytes(0xFE, 0xFF))))
        .isEqualTo(bytes(0x02, 0xFE, 0xFF));
  }

  @Test
  void readFullyFillsExactlyOrFailsExplicitly() {
    byte[] out = new byte[4];
    reader(bytes(1, 2, 3, 4)).readFully(out);
    assertThat(out).isEqualTo(bytes(1, 2, 3, 4));

    byte[] tooBig = new byte[4];
    assertThatThrownBy(() -> reader(bytes(1, 2, 3)).readFully(tooBig))
        .isInstanceOf(ChordProtocolException.class)
        .hasMessageContaining("Stream ended");
  }

  @Test
  void theInputStreamViewReadsThroughTheReaderBuffer() throws java.io.IOException {
    WireReader in = reader(bytes(0xFE, 1, 2, 3, 4, 5));
    java.io.InputStream view = in.asInputStream();
    // Single byte reads come back unsigned, never sign extended.
    assertThat(view.read()).isEqualTo(254);
    byte[] target = new byte[5];
    assertThat(view.read(target, 1, 3)).isEqualTo(3);
    assertThat(target).isEqualTo(bytes(0, 1, 2, 3, 0));
    assertThat(view.read(target, 0, 0)).isZero();
    // Both consumers share one position: the reader continues where the view stopped.
    assertThat(in.readUInt8()).isEqualTo(4);
    assertThat(view.read()).isEqualTo(5);
    // Exhaustion is an explicit protocol failure, not a quiet -1: a packet body that ends
    // early is a broken stream, and layered decoders must not treat it as clean EOF.
    assertThatThrownBy(view::read)
        .isInstanceOf(ChordProtocolException.class)
        .hasMessageContaining("Stream ended");
  }

  @Test
  void bufferedBytesReportPendingDataWithoutTouchingTheStream() {
    WireReader in = reader(bytes(7, 8, 9));
    // Nothing has been pulled from the underlying stream yet.
    assertThat(in.hasBufferedBytes()).isFalse();
    assertThat(in.readUInt8()).isEqualTo(7);
    // The first read buffered the remainder of the chunk.
    assertThat(in.hasBufferedBytes()).isTrue();
    in.readUInt8();
    in.readUInt8();
    assertThat(in.hasBufferedBytes()).isFalse();
  }
}
