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

import io.github.orhaugh.chord.ChordConfigurationException;

/**
 * The compression applied to native protocol payloads CHord sends. Reading always auto detects the
 * method per frame from its method byte, so this choice affects the write direction only.
 *
 * <p>Method bytes mirror {@code CompressionMethodByte} in the ClickHouse sources: NONE is {@code
 * 0x02}, LZ4 is {@code 0x82} and ZSTD is {@code 0x90}. LZ4HC is an encoder mode of the LZ4 format
 * and shares its method byte.
 */
public enum Compression {

  /** Frames without compression: the framing and checksums still apply. */
  NONE(0x02, 0),
  /** LZ4 block compression, the ClickHouse network default. */
  LZ4(0x82, 0),
  /** LZ4 high compression encoder; decodes as ordinary LZ4. */
  LZ4HC(0x82, 9),
  /** ZSTD at level 1, matching the server's network default level. */
  ZSTD(0x90, 1);

  private final int methodByte;
  private final int defaultLevel;

  Compression(int methodByte, int defaultLevel) {
    this.methodByte = methodByte;
    this.defaultLevel = defaultLevel;
  }

  /**
   * Returns the frame method byte.
   *
   * @return the method byte
   */
  public int methodByte() {
    return methodByte;
  }

  /**
   * Returns the default encoder level for this method.
   *
   * @return the default level
   */
  public int defaultLevel() {
    return defaultLevel;
  }

  /**
   * Validates a caller supplied level for this method.
   *
   * @param level requested level
   * @return the level
   */
  public int checkLevel(int level) {
    switch (this) {
      case NONE, LZ4 -> {
        if (level != 0) {
          throw new ChordConfigurationException(name() + " does not take a level");
        }
      }
      case LZ4HC -> {
        if (level < 1 || level > 12) {
          throw new ChordConfigurationException("LZ4HC level must be 1..12, was " + level);
        }
      }
      case ZSTD -> {
        if (level < 1 || level > 22) {
          throw new ChordConfigurationException("ZSTD level must be 1..22, was " + level);
        }
      }
      default -> throw new IllegalStateException();
    }
    return level;
  }
}
