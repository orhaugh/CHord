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

/**
 * Bounds applied while decoding native blocks. Every dimension received from the server is hostile
 * until proven otherwise: nothing is allocated from a wire value that exceeds these limits.
 *
 * @param maxColumns largest permitted number of columns in one block
 * @param maxRows largest permitted number of rows in one block
 * @param maxArrayElements largest permitted total number of flattened elements for one array or map
 *     column in one block
 * @param maxStringValueBytes largest permitted single string value
 * @param maxStringDataBytesPerColumn largest permitted total string payload for one column in one
 *     block
 * @param maxTypeNameLength largest permitted column type name
 * @param maxTypeDepth largest permitted type nesting depth
 */
public record BlockLimits(
    int maxColumns,
    long maxRows,
    long maxArrayElements,
    int maxStringValueBytes,
    long maxStringDataBytesPerColumn,
    int maxTypeNameLength,
    int maxTypeDepth) {

  /**
   * Defaults: 65536 columns, 100 million rows, 100 million array elements, 16 MiB strings, 256 MiB
   * of string data per column and block, 128 KiB type names, depth 64.
   */
  public static final BlockLimits DEFAULTS =
      new BlockLimits(
          65_536, 100_000_000L, 100_000_000L, 16 * 1024 * 1024, 256L * 1024 * 1024, 131_072, 64);

  /** Validates the limits. */
  public BlockLimits {
    if (maxColumns <= 0
        || maxRows <= 0
        || maxArrayElements <= 0
        || maxStringValueBytes <= 0
        || maxStringDataBytesPerColumn <= 0
        || maxTypeNameLength <= 0
        || maxTypeDepth <= 0) {
      throw new IllegalArgumentException("block limits must be positive");
    }
  }
}
