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

import io.github.orhaugh.chord.testkit.ClickHouseServerContainer;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLInvalidAuthorizationSpecException;
import java.sql.SQLSyntaxErrorException;
import java.sql.SQLTimeoutException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** The JDBC adapter end to end through {@link DriverManager} against a real server. */
@Testcontainers
@Timeout(180)
class JdbcIT {

  @Container
  private static final ClickHouseServerContainer CLICKHOUSE = new ClickHouseServerContainer();

  private static Connection connect() throws SQLException {
    String url =
        "jdbc:chord://"
            + CLICKHOUSE.getHost()
            + ":"
            + CLICKHOUSE.nativePort()
            + "/"
            + CLICKHOUSE.database()
            + "?allow_plaintext_password=true";
    Properties info = new Properties();
    info.setProperty("user", CLICKHOUSE.username());
    info.setProperty("password", CLICKHOUSE.password());
    return DriverManager.getConnection(url, info);
  }

  @Test
  void driverManagerFindsTheDriverThroughTheServiceLoader() throws SQLException {
    try (Connection connection = connect()) {
      assertThat(connection.isValid(5)).isTrue();
      assertThat(connection.getAutoCommit()).isTrue();
      assertThat(connection.getCatalog()).isEqualTo(CLICKHOUSE.database());
    }
  }

  @Test
  void statementsQueryAndUpdateWithHonestCounts() throws SQLException {
    try (Connection connection = connect();
        Statement statement = connection.createStatement()) {
      assertThat(
              statement.executeUpdate(
                  "CREATE TABLE jdbc_t (id UInt32, name String, score Float64,"
                      + " added Date, dec Decimal(10, 2), tags Array(String),"
                      + " maybe Nullable(String)) ENGINE = MergeTree ORDER BY id"))
          .isZero();
      long written =
          statement.executeLargeUpdate(
              "INSERT INTO jdbc_t SELECT number, concat('n', toString(number)), number / 2,"
                  + " toDate('2026-01-01') + number, toDecimal64(number, 2) / 100,"
                  + " [toString(number)], if(number % 2 = 0, NULL, 'x') FROM numbers(100)");
      assertThat(written).isEqualTo(100);

      try (ResultSet rows =
          statement.executeQuery(
              "SELECT id, name, score, added, dec, tags, maybe FROM jdbc_t ORDER BY id")) {
        ResultSetMetaData metadata = rows.getMetaData();
        assertThat(metadata.getColumnCount()).isEqualTo(7);
        assertThat(metadata.getColumnName(1)).isEqualTo("id");
        assertThat(metadata.getColumnType(1)).isEqualTo(Types.BIGINT);
        assertThat(metadata.getColumnType(2)).isEqualTo(Types.VARCHAR);
        assertThat(metadata.getColumnType(4)).isEqualTo(Types.DATE);
        assertThat(metadata.getColumnType(5)).isEqualTo(Types.DECIMAL);
        assertThat(metadata.getScale(5)).isEqualTo(2);
        assertThat(metadata.getColumnType(6)).isEqualTo(Types.ARRAY);
        assertThat(metadata.isNullable(7)).isEqualTo(ResultSetMetaData.columnNullable);

        int count = 0;
        while (rows.next()) {
          long id = rows.getLong("id");
          assertThat(rows.getString("name")).isEqualTo("n" + id);
          assertThat(rows.getDouble("score")).isEqualTo(id / 2.0);
          assertThat(rows.getDate("added"))
              .isEqualTo(Date.valueOf(LocalDate.of(2026, 1, 1).plusDays(id)));
          assertThat(rows.getBigDecimal("dec")).isEqualByComparingTo(BigDecimal.valueOf(id, 2));
          assertThat((Object[]) rows.getArray("tags").getArray())
              .containsExactly(String.valueOf(id));
          String maybe = rows.getString("maybe");
          if (id % 2 == 0) {
            assertThat(maybe).isNull();
            assertThat(rows.wasNull()).isTrue();
          } else {
            assertThat(maybe).isEqualTo("x");
          }
          count++;
        }
        assertThat(count).isEqualTo(100);
      }
    }
  }

  @Test
  void preparedStatementsSubstituteAndBatchNatively() throws SQLException {
    try (Connection connection = connect()) {
      try (Statement statement = connection.createStatement()) {
        statement.executeUpdate(
            "CREATE TABLE jdbc_batch (id UInt32, name String, at DateTime)"
                + " ENGINE = MergeTree ORDER BY id");
      }
      // The batched INSERT travels as native blocks with server schema validation.
      try (PreparedStatement insert =
          connection.prepareStatement("INSERT INTO jdbc_batch VALUES (?, ?, ?)")) {
        for (int i = 0; i < 1000; i++) {
          insert.setInt(1, i);
          insert.setString(2, "row-" + i);
          insert.setTimestamp(3, Timestamp.from(Instant.parse("2026-07-22T10:00:00Z")));
          insert.addBatch();
        }
        int[] counts = insert.executeBatch();
        assertThat(counts).hasSize(1000).containsOnly(1);
      }
      // Substitution path with escaping hazards.
      try (PreparedStatement select =
          connection.prepareStatement(
              "SELECT count() FROM jdbc_batch WHERE name = ? OR name = ?")) {
        select.setString(1, "row-1");
        select.setString(2, "it's -- not /* a */ 'comment'");
        try (ResultSet rows = select.executeQuery()) {
          assertThat(rows.next()).isTrue();
          assertThat(rows.getLong(1)).isEqualTo(1);
        }
      }
      try (Statement statement = connection.createStatement();
          ResultSet rows = statement.executeQuery("SELECT count() FROM jdbc_batch")) {
        assertThat(rows.next()).isTrue();
        assertThat(rows.getLong(1)).isEqualTo(1000);
      }
    }
  }

  @Test
  void databaseMetadataDescribesTablesAndColumns() throws SQLException {
    try (Connection connection = connect()) {
      try (Statement statement = connection.createStatement()) {
        statement.executeUpdate(
            "CREATE TABLE jdbc_meta (pk UInt64, val Nullable(String))"
                + " ENGINE = MergeTree ORDER BY pk");
      }
      DatabaseMetaData metadata = connection.getMetaData();
      assertThat(metadata.getDatabaseProductName()).isEqualTo("ClickHouse");
      assertThat(metadata.supportsTransactions()).isFalse();
      assertThat(metadata.getUserName()).isEqualTo(CLICKHOUSE.username());

      try (ResultSet tables = metadata.getTables(CLICKHOUSE.database(), null, "jdbc_meta", null)) {
        assertThat(tables.next()).isTrue();
        assertThat(tables.getString("TABLE_NAME")).isEqualTo("jdbc_meta");
        assertThat(tables.getString("TABLE_TYPE")).isEqualTo("TABLE");
        assertThat(tables.next()).isFalse();
      }
      try (ResultSet columns = metadata.getColumns(CLICKHOUSE.database(), null, "jdbc_meta", "%")) {
        assertThat(columns.next()).isTrue();
        assertThat(columns.getString("COLUMN_NAME")).isEqualTo("pk");
        assertThat(columns.getInt("DATA_TYPE")).isEqualTo(Types.NUMERIC);
        assertThat(columns.getString("IS_NULLABLE")).isEqualTo("NO");
        assertThat(columns.next()).isTrue();
        assertThat(columns.getString("COLUMN_NAME")).isEqualTo("val");
        assertThat(columns.getInt("DATA_TYPE")).isEqualTo(Types.VARCHAR);
        assertThat(columns.getString("IS_NULLABLE")).isEqualTo("YES");
        assertThat(columns.next()).isFalse();
      }
      try (ResultSet keys = metadata.getPrimaryKeys(CLICKHOUSE.database(), null, "jdbc_meta")) {
        assertThat(keys.next()).isTrue();
        assertThat(keys.getString("COLUMN_NAME")).isEqualTo("pk");
        assertThat(keys.next()).isFalse();
      }
    }
  }

  @Test
  void errorsMapToTheJdbcHierarchy() throws SQLException {
    try (Connection connection = connect();
        Statement statement = connection.createStatement()) {
      assertThatThrownBy(() -> statement.executeQuery("SELECT broken syntax here"))
          .isInstanceOf(SQLSyntaxErrorException.class)
          .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("42000"));
      // The connection survives a server rejection.
      try (ResultSet rows = statement.executeQuery("SELECT 1")) {
        assertThat(rows.next()).isTrue();
        assertThat(rows.getInt(1)).isEqualTo(1);
      }
      assertThatThrownBy(() -> connection.setAutoCommit(false))
          .isInstanceOf(SQLFeatureNotSupportedException.class);
    }
    assertThatThrownBy(
            () ->
                DriverManager.getConnection(
                    "jdbc:chord://"
                        + CLICKHOUSE.getHost()
                        + ":"
                        + CLICKHOUSE.nativePort()
                        + "?user=chord&password=wrong&allow_plaintext_password=true"))
        .isInstanceOf(SQLInvalidAuthorizationSpecException.class);
  }

  @Test
  void queryTimeoutsSurfaceAsSqlTimeouts() throws SQLException {
    try (Connection connection = connect();
        Statement statement = connection.createStatement()) {
      statement.setQueryTimeout(1);
      assertThatThrownBy(
              () -> {
                try (ResultSet rows =
                    statement.executeQuery("SELECT count() FROM numbers(1000000000000)")) {
                  rows.next();
                }
              })
          .isInstanceOf(SQLTimeoutException.class);
      // The cancel concluded the stream; the connection remains usable.
      try (ResultSet rows = statement.executeQuery("SELECT 2")) {
        assertThat(rows.next()).isTrue();
        assertThat(rows.getInt(1)).isEqualTo(2);
      }
    }
  }

  @Test
  void maxRowsCapsTheStreamClientSide() throws SQLException {
    try (Connection connection = connect();
        Statement statement = connection.createStatement()) {
      statement.setMaxRows(7);
      try (ResultSet rows = statement.executeQuery("SELECT number FROM numbers(1000)")) {
        int count = 0;
        while (rows.next()) {
          count++;
        }
        assertThat(count).isEqualTo(7);
      }
    }
  }

  @Test
  void unsupportedFeaturesFailHonestly() throws SQLException {
    try (Connection connection = connect()) {
      assertThatThrownBy(() -> connection.prepareCall("CALL x()"))
          .isInstanceOf(SQLFeatureNotSupportedException.class);
      assertThatThrownBy(connection::setSavepoint)
          .isInstanceOf(SQLFeatureNotSupportedException.class);
      assertThatThrownBy(
              () ->
                  connection.createStatement(
                      ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY))
          .isInstanceOf(SQLFeatureNotSupportedException.class);
      try (Statement statement = connection.createStatement();
          ResultSet rows = statement.executeQuery("SELECT 1")) {
        assertThat(rows.next()).isTrue();
        assertThatThrownBy(rows::previous).isInstanceOf(SQLFeatureNotSupportedException.class);
        assertThatThrownBy(() -> rows.updateInt(1, 2))
            .isInstanceOf(SQLFeatureNotSupportedException.class);
      }
    }
  }

  @Test
  void dataSourceConnectsWithoutDriverManager() throws SQLException {
    ChordDataSource dataSource = new ChordDataSource();
    dataSource.setUrl(
        "jdbc:chord://"
            + CLICKHOUSE.getHost()
            + ":"
            + CLICKHOUSE.nativePort()
            + "/"
            + CLICKHOUSE.database()
            + "?allow_plaintext_password=true");
    dataSource.setUser(CLICKHOUSE.username());
    dataSource.setPassword(CLICKHOUSE.password());
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery("SELECT lower('JDBC')")) {
      assertThat(rows.next()).isTrue();
      assertThat(rows.getString(1)).isEqualTo("jdbc");
    }
  }

  @Test
  void lowCardinalityAndUnsignedTypesSurfaceSensibly() throws SQLException {
    try (Connection connection = connect();
        Statement statement = connection.createStatement()) {
      try (ResultSet rows =
          statement.executeQuery(
              "SELECT toLowCardinality('tag') AS lc, toUInt64(18446744073709551615) AS big")) {
        assertThat(rows.next()).isTrue();
        assertThat(rows.getString("lc")).isEqualTo("tag");
        // UInt64 max exceeds long; getLong must refuse rather than truncate.
        assertThat(rows.getObject("big"))
            .isEqualTo(new java.math.BigInteger("18446744073709551615"));
        assertThatThrownBy(() -> rows.getLong("big"))
            .isInstanceOf(SQLException.class)
            .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("22003"));
      }
    }
  }

  @Test
  void floatSpecialValuesSurfaceThroughTheGetters() throws SQLException {
    try (Connection connection = connect();
        Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery("SELECT nan, inf, -inf, toFloat32(nan), -0.0")) {
      assertThat(rows.next()).isTrue();
      assertThat(Double.isNaN(rows.getDouble(1))).isTrue();
      assertThat(rows.wasNull()).isFalse(); // NaN is a value, not SQL NULL
      assertThat(rows.getDouble(2)).isEqualTo(Double.POSITIVE_INFINITY);
      assertThat(rows.getDouble(3)).isEqualTo(Double.NEGATIVE_INFINITY);
      assertThat(Float.isNaN(rows.getFloat(4))).isTrue();
      assertThat(Double.doubleToRawLongBits(rows.getDouble(5)))
          .isEqualTo(Double.doubleToRawLongBits(-0.0d));
      assertThat(Double.isNaN(rows.getObject(1, Double.class))).isTrue();
    }
  }

  @Test
  void arraysComeBackAsLists() throws SQLException {
    try (Connection connection = connect();
        Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery("SELECT [1, 2, 3] AS a")) {
      assertThat(rows.next()).isTrue();
      List<?> values = rows.getObject("a", List.class);
      assertThat(values).isEqualTo(List.of(1, 2, 3)); // UInt8 literals surface as Integers
    }
  }
}
