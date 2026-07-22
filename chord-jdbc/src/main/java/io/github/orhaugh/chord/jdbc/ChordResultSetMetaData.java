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

import io.github.orhaugh.chord.codec.block.Block;
import io.github.orhaugh.chord.codec.type.ClickHouseType;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;

/** Result set metadata derived from the schema header block. */
final class ChordResultSetMetaData implements ResultSetMetaData {

  private final Block header;

  ChordResultSetMetaData(Block header) {
    this.header = header;
  }

  private ClickHouseType typeAt(int column) throws SQLException {
    if (column < 1 || column > header.columnCount()) {
      throw new SQLException("Column index " + column + " is out of range", "07009");
    }
    return header.columnType(column - 1);
  }

  @Override
  public int getColumnCount() {
    return header.columnCount();
  }

  @Override
  public String getColumnName(int column) throws SQLException {
    typeAt(column);
    return header.columnName(column - 1);
  }

  @Override
  public String getColumnLabel(int column) throws SQLException {
    return getColumnName(column);
  }

  @Override
  public String getColumnTypeName(int column) throws SQLException {
    return typeAt(column).name();
  }

  @Override
  public int getColumnType(int column) throws SQLException {
    return JdbcTypes.sqlType(typeAt(column));
  }

  @Override
  public String getColumnClassName(int column) throws SQLException {
    return JdbcTypes.javaClassName(typeAt(column));
  }

  @Override
  public int isNullable(int column) throws SQLException {
    return JdbcTypes.isNullable(typeAt(column)) ? columnNullable : columnNoNulls;
  }

  @Override
  public int getPrecision(int column) throws SQLException {
    return JdbcTypes.precision(typeAt(column));
  }

  @Override
  public int getScale(int column) throws SQLException {
    return JdbcTypes.scale(typeAt(column));
  }

  @Override
  public int getColumnDisplaySize(int column) throws SQLException {
    int precision = getPrecision(column);
    return precision > 0 ? precision : 80;
  }

  @Override
  public boolean isAutoIncrement(int column) throws SQLException {
    typeAt(column);
    return false;
  }

  @Override
  public boolean isCaseSensitive(int column) throws SQLException {
    typeAt(column);
    return true;
  }

  @Override
  public boolean isSearchable(int column) throws SQLException {
    typeAt(column);
    return true;
  }

  @Override
  public boolean isCurrency(int column) throws SQLException {
    typeAt(column);
    return false;
  }

  @Override
  public boolean isSigned(int column) throws SQLException {
    return JdbcTypes.unwrap(typeAt(column)) instanceof ClickHouseType.IntegerType t
        ? t.signed()
        : JdbcTypes.unwrap(typeAt(column)) instanceof ClickHouseType.FloatType
            || JdbcTypes.unwrap(typeAt(column)) instanceof ClickHouseType.DecimalType;
  }

  @Override
  public String getSchemaName(int column) throws SQLException {
    typeAt(column);
    return "";
  }

  @Override
  public String getTableName(int column) throws SQLException {
    typeAt(column);
    return "";
  }

  @Override
  public String getCatalogName(int column) throws SQLException {
    typeAt(column);
    return "";
  }

  @Override
  public boolean isReadOnly(int column) throws SQLException {
    typeAt(column);
    return true;
  }

  @Override
  public boolean isWritable(int column) throws SQLException {
    typeAt(column);
    return false;
  }

  @Override
  public boolean isDefinitelyWritable(int column) throws SQLException {
    typeAt(column);
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
