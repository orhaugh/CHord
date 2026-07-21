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
package io.github.orhaugh.chord.codec.block;

import io.github.orhaugh.chord.codec.column.Column;
import io.github.orhaugh.chord.codec.type.ClickHouseType;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One decoded native block: named, typed columns of equal row count. Blocks are immutable; the zero
 * row header block a SELECT starts with carries the schema.
 *
 * @param info the block metadata
 * @param rows the row count shared by every column
 * @param columns the decoded columns in wire order
 */
public record Block(BlockInfo info, int rows, List<NamedColumn> columns) {

  /** A decoded column with its wire name. */
  public record NamedColumn(String name, Column column) {
    /** Validates components. */
    public NamedColumn {
      Objects.requireNonNull(name, "name");
      Objects.requireNonNull(column, "column");
    }
  }

  /** Validates components and copies the column list. */
  public Block {
    Objects.requireNonNull(info, "info");
    columns = List.copyOf(columns);
    for (NamedColumn column : columns) {
      if (column.column().size() != rows) {
        throw new IllegalArgumentException(
            "Column "
                + column.name()
                + " has "
                + column.column().size()
                + " rows, block has "
                + rows);
      }
    }
  }

  /**
   * Returns the number of columns.
   *
   * @return the column count
   */
  public int columnCount() {
    return columns.size();
  }

  /**
   * Returns a column by position.
   *
   * @param index zero based column position
   * @return the column
   */
  public Column column(int index) {
    return columns.get(index).column();
  }

  /**
   * Returns a column's wire name by position.
   *
   * @param index zero based column position
   * @return the column name
   */
  public String columnName(int index) {
    return columns.get(index).name();
  }

  /**
   * Returns a column's type by position.
   *
   * @param index zero based column position
   * @return the column type
   */
  public ClickHouseType columnType(int index) {
    return columns.get(index).column().type();
  }

  /**
   * Finds a column by its wire name.
   *
   * @param name the column name
   * @return the column, or empty when the block has no such column
   */
  public Optional<Column> columnByName(String name) {
    for (NamedColumn column : columns) {
      if (column.name().equals(name)) {
        return Optional.of(column.column());
      }
    }
    return Optional.empty();
  }
}
