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

import org.testcontainers.utility.DockerImageName;

/**
 * Resolves which ClickHouse server image integration tests run against.
 *
 * <p>Order of precedence: the {@code chord.testkit.clickhouse.image} system property, the {@code
 * CHORD_CLICKHOUSE_IMAGE} environment variable, then {@link #DEFAULT_IMAGE}. CI sweeps the
 * supported release matrix by setting the system property per job.
 */
public final class ClickHouseImages {

  /** System property used to select the server image. */
  public static final String IMAGE_PROPERTY = "chord.testkit.clickhouse.image";

  /** Environment variable used to select the server image when the property is absent. */
  public static final String IMAGE_ENV_VARIABLE = "CHORD_CLICKHOUSE_IMAGE";

  /** The oldest currently supported ClickHouse LTS release. */
  public static final String DEFAULT_IMAGE = "clickhouse/clickhouse-server:25.8";

  private ClickHouseImages() {}

  /**
   * Resolves the configured server image.
   *
   * @return the image to run integration tests against
   */
  public static DockerImageName resolve() {
    String fromProperty = System.getProperty(IMAGE_PROPERTY);
    if (fromProperty != null && !fromProperty.isBlank()) {
      return DockerImageName.parse(fromProperty.trim());
    }
    String fromEnvironment = System.getenv(IMAGE_ENV_VARIABLE);
    if (fromEnvironment != null && !fromEnvironment.isBlank()) {
      return DockerImageName.parse(fromEnvironment.trim());
    }
    return DockerImageName.parse(DEFAULT_IMAGE);
  }
}
