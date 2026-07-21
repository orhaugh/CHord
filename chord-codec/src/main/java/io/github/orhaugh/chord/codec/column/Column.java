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
package io.github.orhaugh.chord.codec.column;

import io.github.orhaugh.chord.codec.type.ClickHouseType;

/**
 * A decoded column of one native block, backed by primitive arrays; the main decode path never
 * allocates one object per cell.
 *
 * <p>Concrete columns in {@link Columns} expose typed accessors ({@code longAt}, {@code stringAt},
 * ...) for allocation free reads. {@link #objectAt(int)} is the boxed convenience path, returning
 * {@code null} exactly where a {@code Nullable} column holds NULL. Columns are immutable views over
 * the decoded block and remain valid after the block iterator advances.
 */
public interface Column {

  /**
   * Returns the ClickHouse type of this column.
   *
   * @return the type
   */
  ClickHouseType type();

  /**
   * Returns the number of rows.
   *
   * @return the row count
   */
  int size();

  /**
   * Returns the boxed value at a row, or {@code null} where a nullable column holds NULL.
   *
   * @param row zero based row index
   * @return the boxed value
   */
  Object objectAt(int row);

  /**
   * Reports whether the value at a row is NULL. Only {@code Nullable} columns ever return {@code
   * true}.
   *
   * @param row zero based row index
   * @return {@code true} when the value is NULL
   */
  default boolean isNullAt(int row) {
    return false;
  }
}
