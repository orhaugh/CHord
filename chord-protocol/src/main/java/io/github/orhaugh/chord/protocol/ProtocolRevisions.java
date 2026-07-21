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
 * ClickHouse native TCP protocol revision constants.
 *
 * <p>A protocol revision is not a server version. The revision ({@code DBMS_TCP_PROTOCOL_VERSION}
 * in the ClickHouse sources) increments only on wire changes, while server versions release
 * monthly. As reference points: ClickHouse 25.8 speaks revision 54479, 26.3 speaks 54484, 26.6
 * speaks 54485 and master speaks {@link #CURRENT}. The full mapping is maintained in {@code
 * docs/protocol-compatibility.md}.
 *
 * <p>Values were verified against {@code src/Core/ProtocolDefines.h} on the ClickHouse master
 * branch on 2026-07-21.
 */
public final class ProtocolRevisions {

  /**
   * The highest protocol revision this client implements and advertises in ClientHello, equal to
   * {@code DBMS_TCP_PROTOCOL_VERSION} of the ClickHouse master branch at verification time.
   * Advertising a revision is a promise to encode and decode every conditional field gated at or
   * below it, so this constant only moves when the affected codecs move with it.
   */
  public static final long CURRENT = 54488;

  /**
   * The lowest server revision this client accepts. Below this the handshake has a structurally
   * different shape (no client addendum) that CHord does not implement or test. The constant equals
   * {@code DBMS_MIN_PROTOCOL_VERSION_WITH_ADDENDUM}. Servers older than this are refused with a
   * clear error rather than partially supported. Supported and tested server releases are
   * documented in {@code docs/protocol-compatibility.md}.
   */
  public static final long MIN_SUPPORTED_SERVER_REVISION = 54458;

  /**
   * The parallel replicas protocol version sent in the client addendum when the negotiated revision
   * enables {@link ProtocolFeature#VERSIONED_PARALLEL_REPLICAS_PROTOCOL}, mirroring {@code
   * DBMS_PARALLEL_REPLICAS_PROTOCOL_VERSION}. CHord sends the value for handshake parity with the
   * official client but does not implement parallel replica packets; receiving one is an explicit
   * protocol error.
   */
  public static final long PARALLEL_REPLICAS_PROTOCOL_VERSION = 8;

  /**
   * Upper bound for every server supplied string in the Hello packet, mirroring {@code
   * DBMS_MAX_HELLO_STRING_SIZE}. The server enforces the same bound when constructing its Hello.
   */
  public static final int MAX_HELLO_STRING_BYTES = 4096;

  /**
   * Upper bound for the number of password complexity rules in the server Hello, mirroring {@code
   * DBMS_MAX_PASSWORD_COMPLEXITY_RULES}.
   */
  public static final int MAX_PASSWORD_COMPLEXITY_RULES = 256;

  private ProtocolRevisions() {}

  /**
   * Computes the negotiated revision for a connection: the lower of what this client advertised and
   * what the server reported. Every conditional field on the wire is gated on this value.
   *
   * @param advertisedRevision revision the client sent in ClientHello
   * @param serverRevision revision the server reported in ServerHello
   * @return the revision both sides operate at
   */
  public static long negotiate(long advertisedRevision, long serverRevision) {
    return Math.min(advertisedRevision, serverRevision);
  }
}
