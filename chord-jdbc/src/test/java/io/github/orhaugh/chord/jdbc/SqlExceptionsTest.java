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

import io.github.orhaugh.chord.ChordAuthenticationException;
import io.github.orhaugh.chord.ChordConfigurationException;
import io.github.orhaugh.chord.ChordServerException;
import io.github.orhaugh.chord.ChordTimeoutException;
import io.github.orhaugh.chord.ChordTransportException;
import io.github.orhaugh.chord.RetryClass;
import java.sql.SQLException;
import java.sql.SQLInvalidAuthorizationSpecException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLNonTransientException;
import java.sql.SQLSyntaxErrorException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransientConnectionException;
import java.sql.SQLTransientException;
import org.junit.jupiter.api.Test;

/**
 * The full exception mapping matrix: SQLStates, vendor codes and the retry classification carrying
 * into JDBC's transient versus non transient taxonomy.
 */
class SqlExceptionsTest {

  private static ChordServerException server(int code) {
    return new ChordServerException(code, "DB::Exception", "boom", "", null);
  }

  @Test
  void serverCodesMapToTheirSqlStates() {
    SQLException syntax = SqlExceptions.map(server(62));
    assertThat((Throwable) syntax).isInstanceOf(SQLSyntaxErrorException.class);
    assertThat(syntax.getSQLState()).isEqualTo("42000");
    assertThat(syntax.getErrorCode()).isEqualTo(62);

    assertThat(SqlExceptions.map(server(60)).getSQLState()).isEqualTo("42S02"); // UNKNOWN_TABLE
    assertThat(SqlExceptions.map(server(81)).getSQLState()).isEqualTo("42S02"); // UNKNOWN_DATABASE
    assertThat(SqlExceptions.map(server(516)).getSQLState()).isEqualTo("28000");
    assertThat(SqlExceptions.map(server(497)).getSQLState()).isEqualTo("42501"); // ACCESS_DENIED
    assertThat(SqlExceptions.map(server(241)).getSQLState()).isEqualTo("53200"); // MEMORY_LIMIT
    assertThat(SqlExceptions.map(server(159)).getSQLState()).isEqualTo("57014"); // TIMEOUT
    assertThat(SqlExceptions.map(server(999)).getSQLState()).isEqualTo("HY000");
  }

  @Test
  void retryClassificationCarriesIntoTheTransientHierarchy() {
    // Admission control rejections are safe to retry: transient.
    assertThat((Throwable) SqlExceptions.map(server(202)))
        .isInstanceOf(SQLTransientException.class);
    // Transient resource conditions: transient.
    assertThat((Throwable) SqlExceptions.map(server(241)))
        .isInstanceOf(SQLTransientException.class);
    // Deterministic errors: non transient.
    assertThat((Throwable) SqlExceptions.map(server(81)))
        .isInstanceOf(SQLNonTransientException.class);
    // UNKNOWN_STATUS_OF_INSERT: never blind retry, non transient.
    assertThat((Throwable) SqlExceptions.map(server(319)))
        .isInstanceOf(SQLNonTransientException.class);
  }

  @Test
  void transportFailuresSplitOnTheirClassification() {
    ChordTransportException safe = new ChordTransportException("refused during connect");
    safe.classifiedAs(RetryClass.SAFE_TO_RETRY);
    SQLException transientMapped = SqlExceptions.map(safe);
    assertThat((Throwable) transientMapped).isInstanceOf(SQLTransientConnectionException.class);
    assertThat(transientMapped.getSQLState()).isEqualTo("08000");

    ChordTransportException unknown = new ChordTransportException("lost mid exchange");
    SQLException nonTransient = SqlExceptions.map(unknown);
    assertThat((Throwable) nonTransient).isInstanceOf(SQLNonTransientConnectionException.class);
    assertThat(nonTransient.getSQLState()).isEqualTo("08000");
  }

  @Test
  void authenticationTimeoutAndConfigurationMapDirectly() {
    SQLException auth =
        SqlExceptions.map(new ChordAuthenticationException("denied", 516, "DB::Exception"));
    assertThat((Throwable) auth).isInstanceOf(SQLInvalidAuthorizationSpecException.class);
    assertThat(auth.getSQLState()).isEqualTo("28000");
    assertThat(auth.getErrorCode()).isEqualTo(516);

    SQLException timeout = SqlExceptions.map(new ChordTimeoutException("deadline"));
    assertThat((Throwable) timeout).isInstanceOf(SQLTimeoutException.class);
    assertThat(timeout.getSQLState()).isEqualTo("57014");

    SQLException config = SqlExceptions.map(new ChordConfigurationException("bad option"));
    assertThat((Throwable) config).isInstanceOf(SQLNonTransientException.class);
    assertThat(config.getSQLState()).isEqualTo("42000");
  }

  @Test
  void helperStatesAreStable() {
    assertThat(SqlExceptions.unsupported("Anything").getSQLState()).isEqualTo("0A000");
    assertThat(SqlExceptions.closed("statement").getSQLState()).isEqualTo("08003");
    assertThat((Throwable) SqlExceptions.closed("statement"))
        .hasMessageContaining("statement is closed");
  }
}
