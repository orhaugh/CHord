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
 * The server rejected the supplied credentials, or authentication could not be completed.
 *
 * <p>When the rejection came from a ClickHouse Exception packet, {@link #code()} and {@link
 * #errorName()} carry the server error, for example {@code 516 AUTHENTICATION_FAILED} or {@code 194
 * REQUIRED_PASSWORD}. When the failure happened before the server answered, {@link #code()} is
 * {@code 0}.
 *
 * <p>The credentials themselves are never included in the message.
 */
public class ChordAuthenticationException extends ChordException {

  private static final long serialVersionUID = 1L;

  private final int code;
  private final String errorName;

  /**
   * Creates an authentication exception that did not originate from a server error packet.
   *
   * @param message description of the failure
   */
  public ChordAuthenticationException(String message) {
    super(message);
    this.code = 0;
    this.errorName = "";
  }

  /**
   * Creates an authentication exception from a server error.
   *
   * @param message description of the failure, including the server message
   * @param code ClickHouse numeric error code
   * @param errorName ClickHouse exception name
   */
  public ChordAuthenticationException(String message, int code, String errorName) {
    super(message);
    this.code = code;
    this.errorName = errorName == null ? "" : errorName;
  }

  /**
   * Returns the ClickHouse numeric error code, or {@code 0} when the failure did not come from a
   * server error packet.
   *
   * @return the numeric error code or {@code 0}
   */
  public int code() {
    return code;
  }

  /**
   * Returns the server exception name, or an empty string when the failure did not come from a
   * server error packet.
   *
   * @return the exception name or an empty string
   */
  public String errorName() {
    return errorName;
  }
}
