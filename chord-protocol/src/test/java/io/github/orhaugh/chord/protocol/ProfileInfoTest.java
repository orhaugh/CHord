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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.orhaugh.chord.ChordProtocolException;
import io.github.orhaugh.chord.protocol.wire.WireLimits;
import io.github.orhaugh.chord.protocol.wire.WireReader;
import io.github.orhaugh.chord.protocol.wire.WireWriter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * The ProfileInfo packet body across its revision gate, plus truncation for it and its sibling
 * Progress, the two packet bodies that previously had no hostile input coverage.
 */
class ProfileInfoTest {

  private static WireReader reader(Consumer<WireWriter> actions) {
    ByteArrayOutputStream sink = new ByteArrayOutputStream();
    WireWriter writer = new WireWriter(sink);
    actions.accept(writer);
    writer.flush();
    return new WireReader(new ByteArrayInputStream(sink.toByteArray()), WireLimits.DEFAULTS);
  }

  @Test
  void readsEveryFieldAtTheCurrentRevision() {
    WireReader in =
        reader(
            w -> {
              w.writeVarUInt(1000); // rows
              w.writeVarUInt(4); // blocks
              w.writeVarUInt(65536); // bytes
              w.writeBool(true); // applied limit
              w.writeVarUInt(5000); // rows before limit
              w.writeBool(false); // obsolete calculated_rows_before_limit
              w.writeBool(true); // applied aggregation (54469)
              w.writeVarUInt(9000); // rows before aggregation
            });
    ProfileInfo info = ProfileInfo.read(in, 54488);
    assertThat(info.rows()).isEqualTo(1000);
    assertThat(info.blocks()).isEqualTo(4);
    assertThat(info.bytes()).isEqualTo(65536);
    assertThat(info.appliedLimit()).isTrue();
    assertThat(info.rowsBeforeLimit()).isEqualTo(5000);
    assertThat(info.appliedAggregation()).isTrue();
    assertThat(info.rowsBeforeAggregation()).isEqualTo(9000);
  }

  @Test
  void omitsTheAggregationFieldsBelowTheirGate() {
    WireReader in =
        reader(
            w -> {
              w.writeVarUInt(10);
              w.writeVarUInt(1);
              w.writeVarUInt(100);
              w.writeBool(false);
              w.writeVarUInt(0);
              w.writeBool(false);
              // Nothing further: revision 54468 predates rows before aggregation.
            });
    ProfileInfo info = ProfileInfo.read(in, 54468);
    assertThat(info.rows()).isEqualTo(10);
    assertThat(info.appliedAggregation()).isFalse();
    assertThat(info.rowsBeforeAggregation()).isZero();
  }

  @Test
  void truncatedProfileInfoFailsExplicitly() {
    WireReader in =
        reader(
            w -> {
              w.writeVarUInt(10);
              w.writeVarUInt(1);
              // The stream dies before the remaining fields.
            });
    assertThatThrownBy(() -> ProfileInfo.read(in, 54488))
        .isInstanceOf(ChordProtocolException.class)
        .hasMessageContaining("Stream ended");
  }

  @Test
  void truncatedProgressFailsExplicitly() {
    WireReader in =
        reader(
            w -> {
              w.writeVarUInt(100);
              w.writeVarUInt(2048);
              // Five more counters were due at this revision.
            });
    assertThatThrownBy(() -> Progress.read(in, 54488))
        .isInstanceOf(ChordProtocolException.class)
        .hasMessageContaining("Stream ended");
  }
}
