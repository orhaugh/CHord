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
import io.github.orhaugh.chord.protocol.wire.WireWriter;
import java.math.BigInteger;
import java.time.LocalDate;

/**
 * Encodes one column into the native wire format, the write side counterpart of {@link
 * ColumnReader}. Any decoded or built {@link Column} of a supported type round trips exactly;
 * columns of types CHord cannot decode cannot be built either, so encoding them is unreachable and
 * rejected defensively.
 */
public final class ColumnWriter {

  private ColumnWriter() {}

  /**
   * Writes a column's values in wire order.
   *
   * @param out writer to encode into
   * @param column the column to encode
   */
  public static void write(WireWriter out, Column column) {
    switch (column) {
      case Columns.Int8Column c -> {
        for (int i = 0; i < c.size(); i++) {
          out.writeUInt8(c.byteAt(i));
        }
      }
      case Columns.UInt8Column c -> {
        for (int i = 0; i < c.size(); i++) {
          out.writeUInt8(c.intAt(i));
        }
      }
      case Columns.BoolColumn c -> {
        for (int i = 0; i < c.size(); i++) {
          out.writeBool(c.booleanAt(i));
        }
      }
      case Columns.Int16Column c -> {
        for (int i = 0; i < c.size(); i++) {
          writeInt16(out, c.shortAt(i));
        }
      }
      case Columns.UInt16Column c -> {
        for (int i = 0; i < c.size(); i++) {
          writeInt16(out, (short) c.intAt(i));
        }
      }
      case Columns.Int32Column c -> {
        for (int i = 0; i < c.size(); i++) {
          out.writeInt32Le(c.intAt(i));
        }
      }
      case Columns.UInt32Column c -> {
        for (int i = 0; i < c.size(); i++) {
          out.writeInt32Le((int) c.longAt(i));
        }
      }
      case Columns.Int64Column c -> {
        for (int i = 0; i < c.size(); i++) {
          out.writeInt64Le(c.longAt(i));
        }
      }
      case Columns.UInt64Column c -> {
        for (int i = 0; i < c.size(); i++) {
          out.writeInt64Le(c.rawLongAt(i));
        }
      }
      case Columns.BigIntegerColumn c -> writeBigIntegers(out, c);
      case Columns.Float32Column c -> {
        for (int i = 0; i < c.size(); i++) {
          out.writeInt32Le(Float.floatToRawIntBits(c.floatAt(i)));
        }
      }
      case Columns.Float64Column c -> {
        for (int i = 0; i < c.size(); i++) {
          out.writeInt64Le(Double.doubleToRawLongBits(c.doubleAt(i)));
        }
      }
      case Columns.BFloat16Column c -> {
        for (int i = 0; i < c.size(); i++) {
          // The decode path widened the sixteen stored bits into the float's upper half.
          writeInt16(out, (short) (Float.floatToRawIntBits(c.floatAt(i)) >>> 16));
        }
      }
      case Columns.StringColumn c -> {
        for (int i = 0; i < c.size(); i++) {
          out.writeString(c.bytesAt(i));
        }
      }
      case Columns.FixedStringColumn c -> {
        for (int i = 0; i < c.size(); i++) {
          byte[] value = c.bytesAt(i);
          out.writeBytes(value, 0, value.length);
        }
      }
      case Columns.DateColumn c -> {
        for (int i = 0; i < c.size(); i++) {
          writeInt16(out, (short) c.localDateAt(i).toEpochDay());
        }
      }
      case Columns.Date32Column c -> {
        for (int i = 0; i < c.size(); i++) {
          out.writeInt32Le((int) c.localDateAt(i).toEpochDay());
        }
      }
      case Columns.DateTimeColumn c -> {
        for (int i = 0; i < c.size(); i++) {
          out.writeInt32Le(c.rawSecondsAt(i));
        }
      }
      case Columns.DateTime64Column c -> {
        for (int i = 0; i < c.size(); i++) {
          out.writeInt64Le(c.rawTicksAt(i));
        }
      }
      case Columns.UuidColumn c -> {
        for (int i = 0; i < c.size(); i++) {
          out.writeInt64Le(c.uuidAt(i).getMostSignificantBits());
          out.writeInt64Le(c.uuidAt(i).getLeastSignificantBits());
        }
      }
      case Columns.Ipv4Column c -> {
        for (int i = 0; i < c.size(); i++) {
          byte[] address = c.inetAddressAt(i).getAddress();
          out.writeInt32Le(
              ((address[0] & 0xFF) << 24)
                  | ((address[1] & 0xFF) << 16)
                  | ((address[2] & 0xFF) << 8)
                  | (address[3] & 0xFF));
        }
      }
      case Columns.Ipv6Column c -> {
        for (int i = 0; i < c.size(); i++) {
          // Raw storage, not InetAddress: Java collapses IPv4 mapped addresses to four bytes.
          byte[] address = c.rawAt(i);
          out.writeBytes(address, 0, 16);
        }
      }
      case Columns.EnumColumn c -> {
        boolean wide =
            ((io.github.orhaugh.chord.codec.type.ClickHouseType.EnumType) c.type()).bits() == 16;
        for (int i = 0; i < c.size(); i++) {
          if (wide) {
            writeInt16(out, (short) c.valueAt(i));
          } else {
            out.writeUInt8(c.valueAt(i));
          }
        }
      }
      case Columns.DecimalColumn c -> writeDecimals(out, c);
      case Columns.IntervalColumn c -> {
        for (int i = 0; i < c.size(); i++) {
          out.writeInt64Le(c.longAt(i));
        }
      }
      case Columns.NothingColumn c -> {
        for (int i = 0; i < c.size(); i++) {
          out.writeUInt8(0);
        }
      }
      case Columns.NullableColumn c -> {
        for (int i = 0; i < c.size(); i++) {
          out.writeBool(c.isNullAt(i));
        }
        write(out, c.inner());
      }
      case Columns.ArrayColumn c -> {
        long[] offsets = c.rawOffsets();
        for (long offset : offsets) {
          out.writeInt64Le(offset);
        }
        write(out, c.elements());
      }
      case Columns.TupleColumn c -> {
        int arity =
            ((io.github.orhaugh.chord.codec.type.ClickHouseType.TupleType) c.type())
                .elements()
                .size();
        for (int e = 0; e < arity; e++) {
          write(out, c.element(e));
        }
      }
      case Columns.MapColumn c -> {
        long[] offsets = c.rawOffsets();
        for (long offset : offsets) {
          out.writeInt64Le(offset);
        }
        write(out, c.rawKeys());
        write(out, c.rawValues());
      }
      default ->
          throw new UnsupportedClickHouseTypeException(
              "Cannot encode column type " + column.type().name());
    }
  }

  private static void writeInt16(WireWriter out, short value) {
    out.writeUInt8(value & 0xFF);
    out.writeUInt8((value >>> 8) & 0xFF);
  }

  private static void writeBigIntegers(WireWriter out, Columns.BigIntegerColumn c) {
    var type = (io.github.orhaugh.chord.codec.type.ClickHouseType.IntegerType) c.type();
    int width = type.bits() / 8;
    for (int i = 0; i < c.size(); i++) {
      writeFixedWidthLittleEndian(out, c.bigIntegerAt(i), width, type.signed(), type.name());
    }
  }

  private static void writeDecimals(WireWriter out, Columns.DecimalColumn c) {
    var type = (io.github.orhaugh.chord.codec.type.ClickHouseType.DecimalType) c.type();
    int width = type.storageBits() / 8;
    for (int i = 0; i < c.size(); i++) {
      BigInteger unscaled = c.bigDecimalAt(i).unscaledValue();
      writeFixedWidthLittleEndian(out, unscaled, width, true, type.name());
    }
  }

  /**
   * Writes a BigInteger as fixed width little endian two's complement, validating the range.
   *
   * @param out writer to encode into
   * @param value the value
   * @param width storage width in bytes
   * @param signed whether the type is signed
   * @param typeName type name for error messages
   */
  static void writeFixedWidthLittleEndian(
      WireWriter out, BigInteger value, int width, boolean signed, String typeName) {
    if (signed) {
      if (value.bitLength() >= width * 8) {
        throw new ChordTypeException("Value " + value + " does not fit " + typeName);
      }
    } else {
      if (value.signum() < 0 || value.bitLength() > width * 8) {
        throw new ChordTypeException("Value " + value + " does not fit " + typeName);
      }
    }
    byte[] bigEndian = value.toByteArray();
    byte fill = value.signum() < 0 ? (byte) 0xFF : 0;
    // toByteArray may carry one leading sign byte beyond the width for unsigned maxima.
    int significant = Math.min(bigEndian.length, width);
    int sourceStart = bigEndian.length - significant;
    byte[] littleEndian = new byte[width];
    for (int i = 0; i < width; i++) {
      littleEndian[i] = i < significant ? bigEndian[bigEndian.length - 1 - i] : fill;
    }
    if (sourceStart > 0) {
      for (int i = 0; i < sourceStart; i++) {
        byte extra = bigEndian[i];
        if (extra != 0 && extra != (byte) 0xFF) {
          throw new ChordTypeException("Value " + value + " does not fit " + typeName);
        }
      }
    }
    out.writeBytes(littleEndian, 0, width);
  }

  /**
   * Reads a {@link LocalDate} range check helper shared with the builder.
   *
   * @param date the date
   * @param typeName type name for error messages
   * @return the epoch day
   */
  static long checkedEpochDay(LocalDate date, String typeName) {
    long epochDay = date.toEpochDay();
    if (typeName.equals("Date") && (epochDay < 0 || epochDay > 0xFFFF)) {
      throw new ChordTypeException("Date " + date + " is outside the Date range");
    }
    if (typeName.equals("Date32")
        && (epochDay < Integer.MIN_VALUE || epochDay > Integer.MAX_VALUE)) {
      throw new ChordTypeException("Date " + date + " is outside the Date32 range");
    }
    return epochDay;
  }
}
