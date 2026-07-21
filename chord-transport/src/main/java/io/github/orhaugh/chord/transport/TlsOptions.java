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
package io.github.orhaugh.chord.transport;

import io.github.orhaugh.chord.ChordConfigurationException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.net.ssl.SSLContext;

/**
 * TLS configuration for the native transport.
 *
 * <p>Secure by design: hostname verification is always on and cannot be switched off through this
 * API, there is no trust-everything option, and credentials for stores are held as defensively
 * copied {@code char[]} values that never appear in {@code toString()}.
 *
 * <p>Trust material comes from exactly one source: the system default trust store (the default), a
 * JKS or PKCS#12 {@linkplain Builder#trustStore(Path, char[]) trust store file}, a {@linkplain
 * Builder#trustedCertificates(Path) PEM certificate bundle}, or a fully {@linkplain
 * Builder#sslContext(SSLContext) custom SSLContext}. Client key material for mutual TLS comes from
 * at most one source: a {@linkplain Builder#keyStore(Path, char[], char[]) key store file} or a
 * {@linkplain Builder#clientCertificate(Path, Path, char[]) PEM certificate and PKCS#8 key pair}.
 */
public final class TlsOptions {

  private final Path trustStorePath;
  private final char[] trustStorePassword;
  private final String trustStoreType;
  private final Path trustedCertificatesPem;
  private final Path keyStorePath;
  private final char[] keyStorePassword;
  private final char[] keyPassword;
  private final String keyStoreType;
  private final Path clientCertificatePem;
  private final Path clientKeyPem;
  private final char[] clientKeyPassword;
  private final List<String> protocols;
  private final List<String> cipherSuites;
  private final SSLContext sslContext;

  private TlsOptions(Builder builder) {
    this.trustStorePath = builder.trustStorePath;
    this.trustStorePassword = clone(builder.trustStorePassword);
    this.trustStoreType = builder.trustStoreType;
    this.trustedCertificatesPem = builder.trustedCertificatesPem;
    this.keyStorePath = builder.keyStorePath;
    this.keyStorePassword = clone(builder.keyStorePassword);
    this.keyPassword = clone(builder.keyPassword);
    this.keyStoreType = builder.keyStoreType;
    this.clientCertificatePem = builder.clientCertificatePem;
    this.clientKeyPem = builder.clientKeyPem;
    this.clientKeyPassword = clone(builder.clientKeyPassword);
    this.protocols = List.copyOf(builder.protocols);
    this.cipherSuites = List.copyOf(builder.cipherSuites);
    this.sslContext = builder.sslContext;
  }

  private static char[] clone(char[] value) {
    return value == null ? null : value.clone();
  }

  /**
   * Returns options trusting the system default trust store, with hostname verification on and JSSE
   * default protocols and cipher suites. The most common production configuration for servers with
   * publicly or organisationally trusted certificates.
   *
   * @return system trust options
   */
  public static TlsOptions systemTrust() {
    return builder().build();
  }

  /**
   * Creates a builder.
   *
   * @return a new builder defaulting to system trust
   */
  public static Builder builder() {
    return new Builder();
  }

  Optional<Path> trustStorePath() {
    return Optional.ofNullable(trustStorePath);
  }

  char[] trustStorePasswordChars() {
    return clone(trustStorePassword);
  }

  Optional<String> trustStoreType() {
    return Optional.ofNullable(trustStoreType);
  }

  Optional<Path> trustedCertificatesPem() {
    return Optional.ofNullable(trustedCertificatesPem);
  }

  Optional<Path> keyStorePath() {
    return Optional.ofNullable(keyStorePath);
  }

  char[] keyStorePasswordChars() {
    return clone(keyStorePassword);
  }

  char[] keyPasswordChars() {
    return clone(keyPassword);
  }

  Optional<String> keyStoreType() {
    return Optional.ofNullable(keyStoreType);
  }

  Optional<Path> clientCertificatePem() {
    return Optional.ofNullable(clientCertificatePem);
  }

  Optional<Path> clientKeyPem() {
    return Optional.ofNullable(clientKeyPem);
  }

  char[] clientKeyPasswordChars() {
    return clone(clientKeyPassword);
  }

  /**
   * Returns the enabled protocol versions, empty for the JSSE defaults.
   *
   * @return the protocol list
   */
  public List<String> protocols() {
    return protocols;
  }

  /**
   * Returns the enabled cipher suites, empty for the JSSE defaults.
   *
   * @return the cipher suite list
   */
  public List<String> cipherSuites() {
    return cipherSuites;
  }

  /**
   * Returns the caller supplied SSLContext, if one was configured.
   *
   * @return the custom context
   */
  public Optional<SSLContext> sslContext() {
    return Optional.ofNullable(sslContext);
  }

  /**
   * Reports whether client key material is configured, meaning the connection attempts mutual TLS.
   *
   * @return {@code true} when a key store or client certificate pair is configured
   */
  public boolean hasClientKeyMaterial() {
    return keyStorePath != null || clientCertificatePem != null;
  }

  @Override
  public String toString() {
    String trust =
        sslContext != null
            ? "customContext"
            : trustStorePath != null
                ? "trustStore=" + trustStorePath
                : trustedCertificatesPem != null
                    ? "trustedCertificates=" + trustedCertificatesPem
                    : "systemTrust";
    return "TlsOptions{" + trust + ", mutualTls=" + hasClientKeyMaterial() + "}";
  }

  /** Builder for {@link TlsOptions}. */
  public static final class Builder {

    private Path trustStorePath;
    private char[] trustStorePassword;
    private String trustStoreType;
    private Path trustedCertificatesPem;
    private Path keyStorePath;
    private char[] keyStorePassword;
    private char[] keyPassword;
    private String keyStoreType;
    private Path clientCertificatePem;
    private Path clientKeyPem;
    private char[] clientKeyPassword;
    private List<String> protocols = List.of();
    private List<String> cipherSuites = List.of();
    private SSLContext sslContext;

    private Builder() {}

    /**
     * Trusts the certificates in a JKS or PKCS#12 trust store file. The type is taken from {@link
     * #trustStoreType(String)} when set, otherwise inferred from the file extension ({@code .jks}
     * means JKS), defaulting to PKCS#12.
     *
     * @param path trust store file
     * @param password trust store password, copied; may be empty
     * @return this builder
     */
    public Builder trustStore(Path path, char[] password) {
      this.trustStorePath = Objects.requireNonNull(path, "path");
      this.trustStorePassword = Objects.requireNonNull(password, "password").clone();
      return this;
    }

    /**
     * Sets the trust store type explicitly, {@code PKCS12} or {@code JKS}.
     *
     * @param type key store type
     * @return this builder
     */
    public Builder trustStoreType(String type) {
      this.trustStoreType = Objects.requireNonNull(type, "type");
      return this;
    }

    /**
     * Trusts the X.509 certificates in a PEM file, typically a private CA bundle. The file may
     * contain several concatenated certificates.
     *
     * @param pemFile PEM certificate bundle
     * @return this builder
     */
    public Builder trustedCertificates(Path pemFile) {
      this.trustedCertificatesPem = Objects.requireNonNull(pemFile, "pemFile");
      return this;
    }

    /**
     * Presents client key material from a JKS or PKCS#12 key store for mutual TLS.
     *
     * @param path key store file
     * @param storePassword key store password, copied
     * @param keyPassword password of the key entry, copied; pass the store password when they are
     *     the same
     * @return this builder
     */
    public Builder keyStore(Path path, char[] storePassword, char[] keyPassword) {
      this.keyStorePath = Objects.requireNonNull(path, "path");
      this.keyStorePassword = Objects.requireNonNull(storePassword, "storePassword").clone();
      this.keyPassword = Objects.requireNonNull(keyPassword, "keyPassword").clone();
      return this;
    }

    /**
     * Sets the key store type explicitly, {@code PKCS12} or {@code JKS}.
     *
     * @param type key store type
     * @return this builder
     */
    public Builder keyStoreType(String type) {
      this.keyStoreType = Objects.requireNonNull(type, "type");
      return this;
    }

    /**
     * Presents a PEM client certificate (or chain) and PKCS#8 private key for mutual TLS.
     * Unencrypted ({@code BEGIN PRIVATE KEY}) and encrypted ({@code BEGIN ENCRYPTED PRIVATE KEY})
     * PKCS#8 keys are supported; traditional OpenSSL formats such as {@code BEGIN RSA PRIVATE KEY}
     * are rejected with a conversion hint.
     *
     * @param certificatePem PEM file with the client certificate chain, leaf first
     * @param keyPem PEM file with the PKCS#8 private key
     * @param keyPassword password for an encrypted key, copied; {@code null} for unencrypted
     * @return this builder
     */
    public Builder clientCertificate(Path certificatePem, Path keyPem, char[] keyPassword) {
      this.clientCertificatePem = Objects.requireNonNull(certificatePem, "certificatePem");
      this.clientKeyPem = Objects.requireNonNull(keyPem, "keyPem");
      this.clientKeyPassword = keyPassword == null ? null : keyPassword.clone();
      return this;
    }

    /**
     * Restricts the enabled TLS protocol versions, for example {@code "TLSv1.3"}. Empty means the
     * JSSE defaults.
     *
     * @param protocols protocol names
     * @return this builder
     */
    public Builder protocols(String... protocols) {
      this.protocols = List.of(protocols);
      return this;
    }

    /**
     * Restricts the enabled cipher suites. Empty means the JSSE defaults. Prefer restricting {@link
     * #protocols(String...)} to TLSv1.3 over hand picking suites.
     *
     * @param cipherSuites cipher suite names
     * @return this builder
     */
    public Builder cipherSuites(String... cipherSuites) {
      this.cipherSuites = List.of(cipherSuites);
      return this;
    }

    /**
     * Uses a fully caller managed SSLContext instead of CHord built trust and key material.
     * Mutually exclusive with every trust store, certificate and key option; protocol and cipher
     * suite restrictions still apply, as does hostname verification.
     *
     * @param context the context to use
     * @return this builder
     */
    public Builder sslContext(SSLContext context) {
      this.sslContext = Objects.requireNonNull(context, "context");
      return this;
    }

    /**
     * Validates and builds the options.
     *
     * @return the immutable options
     */
    public TlsOptions build() {
      if (trustStorePath != null && trustedCertificatesPem != null) {
        throw new ChordConfigurationException(
            "Configure either trustStore or trustedCertificates, not both");
      }
      if (keyStorePath != null && clientCertificatePem != null) {
        throw new ChordConfigurationException(
            "Configure either keyStore or clientCertificate for mutual TLS, not both");
      }
      if (sslContext != null
          && (trustStorePath != null
              || trustedCertificatesPem != null
              || keyStorePath != null
              || clientCertificatePem != null)) {
        throw new ChordConfigurationException(
            "A custom sslContext is mutually exclusive with trust store, certificate and key"
                + " options; build that material into the context instead");
      }
      for (String protocol : protocols) {
        if (protocol.isBlank()) {
          throw new ChordConfigurationException("protocol names must not be blank");
        }
      }
      for (String suite : cipherSuites) {
        if (suite.isBlank()) {
          throw new ChordConfigurationException("cipher suite names must not be blank");
        }
      }
      return new TlsOptions(this);
    }
  }
}
