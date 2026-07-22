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
import io.github.orhaugh.chord.codec.type.ClickHouseType.ArrayType;
import io.github.orhaugh.chord.codec.type.ClickHouseType.DynamicType;
import io.github.orhaugh.chord.codec.type.ClickHouseType.JsonType;
import io.github.orhaugh.chord.codec.type.ClickHouseType.LowCardinalityType;
import io.github.orhaugh.chord.codec.type.ClickHouseType.MapType;
import io.github.orhaugh.chord.codec.type.ClickHouseType.NullableType;
import io.github.orhaugh.chord.codec.type.ClickHouseType.SimpleAggregateFunctionType;
import io.github.orhaugh.chord.codec.type.ClickHouseType.StringType;
import io.github.orhaugh.chord.codec.type.ClickHouseType.TupleElement;
import io.github.orhaugh.chord.codec.type.ClickHouseType.TupleType;
import io.github.orhaugh.chord.codec.type.ClickHouseType.VariantType;
import io.github.orhaugh.chord.codec.type.TypeParser;
import io.github.orhaugh.chord.protocol.wire.WireReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Decoding of the revision gated serialisations: bulk state prefixes, LowCardinality, Variant,
 * Dynamic, JSON and the sparse wrapper.
 *
 * <p>Wire layouts mirror the ClickHouse sources exactly: {@code SerializationLowCardinality},
 * {@code SerializationVariant}, {@code SerializationDynamic}, {@code SerializationObject} and
 * {@code SerializationSparse}, in the single stream layout the Native format uses (position
 * independent encoding off, one state per block). Unknown versions and modes fail explicitly before
 * any value bytes are consumed.
 */
final class Serializations {

  /** {@code KeysSerializationVersion::SharedDictionariesWithAdditionalKeys}. */
  private static final long LOW_CARDINALITY_KEY_VERSION = 1;

  /** {@code IndexesSerializationType} flag bits. */
  private static final long LC_NEED_GLOBAL_DICTIONARY_BIT = 1L << 8;

  private static final long LC_HAS_ADDITIONAL_KEYS_BIT = 1L << 9;
  private static final long LC_NEED_UPDATE_DICTIONARY_BIT = 1L << 10;
  private static final long LC_FLAG_BITS =
      LC_NEED_GLOBAL_DICTIONARY_BIT | LC_HAS_ADDITIONAL_KEYS_BIT | LC_NEED_UPDATE_DICTIONARY_BIT;

  /** {@code SerializationVariant::DiscriminatorsSerializationMode}. */
  private static final long VARIANT_MODE_BASIC = 0;

  private static final long VARIANT_MODE_COMPACT = 1;

  /** {@code CompactDiscriminatorsGranuleFormat}. */
  private static final int VARIANT_GRANULE_PLAIN = 0;

  private static final int VARIANT_GRANULE_COMPACT = 1;

  /** {@code SerializationDynamic::SerializationVersion}: V1=1, V2=2, FLATTENED=3, V3=4. */
  private static final long DYNAMIC_V1 = 1;

  private static final long DYNAMIC_V2 = 2;

  /** {@code SerializationObject::SerializationVersion}: V1=0, STRING=1, V2=2, FLAT=3, V3=4. */
  private static final long OBJECT_V1 = 0;

  private static final long OBJECT_V2 = 2;

  /** {@code SerializationSparse}: trailing defaults marker, {@code 1ULL << 62}. */
  private static final long SPARSE_END_OF_GRANULE_FLAG = 1L << 62;

  /** The Dynamic overflow variant: a String type with a custom name, always present. */
  static final String SHARED_VARIANT_NAME = "SharedVariant";

  /** Upper bound on declared dynamic types per column; the server caps at 254. */
  private static final int MAX_DYNAMIC_TYPES = 1000;

  /** Upper bound on declared dynamic paths per column; the server default budget is 1024. */
  private static final int MAX_DYNAMIC_PATHS = 100_000;

  private Serializations() {}

  /** The per column state produced by the bulk prefix phase, mirroring the type structure. */
  sealed interface Prefix {
    /** Leaf types and containers of only leaves have no prefix state. */
    record None() implements Prefix {}

    /** A container's prefix: the children's prefixes in substream order. */
    record Nested(List<Prefix> children) implements Prefix {
      /** Copies the list. */
      public Nested {
        children = List.copyOf(children);
      }
    }

    /** LowCardinality: the keys serialisation version was read and checked. */
    record LowCardinality() implements Prefix {}

    /** Variant: the discriminators mode plus per variant nested prefixes. */
    record Variant(long mode, List<Prefix> variantPrefixes) implements Prefix {
      /** Copies the list. */
      public Variant {
        variantPrefixes = List.copyOf(variantPrefixes);
      }
    }

    /**
     * Dynamic: the types this block declared, the synthesised variant order (types plus the shared
     * variant, sorted by name) and the inner variant prefix.
     */
    record Dynamic(
        List<ClickHouseType> dynamicTypes,
        List<ClickHouseType> variantOrder,
        int sharedVariantIndex,
        Variant variantPrefix)
        implements Prefix {
      /** Copies the lists. */
      public Dynamic {
        dynamicTypes = List.copyOf(dynamicTypes);
        variantOrder = List.copyOf(variantOrder);
      }
    }

    /** JSON: dynamic paths this block declared plus per path prefixes. */
    record Json(
        List<String> sortedTypedPaths,
        Map<String, ClickHouseType> typedPathTypes,
        List<Prefix> typedPathPrefixes,
        List<String> dynamicPaths,
        List<Dynamic> dynamicPathPrefixes)
        implements Prefix {
      /** Copies the collections. */
      public Json {
        sortedTypedPaths = List.copyOf(sortedTypedPaths);
        typedPathTypes = Map.copyOf(typedPathTypes);
        typedPathPrefixes = List.copyOf(typedPathPrefixes);
        dynamicPaths = List.copyOf(dynamicPaths);
        dynamicPathPrefixes = List.copyOf(dynamicPathPrefixes);
      }
    }
  }

  static final Prefix NONE = new Prefix.None();

  /**
   * Reads the bulk state prefix for a column of the given type, mirroring {@code
   * deserializeBinaryBulkStatePrefix}: containers recurse in substream order, LowCardinality
   * consumes its keys version, Variant its discriminators mode, Dynamic and JSON their structure.
   */
  static Prefix readPrefix(WireReader in, ClickHouseType type, DecodeContext context) {
    return switch (type) {
      case NullableType t -> new Prefix.Nested(List.of(readPrefix(in, t.inner(), context)));
      case ArrayType t -> new Prefix.Nested(List.of(readPrefix(in, t.element(), context)));
      case MapType t ->
          new Prefix.Nested(
              List.of(readPrefix(in, t.key(), context), readPrefix(in, t.value(), context)));
      case TupleType t -> {
        List<Prefix> children = new ArrayList<>(t.elements().size());
        for (TupleElement element : t.elements()) {
          children.add(readPrefix(in, element.type(), context));
        }
        yield new Prefix.Nested(children);
      }
      case SimpleAggregateFunctionType t -> readPrefix(in, t.inner(), context);
      case LowCardinalityType t -> {
        long version = in.readInt64Le();
        if (version != LOW_CARDINALITY_KEY_VERSION) {
          throw new ChordProtocolException(
              "Unknown LowCardinality keys serialisation version " + version);
        }
        yield new Prefix.LowCardinality();
      }
      case VariantType t -> readVariantPrefix(in, t.alternatives(), context);
      case DynamicType t -> readDynamicPrefix(in, context);
      case JsonType t -> readJsonPrefix(in, t, context);
      default -> NONE;
    };
  }

  private static Prefix.Variant readVariantPrefix(
      WireReader in, List<ClickHouseType> variants, DecodeContext context) {
    long mode = in.readInt64Le();
    if (mode != VARIANT_MODE_BASIC && mode != VARIANT_MODE_COMPACT) {
      throw new ChordProtocolException("Unknown Variant discriminators mode " + mode);
    }
    List<Prefix> nested = new ArrayList<>(variants.size());
    for (ClickHouseType variant : variants) {
      nested.add(readPrefix(in, variant, context));
    }
    return new Prefix.Variant(mode, nested);
  }

  private static Prefix.Dynamic readDynamicPrefix(WireReader in, DecodeContext context) {
    long version = in.readInt64Le();
    if (version != DYNAMIC_V1 && version != DYNAMIC_V2) {
      throw new UnsupportedClickHouseTypeException(
          "Dynamic structure serialisation version "
              + version
              + " is not supported; CHord supports V1 and V2, the versions servers send over the"
              + " native protocol");
    }
    if (version == DYNAMIC_V1) {
      in.readVarUInt(); // legacy max_dynamic_types, replaced by the count below
    }
    long declared = in.readVarUInt();
    if (Long.compareUnsigned(declared, MAX_DYNAMIC_TYPES) > 0) {
      throw new ChordProtocolException(
          "Dynamic column declares " + Long.toUnsignedString(declared) + " types");
    }
    int count = (int) declared;
    List<ClickHouseType> dynamicTypes = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      String name = in.readString(context.limits().maxTypeNameLength());
      dynamicTypes.add(
          TypeParser.parse(
              name, context.limits().maxTypeNameLength(), context.limits().maxTypeDepth()));
    }
    // The variant order is the sorted union of the declared type names and the shared variant,
    // matching the name sorted map inside DataTypeVariant.
    TreeMap<String, ClickHouseType> byName = new TreeMap<>();
    for (ClickHouseType dynamicType : dynamicTypes) {
      byName.put(dynamicType.name(), dynamicType);
    }
    byName.put(SHARED_VARIANT_NAME, new StringType());
    List<ClickHouseType> variantOrder = new ArrayList<>(byName.values());
    int sharedIndex = new ArrayList<>(byName.keySet()).indexOf(SHARED_VARIANT_NAME);
    // Statistics are absent on the wire: the server writes them only for MergeTree parts.
    Prefix.Variant variantPrefix = readVariantPrefix(in, variantOrder, context);
    return new Prefix.Dynamic(dynamicTypes, variantOrder, sharedIndex, variantPrefix);
  }

  private static Prefix.Json readJsonPrefix(WireReader in, JsonType type, DecodeContext context) {
    long version = in.readInt64Le();
    if (version != OBJECT_V1 && version != OBJECT_V2) {
      throw new UnsupportedClickHouseTypeException(
          "JSON object serialisation version "
              + version
              + " is not supported; CHord supports V1 and V2, the versions servers send over the"
              + " native protocol");
    }
    if (version == OBJECT_V1) {
      in.readVarUInt(); // legacy max_dynamic_paths, replaced by the count below
    }
    long declared = in.readVarUInt();
    if (Long.compareUnsigned(declared, MAX_DYNAMIC_PATHS) > 0) {
      throw new ChordProtocolException(
          "JSON column declares " + Long.toUnsignedString(declared) + " dynamic paths");
    }
    int pathCount = (int) declared;
    List<String> dynamicPaths = new ArrayList<>(pathCount);
    for (int i = 0; i < pathCount; i++) {
      dynamicPaths.add(in.readString(context.limits().maxStringValueBytes()));
    }
    // Typed paths come from the type declaration, serialised in sorted path order.
    Map<String, ClickHouseType> typedPathTypes =
        JsonTypeArguments.typedPaths(
            type, context.limits().maxTypeNameLength(), context.limits().maxTypeDepth());
    List<String> sortedTypedPaths = new ArrayList<>(typedPathTypes.keySet());
    List<Prefix> typedPathPrefixes = new ArrayList<>(sortedTypedPaths.size());
    for (String path : sortedTypedPaths) {
      typedPathPrefixes.add(readPrefix(in, typedPathTypes.get(path), context));
    }
    List<Prefix.Dynamic> dynamicPathPrefixes = new ArrayList<>(pathCount);
    for (int i = 0; i < pathCount; i++) {
      dynamicPathPrefixes.add(readDynamicPrefix(in, context));
    }
    // The shared data serialises as Map(String, String), which has no prefix.
    return new Prefix.Json(
        sortedTypedPaths, typedPathTypes, typedPathPrefixes, dynamicPaths, dynamicPathPrefixes);
  }

  /** Reads a LowCardinality column body: index type, additional keys, then indexes. */
  static Column readLowCardinality(
      WireReader in, LowCardinalityType type, int rows, DecodeContext context) {
    boolean nullable = type.inner() instanceof NullableType;
    ClickHouseType keyType =
        type.inner() instanceof NullableType nested ? nested.inner() : type.inner();
    if (rows == 0) {
      Column emptyDictionary = ColumnReader.readBody(in, keyType, 0, context, NONE);
      return new Columns.LowCardinalityColumn(type, emptyDictionary, new int[0], nullable);
    }
    long indexType = in.readInt64Le();
    long widthTag = indexType & ~LC_FLAG_BITS;
    if ((indexType & LC_NEED_GLOBAL_DICTIONARY_BIT) != 0) {
      throw new ChordProtocolException(
          "LowCardinality global dictionaries do not appear in native blocks; the stream is"
              + " corrupted or unsupported");
    }
    if ((indexType & LC_HAS_ADDITIONAL_KEYS_BIT) == 0) {
      throw new ChordProtocolException(
          "LowCardinality block carries no additional keys; the stream is corrupted");
    }
    int indexWidth =
        switch ((int) widthTag) {
          case 0 -> 1;
          case 1 -> 2;
          case 2 -> 4;
          case 3 -> 8;
          default ->
              throw new ChordProtocolException(
                  "Unknown LowCardinality index width tag " + widthTag);
        };
    long keyCount = in.readInt64Le();
    if (Long.compareUnsigned(keyCount, context.limits().maxRows()) > 0) {
      throw new ChordProtocolException(
          "LowCardinality dictionary declares "
              + Long.toUnsignedString(keyCount)
              + " keys, more than the row limit");
    }
    Column dictionary = ColumnReader.readBody(in, keyType, (int) keyCount, context, NONE);
    long rowCount = in.readInt64Le();
    if (rowCount != rows) {
      throw new ChordProtocolException(
          "LowCardinality indexes declare " + rowCount + " rows, block has " + rows);
    }
    int[] indexes = new int[rows];
    for (int i = 0; i < rows; i++) {
      long index =
          switch (indexWidth) {
            case 1 -> in.readUInt8();
            case 2 -> in.readUInt8() | ((long) in.readUInt8() << 8);
            case 4 -> in.readInt32Le() & 0xFFFFFFFFL;
            default -> in.readInt64Le();
          };
      if (Long.compareUnsigned(index, keyCount) >= 0) {
        throw new ChordProtocolException(
            "LowCardinality index " + Long.toUnsignedString(index) + " exceeds the dictionary");
      }
      indexes[i] = (int) index;
    }
    return new Columns.LowCardinalityColumn(type, dictionary, indexes, nullable);
  }

  /** Reads a Variant column body: discriminators, then each variant's values in global order. */
  static Columns.VariantColumn readVariant(
      WireReader in,
      ClickHouseType columnType,
      List<ClickHouseType> variantTypes,
      int rows,
      DecodeContext context,
      Prefix.Variant prefix) {
    byte[] discriminators = new byte[rows];
    if (rows > 0) {
      if (prefix.mode() == VARIANT_MODE_COMPACT) {
        readCompactDiscriminators(in, discriminators, rows);
      } else {
        in.readFully(discriminators);
      }
    }
    int[] counts = new int[variantTypes.size()];
    int[] positions = new int[rows];
    for (int i = 0; i < rows; i++) {
      int discriminator = discriminators[i] & 0xFF;
      if (discriminator == Columns.VariantColumn.NULL_DISCRIMINATOR) {
        continue;
      }
      if (discriminator >= variantTypes.size()) {
        throw new ChordProtocolException(
            "Variant discriminator "
                + discriminator
                + " exceeds the "
                + variantTypes.size()
                + " declared variants");
      }
      positions[i] = counts[discriminator]++;
    }
    List<Column> variants = new ArrayList<>(variantTypes.size());
    for (int v = 0; v < variantTypes.size(); v++) {
      variants.add(
          ColumnReader.readBody(
              in, variantTypes.get(v), counts[v], context, prefix.variantPrefixes().get(v)));
    }
    return new Columns.VariantColumn(columnType, discriminators, variants, positions);
  }

  /**
   * Reads one compact mode discriminators granule: varUInt row count, a granule format byte, then
   * either a single shared discriminator or one per row.
   */
  private static void readCompactDiscriminators(WireReader in, byte[] discriminators, int rows) {
    int filled = 0;
    while (filled < rows) {
      long granuleRows = in.readVarUInt();
      if (granuleRows == 0 || granuleRows > rows - filled) {
        throw new ChordProtocolException(
            "Variant compact granule declares "
                + Long.toUnsignedString(granuleRows)
                + " rows with "
                + (rows - filled)
                + " remaining");
      }
      int format = in.readUInt8();
      if (format == VARIANT_GRANULE_COMPACT) {
        byte discriminator = (byte) in.readUInt8();
        java.util.Arrays.fill(discriminators, filled, filled + (int) granuleRows, discriminator);
      } else if (format == VARIANT_GRANULE_PLAIN) {
        byte[] granule = new byte[(int) granuleRows];
        in.readFully(granule);
        System.arraycopy(granule, 0, discriminators, filled, granule.length);
      } else {
        throw new ChordProtocolException("Unknown Variant compact granule format " + format);
      }
      filled += (int) granuleRows;
    }
  }

  /** Reads a Dynamic column body: the inner variant with the prefix's synthesised order. */
  static Columns.DynamicColumn readDynamic(
      WireReader in, DynamicType type, int rows, DecodeContext context, Prefix.Dynamic prefix) {
    Columns.VariantColumn variant =
        readVariant(in, type, prefix.variantOrder(), rows, context, prefix.variantPrefix());
    return new Columns.DynamicColumn(
        type, variant, prefix.sharedVariantIndex(), prefix.dynamicTypes());
  }

  /** Reads a JSON column body: typed paths, dynamic paths, then the shared data map. */
  static Columns.JsonColumn readJson(
      WireReader in, JsonType type, int rows, DecodeContext context, Prefix.Json prefix) {
    Map<String, Column> typedPaths = new LinkedHashMap<>();
    for (int i = 0; i < prefix.sortedTypedPaths().size(); i++) {
      String path = prefix.sortedTypedPaths().get(i);
      typedPaths.put(
          path,
          ColumnReader.readBody(
              in,
              prefix.typedPathTypes().get(path),
              rows,
              context,
              prefix.typedPathPrefixes().get(i)));
    }
    Map<String, Columns.DynamicColumn> dynamicPaths = new LinkedHashMap<>();
    DynamicType pathType = new DynamicType(java.util.Optional.empty());
    for (int i = 0; i < prefix.dynamicPaths().size(); i++) {
      dynamicPaths.put(
          prefix.dynamicPaths().get(i),
          readDynamic(in, pathType, rows, context, prefix.dynamicPathPrefixes().get(i)));
    }
    MapType sharedDataType = new MapType(new StringType(), new StringType());
    Columns.MapColumn sharedData =
        (Columns.MapColumn) ColumnReader.readBody(in, sharedDataType, rows, context, NONE);
    return new Columns.JsonColumn(type, rows, typedPaths, dynamicPaths, sharedData);
  }

  /**
   * Reads a sparse column: varUInt encoded gaps locating the non default rows, then the non default
   * values through the nested serialisation, materialised into a full column.
   */
  static Column readSparse(WireReader in, ClickHouseType type, int rows, DecodeContext context) {
    Prefix prefix = readPrefix(in, type, context);
    long[] valueRows = new long[Math.min(rows, 16)];
    int valueCount = 0;
    long row = 0;
    while (true) {
      long group = in.readVarUInt();
      if ((group & SPARSE_END_OF_GRANULE_FLAG) != 0) {
        row += group & ~SPARSE_END_OF_GRANULE_FLAG;
        break;
      }
      row += group;
      if (row >= rows) {
        throw new ChordProtocolException(
            "Sparse offsets place a value at row " + row + " of a " + rows + " row block");
      }
      if (valueCount == valueRows.length) {
        valueRows = java.util.Arrays.copyOf(valueRows, Math.max(16, valueRows.length * 2));
      }
      valueRows[valueCount++] = row;
      row++;
    }
    if (row != rows) {
      throw new ChordProtocolException(
          "Sparse offsets cover " + row + " rows of a " + rows + " row block");
    }
    Column values = ColumnReader.readBody(in, type, valueCount, context, prefix);
    return materialiseSparse(type, values, valueRows, valueCount, rows);
  }

  /** Expands sparse values into a full column through the lossless block builder. */
  private static Column materialiseSparse(
      ClickHouseType type, Column values, long[] valueRows, int valueCount, int rows) {
    BlockBuilder builder = BlockBuilder.forColumnTypes(List.of(type));
    Object defaultValue = Defaults.of(type);
    int next = 0;
    for (long r = 0; r < rows; r++) {
      if (next < valueCount && valueRows[next] == r) {
        builder.addRow(values.objectAt(next));
        next++;
      } else {
        builder.addRow(defaultValue);
      }
    }
    return builder.build().column(0);
  }
}
