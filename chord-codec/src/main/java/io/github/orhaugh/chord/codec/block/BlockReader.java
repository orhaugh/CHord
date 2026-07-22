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

import io.github.orhaugh.chord.ChordProtocolException;
import io.github.orhaugh.chord.ChordTypeException;
import io.github.orhaugh.chord.codec.UnsupportedClickHouseTypeException;
import io.github.orhaugh.chord.codec.column.Column;
import io.github.orhaugh.chord.codec.column.ColumnReader;
import io.github.orhaugh.chord.codec.type.ClickHouseType;
import io.github.orhaugh.chord.codec.type.TypeParser;
import io.github.orhaugh.chord.protocol.ProtocolFeature;
import io.github.orhaugh.chord.protocol.wire.WireReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Decodes native blocks, mirroring {@code NativeReader} and {@code NativeWriter} in the ClickHouse
 * sources: block info, column and row counts, then per column its name, type name, the custom
 * serialisation flag (from revision 54454) and the column data.
 *
 * <p>Custom serialisation (sparse and related forms) is recognised and rejected explicitly until
 * Phase 6; the failure happens before any value bytes are consumed, so the error is precise.
 */
public final class BlockReader {

  private static final int MAX_OUT_OF_ORDER_BUCKETS = 1_000_000;

  private BlockReader() {}

  /**
   * Reads one uncompressed native block.
   *
   * @param in reader positioned at the block info
   * @param context decode limits and session information
   * @return the decoded block
   */
  public static Block read(WireReader in, DecodeContext context) {
    BlockInfo info = BlockInfo.read(in, context.negotiatedRevision(), MAX_OUT_OF_ORDER_BUCKETS);

    long columnCount = in.readVarUInt();
    if (Long.compareUnsigned(columnCount, context.limits().maxColumns()) > 0) {
      throw new ChordProtocolException(
          "Block declares "
              + Long.toUnsignedString(columnCount)
              + " columns, maximum allowed is "
              + context.limits().maxColumns());
    }
    long rowCount = in.readVarUInt();
    if (Long.compareUnsigned(rowCount, context.limits().maxRows()) > 0) {
      throw new ChordProtocolException(
          "Block declares "
              + Long.toUnsignedString(rowCount)
              + " rows, maximum allowed is "
              + context.limits().maxRows());
    }
    int columns = (int) columnCount;
    int rows = (int) rowCount;

    List<Block.NamedColumn> decoded = new ArrayList<>(columns);
    for (int i = 0; i < columns; i++) {
      String name = in.readString();
      String typeName = in.readString(context.limits().maxTypeNameLength());
      boolean sparse = false;
      if (ProtocolFeature.CUSTOM_SERIALIZATION.enabledFor(context.negotiatedRevision())) {
        int hasCustom = in.readUInt8();
        if (hasCustom != 0) {
          sparse = readSparseKind(in, name);
        }
      }
      ClickHouseType type;
      try {
        type =
            TypeParser.parse(
                typeName, context.limits().maxTypeNameLength(), context.limits().maxTypeDepth());
      } catch (ChordTypeException e) {
        throw new ChordTypeException("Column \"" + name + "\": " + e.getMessage());
      }
      Column column =
          sparse && rows > 0
              ? ColumnReader.readSparse(in, type, rows, context)
              : ColumnReader.read(in, type, rows, context);
      decoded.add(new Block.NamedColumn(name, column));
    }
    return new Block(info, rows, decoded);
  }

  /**
   * Reads the serialisation kind byte of a column with custom serialisation, mirroring {@code
   * SerializationInfo::deserializeFromKindsBinary}: 0 default, 1 sparse; the detached, replicated
   * and combination stacks are inter server forms that fail explicitly.
   */
  private static boolean readSparseKind(WireReader in, String name) {
    int kind = in.readUInt8();
    return switch (kind) {
      case 0 -> false;
      case 1 -> true;
      case 2, 3, 4, 5 ->
          throw new UnsupportedClickHouseTypeException(
              "Column \""
                  + name
                  + "\" uses serialisation kind "
                  + kind
                  + " (detached, replicated or a combination), which servers use between"
                  + " themselves and CHord does not decode. Reading cannot continue safely.");
      default ->
          throw new UnsupportedClickHouseTypeException(
              "Column \"" + name + "\" declares unknown serialisation kind " + kind);
    };
  }
}
