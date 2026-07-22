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

/**
 * Bounds applied while decoding compressed frames. Declared sizes are hostile until proven
 * otherwise: nothing is allocated beyond these limits, protecting against decompression bombs and
 * corrupt length fields.
 *
 * @param maxCompressedFrameBytes largest permitted compressed frame including its header; the
 *     server enforces the same 1 GiB bound ({@code DBMS_MAX_COMPRESSED_SIZE})
 * @param maxDecompressedFrameBytes largest permitted declared decompressed size of one frame
 */
public record CompressionLimits(int maxCompressedFrameBytes, int maxDecompressedFrameBytes) {

  /** Defaults: 1 GiB compressed (the server's own cap) and 1 GiB decompressed. */
  public static final CompressionLimits DEFAULTS = new CompressionLimits(0x4000_0000, 0x4000_0000);

  /** Validates the limits. */
  public CompressionLimits {
    if (maxCompressedFrameBytes <= 9 || maxDecompressedFrameBytes <= 0) {
      throw new IllegalArgumentException("compression limits must be positive");
    }
  }
}
