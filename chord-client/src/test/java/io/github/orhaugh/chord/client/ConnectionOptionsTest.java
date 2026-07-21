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

import io.github.orhaugh.chord.ChordConfigurationException;
import io.github.orhaugh.chord.protocol.ProtocolRevisions;
import org.junit.jupiter.api.Test;

/** Validation and secret handling of {@link ConnectionOptions}. */
class ConnectionOptionsTest {

  @Test
  void appliesDefaults() {
    ConnectionOptions options = ConnectionOptions.builder().host("ch.example").build();
    assertThat(options.port()).isEqualTo(9000);
    assertThat(options.username()).isEqualTo("default");
    assertThat(options.database()).isEmpty();
    assertThat(options.hasPassword()).isFalse();
    assertThat(options.advertisedRevision()).isEqualTo(ProtocolRevisions.CURRENT);
    assertThat(options.allowPlaintextPassword()).isFalse();
  }

  @Test
  void requiresHost() {
    assertThatThrownBy(() -> ConnectionOptions.builder().build())
        .isInstanceOf(ChordConfigurationException.class)
        .hasMessageContaining("host");
  }

  @Test
  void validatesPortRange() {
    assertThatThrownBy(() -> ConnectionOptions.builder().host("h").port(0).build())
        .isInstanceOf(ChordConfigurationException.class)
        .hasMessageContaining("port");
    assertThatThrownBy(() -> ConnectionOptions.builder().host("h").port(70000).build())
        .isInstanceOf(ChordConfigurationException.class)
        .hasMessageContaining("port");
  }

  @Test
  void boundsTheAdvertisedRevision() {
    assertThatThrownBy(
            () -> ConnectionOptions.builder().host("h").advertisedRevision(54457).build())
        .isInstanceOf(ChordConfigurationException.class)
        .hasMessageContaining("advertisedRevision");
    assertThatThrownBy(
            () ->
                ConnectionOptions.builder()
                    .host("h")
                    .advertisedRevision(ProtocolRevisions.CURRENT + 1)
                    .build())
        .isInstanceOf(ChordConfigurationException.class)
        .hasMessageContaining("advertisedRevision");
  }

  @Test
  void neverExposesThePassword() {
    ConnectionOptions options =
        ConnectionOptions.builder().host("h").password("hunter2".toCharArray()).build();
    assertThat(options.toString()).doesNotContain("hunter2").contains("<redacted>");
  }

  @Test
  void copiesPasswordDefensively() {
    char[] secret = "hunter2".toCharArray();
    ConnectionOptions options = ConnectionOptions.builder().host("h").password(secret).build();
    secret[0] = 'X';
    assertThat(options.passwordChars()).isEqualTo("hunter2".toCharArray());

    char[] exposed = options.passwordChars();
    exposed[0] = 'X';
    assertThat(options.passwordChars()).isEqualTo("hunter2".toCharArray());
  }
}
