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
package io.github.orhaugh.chord;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The retry classification defaults and stamping rules. */
class RetryClassificationTest {

  @Test
  void typeDefaultsAreConservative() {
    assertThat(new ChordConfigurationException("bad").retryClass())
        .isEqualTo(RetryClass.NOT_RETRYABLE);
    assertThat(new ChordTypeException("bad").retryClass()).isEqualTo(RetryClass.NOT_RETRYABLE);
    assertThat(new ChordTransportException("lost").retryClass())
        .isEqualTo(RetryClass.OUTCOME_UNKNOWN);
    assertThat(new ChordTimeoutException("late").retryClass())
        .isEqualTo(RetryClass.OUTCOME_UNKNOWN);
    assertThat(new ChordProtocolException("garbage").retryClass())
        .isEqualTo(RetryClass.OUTCOME_UNKNOWN);
    assertThat(new ChordDataCorruptionException("checksum").retryClass())
        .isEqualTo(RetryClass.OUTCOME_UNKNOWN);
  }

  @ParameterizedTest
  @CsvSource({
    "202, SAFE_TO_RETRY", // TOO_MANY_SIMULTANEOUS_QUERIES
    "203, SAFE_TO_RETRY", // NO_FREE_CONNECTION
    "159, RETRY_ONLY_IF_IDEMPOTENT", // TIMEOUT_EXCEEDED
    "209, RETRY_ONLY_IF_IDEMPOTENT", // SOCKET_TIMEOUT
    "210, RETRY_ONLY_IF_IDEMPOTENT", // NETWORK_ERROR
    "241, RETRY_ONLY_IF_IDEMPOTENT", // MEMORY_LIMIT_EXCEEDED
    "242, RETRY_ONLY_IF_IDEMPOTENT", // TABLE_IS_READ_ONLY
    "252, RETRY_ONLY_IF_IDEMPOTENT", // TOO_MANY_PARTS
    "285, RETRY_ONLY_IF_IDEMPOTENT", // TOO_FEW_LIVE_REPLICAS
    "319, OUTCOME_UNKNOWN", // UNKNOWN_STATUS_OF_INSERT
    "62, NOT_RETRYABLE", // SYNTAX_ERROR
    "516, NOT_RETRYABLE", // AUTHENTICATION_FAILED
    "81, NOT_RETRYABLE" // UNKNOWN_DATABASE
  })
  void serverCodesClassifyByTheDocumentedTable(int code, RetryClass expected) {
    assertThat(new ChordServerException(code, "DB::Exception", "boom", "", null).retryClass())
        .isEqualTo(expected);
  }

  @Test
  void theFirstClassificationWinsAndLaterOnesAreIgnored() {
    ChordTransportException e = new ChordTransportException("lost during connect");
    e.classifiedAs(RetryClass.SAFE_TO_RETRY);
    e.classifiedAs(RetryClass.OUTCOME_UNKNOWN);
    assertThat(e.retryClass()).isEqualTo(RetryClass.SAFE_TO_RETRY);
  }

  @Test
  void stampingOverridesTheTypeDefault() {
    ChordTimeoutException e = new ChordTimeoutException("query stream timed out");
    assertThat(e.retryClass()).isEqualTo(RetryClass.OUTCOME_UNKNOWN);
    e.classifiedAs(RetryClass.RETRY_ONLY_IF_IDEMPOTENT);
    assertThat(e.retryClass()).isEqualTo(RetryClass.RETRY_ONLY_IF_IDEMPOTENT);
  }
}
