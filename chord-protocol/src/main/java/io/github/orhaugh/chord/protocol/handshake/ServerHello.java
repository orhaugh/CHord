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

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * The decoded server half of the native handshake. Field presence depends on the negotiated
 * protocol revision; absent gated fields are represented as empty optionals or empty lists.
 *
 * <p>Field order and gating follow {@code TCPHandler::sendHello} and {@code
 * Connection::receiveHello} in the ClickHouse sources (verified 2026-07-21).
 *
 * @param serverName server product name, for example {@code ClickHouse}
 * @param versionMajor server major version
 * @param versionMinor server minor version
 * @param serverRevision protocol revision the server speaks
 * @param parallelReplicasProtocolVersion parallel replicas protocol version, from revision 54471
 * @param timezone server default timezone, from revision 54058
 * @param displayName operator configured display name, from revision 54372
 * @param versionPatch server patch version, from revision 54401; equals the revision below it
 * @param chunkedSendCapability server capability for its send channel, from revision 54470
 * @param chunkedReceiveCapability server capability for its receive channel, from revision 54470
 * @param passwordComplexityRules password rules for interactive clients, from revision 54461
 * @param interserverNonce nonce for inter-server secret v2, from revision 54462; read and retained
 *     for parity although CHord never authenticates as a cluster peer
 * @param serverSettings changed server session settings, from revision 54474
 * @param queryPlanSerializationVersion query plan serialisation version, from revision 54477
 * @param clusterFunctionProtocolVersion cluster function protocol version, from revision 54479
 */
public record ServerHello(
    String serverName,
    long versionMajor,
    long versionMinor,
    long serverRevision,
    OptionalLong parallelReplicasProtocolVersion,
    Optional<String> timezone,
    Optional<String> displayName,
    long versionPatch,
    Optional<String> chunkedSendCapability,
    Optional<String> chunkedReceiveCapability,
    List<PasswordComplexityRule> passwordComplexityRules,
    OptionalLong interserverNonce,
    List<ServerSetting> serverSettings,
    OptionalLong queryPlanSerializationVersion,
    OptionalLong clusterFunctionProtocolVersion) {

  /**
   * Validates components and defensively copies lists.
   *
   * @param serverName server product name
   * @param versionMajor server major version
   * @param versionMinor server minor version
   * @param serverRevision protocol revision the server speaks
   * @param parallelReplicasProtocolVersion parallel replicas protocol version, when present
   * @param timezone server default timezone, when present
   * @param displayName operator configured display name, when present
   * @param versionPatch server patch version
   * @param chunkedSendCapability server send channel capability, when present
   * @param chunkedReceiveCapability server receive channel capability, when present
   * @param passwordComplexityRules password rules, empty when absent
   * @param interserverNonce inter-server nonce, when present
   * @param serverSettings changed server session settings, empty when absent
   * @param queryPlanSerializationVersion query plan serialisation version, when present
   * @param clusterFunctionProtocolVersion cluster function protocol version, when present
   */
  public ServerHello {
    Objects.requireNonNull(serverName, "serverName");
    Objects.requireNonNull(parallelReplicasProtocolVersion, "parallelReplicasProtocolVersion");
    Objects.requireNonNull(timezone, "timezone");
    Objects.requireNonNull(displayName, "displayName");
    Objects.requireNonNull(chunkedSendCapability, "chunkedSendCapability");
    Objects.requireNonNull(chunkedReceiveCapability, "chunkedReceiveCapability");
    passwordComplexityRules = List.copyOf(passwordComplexityRules);
    Objects.requireNonNull(interserverNonce, "interserverNonce");
    serverSettings = List.copyOf(serverSettings);
    Objects.requireNonNull(queryPlanSerializationVersion, "queryPlanSerializationVersion");
    Objects.requireNonNull(clusterFunctionProtocolVersion, "clusterFunctionProtocolVersion");
  }

  /**
   * Returns the server version as {@code major.minor.patch}.
   *
   * @return human readable server version
   */
  public String versionString() {
    return versionMajor + "." + versionMinor + "." + versionPatch;
  }
}
