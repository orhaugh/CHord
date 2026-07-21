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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.orhaugh.chord.ChordAuthenticationException;
import io.github.orhaugh.chord.ChordServerException;
import io.github.orhaugh.chord.protocol.ServerErrorCodes;
import io.github.orhaugh.chord.testkit.ClickHouseServerContainer;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Server side rejections during the handshake surface as typed exceptions. */
@Testcontainers
class AuthenticationFailureIT {

  @Container
  private static final ClickHouseServerContainer CLICKHOUSE = new ClickHouseServerContainer();

  private static ConnectionOptions.Builder options() {
    return ConnectionOptions.builder()
        .host(CLICKHOUSE.getHost())
        .port(CLICKHOUSE.nativePort())
        .username(CLICKHOUSE.username())
        .allowPlaintextPassword(true);
  }

  @Test
  void wrongPasswordRaisesAuthenticationException() {
    ConnectionOptions wrongPassword = options().password("definitely-wrong").build();

    assertThatThrownBy(() -> NativeConnection.open(wrongPassword))
        .isInstanceOf(ChordAuthenticationException.class)
        .satisfies(
            e -> {
              ChordAuthenticationException auth = (ChordAuthenticationException) e;
              assertThat(auth.code()).isEqualTo(ServerErrorCodes.AUTHENTICATION_FAILED);
              assertThat(auth.getMessage()).doesNotContain("definitely-wrong");
            });
  }

  @Test
  void unknownDatabaseRaisesServerException() {
    ConnectionOptions unknownDatabase =
        options().password(CLICKHOUSE.password()).database("no_such_database_here").build();

    assertThatThrownBy(() -> NativeConnection.open(unknownDatabase))
        .isInstanceOf(ChordServerException.class)
        .satisfies(
            e ->
                assertThat(((ChordServerException) e).code())
                    .isEqualTo(ServerErrorCodes.UNKNOWN_DATABASE));
  }
}
