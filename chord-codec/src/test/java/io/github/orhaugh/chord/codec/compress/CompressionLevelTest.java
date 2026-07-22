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
package io.github.orhaugh.chord.codec.compress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.orhaugh.chord.ChordConfigurationException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import org.junit.jupiter.api.Test;

/** Compression level bounds per method, and that the extreme legal levels actually compress. */
class CompressionLevelTest {

  @Test
  void levelBoundsAreEnforcedPerMethod() {
    assertThatThrownBy(() -> Compression.LZ4HC.checkLevel(0))
        .isInstanceOf(ChordConfigurationException.class)
        .hasMessageContaining("1..12");
    assertThatThrownBy(() -> Compression.LZ4HC.checkLevel(13))
        .isInstanceOf(ChordConfigurationException.class)
        .hasMessageContaining("1..12");
    assertThatThrownBy(() -> Compression.ZSTD.checkLevel(0))
        .isInstanceOf(ChordConfigurationException.class)
        .hasMessageContaining("1..22");
    assertThatThrownBy(() -> Compression.ZSTD.checkLevel(23))
        .isInstanceOf(ChordConfigurationException.class)
        .hasMessageContaining("1..22");
    assertThatThrownBy(() -> Compression.LZ4.checkLevel(1))
        .isInstanceOf(ChordConfigurationException.class)
        .hasMessageContaining("does not take a level");
    assertThatThrownBy(() -> Compression.NONE.checkLevel(1))
        .isInstanceOf(ChordConfigurationException.class);
  }

  @Test
  void extremeLegalLevelsRoundTrip() {
    byte[] payload = new byte[8192];
    for (int i = 0; i < payload.length; i++) {
      payload[i] = (byte) (i % 32);
    }
    for (var pair :
        java.util.List.of(
            java.util.Map.entry(Compression.LZ4HC, 12),
            java.util.Map.entry(Compression.ZSTD, 22),
            java.util.Map.entry(Compression.ZSTD, 1),
            java.util.Map.entry(Compression.LZ4HC, 1))) {
      ByteArrayOutputStream sink = new ByteArrayOutputStream();
      FrameCompressingOutputStream out =
          new FrameCompressingOutputStream(sink, pair.getKey(), pair.getValue());
      out.write(payload, 0, payload.length);
      out.flush();
      FrameDecompressingInputStream in =
          new FrameDecompressingInputStream(
              new ByteArrayInputStream(sink.toByteArray()), CompressionLimits.DEFAULTS);
      byte[] decoded = new byte[payload.length];
      int read = 0;
      while (read < decoded.length) {
        int n = java.util.Objects.requireNonNull(in).read(decoded, read, decoded.length - read);
        assertThat(n).isPositive();
        read += n;
      }
      assertThat(decoded).as(pair.toString()).isEqualTo(payload);
    }
  }
}
