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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.orhaugh.chord.ChordProtocolException;
import io.github.orhaugh.chord.codec.UnsupportedClickHouseTypeException;
import io.github.orhaugh.chord.codec.block.BlockLimits;
import io.github.orhaugh.chord.codec.block.DecodeContext;
import io.github.orhaugh.chord.codec.type.TypeParser;
import io.github.orhaugh.chord.protocol.wire.WireLimits;
import io.github.orhaugh.chord.protocol.wire.WireReader;
import io.github.orhaugh.chord.protocol.wire.WireWriter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.ZoneId;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * The guard branches of the revision gated serialisations, driven with hand crafted hostile wire,
 * plus positive vectors for the layouts real servers send that the golden files cannot carry:
 * Variant compact discriminator granules and the V2 Dynamic and JSON structure prefixes.
 */
class SerializationGuardsTest {

  private static final long HAS_ADDITIONAL_KEYS = 1L << 9;
  private static final long NEED_GLOBAL_DICTIONARY = 1L << 8;
  private static final long NEED_UPDATE_DICTIONARY = 1L << 10;

  private static DecodeContext context() {
    return new DecodeContext(BlockLimits.DEFAULTS, 54488, ZoneId.of("UTC"));
  }

  private static byte[] bytes(Consumer<WireWriter> actions) {
    ByteArrayOutputStream sink = new ByteArrayOutputStream();
    WireWriter writer = new WireWriter(sink);
    actions.accept(writer);
    writer.flush();
    return sink.toByteArray();
  }

  private static Column decode(String typeName, int rows, byte[] wire) {
    return ColumnReader.read(
        new WireReader(new ByteArrayInputStream(wire), WireLimits.DEFAULTS),
        TypeParser.parse(typeName, 10_000, 32),
        rows,
        context());
  }

  private static Column decode(String typeName, int rows, byte[] wire, DecodeContext context) {
    return ColumnReader.read(
        new WireReader(new ByteArrayInputStream(wire), WireLimits.DEFAULTS),
        TypeParser.parse(typeName, 10_000, 32),
        rows,
        context);
  }

  // LowCardinality guards.

  @Test
  void lowCardinalityRejectsUnknownKeysVersions() {
    byte[] wire = bytes(w -> w.writeInt64Le(2));
    assertThatThrownBy(() -> decode("LowCardinality(String)", 3, wire))
        .isInstanceOf(ChordProtocolException.class)
        .hasMessageContaining("keys serialisation version");
  }

  @Test
  void lowCardinalityRejectsGlobalDictionaries() {
    byte[] wire =
        bytes(
            w -> {
              w.writeInt64Le(1); // keys version
              w.writeInt64Le(NEED_GLOBAL_DICTIONARY | HAS_ADDITIONAL_KEYS);
            });
    assertThatThrownBy(() -> decode("LowCardinality(String)", 3, wire))
        .isInstanceOf(ChordProtocolException.class)
        .hasMessageContaining("global dictionaries");
  }

  @Test
  void lowCardinalityRejectsMissingAdditionalKeys() {
    byte[] wire =
        bytes(
            w -> {
              w.writeInt64Le(1);
              w.writeInt64Le(0); // neither flag: no per block dictionary follows
            });
    assertThatThrownBy(() -> decode("LowCardinality(String)", 3, wire))
        .isInstanceOf(ChordProtocolException.class)
        .hasMessageContaining("no additional keys");
  }

  @Test
  void lowCardinalityRejectsUnknownIndexWidths() {
    byte[] wire =
        bytes(
            w -> {
              w.writeInt64Le(1);
              w.writeInt64Le(HAS_ADDITIONAL_KEYS | 5);
            });
    assertThatThrownBy(() -> decode("LowCardinality(String)", 3, wire))
        .isInstanceOf(ChordProtocolException.class)
        .hasMessageContaining("index width tag");
  }

  @Test
  void lowCardinalityBoundsTheDeclaredDictionarySize() {
    DecodeContext tight =
        new DecodeContext(
            new BlockLimits(16, 10, 100, 1024, 4096, 1024, 16), 54488, ZoneId.of("UTC"));
    byte[] wire =
        bytes(
            w -> {
              w.writeInt64Le(1);
              w.writeInt64Le(HAS_ADDITIONAL_KEYS | NEED_UPDATE_DICTIONARY);
              w.writeInt64Le(11); // more dictionary keys than the row limit permits
            });
    assertThatThrownBy(() -> decode("LowCardinality(String)", 3, wire, tight))
        .isInstanceOf(ChordProtocolException.class)
        .hasMessageContaining("keys");
  }

  @Test
  void lowCardinalityRejectsIndexesBeyondTheDictionary() {
    byte[] wire =
        bytes(
            w -> {
              w.writeInt64Le(1);
              w.writeInt64Le(HAS_ADDITIONAL_KEYS | NEED_UPDATE_DICTIONARY);
              w.writeInt64Le(2); // dictionary: default slot plus one value
              w.writeString("");
              w.writeString("a");
              w.writeInt64Le(3); // three rows of one byte indexes
              w.writeUInt8(0);
              w.writeUInt8(1);
              w.writeUInt8(2); // beyond the two entry dictionary
            });
    assertThatThrownBy(() -> decode("LowCardinality(String)", 3, wire))
        .isInstanceOf(ChordProtocolException.class)
        .hasMessageContaining("exceeds the dictionary");
  }

  // Variant guards and the compact discriminators mode.

  @Test
  void variantRejectsUnknownDiscriminatorModes() {
    byte[] wire = bytes(w -> w.writeInt64Le(2));
    assertThatThrownBy(() -> decode("Variant(Int64, String)", 3, wire))
        .isInstanceOf(ChordProtocolException.class)
        .hasMessageContaining("discriminators mode");
  }

  @Test
  void variantRejectsDiscriminatorsBeyondTheDeclaredVariants() {
    byte[] wire =
        bytes(
            w -> {
              w.writeInt64Le(0); // basic mode
              w.writeUInt8(0);
              w.writeUInt8(5); // only two variants exist
              w.writeUInt8(255);
            });
    assertThatThrownBy(() -> decode("Variant(Int64, String)", 3, wire))
        .isInstanceOf(ChordProtocolException.class)
        .hasMessageContaining("exceeds the");
  }

  @Test
  void variantRejectsUnknownCompactGranuleFormats() {
    byte[] wire =
        bytes(
            w -> {
              w.writeInt64Le(1); // compact mode
              w.writeVarUInt(3);
              w.writeUInt8(7); // neither PLAIN nor COMPACT
            });
    assertThatThrownBy(() -> decode("Variant(Int64, String)", 3, wire))
        .isInstanceOf(ChordProtocolException.class)
        .hasMessageContaining("granule format");
  }

  @Test
  void variantRejectsCompactGranulesLargerThanTheBlock() {
    byte[] wire =
        bytes(
            w -> {
              w.writeInt64Le(1);
              w.writeVarUInt(5); // five rows declared, block has three
              w.writeUInt8(1);
              w.writeUInt8(0);
            });
    assertThatThrownBy(() -> decode("Variant(Int64, String)", 3, wire))
        .isInstanceOf(ChordProtocolException.class)
        .hasMessageContaining("compact granule");
  }

  @Test
  void variantCompactDiscriminatorGranulesDecode() {
    // Two granules: a compact one where every row is the String variant, then a plain one
    // carrying a NULL. Variants are name sorted: Int64 = 0, String = 1.
    byte[] wire =
        bytes(
            w -> {
              w.writeInt64Le(1); // compact mode prefix
              w.writeVarUInt(2); // granule one: two rows
              w.writeUInt8(1); // COMPACT granule format
              w.writeUInt8(1); // all rows are variant 1 (String)
              w.writeVarUInt(1); // granule two: one row
              w.writeUInt8(0); // PLAIN granule format
              w.writeUInt8(255); // NULL discriminator
              // Variant bodies in global order: Int64 empty, then two strings.
              w.writeString("a");
              w.writeString("b");
            });
    Column column = decode("Variant(Int64, String)", 3, wire);
    assertThat(column.objectAt(0)).isEqualTo("a");
    assertThat(column.objectAt(1)).isEqualTo("b");
    assertThat(column.isNullAt(2)).isTrue();
  }

  // Dynamic guards and the V2 structure prefix.

  @Test
  void dynamicRejectsUnsupportedStructureVersions() {
    byte[] wire = bytes(w -> w.writeInt64Le(3)); // FLATTENED, a file only layout
    assertThatThrownBy(() -> decode("Dynamic", 2, wire))
        .isInstanceOf(UnsupportedClickHouseTypeException.class)
        .hasMessageContaining("not supported");
  }

  @Test
  void dynamicBoundsTheDeclaredTypeCount() {
    byte[] wire =
        bytes(
            w -> {
              w.writeInt64Le(2);
              w.writeVarUInt(2000); // beyond the 1000 cap
            });
    assertThatThrownBy(() -> decode("Dynamic", 2, wire))
        .isInstanceOf(ChordProtocolException.class)
        .hasMessageContaining("types");
  }

  @Test
  void dynamicV2PrefixesDecodeAsTheTcpPathSendsThem() {
    // V2: no legacy max_dynamic_types varint, unlike the V1 golden files. Variant order is
    // the name sorted union of the declared types and SharedVariant:
    // Int64 = 0, SharedVariant = 1, String = 2.
    byte[] wire =
        bytes(
            w -> {
              w.writeInt64Le(2); // structure version V2
              w.writeVarUInt(2); // two dynamic types
              w.writeString("Int64");
              w.writeString("String");
              w.writeInt64Le(0); // inner variant prefix: basic discriminators
              w.writeUInt8(0); // row 0: Int64
              w.writeUInt8(2); // row 1: String
              w.writeUInt8(255); // row 2: NULL
              w.writeInt64Le(42); // Int64 variant body
              // SharedVariant body: empty
              w.writeString("hi"); // String variant body
            });
    Columns.DynamicColumn column = (Columns.DynamicColumn) decode("Dynamic", 3, wire);
    assertThat(column.objectAt(0)).isEqualTo(42L);
    assertThat(column.objectAt(1)).isEqualTo("hi");
    assertThat(column.isNullAt(2)).isTrue();
    assertThat(column.typeNameAt(0)).contains("Int64");
    assertThat(column.typeNameAt(1)).contains("String");
  }

  // JSON guards and the V2 object prefix.

  @Test
  void jsonRejectsUnsupportedObjectVersions() {
    byte[] wire = bytes(w -> w.writeInt64Le(4)); // V3, a file only layout
    assertThatThrownBy(() -> decode("JSON", 2, wire))
        .isInstanceOf(UnsupportedClickHouseTypeException.class)
        .hasMessageContaining("not supported");
  }

  @Test
  void jsonBoundsTheDeclaredDynamicPathCount() {
    byte[] wire =
        bytes(
            w -> {
              w.writeInt64Le(2);
              w.writeVarUInt(200_000); // beyond the 100000 cap
            });
    assertThatThrownBy(() -> decode("JSON", 2, wire))
        .isInstanceOf(ChordProtocolException.class)
        .hasMessageContaining("paths");
  }

  @Test
  void jsonV2PrefixesDecodeAsTheTcpPathSendsThem() {
    byte[] wire =
        bytes(
            w -> {
              w.writeInt64Le(2); // object version V2
              w.writeVarUInt(1); // one dynamic path
              w.writeString("a");
              // The path's Dynamic structure, itself V2.
              w.writeInt64Le(2);
              w.writeVarUInt(1);
              w.writeString("Int64");
              w.writeInt64Le(0); // the path's variant prefix: basic mode
              // Bodies: the dynamic path first (Int64 = 0, SharedVariant = 1).
              w.writeUInt8(0); // row 0 carries an Int64
              w.writeUInt8(255); // row 1 is null for this path
              w.writeInt64Le(7);
              // Shared data as Map(String, String): row 0 has one entry, row 1 none.
              w.writeInt64Le(1);
              w.writeInt64Le(1);
              w.writeString("extra");
              w.writeString("raw-bytes");
            });
    Columns.JsonColumn column = (Columns.JsonColumn) decode("JSON", 2, wire);
    @SuppressWarnings("unchecked")
    java.util.Map<String, Object> first = (java.util.Map<String, Object>) column.objectAt(0);
    assertThat(first).containsEntry("a", 7L).containsKey("extra");
    assertThat(((Columns.RawJsonValue) first.get("extra")).bytes())
        .isEqualTo("raw-bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    @SuppressWarnings("unchecked")
    java.util.Map<String, Object> second = (java.util.Map<String, Object>) column.objectAt(1);
    assertThat(second).isEmpty();
  }
}
