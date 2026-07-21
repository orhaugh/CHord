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

import io.github.orhaugh.chord.testkit.TestCertificates;
import java.io.ByteArrayInputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.TrustManagerFactory;

/**
 * Minimal in-process TLS echo server for transport tests: accepts connections, reads one byte and
 * answers with that byte plus one. Handshake failures on individual connections are swallowed so
 * negative tests do not kill the accept loop.
 */
final class TlsTestServer implements AutoCloseable {

  private final SSLServerSocket serverSocket;
  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

  TlsTestServer(TestCertificates certificates, boolean requireClientCertificate) throws Exception {
    SSLContext context = serverContext(certificates);
    serverSocket = (SSLServerSocket) context.getServerSocketFactory().createServerSocket(0);
    serverSocket.setNeedClientAuth(requireClientCertificate);
    executor.submit(this::acceptLoop);
  }

  private static SSLContext serverContext(TestCertificates certificates) throws Exception {
    CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");
    Certificate serverCertificate =
        certificateFactory.generateCertificate(
            new ByteArrayInputStream(
                certificates.serverCertificatePem().getBytes(StandardCharsets.US_ASCII)));
    Certificate caCertificate =
        certificateFactory.generateCertificate(
            new ByteArrayInputStream(
                certificates.caCertificatePem().getBytes(StandardCharsets.US_ASCII)));
    PrivateKey serverKey = readServerKey(certificates);

    KeyStore keyStore = KeyStore.getInstance("PKCS12");
    keyStore.load(null, null);
    keyStore.setKeyEntry(
        "server", serverKey, new char[0], new Certificate[] {serverCertificate, caCertificate});
    KeyManagerFactory keyManagerFactory =
        KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    keyManagerFactory.init(keyStore, new char[0]);

    KeyStore trustStore = KeyStore.getInstance("PKCS12");
    trustStore.load(null, null);
    trustStore.setCertificateEntry("ca", caCertificate);
    TrustManagerFactory trustManagerFactory =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    trustManagerFactory.init(trustStore);

    SSLContext context = SSLContext.getInstance("TLS");
    context.init(keyManagerFactory.getKeyManagers(), trustManagerFactory.getTrustManagers(), null);
    return context;
  }

  private static PrivateKey readServerKey(TestCertificates certificates) throws Exception {
    byte[] der =
        java.util.Base64.getMimeDecoder()
            .decode(
                certificates
                    .serverKeyPem()
                    .replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", ""));
    return java.security.KeyFactory.getInstance("EC")
        .generatePrivate(new java.security.spec.PKCS8EncodedKeySpec(der));
  }

  private Void acceptLoop() {
    while (!serverSocket.isClosed()) {
      try {
        Socket socket = serverSocket.accept();
        executor.submit(
            () -> {
              try (socket) {
                int received = socket.getInputStream().read();
                if (received >= 0) {
                  socket.getOutputStream().write(received + 1);
                  socket.getOutputStream().flush();
                }
              } catch (Exception e) {
                // Failed handshakes and abrupt closes are expected in negative tests.
              }
              return null;
            });
      } catch (Exception e) {
        // Server socket closed; leave the loop.
        return null;
      }
    }
    return null;
  }

  int port() {
    return serverSocket.getLocalPort();
  }

  @Override
  public void close() throws java.io.IOException {
    serverSocket.close();
    executor.shutdownNow();
  }
}
