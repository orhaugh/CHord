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
import io.github.orhaugh.chord.ChordTransportException;
import io.github.orhaugh.chord.testkit.TestCertificates;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * TLS transport behaviour against an in-process TLS server using certificates generated at test
 * time. Hostname verification is always on; every negative path must fail with an actionable
 * message.
 */
class TlsTransportTest {

  @TempDir static Path tempDir;

  private static TestCertificates certs;
  private static Path caPem;
  private static Path clientCertPem;
  private static Path clientKeyPem;

  @BeforeAll
  static void generateMaterial() {
    certs = TestCertificates.generate(List.of("localhost"), List.of("127.0.0.1"));
    caPem = TestCertificates.write(tempDir, "ca.crt", certs.caCertificatePem());
    clientCertPem = TestCertificates.write(tempDir, "client.crt", certs.clientCertificatePem());
    clientKeyPem = TestCertificates.write(tempDir, "client.key", certs.clientKeyPem());
  }

  private static TlsOptions pemTrust() {
    return TlsOptions.builder().trustedCertificates(caPem).build();
  }

  private static void assertEcho(TlsTransport transport) throws Exception {
    try {
      assertThat(transport.isSecure()).isTrue();
      transport.outputStream().write(41);
      transport.outputStream().flush();
      assertThat(transport.inputStream().read()).isEqualTo(42);
    } finally {
      transport.close();
    }
  }

  @Test
  void handshakesWithPemTrustAndExchangesBytes() throws Exception {
    try (TlsTestServer server = new TlsTestServer(certs, false)) {
      TlsTransport transport =
          TlsTransport.connect("localhost", server.port(), TransportOptions.DEFAULTS, pemTrust());
      assertEcho(transport);
      assertThat(transport.isOpen()).isFalse();
    }
  }

  @Test
  void verifiesIpAddressesAgainstIpSubjectAlternativeNames() throws Exception {
    try (TlsTestServer server = new TlsTestServer(certs, false)) {
      TlsTransport transport =
          TlsTransport.connect("127.0.0.1", server.port(), TransportOptions.DEFAULTS, pemTrust());
      assertEcho(transport);
    }
  }

  @Test
  void trustsPkcs12AndJksTrustStores() throws Exception {
    char[] password = "trust-password".toCharArray();
    Path pkcs12 = certs.writeTrustStore(tempDir.resolve("p12"), "PKCS12", password);
    Path jks = certs.writeTrustStore(tempDir.resolve("jks"), "JKS", password);
    try (TlsTestServer server = new TlsTestServer(certs, false)) {
      for (Path store : List.of(pkcs12, jks)) {
        TlsTransport transport =
            TlsTransport.connect(
                "localhost",
                server.port(),
                TransportOptions.DEFAULTS,
                TlsOptions.builder().trustStore(store, password).build());
        assertEcho(transport);
      }
    }
  }

  @Test
  void rejectsUntrustedServerWithTrustHint() throws Exception {
    try (TlsTestServer server = new TlsTestServer(certs, false)) {
      assertThatThrownBy(
              () ->
                  TlsTransport.connect(
                      "localhost",
                      server.port(),
                      TransportOptions.DEFAULTS,
                      TlsOptions.systemTrust()))
          .isInstanceOf(ChordTransportException.class)
          .hasMessageContaining("not trusted")
          .hasMessageContaining("trustedCertificates");
    }
  }

  @Test
  void rejectsHostnameMismatchWithVerificationHint() throws Exception {
    TestCertificates mismatched =
        TestCertificates.generate(List.of("clickhouse.invalid"), List.of());
    Path mismatchedCa =
        TestCertificates.write(tempDir.resolve("mm"), "ca.crt", mismatched.caCertificatePem());
    try (TlsTestServer server = new TlsTestServer(mismatched, false)) {
      assertThatThrownBy(
              () ->
                  TlsTransport.connect(
                      "localhost",
                      server.port(),
                      TransportOptions.DEFAULTS,
                      TlsOptions.builder().trustedCertificates(mismatchedCa).build()))
          .isInstanceOf(ChordTransportException.class)
          .hasMessageContaining("hostname verification");
    }
  }

  @Test
  void reportsExpiredServerCertificates() throws Exception {
    Instant now = Instant.now();
    TestCertificates expired =
        TestCertificates.generateWithServerValidity(
            now.minus(48, ChronoUnit.HOURS),
            now.minus(24, ChronoUnit.HOURS),
            List.of("localhost"),
            List.of());
    Path expiredCa =
        TestCertificates.write(tempDir.resolve("exp"), "ca.crt", expired.caCertificatePem());
    try (TlsTestServer server = new TlsTestServer(expired, false)) {
      assertThatThrownBy(
              () ->
                  TlsTransport.connect(
                      "localhost",
                      server.port(),
                      TransportOptions.DEFAULTS,
                      TlsOptions.builder().trustedCertificates(expiredCa).build()))
          .isInstanceOf(ChordTransportException.class)
          .hasMessageContaining("expired");
    }
  }

  @Test
  void presentsPemClientCertificateForMutualTls() throws Exception {
    try (TlsTestServer server = new TlsTestServer(certs, true)) {
      TlsTransport transport =
          TlsTransport.connect(
              "localhost",
              server.port(),
              TransportOptions.DEFAULTS,
              TlsOptions.builder()
                  .trustedCertificates(caPem)
                  .clientCertificate(clientCertPem, clientKeyPem, null)
                  .build());
      assertEcho(transport);
    }
  }

  @Test
  void presentsKeyStoreClientMaterialForMutualTls() throws Exception {
    char[] password = "keystore-password".toCharArray();
    Path keyStore = certs.writeClientKeyStore(tempDir.resolve("cks"), "PKCS12", password);
    try (TlsTestServer server = new TlsTestServer(certs, true)) {
      TlsTransport transport =
          TlsTransport.connect(
              "localhost",
              server.port(),
              TransportOptions.DEFAULTS,
              TlsOptions.builder()
                  .trustedCertificates(caPem)
                  .keyStore(keyStore, password, password)
                  .build());
      assertEcho(transport);
    }
  }

  @Test
  void decryptsEncryptedPkcs8ClientKeys() throws Exception {
    char[] keyPassword = "key-password".toCharArray();
    Path encryptedKey =
        TestCertificates.write(
            tempDir.resolve("enc"),
            "client-encrypted.key",
            certs.encryptedClientKeyPem(keyPassword.clone()));
    try (TlsTestServer server = new TlsTestServer(certs, true)) {
      TlsTransport transport =
          TlsTransport.connect(
              "localhost",
              server.port(),
              TransportOptions.DEFAULTS,
              TlsOptions.builder()
                  .trustedCertificates(caPem)
                  .clientCertificate(clientCertPem, encryptedKey, keyPassword)
                  .build());
      assertEcho(transport);
    }
  }

  @Test
  void failsWithoutClientCertificateAgainstStrictServer() throws Exception {
    try (TlsTestServer server = new TlsTestServer(certs, true)) {
      assertThatThrownBy(
              () -> {
                TlsTransport transport =
                    TlsTransport.connect(
                        "localhost", server.port(), TransportOptions.DEFAULTS, pemTrust());
                // TLS 1.3 servers may only reject after the first application data flows.
                try {
                  transport.outputStream().write(1);
                  transport.outputStream().flush();
                  transport.inputStream().read();
                } finally {
                  transport.close();
                }
              })
          .isInstanceOf(Exception.class)
          .satisfies(
              e ->
                  assertThat(e)
                      .isInstanceOfAny(ChordTransportException.class, java.io.IOException.class));
    }
  }

  @Test
  void rejectsTraditionalOpenSslKeysWithConversionHint() {
    Path pkcs1 =
        TestCertificates.write(
            tempDir.resolve("pkcs1"),
            "legacy.key",
            "-----BEGIN RSA PRIVATE KEY-----\nAAAA\n-----END RSA PRIVATE KEY-----\n");
    assertThatThrownBy(
            () ->
                TlsTransport.connect(
                    "localhost",
                    1,
                    TransportOptions.DEFAULTS,
                    TlsOptions.builder()
                        .trustedCertificates(caPem)
                        .clientCertificate(clientCertPem, pkcs1, null)
                        .build()))
        .isInstanceOf(ChordConfigurationException.class)
        .hasMessageContaining("openssl pkcs8 -topk8");
  }

  @Test
  void requiresPasswordForEncryptedKeys() {
    Path encryptedKey =
        TestCertificates.write(
            tempDir.resolve("nopw"),
            "client-encrypted.key",
            certs.encryptedClientKeyPem("secret".toCharArray()));
    assertThatThrownBy(
            () ->
                TlsTransport.connect(
                    "localhost",
                    1,
                    TransportOptions.DEFAULTS,
                    TlsOptions.builder()
                        .trustedCertificates(caPem)
                        .clientCertificate(clientCertPem, encryptedKey, null)
                        .build()))
        .isInstanceOf(ChordConfigurationException.class)
        .hasMessageContaining("no key password");
  }
}
