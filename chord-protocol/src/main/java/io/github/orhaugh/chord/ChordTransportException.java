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
 * A network level failure: connection refused, connection reset, an I/O error mid exchange, or a
 * failure while establishing the transport.
 *
 * <p>Whether the operation can be retried depends on what had already been sent when the failure
 * occurred; the retry classification introduced in a later phase makes that decision explicit.
 */
public class ChordTransportException extends ChordException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates a transport exception.
   *
   * @param message description of the failure
   */
  public ChordTransportException(String message) {
    super(message);
  }

  /**
   * Creates a transport exception with a cause.
   *
   * @param message description of the failure
   * @param cause underlying cause, typically an {@link java.io.IOException}
   */
  public ChordTransportException(String message, Throwable cause) {
    super(message, cause);
  }
}
