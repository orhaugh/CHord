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
package io.github.orhaugh.chord.client;

import java.util.Objects;

/**
 * A username and password pair resolved at connection time. Supplied through {@link
 * ConnectionOptions.Builder#credentials(java.util.function.Supplier)} so long lived pools pick up
 * rotated passwords on every new connection without a rebuild.
 *
 * <p>The password is copied defensively in both directions; callers own the copies they receive and
 * should wipe them after use. Passwords never appear in {@link #toString()}.
 */
public final class ChordCredentials {

  private final String username;
  private final char[] password;

  private ChordCredentials(String username, char[] password) {
    this.username = Objects.requireNonNull(username, "username");
    this.password = Objects.requireNonNull(password, "password").clone();
  }

  /**
   * Creates a credentials pair.
   *
   * @param username the user to authenticate as
   * @param password the password, possibly empty; the array is copied
   * @return the credentials
   */
  public static ChordCredentials of(String username, char[] password) {
    return new ChordCredentials(username, password);
  }

  /**
   * Returns the username.
   *
   * @return the username
   */
  public String username() {
    return username;
  }

  /**
   * Returns a fresh copy of the password characters.
   *
   * @return a copy of the password
   */
  public char[] passwordChars() {
    return password.clone();
  }

  /**
   * Reports whether the password is non empty.
   *
   * @return {@code true} when a password is present
   */
  public boolean hasPassword() {
    return password.length > 0;
  }

  @Override
  public String toString() {
    return "ChordCredentials{username=" + username + ", password=<redacted>}";
  }
}
