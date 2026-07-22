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

import io.github.orhaugh.chord.codec.column.ColumnWriter;
import io.github.orhaugh.chord.protocol.ProtocolFeature;
import io.github.orhaugh.chord.protocol.wire.WireWriter;

/**
 * Encodes native blocks, mirroring {@code NativeWriter::write} in the ClickHouse sources: block
 * info, column and row counts, then per column its name, type name, the custom serialisation flag
 * (always plain, from revision 54454) and the column data.
 */
public final class BlockWriter {

  private BlockWriter() {}

  /**
   * Writes an empty block: default info, zero columns, zero rows.
   *
   * @param out writer to encode into
   * @param negotiatedRevision the negotiated protocol revision, which gates info fields
   */
  public static void writeEmpty(WireWriter out, long negotiatedRevision) {
    BlockInfo.DEFAULT.write(out, negotiatedRevision);
    out.writeVarUInt(0);
    out.writeVarUInt(0);
  }

  /**
   * Writes a block.
   *
   * @param out writer to encode into
   * @param block the block to encode
   * @param negotiatedRevision the negotiated protocol revision, which gates info fields and the
   *     custom serialisation flag
   */
  public static void write(WireWriter out, Block block, long negotiatedRevision) {
    block.info().write(out, negotiatedRevision);
    out.writeVarUInt(block.columnCount());
    out.writeVarUInt(block.rows());
    for (Block.NamedColumn column : block.columns()) {
      out.writeString(column.name());
      out.writeString(column.column().type().name());
      if (ProtocolFeature.CUSTOM_SERIALIZATION.enabledFor(negotiatedRevision)) {
        out.writeUInt8(0); // plain serialisation, never sparse
      }
      if (block.rows() > 0) {
        // Empty blocks carry no column data and no prefix, mirroring NativeWriter.
        ColumnWriter.writePrefix(out, column.column());
        ColumnWriter.write(out, column.column());
      }
    }
  }
}
