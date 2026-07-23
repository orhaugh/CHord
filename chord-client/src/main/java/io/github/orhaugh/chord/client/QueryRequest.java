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
package io.github.orhaugh.chord.client;

import io.github.orhaugh.chord.ChordConfigurationException;
import io.github.orhaugh.chord.annotations.Experimental;
import io.github.orhaugh.chord.codec.compress.Compression;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * An immutable query to execute.
 *
 * <p>Settings are sent to the server as name and string value pairs in the modern STRINGS format,
 * so any server setting can be passed without CHord needing to know it. Parameters are server side
 * substitutions for {@code {name:Type}} placeholders in the query text; values are rendered as
 * strings the way the official client sends them.
 */
@Experimental
public final class QueryRequest {

  private final String query;
  private final String queryId;
  private final Map<String, String> settings;
  private final Map<String, String> parameters;
  private final Compression compression;
  private final java.time.Duration timeout;
  private final java.util.function.Consumer<io.github.orhaugh.chord.protocol.Progress>
      progressListener;
  private final java.util.function.Consumer<ServerLogEntry> logListener;
  private final TraceContext traceContext;

  private QueryRequest(Builder builder) {
    this.query = builder.query;
    this.queryId = builder.queryId != null ? builder.queryId : UUID.randomUUID().toString();
    this.settings = Map.copyOf(builder.settings);
    this.parameters = Map.copyOf(builder.parameters);
    this.compression = builder.compression;
    this.timeout = builder.timeout;
    this.progressListener = builder.progressListener;
    this.logListener = builder.logListener;
    this.traceContext = builder.traceContext;
  }

  /**
   * Creates a builder for a query.
   *
   * @param query the SQL text
   * @return a new builder
   */
  public static Builder builder(String query) {
    return new Builder(query);
  }

  /**
   * Creates a request with just the SQL text and defaults for everything else.
   *
   * @param query the SQL text
   * @return the request
   */
  public static QueryRequest of(String query) {
    return builder(query).build();
  }

  /**
   * Returns the SQL text.
   *
   * @return the query
   */
  public String query() {
    return query;
  }

  /**
   * Returns the query id sent to the server, generated when not set explicitly.
   *
   * @return the query id
   */
  public String queryId() {
    return queryId;
  }

  /**
   * Returns the per query settings.
   *
   * @return setting names to string values
   */
  public Map<String, String> settings() {
    return settings;
  }

  /**
   * Returns the named query parameters.
   *
   * @return parameter names to rendered values
   */
  public Map<String, String> parameters() {
    return parameters;
  }

  /**
   * Returns the per query compression override, empty to use the connection's setting.
   *
   * @return the compression method
   */
  public java.util.Optional<Compression> compression() {
    return java.util.Optional.ofNullable(compression);
  }

  /**
   * Returns the client side execution timeout for the whole result stream, empty for none.
   *
   * @return the timeout
   */
  public java.util.Optional<java.time.Duration> timeout() {
    return java.util.Optional.ofNullable(timeout);
  }

  /**
   * Returns the progress listener, empty for none.
   *
   * @return the progress listener
   */
  public java.util.Optional<java.util.function.Consumer<io.github.orhaugh.chord.protocol.Progress>>
      progressListener() {
    return java.util.Optional.ofNullable(progressListener);
  }

  /**
   * Returns the server log listener, empty for none.
   *
   * @return the log listener
   */
  /**
   * Returns the OpenTelemetry trace context to propagate with this query, if any.
   *
   * @return the trace context
   */
  public java.util.Optional<TraceContext> traceContext() {
    return java.util.Optional.ofNullable(traceContext);
  }

  /**
   * An OpenTelemetry trace context parsed from the W3C {@code traceparent} header form, propagated
   * inside the Query packet so server side spans join the caller's trace.
   *
   * @param traceIdHigh the high 64 bits of the trace id
   * @param traceIdLow the low 64 bits of the trace id
   * @param spanId the parent span id
   * @param traceState the {@code tracestate} header value, empty for none
   * @param traceFlags the trace flags byte; bit 0 is sampled
   */
  public record TraceContext(
      long traceIdHigh, long traceIdLow, long spanId, String traceState, byte traceFlags) {

    private static final java.util.regex.Pattern TRACEPARENT =
        java.util.regex.Pattern.compile("00-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})");

    /**
     * Parses a W3C traceparent value, for example {@code
     * 00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01}.
     *
     * @param traceParent the header value
     * @param traceState the tracestate header value, empty for none
     * @return the parsed context
     */
    public static TraceContext parse(String traceParent, String traceState) {
      java.util.regex.Matcher matcher =
          TRACEPARENT.matcher(java.util.Objects.requireNonNull(traceParent, "traceParent"));
      if (!matcher.matches()) {
        throw new io.github.orhaugh.chord.ChordConfigurationException(
            "Malformed traceparent; expected 00-<32 hex>-<16 hex>-<2 hex>: " + traceParent);
      }
      String traceId = matcher.group(1);
      return new TraceContext(
          Long.parseUnsignedLong(traceId.substring(0, 16), 16),
          Long.parseUnsignedLong(traceId.substring(16), 16),
          Long.parseUnsignedLong(matcher.group(2), 16),
          java.util.Objects.requireNonNull(traceState, "traceState"),
          (byte) Integer.parseInt(matcher.group(3), 16));
    }
  }

  /**
   * Returns the server log listener, if any.
   *
   * @return the listener receiving server log entries
   */
  public java.util.Optional<java.util.function.Consumer<ServerLogEntry>> logListener() {
    return java.util.Optional.ofNullable(logListener);
  }

  @Override
  public String toString() {
    // Query text can carry sensitive literals; never include it here.
    return "QueryRequest{queryId=" + queryId + ", settings=" + settings.keySet() + "}";
  }

  /** Builder for {@link QueryRequest}. */
  public static final class Builder {

    private final String query;
    private String queryId;
    private final Map<String, String> settings = new LinkedHashMap<>();
    private final Map<String, String> parameters = new LinkedHashMap<>();
    private Compression compression;
    private java.time.Duration timeout;
    private java.util.function.Consumer<io.github.orhaugh.chord.protocol.Progress> progressListener;
    private java.util.function.Consumer<ServerLogEntry> logListener;
    private TraceContext traceContext;

    private Builder(String query) {
      this.query = Objects.requireNonNull(query, "query");
      if (query.isBlank()) {
        throw new ChordConfigurationException("query must not be blank");
      }
    }

    /**
     * Sets an explicit query id, visible in {@code system.query_log} and usable for cancellation.
     * Defaults to a random UUID.
     *
     * @param queryId the query id
     * @return this builder
     */
    public Builder queryId(String queryId) {
      this.queryId = Objects.requireNonNull(queryId, "queryId");
      return this;
    }

    /**
     * Adds a per query setting, sent as a string exactly as the modern protocol expects.
     *
     * @param name the setting name
     * @param value the setting value
     * @return this builder
     */
    public Builder setting(String name, Object value) {
      settings.put(
          Objects.requireNonNull(name, "name"), Objects.requireNonNull(value, "value").toString());
      return this;
    }

    /**
     * Adds a named parameter for a {@code {name:Type}} placeholder.
     *
     * @param name the parameter name without braces
     * @param value the value, rendered with {@code toString()}; format temporal values the way the
     *     declared placeholder type expects
     * @return this builder
     */
    public Builder parameter(String name, Object value) {
      parameters.put(
          Objects.requireNonNull(name, "name"), Objects.requireNonNull(value, "value").toString());
      return this;
    }

    /**
     * Overrides the connection's compression for this request, at the method's default level.
     *
     * @param compression the method
     * @return this builder
     */
    public Builder compression(Compression compression) {
      this.compression = Objects.requireNonNull(compression, "compression");
      return this;
    }

    /**
     * Sets a client side execution timeout covering the whole result stream. When it expires at a
     * packet boundary, CHord sends the Cancel packet and drains the concluding response within the
     * connection's cancel grace period, leaving the connection reusable; if the server does not
     * conclude in time, or the expiry falls in the middle of a packet, the connection is closed as
     * broken. Either way the read fails with {@link io.github.orhaugh.chord.ChordTimeoutException}.
     *
     * <p>This is a wall clock bound on the client side; pair it with the server's {@code
     * max_execution_time} setting to also bound work the server performs.
     *
     * @param timeout the timeout, positive
     * @return this builder
     */
    public Builder timeout(java.time.Duration timeout) {
      Objects.requireNonNull(timeout, "timeout");
      if (timeout.isZero() || timeout.isNegative()) {
        throw new ChordConfigurationException("timeout must be positive");
      }
      this.timeout = timeout;
      return this;
    }

    /**
     * Registers a listener invoked for every Progress packet of this request, on the thread
     * consuming the stream, with the delta the packet carried. Accumulated totals stay available
     * through {@link QueryResult#totalProgress()}. Listener exceptions are logged and swallowed;
     * they never affect the stream.
     *
     * @param listener the progress consumer
     * @return this builder
     */
    public Builder onProgress(
        java.util.function.Consumer<io.github.orhaugh.chord.protocol.Progress> listener) {
      this.progressListener = Objects.requireNonNull(listener, "listener");
      return this;
    }

    /**
     * Registers a listener invoked for every server log line of this request, on the thread
     * consuming the stream. Log packets only arrive when the {@code send_logs_level} setting asks
     * for them. Listener exceptions are logged and swallowed; they never affect the stream.
     *
     * @param listener the log entry consumer
     * @return this builder
     */
    public Builder onLog(java.util.function.Consumer<ServerLogEntry> listener) {
      this.logListener = Objects.requireNonNull(listener, "listener");
      return this;
    }

    /**
     * Propagates an OpenTelemetry trace context with this query, from the W3C header values, so
     * server side spans (visible in {@code system.opentelemetry_span_log}) join the caller's trace.
     * No OpenTelemetry dependency is required: pass the header strings your propagator produces.
     *
     * @param traceParent the {@code traceparent} value, {@code 00-&lt;trace id&gt;-&lt;span
     *     id&gt;-&lt;flags&gt;}
     * @param traceState the {@code tracestate} value, empty for none
     * @return this builder
     */
    public Builder traceContext(String traceParent, String traceState) {
      this.traceContext = TraceContext.parse(traceParent, traceState);
      return this;
    }

    /**
     * Sets the {@code insert_deduplication_token} setting: replicated and shared tables drop an
     * INSERT carrying a token they have already applied, making a retried INSERT safe. Retrying
     * remains the caller's decision; see {@link io.github.orhaugh.chord.RetryClass}.
     *
     * @param token the idempotency token, non blank
     * @return this builder
     */
    public Builder insertDeduplicationToken(String token) {
      Objects.requireNonNull(token, "token");
      if (token.isBlank()) {
        throw new ChordConfigurationException("insertDeduplicationToken must not be blank");
      }
      return setting("insert_deduplication_token", token);
    }

    /**
     * Builds the immutable request.
     *
     * @return the request
     */
    public QueryRequest build() {
      return new QueryRequest(this);
    }
  }
}
