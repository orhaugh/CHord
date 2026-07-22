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
package io.github.orhaugh.chord;

/**
 * Received data failed integrity validation: a compressed frame checksum mismatch, a decompressed
 * size that does not match its declaration, or a payload the codec rejects.
 *
 * <p>Corruption may originate in the network, in memory on either side, or in a hostile peer; the
 * connection is unusable afterwards and CHord never attempts to resynchronise past corrupt data.
 */
public class ChordDataCorruptionException extends ChordException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates a data corruption exception.
   *
   * @param message description of the failed validation
   */
  public ChordDataCorruptionException(String message) {
    super(message);
  }

  /**
   * Creates a data corruption exception with a cause.
   *
   * @param message description of the failed validation
   * @param cause underlying codec failure
   */
  public ChordDataCorruptionException(String message, Throwable cause) {
    super(message, cause);
  }
}
