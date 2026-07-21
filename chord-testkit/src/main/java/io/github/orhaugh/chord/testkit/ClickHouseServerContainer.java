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
package io.github.orhaugh.chord.testkit;

import java.time.Duration;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;

/**
 * A ClickHouse server container exposing the native TCP port, with deterministic credentials for
 * integration tests.
 *
 * <p>This fixture deliberately avoids any JDBC based wait strategy: readiness is probed through the
 * HTTP {@code /ping} endpoint, so the only ClickHouse client on the test classpath is the one under
 * test. Credentials are fixed test values, never secrets.
 */
public final class ClickHouseServerContainer extends GenericContainer<ClickHouseServerContainer> {

  /** Native protocol port inside the container. */
  public static final int NATIVE_PORT = 9000;

  /** Secure native protocol port inside the container, enabled by {@link #withSecureNativePort}. */
  public static final int SECURE_NATIVE_PORT = 9440;

  /** HTTP interface port inside the container, used only for readiness probes. */
  public static final int HTTP_PORT = 8123;

  /** Whether the secure native port requires a client certificate. */
  public enum ClientCertificateMode {
    /** The server presents its certificate; clients do not present one. */
    NONE,
    /** Mutual TLS: the handshake fails unless the client presents a certificate the CA signed. */
    REQUIRED
  }

  private boolean secureNativePortEnabled;

  /** Username provisioned in the container. */
  public static final String USERNAME = "chord";

  /** Password provisioned in the container. A fixed test value, not a secret. */
  public static final String PASSWORD = "chord-integration-secret";

  /** Database provisioned in the container. */
  public static final String DATABASE = "chord_test";

  /** Creates a container running the image chosen by {@link ClickHouseImages#resolve()}. */
  public ClickHouseServerContainer() {
    this(ClickHouseImages.resolve());
  }

  /**
   * Creates a container running a specific image.
   *
   * @param image the ClickHouse server image
   */
  public ClickHouseServerContainer(DockerImageName image) {
    super(image);
    addExposedPort(NATIVE_PORT);
    addExposedPort(HTTP_PORT);
    withEnv("CLICKHOUSE_USER", USERNAME);
    withEnv("CLICKHOUSE_PASSWORD", PASSWORD);
    withEnv("CLICKHOUSE_DB", DATABASE);
    waitingFor(
        Wait.forHttp("/ping")
            .forPort(HTTP_PORT)
            .forStatusCode(200)
            .withStartupTimeout(Duration.ofMinutes(2)));
  }

  /**
   * Enables the TLS native port (9440 in the container) using generated test material.
   *
   * <p>The server certificate, key and CA are copied into the container at start and a
   * configuration snippet enables {@code tcp_port_secure}. With {@link
   * ClientCertificateMode#REQUIRED} the server demands a client certificate signed by the test CA,
   * failing the handshake otherwise.
   *
   * @param certificates generated test material; its server SANs must cover the name tests connect
   *     to, typically {@code localhost}
   * @param mode whether clients must present a certificate
   * @return this container
   */
  public ClickHouseServerContainer withSecureNativePort(
      TestCertificates certificates, ClientCertificateMode mode) {
    addExposedPort(SECURE_NATIVE_PORT);
    withCopyToContainer(
        Transferable.of(certificates.serverCertificatePem()),
        "/etc/clickhouse-server/chord-tls/server.crt");
    withCopyToContainer(
        Transferable.of(certificates.serverKeyPem()),
        "/etc/clickhouse-server/chord-tls/server.key");
    withCopyToContainer(
        Transferable.of(certificates.caCertificatePem()),
        "/etc/clickhouse-server/chord-tls/ca.crt");
    String verificationMode = mode == ClientCertificateMode.REQUIRED ? "strict" : "none";
    // The config file targets the Linux container, so line endings are literal \n by design.
    String config =
        """
        <clickhouse>
          <tcp_port_secure>9440</tcp_port_secure>
          <openSSL>
            <server>
              <certificateFile>/etc/clickhouse-server/chord-tls/server.crt</certificateFile>
              <privateKeyFile>/etc/clickhouse-server/chord-tls/server.key</privateKeyFile>
              <caConfig>/etc/clickhouse-server/chord-tls/ca.crt</caConfig>
              <verificationMode>VERIFICATION_MODE</verificationMode>
              <loadDefaultCAFile>false</loadDefaultCAFile>
              <cacheSessions>true</cacheSessions>
              <preferServerCiphers>true</preferServerCiphers>
            </server>
          </openSSL>
        </clickhouse>
        """
            .replace("VERIFICATION_MODE", verificationMode);
    withCopyToContainer(Transferable.of(config), "/etc/clickhouse-server/config.d/chord-tls.xml");
    secureNativePortEnabled = true;
    return this;
  }

  /**
   * Returns the mapped secure native protocol port on the Docker host.
   *
   * @return the host port forwarding to the container's secure native port
   */
  public int secureNativePort() {
    if (!secureNativePortEnabled) {
      throw new IllegalStateException(
          "The secure native port is not enabled; call withSecureNativePort before start");
    }
    return getMappedPort(SECURE_NATIVE_PORT);
  }

  /**
   * Returns the mapped native protocol port on the Docker host.
   *
   * @return the host port forwarding to the container's native port
   */
  public int nativePort() {
    return getMappedPort(NATIVE_PORT);
  }

  /**
   * Returns the mapped HTTP port on the Docker host.
   *
   * @return the host port forwarding to the container's HTTP port
   */
  public int httpPort() {
    return getMappedPort(HTTP_PORT);
  }

  /**
   * Returns the provisioned username.
   *
   * @return the username
   */
  public String username() {
    return USERNAME;
  }

  /**
   * Returns the provisioned password.
   *
   * @return the password
   */
  public String password() {
    return PASSWORD;
  }

  /**
   * Returns the provisioned database.
   *
   * @return the database name
   */
  public String database() {
    return DATABASE;
  }
}
