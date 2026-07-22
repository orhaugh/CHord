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
package io.github.orhaugh.chord.jdbc;

import io.github.orhaugh.chord.ChordAuthenticationException;
import io.github.orhaugh.chord.ChordConfigurationException;
import io.github.orhaugh.chord.ChordException;
import io.github.orhaugh.chord.ChordServerException;
import io.github.orhaugh.chord.ChordTimeoutException;
import io.github.orhaugh.chord.RetryClass;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLInvalidAuthorizationSpecException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLNonTransientException;
import java.sql.SQLSyntaxErrorException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransientConnectionException;
import java.sql.SQLTransientException;

/**
 * Maps CHord exceptions into the JDBC exception hierarchy.
 *
 * <p>The retry classification carries through: failures classified {@code SAFE_TO_RETRY} or {@code
 * RETRY_ONLY_IF_IDEMPOTENT} surface as {@link SQLTransientException} subtypes and everything else
 * as non transient, so JDBC callers and pools that dispatch on the standard hierarchy inherit
 * CHord's judgement. Server error codes become vendor codes, with SQLStates for the well known
 * cases.
 */
final class SqlExceptions {

  private SqlExceptions() {}

  /** Wraps a CHord failure as the closest JDBC exception. */
  static SQLException map(ChordException e) {
    if (e instanceof ChordAuthenticationException auth) {
      return new SQLInvalidAuthorizationSpecException(auth.getMessage(), "28000", auth.code(), e);
    }
    if (e instanceof ChordTimeoutException) {
      return new SQLTimeoutException(e.getMessage(), "57014", e);
    }
    if (e instanceof ChordConfigurationException) {
      return new SQLNonTransientException(e.getMessage(), "42000", e);
    }
    if (e instanceof ChordServerException server) {
      return mapServer(server);
    }
    // Transport, protocol and corruption failures: connection level, transience by class.
    boolean transientFailure =
        e.retryClass() == RetryClass.SAFE_TO_RETRY
            || e.retryClass() == RetryClass.RETRY_ONLY_IF_IDEMPOTENT;
    return transientFailure
        ? new SQLTransientConnectionException(e.getMessage(), "08000", e)
        : new SQLNonTransientConnectionException(e.getMessage(), "08000", e);
  }

  private static SQLException mapServer(ChordServerException server) {
    String state =
        switch (server.code()) {
          case 62, 47, 42 -> "42000"; // SYNTAX_ERROR, UNKNOWN_IDENTIFIER, wrong arguments
          case 60, 81 -> "42S02"; // UNKNOWN_TABLE, UNKNOWN_DATABASE
          case 516 -> "28000"; // AUTHENTICATION_FAILED
          case 497 -> "42501"; // ACCESS_DENIED
          case 241 -> "53200"; // MEMORY_LIMIT_EXCEEDED
          case 159, 209 -> "57014"; // TIMEOUT_EXCEEDED, SOCKET_TIMEOUT
          default -> "HY000";
        };
    if ("42000".equals(state)) {
      return new SQLSyntaxErrorException(server.getMessage(), state, server.code(), server);
    }
    return switch (server.retryClass()) {
      case SAFE_TO_RETRY, RETRY_ONLY_IF_IDEMPOTENT ->
          new SQLTransientException(server.getMessage(), state, server.code(), server);
      case OUTCOME_UNKNOWN, NOT_RETRYABLE ->
          new SQLNonTransientException(server.getMessage(), state, server.code(), server);
    };
  }

  /** The honest refusal for features ClickHouse or this adapter does not have. */
  static SQLFeatureNotSupportedException unsupported(String feature) {
    return new SQLFeatureNotSupportedException(feature + " is not supported", "0A000");
  }

  /** Guard for closed JDBC objects. */
  static SQLException closed(String what) {
    return new SQLNonTransientException("The " + what + " is closed", "08003");
  }
}
