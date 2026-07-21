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
package io.github.orhaugh.chord.protocol;

/**
 * The subset of ClickHouse server error codes that CHord interprets, verified against {@code
 * src/Common/ErrorCodes.cpp} on 2026-07-21. All other codes pass through unmodified in {@link
 * io.github.orhaugh.chord.ChordServerException#code()}.
 */
public final class ServerErrorCodes {

  /** The requested database does not exist. */
  public static final int UNKNOWN_DATABASE = 81;

  /** The user is not known to the server. */
  public static final int UNKNOWN_USER = 192;

  /** The password does not match. */
  public static final int WRONG_PASSWORD = 193;

  /** The user requires a password but none was supplied. */
  public static final int REQUIRED_PASSWORD = 194;

  /** The user is known but not allowed to perform the operation. */
  public static final int ACCESS_DENIED = 497;

  /** Authentication failed; the modern umbrella code for credential rejection. */
  public static final int AUTHENTICATION_FAILED = 516;

  private ServerErrorCodes() {}

  /**
   * Reports whether a server error code represents a credential failure that should surface as
   * {@link io.github.orhaugh.chord.ChordAuthenticationException}.
   *
   * @param code ClickHouse numeric error code
   * @return {@code true} for authentication failures
   */
  public static boolean isAuthenticationFailure(int code) {
    return code == AUTHENTICATION_FAILED
        || code == UNKNOWN_USER
        || code == WRONG_PASSWORD
        || code == REQUIRED_PASSWORD;
  }
}
