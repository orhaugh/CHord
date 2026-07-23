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
import io.github.orhaugh.chord.codec.column.Serializations.Prefix;
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
    if (rows == 0) {
      // Zero row columns carry no data and no prefix on the wire, mirroring NativeReader,
      // which skips readData entirely for empty blocks such as INSERT schema headers.
      return readBody(in, type, 0, context, Serializations.NONE);
    }
    Prefix prefix = Serializations.readPrefix(in, type, context);
    return readBody(in, type, rows, context, prefix);
  }

  /**
   * Reads a column body with an already read bulk state prefix. Containers pass the matching child
   * prefixes down; the revision gated serialisations consume their structure state.
   */
  static Column readBody(
      WireReader in, ClickHouseType type, int rows, DecodeContext context, Prefix prefix) {
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
      case NullableType t -> readNullable(in, t, rows, context, prefix);
      case ArrayType t -> readArray(in, t, rows, context, prefix);
      case TupleType t -> readTuple(in, t, rows, context, prefix);
      case MapType t -> readMap(in, t, rows, context, prefix);
      case SimpleAggregateFunctionType t -> readBody(in, t.inner(), rows, context, prefix);
      case LowCardinalityType t -> Serializations.readLowCardinality(in, t, rows, context);
      case VariantType t ->
          Serializations.readVariant(
              in, t, t.alternatives(), rows, context, variantPrefix(prefix, t, rows, context));
      case DynamicType t ->
          rows == 0
              ? emptyDynamic(in, t, context)
              : Serializations.readDynamic(in, t, rows, context, (Prefix.Dynamic) prefix);
      case JsonType t ->
          rows == 0
              ? emptyJson(in, t, context)
              : Serializations.readJson(in, t, rows, context, (Prefix.Json) prefix);
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
    return columnZone.map(Timezones::parse).orElse(context.serverTimezone());
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
      WireReader in, NullableType type, int rows, DecodeContext context, Prefix prefix) {
    byte[] nullMap = readBytes(in, rows);
    Column inner = readBody(in, type.inner(), rows, context, childPrefix(prefix, 0));
    return new Columns.NullableColumn(type, nullMap, inner);
  }

  private static Column readArray(
      WireReader in, ArrayType type, int rows, DecodeContext context, Prefix prefix) {
    long[] offsets = readOffsets(in, rows, context);
    int total = offsets.length == 0 ? 0 : (int) offsets[offsets.length - 1];
    Column elements = readBody(in, type.element(), total, context, childPrefix(prefix, 0));
    return new Columns.ArrayColumn(type, offsets, elements);
  }

  private static Column readTuple(
      WireReader in, TupleType type, int rows, DecodeContext context, Prefix prefix) {
    Column[] elements = new Column[type.elements().size()];
    for (int i = 0; i < elements.length; i++) {
      elements[i] =
          readBody(in, type.elements().get(i).type(), rows, context, childPrefix(prefix, i));
    }
    return new Columns.TupleColumn(type, elements, rows);
  }

  private static Column readMap(
      WireReader in, MapType type, int rows, DecodeContext context, Prefix prefix) {
    long[] offsets = readOffsets(in, rows, context);
    int total = offsets.length == 0 ? 0 : (int) offsets[offsets.length - 1];
    Column keys = readBody(in, type.key(), total, context, childPrefix(prefix, 0));
    Column values = readBody(in, type.value(), total, context, childPrefix(prefix, 1));
    return new Columns.MapColumn(type, offsets, keys, values);
  }

  /**
   * Reads a sparse column: the nested prefix, the offsets of non default rows, and the non default
   * values, materialised into a full column of the declared type.
   *
   * @param in reader positioned at the column data
   * @param type the parsed column type
   * @param rows number of rows in the block
   * @param context decode limits and session information
   * @return the materialised column
   */
  public static Column readSparse(
      WireReader in, ClickHouseType type, int rows, DecodeContext context) {
    return Serializations.readSparse(in, type, rows, context);
  }

  /** Returns the i-th child of a container prefix, or none when no prefix applies. */
  private static Prefix childPrefix(Prefix prefix, int index) {
    return prefix instanceof Prefix.Nested nested
        ? nested.children().get(index)
        : Serializations.NONE;
  }

  /**
   * Resolves the variant prefix for a body read: the prefix from the wire when rows exist, or a
   * synthetic basic mode prefix for the empty case, which reads no bytes.
   */
  private static Prefix.Variant variantPrefix(
      Prefix prefix, VariantType type, int rows, DecodeContext context) {
    if (prefix instanceof Prefix.Variant variant) {
      return variant;
    }
    if (rows != 0) {
      throw new ChordProtocolException("Variant column body read without its prefix");
    }
    java.util.List<Prefix> none = new java.util.ArrayList<>(type.alternatives().size());
    for (int i = 0; i < type.alternatives().size(); i++) {
      none.add(Serializations.NONE);
    }
    return new Prefix.Variant(0, none);
  }

  /** Builds an empty Dynamic column without touching the stream. */
  private static Column emptyDynamic(WireReader in, DynamicType type, DecodeContext context) {
    Columns.VariantColumn variant =
        new Columns.VariantColumn(
            type,
            new byte[0],
            java.util.List.of(
                readBody(
                    in,
                    new io.github.orhaugh.chord.codec.type.ClickHouseType.StringType(),
                    0,
                    context,
                    Serializations.NONE)),
            new int[0]);
    return new Columns.DynamicColumn(type, variant, 0, java.util.List.of());
  }

  /** Builds an empty JSON column without touching the stream. */
  private static Column emptyJson(WireReader in, JsonType type, DecodeContext context) {
    java.util.Map<String, ClickHouseType> typedPathTypes =
        JsonTypeArguments.typedPaths(
            type, context.limits().maxTypeNameLength(), context.limits().maxTypeDepth());
    java.util.Map<String, Column> typedPaths = new java.util.LinkedHashMap<>();
    for (java.util.Map.Entry<String, ClickHouseType> entry : typedPathTypes.entrySet()) {
      typedPaths.put(
          entry.getKey(), readBody(in, entry.getValue(), 0, context, Serializations.NONE));
    }
    io.github.orhaugh.chord.codec.type.ClickHouseType.MapType sharedType =
        new io.github.orhaugh.chord.codec.type.ClickHouseType.MapType(
            new io.github.orhaugh.chord.codec.type.ClickHouseType.StringType(),
            new io.github.orhaugh.chord.codec.type.ClickHouseType.StringType());
    Columns.MapColumn sharedData =
        (Columns.MapColumn) readBody(in, sharedType, 0, context, Serializations.NONE);
    return new Columns.JsonColumn(type, 0, typedPaths, java.util.Map.of(), sharedData);
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
