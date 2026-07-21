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

/**
 * Bounds applied to every length read from the wire. All lengths received from the server are
 * treated as hostile until proven otherwise: nothing is allocated based on a wire value that
 * exceeds these limits, and a violation raises {@link
 * io.github.orhaugh.chord.ChordProtocolException} and poisons the connection.
 *
 * @param maxStringBytes largest permitted encoded string, in bytes, for general protocol strings
 *     such as exception messages and setting values
 * @param maxExceptionNestingDepth largest permitted chain of nested server exceptions
 * @param maxServerHelloSettings largest permitted number of settings entries in the server Hello
 */
public record WireLimits(
    int maxStringBytes, int maxExceptionNestingDepth, int maxServerHelloSettings) {

  /** Default limits: 16 MiB strings, 16 nested exceptions, 10000 Hello settings entries. */
  public static final WireLimits DEFAULTS = new WireLimits(16 * 1024 * 1024, 16, 10_000);

  /**
   * Validates the limits.
   *
   * @param maxStringBytes must be positive
   * @param maxExceptionNestingDepth must be positive
   * @param maxServerHelloSettings must be positive
   */
  public WireLimits {
    requirePositive(maxStringBytes, "maxStringBytes");
    requirePositive(maxExceptionNestingDepth, "maxExceptionNestingDepth");
    requirePositive(maxServerHelloSettings, "maxServerHelloSettings");
  }

  private static void requirePositive(int value, String name) {
    if (value <= 0) {
      throw new IllegalArgumentException(name + " must be positive, was " + value);
    }
  }
}
