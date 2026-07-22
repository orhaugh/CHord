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
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.time.Duration;

/**
 * A JDBC statement executing SQL text through the native protocol.
 *
 * <p>Result sets are forward only and read only, streaming blocks as the server sends them. One
 * result set may be open per statement; executing again closes the previous one, and closing the
 * statement closes its result set, both per the JDBC contract.
 */
class ChordStatement implements Statement {

  final ChordConnection connection;
  private ChordResultSet currentResultSet;
  private volatile long currentUpdateCount = -1;
  private volatile QueryResult inFlight;
  private volatile int queryTimeoutSeconds;
  private volatile long maxRows;
  private volatile boolean closed;
  private volatile boolean closeOnCompletion;

  ChordStatement(ChordConnection connection) {
    this.connection = connection;
    Duration defaultTimeout = connection.defaultQueryTimeout();
    this.queryTimeoutSeconds =
        defaultTimeout == null ? 0 : (int) Math.max(1, defaultTimeout.toSeconds());
  }

  void ensureOpen() throws SQLException {
    if (closed) {
      throw SqlExceptions.closed("statement");
    }
    if (connection.isClosed()) {
      throw SqlExceptions.closed("connection");
    }
  }

  QueryRequest.Builder requestFor(String sql) {
    QueryRequest.Builder builder = QueryRequest.builder(sql);
    if (queryTimeoutSeconds > 0) {
      builder.timeout(Duration.ofSeconds(queryTimeoutSeconds));
    }
    return builder;
  }

  @Override
  public ResultSet executeQuery(String sql) throws SQLException {
    return executeQueryInternal(sql);
  }

  final ResultSet executeQueryInternal(String sql) throws SQLException {
    executeInternal(sql);
    if (currentResultSet == null) {
      throw new SQLException("The statement produced no result set", "HY000");
    }
    return currentResultSet;
  }

  @Override
  public int executeUpdate(String sql) throws SQLException {
    long count = executeLargeUpdate(sql);
    return count > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) count;
  }

  @Override
  public long executeLargeUpdate(String sql) throws SQLException {
    return executeLargeUpdateInternal(sql);
  }

  final long executeLargeUpdateInternal(String sql) throws SQLException {
    executeInternal(sql);
    if (currentResultSet != null) {
      throw new SQLException("The statement produced a result set", "HY000");
    }
    return Math.max(0, currentUpdateCount);
  }

  @Override
  public boolean execute(String sql) throws SQLException {
    return executeInternal(sql);
  }

  final boolean executeInternal(String sql) throws SQLException {
    ensureOpen();
    closeCurrentResultSet();
    try {
      QueryResult result = connection.nativeConnection().query(requestFor(sql).build());
      inFlight = result;
      ChordResultSet resultSet = new ChordResultSet(this, result, maxRows);
      if (resultSet.hasSchema()) {
        currentResultSet = resultSet;
        currentUpdateCount = -1;
        return true;
      }
      // No schema header: a data free statement such as DDL or INSERT ... SELECT. The written
      // rows accumulate in the final progress, the closest JDBC update count ClickHouse has.
      resultSet.drainFully();
      currentResultSet = null;
      currentUpdateCount = resultSet.writtenRows();
      inFlight = null;
      return false;
    } catch (ChordException e) {
      inFlight = null;
      throw SqlExceptions.map(e);
    }
  }

  private void closeCurrentResultSet() throws SQLException {
    if (currentResultSet != null) {
      currentResultSet.close();
      currentResultSet = null;
    }
    currentUpdateCount = -1;
    inFlight = null;
  }

  /** Called by the result set when it fully drains or closes. */
  void resultSetFinished(ChordResultSet resultSet) {
    if (currentResultSet == resultSet) {
      inFlight = null;
      if (closeOnCompletion) {
        closed = true;
      }
    }
  }

  @Override
  public ResultSet getResultSet() throws SQLException {
    ensureOpen();
    return currentResultSet;
  }

  @Override
  public int getUpdateCount() throws SQLException {
    long count = getLargeUpdateCount();
    return count > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) count;
  }

  @Override
  public long getLargeUpdateCount() throws SQLException {
    ensureOpen();
    return currentResultSet != null ? -1 : currentUpdateCount;
  }

  @Override
  public boolean getMoreResults() throws SQLException {
    return getMoreResults(CLOSE_CURRENT_RESULT);
  }

  @Override
  public boolean getMoreResults(int current) throws SQLException {
    ensureOpen();
    closeCurrentResultSet();
    return false;
  }

  @Override
  public void close() throws SQLException {
    if (!closed) {
      closed = true;
      closeCurrentResultSet();
    }
  }

  @Override
  public boolean isClosed() {
    return closed;
  }

  @Override
  public void cancel() throws SQLException {
    QueryResult result = inFlight;
    if (result != null) {
      result.cancel();
    }
  }

  @Override
  public int getQueryTimeout() throws SQLException {
    ensureOpen();
    return queryTimeoutSeconds;
  }

  @Override
  public void setQueryTimeout(int seconds) throws SQLException {
    ensureOpen();
    if (seconds < 0) {
      throw new SQLException("Query timeout must not be negative", "HY000");
    }
    this.queryTimeoutSeconds = seconds;
  }

  @Override
  public int getMaxRows() throws SQLException {
    long rows = getLargeMaxRows();
    return rows > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) rows;
  }

  @Override
  public long getLargeMaxRows() throws SQLException {
    ensureOpen();
    return maxRows;
  }

  @Override
  public void setMaxRows(int max) throws SQLException {
    setLargeMaxRows(max);
  }

  @Override
  public void setLargeMaxRows(long max) throws SQLException {
    ensureOpen();
    if (max < 0) {
      throw new SQLException("Max rows must not be negative", "HY000");
    }
    this.maxRows = max;
  }

  @Override
  public void setEscapeProcessing(boolean enable) throws SQLException {
    ensureOpen();
    // JDBC escape sequences are passed through verbatim; ClickHouse SQL has no processor for
    // them, and rewriting SQL silently is worse than being explicit about it here.
  }

  @Override
  public int getMaxFieldSize() throws SQLException {
    ensureOpen();
    return 0;
  }

  @Override
  public void setMaxFieldSize(int max) throws SQLException {
    ensureOpen();
    if (max != 0) {
      throw SqlExceptions.unsupported("Truncating values with setMaxFieldSize");
    }
  }

  @Override
  public void setFetchDirection(int direction) throws SQLException {
    ensureOpen();
    if (direction != ResultSet.FETCH_FORWARD) {
      throw SqlExceptions.unsupported("Fetch directions other than FETCH_FORWARD");
    }
  }

  @Override
  public int getFetchDirection() throws SQLException {
    ensureOpen();
    return ResultSet.FETCH_FORWARD;
  }

  @Override
  public void setFetchSize(int rows) throws SQLException {
    ensureOpen();
    // A hint; block sizes are negotiated with the server through settings, not per fetch.
  }

  @Override
  public int getFetchSize() throws SQLException {
    ensureOpen();
    return 0;
  }

  @Override
  public int getResultSetConcurrency() {
    return ResultSet.CONCUR_READ_ONLY;
  }

  @Override
  public int getResultSetType() {
    return ResultSet.TYPE_FORWARD_ONLY;
  }

  @Override
  public int getResultSetHoldability() {
    return ResultSet.CLOSE_CURSORS_AT_COMMIT;
  }

  @Override
  public void addBatch(String sql) throws SQLException {
    throw SqlExceptions.unsupported(
        "Text statement batches (use a prepared INSERT for native block batching)");
  }

  @Override
  public void clearBatch() throws SQLException {
    ensureOpen();
  }

  @Override
  public int[] executeBatch() throws SQLException {
    throw SqlExceptions.unsupported(
        "Text statement batches (use a prepared INSERT for native block batching)");
  }

  @Override
  public Connection getConnection() {
    return connection;
  }

  @Override
  public ResultSet getGeneratedKeys() throws SQLException {
    throw SqlExceptions.unsupported("Generated keys");
  }

  @Override
  public int executeUpdate(String sql, int autoGeneratedKeys) throws SQLException {
    if (autoGeneratedKeys != NO_GENERATED_KEYS) {
      throw SqlExceptions.unsupported("Generated keys");
    }
    return executeUpdate(sql);
  }

  @Override
  public int executeUpdate(String sql, int[] columnIndexes) throws SQLException {
    throw SqlExceptions.unsupported("Generated keys");
  }

  @Override
  public int executeUpdate(String sql, String[] columnNames) throws SQLException {
    throw SqlExceptions.unsupported("Generated keys");
  }

  @Override
  public boolean execute(String sql, int autoGeneratedKeys) throws SQLException {
    if (autoGeneratedKeys != NO_GENERATED_KEYS) {
      throw SqlExceptions.unsupported("Generated keys");
    }
    return execute(sql);
  }

  @Override
  public boolean execute(String sql, int[] columnIndexes) throws SQLException {
    throw SqlExceptions.unsupported("Generated keys");
  }

  @Override
  public boolean execute(String sql, String[] columnNames) throws SQLException {
    throw SqlExceptions.unsupported("Generated keys");
  }

  @Override
  public SQLWarning getWarnings() throws SQLException {
    ensureOpen();
    return null;
  }

  @Override
  public void clearWarnings() throws SQLException {
    ensureOpen();
  }

  @Override
  public void setCursorName(String name) throws SQLException {
    throw SqlExceptions.unsupported("Named cursors");
  }

  @Override
  public void setPoolable(boolean poolable) throws SQLException {
    ensureOpen();
  }

  @Override
  public boolean isPoolable() throws SQLException {
    ensureOpen();
    return false;
  }

  @Override
  public void closeOnCompletion() throws SQLException {
    ensureOpen();
    closeOnCompletion = true;
  }

  @Override
  public boolean isCloseOnCompletion() throws SQLException {
    ensureOpen();
    return closeOnCompletion;
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
