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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.orhaugh.chord.client.ChordOperationListener;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** The operation listener bridge: timers per operation kind, tagged by outcome. */
class ChordClientMetricsTest {

  @Test
  void operationsRecordAsTaggedTimers() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    ChordOperationListener listener = ChordClientMetrics.bind(registry, "main");

    listener.connectFinished(true, Duration.ofMillis(12));
    listener.connectFinished(false, Duration.ofMillis(40));
    listener.queryFinished("finished", Duration.ofMillis(120), 1_000_000);
    listener.queryFinished("finished", Duration.ofMillis(80), 500);
    listener.queryFinished("server_error", Duration.ofMillis(5), 0);
    listener.insertFinished("committed", Duration.ofMillis(60), 100_000);

    Timer connects =
        registry
            .get("chord.client.connects")
            .tag("client", "main")
            .tag("result", "success")
            .timer();
    assertThat(connects.count()).isEqualTo(1);
    assertThat(connects.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(12.0);
    assertThat(registry.get("chord.client.connects").tag("result", "failure").timer().count())
        .isEqualTo(1);

    Timer finished = registry.get("chord.client.queries").tag("outcome", "finished").timer();
    assertThat(finished.count()).isEqualTo(2);
    assertThat(finished.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(200.0);
    assertThat(registry.get("chord.client.queries").tag("outcome", "server_error").timer().count())
        .isEqualTo(1);
    assertThat(registry.get("chord.client.inserts").tag("outcome", "committed").timer().count())
        .isEqualTo(1);
  }

  @Test
  void bindRejectsNullArguments() {
    assertThatThrownBy(() -> ChordClientMetrics.bind(null, "x"))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> ChordClientMetrics.bind(new SimpleMeterRegistry(), null))
        .isInstanceOf(NullPointerException.class);
  }
}
