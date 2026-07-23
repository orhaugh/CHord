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

import static org.assertj.core.api.Assertions.assertThat;

import io.github.orhaugh.chord.protocol.wire.WireWriter;
import java.io.ByteArrayOutputStream;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * Exact byte expectations for the Query packet at revision 54488, mirroring {@code
 * Connection::sendQuery} and {@code ClientInfo::write} in the ClickHouse sources.
 */
class QueryCodecTest {

  private static byte[] written(Consumer<WireWriter> body) {
    ByteArrayOutputStream sink = new ByteArrayOutputStream();
    WireWriter writer = new WireWriter(sink);
    body.accept(writer);
    writer.flush();
    return sink.toByteArray();
  }

  @Test
  void encodesQueryPacketExactBytesAtCurrentRevision() {
    ConnectionOptions options =
        ConnectionOptions.builder().host("h").clientName("c").quotaKey("qk").build();
    QueryRequest request =
        QueryRequest.builder("SELECT 1")
            .queryId("qid")
            .setting("max_threads", 2)
            .parameter("p", "it's")
            .build();

    byte[] encoded =
        written(w -> QueryCodec.writeQuery(w, request, options, 54488, "user", "host", false));

    byte[] expected =
        written(
            w -> {
              w.writeVarUInt(1); // Client::Query
              w.writeString("qid");
              // ClientInfo at revision 54488.
              w.writeUInt8(1); // query_kind INITIAL_QUERY
              w.writeString(""); // initial_user
              w.writeString("qid"); // initial_query_id
              w.writeString("0.0.0.0:0"); // initial_address
              w.writeInt64Le(0); // initial_query_start_time_microseconds
              w.writeUInt8(1); // interface TCP
              w.writeString("user"); // os_user
              w.writeString("host"); // client_hostname
              w.writeString("c"); // client_name
              w.writeVarUInt(NativeConnection.CLIENT_VERSION_MAJOR);
              w.writeVarUInt(NativeConnection.CLIENT_VERSION_MINOR);
              w.writeVarUInt(54488); // client_tcp_protocol_version
              w.writeString("qk"); // quota_key
              w.writeVarUInt(0); // distributed_depth
              w.writeVarUInt(0); // client_version_patch
              w.writeUInt8(0); // no OpenTelemetry context
              w.writeVarUInt(0); // collaborate_with_initiator
              w.writeVarUInt(0); // obsolete_count_participating_replicas
              w.writeVarUInt(0); // number_of_current_replica
              w.writeVarUInt(0); // script_query_number
              w.writeVarUInt(0); // script_line_number
              w.writeUInt8(0); // no JWT
              w.writeString("CHord/54488"); // client_agent
              w.writeBool(false); // is_internal
              w.writeUInt8(0); // no current roles
              // Settings, STRINGS_WITH_FLAGS.
              w.writeString("max_threads");
              w.writeVarUInt(0);
              w.writeString("2");
              w.writeString(""); // end of settings
              w.writeString(new byte[] {0}); // empty external roles vector
              w.writeString(""); // empty interserver secret hash
              w.writeVarUInt(2); // stage Complete
              w.writeVarUInt(0); // compression disabled
              w.writeString("SELECT 1");
              // Parameters as custom settings.
              w.writeString("p");
              w.writeVarUInt(0x02);
              w.writeString("'it\\'s'");
              w.writeString(""); // end of parameters
              // The external tables terminator is a separate packet owned by the connection.
            });

    assertThat(encoded).isEqualTo(expected);
  }

  @Test
  void omitsRevisionGatedFieldsForOlderServers() {
    ConnectionOptions options = ConnectionOptions.builder().host("h").clientName("c").build();
    QueryRequest request = QueryRequest.builder("SELECT 1").queryId("q").build();

    // At revision 54470: no client agent (54485), no internal flag (54486), no roles (54488),
    // no external roles string (54472), no query and line numbers (54475), no JWT flag (54476).
    byte[] encoded =
        written(w -> QueryCodec.writeQuery(w, request, options, 54470, "u", "h", false));

    byte[] expected =
        written(
            w -> {
              w.writeVarUInt(1);
              w.writeString("q");
              w.writeUInt8(1);
              w.writeString("");
              w.writeString("q");
              w.writeString("0.0.0.0:0");
              w.writeInt64Le(0);
              w.writeUInt8(1);
              w.writeString("u");
              w.writeString("h");
              w.writeString("c");
              w.writeVarUInt(NativeConnection.CLIENT_VERSION_MAJOR);
              w.writeVarUInt(NativeConnection.CLIENT_VERSION_MINOR);
              w.writeVarUInt(54488); // advertised revision, not the negotiated one
              w.writeString(""); // quota_key
              w.writeVarUInt(0); // distributed_depth
              w.writeVarUInt(0); // client_version_patch
              w.writeUInt8(0); // no OpenTelemetry
              w.writeVarUInt(0);
              w.writeVarUInt(0);
              w.writeVarUInt(0); // parallel replicas triple
              w.writeString(""); // end of settings
              w.writeString(""); // interserver hash (54441)
              w.writeVarUInt(2);
              w.writeVarUInt(0);
              w.writeString("SELECT 1");
              w.writeString(""); // end of parameters (54459)
            });

    assertThat(encoded).isEqualTo(expected);
  }

  @Test
  void quotesParameterValuesLikeFieldDump() {
    assertThat(QueryCodec.quoteFieldDump("plain")).isEqualTo("'plain'");
    assertThat(QueryCodec.quoteFieldDump("it's")).isEqualTo("'it\\'s'");
    assertThat(QueryCodec.quoteFieldDump("a\\b")).isEqualTo("'a\\\\b'");
    assertThat(QueryCodec.quoteFieldDump("line\nbreak")).isEqualTo("'line\\nbreak'");
  }

  @org.junit.jupiter.api.Test
  void traceContextsTravelInsideClientInfo() {
    ConnectionOptions options = ConnectionOptions.builder().host("h").build();
    QueryRequest.TraceContext context =
        QueryRequest.TraceContext.parse(
            "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01", "vendor=x");
    assertThat(context.traceIdHigh()).isEqualTo(0x0af7651916cd43ddL);
    assertThat(context.traceIdLow()).isEqualTo(0x8448eb211c80319cL);
    assertThat(context.spanId()).isEqualTo(0xb7ad6b7169203331L);
    assertThat(context.traceFlags()).isEqualTo((byte) 1);

    QueryRequest with =
        QueryRequest.builder("SELECT 1")
            .queryId("qid")
            .traceContext("00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01", "vendor=x")
            .build();
    QueryRequest without = QueryRequest.builder("SELECT 1").queryId("qid").build();

    byte[] encodedWith =
        written(w -> QueryCodec.writeQuery(w, with, options, 54488, "u", "h", false));
    byte[] encodedWithout =
        written(w -> QueryCodec.writeQuery(w, without, options, 54488, "u", "h", false));

    byte[] traceSection =
        written(
            w -> {
              w.writeUInt8(1);
              w.writeInt64Le(0x0af7651916cd43ddL);
              w.writeInt64Le(0x8448eb211c80319cL);
              w.writeInt64Le(0xb7ad6b7169203331L);
              w.writeString("vendor=x");
              w.writeUInt8(1);
            });
    assertThat(indexOf(encodedWith, traceSection)).isNotNegative();
    assertThat(indexOf(encodedWithout, traceSection)).isNegative();
    assertThat(encodedWith.length).isEqualTo(encodedWithout.length + traceSection.length - 1);

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> QueryRequest.TraceContext.parse("not-a-traceparent", ""))
        .isInstanceOf(io.github.orhaugh.chord.ChordConfigurationException.class)
        .hasMessageContaining("Malformed traceparent");
  }

  private static int indexOf(byte[] haystack, byte[] needle) {
    outer:
    for (int i = 0; i <= haystack.length - needle.length; i++) {
      for (int j = 0; j < needle.length; j++) {
        if (haystack[i + j] != needle[j]) {
          continue outer;
        }
      }
      return i;
    }
    return -1;
  }
}
