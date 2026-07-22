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
package io.github.orhaugh.chord.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.orhaugh.chord.client.Endpoint;
import java.sql.SQLException;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/** URL parsing: hosts, database, parameters, property precedence and strictness. */
class JdbcUrlTest {

  @Test
  void fullUrlParses() throws SQLException {
    JdbcUrl url =
        JdbcUrl.parse(
            "jdbc:chord://primary:9001,standby/analytics?user=ross&compression=zstd", null);
    assertThat(url.endpoints())
        .containsExactly(Endpoint.of("primary", 9001), Endpoint.of("standby", 9000));
    assertThat(url.database()).isEqualTo("analytics");
    assertThat(url.parameter("user", "")).isEqualTo("ross");
    assertThat(url.parameter("compression", "")).isEqualTo("zstd");
  }

  @Test
  void sslChangesTheDefaultPort() throws SQLException {
    JdbcUrl url = JdbcUrl.parse("jdbc:chord://host?ssl=true", null);
    assertThat(url.endpoints()).containsExactly(Endpoint.of("host", 9440));
  }

  @Test
  void propertiesOverrideUrlParameters() throws SQLException {
    Properties info = new Properties();
    info.setProperty("user", "fromProps");
    JdbcUrl url = JdbcUrl.parse("jdbc:chord://host/db?user=fromUrl", info);
    assertThat(url.parameter("user", "")).isEqualTo("fromProps");
  }

  @Test
  void unknownParametersFailLoudly() {
    assertThatThrownBy(() -> JdbcUrl.parse("jdbc:chord://host?sssl=true", null))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("Unknown JDBC URL parameter");
  }

  @Test
  void malformedUrlsFail() {
    assertThatThrownBy(() -> JdbcUrl.parse("jdbc:chord://", null))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("no host");
    assertThatThrownBy(() -> JdbcUrl.parse("jdbc:chord://host:notaport", null))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("Malformed port");
    assertThatThrownBy(() -> JdbcUrl.parse("jdbc:mysql://host", null))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("Not a CHord JDBC URL");
  }

  @Test
  void driverAcceptsOnlyItsPrefix() {
    ChordDriver driver = new ChordDriver();
    assertThat(driver.acceptsURL("jdbc:chord://host")).isTrue();
    assertThat(driver.acceptsURL("jdbc:clickhouse://host")).isFalse();
    assertThat(driver.acceptsURL(null)).isFalse();
  }
}
