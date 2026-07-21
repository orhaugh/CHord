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

import io.github.orhaugh.chord.ChordProtocolException;
import io.github.orhaugh.chord.codec.UnsupportedClickHouseTypeException;
import io.github.orhaugh.chord.codec.block.DecodeContext;
import io.github.orhaugh.chord.codec.type.ClickHouseType;
import io.github.orhaugh.chord.codec.type.ClickHouseType.AggregateFunctionType;
import io.github.orhaugh.chord.codec.type.ClickHouseType.ArrayType;
import io.github.orhaugh.chord.codec.type.ClickHouseType.BFloat16Type;
import io.github.orhaugh.chord.codec.type.ClickHouseType.BoolType;
import io.github.orhaugh.chord.codec.type.ClickHouseType.Date32Type;
import io.github.orhaugh.chord.codec.type.ClickHouseType.DateTime64Type;
import io.github.orhaugh.chord.codec.type.ClickHouseType.DateTimeType;
import io.github.orhaugh.chord.codec.type.ClickHouseType.DateType;
import io.github.orhaugh.chord.codec.type.ClickHouseType.DecimalType;
import io.github.orhaugh.chord.codec.type.ClickHouseType.DynamicType;
import io.github.orhaugh.chord.codec.type.ClickHouseType.EnumType;
import io.github.orhaugh.chord.codec.type.ClickHouseType.FixedStringType;
import io.github.orhaugh.chord.codec.type.ClickHouseType.FloatType;
import io.github.orhaugh.chord.codec.type.ClickHouseType.IntegerType;
import io.github.orhaugh.chord.codec.type.ClickHouseType.IntervalType;
import io.github.orhaugh.chord.codec.type.ClickHouseType.Ipv4Type;
import io.github.orhaugh.chord.codec.type.ClickHouseType.Ipv6Type;
import io.github.orhaugh.chord.codec.type.ClickHouseType.JsonType;
import io.github.orhaugh.chord.codec.type.ClickHouseType.LowCardinalityType;
import io.github.orhaugh.chord.codec.type.ClickHouseType.MapType;
import io.github.orhaugh.chord.codec.type.ClickHouseType.NestedType;
import io.github.orhaugh.chord.codec.type.ClickHouseType.NothingType;
import io.github.orhaugh.chord.codec.type.ClickHouseType.NullableType;
import io.github.orhaugh.chord.codec.type.ClickHouseType.SimpleAggregateFunctionType;
import io.github.orhaugh.chord.codec.type.ClickHouseType.StringType;
import io.github.orhaugh.chord.codec.type.ClickHouseType.TupleType;
import io.github.orhaugh.chord.codec.type.ClickHouseType.UuidType;
import io.github.orhaugh.chord.codec.type.ClickHouseType.VariantType;
import io.github.orhaugh.chord.protocol.wire.WireReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.ZoneId;
import java.util.Arrays;

/**
 * Decodes one column of a native block. Dispatch is a total switch over the sealed type model:
 * every recognised type either decodes or fails explicitly before any value bytes are consumed;
 * sizes are never guessed.
 */
public final class ColumnReader {

  private ColumnReader() {}

  /**
   * Reads a column of {@code rows} values of the given type.
   *
   * @param in reader positioned at the column data
   * @param type the parsed column type
   * @param rows number of rows in the block
   * @param context decode limits and session information
   * @return the decoded column
   */
  public static Column read(WireReader in, ClickHouseType type, int rows, DecodeContext context) {
    return switch (type) {
      case IntegerType t -> readInteger(in, t, rows);
      case BoolType t -> Columns.bool(t, readBytes(in, rows));
      case FloatType t ->
          t.bits() == 32
              ? new Columns.Float32Column(t, readFloats(in, rows))
              : new Columns.Float64Column(t, readDoubles(in, rows));
      case BFloat16Type t -> new Columns.BFloat16Column(t, readShorts(in, rows));
      case StringType t -> readString(in, t, rows, context);
      case FixedStringType t -> readFixedString(in, t, rows, context);
      case DateType t -> new Columns.DateColumn(t, readShorts(in, rows));
      case Date32Type t -> new Columns.Date32Column(t, readInts(in, rows));
      case DateTimeType t ->
          new Columns.DateTimeColumn(t, readInts(in, rows), zoneFor(t.timezone(), context));
      case DateTime64Type t ->
          new Columns.DateTime64Column(
              t, readLongs(in, rows), t.precision(), zoneFor(t.timezone(), context));
      case UuidType t -> readUuid(in, t, rows);
      case Ipv4Type t -> new Columns.Ipv4Column(t, readInts(in, rows));
      case Ipv6Type t -> new Columns.Ipv6Column(t, readFixedBytes(in, rows, 16), rows);
      case EnumType t -> readEnum(in, t, rows);
      case DecimalType t ->
          new Columns.DecimalColumn(
              t,
              readFixedBytes(in, rows, t.storageBits() / 8),
              t.storageBits() / 8,
              t.scale(),
              rows);
      case IntervalType t -> new Columns.IntervalColumn(t, readLongs(in, rows));
      case NothingType t -> readNothing(in, t, rows);
      case NullableType t -> readNullable(in, t, rows, context);
      case ArrayType t -> readArray(in, t, rows, context);
      case TupleType t -> readTuple(in, t, rows, context);
      case MapType t -> readMap(in, t, rows, context);
      case SimpleAggregateFunctionType t -> read(in, t.inner(), rows, context);
      case LowCardinalityType t ->
          throw new UnsupportedClickHouseTypeException(
              "LowCardinality decoding is not supported yet (Phase 6): " + t.name());
      case VariantType t ->
          throw new UnsupportedClickHouseTypeException(
              "Variant decoding is not supported yet (Phase 6): " + t.name());
      case DynamicType t ->
          throw new UnsupportedClickHouseTypeException(
              "Dynamic decoding is not supported yet (Phase 6): " + t.name());
      case JsonType t ->
          throw new UnsupportedClickHouseTypeException(
              "JSON decoding is not supported yet (Phase 6): " + t.name());
      case AggregateFunctionType t ->
          throw new UnsupportedClickHouseTypeException(
              "AggregateFunction states are opaque and not decodable: " + t.name());
      case NestedType t ->
          throw new UnsupportedClickHouseTypeException(
              "Nested does not appear in result blocks; the server flattens it into arrays: "
                  + t.name());
    };
  }

  private static ZoneId zoneFor(java.util.Optional<String> columnZone, DecodeContext context) {
    return columnZone.map(ZoneId::of).orElse(context.serverTimezone());
  }

  private static Column readInteger(WireReader in, IntegerType type, int rows) {
    return switch (type.bits()) {
      case 8 ->
          type.signed()
              ? Columns.int8(type, readBytes(in, rows))
              : Columns.uint8(type, readBytes(in, rows));
      case 16 ->
          type.signed()
              ? Columns.int16(type, readShorts(in, rows))
              : Columns.uint16(type, readShorts(in, rows));
      case 32 ->
          type.signed()
              ? Columns.int32(type, readInts(in, rows))
              : Columns.uint32(type, readInts(in, rows));
      case 64 ->
          type.signed()
              ? Columns.int64(type, readLongs(in, rows))
              : Columns.uint64(type, readLongs(in, rows));
      default ->
          new Columns.BigIntegerColumn(
              type,
              readFixedBytes(in, rows, type.bits() / 8),
              type.bits() / 8,
              type.signed(),
              rows);
    };
  }

  private static Column readString(
      WireReader in, StringType type, int rows, DecodeContext context) {
    int[] offsets = new int[rows];
    byte[] data = new byte[Math.min(64, Integer.MAX_VALUE)];
    int used = 0;
    for (int i = 0; i < rows; i++) {
      byte[] value = in.readStringBytes(context.limits().maxStringValueBytes());
      long newUsed = (long) used + value.length;
      if (newUsed > context.limits().maxStringDataBytesPerColumn()) {
        throw new ChordProtocolException(
            "String column payload exceeds the permitted "
                + context.limits().maxStringDataBytesPerColumn()
                + " bytes per block");
      }
      if (newUsed > data.length) {
        long doubled = Math.min((long) data.length * 2, Integer.MAX_VALUE - 8);
        data = Arrays.copyOf(data, (int) Math.max(newUsed, doubled));
      }
      System.arraycopy(value, 0, data, used, value.length);
      used = (int) newUsed;
      offsets[i] = used;
    }
    return new Columns.StringColumn(type, data, offsets, rows);
  }

  private static Column readFixedString(
      WireReader in, FixedStringType type, int rows, DecodeContext context) {
    long total = (long) rows * type.length();
    if (total > context.limits().maxStringDataBytesPerColumn()) {
      throw new ChordProtocolException(
          "FixedString column payload "
              + total
              + " exceeds the permitted "
              + context.limits().maxStringDataBytesPerColumn()
              + " bytes per block");
    }
    return new Columns.FixedStringColumn(
        type, readFixedBytes(in, rows, type.length()), type.length(), rows);
  }

  private static Column readUuid(WireReader in, UuidType type, int rows) {
    long[] most = new long[rows];
    long[] least = new long[rows];
    for (int i = 0; i < rows; i++) {
      most[i] = in.readInt64Le();
      least[i] = in.readInt64Le();
    }
    return new Columns.UuidColumn(type, most, least);
  }

  private static Column readEnum(WireReader in, EnumType type, int rows) {
    short[] values = new short[rows];
    if (type.bits() == 8) {
      byte[] raw = readBytes(in, rows);
      for (int i = 0; i < rows; i++) {
        values[i] = raw[i];
      }
    } else {
      values = readShorts(in, rows);
    }
    return new Columns.EnumColumn(type, values);
  }

  private static Column readNothing(WireReader in, NothingType type, int rows) {
    // Nothing occupies one filler byte per row on the wire.
    if (rows > 0) {
      in.readFully(new byte[rows]);
    }
    return new Columns.NothingColumn(type, rows);
  }

  private static Column readNullable(
      WireReader in, NullableType type, int rows, DecodeContext context) {
    byte[] nullMap = readBytes(in, rows);
    Column inner = read(in, type.inner(), rows, context);
    return new Columns.NullableColumn(type, nullMap, inner);
  }

  private static Column readArray(WireReader in, ArrayType type, int rows, DecodeContext context) {
    long[] offsets = readOffsets(in, rows, context);
    int total = offsets.length == 0 ? 0 : (int) offsets[offsets.length - 1];
    Column elements = read(in, type.element(), total, context);
    return new Columns.ArrayColumn(type, offsets, elements);
  }

  private static Column readTuple(WireReader in, TupleType type, int rows, DecodeContext context) {
    Column[] elements = new Column[type.elements().size()];
    for (int i = 0; i < elements.length; i++) {
      elements[i] = read(in, type.elements().get(i).type(), rows, context);
    }
    return new Columns.TupleColumn(type, elements, rows);
  }

  private static Column readMap(WireReader in, MapType type, int rows, DecodeContext context) {
    long[] offsets = readOffsets(in, rows, context);
    int total = offsets.length == 0 ? 0 : (int) offsets[offsets.length - 1];
    Column keys = read(in, type.key(), total, context);
    Column values = read(in, type.value(), total, context);
    return new Columns.MapColumn(type, offsets, keys, values);
  }

  private static long[] readOffsets(WireReader in, int rows, DecodeContext context) {
    long[] offsets = new long[rows];
    long previous = 0;
    for (int i = 0; i < rows; i++) {
      long offset = in.readInt64Le();
      if (offset < previous) {
        throw new ChordProtocolException(
            "Array offsets are not monotonically non decreasing at row " + i);
      }
      if (Long.compareUnsigned(offset, context.limits().maxArrayElements()) > 0) {
        throw new ChordProtocolException(
            "Array offset "
                + Long.toUnsignedString(offset)
                + " exceeds the permitted "
                + context.limits().maxArrayElements()
                + " elements per block");
      }
      offsets[i] = offset;
      previous = offset;
    }
    return offsets;
  }

  private static byte[] readBytes(WireReader in, int rows) {
    byte[] data = new byte[rows];
    in.readFully(data);
    return data;
  }

  private static byte[] readFixedBytes(WireReader in, int rows, int width) {
    long total = (long) rows * width;
    if (total > Integer.MAX_VALUE - 8) {
      throw new ChordProtocolException(
          "Fixed width column of " + total + " bytes exceeds the JVM array limit");
    }
    byte[] data = new byte[(int) total];
    in.readFully(data);
    return data;
  }

  private static short[] readShorts(WireReader in, int rows) {
    byte[] raw = readFixedBytes(in, rows, 2);
    short[] values = new short[rows];
    ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(values);
    return values;
  }

  private static int[] readInts(WireReader in, int rows) {
    byte[] raw = readFixedBytes(in, rows, 4);
    int[] values = new int[rows];
    ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(values);
    return values;
  }

  private static long[] readLongs(WireReader in, int rows) {
    byte[] raw = readFixedBytes(in, rows, 8);
    long[] values = new long[rows];
    ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asLongBuffer().get(values);
    return values;
  }

  private static float[] readFloats(WireReader in, int rows) {
    byte[] raw = readFixedBytes(in, rows, 4);
    float[] values = new float[rows];
    ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(values);
    return values;
  }

  private static double[] readDoubles(WireReader in, int rows) {
    byte[] raw = readFixedBytes(in, rows, 8);
    double[] values = new double[rows];
    ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asDoubleBuffer().get(values);
    return values;
  }
}
