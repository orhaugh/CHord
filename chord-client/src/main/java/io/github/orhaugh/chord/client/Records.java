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
package io.github.orhaugh.chord.client;

import io.github.orhaugh.chord.ChordTypeException;
import io.github.orhaugh.chord.codec.block.Block;
import io.github.orhaugh.chord.codec.column.BlockBuilder;
import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * Row oriented convenience over the columnar API: streams query results as Java records and inserts
 * iterables of records, matching record components to columns by name. The columnar accessors
 * remain the primary, allocation free API (ADR-0005); this mapping allocates one record per row and
 * exists for the code where clarity beats throughput.
 *
 * <p>Component names must match column names exactly. Values convert losslessly: numeric widenings
 * are applied, a {@code BigInteger} lands in a {@code long} only when it fits, and any lossy or
 * unrelated conversion raises {@link ChordTypeException} naming the component. A NULL maps to
 * {@code null} for reference components and raises for primitives.
 */
public final class Records {

  private Records() {}

  /**
   * Streams the remaining rows of a result as records, one record per row, matching components to
   * columns by name. The stream is lazy: it pulls blocks from the result as it is consumed, so it
   * must be consumed before the result is closed and inherits its bounded memory.
   *
   * @param result the result to consume
   * @param type the record type to map rows onto
   * @param <T> the record type
   * @return a lazy stream of one record per row
   */
  public static <T extends Record> Stream<T> stream(QueryResult result, Class<T> type) {
    Objects.requireNonNull(result, "result");
    Mapper<T> mapper = new Mapper<>(type);
    Iterator<T> iterator =
        new Iterator<>() {
          private Block block;
          private int row;

          @Override
          public boolean hasNext() {
            while (block == null || row >= block.rows()) {
              Optional<Block> next = result.nextBlock();
              if (next.isEmpty()) {
                return false;
              }
              block = next.get();
              row = 0;
            }
            return true;
          }

          @Override
          public T next() {
            if (!hasNext()) {
              throw new java.util.NoSuchElementException();
            }
            return mapper.map(block, row++);
          }
        };
    return StreamSupport.stream(
        Spliterators.spliteratorUnknownSize(iterator, Spliterator.ORDERED | Spliterator.NONNULL),
        false);
  }

  /**
   * Sends every record as insert rows, matching components to the server supplied schema by name,
   * batching into blocks of the given size. {@link InsertStream#finish()} remains the caller's
   * explicit commit step.
   *
   * @param insert the open insert to feed
   * @param records the rows to send
   * @param rowsPerBlock rows per network block, at least 1; 65409 matches the server default
   * @param <T> the record type
   * @return the number of rows sent
   */
  public static <T extends Record> long insertAll(
      InsertStream insert, Iterable<T> records, int rowsPerBlock) {
    Objects.requireNonNull(insert, "insert");
    Objects.requireNonNull(records, "records");
    if (rowsPerBlock < 1) {
      throw new io.github.orhaugh.chord.ChordConfigurationException(
          "rowsPerBlock must be at least 1");
    }
    Block schema = insert.schema();
    List<RecordComponent> order = null;
    long sent = 0;
    BlockBuilder builder = null;
    int inBlock = 0;
    for (T record : records) {
      if (order == null) {
        order = componentsInSchemaOrder(record.getClass(), schema);
      }
      if (builder == null) {
        builder = insert.newBlock();
      }
      Object[] values = new Object[order.size()];
      for (int i = 0; i < order.size(); i++) {
        values[i] = read(order.get(i), record);
      }
      builder.addRow(values);
      sent++;
      if (++inBlock == rowsPerBlock) {
        insert.send(builder.build());
        builder = null;
        inBlock = 0;
      }
    }
    if (builder != null && inBlock > 0) {
      insert.send(builder.build());
    }
    return sent;
  }

  private static List<RecordComponent> componentsInSchemaOrder(Class<?> type, Block schema) {
    RecordComponent[] components = type.getRecordComponents();
    if (components == null) {
      throw new ChordTypeException(type.getName() + " is not a record type");
    }
    List<RecordComponent> order = new ArrayList<>(schema.columnCount());
    for (int column = 0; column < schema.columnCount(); column++) {
      String name = schema.columnName(column);
      RecordComponent match = null;
      for (RecordComponent component : components) {
        if (component.getName().equals(name)) {
          match = component;
          break;
        }
      }
      if (match == null) {
        throw new ChordTypeException(
            "Record "
                + type.getName()
                + " has no component named \""
                + name
                + "\" for the insert schema column of that name");
      }
      order.add(match);
    }
    return order;
  }

  private static Object read(RecordComponent component, Object record) {
    try {
      return component.getAccessor().invoke(record);
    } catch (ReflectiveOperationException e) {
      throw new ChordTypeException(
          "Could not read record component " + component.getName() + ": " + e.getMessage(), e);
    }
  }

  private static final class Mapper<T extends Record> {
    private final Class<T> type;
    private final RecordComponent[] components;
    private final Constructor<T> constructor;
    private int[] columnIndexes;
    private Block resolvedFor;

    Mapper(Class<T> type) {
      this.type = Objects.requireNonNull(type, "type");
      this.components = type.getRecordComponents();
      if (components == null) {
        throw new ChordTypeException(type.getName() + " is not a record type");
      }
      Class<?>[] parameterTypes = new Class<?>[components.length];
      for (int i = 0; i < components.length; i++) {
        parameterTypes[i] = components[i].getType();
      }
      try {
        this.constructor = type.getDeclaredConstructor(parameterTypes);
        constructor.setAccessible(true);
      } catch (NoSuchMethodException e) {
        throw new ChordTypeException(type.getName() + " has no canonical record constructor", e);
      }
    }

    T map(Block block, int row) {
      resolveColumns(block);
      Object[] arguments = new Object[components.length];
      for (int i = 0; i < components.length; i++) {
        Object raw = block.column(columnIndexes[i]).objectAt(row);
        arguments[i] = convert(raw, components[i]);
      }
      try {
        return constructor.newInstance(arguments);
      } catch (ReflectiveOperationException e) {
        throw new ChordTypeException(
            "Could not construct " + type.getName() + ": " + e.getMessage(), e);
      }
    }

    private void resolveColumns(Block block) {
      if (resolvedFor != null && block.columnCount() == resolvedFor.columnCount()) {
        return; // blocks of one result share their schema
      }
      columnIndexes = new int[components.length];
      for (int i = 0; i < components.length; i++) {
        String name = components[i].getName();
        int index = -1;
        for (int column = 0; column < block.columnCount(); column++) {
          if (block.columnName(column).equals(name)) {
            index = column;
            break;
          }
        }
        if (index < 0) {
          throw new ChordTypeException(
              "The result has no column named \""
                  + name
                  + "\" for record component "
                  + type.getName()
                  + "."
                  + name);
        }
        columnIndexes[i] = index;
      }
      resolvedFor = block;
    }

    private Object convert(Object raw, RecordComponent component) {
      Class<?> target = component.getType();
      if (raw == null) {
        if (target.isPrimitive()) {
          throw new ChordTypeException(
              "NULL for primitive record component "
                  + type.getName()
                  + "."
                  + component.getName()
                  + "; use a boxed or reference type");
        }
        return null;
      }
      Class<?> boxed = boxed(target);
      if (boxed.isInstance(raw)) {
        return raw;
      }
      if (raw instanceof Number number) {
        if (boxed == Long.class) {
          if (number instanceof BigInteger big) {
            return big.longValueExact();
          }
          if (number instanceof Integer || number instanceof Short || number instanceof Byte) {
            return number.longValue();
          }
        }
        if (boxed == Integer.class && (number instanceof Short || number instanceof Byte)) {
          return number.intValue();
        }
        if (boxed == Double.class && number instanceof Float) {
          return number.doubleValue();
        }
      }
      throw new ChordTypeException(
          "Cannot convert column value of type "
              + raw.getClass().getSimpleName()
              + " to "
              + target.getSimpleName()
              + " for "
              + type.getName()
              + "."
              + component.getName());
    }

    private static Class<?> boxed(Class<?> type) {
      if (!type.isPrimitive()) {
        return type;
      }
      if (type == long.class) {
        return Long.class;
      }
      if (type == int.class) {
        return Integer.class;
      }
      if (type == double.class) {
        return Double.class;
      }
      if (type == float.class) {
        return Float.class;
      }
      if (type == boolean.class) {
        return Boolean.class;
      }
      if (type == short.class) {
        return Short.class;
      }
      if (type == byte.class) {
        return Byte.class;
      }
      return Character.class;
    }
  }
}
