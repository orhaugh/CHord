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
package io.github.orhaugh.chord.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Sanity checks over the revision registry. */
class ProtocolFeatureTest {

  @Test
  void gatesAreMonotonicallyOrderedInDeclarationOrder() {
    long previous = 0;
    for (ProtocolFeature feature : ProtocolFeature.values()) {
      assertThat(feature.minRevision())
          .as("feature %s must not be gated below its predecessor", feature)
          .isGreaterThanOrEqualTo(previous);
      previous = feature.minRevision();
    }
  }

  @Test
  void noFeatureIsGatedAboveTheAdvertisedRevision() {
    for (ProtocolFeature feature : ProtocolFeature.values()) {
      assertThat(feature.minRevision()).isLessThanOrEqualTo(ProtocolRevisions.CURRENT);
    }
  }

  @Test
  void enabledForComparesAgainstTheGate() {
    assertThat(ProtocolFeature.CHUNKED_PACKETS.enabledFor(54469)).isFalse();
    assertThat(ProtocolFeature.CHUNKED_PACKETS.enabledFor(54470)).isTrue();
    assertThat(ProtocolFeature.SERVER_SETTINGS_IN_HELLO.enabledFor(54473)).isFalse();
    assertThat(ProtocolFeature.SERVER_SETTINGS_IN_HELLO.enabledFor(54474)).isTrue();
  }

  @Test
  void negotiationTakesTheMinimum() {
    assertThat(ProtocolRevisions.negotiate(54488, 54479)).isEqualTo(54479);
    assertThat(ProtocolRevisions.negotiate(54470, 54488)).isEqualTo(54470);
  }

  @Test
  void knownServerReleasesAreAboveTheSupportFloor() {
    // 25.8 = 54479, 26.3 = 54484, 26.6 = 54485.
    for (long revision : new long[] {54479, 54484, 54485}) {
      assertThat(revision).isGreaterThanOrEqualTo(ProtocolRevisions.MIN_SUPPORTED_SERVER_REVISION);
    }
  }
}
