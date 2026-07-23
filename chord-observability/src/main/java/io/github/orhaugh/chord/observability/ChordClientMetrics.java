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
package io.github.orhaugh.chord.observability;

import io.github.orhaugh.chord.annotations.Experimental;
import io.github.orhaugh.chord.client.ChordOperationListener;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Objects;

/**
 * Bridges {@link ChordOperationListener} to Micrometer timers, so per operation latency reaches any
 * registry without CHord depending on a metrics library.
 *
 * <p>Registered meters, all tagged {@code client} plus the outcome:
 *
 * <ul>
 *   <li>{@code chord.client.connects}, tagged {@code result=success|failure}
 *   <li>{@code chord.client.queries}, tagged {@code outcome} (finished, server_error, ...)
 *   <li>{@code chord.client.inserts}, tagged {@code outcome} (committed, aborted, failed)
 * </ul>
 *
 * <p>Usage: {@code
 * ConnectionOptions.builder()...operationListener(ChordClientMetrics.bind(registry, "main"))}. The
 * same listener may serve every connection of a pool.
 */
@Experimental
public final class ChordClientMetrics {

  private ChordClientMetrics() {}

  /**
   * Creates a listener recording operation timers into the registry.
   *
   * @param registry the registry to record into
   * @param clientName value of the {@code client} tag distinguishing multiple configurations
   * @return a listener for {@code ConnectionOptions.Builder#operationListener}
   */
  public static ChordOperationListener bind(MeterRegistry registry, String clientName) {
    Objects.requireNonNull(registry, "registry");
    Objects.requireNonNull(clientName, "clientName");
    Tags tags = Tags.of("client", clientName);
    return new ChordOperationListener() {
      @Override
      public void connectFinished(boolean succeeded, Duration duration) {
        Timer.builder("chord.client.connects")
            .tags(tags.and("result", succeeded ? "success" : "failure"))
            .description("Connection attempts including the handshake")
            .register(registry)
            .record(duration);
      }

      @Override
      public void queryFinished(String outcome, Duration duration, long rowsRead) {
        Timer.builder("chord.client.queries")
            .tags(tags.and("outcome", outcome))
            .description("Queries from request to stream conclusion")
            .register(registry)
            .record(duration);
      }

      @Override
      public void insertFinished(String outcome, Duration duration, long rowsSent) {
        Timer.builder("chord.client.inserts")
            .tags(tags.and("outcome", outcome))
            .description("Inserts from request to conclusion")
            .register(registry)
            .record(duration);
      }
    };
  }
}
