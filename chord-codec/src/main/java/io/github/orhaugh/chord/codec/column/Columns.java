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
import io.github.orhaugh.chord.codec.type.ClickHouseType.EnumType;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.AbstractList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Concrete column implementations, one per ClickHouse storage shape, each backed by primitive
 * arrays. Instances are created by the block decoder and are immutable.
 */
public final class Columns {

  private Columns() {}

  private abstract static class Base implements Column {
    private final ClickHouseType type;
    private final int size;

    Base(ClickHouseType type, int size) {
      this.type = type;
      this.size = size;
    }

    @Override
    public final ClickHouseType type() {
      return type;
    }

    @Override
    public final int size() {
      return size;
    }

    final void checkRow(int row) {
      if (row < 0 || row >= size) {
        throw new IndexOutOfBoundsException("row " + row + " of " + size);
      }
    }
  }

  /** Int8 column. */
  public static final class Int8Column extends Base {
    private final byte[] values;

    Int8Column(ClickHouseType type, byte[] values) {
      super(type, values.length);
      this.values = values;
    }

    /**
     * Returns the value at a row.
     *
     * @param row row index
     * @return the signed byte value
     */
    public byte byteAt(int row) {
      checkRow(row);
      return values[row];
    }

    @Override
    public Object objectAt(int row) {
      return byteAt(row);
    }
  }

  /** UInt8 column. */
  public static final class UInt8Column extends Base {
    private final byte[] values;

    UInt8Column(ClickHouseType type, byte[] values) {
      super(type, values.length);
      this.values = values;
    }

    /**
     * Returns the value at a row as an unsigned int.
     *
     * @param row row index
     * @return the value in 0..255
     */
    public int intAt(int row) {
      checkRow(row);
      return values[row] & 0xFF;
    }

    @Override
    public Object objectAt(int row) {
      return intAt(row);
    }
  }

  /** Bool column. */
  public static final class BoolColumn extends Base {
    private final byte[] values;

    BoolColumn(ClickHouseType type, byte[] values) {
      super(type, values.length);
      this.values = values;
    }

    /**
     * Returns the value at a row.
     *
     * @param row row index
     * @return the boolean value
     */
    public boolean booleanAt(int row) {
      checkRow(row);
      return values[row] != 0;
    }

    @Override
    public Object objectAt(int row) {
      return booleanAt(row);
    }
  }

  /** Int16 column. */
  public static final class Int16Column extends Base {
    private final short[] values;

    Int16Column(ClickHouseType type, short[] values) {
      super(type, values.length);
      this.values = values;
    }

    /**
     * Returns the value at a row.
     *
     * @param row row index
     * @return the signed short value
     */
    public short shortAt(int row) {
      checkRow(row);
      return values[row];
    }

    @Override
    public Object objectAt(int row) {
      return shortAt(row);
    }
  }

  /** UInt16 column. */
  public static final class UInt16Column extends Base {
    private final short[] values;

    UInt16Column(ClickHouseType type, short[] values) {
      super(type, values.length);
      this.values = values;
    }

    /**
     * Returns the value at a row as an unsigned int.
     *
     * @param row row index
     * @return the value in 0..65535
     */
    public int intAt(int row) {
      checkRow(row);
      return values[row] & 0xFFFF;
    }

    @Override
    public Object objectAt(int row) {
      return intAt(row);
    }
  }

  /** Int32 column. */
  public static final class Int32Column extends Base {
    private final int[] values;

    Int32Column(ClickHouseType type, int[] values) {
      super(type, values.length);
      this.values = values;
    }

    /**
     * Returns the value at a row.
     *
     * @param row row index
     * @return the signed int value
     */
    public int intAt(int row) {
      checkRow(row);
      return values[row];
    }

    @Override
    public Object objectAt(int row) {
      return intAt(row);
    }
  }

  /** UInt32 column. */
  public static final class UInt32Column extends Base {
    private final int[] values;

    UInt32Column(ClickHouseType type, int[] values) {
      super(type, values.length);
      this.values = values;
    }

    /**
     * Returns the value at a row as an unsigned long.
     *
     * @param row row index
     * @return the value in 0..4294967295
     */
    public long longAt(int row) {
      checkRow(row);
      return values[row] & 0xFFFF_FFFFL;
    }

    @Override
    public Object objectAt(int row) {
      return longAt(row);
    }
  }

  /** Int64 column. */
  public static final class Int64Column extends Base {
    private final long[] values;

    Int64Column(ClickHouseType type, long[] values) {
      super(type, values.length);
      this.values = values;
    }

    /**
     * Returns the value at a row.
     *
     * @param row row index
     * @return the signed long value
     */
    public long longAt(int row) {
      checkRow(row);
      return values[row];
    }

    @Override
    public Object objectAt(int row) {
      return longAt(row);
    }
  }

  /** UInt64 column: raw longs with unsigned semantics plus a BigInteger path. */
  public static final class UInt64Column extends Base {
    private final long[] values;

    UInt64Column(ClickHouseType type, long[] values) {
      super(type, values.length);
      this.values = values;
    }

    /**
     * Returns the raw 64 bits at a row; interpret with unsigned semantics.
     *
     * @param row row index
     * @return the raw value
     */
    public long rawLongAt(int row) {
      checkRow(row);
      return values[row];
    }

    /**
     * Returns the value at a row as an unsigned BigInteger.
     *
     * @param row row index
     * @return the unsigned value
     */
    public BigInteger bigIntegerAt(int row) {
      checkRow(row);
      long raw = values[row];
      BigInteger result = BigInteger.valueOf(raw & Long.MAX_VALUE);
      return raw < 0 ? result.setBit(63) : result;
    }

    @Override
    public Object objectAt(int row) {
      return bigIntegerAt(row);
    }
  }

  /** Int128, UInt128, Int256 and UInt256 column over little endian fixed width bytes. */
  public static final class BigIntegerColumn extends Base {
    private final byte[] data;
    private final int width;
    private final boolean signed;

    BigIntegerColumn(ClickHouseType type, byte[] data, int width, boolean signed, int rows) {
      super(type, rows);
      this.data = data;
      this.width = width;
      this.signed = signed;
    }

    /**
     * Returns the value at a row.
     *
     * @param row row index
     * @return the value as a BigInteger
     */
    public BigInteger bigIntegerAt(int row) {
      checkRow(row);
      byte[] bigEndian = new byte[width];
      int base = row * width;
      for (int i = 0; i < width; i++) {
        bigEndian[i] = data[base + width - 1 - i];
      }
      return signed ? new BigInteger(bigEndian) : new BigInteger(1, bigEndian);
    }

    @Override
    public Object objectAt(int row) {
      return bigIntegerAt(row);
    }
  }

  /** Float32 column. */
  public static final class Float32Column extends Base {
    private final float[] values;

    Float32Column(ClickHouseType type, float[] values) {
      super(type, values.length);
      this.values = values;
    }

    /**
     * Returns the value at a row.
     *
     * @param row row index
     * @return the float value
     */
    public float floatAt(int row) {
      checkRow(row);
      return values[row];
    }

    @Override
    public Object objectAt(int row) {
      return floatAt(row);
    }
  }

  /** Float64 column. */
  public static final class Float64Column extends Base {
    private final double[] values;

    Float64Column(ClickHouseType type, double[] values) {
      super(type, values.length);
      this.values = values;
    }

    /**
     * Returns the value at a row.
     *
     * @param row row index
     * @return the double value
     */
    public double doubleAt(int row) {
      checkRow(row);
      return values[row];
    }

    @Override
    public Object objectAt(int row) {
      return doubleAt(row);
    }
  }

  /** BFloat16 column: raw 16 bit payloads widened to float on access. */
  public static final class BFloat16Column extends Base {
    private final short[] values;

    BFloat16Column(ClickHouseType type, short[] values) {
      super(type, values.length);
      this.values = values;
    }

    /**
     * Returns the value at a row widened to a float.
     *
     * @param row row index
     * @return the float value
     */
    public float floatAt(int row) {
      checkRow(row);
      return Float.intBitsToFloat((values[row] & 0xFFFF) << 16);
    }

    @Override
    public Object objectAt(int row) {
      return floatAt(row);
    }
  }

  /** String column: one shared payload with per row offsets; values are byte strings. */
  public static final class StringColumn extends Base {
    private final byte[] data;
    private final int[] offsets;

    StringColumn(ClickHouseType type, byte[] data, int[] offsets, int rows) {
      super(type, rows);
      this.data = data;
      this.offsets = offsets;
    }

    /**
     * Returns the value at a row decoded as UTF-8.
     *
     * @param row row index
     * @return the string value
     */
    public String stringAt(int row) {
      checkRow(row);
      int start = row == 0 ? 0 : offsets[row - 1];
      return new String(data, start, offsets[row] - start, StandardCharsets.UTF_8);
    }

    /**
     * Returns the raw bytes at a row; ClickHouse strings are byte strings.
     *
     * @param row row index
     * @return a copy of the raw bytes
     */
    public byte[] bytesAt(int row) {
      checkRow(row);
      int start = row == 0 ? 0 : offsets[row - 1];
      return Arrays.copyOfRange(data, start, offsets[row]);
    }

    @Override
    public Object objectAt(int row) {
      return stringAt(row);
    }
  }

  /** FixedString column. */
  public static final class FixedStringColumn extends Base {
    private final byte[] data;
    private final int length;

    FixedStringColumn(ClickHouseType type, byte[] data, int length, int rows) {
      super(type, rows);
      this.data = data;
      this.length = length;
    }

    /**
     * Returns the raw fixed width bytes at a row.
     *
     * @param row row index
     * @return a copy of the raw bytes, exactly the declared length
     */
    public byte[] bytesAt(int row) {
      checkRow(row);
      return Arrays.copyOfRange(data, row * length, row * length + length);
    }

    /**
     * Returns the value at a row decoded as UTF-8 with trailing zero bytes stripped.
     *
     * @param row row index
     * @return the string value
     */
    public String stringAt(int row) {
      checkRow(row);
      int start = row * length;
      int end = start + length;
      while (end > start && data[end - 1] == 0) {
        end--;
      }
      return new String(data, start, end - start, StandardCharsets.UTF_8);
    }

    @Override
    public Object objectAt(int row) {
      return stringAt(row);
    }
  }

  /** Date column: unsigned days since the epoch. */
  public static final class DateColumn extends Base {
    private final short[] days;

    DateColumn(ClickHouseType type, short[] days) {
      super(type, days.length);
      this.days = days;
    }

    /**
     * Returns the value at a row.
     *
     * @param row row index
     * @return the local date
     */
    public LocalDate localDateAt(int row) {
      checkRow(row);
      return LocalDate.ofEpochDay(days[row] & 0xFFFF);
    }

    @Override
    public Object objectAt(int row) {
      return localDateAt(row);
    }
  }

  /** Date32 column: signed days since the epoch. */
  public static final class Date32Column extends Base {
    private final int[] days;

    Date32Column(ClickHouseType type, int[] days) {
      super(type, days.length);
      this.days = days;
    }

    /**
     * Returns the value at a row.
     *
     * @param row row index
     * @return the local date
     */
    public LocalDate localDateAt(int row) {
      checkRow(row);
      return LocalDate.ofEpochDay(days[row]);
    }

    @Override
    public Object objectAt(int row) {
      return localDateAt(row);
    }
  }

  /** Time column: signed seconds beyond calendar semantics, surfacing as {@link Duration}. */
  public static final class TimeColumn extends Base {
    private final int[] seconds;

    TimeColumn(ClickHouseType type, int[] seconds) {
      super(type, seconds.length);
      this.seconds = seconds;
    }

    /**
     * Returns the signed duration at a row.
     *
     * @param row row index
     * @return the duration
     */
    public Duration durationAt(int row) {
      checkRow(row);
      return Duration.ofSeconds(seconds[row]);
    }

    int rawSecondsAt(int row) {
      checkRow(row);
      return seconds[row];
    }

    @Override
    public Object objectAt(int row) {
      return durationAt(row);
    }
  }

  /** Time64 column: signed sub second ticks at a fixed precision, surfacing as {@link Duration}. */
  public static final class Time64Column extends Base {
    private final long[] ticks;
    private final int precision;

    Time64Column(ClickHouseType type, long[] ticks, int precision) {
      super(type, ticks.length);
      this.ticks = ticks;
      this.precision = precision;
    }

    /**
     * Returns the signed duration at a row.
     *
     * @param row row index
     * @return the duration
     */
    public Duration durationAt(int row) {
      checkRow(row);
      long value = ticks[row];
      long perSecond = 1;
      for (int i = 0; i < precision; i++) {
        perSecond *= 10;
      }
      long seconds = Math.floorDiv(value, perSecond);
      long fraction = Math.floorMod(value, perSecond);
      long nanosPerTick = 1_000_000_000L;
      for (int i = 0; i < precision; i++) {
        nanosPerTick /= 10;
      }
      return Duration.ofSeconds(seconds, fraction * nanosPerTick);
    }

    long rawTicksAt(int row) {
      checkRow(row);
      return ticks[row];
    }

    @Override
    public Object objectAt(int row) {
      return durationAt(row);
    }
  }

  /** DateTime column: unsigned seconds since the epoch with its column or session zone. */
  public static final class DateTimeColumn extends Base {
    private final int[] seconds;
    private final ZoneId zone;

    DateTimeColumn(ClickHouseType type, int[] seconds, ZoneId zone) {
      super(type, seconds.length);
      this.seconds = seconds;
      this.zone = zone;
    }

    /**
     * Returns the instant at a row.
     *
     * @param row row index
     * @return the instant
     */
    public Instant instantAt(int row) {
      checkRow(row);
      return Instant.ofEpochSecond(seconds[row] & 0xFFFF_FFFFL);
    }

    /**
     * Returns the timezone the column carries: the column parameter when present, otherwise the
     * server session timezone at decode time.
     *
     * @return the zone
     */
    public ZoneId zone() {
      return zone;
    }

    int rawSecondsAt(int row) {
      checkRow(row);
      return seconds[row];
    }

    @Override
    public Object objectAt(int row) {
      return instantAt(row);
    }
  }

  /** DateTime64 column: signed ticks at the declared precision. */
  public static final class DateTime64Column extends Base {
    private final long[] ticks;
    private final int precision;
    private final ZoneId zone;

    DateTime64Column(ClickHouseType type, long[] ticks, int precision, ZoneId zone) {
      super(type, ticks.length);
      this.ticks = ticks;
      this.precision = precision;
      this.zone = zone;
    }

    /**
     * Returns the instant at a row.
     *
     * @param row row index
     * @return the instant
     */
    public Instant instantAt(int row) {
      checkRow(row);
      long value = ticks[row];
      long perSecond = pow10(precision);
      long seconds = Math.floorDiv(value, perSecond);
      long fraction = Math.floorMod(value, perSecond);
      long nanos = fraction * pow10(9 - precision);
      return Instant.ofEpochSecond(seconds, nanos);
    }

    private static long pow10(int exponent) {
      long result = 1;
      for (int i = 0; i < exponent; i++) {
        result *= 10;
      }
      return result;
    }

    /**
     * Returns the timezone the column carries: the column parameter when present, otherwise the
     * server session timezone at decode time.
     *
     * @return the zone
     */
    public ZoneId zone() {
      return zone;
    }

    long rawTicksAt(int row) {
      checkRow(row);
      return ticks[row];
    }

    @Override
    public Object objectAt(int row) {
      return instantAt(row);
    }
  }

  /** UUID column. */
  public static final class UuidColumn extends Base {
    private final long[] mostSignificant;
    private final long[] leastSignificant;

    UuidColumn(ClickHouseType type, long[] mostSignificant, long[] leastSignificant) {
      super(type, mostSignificant.length);
      this.mostSignificant = mostSignificant;
      this.leastSignificant = leastSignificant;
    }

    /**
     * Returns the value at a row.
     *
     * @param row row index
     * @return the UUID
     */
    public UUID uuidAt(int row) {
      checkRow(row);
      return new UUID(mostSignificant[row], leastSignificant[row]);
    }

    @Override
    public Object objectAt(int row) {
      return uuidAt(row);
    }
  }

  /** IPv4 column: unsigned 32 bit addresses. */
  public static final class Ipv4Column extends Base {
    private final int[] values;

    Ipv4Column(ClickHouseType type, int[] values) {
      super(type, values.length);
      this.values = values;
    }

    /**
     * Returns the value at a row.
     *
     * @param row row index
     * @return the address
     */
    public InetAddress inetAddressAt(int row) {
      checkRow(row);
      int value = values[row];
      byte[] bytes = {
        (byte) (value >>> 24), (byte) (value >>> 16), (byte) (value >>> 8), (byte) value
      };
      try {
        return InetAddress.getByAddress(bytes);
      } catch (UnknownHostException e) {
        throw new IllegalStateException(e);
      }
    }

    @Override
    public Object objectAt(int row) {
      return inetAddressAt(row);
    }
  }

  /** IPv6 column: sixteen network order bytes per value. */
  public static final class Ipv6Column extends Base {
    private final byte[] data;

    Ipv6Column(ClickHouseType type, byte[] data, int rows) {
      super(type, rows);
      this.data = data;
    }

    /**
     * Returns the value at a row. IPv4 mapped addresses surface as {@link java.net.Inet4Address}
     * per Java's address model; the wire form is always sixteen bytes.
     *
     * @param row row index
     * @return the address
     */
    public InetAddress inetAddressAt(int row) {
      checkRow(row);
      try {
        return InetAddress.getByAddress(Arrays.copyOfRange(data, row * 16, row * 16 + 16));
      } catch (UnknownHostException e) {
        throw new IllegalStateException(e);
      }
    }

    byte[] rawAt(int row) {
      checkRow(row);
      return Arrays.copyOfRange(data, row * 16, row * 16 + 16);
    }

    @Override
    public Object objectAt(int row) {
      return inetAddressAt(row);
    }
  }

  /** Enum8 and Enum16 column: numeric values with label resolution. */
  public static final class EnumColumn extends Base {
    private final short[] values;
    private final EnumType enumType;

    EnumColumn(EnumType type, short[] values) {
      super(type, values.length);
      this.values = values;
      this.enumType = type;
    }

    /**
     * Returns the numeric value at a row.
     *
     * @param row row index
     * @return the enum value
     */
    public int valueAt(int row) {
      checkRow(row);
      return values[row];
    }

    /**
     * Returns the label at a row.
     *
     * @param row row index
     * @return the enum label
     */
    public String labelAt(int row) {
      return enumType.labelFor(valueAt(row));
    }

    @Override
    public Object objectAt(int row) {
      return labelAt(row);
    }
  }

  /** Decimal column over 32, 64, 128 or 256 bit unscaled little endian storage. */
  public static final class DecimalColumn extends Base {
    private final byte[] data;
    private final int width;
    private final int scale;

    DecimalColumn(ClickHouseType type, byte[] data, int width, int scale, int rows) {
      super(type, rows);
      this.data = data;
      this.width = width;
      this.scale = scale;
    }

    /**
     * Returns the value at a row.
     *
     * @param row row index
     * @return the decimal value
     */
    public BigDecimal bigDecimalAt(int row) {
      checkRow(row);
      byte[] bigEndian = new byte[width];
      int base = row * width;
      for (int i = 0; i < width; i++) {
        bigEndian[i] = data[base + width - 1 - i];
      }
      return new BigDecimal(new BigInteger(bigEndian), scale);
    }

    @Override
    public Object objectAt(int row) {
      return bigDecimalAt(row);
    }
  }

  /** Interval column: signed counts of the interval kind. */
  public static final class IntervalColumn extends Base {
    private final long[] values;

    IntervalColumn(ClickHouseType type, long[] values) {
      super(type, values.length);
      this.values = values;
    }

    /**
     * Returns the count at a row; the unit is the interval kind of the type.
     *
     * @param row row index
     * @return the count
     */
    public long longAt(int row) {
      checkRow(row);
      return values[row];
    }

    @Override
    public Object objectAt(int row) {
      return longAt(row);
    }
  }

  /** Nothing column: no values, only a row count. */
  public static final class NothingColumn extends Base {
    NothingColumn(ClickHouseType type, int rows) {
      super(type, rows);
    }

    @Override
    public Object objectAt(int row) {
      checkRow(row);
      return null;
    }

    @Override
    public boolean isNullAt(int row) {
      checkRow(row);
      return true;
    }
  }

  /** Nullable wrapper: a null map over an inner column. */
  public static final class NullableColumn extends Base {
    private final byte[] nullMap;
    private final Column inner;

    NullableColumn(ClickHouseType type, byte[] nullMap, Column inner) {
      super(type, nullMap.length);
      this.nullMap = nullMap;
      this.inner = inner;
    }

    /**
     * Returns the inner column carrying the non null values.
     *
     * @return the inner column
     */
    public Column inner() {
      return inner;
    }

    @Override
    public boolean isNullAt(int row) {
      checkRow(row);
      return nullMap[row] != 0;
    }

    @Override
    public Object objectAt(int row) {
      return isNullAt(row) ? null : inner.objectAt(row);
    }
  }

  /** Array column: cumulative offsets over a flattened element column. */
  public static final class ArrayColumn extends Base {
    private final long[] offsets;
    private final Column elements;

    ArrayColumn(ClickHouseType type, long[] offsets, Column elements) {
      super(type, offsets.length);
      this.offsets = offsets;
      this.elements = elements;
    }

    /**
     * Returns the flattened element column shared by every row.
     *
     * @return the element column
     */
    public Column elements() {
      return elements;
    }

    long[] rawOffsets() {
      return offsets;
    }

    /**
     * Returns the value at a row as an unmodifiable list view over the flattened elements.
     *
     * @param row row index
     * @return the list of boxed element values
     */
    public List<Object> listAt(int row) {
      checkRow(row);
      int start = (int) (row == 0 ? 0 : offsets[row - 1]);
      int end = (int) offsets[row];
      return new AbstractList<>() {
        @Override
        public Object get(int index) {
          java.util.Objects.checkIndex(index, end - start);
          return elements.objectAt(start + index);
        }

        @Override
        public int size() {
          return end - start;
        }
      };
    }

    @Override
    public Object objectAt(int row) {
      return listAt(row);
    }
  }

  /** Tuple column: element columns side by side. */
  public static final class TupleColumn extends Base {
    private final Column[] elements;

    TupleColumn(ClickHouseType type, Column[] elements, int rows) {
      super(type, rows);
      this.elements = elements;
    }

    /**
     * Returns one element column.
     *
     * @param index element position
     * @return the element column
     */
    public Column element(int index) {
      return elements[index];
    }

    /**
     * Returns the value at a row as a fixed size list of the element values; elements of nullable
     * types may be {@code null}.
     *
     * @param row row index
     * @return the tuple values
     */
    public List<Object> tupleAt(int row) {
      checkRow(row);
      Object[] values = new Object[elements.length];
      for (int i = 0; i < elements.length; i++) {
        values[i] = elements[i].objectAt(row);
      }
      return Arrays.asList(values);
    }

    @Override
    public Object objectAt(int row) {
      return tupleAt(row);
    }
  }

  /** Map column: offsets over flattened key and value columns, preserving wire order. */
  public static final class MapColumn extends Base {
    private final long[] offsets;
    private final Column keys;
    private final Column values;

    MapColumn(ClickHouseType type, long[] offsets, Column keys, Column values) {
      super(type, offsets.length);
      this.offsets = offsets;
      this.keys = keys;
      this.values = values;
    }

    long[] rawOffsets() {
      return offsets;
    }

    Column rawKeys() {
      return keys;
    }

    Column rawValues() {
      return values;
    }

    /**
     * Returns the value at a row as an insertion ordered map.
     *
     * @param row row index
     * @return the map value
     */
    public Map<Object, Object> mapAt(int row) {
      checkRow(row);
      int start = (int) (row == 0 ? 0 : offsets[row - 1]);
      int end = (int) offsets[row];
      Map<Object, Object> result = new LinkedHashMap<>();
      for (int i = start; i < end; i++) {
        result.put(keys.objectAt(i), values.objectAt(i));
      }
      return result;
    }

    @Override
    public Object objectAt(int row) {
      return mapAt(row);
    }
  }

  static Column int8(ClickHouseType type, byte[] values) {
    return new Int8Column(type, values);
  }

  static Column uint8(ClickHouseType type, byte[] values) {
    return new UInt8Column(type, values);
  }

  static Column bool(ClickHouseType type, byte[] values) {
    return new BoolColumn(type, values);
  }

  static Column int16(ClickHouseType type, short[] values) {
    return new Int16Column(type, values);
  }

  static Column uint16(ClickHouseType type, short[] values) {
    return new UInt16Column(type, values);
  }

  static Column int32(ClickHouseType type, int[] values) {
    return new Int32Column(type, values);
  }

  static Column uint32(ClickHouseType type, int[] values) {
    return new UInt32Column(type, values);
  }

  static Column int64(ClickHouseType type, long[] values) {
    return new Int64Column(type, values);
  }

  static Column uint64(ClickHouseType type, long[] values) {
    return new UInt64Column(type, values);
  }

  /**
   * LowCardinality column: a dictionary of distinct values plus one dictionary index per row.
   *
   * <p>Slot 0 of the dictionary is the inner type's default value, or NULL when the inner type is
   * nullable, mirroring the server's dictionary layout. {@link #objectAt(int)} resolves through the
   * dictionary transparently, so consumers see plain values.
   */
  public static final class LowCardinalityColumn extends Base {
    private final Column dictionary;
    private final int[] indexes;
    private final boolean nullable;

    LowCardinalityColumn(ClickHouseType type, Column dictionary, int[] indexes, boolean nullable) {
      super(type, indexes.length);
      this.dictionary = dictionary;
      this.indexes = indexes;
      this.nullable = nullable;
    }

    /**
     * Returns the dictionary of distinct values, decoded as the non nullable inner type; slot 0
     * holds the default value and represents NULL when the column is nullable.
     *
     * @return the dictionary column
     */
    public Column dictionary() {
      return dictionary;
    }

    /**
     * Returns the dictionary index of a row.
     *
     * @param row row index
     * @return the dictionary slot the row references
     */
    public int indexAt(int row) {
      checkRow(row);
      return indexes[row];
    }

    @Override
    public boolean isNullAt(int row) {
      checkRow(row);
      return nullable && indexes[row] == 0;
    }

    @Override
    public Object objectAt(int row) {
      checkRow(row);
      if (nullable && indexes[row] == 0) {
        return null;
      }
      return dictionary.objectAt(indexes[row]);
    }

    int[] rawIndexes() {
      return indexes;
    }

    boolean nullableDictionary() {
      return nullable;
    }
  }

  /**
   * Variant column: one global discriminator per row selecting among the variant columns, with 255
   * marking NULL. {@link #objectAt(int)} resolves the discriminator transparently.
   */
  public static final class VariantColumn extends Base {
    /** The wire value marking a NULL row. */
    public static final int NULL_DISCRIMINATOR = 255;

    private final byte[] discriminators;
    private final List<Column> variants;
    private final int[] positionsInVariant;

    VariantColumn(
        ClickHouseType type, byte[] discriminators, List<Column> variants, int[] positions) {
      super(type, discriminators.length);
      this.discriminators = discriminators;
      this.variants = List.copyOf(variants);
      this.positionsInVariant = positions;
    }

    /**
     * Returns the global discriminator of a row: the index into {@link #variants()}, or {@link
     * #NULL_DISCRIMINATOR} for a NULL row.
     *
     * @param row row index
     * @return the discriminator
     */
    public int discriminatorAt(int row) {
      checkRow(row);
      return discriminators[row] & 0xFF;
    }

    /**
     * Returns the per variant value columns in global discriminator order.
     *
     * @return the variant columns
     */
    public List<Column> variants() {
      return variants;
    }

    @Override
    public boolean isNullAt(int row) {
      return discriminatorAt(row) == NULL_DISCRIMINATOR;
    }

    @Override
    public Object objectAt(int row) {
      int discriminator = discriminatorAt(row);
      if (discriminator == NULL_DISCRIMINATOR) {
        return null;
      }
      return variants.get(discriminator).objectAt(positionsInVariant[row]);
    }

    int positionInVariantAt(int row) {
      checkRow(row);
      return positionsInVariant[row];
    }
  }

  /**
   * Dynamic column: per row values of varying types, carried as a Variant over the types the block
   * declared plus the shared variant for overflow types.
   *
   * <p>Rows whose type overflowed into the shared variant are not decoded in this phase: {@link
   * #objectAt(int)} fails explicitly for them and {@link #sharedVariantRawAt(int)} exposes the raw
   * bytes (a binary encoded type followed by the binary value).
   */
  public static final class DynamicColumn extends Base {
    private final VariantColumn variant;
    private final int sharedVariantDiscriminator;
    private final List<ClickHouseType> dynamicTypes;

    DynamicColumn(
        ClickHouseType type,
        VariantColumn variant,
        int sharedVariantDiscriminator,
        List<ClickHouseType> dynamicTypes) {
      super(type, variant.size());
      this.variant = variant;
      this.sharedVariantDiscriminator = sharedVariantDiscriminator;
      this.dynamicTypes = List.copyOf(dynamicTypes);
    }

    VariantColumn variant() {
      return variant;
    }

    /**
     * Returns the concrete types this column's block declared, in global discriminator order and
     * excluding the shared variant.
     *
     * @return the dynamic types
     */
    public List<ClickHouseType> dynamicTypes() {
      return dynamicTypes;
    }

    /**
     * Returns the name of the concrete type of a row, empty for NULL rows.
     *
     * @param row row index
     * @return the type name, or empty
     */
    public java.util.Optional<String> typeNameAt(int row) {
      int discriminator = variant.discriminatorAt(row);
      if (discriminator == VariantColumn.NULL_DISCRIMINATOR) {
        return java.util.Optional.empty();
      }
      if (discriminator == sharedVariantDiscriminator) {
        return java.util.Optional.of("SharedVariant");
      }
      return java.util.Optional.of(variant.variants().get(discriminator).type().name());
    }

    /**
     * Returns the raw shared variant bytes of a row: a binary encoded type followed by the value in
     * single value binary format. Only valid for rows whose {@link #typeNameAt(int)} is {@code
     * SharedVariant}.
     *
     * @param row row index
     * @return the raw bytes
     */
    public byte[] sharedVariantRawAt(int row) {
      int discriminator = variant.discriminatorAt(row);
      if (discriminator != sharedVariantDiscriminator) {
        throw new IllegalArgumentException("Row " + row + " is not stored in the shared variant");
      }
      StringColumn shared = (StringColumn) variant.variants().get(sharedVariantDiscriminator);
      return shared.bytesAt(variant.positionInVariantAt(row));
    }

    @Override
    public boolean isNullAt(int row) {
      return variant.isNullAt(row);
    }

    @Override
    public Object objectAt(int row) {
      int discriminator = variant.discriminatorAt(row);
      if (discriminator == sharedVariantDiscriminator) {
        throw new io.github.orhaugh.chord.ChordTypeException(
            "Row "
                + row
                + " of this Dynamic column overflowed into the shared variant; its value is not"
                + " decoded in this phase. Use sharedVariantRawAt for the raw bytes.");
      }
      return variant.objectAt(row);
    }
  }

  /**
   * A shared data value of a JSON column that CHord does not decode: the raw bytes carry a binary
   * encoded type followed by the value in single value binary format.
   *
   * @param bytes the raw type and value bytes
   */
  public record RawJsonValue(byte[] bytes) {
    /** Copies the array defensively. */
    public RawJsonValue {
      bytes = bytes.clone();
    }

    /**
     * Returns a copy of the raw bytes.
     *
     * @return the bytes
     */
    @Override
    public byte[] bytes() {
      return bytes.clone();
    }
  }

  /**
   * JSON column: typed paths from the type declaration, dynamic paths discovered per block, and
   * shared data for paths beyond the dynamic path budget.
   *
   * <p>{@link #objectAt(int)} returns a map from path to value: typed paths always present, dynamic
   * paths present when non NULL, shared data entries present as {@link RawJsonValue}.
   */
  public static final class JsonColumn extends Base {
    private final Map<String, Column> typedPaths;
    private final Map<String, DynamicColumn> dynamicPaths;
    private final MapColumn sharedData;

    JsonColumn(
        ClickHouseType type,
        int rows,
        Map<String, Column> typedPaths,
        Map<String, DynamicColumn> dynamicPaths,
        MapColumn sharedData) {
      super(type, rows);
      this.typedPaths = new LinkedHashMap<>(typedPaths);
      this.dynamicPaths = new LinkedHashMap<>(dynamicPaths);
      this.sharedData = sharedData;
    }

    MapColumn sharedData() {
      return sharedData;
    }

    /**
     * Returns the typed path columns declared by the JSON type, in sorted path order.
     *
     * @return path to column
     */
    public Map<String, Column> typedPaths() {
      return java.util.Collections.unmodifiableMap(typedPaths);
    }

    /**
     * Returns the dynamic path columns this block carried, in sorted path order.
     *
     * @return path to column
     */
    public Map<String, DynamicColumn> dynamicPaths() {
      return java.util.Collections.unmodifiableMap(dynamicPaths);
    }

    @Override
    public Object objectAt(int row) {
      checkRow(row);
      Map<String, Object> values = new LinkedHashMap<>();
      for (Map.Entry<String, Column> entry : typedPaths.entrySet()) {
        values.put(entry.getKey(), entry.getValue().objectAt(row));
      }
      for (Map.Entry<String, DynamicColumn> entry : dynamicPaths.entrySet()) {
        DynamicColumn column = entry.getValue();
        if (!column.isNullAt(row)) {
          values.put(entry.getKey(), column.objectAt(row));
        }
      }
      StringColumn sharedKeys = (StringColumn) sharedData.rawKeys();
      StringColumn sharedValues = (StringColumn) sharedData.rawValues();
      long[] offsets = sharedData.rawOffsets();
      int start = row == 0 ? 0 : (int) offsets[row - 1];
      int end = (int) offsets[row];
      for (int i = start; i < end; i++) {
        values.put(sharedKeys.stringAt(i), new RawJsonValue(sharedValues.bytesAt(i)));
      }
      return values;
    }
  }
}
