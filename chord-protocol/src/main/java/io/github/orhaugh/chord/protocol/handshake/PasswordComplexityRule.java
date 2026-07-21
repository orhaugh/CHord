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
package io.github.orhaugh.chord.protocol.handshake;

import java.util.Objects;

/**
 * A password complexity rule advertised by the server in its Hello packet, used by interactive
 * clients to validate new passwords before submitting them.
 *
 * @param pattern the regular expression a password must match, as configured on the server
 * @param message the message to show when the pattern does not match
 */
public record PasswordComplexityRule(String pattern, String message) {

  /**
   * Validates components.
   *
   * @param pattern the regular expression a password must match
   * @param message the message to show when the pattern does not match
   */
  public PasswordComplexityRule {
    Objects.requireNonNull(pattern, "pattern");
    Objects.requireNonNull(message, "message");
  }
}
