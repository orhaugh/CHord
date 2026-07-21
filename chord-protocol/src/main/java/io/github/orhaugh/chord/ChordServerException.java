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

import java.util.Objects;

/**
 * The ClickHouse server reported an error through an Exception packet.
 *
 * <p>The numeric {@linkplain #code() error code}, the {@linkplain #errorName() exception name}, the
 * {@linkplain #serverMessage() server message} and, when the server supplied one, the {@linkplain
 * #serverStackTrace() server side stack trace} are preserved verbatim. Nested server exceptions are
 * chained through {@link #getCause()}.
 *
 * <p>Error codes are defined in {@code src/Common/ErrorCodes.cpp} in the ClickHouse sources.
 */
public class ChordServerException extends ChordException {

  private static final long serialVersionUID = 1L;

  private final int code;
  private final String errorName;
  private final String serverMessage;
  private final String serverStackTrace;

  /**
   * Creates a server exception.
   *
   * @param code ClickHouse numeric error code
   * @param errorName ClickHouse exception name, for example {@code DB::Exception}
   * @param serverMessage message supplied by the server
   * @param serverStackTrace server side stack trace, empty when the server withheld it
   * @param cause nested server exception, or {@code null}
   */
  public ChordServerException(
      int code, String errorName, String serverMessage, String serverStackTrace, Throwable cause) {
    super(formatMessage(code, errorName, serverMessage), cause);
    this.code = code;
    this.errorName = Objects.requireNonNull(errorName, "errorName");
    this.serverMessage = Objects.requireNonNull(serverMessage, "serverMessage");
    this.serverStackTrace = Objects.requireNonNull(serverStackTrace, "serverStackTrace");
  }

  private static String formatMessage(int code, String errorName, String serverMessage) {
    return "ClickHouse server error " + code + " (" + errorName + "): " + serverMessage;
  }

  /**
   * Returns the ClickHouse numeric error code, for example {@code 81} for {@code UNKNOWN_DATABASE}.
   *
   * @return the numeric error code
   */
  public int code() {
    return code;
  }

  /**
   * Returns the server exception name, for example {@code DB::Exception}.
   *
   * @return the exception name reported by the server
   */
  public String errorName() {
    return errorName;
  }

  /**
   * Returns the message exactly as supplied by the server.
   *
   * @return the server message
   */
  public String serverMessage() {
    return serverMessage;
  }

  /**
   * Returns the server side stack trace, or an empty string when the server withheld it.
   *
   * @return the server stack trace text
   */
  public String serverStackTrace() {
    return serverStackTrace;
  }
}
