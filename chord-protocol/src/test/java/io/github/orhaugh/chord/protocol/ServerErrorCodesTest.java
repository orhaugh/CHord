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

/** The authentication failure classification and its boundaries. */
class ServerErrorCodesTest {

  @Test
  void theFourCredentialCodesClassifyAsAuthenticationFailures() {
    assertThat(ServerErrorCodes.isAuthenticationFailure(ServerErrorCodes.UNKNOWN_USER)).isTrue();
    assertThat(ServerErrorCodes.isAuthenticationFailure(ServerErrorCodes.WRONG_PASSWORD)).isTrue();
    assertThat(ServerErrorCodes.isAuthenticationFailure(ServerErrorCodes.REQUIRED_PASSWORD))
        .isTrue();
    assertThat(ServerErrorCodes.isAuthenticationFailure(ServerErrorCodes.AUTHENTICATION_FAILED))
        .isTrue();
  }

  @Test
  void adjacentAndRelatedCodesDoNot() {
    // Authorisation and existence problems are not credential problems.
    assertThat(ServerErrorCodes.isAuthenticationFailure(ServerErrorCodes.ACCESS_DENIED)).isFalse();
    assertThat(ServerErrorCodes.isAuthenticationFailure(ServerErrorCodes.UNKNOWN_DATABASE))
        .isFalse();
    // Boundary neighbours of the credential code cluster.
    assertThat(ServerErrorCodes.isAuthenticationFailure(191)).isFalse();
    assertThat(ServerErrorCodes.isAuthenticationFailure(195)).isFalse();
    assertThat(ServerErrorCodes.isAuthenticationFailure(515)).isFalse();
    assertThat(ServerErrorCodes.isAuthenticationFailure(517)).isFalse();
    assertThat(ServerErrorCodes.isAuthenticationFailure(0)).isFalse();
    assertThat(ServerErrorCodes.isAuthenticationFailure(-516)).isFalse();
  }
}
