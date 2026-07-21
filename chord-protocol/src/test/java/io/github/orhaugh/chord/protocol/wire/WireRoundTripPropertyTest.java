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

import static io.github.orhaugh.chord.protocol.wire.WireTestUtil.reader;
import static io.github.orhaugh.chord.protocol.wire.WireTestUtil.written;
import static org.assertj.core.api.Assertions.assertThat;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;

/** Property based round trips through the wire codecs over the full value domains. */
class WireRoundTripPropertyTest {

  @Property(tries = 2000)
  void varUIntRoundTripsEveryLong(@ForAll long value) {
    byte[] encoded = written(w -> w.writeVarUInt(value));
    assertThat(encoded.length).isBetween(1, 10);
    assertThat(reader(encoded).readVarUInt()).isEqualTo(value);
  }

  @Property(tries = 2000)
  void varIntRoundTripsEveryLong(@ForAll long value) {
    byte[] encoded = written(w -> w.writeVarInt(value));
    assertThat(reader(encoded).readVarInt()).isEqualTo(value);
  }

  @Property(tries = 500)
  void stringsRoundTripArbitraryUnicode(@ForAll String value) {
    byte[] encoded = written(w -> w.writeString(value));
    assertThat(reader(encoded).readString()).isEqualTo(value);
  }

  @Property(tries = 200)
  void fixedIntegersRoundTrip(@ForAll int int32, @ForAll long int64) {
    byte[] encoded =
        written(
            w -> {
              w.writeInt32Le(int32);
              w.writeInt64Le(int64);
            });
    WireReader in = reader(encoded);
    assertThat(in.readInt32Le()).isEqualTo(int32);
    assertThat(in.readInt64Le()).isEqualTo(int64);
  }
}
