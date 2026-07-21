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
package io.github.orhaugh.chord.protocol;

import static io.github.orhaugh.chord.protocol.wire.WireTestUtil.bytes;
import static io.github.orhaugh.chord.protocol.wire.WireTestUtil.reader;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Decoding of Progress packet bodies across revision gates, mirroring {@code ProgressValues::read}
 * in {@code src/IO/Progress.cpp}.
 */
class ProgressTest {

  @Test
  void decodesProgressAtRevision54458() {
    // Gates: written counters at 54420 present; total bytes at 54463 and elapsed at 54460 absent.
    Progress progress = Progress.read(reader(bytes(0x01, 0x02, 0x03, 0x04, 0x05)), 54458);

    assertThat(progress.readRows()).isEqualTo(1);
    assertThat(progress.readBytes()).isEqualTo(2);
    assertThat(progress.totalRowsToRead()).isEqualTo(3);
    assertThat(progress.totalBytesToRead()).isZero();
    assertThat(progress.writtenRows()).isEqualTo(4);
    assertThat(progress.writtenBytes()).isEqualTo(5);
    assertThat(progress.elapsedNanos()).isZero();
  }

  @Test
  void decodesProgressAtCurrentRevision() {
    Progress progress =
        Progress.read(reader(bytes(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07)), 54488);

    assertThat(progress.readRows()).isEqualTo(1);
    assertThat(progress.readBytes()).isEqualTo(2);
    assertThat(progress.totalRowsToRead()).isEqualTo(3);
    assertThat(progress.totalBytesToRead()).isEqualTo(4);
    assertThat(progress.writtenRows()).isEqualTo(5);
    assertThat(progress.writtenBytes()).isEqualTo(6);
    assertThat(progress.elapsedNanos()).isEqualTo(7);
  }
}
