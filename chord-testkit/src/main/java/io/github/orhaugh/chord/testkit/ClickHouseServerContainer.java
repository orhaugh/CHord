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

  /** HTTP interface port inside the container, used only for readiness probes. */
  public static final int HTTP_PORT = 8123;

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
