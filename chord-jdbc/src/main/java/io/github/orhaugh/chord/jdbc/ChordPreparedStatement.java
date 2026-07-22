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
import io.github.orhaugh.chord.client.InsertStream;
import io.github.orhaugh.chord.codec.column.BlockBuilder;
import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.URL;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.NClob;
import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.Ref;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLXML;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;

/**
 * A prepared statement binding values into SQL.
 *
 * <p>Two execution paths exist. {@code INSERT INTO t [(cols)] VALUES (?, ...)} with only plain
 * placeholders batches through native blocks: {@link #addBatch()} accumulates rows and {@link
 * #executeBatch()} streams them through one INSERT with the server supplied schema validating every
 * value, which is both the fastest and the safest path. Every other statement substitutes bound
 * values as ClickHouse literals at the placeholder positions.
 */
final class ChordPreparedStatement extends ChordStatement implements PreparedStatement {

  private final List<String> fragments;
  private final Object[] parameters;
  private final boolean[] bound;
  private final String nativeInsertPrefix;
  private final List<Object[]> batch = new ArrayList<>();

  ChordPreparedStatement(ChordConnection connection, String sql) throws SQLException {
    super(connection);
    this.fragments = SqlText.splitAtPlaceholders(sql);
    this.parameters = new Object[fragments.size() - 1];
    this.bound = new boolean[parameters.length];
    this.nativeInsertPrefix = SqlText.nativeInsertPrefix(sql, parameters.length);
  }

  private void bind(int parameterIndex, Object value) throws SQLException {
    ensureOpen();
    if (parameterIndex < 1 || parameterIndex > parameters.length) {
      throw new SQLException(
          "Parameter index "
              + parameterIndex
              + " is out of range; the statement has "
              + parameters.length
              + " parameters",
          "07009");
    }
    parameters[parameterIndex - 1] = value;
    bound[parameterIndex - 1] = true;
  }

  private String substituted() throws SQLException {
    for (int i = 0; i < bound.length; i++) {
      if (!bound[i]) {
        throw new SQLException("Parameter " + (i + 1) + " is not bound", "07002");
      }
    }
    StringBuilder builder = new StringBuilder(fragments.get(0));
    for (int i = 0; i < parameters.length; i++) {
      builder.append(SqlText.literal(parameters[i]));
      builder.append(fragments.get(i + 1));
    }
    return builder.toString();
  }

  @Override
  public ResultSet executeQuery() throws SQLException {
    return executeQueryInternal(substituted());
  }

  @Override
  public int executeUpdate() throws SQLException {
    long count = executeLargeUpdate();
    return count > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) count;
  }

  @Override
  public long executeLargeUpdate() throws SQLException {
    if (nativeInsertPrefix != null) {
      // A single row through the same native path the batch uses keeps semantics identical.
      addBatch();
      long[] counts = executeLargeBatch();
      return counts.length == 1 ? counts[0] : Arrays.stream(counts).sum();
    }
    return executeLargeUpdateInternal(substituted());
  }

  @Override
  public boolean execute() throws SQLException {
    if (nativeInsertPrefix != null) {
      executeLargeUpdate();
      return false;
    }
    return executeInternal(substituted());
  }

  @Override
  public void addBatch() throws SQLException {
    ensureOpen();
    if (nativeInsertPrefix == null) {
      throw SqlExceptions.unsupported(
          "Batches on statements other than INSERT INTO ... VALUES (?, ...)");
    }
    for (int i = 0; i < bound.length; i++) {
      if (!bound[i]) {
        throw new SQLException("Parameter " + (i + 1) + " is not bound", "07002");
      }
    }
    batch.add(parameters.clone());
  }

  @Override
  public void clearBatch() throws SQLException {
    ensureOpen();
    batch.clear();
  }

  @Override
  public int[] executeBatch() throws SQLException {
    long[] large = executeLargeBatch();
    int[] counts = new int[large.length];
    for (int i = 0; i < large.length; i++) {
      counts[i] = large[i] > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) large[i];
    }
    return counts;
  }

  @Override
  public long[] executeLargeBatch() throws SQLException {
    ensureOpen();
    if (nativeInsertPrefix == null) {
      throw SqlExceptions.unsupported(
          "Batches on statements other than INSERT INTO ... VALUES (?, ...)");
    }
    if (batch.isEmpty()) {
      return new long[0];
    }
    List<Object[]> rows = new ArrayList<>(batch);
    batch.clear();
    try (InsertStream insert =
        connection.nativeConnection().insert(requestFor(nativeInsertPrefix).build())) {
      BlockBuilder builder = insert.newBlock();
      for (Object[] row : rows) {
        builder.addRow(row);
      }
      insert.send(builder.build());
      insert.finish();
      long[] counts = new long[rows.size()];
      Arrays.fill(counts, 1);
      return counts;
    } catch (ChordException e) {
      throw SqlExceptions.map(e);
    }
  }

  @Override
  public void clearParameters() throws SQLException {
    ensureOpen();
    Arrays.fill(parameters, null);
    Arrays.fill(bound, false);
  }

  @Override
  public ResultSetMetaData getMetaData() throws SQLException {
    ensureOpen();
    ResultSet current = getResultSet();
    return current != null ? current.getMetaData() : null;
  }

  @Override
  public ParameterMetaData getParameterMetaData() throws SQLException {
    ensureOpen();
    return new ChordParameterMetaData(parameters.length);
  }

  // Setters.

  @Override
  public void setNull(int parameterIndex, int sqlType) throws SQLException {
    bind(parameterIndex, null);
  }

  @Override
  public void setNull(int parameterIndex, int sqlType, String typeName) throws SQLException {
    bind(parameterIndex, null);
  }

  @Override
  public void setBoolean(int parameterIndex, boolean x) throws SQLException {
    bind(parameterIndex, x);
  }

  @Override
  public void setByte(int parameterIndex, byte x) throws SQLException {
    bind(parameterIndex, x);
  }

  @Override
  public void setShort(int parameterIndex, short x) throws SQLException {
    bind(parameterIndex, x);
  }

  @Override
  public void setInt(int parameterIndex, int x) throws SQLException {
    bind(parameterIndex, x);
  }

  @Override
  public void setLong(int parameterIndex, long x) throws SQLException {
    bind(parameterIndex, x);
  }

  @Override
  public void setFloat(int parameterIndex, float x) throws SQLException {
    bind(parameterIndex, x);
  }

  @Override
  public void setDouble(int parameterIndex, double x) throws SQLException {
    bind(parameterIndex, x);
  }

  @Override
  public void setBigDecimal(int parameterIndex, BigDecimal x) throws SQLException {
    bind(parameterIndex, x);
  }

  @Override
  public void setString(int parameterIndex, String x) throws SQLException {
    bind(parameterIndex, x);
  }

  @Override
  public void setBytes(int parameterIndex, byte[] x) throws SQLException {
    bind(parameterIndex, x == null ? null : x.clone());
  }

  @Override
  public void setDate(int parameterIndex, Date x) throws SQLException {
    bind(parameterIndex, x == null ? null : x.toLocalDate());
  }

  @Override
  public void setDate(int parameterIndex, Date x, Calendar cal) throws SQLException {
    setDate(parameterIndex, x);
  }

  @Override
  public void setTime(int parameterIndex, Time x) throws SQLException {
    throw SqlExceptions.unsupported("TIME parameters");
  }

  @Override
  public void setTime(int parameterIndex, Time x, Calendar cal) throws SQLException {
    throw SqlExceptions.unsupported("TIME parameters");
  }

  @Override
  public void setTimestamp(int parameterIndex, Timestamp x) throws SQLException {
    bind(parameterIndex, x == null ? null : x.toInstant());
  }

  @Override
  public void setTimestamp(int parameterIndex, Timestamp x, Calendar cal) throws SQLException {
    setTimestamp(parameterIndex, x);
  }

  @Override
  public void setObject(int parameterIndex, Object x) throws SQLException {
    bind(parameterIndex, x);
  }

  @Override
  public void setObject(int parameterIndex, Object x, int targetSqlType) throws SQLException {
    if (x == null || targetSqlType == Types.NULL) {
      bind(parameterIndex, null);
      return;
    }
    bind(parameterIndex, x);
  }

  @Override
  public void setObject(int parameterIndex, Object x, int targetSqlType, int scaleOrLength)
      throws SQLException {
    setObject(parameterIndex, x, targetSqlType);
  }

  @Override
  public void setArray(int parameterIndex, Array x) throws SQLException {
    if (x == null) {
      bind(parameterIndex, null);
      return;
    }
    Object values = x.getArray();
    bind(parameterIndex, values instanceof Object[] array ? List.of(array) : values);
  }

  @Override
  public void setURL(int parameterIndex, URL x) throws SQLException {
    bind(parameterIndex, x == null ? null : x.toString());
  }

  // Unsupported parameter shapes.

  @Override
  public void setAsciiStream(int parameterIndex, InputStream x, int length) throws SQLException {
    throw SqlExceptions.unsupported("Stream parameters");
  }

  @Override
  @Deprecated
  public void setUnicodeStream(int parameterIndex, InputStream x, int length) throws SQLException {
    throw SqlExceptions.unsupported("Stream parameters");
  }

  @Override
  public void setBinaryStream(int parameterIndex, InputStream x, int length) throws SQLException {
    throw SqlExceptions.unsupported("Stream parameters");
  }

  @Override
  public void setAsciiStream(int parameterIndex, InputStream x, long length) throws SQLException {
    throw SqlExceptions.unsupported("Stream parameters");
  }

  @Override
  public void setBinaryStream(int parameterIndex, InputStream x, long length) throws SQLException {
    throw SqlExceptions.unsupported("Stream parameters");
  }

  @Override
  public void setAsciiStream(int parameterIndex, InputStream x) throws SQLException {
    throw SqlExceptions.unsupported("Stream parameters");
  }

  @Override
  public void setBinaryStream(int parameterIndex, InputStream x) throws SQLException {
    throw SqlExceptions.unsupported("Stream parameters");
  }

  @Override
  public void setCharacterStream(int parameterIndex, Reader reader, int length)
      throws SQLException {
    throw SqlExceptions.unsupported("Stream parameters");
  }

  @Override
  public void setCharacterStream(int parameterIndex, Reader reader, long length)
      throws SQLException {
    throw SqlExceptions.unsupported("Stream parameters");
  }

  @Override
  public void setCharacterStream(int parameterIndex, Reader reader) throws SQLException {
    throw SqlExceptions.unsupported("Stream parameters");
  }

  @Override
  public void setNCharacterStream(int parameterIndex, Reader value, long length)
      throws SQLException {
    throw SqlExceptions.unsupported("Stream parameters");
  }

  @Override
  public void setNCharacterStream(int parameterIndex, Reader value) throws SQLException {
    throw SqlExceptions.unsupported("Stream parameters");
  }

  @Override
  public void setRef(int parameterIndex, Ref x) throws SQLException {
    throw SqlExceptions.unsupported("REF parameters");
  }

  @Override
  public void setBlob(int parameterIndex, Blob x) throws SQLException {
    throw SqlExceptions.unsupported("BLOB parameters");
  }

  @Override
  public void setBlob(int parameterIndex, InputStream inputStream, long length)
      throws SQLException {
    throw SqlExceptions.unsupported("BLOB parameters");
  }

  @Override
  public void setBlob(int parameterIndex, InputStream inputStream) throws SQLException {
    throw SqlExceptions.unsupported("BLOB parameters");
  }

  @Override
  public void setClob(int parameterIndex, Clob x) throws SQLException {
    throw SqlExceptions.unsupported("CLOB parameters");
  }

  @Override
  public void setClob(int parameterIndex, Reader reader, long length) throws SQLException {
    throw SqlExceptions.unsupported("CLOB parameters");
  }

  @Override
  public void setClob(int parameterIndex, Reader reader) throws SQLException {
    throw SqlExceptions.unsupported("CLOB parameters");
  }

  @Override
  public void setNClob(int parameterIndex, NClob value) throws SQLException {
    throw SqlExceptions.unsupported("NCLOB parameters");
  }

  @Override
  public void setNClob(int parameterIndex, Reader reader, long length) throws SQLException {
    throw SqlExceptions.unsupported("NCLOB parameters");
  }

  @Override
  public void setNClob(int parameterIndex, Reader reader) throws SQLException {
    throw SqlExceptions.unsupported("NCLOB parameters");
  }

  @Override
  public void setSQLXML(int parameterIndex, SQLXML xmlObject) throws SQLException {
    throw SqlExceptions.unsupported("SQLXML parameters");
  }

  @Override
  public void setRowId(int parameterIndex, RowId x) throws SQLException {
    throw SqlExceptions.unsupported("ROWID parameters");
  }

  @Override
  public void setNString(int parameterIndex, String value) throws SQLException {
    setString(parameterIndex, value);
  }

  // The text execution methods on the base class must not run with pending binds.

  @Override
  public ResultSet executeQuery(String sql) throws SQLException {
    throw new SQLException(
        "executeQuery(String) is not valid on a PreparedStatement; use executeQuery()", "HY000");
  }

  @Override
  public boolean execute(String sql) throws SQLException {
    throw new SQLException(
        "execute(String) is not valid on a PreparedStatement; use execute()", "HY000");
  }

  @Override
  public int executeUpdate(String sql) throws SQLException {
    throw new SQLException(
        "executeUpdate(String) is not valid on a PreparedStatement; use executeUpdate()", "HY000");
  }

  /** Parameter metadata limited to what the driver knows without a server round trip. */
  private static final class ChordParameterMetaData implements ParameterMetaData {
    private final int count;

    ChordParameterMetaData(int count) {
      this.count = count;
    }

    private void checkIndex(int param) throws SQLException {
      if (param < 1 || param > count) {
        throw new SQLException("Parameter index " + param + " is out of range", "07009");
      }
    }

    @Override
    public int getParameterCount() {
      return count;
    }

    @Override
    public int isNullable(int param) throws SQLException {
      checkIndex(param);
      return parameterNullableUnknown;
    }

    @Override
    public boolean isSigned(int param) throws SQLException {
      throw SqlExceptions.unsupported("Parameter type introspection");
    }

    @Override
    public int getPrecision(int param) throws SQLException {
      throw SqlExceptions.unsupported("Parameter type introspection");
    }

    @Override
    public int getScale(int param) throws SQLException {
      throw SqlExceptions.unsupported("Parameter type introspection");
    }

    @Override
    public int getParameterType(int param) throws SQLException {
      throw SqlExceptions.unsupported("Parameter type introspection");
    }

    @Override
    public String getParameterTypeName(int param) throws SQLException {
      throw SqlExceptions.unsupported("Parameter type introspection");
    }

    @Override
    public String getParameterClassName(int param) throws SQLException {
      throw SqlExceptions.unsupported("Parameter type introspection");
    }

    @Override
    public int getParameterMode(int param) throws SQLException {
      checkIndex(param);
      return parameterModeIn;
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
}
