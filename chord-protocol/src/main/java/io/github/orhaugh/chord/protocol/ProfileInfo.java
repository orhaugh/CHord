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
package io.github.orhaugh.chord.protocol;

import io.github.orhaugh.chord.protocol.wire.WireReader;

/**
 * The decoded body of a ProfileInfo packet, mirroring {@code ProfileInfo::read} in {@code
 * src/QueryPipeline/ProfileInfo.cpp}: VarUInt rows, blocks and bytes, the applied limit flag, rows
 * before limit, one obsolete flag, and from revision 54469 the applied aggregation flag and rows
 * before aggregation.
 *
 * @param rows rows the query pipeline produced
 * @param blocks blocks the query pipeline produced
 * @param bytes bytes the query pipeline produced
 * @param appliedLimit whether a LIMIT was applied
 * @param rowsBeforeLimit row count before the LIMIT
 * @param appliedAggregation whether aggregation was applied, zero revision gated
 * @param rowsBeforeAggregation row count before aggregation, zero when not carried
 */
public record ProfileInfo(
    long rows,
    long blocks,
    long bytes,
    boolean appliedLimit,
    long rowsBeforeLimit,
    boolean appliedAggregation,
    long rowsBeforeAggregation) {

  /**
   * Reads a ProfileInfo packet body. The leading packet type identifier must already have been
   * consumed.
   *
   * @param in reader positioned at the body
   * @param negotiatedRevision the negotiated protocol revision of the connection
   * @return the decoded profile information
   */
  public static ProfileInfo read(WireReader in, long negotiatedRevision) {
    long rows = in.readVarUInt();
    long blocks = in.readVarUInt();
    long bytes = in.readVarUInt();
    boolean appliedLimit = in.readBool();
    long rowsBeforeLimit = in.readVarUInt();
    in.readBool(); // obsolete calculated_rows_before_limit flag
    boolean appliedAggregation = false;
    long rowsBeforeAggregation = 0;
    if (ProtocolFeature.ROWS_BEFORE_AGGREGATION.enabledFor(negotiatedRevision)) {
      appliedAggregation = in.readBool();
      rowsBeforeAggregation = in.readVarUInt();
    }
    return new ProfileInfo(
        rows,
        blocks,
        bytes,
        appliedLimit,
        rowsBeforeLimit,
        appliedAggregation,
        rowsBeforeAggregation);
  }
}
