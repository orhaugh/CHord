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

import io.github.orhaugh.chord.ChordException;
import io.github.orhaugh.chord.client.QueryRequest;
import io.github.orhaugh.chord.client.QueryResult;
import io.github.orhaugh.chord.codec.block.Block;
import io.github.orhaugh.chord.codec.column.BlockBuilder;
import io.github.orhaugh.chord.codec.type.ClickHouseType;
import io.github.orhaugh.chord.codec.type.TypeParser;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.RowIdLifetime;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Database metadata over the native connection and the {@code system} tables.
 *
 * <p>ClickHouse databases surface as JDBC catalogs; the schema level is unused. Capability answers
 * are honest: no transactions, no stored procedures, not SQL-92 compliant.
 */
final class ChordDatabaseMetaData implements DatabaseMetaData {

  private static final ClickHouseType STRING = TypeParser.parse("String", 100, 8);
  private static final ClickHouseType NULLABLE_STRING =
      TypeParser.parse("Nullable(String)", 100, 8);
  private static final ClickHouseType INT32 = TypeParser.parse("Int32", 100, 8);

  private final ChordConnection connection;

  ChordDatabaseMetaData(ChordConnection connection) {
    this.connection = connection;
  }

  private List<List<Object>> query(String sql, int columns) throws SQLException {
    try (QueryResult result = connection.nativeConnection().query(QueryRequest.of(sql))) {
      List<List<Object>> rows = new ArrayList<>();
      Optional<Block> next;
      while ((next = result.nextBlock()).isPresent()) {
        Block block = next.get();
        for (int r = 0; r < block.rows(); r++) {
          List<Object> row = new ArrayList<>(columns);
          for (int c = 0; c < columns; c++) {
            row.add(block.column(c).isNullAt(r) ? null : block.column(c).objectAt(r));
          }
          rows.add(row);
        }
      }
      return rows;
    } catch (ChordException e) {
      throw SqlExceptions.map(e);
    }
  }

  private static ResultSet localResult(
      List<String> names, List<ClickHouseType> types, List<List<Object>> rows) {
    BlockBuilder headerBuilder = BlockBuilder.forColumnTypes(types, names);
    Block header = headerBuilder.build();
    BlockBuilder builder = BlockBuilder.forColumnTypes(types, names);
    for (List<Object> row : rows) {
      builder.addRow(row.toArray());
    }
    Block data = builder.build();
    return new ChordResultSet(header, data.rows() == 0 ? List.of() : List.of(data));
  }

  private static String likeOrAll(String pattern) {
    return pattern == null || pattern.isEmpty() ? "%" : pattern;
  }

  // Identity.

  @Override
  public String getDatabaseProductName() {
    return "ClickHouse";
  }

  @Override
  public String getDatabaseProductVersion() {
    return connection.nativeConnection().serverHello().versionString();
  }

  @Override
  public int getDatabaseMajorVersion() {
    return (int) connection.nativeConnection().serverHello().versionMajor();
  }

  @Override
  public int getDatabaseMinorVersion() {
    return (int) connection.nativeConnection().serverHello().versionMinor();
  }

  @Override
  public String getDriverName() {
    return "CHord JDBC";
  }

  @Override
  public String getDriverVersion() {
    return getDriverMajorVersion() + "." + getDriverMinorVersion();
  }

  @Override
  public int getDriverMajorVersion() {
    return 0;
  }

  @Override
  public int getDriverMinorVersion() {
    return 1;
  }

  @Override
  public int getJDBCMajorVersion() {
    return 4;
  }

  @Override
  public int getJDBCMinorVersion() {
    return 3;
  }

  @Override
  public String getURL() {
    return null;
  }

  @Override
  public String getUserName() throws SQLException {
    List<List<Object>> rows = query("SELECT currentUser()", 1);
    return rows.isEmpty() ? "" : String.valueOf(rows.get(0).get(0));
  }

  @Override
  public Connection getConnection() {
    return connection;
  }

  // Catalog browsing.

  @Override
  public ResultSet getCatalogs() throws SQLException {
    List<List<Object>> databases = query("SELECT name FROM system.databases ORDER BY name", 1);
    List<List<Object>> rows = new ArrayList<>();
    for (List<Object> database : databases) {
      rows.add(List.of(String.valueOf(database.get(0))));
    }
    return localResult(List.of("TABLE_CAT"), List.of(STRING), rows);
  }

  @Override
  public ResultSet getSchemas() {
    return localResult(List.of("TABLE_SCHEM", "TABLE_CATALOG"), List.of(STRING, STRING), List.of());
  }

  @Override
  public ResultSet getSchemas(String catalog, String schemaPattern) {
    return getSchemas();
  }

  @Override
  public ResultSet getTableTypes() {
    return localResult(
        List.of("TABLE_TYPE"),
        List.of(STRING),
        List.of(List.of("TABLE"), List.of("VIEW"), List.of("SYSTEM TABLE")));
  }

  @Override
  public ResultSet getTables(
      String catalog, String schemaPattern, String tableNamePattern, String[] types)
      throws SQLException {
    String database = catalog == null || catalog.isEmpty() ? currentDatabase() : catalog;
    String sql =
        "SELECT database, name, engine FROM system.tables WHERE database = "
            + SqlText.quote(database)
            + " AND name LIKE "
            + SqlText.quote(likeOrAll(tableNamePattern))
            + " ORDER BY name";
    List<List<Object>> raw = query(sql, 3);
    List<List<Object>> rows = new ArrayList<>();
    for (List<Object> table : raw) {
      String engine = String.valueOf(table.get(2));
      String tableType =
          engine.endsWith("View")
              ? "VIEW"
              : "system".equals(String.valueOf(table.get(0))) ? "SYSTEM TABLE" : "TABLE";
      if (types != null && !List.of(types).contains(tableType)) {
        continue;
      }
      List<Object> row = new ArrayList<>();
      row.add(table.get(0)); // TABLE_CAT
      row.add(null); // TABLE_SCHEM
      row.add(table.get(1)); // TABLE_NAME
      row.add(tableType);
      row.add(""); // REMARKS
      row.add(null);
      row.add(null);
      row.add(null);
      row.add(null);
      row.add(null);
      rows.add(row);
    }
    return localResult(
        List.of(
            "TABLE_CAT",
            "TABLE_SCHEM",
            "TABLE_NAME",
            "TABLE_TYPE",
            "REMARKS",
            "TYPE_CAT",
            "TYPE_SCHEM",
            "TYPE_NAME",
            "SELF_REFERENCING_COL_NAME",
            "REF_GENERATION"),
        List.of(
            STRING,
            NULLABLE_STRING,
            STRING,
            STRING,
            STRING,
            NULLABLE_STRING,
            NULLABLE_STRING,
            NULLABLE_STRING,
            NULLABLE_STRING,
            NULLABLE_STRING),
        rows);
  }

  private String currentDatabase() throws SQLException {
    return connection.getCatalog();
  }

  @Override
  public ResultSet getColumns(
      String catalog, String schemaPattern, String tableNamePattern, String columnNamePattern)
      throws SQLException {
    String database = catalog == null || catalog.isEmpty() ? currentDatabase() : catalog;
    String sql =
        "SELECT database, table, name, type, position, default_expression FROM system.columns"
            + " WHERE database = "
            + SqlText.quote(database)
            + " AND table LIKE "
            + SqlText.quote(likeOrAll(tableNamePattern))
            + " AND name LIKE "
            + SqlText.quote(likeOrAll(columnNamePattern))
            + " ORDER BY table, position";
    List<List<Object>> raw = query(sql, 6);
    List<List<Object>> rows = new ArrayList<>();
    for (List<Object> column : raw) {
      String typeName = String.valueOf(column.get(3));
      ClickHouseType type;
      try {
        type = TypeParser.parse(typeName, 10_000, 32);
      } catch (RuntimeException e) {
        type = STRING; // Unknown upstream type names still deserve a row.
      }
      List<Object> row = new ArrayList<>();
      row.add(column.get(0)); // TABLE_CAT
      row.add(null); // TABLE_SCHEM
      row.add(column.get(1)); // TABLE_NAME
      row.add(column.get(2)); // COLUMN_NAME
      row.add((long) JdbcTypes.sqlType(type)); // DATA_TYPE
      row.add(typeName); // TYPE_NAME
      row.add((long) JdbcTypes.precision(type)); // COLUMN_SIZE
      row.add(null); // BUFFER_LENGTH
      row.add((long) JdbcTypes.scale(type)); // DECIMAL_DIGITS
      row.add(10L); // NUM_PREC_RADIX
      row.add((long) (JdbcTypes.isNullable(type) ? columnNullable : columnNoNulls));
      row.add(""); // REMARKS
      String defaultExpression = String.valueOf(column.get(5));
      row.add(defaultExpression.isEmpty() ? null : defaultExpression); // COLUMN_DEF
      row.add(null); // SQL_DATA_TYPE
      row.add(null); // SQL_DATETIME_SUB
      row.add(null); // CHAR_OCTET_LENGTH
      row.add(((Number) column.get(4)).longValue()); // ORDINAL_POSITION
      row.add(JdbcTypes.isNullable(type) ? "YES" : "NO"); // IS_NULLABLE
      row.add(null); // SCOPE_CATALOG
      row.add(null); // SCOPE_SCHEMA
      row.add(null); // SCOPE_TABLE
      row.add(null); // SOURCE_DATA_TYPE
      row.add("NO"); // IS_AUTOINCREMENT
      row.add("NO"); // IS_GENERATEDCOLUMN
      rows.add(row);
    }
    return localResult(
        List.of(
            "TABLE_CAT",
            "TABLE_SCHEM",
            "TABLE_NAME",
            "COLUMN_NAME",
            "DATA_TYPE",
            "TYPE_NAME",
            "COLUMN_SIZE",
            "BUFFER_LENGTH",
            "DECIMAL_DIGITS",
            "NUM_PREC_RADIX",
            "NULLABLE",
            "REMARKS",
            "COLUMN_DEF",
            "SQL_DATA_TYPE",
            "SQL_DATETIME_SUB",
            "CHAR_OCTET_LENGTH",
            "ORDINAL_POSITION",
            "IS_NULLABLE",
            "SCOPE_CATALOG",
            "SCOPE_SCHEMA",
            "SCOPE_TABLE",
            "SOURCE_DATA_TYPE",
            "IS_AUTOINCREMENT",
            "IS_GENERATEDCOLUMN"),
        List.of(
            STRING,
            NULLABLE_STRING,
            STRING,
            STRING,
            INT32,
            STRING,
            INT32,
            TypeParser.parse("Nullable(Int32)", 100, 8),
            INT32,
            INT32,
            INT32,
            STRING,
            NULLABLE_STRING,
            TypeParser.parse("Nullable(Int32)", 100, 8),
            TypeParser.parse("Nullable(Int32)", 100, 8),
            TypeParser.parse("Nullable(Int32)", 100, 8),
            INT32,
            STRING,
            NULLABLE_STRING,
            NULLABLE_STRING,
            NULLABLE_STRING,
            TypeParser.parse("Nullable(Int32)", 100, 8),
            STRING,
            STRING),
        rows);
  }

  @Override
  public ResultSet getPrimaryKeys(String catalog, String schema, String table) throws SQLException {
    String database = catalog == null || catalog.isEmpty() ? currentDatabase() : catalog;
    String sql =
        "SELECT database, table, name, position FROM system.columns WHERE database = "
            + SqlText.quote(database)
            + " AND table = "
            + SqlText.quote(table == null ? "" : table)
            + " AND is_in_primary_key ORDER BY position";
    List<List<Object>> raw = query(sql, 4);
    List<List<Object>> rows = new ArrayList<>();
    int sequence = 1;
    for (List<Object> column : raw) {
      List<Object> row = new ArrayList<>();
      row.add(column.get(0));
      row.add(null);
      row.add(column.get(1));
      row.add(column.get(2));
      row.add((long) sequence++);
      row.add(null); // PK_NAME: ClickHouse primary keys are unnamed
      rows.add(row);
    }
    return localResult(
        List.of("TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "COLUMN_NAME", "KEY_SEQ", "PK_NAME"),
        List.of(
            STRING,
            NULLABLE_STRING,
            STRING,
            STRING,
            TypeParser.parse("Int16", 100, 8),
            NULLABLE_STRING),
        rows);
  }

  // Capability flags: honest answers for an analytical columnar database.

  @Override
  public boolean allProceduresAreCallable() {
    return false;
  }

  @Override
  public boolean allTablesAreSelectable() {
    return true;
  }

  @Override
  public boolean isReadOnly() {
    return false;
  }

  @Override
  public boolean nullsAreSortedHigh() {
    return false;
  }

  @Override
  public boolean nullsAreSortedLow() {
    return false;
  }

  @Override
  public boolean nullsAreSortedAtStart() {
    return false;
  }

  @Override
  public boolean nullsAreSortedAtEnd() {
    return true;
  }

  @Override
  public boolean usesLocalFiles() {
    return false;
  }

  @Override
  public boolean usesLocalFilePerTable() {
    return false;
  }

  @Override
  public boolean supportsMixedCaseIdentifiers() {
    return true;
  }

  @Override
  public boolean storesUpperCaseIdentifiers() {
    return false;
  }

  @Override
  public boolean storesLowerCaseIdentifiers() {
    return false;
  }

  @Override
  public boolean storesMixedCaseIdentifiers() {
    return true;
  }

  @Override
  public boolean supportsMixedCaseQuotedIdentifiers() {
    return true;
  }

  @Override
  public boolean storesUpperCaseQuotedIdentifiers() {
    return false;
  }

  @Override
  public boolean storesLowerCaseQuotedIdentifiers() {
    return false;
  }

  @Override
  public boolean storesMixedCaseQuotedIdentifiers() {
    return true;
  }

  @Override
  public String getIdentifierQuoteString() {
    return "`";
  }

  @Override
  public String getSQLKeywords() {
    return "PREWHERE,SAMPLE,FINAL,ARRAY JOIN,GLOBAL,ATTACH,DETACH,OPTIMIZE,MATERIALIZED";
  }

  @Override
  public String getNumericFunctions() {
    return "";
  }

  @Override
  public String getStringFunctions() {
    return "";
  }

  @Override
  public String getSystemFunctions() {
    return "";
  }

  @Override
  public String getTimeDateFunctions() {
    return "";
  }

  @Override
  public String getSearchStringEscape() {
    return "\\";
  }

  @Override
  public String getExtraNameCharacters() {
    return "";
  }

  @Override
  public boolean supportsAlterTableWithAddColumn() {
    return true;
  }

  @Override
  public boolean supportsAlterTableWithDropColumn() {
    return true;
  }

  @Override
  public boolean supportsColumnAliasing() {
    return true;
  }

  @Override
  public boolean nullPlusNonNullIsNull() {
    return true;
  }

  @Override
  public boolean supportsConvert() {
    return false;
  }

  @Override
  public boolean supportsConvert(int fromType, int toType) {
    return false;
  }

  @Override
  public boolean supportsTableCorrelationNames() {
    return true;
  }

  @Override
  public boolean supportsDifferentTableCorrelationNames() {
    return false;
  }

  @Override
  public boolean supportsExpressionsInOrderBy() {
    return true;
  }

  @Override
  public boolean supportsOrderByUnrelated() {
    return true;
  }

  @Override
  public boolean supportsGroupBy() {
    return true;
  }

  @Override
  public boolean supportsGroupByUnrelated() {
    return true;
  }

  @Override
  public boolean supportsGroupByBeyondSelect() {
    return true;
  }

  @Override
  public boolean supportsLikeEscapeClause() {
    return false;
  }

  @Override
  public boolean supportsMultipleResultSets() {
    return false;
  }

  @Override
  public boolean supportsMultipleTransactions() {
    return false;
  }

  @Override
  public boolean supportsNonNullableColumns() {
    return true;
  }

  @Override
  public boolean supportsMinimumSQLGrammar() {
    return true;
  }

  @Override
  public boolean supportsCoreSQLGrammar() {
    return false;
  }

  @Override
  public boolean supportsExtendedSQLGrammar() {
    return false;
  }

  @Override
  public boolean supportsANSI92EntryLevelSQL() {
    return false;
  }

  @Override
  public boolean supportsANSI92IntermediateSQL() {
    return false;
  }

  @Override
  public boolean supportsANSI92FullSQL() {
    return false;
  }

  @Override
  public boolean supportsIntegrityEnhancementFacility() {
    return false;
  }

  @Override
  public boolean supportsOuterJoins() {
    return true;
  }

  @Override
  public boolean supportsFullOuterJoins() {
    return true;
  }

  @Override
  public boolean supportsLimitedOuterJoins() {
    return true;
  }

  @Override
  public String getSchemaTerm() {
    return "";
  }

  @Override
  public String getProcedureTerm() {
    return "function";
  }

  @Override
  public String getCatalogTerm() {
    return "database";
  }

  @Override
  public boolean isCatalogAtStart() {
    return true;
  }

  @Override
  public String getCatalogSeparator() {
    return ".";
  }

  @Override
  public boolean supportsSchemasInDataManipulation() {
    return false;
  }

  @Override
  public boolean supportsSchemasInProcedureCalls() {
    return false;
  }

  @Override
  public boolean supportsSchemasInTableDefinitions() {
    return false;
  }

  @Override
  public boolean supportsSchemasInIndexDefinitions() {
    return false;
  }

  @Override
  public boolean supportsSchemasInPrivilegeDefinitions() {
    return false;
  }

  @Override
  public boolean supportsCatalogsInDataManipulation() {
    return true;
  }

  @Override
  public boolean supportsCatalogsInProcedureCalls() {
    return false;
  }

  @Override
  public boolean supportsCatalogsInTableDefinitions() {
    return true;
  }

  @Override
  public boolean supportsCatalogsInIndexDefinitions() {
    return false;
  }

  @Override
  public boolean supportsCatalogsInPrivilegeDefinitions() {
    return false;
  }

  @Override
  public boolean supportsPositionedDelete() {
    return false;
  }

  @Override
  public boolean supportsPositionedUpdate() {
    return false;
  }

  @Override
  public boolean supportsSelectForUpdate() {
    return false;
  }

  @Override
  public boolean supportsStoredProcedures() {
    return false;
  }

  @Override
  public boolean supportsSubqueriesInComparisons() {
    return true;
  }

  @Override
  public boolean supportsSubqueriesInExists() {
    return true;
  }

  @Override
  public boolean supportsSubqueriesInIns() {
    return true;
  }

  @Override
  public boolean supportsSubqueriesInQuantifieds() {
    return false;
  }

  @Override
  public boolean supportsCorrelatedSubqueries() {
    return false;
  }

  @Override
  public boolean supportsUnion() {
    return true;
  }

  @Override
  public boolean supportsUnionAll() {
    return true;
  }

  @Override
  public boolean supportsOpenCursorsAcrossCommit() {
    return false;
  }

  @Override
  public boolean supportsOpenCursorsAcrossRollback() {
    return false;
  }

  @Override
  public boolean supportsOpenStatementsAcrossCommit() {
    return false;
  }

  @Override
  public boolean supportsOpenStatementsAcrossRollback() {
    return false;
  }

  @Override
  public int getMaxBinaryLiteralLength() {
    return 0;
  }

  @Override
  public int getMaxCharLiteralLength() {
    return 0;
  }

  @Override
  public int getMaxColumnNameLength() {
    return 0;
  }

  @Override
  public int getMaxColumnsInGroupBy() {
    return 0;
  }

  @Override
  public int getMaxColumnsInIndex() {
    return 0;
  }

  @Override
  public int getMaxColumnsInOrderBy() {
    return 0;
  }

  @Override
  public int getMaxColumnsInSelect() {
    return 0;
  }

  @Override
  public int getMaxColumnsInTable() {
    return 0;
  }

  @Override
  public int getMaxConnections() {
    return 0;
  }

  @Override
  public int getMaxCursorNameLength() {
    return 0;
  }

  @Override
  public int getMaxIndexLength() {
    return 0;
  }

  @Override
  public int getMaxSchemaNameLength() {
    return 0;
  }

  @Override
  public int getMaxProcedureNameLength() {
    return 0;
  }

  @Override
  public int getMaxCatalogNameLength() {
    return 0;
  }

  @Override
  public int getMaxRowSize() {
    return 0;
  }

  @Override
  public boolean doesMaxRowSizeIncludeBlobs() {
    return false;
  }

  @Override
  public int getMaxStatementLength() {
    return 0;
  }

  @Override
  public int getMaxStatements() {
    return 0;
  }

  @Override
  public int getMaxTableNameLength() {
    return 0;
  }

  @Override
  public int getMaxTablesInSelect() {
    return 0;
  }

  @Override
  public int getMaxUserNameLength() {
    return 0;
  }

  @Override
  public int getDefaultTransactionIsolation() {
    return Connection.TRANSACTION_NONE;
  }

  @Override
  public boolean supportsTransactions() {
    return false;
  }

  @Override
  public boolean supportsTransactionIsolationLevel(int level) {
    return level == Connection.TRANSACTION_NONE;
  }

  @Override
  public boolean supportsDataDefinitionAndDataManipulationTransactions() {
    return false;
  }

  @Override
  public boolean supportsDataManipulationTransactionsOnly() {
    return false;
  }

  @Override
  public boolean dataDefinitionCausesTransactionCommit() {
    return false;
  }

  @Override
  public boolean dataDefinitionIgnoredInTransactions() {
    return false;
  }

  @Override
  public ResultSet getProcedures(
      String catalog, String schemaPattern, String procedureNamePattern) {
    return localResult(
        List.of(
            "PROCEDURE_CAT",
            "PROCEDURE_SCHEM",
            "PROCEDURE_NAME",
            "R1",
            "R2",
            "R3",
            "REMARKS",
            "PROCEDURE_TYPE",
            "SPECIFIC_NAME"),
        List.of(
            NULLABLE_STRING,
            NULLABLE_STRING,
            STRING,
            NULLABLE_STRING,
            NULLABLE_STRING,
            NULLABLE_STRING,
            STRING,
            TypeParser.parse("Int16", 100, 8),
            STRING),
        List.of());
  }

  @Override
  public ResultSet getProcedureColumns(
      String catalog, String schemaPattern, String procedureNamePattern, String columnNamePattern)
      throws SQLException {
    throw SqlExceptions.unsupported("Stored procedures");
  }

  @Override
  public ResultSet getColumnPrivileges(
      String catalog, String schema, String table, String columnNamePattern) throws SQLException {
    throw SqlExceptions.unsupported("Column privilege metadata");
  }

  @Override
  public ResultSet getTablePrivileges(String catalog, String schemaPattern, String tableNamePattern)
      throws SQLException {
    throw SqlExceptions.unsupported("Table privilege metadata");
  }

  @Override
  public ResultSet getBestRowIdentifier(
      String catalog, String schema, String table, int scope, boolean nullable)
      throws SQLException {
    throw SqlExceptions.unsupported("Best row identifier metadata");
  }

  @Override
  public ResultSet getVersionColumns(String catalog, String schema, String table)
      throws SQLException {
    throw SqlExceptions.unsupported("Version column metadata");
  }

  @Override
  public ResultSet getImportedKeys(String catalog, String schema, String table) {
    return emptyKeysResult();
  }

  @Override
  public ResultSet getExportedKeys(String catalog, String schema, String table) {
    return emptyKeysResult();
  }

  @Override
  public ResultSet getCrossReference(
      String parentCatalog,
      String parentSchema,
      String parentTable,
      String foreignCatalog,
      String foreignSchema,
      String foreignTable) {
    return emptyKeysResult();
  }

  private static ResultSet emptyKeysResult() {
    // ClickHouse has no foreign keys; an empty result is the truthful answer tools expect.
    return localResult(
        List.of(
            "PKTABLE_CAT",
            "PKTABLE_SCHEM",
            "PKTABLE_NAME",
            "PKCOLUMN_NAME",
            "FKTABLE_CAT",
            "FKTABLE_SCHEM",
            "FKTABLE_NAME",
            "FKCOLUMN_NAME",
            "KEY_SEQ",
            "UPDATE_RULE",
            "DELETE_RULE",
            "FK_NAME",
            "PK_NAME",
            "DEFERRABILITY"),
        List.of(
            NULLABLE_STRING,
            NULLABLE_STRING,
            STRING,
            STRING,
            NULLABLE_STRING,
            NULLABLE_STRING,
            STRING,
            STRING,
            TypeParser.parse("Int16", 100, 8),
            TypeParser.parse("Int16", 100, 8),
            TypeParser.parse("Int16", 100, 8),
            NULLABLE_STRING,
            NULLABLE_STRING,
            TypeParser.parse("Int16", 100, 8)),
        List.of());
  }

  @Override
  public ResultSet getTypeInfo() {
    return localResult(
        List.of("TYPE_NAME", "DATA_TYPE", "PRECISION"),
        List.of(STRING, INT32, INT32),
        List.of(
            List.of("String", (long) java.sql.Types.VARCHAR, 0L),
            List.of("UInt64", (long) java.sql.Types.NUMERIC, 20L),
            List.of("Int64", (long) java.sql.Types.BIGINT, 20L),
            List.of("Float64", (long) java.sql.Types.DOUBLE, 0L),
            List.of("DateTime", (long) java.sql.Types.TIMESTAMP, 0L),
            List.of("Date", (long) java.sql.Types.DATE, 0L)));
  }

  @Override
  public ResultSet getIndexInfo(
      String catalog, String schema, String table, boolean unique, boolean approximate)
      throws SQLException {
    throw SqlExceptions.unsupported("Index metadata (ClickHouse indexes are not JDBC indexes)");
  }

  @Override
  public boolean supportsResultSetType(int type) {
    return type == ResultSet.TYPE_FORWARD_ONLY;
  }

  @Override
  public boolean supportsResultSetConcurrency(int type, int concurrency) {
    return type == ResultSet.TYPE_FORWARD_ONLY && concurrency == ResultSet.CONCUR_READ_ONLY;
  }

  @Override
  public boolean ownUpdatesAreVisible(int type) {
    return false;
  }

  @Override
  public boolean ownDeletesAreVisible(int type) {
    return false;
  }

  @Override
  public boolean ownInsertsAreVisible(int type) {
    return false;
  }

  @Override
  public boolean othersUpdatesAreVisible(int type) {
    return false;
  }

  @Override
  public boolean othersDeletesAreVisible(int type) {
    return false;
  }

  @Override
  public boolean othersInsertsAreVisible(int type) {
    return false;
  }

  @Override
  public boolean updatesAreDetected(int type) {
    return false;
  }

  @Override
  public boolean deletesAreDetected(int type) {
    return false;
  }

  @Override
  public boolean insertsAreDetected(int type) {
    return false;
  }

  @Override
  public boolean supportsBatchUpdates() {
    return true;
  }

  @Override
  public ResultSet getUDTs(
      String catalog, String schemaPattern, String typeNamePattern, int[] types) {
    return localResult(
        List.of("TYPE_CAT", "TYPE_SCHEM", "TYPE_NAME", "CLASS_NAME", "DATA_TYPE", "REMARKS"),
        List.of(NULLABLE_STRING, NULLABLE_STRING, STRING, STRING, INT32, STRING),
        List.of());
  }

  @Override
  public boolean supportsSavepoints() {
    return false;
  }

  @Override
  public boolean supportsNamedParameters() {
    return false;
  }

  @Override
  public boolean supportsMultipleOpenResults() {
    return false;
  }

  @Override
  public boolean supportsGetGeneratedKeys() {
    return false;
  }

  @Override
  public ResultSet getSuperTypes(String catalog, String schemaPattern, String typeNamePattern)
      throws SQLException {
    throw SqlExceptions.unsupported("Super type metadata");
  }

  @Override
  public ResultSet getSuperTables(String catalog, String schemaPattern, String tableNamePattern)
      throws SQLException {
    throw SqlExceptions.unsupported("Super table metadata");
  }

  @Override
  public ResultSet getAttributes(
      String catalog, String schemaPattern, String typeNamePattern, String attributeNamePattern)
      throws SQLException {
    throw SqlExceptions.unsupported("UDT attribute metadata");
  }

  @Override
  public boolean supportsResultSetHoldability(int holdability) {
    return holdability == ResultSet.CLOSE_CURSORS_AT_COMMIT;
  }

  @Override
  public int getResultSetHoldability() {
    return ResultSet.CLOSE_CURSORS_AT_COMMIT;
  }

  @Override
  public int getSQLStateType() {
    return sqlStateSQL;
  }

  @Override
  public boolean locatorsUpdateCopy() {
    return false;
  }

  @Override
  public boolean supportsStatementPooling() {
    return false;
  }

  @Override
  public RowIdLifetime getRowIdLifetime() {
    return RowIdLifetime.ROWID_UNSUPPORTED;
  }

  @Override
  public boolean supportsStoredFunctionsUsingCallSyntax() {
    return false;
  }

  @Override
  public boolean autoCommitFailureClosesAllResultSets() {
    return false;
  }

  @Override
  public ResultSet getClientInfoProperties() {
    return localResult(
        List.of("NAME", "MAX_LEN", "DEFAULT_VALUE", "DESCRIPTION"),
        List.of(STRING, INT32, STRING, STRING),
        List.of());
  }

  @Override
  public ResultSet getFunctions(String catalog, String schemaPattern, String functionNamePattern)
      throws SQLException {
    String sql =
        "SELECT name FROM system.functions WHERE name LIKE "
            + SqlText.quote(likeOrAll(functionNamePattern))
            + " ORDER BY name";
    List<List<Object>> raw = query(sql, 1);
    List<List<Object>> rows = new ArrayList<>();
    for (List<Object> function : raw) {
      List<Object> row = new ArrayList<>();
      row.add(null);
      row.add(null);
      row.add(function.get(0));
      row.add("");
      row.add((long) functionResultUnknown);
      row.add(function.get(0));
      rows.add(row);
    }
    return localResult(
        List.of(
            "FUNCTION_CAT",
            "FUNCTION_SCHEM",
            "FUNCTION_NAME",
            "REMARKS",
            "FUNCTION_TYPE",
            "SPECIFIC_NAME"),
        List.of(
            NULLABLE_STRING,
            NULLABLE_STRING,
            STRING,
            STRING,
            TypeParser.parse("Int16", 100, 8),
            STRING),
        rows);
  }

  @Override
  public ResultSet getFunctionColumns(
      String catalog, String schemaPattern, String functionNamePattern, String columnNamePattern)
      throws SQLException {
    throw SqlExceptions.unsupported("Function column metadata");
  }

  @Override
  public ResultSet getPseudoColumns(
      String catalog, String schemaPattern, String tableNamePattern, String columnNamePattern)
      throws SQLException {
    throw SqlExceptions.unsupported("Pseudo column metadata");
  }

  @Override
  public boolean generatedKeyAlwaysReturned() {
    return false;
  }

  @Override
  public <T> T unwrap(Class<T> iface) throws SQLException {
    if (iface.isInstance(this)) {
      return iface.cast(this);
    }
    throw new SQLException("Cannot unwrap to " + iface.getName(), "HY000");
  }

  @Override
  public boolean isWrapperFor(Class<?> iface) {
    return iface.isInstance(this);
  }
}
