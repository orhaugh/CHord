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
package io.github.orhaugh.chord.codec;

import io.github.orhaugh.chord.ChordTypeException;

/**
 * The server used a type or serialisation form CHord does not support (yet). The failure happens
 * before any value bytes are consumed, so the error is precise and no data is misread; CHord never
 * guesses the size of an unsupported value and keeps going.
 */
public class UnsupportedClickHouseTypeException extends ChordTypeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message which type or serialisation is unsupported and, where scheduled, when it lands
   */
  public UnsupportedClickHouseTypeException(String message) {
    super(message);
  }
}
