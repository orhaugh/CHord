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
 * Base type of every exception thrown by CHord.
 *
 * <p>CHord uses unchecked exceptions throughout its native API. Catch {@code ChordException} to
 * handle any client failure, or one of its subtypes to react to a specific failure class:
 *
 * <ul>
 *   <li>{@link ChordServerException}: the server executed the request and reported an error
 *   <li>{@link ChordAuthenticationException}: the server rejected the supplied credentials
 *   <li>{@link ChordProtocolException}: the byte stream violated the native protocol; the
 *       connection is no longer trustworthy
 *   <li>{@link ChordTransportException}: network level failure such as connection refused or reset
 *   <li>{@link ChordTimeoutException}: a configured deadline elapsed
 *   <li>{@link ChordConfigurationException}: the client was configured incorrectly
 * </ul>
 */
public class ChordException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates an exception with a message.
   *
   * @param message human readable description of the failure
   */
  public ChordException(String message) {
    super(message);
  }

  /**
   * Creates an exception with a message and a cause.
   *
   * @param message human readable description of the failure
   * @param cause underlying cause
   */
  public ChordException(String message, Throwable cause) {
    super(message, cause);
  }
}
