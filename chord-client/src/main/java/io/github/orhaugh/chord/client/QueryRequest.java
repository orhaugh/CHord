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

  private QueryRequest(Builder builder) {
    this.query = builder.query;
    this.queryId = builder.queryId != null ? builder.queryId : UUID.randomUUID().toString();
    this.settings = Map.copyOf(builder.settings);
    this.parameters = Map.copyOf(builder.parameters);
    this.compression = builder.compression;
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
     * Builds the immutable request.
     *
     * @return the request
     */
    public QueryRequest build() {
      return new QueryRequest(this);
    }
  }
}
