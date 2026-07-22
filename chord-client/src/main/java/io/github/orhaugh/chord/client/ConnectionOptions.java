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
import io.github.orhaugh.chord.codec.block.BlockLimits;
import io.github.orhaugh.chord.codec.compress.Compression;
import io.github.orhaugh.chord.codec.compress.CompressionLimits;
import io.github.orhaugh.chord.protocol.ProtocolRevisions;
import io.github.orhaugh.chord.protocol.wire.WireLimits;
import io.github.orhaugh.chord.transport.TlsOptions;
import io.github.orhaugh.chord.transport.TransportOptions;
import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;

/**
 * Configuration for a single native connection.
 *
 * <p>Instances are immutable and reusable. The password is held as a defensive {@code char[]} copy
 * and is never included in {@link #toString()}, logs or exception messages.
 *
 * <p>Security default: a non empty password is refused over a plaintext transport unless {@link
 * Builder#allowPlaintextPassword(boolean)} is explicitly enabled, because the native protocol sends
 * the password verbatim inside the handshake. Configure {@link Builder#tls(TlsOptions)} instead;
 * the opt in exists for development and for networks that are secured by other means.
 */
@Experimental
public final class ConnectionOptions {

  private final String host;
  private final int port;
  private final TlsOptions tls;
  private final String database;
  private final String username;
  private final char[] password;
  private final String clientName;
  private final String quotaKey;
  private final long advertisedRevision;
  private final boolean allowPlaintextPassword;
  private final WireLimits wireLimits;
  private final BlockLimits blockLimits;
  private final Compression compression;
  private final int compressionLevel;
  private final CompressionLimits compressionLimits;
  private final TransportOptions transportOptions;

  private ConnectionOptions(Builder builder) {
    this.host = builder.host;
    this.port = builder.resolvedPort();
    this.tls = builder.tls;
    this.database = builder.database;
    this.username = builder.username;
    this.password = builder.password.clone();
    this.clientName = builder.clientName;
    this.quotaKey = builder.quotaKey;
    this.advertisedRevision = builder.advertisedRevision;
    this.allowPlaintextPassword = builder.allowPlaintextPassword;
    this.wireLimits = builder.wireLimits;
    this.blockLimits = builder.blockLimits;
    this.compression = builder.compression;
    this.compressionLevel = builder.compressionLevel;
    this.compressionLimits = builder.compressionLimits;
    this.transportOptions = builder.transportOptions;
  }

  /**
   * Creates a builder.
   *
   * @return a new builder with defaults applied
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Returns the server hostname or address.
   *
   * @return the host
   */
  public String host() {
    return host;
  }

  /**
   * Returns the native protocol port.
   *
   * @return the port
   */
  public int port() {
    return port;
  }

  /**
   * Returns the TLS configuration, empty for plain TCP.
   *
   * @return the TLS options
   */
  public java.util.Optional<TlsOptions> tls() {
    return java.util.Optional.ofNullable(tls);
  }

  /**
   * Returns the default database, empty for the server default.
   *
   * @return the database name
   */
  public String database() {
    return database;
  }

  /**
   * Returns the user to authenticate as.
   *
   * @return the username
   */
  public String username() {
    return username;
  }

  /**
   * Returns a fresh copy of the password characters. Callers own the copy and should wipe it after
   * use.
   *
   * @return a copy of the password, possibly empty
   */
  public char[] passwordChars() {
    return password.clone();
  }

  /**
   * Reports whether a password was configured.
   *
   * @return {@code true} when the password is non empty
   */
  public boolean hasPassword() {
    return password.length > 0;
  }

  /**
   * Returns the client name reported to the server.
   *
   * @return the client name
   */
  public String clientName() {
    return clientName;
  }

  /**
   * Returns the quota key sent in the handshake addendum, empty for none.
   *
   * @return the quota key
   */
  public String quotaKey() {
    return quotaKey;
  }

  /**
   * Returns the protocol revision advertised in ClientHello.
   *
   * @return the advertised revision
   */
  public long advertisedRevision() {
    return advertisedRevision;
  }

  /**
   * Reports whether sending a non empty password over a plaintext transport has been explicitly
   * permitted.
   *
   * @return {@code true} when the caller opted in
   */
  public boolean allowPlaintextPassword() {
    return allowPlaintextPassword;
  }

  /**
   * Returns the wire limits applied to server supplied lengths.
   *
   * @return the wire limits
   */
  public WireLimits wireLimits() {
    return wireLimits;
  }

  /**
   * Returns the limits applied to decoded block dimensions.
   *
   * @return the block limits
   */
  public BlockLimits blockLimits() {
    return blockLimits;
  }

  /**
   * Returns the compression applied to payloads this connection sends, empty when the exchange is
   * uncompressed. Reading always auto detects the method per frame.
   *
   * @return the compression method
   */
  public java.util.Optional<Compression> compression() {
    return java.util.Optional.ofNullable(compression);
  }

  /**
   * Returns the encoder level for the configured compression method.
   *
   * @return the level
   */
  public int compressionLevel() {
    return compressionLevel;
  }

  /**
   * Returns the limits applied to received compressed frames.
   *
   * @return the compression limits
   */
  public CompressionLimits compressionLimits() {
    return compressionLimits;
  }

  /**
   * Returns the socket configuration.
   *
   * @return the transport options
   */
  public TransportOptions transportOptions() {
    return transportOptions;
  }

  @Override
  public String toString() {
    return "ConnectionOptions{host="
        + host
        + ", port="
        + port
        + ", database="
        + database
        + ", username="
        + username
        + ", password=<redacted>"
        + ", tls="
        + (tls != null)
        + ", advertisedRevision="
        + advertisedRevision
        + "}";
  }

  /** Builder for {@link ConnectionOptions}. */
  public static final class Builder {

    private String host;
    private Integer port;
    private TlsOptions tls;
    private String database = "";
    private String username = "default";
    private char[] password = new char[0];
    private String clientName = "CHord Java";
    private String quotaKey = "";
    private long advertisedRevision = ProtocolRevisions.CURRENT;
    private boolean allowPlaintextPassword;
    private WireLimits wireLimits = WireLimits.DEFAULTS;
    private BlockLimits blockLimits = BlockLimits.DEFAULTS;
    private Compression compression;
    private int compressionLevel;
    private CompressionLimits compressionLimits = CompressionLimits.DEFAULTS;
    private TransportOptions transportOptions = TransportOptions.DEFAULTS;

    private Builder() {}

    /**
     * Sets the server hostname or address. Required.
     *
     * @param host the host
     * @return this builder
     */
    public Builder host(String host) {
      this.host = Objects.requireNonNull(host, "host");
      return this;
    }

    /**
     * Sets the native protocol port explicitly. When not set, the port defaults to 9000 for plain
     * TCP and to 9440, the conventional secure native port, once {@link #tls(TlsOptions)} is
     * configured.
     *
     * @param port the port
     * @return this builder
     */
    public Builder port(int port) {
      this.port = port;
      return this;
    }

    /**
     * Enables TLS for the connection. Hostname verification is always on; see {@link TlsOptions}
     * for trust and client certificate material. With TLS configured, passwords no longer require
     * the plaintext opt in and the default port becomes 9440.
     *
     * @param tls the TLS configuration
     * @return this builder
     */
    public Builder tls(TlsOptions tls) {
      this.tls = Objects.requireNonNull(tls, "tls");
      return this;
    }

    /**
     * Sets the default database. Defaults to empty, which selects the server default.
     *
     * @param database the database name
     * @return this builder
     */
    public Builder database(String database) {
      this.database = Objects.requireNonNull(database, "database");
      return this;
    }

    /**
     * Sets the user to authenticate as. Defaults to {@code default}.
     *
     * @param username the username
     * @return this builder
     */
    public Builder username(String username) {
      this.username = Objects.requireNonNull(username, "username");
      return this;
    }

    /**
     * Sets the password from a character array. The array is copied; the caller keeps ownership of
     * the original and may wipe it immediately.
     *
     * @param password the password characters
     * @return this builder
     */
    public Builder password(char[] password) {
      this.password = Objects.requireNonNull(password, "password").clone();
      return this;
    }

    /**
     * Sets the password from a character sequence. Prefer {@link #password(char[])} where the
     * secret's lifetime matters, since strings cannot be wiped.
     *
     * @param password the password
     * @return this builder
     */
    public Builder password(CharSequence password) {
      Objects.requireNonNull(password, "password");
      char[] chars = new char[password.length()];
      for (int i = 0; i < chars.length; i++) {
        chars[i] = password.charAt(i);
      }
      this.password = chars;
      return this;
    }

    /**
     * Sets the client name reported to the server, visible in {@code system.processes}.
     *
     * @param clientName the client name
     * @return this builder
     */
    public Builder clientName(String clientName) {
      this.clientName = Objects.requireNonNull(clientName, "clientName");
      return this;
    }

    /**
     * Sets the quota key sent in the handshake addendum.
     *
     * @param quotaKey the quota key
     * @return this builder
     */
    public Builder quotaKey(String quotaKey) {
      this.quotaKey = Objects.requireNonNull(quotaKey, "quotaKey");
      return this;
    }

    /**
     * Overrides the protocol revision advertised in ClientHello. Only revisions between the CHord
     * support floor and {@link ProtocolRevisions#CURRENT} are accepted; this is an escape hatch for
     * protocol debugging, not a compatibility knob.
     *
     * @param revision the revision to advertise
     * @return this builder
     */
    public Builder advertisedRevision(long revision) {
      this.advertisedRevision = revision;
      return this;
    }

    /**
     * Permits sending a non empty password over a plaintext transport. Off by default; leaving this
     * off makes accidental credential exposure a configuration error instead of a silent network
     * capture.
     *
     * @param allow {@code true} to permit plaintext password authentication
     * @return this builder
     */
    public Builder allowPlaintextPassword(boolean allow) {
      this.allowPlaintextPassword = allow;
      return this;
    }

    /**
     * Sets the wire limits applied to server supplied lengths.
     *
     * @param limits the limits
     * @return this builder
     */
    public Builder wireLimits(WireLimits limits) {
      this.wireLimits = Objects.requireNonNull(limits, "wireLimits");
      return this;
    }

    /**
     * Sets the limits applied to decoded block dimensions.
     *
     * @param limits the block limits
     * @return this builder
     */
    public Builder blockLimits(BlockLimits limits) {
      this.blockLimits = Objects.requireNonNull(limits, "blockLimits");
      return this;
    }

    /**
     * Enables compression for payloads this connection sends, at the method's default level. Data
     * blocks in both directions travel as checksummed frames; the server chooses its own method per
     * frame and CHord auto detects it. NONE gives integrity framing without compression.
     *
     * @param compression the method
     * @return this builder
     */
    public Builder compression(Compression compression) {
      this.compression = Objects.requireNonNull(compression, "compression");
      this.compressionLevel = compression.defaultLevel();
      return this;
    }

    /**
     * Enables compression with an explicit encoder level.
     *
     * @param compression the method
     * @param level the encoder level, validated per method
     * @return this builder
     */
    public Builder compression(Compression compression, int level) {
      this.compression = Objects.requireNonNull(compression, "compression");
      this.compressionLevel = compression.checkLevel(level);
      return this;
    }

    /**
     * Sets the limits applied to received compressed frames.
     *
     * @param limits the limits
     * @return this builder
     */
    public Builder compressionLimits(CompressionLimits limits) {
      this.compressionLimits = Objects.requireNonNull(limits, "compressionLimits");
      return this;
    }

    /**
     * Sets the socket configuration.
     *
     * @param options the transport options
     * @return this builder
     */
    public Builder transportOptions(TransportOptions options) {
      this.transportOptions = Objects.requireNonNull(options, "transportOptions");
      return this;
    }

    /**
     * Convenience for setting the transport connect timeout.
     *
     * @param timeout the connect timeout
     * @return this builder
     */
    public Builder connectTimeout(Duration timeout) {
      this.transportOptions = transportOptions.withConnectTimeout(timeout);
      return this;
    }

    /**
     * Convenience for setting the transport read timeout.
     *
     * @param timeout the read timeout
     * @return this builder
     */
    public Builder readTimeout(Duration timeout) {
      this.transportOptions = transportOptions.withReadTimeout(timeout);
      return this;
    }

    /**
     * Validates and builds the options.
     *
     * @return the immutable options
     */
    public ConnectionOptions build() {
      if (host == null || host.isBlank()) {
        throw new ChordConfigurationException("host is required");
      }
      int resolvedPort = resolvedPort();
      if (resolvedPort < 1 || resolvedPort > 65535) {
        throw new ChordConfigurationException(
            "port must be between 1 and 65535, was " + resolvedPort);
      }
      if (advertisedRevision < ProtocolRevisions.MIN_SUPPORTED_SERVER_REVISION
          || advertisedRevision > ProtocolRevisions.CURRENT) {
        throw new ChordConfigurationException(
            "advertisedRevision must be between "
                + ProtocolRevisions.MIN_SUPPORTED_SERVER_REVISION
                + " and "
                + ProtocolRevisions.CURRENT
                + ", was "
                + advertisedRevision);
      }
      return new ConnectionOptions(this);
    }

    private int resolvedPort() {
      if (port != null) {
        return port;
      }
      return tls != null ? 9440 : 9000;
    }
  }

  static void wipe(char[] secret) {
    Arrays.fill(secret, '\0');
  }
}
