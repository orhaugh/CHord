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
import java.nio.file.Path;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.Test;

/** Validation rules of {@link TlsOptions}. */
class TlsOptionsTest {

  private static final Path SOME_PATH = Path.of("material.pem");

  @Test
  void systemTrustIsTheDefault() {
    TlsOptions options = TlsOptions.systemTrust();
    assertThat(options.hasClientKeyMaterial()).isFalse();
    assertThat(options.sslContext()).isEmpty();
    assertThat(options.toString()).contains("systemTrust");
  }

  @Test
  void rejectsTwoTrustSources() {
    assertThatThrownBy(
            () ->
                TlsOptions.builder()
                    .trustStore(SOME_PATH, new char[0])
                    .trustedCertificates(SOME_PATH)
                    .build())
        .isInstanceOf(ChordConfigurationException.class)
        .hasMessageContaining("not both");
  }

  @Test
  void rejectsTwoKeySources() {
    assertThatThrownBy(
            () ->
                TlsOptions.builder()
                    .keyStore(SOME_PATH, new char[0], new char[0])
                    .clientCertificate(SOME_PATH, SOME_PATH, null)
                    .build())
        .isInstanceOf(ChordConfigurationException.class)
        .hasMessageContaining("not both");
  }

  @Test
  void customContextExcludesOtherMaterial() throws Exception {
    SSLContext context = SSLContext.getDefault();
    assertThatThrownBy(
            () -> TlsOptions.builder().sslContext(context).trustedCertificates(SOME_PATH).build())
        .isInstanceOf(ChordConfigurationException.class)
        .hasMessageContaining("mutually exclusive");
  }

  @Test
  void rejectsBlankProtocolAndCipherNames() {
    assertThatThrownBy(() -> TlsOptions.builder().protocols("TLSv1.3", " ").build())
        .isInstanceOf(ChordConfigurationException.class)
        .hasMessageContaining("protocol");
    assertThatThrownBy(() -> TlsOptions.builder().cipherSuites("").build())
        .isInstanceOf(ChordConfigurationException.class)
        .hasMessageContaining("cipher");
  }

  @Test
  void neverExposesPasswordsInToString() {
    TlsOptions options =
        TlsOptions.builder().trustStore(SOME_PATH, "store-secret".toCharArray()).build();
    assertThat(options.toString()).doesNotContain("store-secret");
  }

  @Test
  void thereIsNoTrustAllOrHostnameVerificationSwitch() {
    for (var method : TlsOptions.Builder.class.getMethods()) {
      String name = method.getName().toLowerCase(java.util.Locale.ROOT);
      assertThat(name)
          .as("builder method %s must not exist", method.getName())
          .doesNotContain("trustall")
          .doesNotContain("insecure")
          .doesNotContain("disablehostname")
          .doesNotContain("verifyhostname");
    }
  }
}
