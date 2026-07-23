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
import io.github.orhaugh.chord.client.QueryResult;
import io.github.orhaugh.chord.codec.block.Block;
import io.github.orhaugh.chord.codec.column.Column;
import io.github.orhaugh.chord.codec.column.Columns;
import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Calendar;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * A forward only, read only result set streaming native blocks.
 *
 * <p>Getter coercions are lossless: a value that does not fit the requested Java type raises a
 * numeric range error instead of truncating silently.
 */
final class ChordResultSet implements ResultSet {

  private final ChordStatement statement;
  private final QueryResult result;
  private final java.util.Iterator<Block> localBlocks;
  private final Block header;
  private final long maxRows;

  private Block currentBlock;
  private int rowInBlock = -1;
  private long absoluteRow;
  private boolean afterLast;
  private boolean wasNull;
  private boolean closed;

  ChordResultSet(ChordStatement statement, QueryResult result, long maxRows) {
    this.statement = statement;
    this.result = result;
    this.localBlocks = null;
    this.header = result.header().orElse(null);
    this.maxRows = maxRows;
  }

  /** A result set over locally built blocks, used by metadata queries. */
  ChordResultSet(Block header, List<Block> blocks) {
    this.statement = null;
    this.result = null;
    this.localBlocks = blocks.iterator();
    this.header = header;
    this.maxRows = 0;
  }

  boolean hasSchema() {
    return header != null;
  }

  /** Consumes the stream to its end, for statements without a result. */
  void drainFully() throws SQLException {
    try {
      while (result.nextBlock().isPresent()) {
        // Discard; data free statements produce no rows.
      }
      result.close();
      statement.resultSetFinished(this);
    } catch (ChordException e) {
      throw SqlExceptions.map(e);
    }
  }

  long writtenRows() {
    return result.totalProgress().writtenRows();
  }

  private void ensureOpen() throws SQLException {
    if (closed) {
      throw SqlExceptions.closed("result set");
    }
  }

  @Override
  public boolean next() throws SQLException {
    ensureOpen();
    if (afterLast) {
      return false;
    }
    if (maxRows > 0 && absoluteRow >= maxRows) {
      afterLast = true;
      return false;
    }
    try {
      if (currentBlock != null && rowInBlock + 1 < currentBlock.rows()) {
        rowInBlock++;
        absoluteRow++;
        return true;
      }
      Optional<Block> next =
          result != null
              ? result.nextBlock()
              : localBlocks.hasNext() ? Optional.of(localBlocks.next()) : Optional.empty();
      if (next.isEmpty()) {
        afterLast = true;
        if (statement != null) {
          statement.resultSetFinished(this);
        }
        return false;
      }
      currentBlock = next.get();
      rowInBlock = 0;
      absoluteRow++;
      return true;
    } catch (ChordException e) {
      throw SqlExceptions.map(e);
    }
  }

  @Override
  public void close() throws SQLException {
    if (!closed) {
      closed = true;
      if (result == null) {
        return;
      }
      try {
        result.close();
      } catch (ChordException e) {
        throw SqlExceptions.map(e);
      } finally {
        if (statement != null) {
          statement.resultSetFinished(this);
        }
      }
    }
  }

  @Override
  public boolean isClosed() {
    return closed;
  }

  private Column columnAt(int columnIndex) throws SQLException {
    ensureOpen();
    if (currentBlock == null || rowInBlock < 0) {
      throw new SQLException("The cursor is not positioned on a row", "24000");
    }
    if (columnIndex < 1 || columnIndex > currentBlock.columnCount()) {
      throw new SQLException("Column index " + columnIndex + " is out of range", "07009");
    }
    return currentBlock.column(columnIndex - 1);
  }

  private Object valueAt(int columnIndex) throws SQLException {
    Column column = columnAt(columnIndex);
    Object value = column.isNullAt(rowInBlock) ? null : column.objectAt(rowInBlock);
    wasNull = value == null;
    return value;
  }

  @Override
  public boolean wasNull() throws SQLException {
    ensureOpen();
    return wasNull;
  }

  @Override
  public int findColumn(String columnLabel) throws SQLException {
    ensureOpen();
    Block source = currentBlock != null ? currentBlock : header;
    if (source == null) {
      throw new SQLException("The result set has no columns", "07009");
    }
    for (int i = 0; i < source.columnCount(); i++) {
      if (source.columnName(i).equals(columnLabel)) {
        return i + 1;
      }
    }
    for (int i = 0; i < source.columnCount(); i++) {
      if (source.columnName(i).equalsIgnoreCase(columnLabel)) {
        return i + 1;
      }
    }
    throw new SQLException("Unknown column \"" + columnLabel + "\"", "42S22");
  }

  @Override
  public ResultSetMetaData getMetaData() throws SQLException {
    ensureOpen();
    Block source = header != null ? header : currentBlock;
    if (source == null) {
      throw new SQLException("The result set has no columns", "HY000");
    }
    return new ChordResultSetMetaData(source);
  }

  // Typed getters.

  @Override
  public String getString(int columnIndex) throws SQLException {
    Object value = valueAt(columnIndex);
    if (value == null) {
      return null;
    }
    if (value instanceof byte[] bytes) {
      return new String(bytes, StandardCharsets.UTF_8);
    }
    return String.valueOf(value);
  }

  @Override
  public boolean getBoolean(int columnIndex) throws SQLException {
    Object value = valueAt(columnIndex);
    return switch (value) {
      case null -> false;
      case Boolean b -> b;
      case Number n -> n.longValue() != 0;
      case String s -> s.equals("1") || s.equalsIgnoreCase("true");
      default -> throw conversion(value, "boolean");
    };
  }

  private long longValue(Object value, long min, long max) throws SQLException {
    long v =
        switch (value) {
          case Boolean b -> b ? 1 : 0;
          case BigInteger big -> {
            try {
              yield big.longValueExact();
            } catch (ArithmeticException e) {
              throw outOfRange(big);
            }
          }
          case BigDecimal decimal -> {
            try {
              yield decimal.setScale(0, RoundingMode.UNNECESSARY).longValueExact();
            } catch (ArithmeticException e) {
              throw outOfRange(decimal);
            }
          }
          case Number n -> n.longValue();
          case String s -> {
            try {
              yield Long.parseLong(s.trim());
            } catch (NumberFormatException e) {
              throw conversion(s, "a number");
            }
          }
          default -> throw conversion(value, "a number");
        };
    if (v < min || v > max) {
      throw outOfRange(v);
    }
    return v;
  }

  private static SQLException conversion(Object value, String target) {
    return new SQLException(
        "Cannot convert " + value.getClass().getSimpleName() + " value to " + target, "22018");
  }

  private static SQLException outOfRange(Object value) {
    return new SQLException("Value " + value + " is out of range for the requested type", "22003");
  }

  @Override
  public byte getByte(int columnIndex) throws SQLException {
    Object value = valueAt(columnIndex);
    return value == null ? 0 : (byte) longValue(value, Byte.MIN_VALUE, Byte.MAX_VALUE);
  }

  @Override
  public short getShort(int columnIndex) throws SQLException {
    Object value = valueAt(columnIndex);
    return value == null ? 0 : (short) longValue(value, Short.MIN_VALUE, Short.MAX_VALUE);
  }

  @Override
  public int getInt(int columnIndex) throws SQLException {
    Object value = valueAt(columnIndex);
    return value == null ? 0 : (int) longValue(value, Integer.MIN_VALUE, Integer.MAX_VALUE);
  }

  @Override
  public long getLong(int columnIndex) throws SQLException {
    Object value = valueAt(columnIndex);
    return value == null ? 0 : longValue(value, Long.MIN_VALUE, Long.MAX_VALUE);
  }

  @Override
  public float getFloat(int columnIndex) throws SQLException {
    Object value = valueAt(columnIndex);
    return switch (value) {
      case null -> 0f;
      case Number n -> n.floatValue();
      case String s -> Float.parseFloat(s.trim());
      default -> throw conversion(value, "float");
    };
  }

  @Override
  public double getDouble(int columnIndex) throws SQLException {
    Object value = valueAt(columnIndex);
    return switch (value) {
      case null -> 0d;
      case Number n -> n.doubleValue();
      case String s -> Double.parseDouble(s.trim());
      default -> throw conversion(value, "double");
    };
  }

  @Override
  public BigDecimal getBigDecimal(int columnIndex) throws SQLException {
    Object value = valueAt(columnIndex);
    return switch (value) {
      case null -> null;
      case BigDecimal decimal -> decimal;
      case BigInteger big -> new BigDecimal(big);
      case Double d -> BigDecimal.valueOf(d);
      case Float f -> BigDecimal.valueOf(f);
      case Number n -> BigDecimal.valueOf(n.longValue());
      case String s -> new BigDecimal(s.trim());
      default -> throw conversion(value, "BigDecimal");
    };
  }

  @Override
  @Deprecated
  public BigDecimal getBigDecimal(int columnIndex, int scale) throws SQLException {
    BigDecimal value = getBigDecimal(columnIndex);
    return value == null ? null : value.setScale(scale, RoundingMode.HALF_UP);
  }

  @Override
  public byte[] getBytes(int columnIndex) throws SQLException {
    Column column = columnAt(columnIndex);
    if (column.isNullAt(rowInBlock)) {
      wasNull = true;
      return null;
    }
    wasNull = false;
    if (column instanceof Columns.StringColumn strings) {
      return strings.bytesAt(rowInBlock);
    }
    if (column instanceof Columns.FixedStringColumn fixed) {
      return fixed.bytesAt(rowInBlock);
    }
    Object value = column.objectAt(rowInBlock);
    if (value instanceof byte[] bytes) {
      return bytes.clone();
    }
    throw conversion(value, "byte[]");
  }

  @Override
  public Date getDate(int columnIndex) throws SQLException {
    Object value = valueAt(columnIndex);
    return switch (value) {
      case null -> null;
      case LocalDate date -> Date.valueOf(date);
      case Instant instant -> Date.valueOf(instant.atZone(ZoneId.systemDefault()).toLocalDate());
      default -> throw conversion(value, "Date");
    };
  }

  @Override
  public Timestamp getTimestamp(int columnIndex) throws SQLException {
    Object value = valueAt(columnIndex);
    return switch (value) {
      case null -> null;
      case Instant instant -> Timestamp.from(instant);
      case LocalDate date -> Timestamp.valueOf(date.atStartOfDay());
      default -> throw conversion(value, "Timestamp");
    };
  }

  @Override
  public Time getTime(int columnIndex) throws SQLException {
    throw SqlExceptions.unsupported(
        "getTime (ClickHouse Time values range past a day; use getObject(column, Duration.class))");
  }

  @Override
  public Object getObject(int columnIndex) throws SQLException {
    return valueAt(columnIndex);
  }

  @Override
  public <T> T getObject(int columnIndex, Class<T> type) throws SQLException {
    if (type == null) {
      throw new SQLException("type must not be null", "HY000");
    }
    Object converted;
    if (type == String.class) {
      converted = getString(columnIndex);
    } else if (type == Boolean.class) {
      converted = wasNullOr(getBoolean(columnIndex));
    } else if (type == Integer.class) {
      converted = wasNullOr(getInt(columnIndex));
    } else if (type == Long.class) {
      converted = wasNullOr(getLong(columnIndex));
    } else if (type == Double.class) {
      converted = wasNullOr(getDouble(columnIndex));
    } else if (type == Float.class) {
      converted = wasNullOr(getFloat(columnIndex));
    } else if (type == BigDecimal.class) {
      converted = getBigDecimal(columnIndex);
    } else if (type == byte[].class) {
      converted = getBytes(columnIndex);
    } else if (type == Date.class) {
      converted = getDate(columnIndex);
    } else if (type == Timestamp.class) {
      converted = getTimestamp(columnIndex);
    } else if (type == LocalDate.class
        || type == Instant.class
        || type == java.time.Duration.class
        || type == UUID.class
        || type == BigInteger.class
        || type == InetAddress.class
        || type == List.class
        || type == Map.class) {
      Object raw = valueAt(columnIndex);
      if (raw != null && !type.isInstance(raw)) {
        throw conversion(raw, type.getSimpleName());
      }
      converted = raw;
    } else {
      throw SqlExceptions.unsupported("getObject conversion to " + type.getName());
    }
    return type.cast(converted);
  }

  private Object wasNullOr(Object primitive) throws SQLException {
    return wasNull() ? null : primitive;
  }

  @Override
  public java.sql.Array getArray(int columnIndex) throws SQLException {
    Object value = valueAt(columnIndex);
    if (value == null) {
      return null;
    }
    if (value instanceof List<?> list) {
      return new ChordArray(list);
    }
    throw conversion(value, "Array");
  }

  // Label based getters delegate to index based ones.

  @Override
  public String getString(String columnLabel) throws SQLException {
    return getString(findColumn(columnLabel));
  }

  @Override
  public boolean getBoolean(String columnLabel) throws SQLException {
    return getBoolean(findColumn(columnLabel));
  }

  @Override
  public byte getByte(String columnLabel) throws SQLException {
    return getByte(findColumn(columnLabel));
  }

  @Override
  public short getShort(String columnLabel) throws SQLException {
    return getShort(findColumn(columnLabel));
  }

  @Override
  public int getInt(String columnLabel) throws SQLException {
    return getInt(findColumn(columnLabel));
  }

  @Override
  public long getLong(String columnLabel) throws SQLException {
    return getLong(findColumn(columnLabel));
  }

  @Override
  public float getFloat(String columnLabel) throws SQLException {
    return getFloat(findColumn(columnLabel));
  }

  @Override
  public double getDouble(String columnLabel) throws SQLException {
    return getDouble(findColumn(columnLabel));
  }

  @Override
  public BigDecimal getBigDecimal(String columnLabel) throws SQLException {
    return getBigDecimal(findColumn(columnLabel));
  }

  @Override
  @Deprecated
  public BigDecimal getBigDecimal(String columnLabel, int scale) throws SQLException {
    return getBigDecimal(findColumn(columnLabel), scale);
  }

  @Override
  public byte[] getBytes(String columnLabel) throws SQLException {
    return getBytes(findColumn(columnLabel));
  }

  @Override
  public Date getDate(String columnLabel) throws SQLException {
    return getDate(findColumn(columnLabel));
  }

  @Override
  public Time getTime(String columnLabel) throws SQLException {
    return getTime(findColumn(columnLabel));
  }

  @Override
  public Timestamp getTimestamp(String columnLabel) throws SQLException {
    return getTimestamp(findColumn(columnLabel));
  }

  @Override
  public Object getObject(String columnLabel) throws SQLException {
    return getObject(findColumn(columnLabel));
  }

  @Override
  public <T> T getObject(String columnLabel, Class<T> type) throws SQLException {
    return getObject(findColumn(columnLabel), type);
  }

  @Override
  public java.sql.Array getArray(String columnLabel) throws SQLException {
    return getArray(findColumn(columnLabel));
  }

  @Override
  public Date getDate(int columnIndex, Calendar cal) throws SQLException {
    return getDate(columnIndex);
  }

  @Override
  public Date getDate(String columnLabel, Calendar cal) throws SQLException {
    return getDate(columnLabel);
  }

  @Override
  public Time getTime(int columnIndex, Calendar cal) throws SQLException {
    return getTime(columnIndex);
  }

  @Override
  public Time getTime(String columnLabel, Calendar cal) throws SQLException {
    return getTime(columnLabel);
  }

  @Override
  public Timestamp getTimestamp(int columnIndex, Calendar cal) throws SQLException {
    return getTimestamp(columnIndex);
  }

  @Override
  public Timestamp getTimestamp(String columnLabel, Calendar cal) throws SQLException {
    return getTimestamp(columnLabel);
  }

  // Cursor state.

  @Override
  public boolean isBeforeFirst() throws SQLException {
    ensureOpen();
    return absoluteRow == 0 && !afterLast;
  }

  @Override
  public boolean isAfterLast() throws SQLException {
    ensureOpen();
    return afterLast;
  }

  @Override
  public boolean isFirst() throws SQLException {
    ensureOpen();
    return absoluteRow == 1 && !afterLast;
  }

  @Override
  public boolean isLast() throws SQLException {
    throw SqlExceptions.unsupported("isLast on a streaming result set");
  }

  @Override
  public int getRow() throws SQLException {
    ensureOpen();
    return afterLast || absoluteRow > Integer.MAX_VALUE ? 0 : (int) absoluteRow;
  }

  @Override
  public int getType() {
    return ResultSet.TYPE_FORWARD_ONLY;
  }

  @Override
  public int getConcurrency() {
    return ResultSet.CONCUR_READ_ONLY;
  }

  @Override
  public int getHoldability() {
    return ResultSet.CLOSE_CURSORS_AT_COMMIT;
  }

  @Override
  public Statement getStatement() {
    return statement;
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
  public String getCursorName() throws SQLException {
    throw SqlExceptions.unsupported("Named cursors");
  }

  @Override
  public void setFetchDirection(int direction) throws SQLException {
    if (direction != ResultSet.FETCH_FORWARD) {
      throw SqlExceptions.unsupported("Fetch directions other than FETCH_FORWARD");
    }
  }

  @Override
  public int getFetchDirection() {
    return ResultSet.FETCH_FORWARD;
  }

  @Override
  public void setFetchSize(int rows) throws SQLException {
    ensureOpen();
  }

  @Override
  public int getFetchSize() {
    return 0;
  }

  // Everything below is either scrolling or mutation, neither of which a streaming read only
  // result set can honestly provide.

  @Override
  public void beforeFirst() throws SQLException {
    throw SqlExceptions.unsupported("Scrolling");
  }

  @Override
  public void afterLast() throws SQLException {
    throw SqlExceptions.unsupported("Scrolling");
  }

  @Override
  public boolean first() throws SQLException {
    throw SqlExceptions.unsupported("Scrolling");
  }

  @Override
  public boolean last() throws SQLException {
    throw SqlExceptions.unsupported("Scrolling");
  }

  @Override
  public boolean absolute(int row) throws SQLException {
    throw SqlExceptions.unsupported("Scrolling");
  }

  @Override
  public boolean relative(int rows) throws SQLException {
    throw SqlExceptions.unsupported("Scrolling");
  }

  @Override
  public boolean previous() throws SQLException {
    throw SqlExceptions.unsupported("Scrolling");
  }

  @Override
  public boolean rowUpdated() throws SQLException {
    throw SqlExceptions.unsupported("Updatable result sets");
  }

  @Override
  public boolean rowInserted() throws SQLException {
    throw SqlExceptions.unsupported("Updatable result sets");
  }

  @Override
  public boolean rowDeleted() throws SQLException {
    throw SqlExceptions.unsupported("Updatable result sets");
  }

  @Override
  public void insertRow() throws SQLException {
    throw SqlExceptions.unsupported("Updatable result sets");
  }

  @Override
  public void updateRow() throws SQLException {
    throw SqlExceptions.unsupported("Updatable result sets");
  }

  @Override
  public void deleteRow() throws SQLException {
    throw SqlExceptions.unsupported("Updatable result sets");
  }

  @Override
  public void refreshRow() throws SQLException {
    throw SqlExceptions.unsupported("Updatable result sets");
  }

  @Override
  public void cancelRowUpdates() throws SQLException {
    throw SqlExceptions.unsupported("Updatable result sets");
  }

  @Override
  public void moveToInsertRow() throws SQLException {
    throw SqlExceptions.unsupported("Updatable result sets");
  }

  @Override
  public void moveToCurrentRow() throws SQLException {
    throw SqlExceptions.unsupported("Updatable result sets");
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

  // Stream and LOB getters that a columnar analytical database has no representation for.

  @Override
  public InputStream getAsciiStream(int columnIndex) throws SQLException {
    throw SqlExceptions.unsupported("Stream getters");
  }

  @Override
  @Deprecated
  public InputStream getUnicodeStream(int columnIndex) throws SQLException {
    throw SqlExceptions.unsupported("Stream getters");
  }

  @Override
  public InputStream getBinaryStream(int columnIndex) throws SQLException {
    throw SqlExceptions.unsupported("Stream getters");
  }

  @Override
  public InputStream getAsciiStream(String columnLabel) throws SQLException {
    throw SqlExceptions.unsupported("Stream getters");
  }

  @Override
  @Deprecated
  public InputStream getUnicodeStream(String columnLabel) throws SQLException {
    throw SqlExceptions.unsupported("Stream getters");
  }

  @Override
  public InputStream getBinaryStream(String columnLabel) throws SQLException {
    throw SqlExceptions.unsupported("Stream getters");
  }

  @Override
  public Reader getCharacterStream(int columnIndex) throws SQLException {
    throw SqlExceptions.unsupported("Stream getters");
  }

  @Override
  public Reader getCharacterStream(String columnLabel) throws SQLException {
    throw SqlExceptions.unsupported("Stream getters");
  }

  @Override
  public java.sql.Ref getRef(int columnIndex) throws SQLException {
    throw SqlExceptions.unsupported("REF values");
  }

  @Override
  public java.sql.Ref getRef(String columnLabel) throws SQLException {
    throw SqlExceptions.unsupported("REF values");
  }

  @Override
  public java.sql.Blob getBlob(int columnIndex) throws SQLException {
    throw SqlExceptions.unsupported("BLOB values");
  }

  @Override
  public java.sql.Blob getBlob(String columnLabel) throws SQLException {
    throw SqlExceptions.unsupported("BLOB values");
  }

  @Override
  public java.sql.Clob getClob(int columnIndex) throws SQLException {
    throw SqlExceptions.unsupported("CLOB values");
  }

  @Override
  public java.sql.Clob getClob(String columnLabel) throws SQLException {
    throw SqlExceptions.unsupported("CLOB values");
  }

  @Override
  public Object getObject(int columnIndex, Map<String, Class<?>> map) throws SQLException {
    throw SqlExceptions.unsupported("Custom type maps");
  }

  @Override
  public Object getObject(String columnLabel, Map<String, Class<?>> map) throws SQLException {
    throw SqlExceptions.unsupported("Custom type maps");
  }

  @Override
  public java.net.URL getURL(int columnIndex) throws SQLException {
    throw SqlExceptions.unsupported("URL values");
  }

  @Override
  public java.net.URL getURL(String columnLabel) throws SQLException {
    throw SqlExceptions.unsupported("URL values");
  }

  @Override
  public java.sql.RowId getRowId(int columnIndex) throws SQLException {
    throw SqlExceptions.unsupported("ROWID values");
  }

  @Override
  public java.sql.RowId getRowId(String columnLabel) throws SQLException {
    throw SqlExceptions.unsupported("ROWID values");
  }

  @Override
  public java.sql.NClob getNClob(int columnIndex) throws SQLException {
    throw SqlExceptions.unsupported("NCLOB values");
  }

  @Override
  public java.sql.NClob getNClob(String columnLabel) throws SQLException {
    throw SqlExceptions.unsupported("NCLOB values");
  }

  @Override
  public java.sql.SQLXML getSQLXML(int columnIndex) throws SQLException {
    throw SqlExceptions.unsupported("SQLXML values");
  }

  @Override
  public java.sql.SQLXML getSQLXML(String columnLabel) throws SQLException {
    throw SqlExceptions.unsupported("SQLXML values");
  }

  @Override
  public String getNString(int columnIndex) throws SQLException {
    return getString(columnIndex);
  }

  @Override
  public String getNString(String columnLabel) throws SQLException {
    return getString(columnLabel);
  }

  @Override
  public Reader getNCharacterStream(int columnIndex) throws SQLException {
    throw SqlExceptions.unsupported("Stream getters");
  }

  @Override
  public Reader getNCharacterStream(String columnLabel) throws SQLException {
    throw SqlExceptions.unsupported("Stream getters");
  }

  // The update* family: one representative pair per JDBC signature, all unsupported.

  @Override
  public void updateNull(int columnIndex) throws SQLException {
    throw SqlExceptions.unsupported("Updatable result sets");
  }

  @Override
  public void updateNull(String columnLabel) throws SQLException {
    throw SqlExceptions.unsupported("Updatable result sets");
  }

  @Override
  public void updateBoolean(int columnIndex, boolean x) throws SQLException {
    throw SqlExceptions.unsupported("Updatable result sets");
  }

  @Override
  public void updateBoolean(String columnLabel, boolean x) throws SQLException {
    throw SqlExceptions.unsupported("Updatable result sets");
  }

  @Override
  public void updateByte(int columnIndex, byte x) throws SQLException {
    throw SqlExceptions.unsupported("Updatable result sets");
  }

  @Override
  public void updateByte(String columnLabel, byte x) throws SQLException {
    throw SqlExceptions.unsupported("Updatable result sets");
  }

  @Override
  public void updateShort(int columnIndex, short x) throws SQLException {
    throw SqlExceptions.unsupported("Updatable result sets");
  }

  @Override
  public void updateShort(String columnLabel, short x) throws SQLException {
    throw SqlExceptions.unsupported("Updatable result sets");
  }

  @Override
  public void updateInt(int columnIndex, int x) throws SQLException {
    throw SqlExceptions.unsupported("Updatable result sets");
  }

  @Override
  public void updateInt(String columnLabel, int x) throws SQLException {
    throw SqlExceptions.unsupported("Updatable result sets");
  }

  @Override
  public void updateLong(int columnIndex, long x) throws SQLException {
    throw SqlExceptions.unsupported("Updatable result sets");
  }

  @Override
  public void updateLong(String columnLabel, long x) throws SQLException {
    throw SqlExceptions.unsupported("Updatable result sets");
  }

  @Override
  public void updateFloat(int columnIndex, float x) throws SQLException {
    throw SqlExceptions.unsupported("Updatable result sets");
  }

  @Override
  public void updateFloat(String columnLabel, float x) throws SQLException {
    throw SqlExceptions.unsupported("Updatable result sets");
  }

  @Override
  public void updateDouble(int columnIndex, double x) throws SQLException {
    throw SqlExceptions.unsupported("Updatable result sets");
  }

  @Override
  public void updateDouble(String columnLabel, double x) throws SQLException {
    throw SqlExceptions.unsupported("Updatable result sets");
  }

  @Override
  public void updateBigDecimal(int columnIndex, BigDecimal x) throws SQLException {
    throw SqlExceptions.unsupported("Updatable result sets");
  }

  @Override
  public void updateBigDecimal(String columnLabel, BigDecimal x) throws SQLException {
    throw SqlExceptions.unsupported("Updatable result sets");
  }

  @Override
  public void updateString(int columnIndex, String x) throws SQLException {
    throw SqlExceptions.unsupported("Updatable result sets");
  }

  @Override
  public void updateString(String columnLabel, String x) throws SQLException {
    throw SqlExceptions.unsupported("Updatable result sets");
  }

  @Override
  public void updateBytes(int columnIndex, byte[] x) throws SQLException {
    throw SqlExceptions.unsupported("Updatable result sets");
  }

  @Override
  public void updateBytes(String columnLabel, byte[] x) throws SQLException {
    throw SqlExceptions.unsupported("Updatable result sets");
  }

  @Override
  public void updateDate(int columnIndex, Date x) throws SQLException {
    throw SqlExceptions.unsupported("Updatable result sets");
  }

  @Override
  public void updateDate(String columnLabel, Date x) throws SQLException {
    throw SqlExceptions.unsupported("Updatable result sets");
  }

  @Override
  public void updateTime(int columnIndex, Time x) throws SQLException {
    throw SqlExceptions.unsupported("Updatable result sets");
  }

  @Override
  public void updateTime(String columnLabel, Time x) throws SQLException {
    throw SqlExceptions.unsupported("Updatable result sets");
  }

  @Override
  public void updateTimestamp(int columnIndex, Timestamp x) throws SQLException {
    throw SqlExceptions.unsupported("Updatable result sets");
  }

  @Override
  public void updateTimestamp(String columnLabel, Timestamp x) throws SQLException {
    throw SqlExceptions.unsupported("Updatable result sets");
  }

  @Override
  public void updateAsciiStream(int columnIndex, InputStream x, int length) throws SQLException {
    throw SqlExceptions.unsupported("Updatable result sets");
  }

  @Override
  public void updateAsciiStream(String columnLabel, InputStream x, int length) throws SQLException {
    throw SqlExceptions.unsupported("Updatable result sets");
  }

  @Override
  public void updateAsciiStream(int columnIndex, InputStream x, long length) throws SQLException {
    throw SqlExceptions.unsupported("Updatable result sets");
  }

  @Override
  public void updateAsciiStream(String columnLabel, InputStream x, long length)
      throws SQLException {
    throw SqlExceptions.unsupported("Updatable result sets");
  }

  @Override
  public void updateAsciiStream(int columnIndex, InputStream x) throws SQLException {
    throw SqlExceptions.unsupported("Updatable result sets");
  }

  @Override
  public void updateAsciiStream(String columnLabel, InputStream x) throws SQLException {
    throw SqlExceptions.unsupported("Updatable result sets");
  }

  @Override
  public void updateBinaryStream(int columnIndex, InputStream x, int length) throws SQLException {
    throw SqlExceptions.unsupported("Updatable result sets");
  }

  @Override
  public void updateBinaryStream(String columnLabel, InputStream x, int length)
      throws SQLException {
    throw SqlExceptions.unsupported("Updatable result sets");
  }

  @Override
  public void updateBinaryStream(int columnIndex, InputStream x, long length) throws SQLException {
    throw SqlExceptions.unsupported("Updatable result sets");
  }

  @Override
  public void updateBinaryStream(String columnLabel, InputStream x, long length)
      throws SQLException {
    throw SqlExceptions.unsupported("Updatable result sets");
  }

  @Override
  public void updateBinaryStream(int columnIndex, InputStream x) throws SQLException {
    throw SqlExceptions.unsupported("Updatable result sets");
  }

  @Override
  public void updateBinaryStream(String columnLabel, InputStream x) throws SQLException {
    throw SqlExceptions.unsupported("Updatable result sets");
  }

  @Override
  public void updateCharacterStream(int columnIndex, Reader x, int length) throws SQLException {
    throw SqlExceptions.unsupported("Updatable result sets");
  }

  @Override
  public void updateCharacterStream(String columnLabel, Reader reader, int length)
      throws SQLException {
    throw SqlExceptions.unsupported("Updatable result sets");
  }

  @Override
  public void updateCharacterStream(int columnIndex, Reader x, long length) throws SQLException {
    throw SqlExceptions.unsupported("Updatable result sets");
  }

  @Override
  public void updateCharacterStream(String columnLabel, Reader reader, long length)
      throws SQLException {
    throw SqlExceptions.unsupported("Updatable result sets");
  }

  @Override
  public void updateCharacterStream(int columnIndex, Reader x) throws SQLException {
    throw SqlExceptions.unsupported("Updatable result sets");
  }

  @Override
  public void updateCharacterStream(String columnLabel, Reader reader) throws SQLException {
    throw SqlExceptions.unsupported("Updatable result sets");
  }

  @Override
  public void updateObject(int columnIndex, Object x, int scaleOrLength) throws SQLException {
    throw SqlExceptions.unsupported("Updatable result sets");
  }

  @Override
  public void updateObject(String columnLabel, Object x, int scaleOrLength) throws SQLException {
    throw SqlExceptions.unsupported("Updatable result sets");
  }

  @Override
  public void updateObject(int columnIndex, Object x) throws SQLException {
    throw SqlExceptions.unsupported("Updatable result sets");
  }

  @Override
  public void updateObject(String columnLabel, Object x) throws SQLException {
    throw SqlExceptions.unsupported("Updatable result sets");
  }

  @Override
  public void updateRef(int columnIndex, java.sql.Ref x) throws SQLException {
    throw SqlExceptions.unsupported("Updatable result sets");
  }

  @Override
  public void updateRef(String columnLabel, java.sql.Ref x) throws SQLException {
    throw SqlExceptions.unsupported("Updatable result sets");
  }

  @Override
  public void updateBlob(int columnIndex, java.sql.Blob x) throws SQLException {
    throw SqlExceptions.unsupported("Updatable result sets");
  }

  @Override
  public void updateBlob(String columnLabel, java.sql.Blob x) throws SQLException {
    throw SqlExceptions.unsupported("Updatable result sets");
  }

  @Override
  public void updateBlob(int columnIndex, InputStream inputStream, long length)
      throws SQLException {
    throw SqlExceptions.unsupported("Updatable result sets");
  }

  @Override
  public void updateBlob(String columnLabel, InputStream inputStream, long length)
      throws SQLException {
    throw SqlExceptions.unsupported("Updatable result sets");
  }

  @Override
  public void updateBlob(int columnIndex, InputStream inputStream) throws SQLException {
    throw SqlExceptions.unsupported("Updatable result sets");
  }

  @Override
  public void updateBlob(String columnLabel, InputStream inputStream) throws SQLException {
    throw SqlExceptions.unsupported("Updatable result sets");
  }

  @Override
  public void updateClob(int columnIndex, java.sql.Clob x) throws SQLException {
    throw SqlExceptions.unsupported("Updatable result sets");
  }

  @Override
  public void updateClob(String columnLabel, java.sql.Clob x) throws SQLException {
    throw SqlExceptions.unsupported("Updatable result sets");
  }

  @Override
  public void updateClob(int columnIndex, Reader reader, long length) throws SQLException {
    throw SqlExceptions.unsupported("Updatable result sets");
  }

  @Override
  public void updateClob(String columnLabel, Reader reader, long length) throws SQLException {
    throw SqlExceptions.unsupported("Updatable result sets");
  }

  @Override
  public void updateClob(int columnIndex, Reader reader) throws SQLException {
    throw SqlExceptions.unsupported("Updatable result sets");
  }

  @Override
  public void updateClob(String columnLabel, Reader reader) throws SQLException {
    throw SqlExceptions.unsupported("Updatable result sets");
  }

  @Override
  public void updateArray(int columnIndex, java.sql.Array x) throws SQLException {
    throw SqlExceptions.unsupported("Updatable result sets");
  }

  @Override
  public void updateArray(String columnLabel, java.sql.Array x) throws SQLException {
    throw SqlExceptions.unsupported("Updatable result sets");
  }

  @Override
  public void updateRowId(int columnIndex, java.sql.RowId x) throws SQLException {
    throw SqlExceptions.unsupported("Updatable result sets");
  }

  @Override
  public void updateRowId(String columnLabel, java.sql.RowId x) throws SQLException {
    throw SqlExceptions.unsupported("Updatable result sets");
  }

  @Override
  public void updateNString(int columnIndex, String nString) throws SQLException {
    throw SqlExceptions.unsupported("Updatable result sets");
  }

  @Override
  public void updateNString(String columnLabel, String nString) throws SQLException {
    throw SqlExceptions.unsupported("Updatable result sets");
  }

  @Override
  public void updateNClob(int columnIndex, java.sql.NClob nClob) throws SQLException {
    throw SqlExceptions.unsupported("Updatable result sets");
  }

  @Override
  public void updateNClob(String columnLabel, java.sql.NClob nClob) throws SQLException {
    throw SqlExceptions.unsupported("Updatable result sets");
  }

  @Override
  public void updateNClob(int columnIndex, Reader reader, long length) throws SQLException {
    throw SqlExceptions.unsupported("Updatable result sets");
  }

  @Override
  public void updateNClob(String columnLabel, Reader reader, long length) throws SQLException {
    throw SqlExceptions.unsupported("Updatable result sets");
  }

  @Override
  public void updateNClob(int columnIndex, Reader reader) throws SQLException {
    throw SqlExceptions.unsupported("Updatable result sets");
  }

  @Override
  public void updateNClob(String columnLabel, Reader reader) throws SQLException {
    throw SqlExceptions.unsupported("Updatable result sets");
  }

  @Override
  public void updateSQLXML(int columnIndex, java.sql.SQLXML xmlObject) throws SQLException {
    throw SqlExceptions.unsupported("Updatable result sets");
  }

  @Override
  public void updateSQLXML(String columnLabel, java.sql.SQLXML xmlObject) throws SQLException {
    throw SqlExceptions.unsupported("Updatable result sets");
  }

  @Override
  public void updateNCharacterStream(int columnIndex, Reader x, long length) throws SQLException {
    throw SqlExceptions.unsupported("Updatable result sets");
  }

  @Override
  public void updateNCharacterStream(String columnLabel, Reader reader, long length)
      throws SQLException {
    throw SqlExceptions.unsupported("Updatable result sets");
  }

  @Override
  public void updateNCharacterStream(int columnIndex, Reader x) throws SQLException {
    throw SqlExceptions.unsupported("Updatable result sets");
  }

  @Override
  public void updateNCharacterStream(String columnLabel, Reader reader) throws SQLException {
    throw SqlExceptions.unsupported("Updatable result sets");
  }
}
