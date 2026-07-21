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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.function.Consumer;

/** Shared helpers for byte level protocol tests. */
public final class WireTestUtil {

  private WireTestUtil() {}

  /**
   * Builds a byte array from integer literals, keeping golden vectors readable.
   *
   * @param values byte values, each in the range 0 to 255
   * @return the byte array
   */
  public static byte[] bytes(int... values) {
    byte[] out = new byte[values.length];
    for (int i = 0; i < values.length; i++) {
      out[i] = (byte) values[i];
    }
    return out;
  }

  /**
   * Creates a reader over a fixed byte array with default limits.
   *
   * @param data wire bytes
   * @return a reader positioned at the start of the data
   */
  public static WireReader reader(byte[] data) {
    return new WireReader(new ByteArrayInputStream(data), WireLimits.DEFAULTS);
  }

  /**
   * Creates a reader over a fixed byte array with explicit limits.
   *
   * @param data wire bytes
   * @param limits limits to enforce
   * @return a reader positioned at the start of the data
   */
  public static WireReader reader(byte[] data, WireLimits limits) {
    return new WireReader(new ByteArrayInputStream(data), limits);
  }

  /**
   * Runs a writer callback and returns everything it produced.
   *
   * @param actions callback that writes wire data
   * @return the produced bytes
   */
  public static byte[] written(Consumer<WireWriter> actions) {
    ByteArrayOutputStream sink = new ByteArrayOutputStream();
    WireWriter writer = new WireWriter(sink);
    actions.accept(writer);
    writer.flush();
    return sink.toByteArray();
  }
}
