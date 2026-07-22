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

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import java.util.Map;

/** A read only {@link Array} over a decoded ClickHouse array value. */
final class ChordArray implements Array {

  private final List<?> values;

  ChordArray(List<?> values) {
    this.values = values;
  }

  @Override
  public Object getArray() {
    return values.toArray();
  }

  @Override
  public Object getArray(long index, int count) throws SQLException {
    int from = Math.toIntExact(index - 1);
    if (from < 0 || from + count > values.size()) {
      throw new SQLException("Array slice out of range", "22003");
    }
    return values.subList(from, from + count).toArray();
  }

  @Override
  public Object getArray(Map<String, Class<?>> map) throws SQLException {
    throw SqlExceptions.unsupported("Custom type maps");
  }

  @Override
  public Object getArray(long index, int count, Map<String, Class<?>> map) throws SQLException {
    throw SqlExceptions.unsupported("Custom type maps");
  }

  @Override
  public int getBaseType() {
    return Types.OTHER;
  }

  @Override
  public String getBaseTypeName() {
    return "Object";
  }

  @Override
  public ResultSet getResultSet() throws SQLException {
    throw SqlExceptions.unsupported("Array result set views");
  }

  @Override
  public ResultSet getResultSet(long index, int count) throws SQLException {
    throw SqlExceptions.unsupported("Array result set views");
  }

  @Override
  public ResultSet getResultSet(Map<String, Class<?>> map) throws SQLException {
    throw SqlExceptions.unsupported("Array result set views");
  }

  @Override
  public ResultSet getResultSet(long index, int count, Map<String, Class<?>> map)
      throws SQLException {
    throw SqlExceptions.unsupported("Array result set views");
  }

  @Override
  public void free() {
    // Nothing to release; the values are plain objects.
  }
}
