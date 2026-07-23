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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.orhaugh.chord.ChordProtocolException;
import org.junit.jupiter.api.Test;

/** The packet type tables against {@code Protocol.h}: codes, resolution and client visibility. */
class PacketTypesTest {

  @Test
  void everyServerPacketTypeResolvesFromItsOwnCode() {
    for (ServerPacketType type : ServerPacketType.values()) {
      assertThat(ServerPacketType.fromCode(type.code())).isSameAs(type);
    }
  }

  @Test
  void serverCodesMatchTheProtocolHeader() {
    assertThat(ServerPacketType.HELLO.code()).isZero();
    assertThat(ServerPacketType.DATA.code()).isEqualTo(1);
    assertThat(ServerPacketType.EXCEPTION.code()).isEqualTo(2);
    assertThat(ServerPacketType.PROGRESS.code()).isEqualTo(3);
    assertThat(ServerPacketType.PONG.code()).isEqualTo(4);
    assertThat(ServerPacketType.END_OF_STREAM.code()).isEqualTo(5);
    assertThat(ServerPacketType.PROFILE_INFO.code()).isEqualTo(6);
    assertThat(ServerPacketType.TOTALS.code()).isEqualTo(7);
    assertThat(ServerPacketType.EXTREMES.code()).isEqualTo(8);
    assertThat(ServerPacketType.LOG.code()).isEqualTo(10);
    assertThat(ServerPacketType.PROFILE_EVENTS.code()).isEqualTo(14);
    assertThat(ServerPacketType.TIMEZONE_UPDATE.code()).isEqualTo(17);
    assertThat(ServerPacketType.SSH_CHALLENGE.code()).isEqualTo(18);
  }

  @Test
  void unknownServerCodesFailExplicitlyInsteadOfGuessing() {
    assertThatThrownBy(() -> ServerPacketType.fromCode(19))
        .isInstanceOf(ChordProtocolException.class)
        .hasMessageContaining("Unknown server packet type 19")
        .hasMessageContaining("desynchronised");
    assertThatThrownBy(() -> ServerPacketType.fromCode(-1))
        .isInstanceOf(ChordProtocolException.class)
        .hasMessageContaining("-1");
  }

  @Test
  void interServerCoordinationPacketsAreNotExpectedByExternalClients() {
    assertThat(ServerPacketType.PART_UUIDS.expectedByExternalClients()).isFalse();
    assertThat(ServerPacketType.READ_TASK_REQUEST.expectedByExternalClients()).isFalse();
    assertThat(ServerPacketType.MERGE_TREE_ALL_RANGES_ANNOUNCEMENT.expectedByExternalClients())
        .isFalse();
    assertThat(ServerPacketType.MERGE_TREE_READ_TASK_REQUEST.expectedByExternalClients()).isFalse();
    // Everything else a well behaved server may send to us.
    for (ServerPacketType type : ServerPacketType.values()) {
      if (type.code() < 12 || type.code() == 14 || type.code() >= 17) {
        assertThat(type.expectedByExternalClients()).as("%s", type).isTrue();
      }
    }
  }

  @Test
  void clientCodesMatchTheProtocolHeader() {
    assertThat(ClientPacketType.HELLO.code()).isZero();
    assertThat(ClientPacketType.QUERY.code()).isEqualTo(1);
    assertThat(ClientPacketType.DATA.code()).isEqualTo(2);
    assertThat(ClientPacketType.CANCEL.code()).isEqualTo(3);
    assertThat(ClientPacketType.PING.code()).isEqualTo(4);
    assertThat(ClientPacketType.TABLES_STATUS_REQUEST.code()).isEqualTo(5);
    assertThat(ClientPacketType.KEEP_ALIVE.code()).isEqualTo(6);
    assertThat(ClientPacketType.SCALAR.code()).isEqualTo(7);
    assertThat(ClientPacketType.IGNORED_PART_UUIDS.code()).isEqualTo(8);
    assertThat(ClientPacketType.READ_TASK_RESPONSE.code()).isEqualTo(9);
    assertThat(ClientPacketType.SSH_CHALLENGE_REQUEST.code()).isEqualTo(11);
    assertThat(ClientPacketType.SSH_CHALLENGE_RESPONSE.code()).isEqualTo(12);
    assertThat(ClientPacketType.QUERY_PLAN.code()).isEqualTo(13);
    // The table is dense: every code up to the highest is assigned exactly once.
    long distinct =
        java.util.Arrays.stream(ClientPacketType.values())
            .mapToLong(ClientPacketType::code)
            .distinct()
            .count();
    assertThat(distinct).isEqualTo(ClientPacketType.values().length);
  }
}
