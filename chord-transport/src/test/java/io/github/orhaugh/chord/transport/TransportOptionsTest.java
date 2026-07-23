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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.orhaugh.chord.ChordConfigurationException;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** The options record: copy methods carry a single change and validation refuses nonsense. */
class TransportOptionsTest {

  @Test
  void withersReturnModifiedCopiesLeavingTheOriginalAlone() {
    TransportOptions original = TransportOptions.DEFAULTS;
    TransportOptions quicker = original.withConnectTimeout(Duration.ofMillis(300));

    assertThat(quicker.connectTimeout()).isEqualTo(Duration.ofMillis(300));
    assertThat(quicker.readTimeout()).isEqualTo(original.readTimeout());
    assertThat(original.connectTimeout()).isNotEqualTo(Duration.ofMillis(300));

    TransportOptions bounded = original.withReadTimeout(Duration.ofSeconds(5));
    assertThat(bounded.readTimeout()).isEqualTo(Duration.ofSeconds(5));
    assertThat(bounded.connectTimeout()).isEqualTo(original.connectTimeout());
  }

  @Test
  void validationRefusesImpossibleValues() {
    TransportOptions defaults = TransportOptions.DEFAULTS;
    assertThatThrownBy(() -> defaults.withConnectTimeout(Duration.ZERO))
        .isInstanceOf(ChordConfigurationException.class)
        .hasMessageContaining("connectTimeout");
    assertThatThrownBy(() -> defaults.withConnectTimeout(Duration.ofSeconds(-1)))
        .isInstanceOf(ChordConfigurationException.class);
    assertThatThrownBy(() -> defaults.withReadTimeout(Duration.ofSeconds(-1)))
        .isInstanceOf(ChordConfigurationException.class)
        .hasMessageContaining("readTimeout");
    // Zero read timeout is legal: it means no limit.
    assertThat(defaults.withReadTimeout(Duration.ZERO).readTimeout()).isEqualTo(Duration.ZERO);
  }
}
