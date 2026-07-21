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
import io.github.orhaugh.chord.protocol.ProtocolFeature;
import io.github.orhaugh.chord.protocol.wire.WireReader;
import io.github.orhaugh.chord.protocol.wire.WireWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * The per block metadata preceding column data, framed as (field number, value) pairs terminated by
 * field number zero, mirroring {@code BlockInfo} in {@code src/Core/BlockInfo.h}: field 1 is the
 * overflow flag, field 2 the two level aggregation bucket, and from revision 54480 field 3 carries
 * out of order bucket numbers. Unknown field numbers poison the connection because their size is
 * unknowable.
 *
 * @param isOverflows whether the block carries overflow rows after GROUP BY with totals
 * @param bucketNum two level aggregation bucket number, -1 when not applicable
 * @param outOfOrderBuckets out of order bucket numbers, empty when absent
 */
public record BlockInfo(boolean isOverflows, int bucketNum, List<Integer> outOfOrderBuckets) {

  /** The default info carried by ordinary data blocks. */
  public static final BlockInfo DEFAULT = new BlockInfo(false, -1, List.of());

  /** Validates components and copies the bucket list. */
  public BlockInfo {
    outOfOrderBuckets = List.copyOf(outOfOrderBuckets);
  }

  /**
   * Reads block info.
   *
   * @param in reader positioned at the info
   * @param negotiatedRevision the negotiated protocol revision
   * @param maxOutOfOrderBuckets bound for the field 3 vector
   * @return the decoded info
   */
  public static BlockInfo read(WireReader in, long negotiatedRevision, int maxOutOfOrderBuckets) {
    boolean isOverflows = false;
    int bucketNum = -1;
    List<Integer> outOfOrder = List.of();
    while (true) {
      long field = in.readVarUInt();
      if (field == 0) {
        return new BlockInfo(isOverflows, bucketNum, outOfOrder);
      }
      if (field == 1) {
        isOverflows = in.readBool();
      } else if (field == 2) {
        bucketNum = in.readInt32Le();
      } else if (field == 3
          && ProtocolFeature.OUT_OF_ORDER_BUCKETS_IN_AGGREGATION.enabledFor(negotiatedRevision)) {
        long count = in.readVarUInt();
        if (Long.compareUnsigned(count, maxOutOfOrderBuckets) > 0) {
          throw new ChordProtocolException(
              "BlockInfo declares "
                  + Long.toUnsignedString(count)
                  + " out of order buckets, maximum allowed is "
                  + maxOutOfOrderBuckets);
        }
        List<Integer> buckets = new ArrayList<>((int) count);
        for (int i = 0; i < count; i++) {
          buckets.add(in.readInt32Le());
        }
        outOfOrder = buckets;
      } else {
        throw new ChordProtocolException(
            "Unknown BlockInfo field number "
                + Long.toUnsignedString(field)
                + "; the field size is unknowable, so the connection is desynchronised");
      }
    }
  }

  /**
   * Writes block info with the fields the negotiated revision carries.
   *
   * @param out writer to encode into
   * @param negotiatedRevision the negotiated protocol revision
   */
  public void write(WireWriter out, long negotiatedRevision) {
    out.writeVarUInt(1);
    out.writeBool(isOverflows);
    out.writeVarUInt(2);
    out.writeInt32Le(bucketNum);
    if (ProtocolFeature.OUT_OF_ORDER_BUCKETS_IN_AGGREGATION.enabledFor(negotiatedRevision)) {
      out.writeVarUInt(3);
      out.writeVarUInt(outOfOrderBuckets.size());
      for (int bucket : outOfOrderBuckets) {
        out.writeInt32Le(bucket);
      }
    }
    out.writeVarUInt(0);
  }
}
