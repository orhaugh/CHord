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

import io.github.orhaugh.chord.codec.block.BlockWriter;
import io.github.orhaugh.chord.codec.column.BlockBuilder;
import io.github.orhaugh.chord.codec.type.TypeParser;
import io.github.orhaugh.chord.protocol.wire.WireWriter;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The JDK Flight Recorder events actually commit with their documented names, fields and outcome
 * strings. Exchanges run over scripted transports and a loopback pool so the recording is
 * deterministic.
 */
class JfrEventsTest {

  @TempDir Path tempDir;

  private static final long SERVER_REVISION = 54488;

  private static byte[] script(Consumer<WireWriter> actions) {
    ByteArrayOutputStream sink = new ByteArrayOutputStream();
    WireWriter writer = new WireWriter(sink);
    actions.accept(writer);
    writer.flush();
    return sink.toByteArray();
  }

  private static void writeServerHello(WireWriter w) {
    w.writeVarUInt(0);
    w.writeString("ClickHouse");
    w.writeVarUInt(26);
    w.writeVarUInt(7);
    w.writeVarUInt(SERVER_REVISION);
    w.writeVarUInt(8);
    w.writeString("UTC");
    w.writeString("jfr-test");
    w.writeVarUInt(1);
    w.writeString("notchunked_optional");
    w.writeString("notchunked_optional");
    w.writeVarUInt(0);
    w.writeInt64Le(7);
    w.writeString("");
    w.writeVarUInt(3);
    w.writeVarUInt(8);
  }

  private static void writeDataPacket(WireWriter w, long... values) {
    BlockBuilder builder =
        BlockBuilder.forColumnTypes(List.of(TypeParser.parse("UInt64", 100, 8)), List.of("n"));
    for (long value : values) {
      builder.addRow(java.math.BigInteger.valueOf(value));
    }
    w.writeVarUInt(1);
    w.writeString("");
    BlockWriter.write(w, builder.build(), SERVER_REVISION);
  }

  private static ConnectionOptions options() {
    return ConnectionOptions.builder().host("scripted").build();
  }

  @Test
  void everyEventTypeCommitsWithItsOutcome() throws Exception {
    Path dump = tempDir.resolve("chord.jfr");
    try (Recording recording = new Recording()) {
      recording.enable("io.github.orhaugh.chord.Connect");
      recording.enable("io.github.orhaugh.chord.Query");
      recording.enable("io.github.orhaugh.chord.Insert");
      recording.enable("io.github.orhaugh.chord.PoolAcquire");
      recording.start();

      // A finished query over a scripted transport.
      ScriptedTransport queryTransport =
          new ScriptedTransport(
              script(
                  w -> {
                    writeServerHello(w);
                    writeDataPacket(w); // schema header
                    writeDataPacket(w, 1, 2, 3);
                    w.writeVarUInt(5); // EndOfStream
                  }));
      try (NativeConnection connection = NativeConnection.open(options(), queryTransport)) {
        try (QueryResult result = connection.query(QueryRequest.of("SELECT n FROM t"))) {
          while (result.nextBlock().isPresent()) {
            // Drain to conclusion so the outcome is "finished".
          }
        }
      }

      // A committed insert, then an aborted one, each over scripted transports.
      ScriptedTransport insertTransport =
          new ScriptedTransport(
              script(
                  w -> {
                    writeServerHello(w);
                    writeDataPacket(w); // insert schema
                    w.writeVarUInt(5); // EndOfStream concluding the insert
                  }));
      try (NativeConnection connection = NativeConnection.open(options(), insertTransport)) {
        InsertStream insert = connection.insert(QueryRequest.of("INSERT INTO t VALUES"));
        BlockBuilder builder = insert.newBlock();
        builder.addRow(java.math.BigInteger.valueOf(9));
        insert.send(builder.build());
        insert.finish();
        insert.close();
      }
      ScriptedTransport abortTransport =
          new ScriptedTransport(
              script(
                  w -> {
                    writeServerHello(w);
                    writeDataPacket(w);
                  }));
      NativeConnection abortConnection = NativeConnection.open(options(), abortTransport);
      InsertStream abandoned = abortConnection.insert(QueryRequest.of("INSERT INTO t VALUES"));
      abandoned.close(); // no finish: the hard abort path

      // A pool acquire against the loopback server.
      try (LoopbackPingServer server = new LoopbackPingServer();
          ConnectionPool pool = ConnectionPool.builder(server.options()).maxSize(1).build()) {
        try (PooledConnection lease = pool.acquire()) {
          lease.ping();
        }
      }

      recording.stop();
      recording.dump(dump);
    }

    Map<String, List<RecordedEvent>> byName =
        RecordingFile.readAllEvents(dump).stream()
            .collect(Collectors.groupingBy(event -> event.getEventType().getName()));

    List<RecordedEvent> connects = byName.get("io.github.orhaugh.chord.Connect");
    assertThat(connects).isNotNull();
    assertThat(connects).allSatisfy(event -> assertThat(event.getBoolean("succeeded")).isTrue());
    assertThat(connects.get(0).getLong("negotiatedRevision")).isEqualTo(SERVER_REVISION);

    List<RecordedEvent> queries = byName.get("io.github.orhaugh.chord.Query");
    assertThat(queries).isNotNull().hasSize(1);
    assertThat(queries.get(0).getString("outcome")).isEqualTo("finished");
    assertThat(queries.get(0).getLong("rowsRead")).isZero(); // scripted server sends no Progress
    assertThat(queries.get(0).getString("queryId")).isNotBlank();

    List<RecordedEvent> inserts = byName.get("io.github.orhaugh.chord.Insert");
    assertThat(inserts).isNotNull().hasSize(2);
    Map<String, Long> insertOutcomes =
        inserts.stream()
            .collect(
                Collectors.groupingBy(event -> event.getString("outcome"), Collectors.counting()));
    assertThat(insertOutcomes).containsEntry("committed", 1L).containsEntry("aborted", 1L);
    assertThat(
            inserts.stream()
                .filter(event -> event.getString("outcome").equals("committed"))
                .findFirst()
                .orElseThrow()
                .getLong("rowsSent"))
        .isEqualTo(1);

    List<RecordedEvent> acquires = byName.get("io.github.orhaugh.chord.PoolAcquire");
    assertThat(acquires).isNotNull().hasSize(1);
    assertThat(acquires.get(0).getBoolean("succeeded")).isTrue();
    assertThat(acquires.get(0).getBoolean("openedNewConnection")).isTrue();
  }
}
