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
import io.github.orhaugh.chord.protocol.ClientPacketType;
import io.github.orhaugh.chord.protocol.ProtocolFeature;
import io.github.orhaugh.chord.protocol.ProtocolRevisions;
import io.github.orhaugh.chord.protocol.ServerError;
import io.github.orhaugh.chord.protocol.ServerPacketType;
import io.github.orhaugh.chord.protocol.wire.WireReader;
import io.github.orhaugh.chord.protocol.wire.WireWriter;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Encoder and decoder for the native handshake: ClientHello, ServerHello and the client addendum.
 *
 * <p>Field order and revision gating mirror {@code Connection::sendHello}, {@code
 * Connection::receiveHello}, {@code Connection::sendAddendum} and {@code TCPHandler::sendHello} in
 * the ClickHouse sources (verified against master, 2026-07-21). The server emits a gated field only
 * when the revision the client advertised enables it, and can only do so when its own revision
 * knows the field, so every gate is evaluated against the negotiated revision: the minimum of both.
 */
public final class HelloCodec {

  private HelloCodec() {}

  /**
   * Writes a ClientHello packet. The caller remains responsible for flushing the writer and for
   * wiping the password array after the connection attempt completes.
   *
   * @param out writer to encode into
   * @param hello handshake fields
   * @param password password characters; encoded as UTF-8 and wiped from temporary buffers, and
   *     validated against the same control character rule the server applies
   */
  public static void writeClientHello(WireWriter out, ClientHello hello, char[] password) {
    ClientHello.requireNoControlCharacters(CharBuffer.wrap(password), "password");
    out.writeVarUInt(ClientPacketType.HELLO.code());
    out.writeString(hello.clientName());
    out.writeVarUInt(hello.versionMajor());
    out.writeVarUInt(hello.versionMinor());
    out.writeVarUInt(hello.protocolRevision());
    out.writeString(hello.database());
    out.writeString(hello.username());
    writePassword(out, password);
  }

  private static void writePassword(WireWriter out, char[] password) {
    ByteBuffer encoded = StandardCharsets.UTF_8.encode(CharBuffer.wrap(password));
    byte[] bytes = new byte[encoded.remaining()];
    encoded.get(bytes);
    if (encoded.hasArray()) {
      Arrays.fill(encoded.array(), (byte) 0);
    }
    try {
      out.writeString(bytes);
    } finally {
      Arrays.fill(bytes, (byte) 0);
    }
  }

  /**
   * Reads the server response to ClientHello: a ServerHello packet, or an Exception packet that is
   * decoded and thrown, typically as an authentication failure.
   *
   * @param in reader positioned at the first byte of the server response
   * @param advertisedRevision the revision the client sent in ClientHello
   * @return the decoded server hello
   */
  public static ServerHello readServerHello(WireReader in, long advertisedRevision) {
    long packetCode = in.readVarUInt();
    if (packetCode == ServerPacketType.EXCEPTION.code()) {
      throw ServerError.read(in).toException();
    }
    if (packetCode != ServerPacketType.HELLO.code()) {
      throw new ChordProtocolException(
          "Expected Hello or Exception during the handshake but received server packet type "
              + Long.toUnsignedString(packetCode));
    }

    String serverName = in.readString(ProtocolRevisions.MAX_HELLO_STRING_BYTES);
    long versionMajor = in.readVarUInt();
    long versionMinor = in.readVarUInt();
    long serverRevision = in.readVarUInt();
    if (Long.compareUnsigned(serverRevision, ProtocolRevisions.MIN_SUPPORTED_SERVER_REVISION) < 0) {
      throw new ChordProtocolException(
          "Server protocol revision "
              + Long.toUnsignedString(serverRevision)
              + " is older than the minimum this client supports ("
              + ProtocolRevisions.MIN_SUPPORTED_SERVER_REVISION
              + "); refusing the connection instead of guessing at an untested handshake shape");
    }
    long negotiated = ProtocolRevisions.negotiate(advertisedRevision, serverRevision);

    OptionalLong parallelReplicasProtocolVersion = OptionalLong.empty();
    if (ProtocolFeature.VERSIONED_PARALLEL_REPLICAS_PROTOCOL.enabledFor(negotiated)) {
      parallelReplicasProtocolVersion = OptionalLong.of(in.readVarUInt());
    }
    Optional<String> timezone = Optional.empty();
    if (ProtocolFeature.SERVER_TIMEZONE.enabledFor(negotiated)) {
      timezone = Optional.of(in.readString(ProtocolRevisions.MAX_HELLO_STRING_BYTES));
    }
    Optional<String> displayName = Optional.empty();
    if (ProtocolFeature.SERVER_DISPLAY_NAME.enabledFor(negotiated)) {
      displayName = Optional.of(in.readString(ProtocolRevisions.MAX_HELLO_STRING_BYTES));
    }
    long versionPatch = serverRevision;
    if (ProtocolFeature.VERSION_PATCH.enabledFor(negotiated)) {
      versionPatch = in.readVarUInt();
    }
    Optional<String> chunkedSend = Optional.empty();
    Optional<String> chunkedReceive = Optional.empty();
    if (ProtocolFeature.CHUNKED_PACKETS.enabledFor(negotiated)) {
      chunkedSend = Optional.of(in.readString(ProtocolRevisions.MAX_HELLO_STRING_BYTES));
      chunkedReceive = Optional.of(in.readString(ProtocolRevisions.MAX_HELLO_STRING_BYTES));
    }
    List<PasswordComplexityRule> rules = List.of();
    if (ProtocolFeature.PASSWORD_COMPLEXITY_RULES.enabledFor(negotiated)) {
      rules = readPasswordComplexityRules(in);
    }
    OptionalLong interserverNonce = OptionalLong.empty();
    if (ProtocolFeature.INTERSERVER_SECRET_V2.enabledFor(negotiated)) {
      interserverNonce = OptionalLong.of(in.readInt64Le());
    }
    List<ServerSetting> serverSettings = List.of();
    if (ProtocolFeature.SERVER_SETTINGS_IN_HELLO.enabledFor(negotiated)) {
      serverSettings = readServerSettings(in);
    }
    OptionalLong queryPlanVersion = OptionalLong.empty();
    if (ProtocolFeature.QUERY_PLAN_SERIALIZATION.enabledFor(negotiated)) {
      queryPlanVersion = OptionalLong.of(in.readVarUInt());
    }
    OptionalLong clusterFunctionVersion = OptionalLong.empty();
    if (ProtocolFeature.VERSIONED_CLUSTER_FUNCTION_PROTOCOL.enabledFor(negotiated)) {
      clusterFunctionVersion = OptionalLong.of(in.readVarUInt());
    }

    return new ServerHello(
        serverName,
        versionMajor,
        versionMinor,
        serverRevision,
        parallelReplicasProtocolVersion,
        timezone,
        displayName,
        versionPatch,
        chunkedSend,
        chunkedReceive,
        rules,
        interserverNonce,
        serverSettings,
        queryPlanVersion,
        clusterFunctionVersion);
  }

  private static List<PasswordComplexityRule> readPasswordComplexityRules(WireReader in) {
    long declared = in.readVarUInt();
    if (Long.compareUnsigned(declared, ProtocolRevisions.MAX_PASSWORD_COMPLEXITY_RULES) > 0) {
      throw new ChordProtocolException(
          "Server declared "
              + Long.toUnsignedString(declared)
              + " password complexity rules, maximum allowed is "
              + ProtocolRevisions.MAX_PASSWORD_COMPLEXITY_RULES);
    }
    int count = (int) declared;
    List<PasswordComplexityRule> rules = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      String pattern = in.readString(ProtocolRevisions.MAX_HELLO_STRING_BYTES);
      String message = in.readString(ProtocolRevisions.MAX_HELLO_STRING_BYTES);
      rules.add(new PasswordComplexityRule(pattern, message));
    }
    return rules;
  }

  private static List<ServerSetting> readServerSettings(WireReader in) {
    List<ServerSetting> settings = new ArrayList<>();
    int max = in.limits().maxServerHelloSettings();
    while (true) {
      String name = in.readString(ProtocolRevisions.MAX_HELLO_STRING_BYTES);
      if (name.isEmpty()) {
        return settings;
      }
      if (settings.size() == max) {
        throw new ChordProtocolException(
            "Server Hello settings block exceeds the permitted maximum of " + max + " entries");
      }
      long flags = in.readVarUInt();
      String value = in.readString();
      settings.add(new ServerSetting(name, flags, value));
    }
  }

  /**
   * Writes the client addendum that follows ServerHello, carrying the quota key, the resolved
   * chunked framing choice per channel and the parallel replicas protocol version, each gated on
   * the negotiated revision. The caller must only invoke this when {@link ProtocolFeature#ADDENDUM}
   * is enabled for the negotiated revision and remains responsible for flushing.
   *
   * @param out writer to encode into
   * @param negotiatedRevision negotiated revision of the connection
   * @param quotaKey quota key, empty for none
   * @param resolvedSend resolved strict framing mode for the client send channel
   * @param resolvedReceive resolved strict framing mode for the client receive channel
   */
  public static void writeAddendum(
      WireWriter out,
      long negotiatedRevision,
      String quotaKey,
      ChunkedProtocolMode resolvedSend,
      ChunkedProtocolMode resolvedReceive) {
    if (!ProtocolFeature.ADDENDUM.enabledFor(negotiatedRevision)) {
      throw new ChordProtocolException(
          "The client addendum requires protocol revision "
              + ProtocolFeature.ADDENDUM.minRevision()
              + " or newer, negotiated revision was "
              + negotiatedRevision);
    }
    if (resolvedSend.isOptional() || resolvedReceive.isOptional()) {
      throw new ChordProtocolException(
          "The addendum must carry resolved framing modes, not optional preferences");
    }
    if (ProtocolFeature.QUOTA_KEY.enabledFor(negotiatedRevision)) {
      out.writeString(quotaKey);
    }
    if (ProtocolFeature.CHUNKED_PACKETS.enabledFor(negotiatedRevision)) {
      out.writeString(resolvedSend.wireValue());
      out.writeString(resolvedReceive.wireValue());
    }
    if (ProtocolFeature.VERSIONED_PARALLEL_REPLICAS_PROTOCOL.enabledFor(negotiatedRevision)) {
      out.writeVarUInt(ProtocolRevisions.PARALLEL_REPLICAS_PROTOCOL_VERSION);
    }
  }
}
