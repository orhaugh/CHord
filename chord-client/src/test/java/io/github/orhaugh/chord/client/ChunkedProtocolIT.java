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

import io.github.orhaugh.chord.codec.block.Block;
import io.github.orhaugh.chord.codec.column.BlockBuilder;
import io.github.orhaugh.chord.codec.column.Columns;
import io.github.orhaugh.chord.codec.compress.Compression;
import io.github.orhaugh.chord.protocol.state.ConnectionState;
import io.github.orhaugh.chord.testkit.ClickHouseServerContainer;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The full protocol against a server that strictly requires chunked packet framing, the non default
 * {@code proto_caps} configuration CHord previously refused.
 */
@Testcontainers
class ChunkedProtocolIT {

  @Container
  private static final ClickHouseServerContainer CLICKHOUSE =
      new ClickHouseServerContainer().withStrictChunkedProtocol();

  private static NativeConnection connect(Compression compression) {
    ConnectionOptions.Builder builder =
        ConnectionOptions.builder()
            .host(CLICKHOUSE.getHost())
            .port(CLICKHOUSE.nativePort())
            .database(CLICKHOUSE.database())
            .username(CLICKHOUSE.username())
            .password(CLICKHOUSE.password())
            .allowPlaintextPassword(true);
    if (compression != null) {
      builder.compression(compression);
    }
    return NativeConnection.open(builder.build());
  }

  @Test
  void handshakesAndPingsOverChunkedFraming() {
    try (NativeConnection connection = connect(null)) {
      assertThat(connection.serverHello().chunkedSendCapability()).contains("chunked");
      for (int i = 0; i < 3; i++) {
        connection.ping();
      }
      assertThat(connection.state()).isEqualTo(ConnectionState.READY);
    }
  }

  @Test
  void selectsAndInsertsOverChunkedFraming() {
    try (NativeConnection connection = connect(null)) {
      try (QueryResult result =
          connection.query(QueryRequest.of("CREATE TABLE chunked_t (n UInt64) ENGINE = Memory"))) {
        result.nextBlock();
      }
      try (InsertStream insert =
          connection.insert(QueryRequest.of("INSERT INTO chunked_t VALUES"))) {
        BlockBuilder builder = insert.newBlock();
        for (long row = 0; row < 10_000; row++) {
          builder.addRow(row);
        }
        insert.send(builder.build());
        insert.finish();
      }
      try (QueryResult result =
          connection.query(QueryRequest.of("SELECT count(), sum(n) FROM chunked_t"))) {
        Block block = result.nextBlock().orElseThrow();
        assertThat(((Columns.UInt64Column) block.column(0)).rawLongAt(0)).isEqualTo(10_000);
        assertThat(((Columns.UInt64Column) block.column(1)).rawLongAt(0))
            .isEqualTo(9_999L * 10_000 / 2);
      }
    }
  }

  @Test
  void compressionAndChunkedFramingCompose() {
    try (NativeConnection connection = connect(Compression.ZSTD);
        QueryResult result =
            connection.query(
                QueryRequest.builder("SELECT number FROM system.numbers LIMIT 100000")
                    .setting("max_block_size", 16384)
                    .build())) {
      long expected = 0;
      Optional<Block> next;
      while ((next = result.nextBlock()).isPresent()) {
        Block block = next.orElseThrow();
        Columns.UInt64Column numbers = (Columns.UInt64Column) block.column(0);
        for (int i = 0; i < block.rows(); i++) {
          if (numbers.rawLongAt(i) != expected) {
            throw new AssertionError("row " + expected + " corrupted");
          }
          expected++;
        }
      }
      assertThat(expected).isEqualTo(100_000);
      assertThat(connection.state()).isEqualTo(ConnectionState.READY);
    }
  }
}
