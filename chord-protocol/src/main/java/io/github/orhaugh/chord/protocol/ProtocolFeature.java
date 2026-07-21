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

/**
 * Central registry of revision gated protocol features. Every conditionally present field on the
 * wire is guarded through {@link #enabledFor(long)} against the negotiated revision of the
 * connection, never through scattered integer comparisons.
 *
 * <p>Constant names mirror the {@code DBMS_MIN_...} definitions in {@code
 * src/Core/ProtocolDefines.h} with the prefix removed; values were verified against the ClickHouse
 * master branch on 2026-07-21. A feature appearing here means CHord knows the gate exists, not that
 * CHord implements the feature; implementation status lives in {@code
 * docs/protocol-compatibility.md}.
 */
public enum ProtocolFeature {

  /** ClientInfo block in the Query packet. */
  CLIENT_INFO(54032),
  /** Server timezone string in ServerHello. */
  SERVER_TIMEZONE(54058),
  /** Quota key inside ClientInfo. */
  QUOTA_KEY_IN_CLIENT_INFO(54060),
  /** TablesStatus request and response packets. */
  TABLES_STATUS(54226),
  /** Timezone parameter in the DateTime data type name. */
  TIME_ZONE_PARAMETER_IN_DATETIME_DATA_TYPE(54337),
  /** Server display name string in ServerHello. */
  SERVER_DISPLAY_NAME(54372),
  /** Server patch version in ServerHello. */
  VERSION_PATCH(54401),
  /** LowCardinality type over the native protocol. */
  LOW_CARDINALITY_TYPE(54405),
  /** Server Log packets. */
  SERVER_LOGS(54406),
  /** Column defaults metadata in TableColumns. */
  COLUMN_DEFAULTS_METADATA(54410),
  /** Written rows and bytes in Progress packets. */
  CLIENT_WRITE_INFO(54420),
  /** Query settings serialised as strings rather than typed binary. */
  SETTINGS_SERIALIZED_AS_STRINGS(54429),
  /** Scalar Data packets for scalar subquery results. */
  SCALARS(54429),
  /** Interserver secret authentication. Interserver connections only. */
  INTERSERVER_SECRET(54441),
  /** OpenTelemetry trace context in the Query packet. */
  OPENTELEMETRY(54442),
  /** X-Forwarded-For field inside ClientInfo. */
  X_FORWARDED_FOR_IN_CLIENT_INFO(54443),
  /** Referer field inside ClientInfo. */
  REFERER_IN_CLIENT_INFO(54447),
  /** Distributed depth field inside ClientInfo. */
  DISTRIBUTED_DEPTH(54448),
  /** Initial query start time inside ClientInfo. */
  INITIAL_QUERY_START_TIME(54449),
  /** Incremental ProfileEvents packets. */
  INCREMENTAL_PROFILE_EVENTS(54451),
  /** Versioned aggregate function state serialisation. */
  AGGREGATE_FUNCTIONS_VERSIONING(54452),
  /** Parallel replicas packets. Not implemented by CHord; receiving one is an error. */
  PARALLEL_REPLICAS(54453),
  /** Custom column serialisation metadata in native blocks. */
  CUSTOM_SERIALIZATION(54454),
  /** ProfileEvents packets during INSERT. */
  PROFILE_EVENTS_IN_INSERT(54456),
  /** The view-if-permitted form of the EXISTS query. */
  VIEW_IF_PERMITTED(54457),
  /** Client addendum after ServerHello. Also the CHord minimum supported server revision. */
  ADDENDUM(54458),
  /** Quota key string in the client addendum. */
  QUOTA_KEY(54458),
  /** Server side query parameters in the Query packet. */
  PARAMETERS(54459),
  /** Query elapsed time in Progress packets. */
  SERVER_QUERY_TIME_IN_PROGRESS(54460),
  /** Password complexity rules in ServerHello. */
  PASSWORD_COMPLEXITY_RULES(54461),
  /** Interserver secret v2 nonce in ServerHello. The nonce is read by every client. */
  INTERSERVER_SECRET_V2(54462),
  /** Total bytes to read in Progress packets. */
  TOTAL_BYTES_IN_PROGRESS(54463),
  /** TimezoneUpdate packets on session timezone changes. */
  TIMEZONE_UPDATES(54464),
  /** Sparse column serialisation in native blocks. */
  SPARSE_SERIALIZATION(54465),
  /** SSH key authentication packets. */
  SSH_AUTHENTICATION(54466),
  /** Read-only flag for replicated tables in TablesStatusResponse. */
  TABLE_READ_ONLY_CHECK(54467),
  /** system.keywords table support flag. */
  SYSTEM_KEYWORDS_TABLE(54468),
  /** Rows-before-aggregation counter in ProfileInfo. */
  ROWS_BEFORE_AGGREGATION(54469),
  /** Chunked packet framing, negotiated per direction during the handshake. */
  CHUNKED_PACKETS(54470),
  /** Parallel replicas protocol version fields in ServerHello and the client addendum. */
  VERSIONED_PARALLEL_REPLICAS_PROTOCOL(54471),
  /** Externally granted roles. Interserver connections only. */
  INTERSERVER_EXTERNALLY_GRANTED_ROLES(54472),
  /** Version 2 serialisation for Dynamic and JSON types. */
  V2_DYNAMIC_AND_JSON_SERIALIZATION(54473),
  /** Server settings list in ServerHello. */
  SERVER_SETTINGS_IN_HELLO(54474),
  /** Query text carries script line numbers in ClientInfo. */
  QUERY_AND_LINE_NUMBERS(54475),
  /** JWT in interserver connections. Interserver connections only. */
  JWT_IN_INTERSERVER(54476),
  /** Query plan serialisation version in ServerHello and QueryPlan packets. */
  QUERY_PLAN_SERIALIZATION(54477),
  /** Parallel block marshalling in native blocks. */
  PARALLEL_BLOCK_MARSHALLING(54478),
  /** Cluster function protocol version in ServerHello. */
  VERSIONED_CLUSTER_FUNCTION_PROTOCOL(54479),
  /** Out of order buckets in two level aggregation. */
  OUT_OF_ORDER_BUCKETS_IN_AGGREGATION(54480),
  /** Compressed Log and ProfileEvents packet columns. */
  COMPRESSED_LOGS_PROFILE_EVENTS_COLUMNS(54481),
  /** Replicated column serialisation in native blocks. */
  REPLICATED_SERIALIZATION(54482),
  /** Sparse serialisation nested inside Nullable columns. */
  NULLABLE_SPARSE_SERIALIZATION(54483),
  /** Progress packets during asynchronous INSERT. */
  PROGRESS_IN_ASYNC_INSERT(54484),
  /** Client agent string inside ClientInfo. */
  CLIENT_AGENT_IN_CLIENT_INFO(54485),
  /** Internal query flag inside ClientInfo. */
  INTERNAL_QUERY_FLAG(54486),
  /** Cluster secret hash on TablesStatusRequest. Interserver connections only. */
  INTERSERVER_SECRET_TABLES_STATUS(54487),
  /** Initiator current roles propagation. Interserver connections only. */
  INTERSERVER_CURRENT_ROLES(54488);

  private final long minRevision;

  ProtocolFeature(long minRevision) {
    this.minRevision = minRevision;
  }

  /**
   * Returns the lowest protocol revision at which this feature is present on the wire.
   *
   * @return the gating revision
   */
  public long minRevision() {
    return minRevision;
  }

  /**
   * Reports whether this feature is present on the wire at the given negotiated revision.
   *
   * @param negotiatedRevision the revision computed by {@link ProtocolRevisions#negotiate}
   * @return {@code true} when fields gated by this feature are present
   */
  public boolean enabledFor(long negotiatedRevision) {
    return negotiatedRevision >= minRevision;
  }
}
