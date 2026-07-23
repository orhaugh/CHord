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
  @SuppressWarnings("deprecation") // getBigDecimal(int, scale) is part of the tested surface
  void resultSetGettersCoverEveryCoercion() throws SQLException {
    try (Connection connection = connect();
        Statement statement = connection.createStatement();
        ResultSet rows =
            statement.executeQuery(
                "SELECT true AS b, toInt8(-7) AS i8, toInt16(-300) AS i16, toFloat32(1.5) AS f32,"
                    + " 'bytes' AS s, toDateTime(1000000, 'UTC') AS dt,"
                    + " toDecimal64('12.34', 2) AS dec, toUUID('01234567-89ab-cdef-0123-456789abcdef') AS u,"
                    + " toDate('2024-05-01') AS d, CAST(NULL, 'Nullable(Int32)') AS n,"
                    + " map('k', 1) AS m")) {
      assertThat(rows.next()).isTrue();
      assertThat(rows.getBoolean("b")).isTrue();
      assertThat(rows.getBoolean(1)).isTrue();
      assertThat(rows.getByte("i8")).isEqualTo((byte) -7);
      assertThat(rows.getShort("i16")).isEqualTo((short) -300);
      assertThat(rows.getFloat("f32")).isEqualTo(1.5f);
      assertThat(rows.getBytes("s"))
          .isEqualTo("bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8));
      assertThat(rows.getTimestamp("dt"))
          .isEqualTo(Timestamp.from(Instant.ofEpochSecond(1_000_000)));
      assertThat(rows.getTimestamp(6)).isEqualTo(Timestamp.from(Instant.ofEpochSecond(1_000_000)));
      assertThat(rows.getBigDecimal(7)).isEqualByComparingTo("12.34");
      assertThat(rows.getBigDecimal(7, 1)).isEqualByComparingTo("12.3");
      assertThat(rows.getObject("u", java.util.UUID.class))
          .isEqualTo(java.util.UUID.fromString("01234567-89ab-cdef-0123-456789abcdef"));
      assertThat(rows.getObject("d", java.time.LocalDate.class))
          .isEqualTo(java.time.LocalDate.of(2024, 5, 1));
      assertThat(rows.getObject("dt", Instant.class)).isEqualTo(Instant.ofEpochSecond(1_000_000));
      java.util.Map<?, ?> mapValue = rows.getObject("m", java.util.Map.class);
      assertThat(mapValue.get("k")).isEqualTo(1);
      // The wasNullOr path: a NULL through a primitive getter conversion.
      assertThat(rows.getObject("n", Integer.class)).isNull();
      assertThat(rows.getInt("n")).isZero();
      assertThat(rows.wasNull()).isTrue();
      // Case insensitive fallback in findColumn.
      assertThat(rows.getBoolean("B")).isTrue();
      // Error states: unknown column, out of range index, unsupported conversion.
      assertThatThrownBy(() -> rows.getString("missing"))
          .isInstanceOf(SQLException.class)
          .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("42S22"));
      assertThatThrownBy(() -> rows.getString(99))
          .isInstanceOf(SQLException.class)
          .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("07009"));
      assertThatThrownBy(() -> rows.getObject("s", java.io.File.class))
          .isInstanceOf(SQLFeatureNotSupportedException.class);
    }
    // The cursor not positioned state.
    try (Connection connection = connect();
        Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery("SELECT 1")) {
      assertThatThrownBy(() -> rows.getInt(1))
          .isInstanceOf(SQLException.class)
          .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("24000"));
    }
  }

  @Test
  void preparedStatementSettersAndBatchSemanticsCoverTheSurface() throws SQLException {
    try (Connection connection = connect()) {
      try (Statement statement = connection.createStatement()) {
        statement.executeUpdate(
            "CREATE TABLE jdbc_setters (id UInt32, b Bool, i8 Int8, i16 Int16, l Int64,"
                + " f Float32, d Float64, dec Decimal(10, 2), bytes String, day Date,"
                + " maybe Nullable(String), tags Array(String)) ENGINE = MergeTree ORDER BY id");
      }
      try (PreparedStatement insert =
          connection.prepareStatement(
              "INSERT INTO jdbc_setters VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
        insert.setInt(1, 1);
        insert.setBoolean(2, true);
        insert.setByte(3, (byte) -7);
        insert.setShort(4, (short) -300);
        insert.setLong(5, Long.MAX_VALUE);
        insert.setFloat(6, 1.5f);
        insert.setDouble(7, 2.5d);
        insert.setBigDecimal(8, new BigDecimal("12.34"));
        insert.setBytes(9, new byte[] {104, 105});
        insert.setDate(10, Date.valueOf(LocalDate.of(2024, 5, 1)));
        insert.setNull(11, Types.VARCHAR);
        insert.setObject(12, List.of("a", "b"));
        // The single row path travels through the same native machinery as batches.
        assertThat(insert.executeUpdate()).isEqualTo(1);

        // A second round through addBatch proves batches are reusable after execution.
        insert.setInt(1, 2);
        insert.setBoolean(2, false);
        insert.setByte(3, (byte) 0);
        insert.setShort(4, (short) 0);
        insert.setLong(5, 0L);
        insert.setFloat(6, 0f);
        insert.setDouble(7, 0d);
        insert.setBigDecimal(8, BigDecimal.ZERO);
        insert.setBytes(9, new byte[0]);
        insert.setDate(10, Date.valueOf(LocalDate.ofEpochDay(0)));
        insert.setString(11, "present");
        insert.setObject(12, List.of());
        insert.addBatch();
        insert.clearBatch(); // discarded: the row must not appear
        insert.addBatch();
        assertThat(insert.executeBatch()).containsExactly(1);
        assertThat(insert.executeBatch()).isEmpty(); // an empty batch is a no op
      }
      try (Statement statement = connection.createStatement();
          ResultSet rows =
              statement.executeQuery(
                  "SELECT id, b, i8, l, dec, bytes, maybe, tags FROM jdbc_setters ORDER BY id")) {
        assertThat(rows.next()).isTrue();
        assertThat(rows.getBoolean("b")).isTrue();
        assertThat(rows.getByte("i8")).isEqualTo((byte) -7);
        assertThat(rows.getLong("l")).isEqualTo(Long.MAX_VALUE);
        assertThat(rows.getBigDecimal("dec")).isEqualByComparingTo("12.34");
        assertThat(rows.getString("bytes")).isEqualTo("hi");
        assertThat(rows.getString("maybe")).isNull();
        assertThat(rows.next()).isTrue();
        assertThat(rows.getString("maybe")).isEqualTo("present");
        assertThat(rows.next()).isFalse();
      }
      // Refusals: unbound parameters, bad indexes, non INSERT batches.
      try (PreparedStatement unbound =
          connection.prepareStatement("SELECT count() FROM jdbc_setters WHERE id = ?")) {
        assertThatThrownBy(unbound::executeQuery)
            .isInstanceOf(SQLException.class)
            .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("07002"));
        assertThatThrownBy(() -> unbound.setInt(2, 1))
            .isInstanceOf(SQLException.class)
            .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("07009"));
        unbound.setInt(1, 1);
        unbound.clearParameters();
        assertThatThrownBy(unbound::executeQuery)
            .isInstanceOf(SQLException.class)
            .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("07002"));
        assertThatThrownBy(unbound::addBatch).isInstanceOf(SQLFeatureNotSupportedException.class);
      }
      // The substitution execute path for a statement with parameters but no result set.
      try (Statement statement = connection.createStatement()) {
        statement.executeUpdate(
            "CREATE TABLE jdbc_setters_copy (id UInt32) ENGINE = MergeTree ORDER BY id");
      }
      try (PreparedStatement copy =
          connection.prepareStatement(
              "INSERT INTO jdbc_setters_copy SELECT id FROM jdbc_setters WHERE id = ?")) {
        copy.setInt(1, 1);
        assertThat(copy.executeUpdate()).isEqualTo(1);
      }
      // Parameter metadata knows the count and mode without a server round trip.
      try (PreparedStatement prepared = connection.prepareStatement("SELECT ?, ?")) {
        java.sql.ParameterMetaData metadata = prepared.getParameterMetaData();
        assertThat(metadata.getParameterCount()).isEqualTo(2);
        assertThat(metadata.getParameterMode(1))
            .isEqualTo(java.sql.ParameterMetaData.parameterModeIn);
        assertThatThrownBy(() -> metadata.getParameterType(1))
            .isInstanceOf(SQLFeatureNotSupportedException.class);
        assertThat(prepared.getMetaData()).isNull(); // before execution
      }
    }
  }

  @Test
  void statementLifecycleFollowsTheJdbcContract() throws SQLException, InterruptedException {
    try (Connection connection = connect()) {
      try (Statement statement = connection.createStatement()) {
        assertThat(statement.executeUpdate("CREATE TABLE jdbc_life (n UInt8) ENGINE = Memory"))
            .isZero();
        assertThat(statement.getLargeUpdateCount()).isZero();
        assertThat(statement.execute("SELECT 1")).isTrue();
        assertThat(statement.getUpdateCount()).isEqualTo(-1);
        ResultSet rows = statement.getResultSet();
        assertThat(rows.next()).isTrue();
        assertThat(statement.getMoreResults()).isFalse();
        assertThat(rows.isClosed()).isTrue(); // getMoreResults closes the current result
        assertThat(statement.getUpdateCount()).isEqualTo(-1);
      }
      // closeOnCompletion closes the statement once its result set fully drains.
      Statement completing = connection.createStatement();
      completing.closeOnCompletion();
      assertThat(completing.isCloseOnCompletion()).isTrue();
      try (ResultSet rows = completing.executeQuery("SELECT 1")) {
        while (rows.next()) {
          rows.getInt(1);
        }
      }
      assertThat(completing.isClosed()).isTrue();
      // Direct cancel from another thread while the consumer streams.
      try (Statement statement = connection.createStatement()) {
        try (ResultSet rows = statement.executeQuery("SELECT number FROM system.numbers")) {
          assertThat(rows.next()).isTrue();
          Thread canceller =
              Thread.ofVirtual()
                  .start(
                      () -> {
                        try {
                          statement.cancel();
                        } catch (SQLException e) {
                          // The consumer side assertions carry the test.
                        }
                      });
          int drained = 0;
          while (rows.next() && drained < 10_000_000) {
            drained++;
          }
          canceller.join(java.time.Duration.ofSeconds(10));
        }
        try (ResultSet rows = statement.executeQuery("SELECT 3")) {
          assertThat(rows.next()).isTrue();
          assertThat(rows.getInt(1)).isEqualTo(3);
        }
      }
    }
  }

  @Test
  void databaseMetadataSurfaceAnswersTools() throws SQLException {
    try (Connection connection = connect()) {
      DatabaseMetaData metadata = connection.getMetaData();
      assertThat(metadata.getDatabaseProductVersion()).isNotBlank();
      assertThat(metadata.getDatabaseMajorVersion()).isGreaterThanOrEqualTo(25);
      assertThat(metadata.getDriverName()).isEqualTo("CHord JDBC");
      assertThat(metadata.getJDBCMajorVersion()).isEqualTo(4);
      assertThat(metadata.getIdentifierQuoteString()).isEqualTo("`");
      assertThat(metadata.supportsBatchUpdates()).isTrue();
      assertThat(metadata.getSQLStateType()).isEqualTo(DatabaseMetaData.sqlStateSQL);
      assertThat(metadata.getRowIdLifetime()).isEqualTo(java.sql.RowIdLifetime.ROWID_UNSUPPORTED);

      try (ResultSet catalogs = metadata.getCatalogs()) {
        java.util.Set<String> names = new java.util.HashSet<>();
        while (catalogs.next()) {
          names.add(catalogs.getString("TABLE_CAT"));
        }
        assertThat(names).contains(CLICKHOUSE.database(), "system");
      }
      try (ResultSet types = metadata.getTableTypes()) {
        java.util.List<String> names = new java.util.ArrayList<>();
        while (types.next()) {
          names.add(types.getString("TABLE_TYPE"));
        }
        assertThat(names).containsExactly("TABLE", "VIEW", "SYSTEM TABLE");
      }
      try (ResultSet typeInfo = metadata.getTypeInfo()) {
        assertThat(typeInfo.next()).isTrue();
        assertThat(typeInfo.getString("TYPE_NAME")).isNotBlank();
      }
      try (ResultSet functions = metadata.getFunctions(null, null, "toStr%")) {
        assertThat(functions.next()).isTrue();
        assertThat(functions.getString("FUNCTION_NAME")).startsWith("toStr");
      }
      try (Statement statement = connection.createStatement()) {
        statement.executeUpdate("CREATE TABLE jdbc_meta_base (n UInt8) ENGINE = Memory");
        statement.executeUpdate("CREATE VIEW jdbc_meta_view AS SELECT n FROM jdbc_meta_base");
      }
      try (ResultSet views =
          metadata.getTables(CLICKHOUSE.database(), null, "jdbc_meta_%", new String[] {"VIEW"})) {
        assertThat(views.next()).isTrue();
        assertThat(views.getString("TABLE_NAME")).isEqualTo("jdbc_meta_view");
        assertThat(views.getString("TABLE_TYPE")).isEqualTo("VIEW");
        assertThat(views.next()).isFalse(); // the types filter excluded the base TABLE
      }
      try (ResultSet system = metadata.getTables("system", null, "tables", null)) {
        assertThat(system.next()).isTrue();
        assertThat(system.getString("TABLE_TYPE")).isEqualTo("SYSTEM TABLE");
      }
      try (ResultSet keys = metadata.getImportedKeys(CLICKHOUSE.database(), null, "anything")) {
        assertThat(keys.next()).isFalse(); // truthful: ClickHouse has no foreign keys
      }
    }
  }

  @Test
  void resultSetMetadataDescribesEveryMappedType() throws SQLException {
    try (Connection connection = connect();
        Statement statement = connection.createStatement();
        ResultSet rows =
            statement.executeQuery(
                "SELECT toUInt8(1) AS u8, toUInt16(1) AS u16, toUInt32(1) AS u32,"
                    + " toUInt64(1) AS u64, toInt8(1) AS i8, toFloat32(1) AS f32,"
                    + " toFloat64(1) AS f64, true AS b, toDate('2024-01-01') AS d,"
                    + " toDateTime(0, 'UTC') AS dt, toDecimal64('1.23', 2) AS dec,"
                    + " 'x' AS s, [1] AS arr, toUUID('01234567-89ab-cdef-0123-456789abcdef') AS u")) {
      ResultSetMetaData metadata = rows.getMetaData();
      assertThat(metadata.getColumnType(1)).isEqualTo(Types.SMALLINT);
      assertThat(metadata.getColumnType(2)).isEqualTo(Types.INTEGER);
      assertThat(metadata.getColumnType(3)).isEqualTo(Types.BIGINT);
      assertThat(metadata.getColumnType(4)).isEqualTo(Types.NUMERIC);
      assertThat(metadata.getColumnType(5)).isEqualTo(Types.TINYINT);
      assertThat(metadata.getColumnType(6)).isEqualTo(Types.REAL);
      assertThat(metadata.getColumnType(7)).isEqualTo(Types.DOUBLE);
      assertThat(metadata.getColumnType(8)).isEqualTo(Types.BOOLEAN);
      assertThat(metadata.getColumnType(9)).isEqualTo(Types.DATE);
      assertThat(metadata.getColumnType(10)).isEqualTo(Types.TIMESTAMP);
      assertThat(metadata.getColumnType(11)).isEqualTo(Types.DECIMAL);
      assertThat(metadata.getColumnType(13)).isEqualTo(Types.ARRAY);
      assertThat(metadata.getColumnType(14)).isEqualTo(Types.OTHER);

      assertThat(metadata.getColumnClassName(4)).isEqualTo("java.math.BigInteger");
      assertThat(metadata.getColumnClassName(7)).isEqualTo("java.lang.Double");
      assertThat(metadata.getColumnClassName(12)).isEqualTo("java.lang.String");
      assertThat(metadata.getColumnTypeName(11).replace(" ", "")).isEqualTo("Decimal(18,2)");
      assertThat(metadata.getPrecision(11)).isEqualTo(18);
      assertThat(metadata.getScale(11)).isEqualTo(2);
      assertThat(metadata.isSigned(5)).isTrue();
      assertThat(metadata.isSigned(1)).isFalse();
      assertThat(metadata.isSigned(7)).isTrue();
      assertThat(metadata.getColumnLabel(12)).isEqualTo("s");
      assertThat(metadata.isReadOnly(1)).isTrue();
    }
  }

  @Test
  void driverUrlOptionsAndDataSourcePathsAreWired() throws SQLException {
    String base =
        "jdbc:chord://"
            + CLICKHOUSE.getHost()
            + ":"
            + CLICKHOUSE.nativePort()
            + "/"
            + CLICKHOUSE.database();
    Properties credentials = new Properties();
    credentials.setProperty("user", CLICKHOUSE.username());
    credentials.setProperty("password", CLICKHOUSE.password());
    credentials.setProperty("allow_plaintext_password", "true");

    // Multiple hosts: the first endpoint refuses instantly and the driver fails over.
    try (Connection connection =
        DriverManager.getConnection(
            "jdbc:chord://127.0.0.1:1,"
                + CLICKHOUSE.getHost()
                + ":"
                + CLICKHOUSE.nativePort()
                + "/"
                + CLICKHOUSE.database()
                + "?allow_plaintext_password=true",
            credentials)) {
      try (Statement statement = connection.createStatement();
          ResultSet rows = statement.executeQuery("SELECT 5")) {
        assertThat(rows.next()).isTrue();
        assertThat(rows.getInt(1)).isEqualTo(5);
      }
    }
    // Compression and the default statement timeout arrive through URL parameters.
    try (Connection connection =
        DriverManager.getConnection(
            base + "?compression=zstd&query_timeout_ms=60000", credentials)) {
      try (Statement statement = connection.createStatement()) {
        assertThat(statement.getQueryTimeout()).isEqualTo(60);
        try (ResultSet rows = statement.executeQuery("SELECT sum(number) FROM numbers(100000)")) {
          assertThat(rows.next()).isTrue();
          assertThat(rows.getLong(1)).isEqualTo(4_999_950_000L);
        }
      }
    }
    assertThatThrownBy(() -> DriverManager.getConnection(base + "?compression=snappy", credentials))
        .isInstanceOf(SQLException.class)
        .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("08001"));
    assertThatThrownBy(
            () -> DriverManager.getConnection(base + "?connect_timeout_ms=abc", credentials))
        .isInstanceOf(SQLException.class)
        .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("08001"));

    ChordDataSource dataSource = new ChordDataSource();
    assertThatThrownBy(dataSource::getConnection)
        .isInstanceOf(SQLException.class)
        .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("08001"));
    dataSource.setUrl("jdbc:mysql://elsewhere");
    assertThatThrownBy(dataSource::getConnection).isInstanceOf(SQLException.class);
    dataSource.setUrl(base + "?allow_plaintext_password=true");
    try (Connection connection =
        dataSource.getConnection(CLICKHOUSE.username(), CLICKHOUSE.password())) {
      assertThat(connection.isValid(5)).isTrue();
      // Unwrap reaches the native connection underneath the adapter.
      assertThat(connection.unwrap(io.github.orhaugh.chord.client.NativeConnection.class))
          .isNotNull();
      assertThat(connection.isWrapperFor(io.github.orhaugh.chord.client.NativeConnection.class))
          .isTrue();
      assertThatThrownBy(() -> connection.unwrap(Integer.class)).isInstanceOf(SQLException.class);
    }
  }

  @Test
  void statementKindsFlowThroughJdbc() throws SQLException {
    try (Connection connection = connect();
        Statement statement = connection.createStatement()) {
      // DDL executes with an honest zero count and no result set.
      assertThat(
              statement.executeUpdate(
                  "CREATE TABLE jdbc_kinds (id UInt64, v UInt64)"
                      + " ENGINE = MergeTree ORDER BY id"))
          .isZero();
      assertThat(statement.execute("ALTER TABLE jdbc_kinds ADD COLUMN extra UInt8 DEFAULT 3"))
          .isFalse();
      assertThat(statement.getUpdateCount()).isZero();
      statement.executeUpdate("INSERT INTO jdbc_kinds SELECT number, number, 3 FROM numbers(10)");

      // Introspection statements come back as ordinary result sets.
      boolean found = false;
      try (ResultSet tables = statement.executeQuery("SHOW TABLES")) {
        while (tables.next()) {
          found |= "jdbc_kinds".equals(tables.getString(1));
        }
      }
      assertThat(found).isTrue();
      try (ResultSet described = statement.executeQuery("DESCRIBE TABLE jdbc_kinds")) {
        assertThat(described.next()).isTrue();
        assertThat(described.getString("name")).isEqualTo("id");
        assertThat(described.getString("type")).isEqualTo("UInt64");
      }
      try (ResultSet plan = statement.executeQuery("EXPLAIN SELECT sum(v) FROM jdbc_kinds")) {
        assertThat(plan.next()).isTrue();
        assertThat(plan.getString(1)).isNotBlank();
      }

      // A lightweight DELETE flows as an update and the effect is visible immediately.
      statement.executeUpdate("DELETE FROM jdbc_kinds WHERE id < 4");
      try (ResultSet count = statement.executeQuery("SELECT count() FROM jdbc_kinds")) {
        assertThat(count.next()).isTrue();
        assertThat(count.getLong(1)).isEqualTo(6);
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
