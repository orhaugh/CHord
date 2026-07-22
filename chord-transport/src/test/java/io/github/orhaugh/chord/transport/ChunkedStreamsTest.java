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
package io.github.orhaugh.chord.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.orhaugh.chord.ChordProtocolException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Chunked framing on both directions, including the write coalescing the server depends on. */
class ChunkedStreamsTest {

  /**
   * Captures each underlying write call separately, so tests can assert not just the byte stream
   * but how it is grouped into writes. The grouping matters: the server completes a split chunk
   * header with a single further read and treats a short result as end of stream, so a header must
   * never travel in its own write.
   */
  private static final class RecordingStream extends OutputStream {
    final List<byte[]> writes = new ArrayList<>();
    final ByteArrayOutputStream all = new ByteArrayOutputStream();

    @Override
    public void write(int b) {
      writes.add(new byte[] {(byte) b});
      all.write(b);
    }

    @Override
    public void write(byte[] source, int offset, int length) {
      writes.add(Arrays.copyOfRange(source, offset, offset + length));
      all.write(source, offset, length);
    }
  }

  private static byte[] header(int value) {
    return new byte[] {
      (byte) (value & 0xFF),
      (byte) ((value >>> 8) & 0xFF),
      (byte) ((value >>> 16) & 0xFF),
      (byte) ((value >>> 24) & 0xFF)
    };
  }

  private static byte[] concat(byte[]... parts) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (byte[] part : parts) {
      out.writeBytes(part);
    }
    return out.toByteArray();
  }

  @Test
  void endMessageEmitsHeaderPayloadAndTerminatorInOneWrite() throws IOException {
    RecordingStream recording = new RecordingStream();
    ChunkedOutputStream out = new ChunkedOutputStream(recording);

    out.write("abc".getBytes(StandardCharsets.US_ASCII));
    out.endMessage();

    assertThat(recording.writes).hasSize(1);
    assertThat(recording.writes.get(0))
        .isEqualTo(concat(header(3), "abc".getBytes(StandardCharsets.US_ASCII), header(0)));
  }

  @Test
  void flushedChunksTravelWholeAndTheTerminatorFollowsAlone() throws IOException {
    RecordingStream recording = new RecordingStream();
    ChunkedOutputStream out = new ChunkedOutputStream(recording);

    out.write("abc".getBytes(StandardCharsets.US_ASCII));
    out.flush();
    out.endMessage();

    assertThat(recording.writes).hasSize(2);
    assertThat(recording.writes.get(0))
        .isEqualTo(concat(header(3), "abc".getBytes(StandardCharsets.US_ASCII)));
    assertThat(recording.writes.get(1)).isEqualTo(header(0));
  }

  @Test
  void continuationAfterFlushJoinsTheSameMessage() throws IOException {
    RecordingStream recording = new RecordingStream();
    ChunkedOutputStream out = new ChunkedOutputStream(recording);

    out.write("abc".getBytes(StandardCharsets.US_ASCII));
    out.flush();
    out.write("de".getBytes(StandardCharsets.US_ASCII));
    out.endMessage();

    assertThat(recording.writes).hasSize(2);
    assertThat(recording.writes.get(1))
        .isEqualTo(concat(header(2), "de".getBytes(StandardCharsets.US_ASCII), header(0)));
    assertThat(readOneMessage(recording.all.toByteArray()))
        .isEqualTo("abcde".getBytes(StandardCharsets.US_ASCII));
  }

  @Test
  void emptyMessagesAreSuppressed() throws IOException {
    RecordingStream recording = new RecordingStream();
    ChunkedOutputStream out = new ChunkedOutputStream(recording);

    out.endMessage();
    out.endMessage();

    assertThat(recording.writes).isEmpty();
  }

  @Test
  void payloadsLargerThanTheBufferSplitIntoChunksWithoutLoneHeaders() throws IOException {
    RecordingStream recording = new RecordingStream();
    ChunkedOutputStream out = new ChunkedOutputStream(recording, 16);

    byte[] payload = new byte[40];
    new Random(7).nextBytes(payload);
    out.write(payload);
    out.endMessage();

    // Two full chunks, then the tail chunk sharing its write with the terminator.
    assertThat(recording.writes).hasSize(3);
    assertThat(recording.writes.get(0))
        .isEqualTo(concat(header(16), Arrays.copyOfRange(payload, 0, 16)));
    assertThat(recording.writes.get(1))
        .isEqualTo(concat(header(16), Arrays.copyOfRange(payload, 16, 32)));
    assertThat(recording.writes.get(2))
        .isEqualTo(concat(header(8), Arrays.copyOfRange(payload, 32, 40), header(0)));
    assertThat(readOneMessage(recording.all.toByteArray())).isEqualTo(payload);
  }

  @Test
  void messagesRoundTripThroughWriterAndReader() throws IOException {
    ByteArrayOutputStream wire = new ByteArrayOutputStream();
    ChunkedOutputStream out = new ChunkedOutputStream(wire, 32);
    Random random = new Random(42);
    List<byte[]> messages = new ArrayList<>();
    for (int i = 0; i < 20; i++) {
      byte[] message = new byte[1 + random.nextInt(200)];
      random.nextBytes(message);
      messages.add(message);
      out.write(message);
      if (random.nextBoolean()) {
        out.flush();
      }
      out.endMessage();
    }

    ChunkedInputStream in = new ChunkedInputStream(new ByteArrayInputStream(wire.toByteArray()));
    for (byte[] message : messages) {
      byte[] read = in.readNBytes(message.length);
      assertThat(read).isEqualTo(message);
    }
    assertThat(in.read()).isEqualTo(-1);
  }

  @Test
  void hostileDeclaredChunkLengthsStreamWithoutUpfrontAllocation() throws IOException {
    // A chunk header declaring 4 GiB must not allocate anything: the reader streams the bytes
    // that actually exist and fails explicitly when the stream ends inside the chunk.
    ByteArrayOutputStream wire = new ByteArrayOutputStream();
    wire.writeBytes(header(0xFFFFFFFF));
    wire.writeBytes("only-nine".getBytes(StandardCharsets.US_ASCII));
    ChunkedInputStream in = new ChunkedInputStream(new ByteArrayInputStream(wire.toByteArray()));
    byte[] received = in.readNBytes(9);
    assertThat(received).isEqualTo("only-nine".getBytes(StandardCharsets.US_ASCII));
    assertThatThrownBy(in::read)
        .isInstanceOf(ChordProtocolException.class)
        .hasMessageContaining("middle of a chunk");
  }

  @Test
  void emptyChunkAtMessageStartIsAProtocolViolation() {
    byte[] wire = header(0);
    ChunkedInputStream in = new ChunkedInputStream(new ByteArrayInputStream(wire));
    assertThatThrownBy(in::read)
        .isInstanceOf(ChordProtocolException.class)
        .hasMessageContaining("empty chunk at message start");
  }

  @Test
  void streamsEndingMidChunkOrMidHeaderFailExplicitly() {
    byte[] midChunk = concat(header(4), new byte[] {1, 2});
    assertThatThrownBy(
            () -> new ChunkedInputStream(new ByteArrayInputStream(midChunk)).readNBytes(4))
        .isInstanceOf(ChordProtocolException.class)
        .hasMessageContaining("middle of a chunk");

    byte[] midHeader = {5, 0};
    assertThatThrownBy(() -> new ChunkedInputStream(new ByteArrayInputStream(midHeader)).read())
        .isInstanceOf(ChordProtocolException.class)
        .hasMessageContaining("middle of a chunk header");
  }

  private static byte[] readOneMessage(byte[] wire) throws IOException {
    ChunkedInputStream in = new ChunkedInputStream(new ByteArrayInputStream(wire));
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    in.transferTo(out);
    return out.toByteArray();
  }
}
