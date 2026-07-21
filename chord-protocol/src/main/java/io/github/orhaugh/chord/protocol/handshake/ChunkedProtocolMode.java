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

import io.github.orhaugh.chord.ChordProtocolException;

/**
 * Chunked protocol capabilities exchanged during the handshake, one per channel direction.
 *
 * <p>From protocol revision 54470 the server advertises a capability for each of its channels in
 * ServerHello ({@code chunked}, {@code notchunked}, {@code chunked_optional} or {@code
 * notchunked_optional}) and the client answers in the addendum with the resolved strict value
 * ({@code chunked} or {@code notchunked}) for each of its own channels. Resolution follows {@code
 * Connection::connect} in the ClickHouse sources: an optional side adopts the peer's preference,
 * two strict sides must agree, and disagreement is a connection error.
 *
 * <p>Direction pairing is easy to get wrong: the client's send channel resolves against the
 * server's receive capability, and the client's receive channel against the server's send
 * capability.
 */
public enum ChunkedProtocolMode {

  /** The channel must use chunked framing. */
  CHUNKED("chunked"),
  /** The channel must not use chunked framing. */
  NOTCHUNKED("notchunked"),
  /** Prefers chunked framing but accepts the peer's requirement. */
  CHUNKED_OPTIONAL("chunked_optional"),
  /** Prefers plain framing but accepts the peer's requirement. */
  NOTCHUNKED_OPTIONAL("notchunked_optional");

  private final String wireValue;

  ChunkedProtocolMode(String wireValue) {
    this.wireValue = wireValue;
  }

  /**
   * Returns the exact token used on the wire.
   *
   * @return the wire token
   */
  public String wireValue() {
    return wireValue;
  }

  /**
   * Reports whether this capability names chunked framing, ignoring optionality.
   *
   * @return {@code true} for {@link #CHUNKED} and {@link #CHUNKED_OPTIONAL}
   */
  public boolean prefersChunked() {
    return this == CHUNKED || this == CHUNKED_OPTIONAL;
  }

  /**
   * Reports whether this capability is negotiable.
   *
   * @return {@code true} for the optional variants
   */
  public boolean isOptional() {
    return this == CHUNKED_OPTIONAL || this == NOTCHUNKED_OPTIONAL;
  }

  /**
   * Parses a capability token received from the server.
   *
   * @param wireValue token as read from ServerHello
   * @return the parsed capability
   */
  public static ChunkedProtocolMode fromWire(String wireValue) {
    for (ChunkedProtocolMode mode : values()) {
      if (mode.wireValue.equals(wireValue)) {
        return mode;
      }
    }
    throw new ChordProtocolException(
        "Unknown chunked protocol capability received from server: \"" + wireValue + "\"");
  }

  /**
   * Resolves whether a client channel uses chunked framing, mirroring the {@code is_chunked}
   * resolution in {@code Connection::connect}.
   *
   * @param serverCapability the server capability for the opposite direction
   * @param clientPreference the client preference for this channel
   * @param direction channel label for error messages, {@code "send"} or {@code "recv"}
   * @return {@code true} when the channel must use chunked framing
   */
  public static boolean resolveChunked(
      ChunkedProtocolMode serverCapability,
      ChunkedProtocolMode clientPreference,
      String direction) {
    if (serverCapability.isOptional()) {
      return clientPreference.prefersChunked();
    }
    if (clientPreference.isOptional()) {
      return serverCapability.prefersChunked();
    }
    if (clientPreference.prefersChunked() != serverCapability.prefersChunked()) {
      throw new ChordProtocolException(
          "Incompatible chunked protocol for the "
              + direction
              + " channel: client requires "
              + (clientPreference.prefersChunked() ? "chunked" : "notchunked")
              + " but the server requires "
              + (serverCapability.prefersChunked() ? "chunked" : "notchunked"));
    }
    return serverCapability.prefersChunked();
  }
}
