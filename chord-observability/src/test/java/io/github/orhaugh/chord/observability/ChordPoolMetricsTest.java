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

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

/** The gauge binding contract, checked against the meter registry only. */
class ChordPoolMetricsTest {

  @Test
  void bindRegistersTheGaugesWithThePoolTag() {
    // The pool is exercised against real servers in chord-client tests; here the subject is
    // the meter binding itself, so an unstarted pool with a failing factory is sufficient.
    try (io.github.orhaugh.chord.client.ConnectionPool pool =
        io.github.orhaugh.chord.client.ConnectionPool.builder(
                () -> {
                  throw new io.github.orhaugh.chord.ChordTransportException("never dialled");
                })
            .maxSize(3)
            .build()) {
      SimpleMeterRegistry registry = new SimpleMeterRegistry();
      ChordPoolMetrics.bind(pool, registry, "analytics");

      assertThat(
              registry
                  .get("chord.pool.connections.active")
                  .tag("pool", "analytics")
                  .gauge()
                  .value())
          .isEqualTo(0.0);
      assertThat(
              registry.get("chord.pool.connections.idle").tag("pool", "analytics").gauge().value())
          .isEqualTo(0.0);
    }
  }
}
