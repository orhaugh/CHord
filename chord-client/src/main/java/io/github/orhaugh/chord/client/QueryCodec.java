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

import io.github.orhaugh.chord.codec.block.BlockWriter;
import io.github.orhaugh.chord.protocol.ClientPacketType;
import io.github.orhaugh.chord.protocol.ProtocolFeature;
import io.github.orhaugh.chord.protocol.ProtocolRevisions;
import io.github.orhaugh.chord.protocol.wire.WireWriter;
import java.util.Map;

/**
 * Encodes the Query packet, mirroring {@code Connection::sendQuery} and {@code ClientInfo::write}
 * in the ClickHouse sources at revision 54488.
 *
 * <p>Wire order: query id, ClientInfo (gated at 54032), settings in the STRINGS_WITH_FLAGS format
 * terminated by an empty name (54429; older servers are below CHord's support floor), the external
 * roles string (54472), the interserver secret hash (54441, empty for ordinary clients), the
 * processing stage, the compression flag, the query text, and the parameters block (54459). The
 * query is followed by the empty Data block that terminates the external tables stream.
 */
final class QueryCodec {

  /** QueryProcessingStage::Complete. */
  private static final long STAGE_COMPLETE = 2;

  /** ClientInfo::QueryKind::INITIAL_QUERY. */
  private static final int QUERY_KIND_INITIAL = 1;

  /** ClientInfo::Interface::TCP. */
  private static final int INTERFACE_TCP = 1;

  private QueryCodec() {}

  /**
   * Writes a Query packet followed by the terminal empty external tables block.
   *
   * <p>The terminator is required for every query, INSERT included: the server's external tables
   * initializer runs inside {@code executeQuery} for all queries and consumes Data packets until an
   * empty block before the query pipeline starts. For an INSERT the streamed data then follows the
   * server's schema header and ends with its own, second, empty block. The caller remains
   * responsible for flushing.
   */
  static void writeQuery(
      WireWriter out,
      QueryRequest request,
      ConnectionOptions options,
      long negotiatedRevision,
      String osUser,
      String hostname) {
    out.writeVarUInt(ClientPacketType.QUERY.code());
    out.writeString(request.queryId());

    if (ProtocolFeature.CLIENT_INFO.enabledFor(negotiatedRevision)) {
      writeClientInfo(out, request, options, negotiatedRevision, osUser, hostname);
    }

    // Settings, STRINGS_WITH_FLAGS: name, flags VarUInt, string value; empty name terminates.
    for (Map.Entry<String, String> setting : request.settings().entrySet()) {
      out.writeString(setting.getKey());
      out.writeVarUInt(0);
      out.writeString(setting.getValue());
    }
    out.writeString("");

    if (ProtocolFeature.INTERSERVER_EXTERNALLY_GRANTED_ROLES.enabledFor(negotiatedRevision)) {
      // An empty roles vector: length prefixed vector encoding inside a length prefixed string.
      out.writeString(new byte[] {0});
    }
    if (ProtocolFeature.INTERSERVER_SECRET.enabledFor(negotiatedRevision)) {
      // Ordinary clients send an empty hash; the secret is an interserver concern.
      out.writeString("");
    }

    out.writeVarUInt(STAGE_COMPLETE);
    out.writeVarUInt(0); // compression disabled until Phase 4
    out.writeString(request.query());

    if (ProtocolFeature.PARAMETERS.enabledFor(negotiatedRevision)) {
      // Parameters travel as custom settings: name, CUSTOM flag, quoted string field dump.
      for (Map.Entry<String, String> parameter : request.parameters().entrySet()) {
        out.writeString(parameter.getKey());
        out.writeVarUInt(0x02);
        out.writeString(quoteFieldDump(parameter.getValue()));
      }
      out.writeString("");
    }

    // End of external tables: one empty Data block, required for every query kind.
    out.writeVarUInt(ClientPacketType.DATA.code());
    out.writeString(""); // external table name; empty means the main stream
    BlockWriter.writeEmpty(out, negotiatedRevision);
  }

  private static void writeClientInfo(
      WireWriter out,
      QueryRequest request,
      ConnectionOptions options,
      long negotiatedRevision,
      String osUser,
      String hostname) {
    out.writeUInt8(QUERY_KIND_INITIAL);
    out.writeString(""); // initial_user: empty, the server fills it for initial queries
    out.writeString(request.queryId()); // initial_query_id equals the query id here
    out.writeString("0.0.0.0:0"); // initial_address, server side value for initial queries
    if (ProtocolFeature.INITIAL_QUERY_START_TIME.enabledFor(negotiatedRevision)) {
      out.writeInt64Le(0); // initial_query_start_time_microseconds: 0 lets the server stamp it
    }
    out.writeUInt8(INTERFACE_TCP);
    out.writeString(osUser);
    out.writeString(hostname);
    out.writeString(options.clientName());
    out.writeVarUInt(NativeConnection.CLIENT_VERSION_MAJOR);
    out.writeVarUInt(NativeConnection.CLIENT_VERSION_MINOR);
    out.writeVarUInt(options.advertisedRevision());
    if (ProtocolFeature.QUOTA_KEY_IN_CLIENT_INFO.enabledFor(negotiatedRevision)) {
      out.writeString(options.quotaKey());
    }
    if (ProtocolFeature.DISTRIBUTED_DEPTH.enabledFor(negotiatedRevision)) {
      out.writeVarUInt(0);
    }
    if (ProtocolFeature.VERSION_PATCH.enabledFor(negotiatedRevision)) {
      out.writeVarUInt(0); // client_version_patch
    }
    if (ProtocolFeature.OPENTELEMETRY.enabledFor(negotiatedRevision)) {
      out.writeUInt8(0); // no trace context
    }
    if (ProtocolFeature.PARALLEL_REPLICAS.enabledFor(negotiatedRevision)) {
      out.writeVarUInt(0); // collaborate_with_initiator
      out.writeVarUInt(0); // obsolete_count_participating_replicas
      out.writeVarUInt(0); // number_of_current_replica
    }
    if (ProtocolFeature.QUERY_AND_LINE_NUMBERS.enabledFor(negotiatedRevision)) {
      out.writeVarUInt(0); // script_query_number
      out.writeVarUInt(0); // script_line_number
    }
    if (ProtocolFeature.JWT_IN_INTERSERVER.enabledFor(negotiatedRevision)) {
      out.writeUInt8(0); // no JWT
    }
    if (ProtocolFeature.CLIENT_AGENT_IN_CLIENT_INFO.enabledFor(negotiatedRevision)) {
      out.writeString("CHord/" + ProtocolRevisions.CURRENT);
    }
    if (ProtocolFeature.INTERNAL_QUERY_FLAG.enabledFor(negotiatedRevision)) {
      out.writeBool(false); // is_internal
    }
    if (ProtocolFeature.INTERSERVER_CURRENT_ROLES.enabledFor(negotiatedRevision)) {
      out.writeUInt8(0); // no current roles pushed
    }
  }

  /**
   * Renders a parameter value the way {@code SettingFieldCustom(Field(value)).toString()} does: a
   * single quoted SQL string literal with backslash escaping.
   */
  static String quoteFieldDump(String value) {
    StringBuilder builder = new StringBuilder(value.length() + 2).append('\'');
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      switch (c) {
        case '\\' -> builder.append("\\\\");
        case '\'' -> builder.append("\\'");
        case '\t' -> builder.append("\\t");
        case '\n' -> builder.append("\\n");
        case '\r' -> builder.append("\\r");
        case '\0' -> builder.append("\\0");
        default -> builder.append(c);
      }
    }
    return builder.append('\'').toString();
  }
}
