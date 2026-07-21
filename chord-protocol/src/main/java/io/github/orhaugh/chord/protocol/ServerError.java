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

import io.github.orhaugh.chord.ChordAuthenticationException;
import io.github.orhaugh.chord.ChordException;
import io.github.orhaugh.chord.ChordProtocolException;
import io.github.orhaugh.chord.ChordServerException;
import io.github.orhaugh.chord.protocol.wire.WireReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The decoded body of a server Exception packet.
 *
 * <p>Wire format, per {@code writeException} in {@code src/IO/WriteHelpers.cpp}: a little endian
 * {@code Int32} code, the exception name, the message and the stack trace as strings, then a one
 * byte nested flag. Modern servers always write the flag as false, but older peers chained nested
 * exceptions after a true flag, so the reader consumes the chain (bounded by {@link
 * io.github.orhaugh.chord.protocol.wire.WireLimits#maxExceptionNestingDepth()}) to stay framed.
 *
 * @param code ClickHouse numeric error code
 * @param name server exception class name
 * @param message server supplied message
 * @param stackTrace server side stack trace, possibly empty
 * @param nested nested server error, if the server chained one
 */
public record ServerError(
    int code, String name, String message, String stackTrace, Optional<ServerError> nested) {

  /**
   * Validates components.
   *
   * @param code ClickHouse numeric error code
   * @param name server exception class name
   * @param message server supplied message
   * @param stackTrace server side stack trace, possibly empty
   * @param nested nested server error, if the server chained one
   */
  public ServerError {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(message, "message");
    Objects.requireNonNull(stackTrace, "stackTrace");
    Objects.requireNonNull(nested, "nested");
  }

  /**
   * Reads an Exception packet body. The leading packet type identifier must already have been
   * consumed.
   *
   * @param in reader positioned at the exception body
   * @return the decoded error, including any nested chain
   */
  public static ServerError read(WireReader in) {
    int maxDepth = in.limits().maxExceptionNestingDepth();
    List<ServerError> flat = new ArrayList<>();
    boolean hasNested = true;
    int depth = 0;
    while (hasNested) {
      if (depth == maxDepth) {
        throw new ChordProtocolException(
            "Server exception chain exceeds the permitted nesting depth of " + maxDepth);
      }
      int code = in.readInt32Le();
      String name = in.readString();
      String message = in.readString();
      String stackTrace = in.readString();
      hasNested = in.readBool();
      flat.add(new ServerError(code, name, message, stackTrace, Optional.empty()));
      depth++;
    }
    ServerError result = flat.get(flat.size() - 1);
    for (int i = flat.size() - 2; i >= 0; i--) {
      ServerError outer = flat.get(i);
      result =
          new ServerError(
              outer.code(), outer.name(), outer.message(), outer.stackTrace(), Optional.of(result));
    }
    return result;
  }

  /**
   * Converts this error to the matching CHord exception: {@link ChordAuthenticationException} for
   * credential failures, otherwise {@link ChordServerException}. Nested errors become the cause
   * chain.
   *
   * @return the exception to throw
   */
  public ChordException toException() {
    ChordServerException cause = nested.map(ServerError::toServerException).orElse(null);
    if (ServerErrorCodes.isAuthenticationFailure(code)) {
      ChordAuthenticationException authFailure =
          new ChordAuthenticationException(
              "Authentication failed with ClickHouse error " + code + " (" + name + "): " + message,
              code,
              name);
      if (cause != null) {
        authFailure.initCause(cause);
      }
      return authFailure;
    }
    return new ChordServerException(code, name, message, stackTrace, cause);
  }

  private ChordServerException toServerException() {
    ChordServerException cause = nested.map(ServerError::toServerException).orElse(null);
    return new ChordServerException(code, name, message, stackTrace, cause);
  }
}
