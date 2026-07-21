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

import io.github.orhaugh.chord.ChordConfigurationException;
import java.util.Objects;

/**
 * The client half of the native handshake, excluding the password, which is supplied separately at
 * write time so it never sits inside a value object.
 *
 * <p>ClickHouse rejects ASCII control characters in the database, user and password fields as an
 * SSRF mitigation ({@code Connection::sendHello}); this record enforces the same rule client side.
 * A username may also not begin with a space, because leading space usernames are reserved markers
 * for inter-server, SSH and JWT authentication in the protocol.
 *
 * @param clientName free form client name reported to the server, shown in system.processes
 * @param versionMajor client major version reported to the server
 * @param versionMinor client minor version reported to the server
 * @param protocolRevision protocol revision the client advertises
 * @param database default database for the connection, empty for the server default
 * @param username user to authenticate as
 */
public record ClientHello(
    String clientName,
    long versionMajor,
    long versionMinor,
    long protocolRevision,
    String database,
    String username) {

  /**
   * Validates the handshake fields.
   *
   * @param clientName free form client name reported to the server
   * @param versionMajor client major version
   * @param versionMinor client minor version
   * @param protocolRevision protocol revision the client advertises
   * @param database default database, empty for the server default
   * @param username user to authenticate as
   */
  public ClientHello {
    Objects.requireNonNull(clientName, "clientName");
    Objects.requireNonNull(database, "database");
    Objects.requireNonNull(username, "username");
    if (username.isEmpty()) {
      throw new ChordConfigurationException("username must not be empty; use \"default\"");
    }
    if (username.startsWith(" ")) {
      throw new ChordConfigurationException(
          "username must not start with a space; leading space usernames are reserved protocol"
              + " markers");
    }
    requireNoControlCharacters(database, "database");
    requireNoControlCharacters(username, "username");
  }

  /**
   * Rejects ASCII control characters (code points 0 to 31), matching the server side SSRF
   * mitigation for handshake fields.
   *
   * @param value value to check
   * @param field field name for the error message
   */
  public static void requireNoControlCharacters(CharSequence value, String field) {
    for (int i = 0; i < value.length(); i++) {
      if (value.charAt(i) <= 0x1F) {
        throw new ChordConfigurationException(field + " must not contain ASCII control characters");
      }
    }
  }
}
