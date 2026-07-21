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
 * The byte stream received from (or produced for) the server violated the ClickHouse native
 * protocol: an unknown packet identifier, a field outside its permitted bounds, a truncated stream,
 * or a value no compliant peer can produce.
 *
 * <p>After this exception the connection must be considered desynchronised. CHord never reuses a
 * connection that has thrown it; callers holding a raw connection must close it.
 */
public class ChordProtocolException extends ChordException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates a protocol violation exception.
   *
   * @param message description of the violation
   */
  public ChordProtocolException(String message) {
    super(message);
  }

  /**
   * Creates a protocol violation exception with a cause.
   *
   * @param message description of the violation
   * @param cause underlying cause
   */
  public ChordProtocolException(String message, Throwable cause) {
    super(message, cause);
  }
}
