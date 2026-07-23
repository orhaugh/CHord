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

import io.github.orhaugh.chord.ChordTypeException;
import io.github.orhaugh.chord.codec.UnsupportedClickHouseTypeException;
import io.github.orhaugh.chord.codec.block.Block;
import io.github.orhaugh.chord.codec.block.BlockInfo;
import io.github.orhaugh.chord.codec.type.ClickHouseType;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Builds native blocks from Java values for INSERT, converting and validating each value against
 * the target column type at append time so failures carry the row and column they belong to.
 *
 * <p>Conversions are strict and lossless: integers are range checked per width and signedness,
 * decimals must rescale exactly, DateTime64 values must not lose sub tick precision, FixedString
 * values are zero padded but never truncated, and enum values must name an existing label. NULL is
 * only accepted for {@code Nullable} columns. Values that would lose data raise {@link
 * ChordTypeException} rather than being coerced.
 */
public final class BlockBuilder {

  private final List<String> names;
  private final List<Appender> appenders;
  private int rows;

  private BlockBuilder(List<String> names, List<Appender> appenders) {
    this.names = names;
    this.appenders = appenders;
  }

  /**
   * Creates a builder targeting the schema of a header block, typically the block the server sends
   * when an INSERT begins.
   *
   * @param schema the schema block; row content is ignored
   * @return a builder with one appender per column
   */
  public static BlockBuilder forSchema(Block schema) {
    List<String> names = new ArrayList<>(schema.columnCount());
    List<Appender> appenders = new ArrayList<>(schema.columnCount());
    for (int i = 0; i < schema.columnCount(); i++) {
      names.add(schema.columnName(i));
      appenders.add(appenderFor(schema.columnType(i)));
    }
    return new BlockBuilder(names, appenders);
  }

  /**
   * Creates a builder over bare column types with generated names, for callers assembling columns
   * outside an INSERT schema, such as sparse materialisation or locally built result sets.
   *
   * @param types one entry per column
   * @return a builder with one appender per column
   */
  public static BlockBuilder forColumnTypes(
      List<io.github.orhaugh.chord.codec.type.ClickHouseType> types) {
    return forColumnTypes(types, null);
  }

  /**
   * Creates a builder over bare column types with explicit names.
   *
   * @param types one entry per column
   * @param names column names, or {@code null} to generate positional names
   * @return a builder with one appender per column
   */
  public static BlockBuilder forColumnTypes(
      List<io.github.orhaugh.chord.codec.type.ClickHouseType> types, List<String> names) {
    List<String> columnNames = new ArrayList<>(types.size());
    List<Appender> appenders = new ArrayList<>(types.size());
    for (int i = 0; i < types.size(); i++) {
      columnNames.add(names != null ? names.get(i) : "c" + i);
      appenders.add(appenderFor(types.get(i)));
    }
    return new BlockBuilder(columnNames, appenders);
  }

  /**
   * Appends one row.
   *
   * @param values one value per column, in schema order; {@code null} only for Nullable columns
   * @return this builder
   */
  public BlockBuilder addRow(Object... values) {
    if (values.length != appenders.size()) {
      throw new ChordTypeException(
          "Row has "
              + values.length
              + " values but the schema has "
              + appenders.size()
              + " columns");
    }
    for (int i = 0; i < values.length; i++) {
      try {
        appenders.get(i).append(values[i]);
      } catch (ChordTypeException e) {
        throw new ChordTypeException(
            "Row " + rows + ", column \"" + names.get(i) + "\": " + e.getMessage());
      }
    }
    rows++;
    return this;
  }

  /**
   * Returns the number of rows appended so far.
   *
   * @return the row count
   */
  public int rows() {
    return rows;
  }

  /**
   * Builds the immutable block and resets this builder for the next batch.
   *
   * @return the built block
   */
  public Block build() {
    List<Block.NamedColumn> columns = new ArrayList<>(appenders.size());
    for (int i = 0; i < appenders.size(); i++) {
      columns.add(new Block.NamedColumn(names.get(i), appenders.get(i).finish()));
    }
    Block block = new Block(BlockInfo.DEFAULT, rows, columns);
    rows = 0;
    return block;
  }

  private interface Appender {
    void append(Object value);

    void appendDefault();

    Column finish();
  }

  private static ChordTypeException mismatch(Object value, ClickHouseType type) {
    String actual = value == null ? "NULL" : value.getClass().getSimpleName();
    return new ChordTypeException("Cannot convert " + actual + " to " + type.name());
  }

  private static long integralValue(Object value, ClickHouseType type) {
    if (value instanceof Byte
        || value instanceof Short
        || value instanceof Integer
        || value instanceof Long) {
      return ((Number) value).longValue();
    }
    if (value instanceof BigInteger big) {
      try {
        return big.longValueExact();
      } catch (ArithmeticException e) {
        throw new ChordTypeException("Value " + big + " does not fit " + type.name());
      }
    }
    throw mismatch(value, type);
  }

  private static long rangeChecked(Object value, ClickHouseType type, long min, long max) {
    long v = integralValue(value, type);
    if (v < min || v > max) {
      throw new ChordTypeException("Value " + v + " is outside the range of " + type.name());
    }
    return v;
  }

  static Appender appenderFor(ClickHouseType type) {
    return switch (type) {
      case ClickHouseType.IntegerType t -> integerAppender(t);
      case ClickHouseType.BoolType t -> new BoolAppender(t);
      case ClickHouseType.FloatType t ->
          t.bits() == 32 ? new Float32Appender(t) : new Float64Appender(t);
      case ClickHouseType.BFloat16Type t -> new BFloat16Appender(t);
      case ClickHouseType.StringType t -> new StringAppender(t);
      case ClickHouseType.FixedStringType t -> new FixedStringAppender(t);
      case ClickHouseType.DateType t -> new DateAppender(t);
      case ClickHouseType.Date32Type t -> new Date32Appender(t);
      case ClickHouseType.DateTimeType t -> new DateTimeAppender(t);
      case ClickHouseType.DateTime64Type t -> new DateTime64Appender(t);
      case ClickHouseType.TimeType t -> new TimeAppender(t);
      case ClickHouseType.Time64Type t -> new Time64Appender(t);
      case ClickHouseType.UuidType t -> new UuidAppender(t);
      case ClickHouseType.Ipv4Type t -> new Ipv4Appender(t);
      case ClickHouseType.Ipv6Type t -> new Ipv6Appender(t);
      case ClickHouseType.EnumType t -> new EnumAppender(t);
      case ClickHouseType.DecimalType t -> new DecimalAppender(t);
      case ClickHouseType.IntervalType t -> new IntervalAppender(t);
      case ClickHouseType.NullableType t -> new NullableAppender(t);
      case ClickHouseType.ArrayType t -> new ArrayAppender(t);
      case ClickHouseType.TupleType t -> new TupleAppender(t);
      case ClickHouseType.MapType t -> new MapAppender(t);
      case ClickHouseType.SimpleAggregateFunctionType t ->
          new DelegatingAppender(t, appenderFor(t.inner()));
      case ClickHouseType.NothingType t ->
          throw new UnsupportedClickHouseTypeException("Cannot insert values of type Nothing");
      case ClickHouseType.LowCardinalityType t -> new LowCardinalityAppender(t);
      case ClickHouseType.VariantType t ->
          throw new UnsupportedClickHouseTypeException(
              "Building Variant values client side is not supported; write the concrete column"
                  + " type instead: "
                  + t.name());
      case ClickHouseType.DynamicType t ->
          throw new UnsupportedClickHouseTypeException(
              "Building Dynamic values client side is not supported; write a concrete column type"
                  + " instead: "
                  + t.name());
      case ClickHouseType.JsonType t ->
          throw new UnsupportedClickHouseTypeException(
              "Building JSON values client side is not supported; insert JSON text through a"
                  + " String column with input format settings, or use HTTP formats: "
                  + t.name());
      case ClickHouseType.AggregateFunctionType t ->
          throw new UnsupportedClickHouseTypeException(
              "AggregateFunction states cannot be built client side: " + t.name());
      case ClickHouseType.NestedType t ->
          throw new UnsupportedClickHouseTypeException(
              "Insert into Nested columns through their flattened Array subcolumns: " + t.name());
    };
  }

  private static Appender integerAppender(ClickHouseType.IntegerType type) {
    return switch (type.bits()) {
      case 8 -> new Int8Appender(type);
      case 16 -> new Int16Appender(type);
      case 32 -> new Int32Appender(type);
      case 64 -> new Int64Appender(type);
      default -> new BigIntegerAppender(type);
    };
  }

  private abstract static class PrimitiveAppender implements Appender {
    final ClickHouseType type;
    int size;

    PrimitiveAppender(ClickHouseType type) {
      this.type = type;
    }
  }

  private static final class Int8Appender extends PrimitiveAppender {
    private byte[] values = new byte[16];
    private final boolean signed;

    Int8Appender(ClickHouseType.IntegerType type) {
      super(type);
      this.signed = type.signed();
    }

    @Override
    public void append(Object value) {
      long v =
          signed
              ? rangeChecked(value, type, Byte.MIN_VALUE, Byte.MAX_VALUE)
              : rangeChecked(value, type, 0, 0xFF);
      if (size == values.length) {
        values = Arrays.copyOf(values, size * 2);
      }
      values[size++] = (byte) v;
    }

    @Override
    public void appendDefault() {
      append(0);
    }

    @Override
    public Column finish() {
      byte[] exact = Arrays.copyOf(values, size);
      size = 0;
      return signed ? new Columns.Int8Column(type, exact) : new Columns.UInt8Column(type, exact);
    }
  }

  private static final class Int16Appender extends PrimitiveAppender {
    private short[] values = new short[16];
    private final boolean signed;

    Int16Appender(ClickHouseType.IntegerType type) {
      super(type);
      this.signed = type.signed();
    }

    @Override
    public void append(Object value) {
      long v =
          signed
              ? rangeChecked(value, type, Short.MIN_VALUE, Short.MAX_VALUE)
              : rangeChecked(value, type, 0, 0xFFFF);
      if (size == values.length) {
        values = Arrays.copyOf(values, size * 2);
      }
      values[size++] = (short) v;
    }

    @Override
    public void appendDefault() {
      append(0);
    }

    @Override
    public Column finish() {
      short[] exact = Arrays.copyOf(values, size);
      size = 0;
      return signed ? new Columns.Int16Column(type, exact) : new Columns.UInt16Column(type, exact);
    }
  }

  private static final class Int32Appender extends PrimitiveAppender {
    private int[] values = new int[16];
    private final boolean signed;

    Int32Appender(ClickHouseType.IntegerType type) {
      super(type);
      this.signed = type.signed();
    }

    @Override
    public void append(Object value) {
      long v =
          signed
              ? rangeChecked(value, type, Integer.MIN_VALUE, Integer.MAX_VALUE)
              : rangeChecked(value, type, 0, 0xFFFF_FFFFL);
      if (size == values.length) {
        values = Arrays.copyOf(values, size * 2);
      }
      values[size++] = (int) v;
    }

    @Override
    public void appendDefault() {
      append(0);
    }

    @Override
    public Column finish() {
      int[] exact = Arrays.copyOf(values, size);
      size = 0;
      return signed ? new Columns.Int32Column(type, exact) : new Columns.UInt32Column(type, exact);
    }
  }

  private static final class Int64Appender extends PrimitiveAppender {
    private long[] values = new long[16];
    private final boolean signed;

    Int64Appender(ClickHouseType.IntegerType type) {
      super(type);
      this.signed = type.signed();
    }

    @Override
    public void append(Object value) {
      long v;
      if (!signed && value instanceof BigInteger big) {
        if (big.signum() < 0 || big.bitLength() > 64) {
          throw new ChordTypeException("Value " + big + " does not fit " + type.name());
        }
        v = big.longValue();
      } else {
        v = integralValue(value, type);
        if (!signed && v < 0) {
          throw new ChordTypeException(
              "Value "
                  + v
                  + " is outside the range of "
                  + type.name()
                  + "; pass a BigInteger for values above Long.MAX_VALUE");
        }
      }
      if (size == values.length) {
        values = Arrays.copyOf(values, size * 2);
      }
      values[size++] = v;
    }

    @Override
    public void appendDefault() {
      append(0);
    }

    @Override
    public Column finish() {
      long[] exact = Arrays.copyOf(values, size);
      size = 0;
      return signed ? new Columns.Int64Column(type, exact) : new Columns.UInt64Column(type, exact);
    }
  }

  private static final class BigIntegerAppender extends PrimitiveAppender {
    private final int width;
    private final boolean signed;
    private byte[] data = new byte[256];

    BigIntegerAppender(ClickHouseType.IntegerType type) {
      super(type);
      this.width = type.bits() / 8;
      this.signed = type.signed();
    }

    @Override
    public void append(Object value) {
      BigInteger big;
      if (value instanceof BigInteger b) {
        big = b;
      } else if (value instanceof Byte
          || value instanceof Short
          || value instanceof Integer
          || value instanceof Long) {
        big = BigInteger.valueOf(((Number) value).longValue());
      } else {
        throw mismatch(value, type);
      }
      if (signed
          ? big.bitLength() >= width * 8
          : (big.signum() < 0 || big.bitLength() > width * 8)) {
        throw new ChordTypeException("Value " + big + " does not fit " + type.name());
      }
      ensure(width);
      byte[] bigEndian = big.toByteArray();
      byte fill = big.signum() < 0 ? (byte) 0xFF : 0;
      int significant = Math.min(bigEndian.length, width);
      for (int i = 0; i < width; i++) {
        data[size * width + i] = i < significant ? bigEndian[bigEndian.length - 1 - i] : fill;
      }
      size++;
    }

    private void ensure(int widthBytes) {
      if ((size + 1) * widthBytes > data.length) {
        data = Arrays.copyOf(data, data.length * 2);
      }
    }

    @Override
    public void appendDefault() {
      append(BigInteger.ZERO);
    }

    @Override
    public Column finish() {
      byte[] exact = Arrays.copyOf(data, size * width);
      int rows = size;
      size = 0;
      return new Columns.BigIntegerColumn(type, exact, width, signed, rows);
    }
  }

  private static final class BoolAppender extends PrimitiveAppender {
    private byte[] values = new byte[16];

    BoolAppender(ClickHouseType type) {
      super(type);
    }

    @Override
    public void append(Object value) {
      if (!(value instanceof Boolean b)) {
        throw mismatch(value, type);
      }
      if (size == values.length) {
        values = Arrays.copyOf(values, size * 2);
      }
      values[size++] = (byte) (b ? 1 : 0);
    }

    @Override
    public void appendDefault() {
      append(Boolean.FALSE);
    }

    @Override
    public Column finish() {
      byte[] exact = Arrays.copyOf(values, size);
      size = 0;
      return new Columns.BoolColumn(type, exact);
    }
  }

  private static final class Float32Appender extends PrimitiveAppender {
    private float[] values = new float[16];

    Float32Appender(ClickHouseType type) {
      super(type);
    }

    @Override
    public void append(Object value) {
      if (!(value instanceof Number n)) {
        throw mismatch(value, type);
      }
      if (size == values.length) {
        values = Arrays.copyOf(values, size * 2);
      }
      values[size++] = n.floatValue();
    }

    @Override
    public void appendDefault() {
      append(0f);
    }

    @Override
    public Column finish() {
      float[] exact = Arrays.copyOf(values, size);
      size = 0;
      return new Columns.Float32Column(type, exact);
    }
  }

  private static final class Float64Appender extends PrimitiveAppender {
    private double[] values = new double[16];

    Float64Appender(ClickHouseType type) {
      super(type);
    }

    @Override
    public void append(Object value) {
      if (!(value instanceof Number n)) {
        throw mismatch(value, type);
      }
      if (size == values.length) {
        values = Arrays.copyOf(values, size * 2);
      }
      values[size++] = n.doubleValue();
    }

    @Override
    public void appendDefault() {
      append(0d);
    }

    @Override
    public Column finish() {
      double[] exact = Arrays.copyOf(values, size);
      size = 0;
      return new Columns.Float64Column(type, exact);
    }
  }

  private static final class BFloat16Appender extends PrimitiveAppender {
    private short[] values = new short[16];

    BFloat16Appender(ClickHouseType type) {
      super(type);
    }

    @Override
    public void append(Object value) {
      if (!(value instanceof Number n)) {
        throw mismatch(value, type);
      }
      if (size == values.length) {
        values = Arrays.copyOf(values, size * 2);
      }
      // BFloat16 is the upper half of the float bit pattern; conversion truncates.
      values[size++] = (short) (Float.floatToRawIntBits(n.floatValue()) >>> 16);
    }

    @Override
    public void appendDefault() {
      append(0f);
    }

    @Override
    public Column finish() {
      short[] exact = Arrays.copyOf(values, size);
      size = 0;
      return new Columns.BFloat16Column(type, exact);
    }
  }

  private static final class StringAppender extends PrimitiveAppender {
    private final List<byte[]> values = new ArrayList<>();
    private long totalBytes;

    StringAppender(ClickHouseType type) {
      super(type);
    }

    @Override
    public void append(Object value) {
      byte[] bytes;
      if (value instanceof String s) {
        bytes = s.getBytes(StandardCharsets.UTF_8);
      } else if (value instanceof byte[] raw) {
        bytes = raw.clone();
      } else {
        throw mismatch(value, type);
      }
      values.add(bytes);
      totalBytes += bytes.length;
      size++;
    }

    @Override
    public void appendDefault() {
      append("");
    }

    @Override
    public Column finish() {
      byte[] data = new byte[(int) Math.min(totalBytes, Integer.MAX_VALUE - 8)];
      int[] offsets = new int[size];
      int used = 0;
      for (int i = 0; i < size; i++) {
        byte[] value = values.get(i);
        System.arraycopy(value, 0, data, used, value.length);
        used += value.length;
        offsets[i] = used;
      }
      int rows = size;
      values.clear();
      totalBytes = 0;
      size = 0;
      return new Columns.StringColumn(type, data, offsets, rows);
    }
  }

  private static final class FixedStringAppender extends PrimitiveAppender {
    private final int length;
    private byte[] data = new byte[64];

    FixedStringAppender(ClickHouseType.FixedStringType type) {
      super(type);
      this.length = type.length();
    }

    @Override
    public void append(Object value) {
      byte[] bytes;
      if (value instanceof String s) {
        bytes = s.getBytes(StandardCharsets.UTF_8);
      } else if (value instanceof byte[] raw) {
        bytes = raw;
      } else {
        throw mismatch(value, type);
      }
      if (bytes.length > length) {
        throw new ChordTypeException(
            "Value of " + bytes.length + " bytes does not fit " + type.name());
      }
      if ((size + 1) * length > data.length) {
        data = Arrays.copyOf(data, Math.max(data.length * 2, (size + 1) * length));
      }
      System.arraycopy(bytes, 0, data, size * length, bytes.length);
      // The remainder is already zero: FixedString pads with zero bytes.
      size++;
    }

    @Override
    public void appendDefault() {
      append(new byte[0]);
    }

    @Override
    public Column finish() {
      byte[] exact = Arrays.copyOf(data, size * length);
      int rows = size;
      size = 0;
      data = new byte[64];
      return new Columns.FixedStringColumn(type, exact, length, rows);
    }
  }

  private static final class DateAppender extends PrimitiveAppender {
    private short[] values = new short[16];

    DateAppender(ClickHouseType type) {
      super(type);
    }

    @Override
    public void append(Object value) {
      if (!(value instanceof LocalDate date)) {
        throw mismatch(value, type);
      }
      long epochDay = ColumnWriter.checkedEpochDay(date, "Date");
      if (size == values.length) {
        values = Arrays.copyOf(values, size * 2);
      }
      values[size++] = (short) epochDay;
    }

    @Override
    public void appendDefault() {
      append(LocalDate.EPOCH);
    }

    @Override
    public Column finish() {
      short[] exact = Arrays.copyOf(values, size);
      size = 0;
      return new Columns.DateColumn(type, exact);
    }
  }

  private static final class Date32Appender extends PrimitiveAppender {
    private int[] values = new int[16];

    Date32Appender(ClickHouseType type) {
      super(type);
    }

    @Override
    public void append(Object value) {
      if (!(value instanceof LocalDate date)) {
        throw mismatch(value, type);
      }
      long epochDay = ColumnWriter.checkedEpochDay(date, "Date32");
      if (size == values.length) {
        values = Arrays.copyOf(values, size * 2);
      }
      values[size++] = (int) epochDay;
    }

    @Override
    public void appendDefault() {
      append(LocalDate.EPOCH);
    }

    @Override
    public Column finish() {
      int[] exact = Arrays.copyOf(values, size);
      size = 0;
      return new Columns.Date32Column(type, exact);
    }
  }

  private static final class DateTimeAppender extends PrimitiveAppender {
    private int[] values = new int[16];
    private final ZoneId zone;

    DateTimeAppender(ClickHouseType.DateTimeType type) {
      super(type);
      this.zone = type.timezone().map(Timezones::parse).orElse(ZoneId.of("UTC"));
    }

    @Override
    public void append(Object value) {
      if (!(value instanceof Instant instant)) {
        throw mismatch(value, type);
      }
      long seconds = instant.getEpochSecond();
      if (instant.getNano() != 0) {
        throw new ChordTypeException(
            "DateTime has second precision; " + instant + " carries sub second time");
      }
      if (seconds < 0 || seconds > 0xFFFF_FFFFL) {
        throw new ChordTypeException(instant + " is outside the DateTime range");
      }
      if (size == values.length) {
        values = Arrays.copyOf(values, size * 2);
      }
      values[size++] = (int) seconds;
    }

    @Override
    public void appendDefault() {
      append(Instant.EPOCH);
    }

    @Override
    public Column finish() {
      int[] exact = Arrays.copyOf(values, size);
      size = 0;
      return new Columns.DateTimeColumn(type, exact, zone);
    }
  }

  private static final class DateTime64Appender extends PrimitiveAppender {
    private long[] values = new long[16];
    private final int precision;
    private final ZoneId zone;

    DateTime64Appender(ClickHouseType.DateTime64Type type) {
      super(type);
      this.precision = type.precision();
      this.zone = type.timezone().map(Timezones::parse).orElse(ZoneId.of("UTC"));
    }

    @Override
    public void append(Object value) {
      if (!(value instanceof Instant instant)) {
        throw mismatch(value, type);
      }
      long perSecond = pow10(precision);
      long nanosPerTick = pow10(9 - precision);
      if (instant.getNano() % nanosPerTick != 0) {
        throw new ChordTypeException(
            instant + " carries more precision than " + type.name() + " stores");
      }
      long ticks;
      try {
        ticks =
            Math.addExact(
                Math.multiplyExact(instant.getEpochSecond(), perSecond),
                instant.getNano() / nanosPerTick);
      } catch (ArithmeticException e) {
        throw new ChordTypeException(instant + " is outside the " + type.name() + " range");
      }
      if (size == values.length) {
        values = Arrays.copyOf(values, size * 2);
      }
      values[size++] = ticks;
    }

    private static long pow10(int exponent) {
      long result = 1;
      for (int i = 0; i < exponent; i++) {
        result *= 10;
      }
      return result;
    }

    @Override
    public void appendDefault() {
      append(Instant.EPOCH);
    }

    @Override
    public Column finish() {
      long[] exact = Arrays.copyOf(values, size);
      size = 0;
      return new Columns.DateTime64Column(type, exact, precision, zone);
    }
  }

  private static final class TimeAppender extends PrimitiveAppender {
    /** ClickHouse bounds Time to [-999:59:59, 999:59:59]. */
    private static final long MAX_SECONDS = 999L * 3600 + 59 * 60 + 59;

    private int[] values = new int[16];

    TimeAppender(ClickHouseType.TimeType type) {
      super(type);
    }

    @Override
    public void append(Object value) {
      if (!(value instanceof java.time.Duration duration)) {
        throw mismatch(value, type);
      }
      if (duration.getNano() != 0) {
        throw new ChordTypeException(
            duration + " carries more precision than Time stores; use Time64");
      }
      long seconds = duration.getSeconds();
      if (seconds < -MAX_SECONDS || seconds > MAX_SECONDS) {
        throw new ChordTypeException(duration + " is outside the Time range of +-999:59:59");
      }
      if (size == values.length) {
        values = Arrays.copyOf(values, size * 2);
      }
      values[size++] = (int) seconds;
    }

    @Override
    public void appendDefault() {
      append(java.time.Duration.ZERO);
    }

    @Override
    public Column finish() {
      int[] exact = Arrays.copyOf(values, size);
      size = 0;
      return new Columns.TimeColumn(type, exact);
    }
  }

  private static final class Time64Appender extends PrimitiveAppender {
    private static final long MAX_SECONDS = 999L * 3600 + 59 * 60 + 59;

    private long[] values = new long[16];
    private final int precision;

    Time64Appender(ClickHouseType.Time64Type type) {
      super(type);
      this.precision = type.precision();
    }

    @Override
    public void append(Object value) {
      if (!(value instanceof java.time.Duration duration)) {
        throw mismatch(value, type);
      }
      long perSecond = pow10(precision);
      long nanosPerTick = pow10(9 - precision);
      if (duration.getNano() % nanosPerTick != 0) {
        throw new ChordTypeException(
            duration + " carries more precision than " + type.name() + " stores");
      }
      long ticks;
      try {
        ticks =
            Math.addExact(
                Math.multiplyExact(duration.getSeconds(), perSecond),
                duration.getNano() / nanosPerTick);
      } catch (ArithmeticException e) {
        throw new ChordTypeException(
            duration + " is outside the " + type.name() + " range of +-999:59:59");
      }
      // The bound covers the fractional part: +-999:59:59 point whatever the precision holds.
      long maxTicks = MAX_SECONDS * perSecond + (perSecond - 1);
      if (ticks < -maxTicks || ticks > maxTicks) {
        throw new ChordTypeException(
            duration + " is outside the " + type.name() + " range of +-999:59:59");
      }
      if (size == values.length) {
        values = Arrays.copyOf(values, size * 2);
      }
      values[size++] = ticks;
    }

    private static long pow10(int exponent) {
      long result = 1;
      for (int i = 0; i < exponent; i++) {
        result *= 10;
      }
      return result;
    }

    @Override
    public void appendDefault() {
      append(java.time.Duration.ZERO);
    }

    @Override
    public Column finish() {
      long[] exact = Arrays.copyOf(values, size);
      size = 0;
      return new Columns.Time64Column(type, exact, precision);
    }
  }

  private static final class UuidAppender extends PrimitiveAppender {
    private long[] most = new long[16];
    private long[] least = new long[16];

    UuidAppender(ClickHouseType type) {
      super(type);
    }

    @Override
    public void append(Object value) {
      if (!(value instanceof UUID uuid)) {
        throw mismatch(value, type);
      }
      if (size == most.length) {
        most = Arrays.copyOf(most, size * 2);
        least = Arrays.copyOf(least, size * 2);
      }
      most[size] = uuid.getMostSignificantBits();
      least[size] = uuid.getLeastSignificantBits();
      size++;
    }

    @Override
    public void appendDefault() {
      append(new UUID(0, 0));
    }

    @Override
    public Column finish() {
      Column column =
          new Columns.UuidColumn(type, Arrays.copyOf(most, size), Arrays.copyOf(least, size));
      size = 0;
      return column;
    }
  }

  private static final class Ipv4Appender extends PrimitiveAppender {
    private int[] values = new int[16];

    Ipv4Appender(ClickHouseType type) {
      super(type);
    }

    @Override
    public void append(Object value) {
      if (!(value instanceof InetAddress address) || address.getAddress().length != 4) {
        throw mismatch(value, type);
      }
      byte[] bytes = address.getAddress();
      if (size == values.length) {
        values = Arrays.copyOf(values, size * 2);
      }
      values[size++] =
          ((bytes[0] & 0xFF) << 24)
              | ((bytes[1] & 0xFF) << 16)
              | ((bytes[2] & 0xFF) << 8)
              | (bytes[3] & 0xFF);
    }

    @Override
    public void appendDefault() {
      if (size == values.length) {
        values = Arrays.copyOf(values, size * 2);
      }
      values[size++] = 0;
    }

    @Override
    public Column finish() {
      int[] exact = Arrays.copyOf(values, size);
      size = 0;
      return new Columns.Ipv4Column(type, exact);
    }
  }

  private static final class Ipv6Appender extends PrimitiveAppender {
    private byte[] data = new byte[256];

    Ipv6Appender(ClickHouseType type) {
      super(type);
    }

    @Override
    public void append(Object value) {
      if (!(value instanceof InetAddress address)) {
        throw mismatch(value, type);
      }
      byte[] bytes = address.getAddress();
      byte[] sixteen;
      if (bytes.length == 16) {
        sixteen = bytes;
      } else if (address instanceof Inet4Address) {
        // Store IPv4 addresses in their IPv4 mapped IPv6 form, as the server does.
        sixteen = new byte[16];
        sixteen[10] = (byte) 0xFF;
        sixteen[11] = (byte) 0xFF;
        System.arraycopy(bytes, 0, sixteen, 12, 4);
      } else {
        throw mismatch(value, type);
      }
      if ((size + 1) * 16 > data.length) {
        data = Arrays.copyOf(data, data.length * 2);
      }
      System.arraycopy(sixteen, 0, data, size * 16, 16);
      size++;
    }

    @Override
    public void appendDefault() {
      if ((size + 1) * 16 > data.length) {
        data = Arrays.copyOf(data, data.length * 2);
      }
      size++;
    }

    @Override
    public Column finish() {
      byte[] exact = Arrays.copyOf(data, size * 16);
      int rows = size;
      size = 0;
      data = new byte[256];
      return new Columns.Ipv6Column(type, exact, rows);
    }
  }

  private static final class EnumAppender extends PrimitiveAppender {
    private final ClickHouseType.EnumType enumType;
    private short[] values = new short[16];

    EnumAppender(ClickHouseType.EnumType type) {
      super(type);
      this.enumType = type;
    }

    @Override
    public void append(Object value) {
      int numeric;
      if (value instanceof String label) {
        numeric = valueForLabel(label);
      } else if (value instanceof Byte || value instanceof Short || value instanceof Integer) {
        numeric = ((Number) value).intValue();
        enumType.labelFor(numeric); // validates the value names a label
      } else {
        throw mismatch(value, type);
      }
      if (size == values.length) {
        values = Arrays.copyOf(values, size * 2);
      }
      values[size++] = (short) numeric;
    }

    private int valueForLabel(String label) {
      for (ClickHouseType.EnumEntry entry : enumType.entries()) {
        if (entry.label().equals(label)) {
          return entry.value();
        }
      }
      throw new ChordTypeException("Label \"" + label + "\" is not part of " + type.name());
    }

    @Override
    public void appendDefault() {
      if (size == values.length) {
        values = Arrays.copyOf(values, size * 2);
      }
      values[size++] = 0;
    }

    @Override
    public Column finish() {
      short[] exact = Arrays.copyOf(values, size);
      size = 0;
      return new Columns.EnumColumn(enumType, exact);
    }
  }

  private static final class DecimalAppender extends PrimitiveAppender {
    private final ClickHouseType.DecimalType decimalType;
    private final int width;
    private byte[] data = new byte[256];

    DecimalAppender(ClickHouseType.DecimalType type) {
      super(type);
      this.decimalType = type;
      this.width = type.storageBits() / 8;
    }

    @Override
    public void append(Object value) {
      if (!(value instanceof BigDecimal decimal)) {
        throw mismatch(value, type);
      }
      BigDecimal scaled;
      try {
        scaled = decimal.setScale(decimalType.scale(), RoundingMode.UNNECESSARY);
      } catch (ArithmeticException e) {
        throw new ChordTypeException(
            decimal
                + " cannot be represented exactly at scale "
                + decimalType.scale()
                + " of "
                + type.name());
      }
      BigInteger unscaled = scaled.unscaledValue();
      if (unscaled.bitLength() >= width * 8) {
        throw new ChordTypeException(decimal + " does not fit " + type.name());
      }
      if ((size + 1) * width > data.length) {
        data = Arrays.copyOf(data, data.length * 2);
      }
      byte[] bigEndian = unscaled.toByteArray();
      byte fill = unscaled.signum() < 0 ? (byte) 0xFF : 0;
      int significant = Math.min(bigEndian.length, width);
      for (int i = 0; i < width; i++) {
        data[size * width + i] = i < significant ? bigEndian[bigEndian.length - 1 - i] : fill;
      }
      size++;
    }

    @Override
    public void appendDefault() {
      append(BigDecimal.valueOf(0, decimalType.scale()));
    }

    @Override
    public Column finish() {
      byte[] exact = Arrays.copyOf(data, size * width);
      int rows = size;
      size = 0;
      data = new byte[256];
      return new Columns.DecimalColumn(type, exact, width, decimalType.scale(), rows);
    }
  }

  private static final class IntervalAppender extends PrimitiveAppender {
    private long[] values = new long[16];

    IntervalAppender(ClickHouseType type) {
      super(type);
    }

    @Override
    public void append(Object value) {
      long v = integralValue(value, type);
      if (size == values.length) {
        values = Arrays.copyOf(values, size * 2);
      }
      values[size++] = v;
    }

    @Override
    public void appendDefault() {
      append(0L);
    }

    @Override
    public Column finish() {
      long[] exact = Arrays.copyOf(values, size);
      size = 0;
      return new Columns.IntervalColumn(type, exact);
    }
  }

  private static final class NullableAppender implements Appender {
    private final ClickHouseType type;
    private final Appender inner;
    private byte[] nullMap = new byte[16];
    private int size;

    NullableAppender(ClickHouseType.NullableType type) {
      this.type = type;
      this.inner = appenderFor(type.inner());
    }

    @Override
    public void append(Object value) {
      if (size == nullMap.length) {
        nullMap = Arrays.copyOf(nullMap, size * 2);
      }
      if (value == null) {
        nullMap[size++] = 1;
        inner.appendDefault();
      } else {
        nullMap[size++] = 0;
        inner.append(value);
      }
    }

    @Override
    public void appendDefault() {
      append(null);
    }

    @Override
    public Column finish() {
      byte[] exact = Arrays.copyOf(nullMap, size);
      size = 0;
      return new Columns.NullableColumn(type, exact, inner.finish());
    }
  }

  private static final class ArrayAppender implements Appender {
    private final ClickHouseType type;
    private final Appender elements;
    private long[] offsets = new long[16];
    private long total;
    private int size;

    ArrayAppender(ClickHouseType.ArrayType type) {
      this.type = type;
      this.elements = appenderFor(type.element());
    }

    @Override
    public void append(Object value) {
      Collection<?> collection;
      if (value instanceof Collection<?> c) {
        collection = c;
      } else if (value instanceof Object[] array) {
        collection = Arrays.asList(array);
      } else {
        throw mismatch(value, type);
      }
      for (Object element : collection) {
        elements.append(element);
      }
      total += collection.size();
      if (size == offsets.length) {
        offsets = Arrays.copyOf(offsets, size * 2);
      }
      offsets[size++] = total;
    }

    @Override
    public void appendDefault() {
      append(List.of());
    }

    @Override
    public Column finish() {
      long[] exact = Arrays.copyOf(offsets, size);
      size = 0;
      total = 0;
      return new Columns.ArrayColumn(type, exact, elements.finish());
    }
  }

  private static final class TupleAppender implements Appender {
    private final ClickHouseType.TupleType type;
    private final Appender[] elements;
    private int size;

    TupleAppender(ClickHouseType.TupleType type) {
      this.type = type;
      this.elements = new Appender[type.elements().size()];
      for (int i = 0; i < elements.length; i++) {
        elements[i] = appenderFor(type.elements().get(i).type());
      }
    }

    @Override
    public void append(Object value) {
      if (!(value instanceof List<?> list) || list.size() != elements.length) {
        throw mismatch(value, type);
      }
      for (int i = 0; i < elements.length; i++) {
        elements[i].append(list.get(i));
      }
      size++;
    }

    @Override
    public void appendDefault() {
      for (Appender element : elements) {
        element.appendDefault();
      }
      size++;
    }

    @Override
    public Column finish() {
      Column[] columns = new Column[elements.length];
      for (int i = 0; i < elements.length; i++) {
        columns[i] = elements[i].finish();
      }
      int rows = size;
      size = 0;
      return new Columns.TupleColumn(type, columns, rows);
    }
  }

  private static final class MapAppender implements Appender {
    private final ClickHouseType type;
    private final Appender keys;
    private final Appender values;
    private long[] offsets = new long[16];
    private long total;
    private int size;

    MapAppender(ClickHouseType.MapType type) {
      this.type = type;
      this.keys = appenderFor(type.key());
      this.values = appenderFor(type.value());
    }

    @Override
    public void append(Object value) {
      if (!(value instanceof Map<?, ?> map)) {
        throw mismatch(value, type);
      }
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        keys.append(entry.getKey());
        values.append(entry.getValue());
      }
      total += map.size();
      if (size == offsets.length) {
        offsets = Arrays.copyOf(offsets, size * 2);
      }
      offsets[size++] = total;
    }

    @Override
    public void appendDefault() {
      append(Map.of());
    }

    @Override
    public Column finish() {
      long[] exact = Arrays.copyOf(offsets, size);
      size = 0;
      total = 0;
      return new Columns.MapColumn(type, exact, keys.finish(), values.finish());
    }
  }

  /**
   * Builds a LowCardinality column: values append through the inner type's appender with its full
   * validation, and {@link #finish()} dictionary encodes the built values. Slot 0 of the dictionary
   * is the inner default, representing NULL for nullable inner types, mirroring the server's
   * dictionary layout.
   */
  private static final class LowCardinalityAppender implements Appender {
    private final ClickHouseType.LowCardinalityType type;
    private final Appender values;

    LowCardinalityAppender(ClickHouseType.LowCardinalityType type) {
      this.type = type;
      this.values = appenderFor(type.inner());
    }

    @Override
    public void append(Object value) {
      values.append(value);
    }

    @Override
    public void appendDefault() {
      values.appendDefault();
    }

    @Override
    public Column finish() {
      Column full = values.finish();
      boolean nullable = type.inner() instanceof ClickHouseType.NullableType;
      ClickHouseType keyType =
          type.inner() instanceof ClickHouseType.NullableType n ? n.inner() : type.inner();
      Appender dictionary = appenderFor(keyType);
      dictionary.appendDefault(); // slot 0: the default value, or NULL for nullable inner types
      java.util.Map<Object, Integer> slots = new java.util.HashMap<>();
      int[] indexes = new int[full.size()];
      int nextSlot = 1;
      for (int row = 0; row < full.size(); row++) {
        Object value = full.objectAt(row);
        if (value == null) {
          indexes[row] = 0;
          continue;
        }
        Object key = value instanceof byte[] bytes ? java.nio.ByteBuffer.wrap(bytes) : value;
        Integer slot = slots.get(key);
        if (slot == null) {
          slot = nextSlot++;
          slots.put(key, slot);
          dictionary.append(value);
        }
        indexes[row] = slot;
      }
      return new Columns.LowCardinalityColumn(type, dictionary.finish(), indexes, nullable);
    }
  }

  private record DelegatingAppender(ClickHouseType type, Appender inner) implements Appender {
    @Override
    public void append(Object value) {
      inner.append(value);
    }

    @Override
    public void appendDefault() {
      inner.appendDefault();
    }

    @Override
    public Column finish() {
      return inner.finish();
    }
  }
}
