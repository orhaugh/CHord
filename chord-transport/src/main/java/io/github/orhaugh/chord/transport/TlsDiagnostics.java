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

import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateNotYetValidException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;

/** Turns JSSE handshake failures into actionable messages and surfaces expiry diagnostics. */
final class TlsDiagnostics {

  private static final Duration EXPIRY_WARNING_WINDOW = Duration.ofDays(30);

  private TlsDiagnostics() {}

  /**
   * Describes a handshake failure with the most likely cause first: certificate validity, hostname
   * verification, then trust path problems.
   */
  static String describeHandshakeFailure(String host, int port, Throwable failure) {
    String base = "TLS handshake with " + host + ":" + port + " failed";
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof CertificateExpiredException) {
        return base
            + ": the server certificate has expired ("
            + cause.getMessage()
            + "). Renew the server certificate.";
      }
      if (cause instanceof CertificateNotYetValidException) {
        return base
            + ": the server certificate is not yet valid ("
            + cause.getMessage()
            + "). Check certificate validity dates and system clocks.";
      }
    }
    String message = String.valueOf(failure.getMessage());
    if (message.contains("subject alternative")
        || message.contains("No name matching")
        || message.contains("doesn't match")) {
      return base
          + ": hostname verification rejected the server certificate ("
          + message
          + "). The certificate does not name "
          + host
          + "; connect using a name the certificate carries.";
    }
    if (message.contains("PKIX path building failed")
        || message.contains("unable to find valid certification path")
        || message.contains("certificate_unknown")) {
      return base
          + ": the server certificate is not trusted ("
          + message
          + "). Configure trustedCertificates or a trust store containing the issuing CA.";
    }
    if (message.contains("bad_certificate") || message.contains("certificate_required")) {
      return base
          + ": the server rejected the client certificate ("
          + message
          + "). The server requires mutual TLS; configure clientCertificate or keyStore with"
          + " material the server trusts.";
    }
    return base + ": " + message;
  }

  /** Logs a warning when the presented server certificate is close to expiry. */
  static void warnIfExpiringSoon(Logger log, String host, X509Certificate certificate) {
    Instant notAfter = certificate.getNotAfter().toInstant();
    Instant threshold = Instant.now().plus(EXPIRY_WARNING_WINDOW);
    if (notAfter.isBefore(threshold)) {
      log.warn(
          "server certificate for {} expires soon: notAfter={} subject={}",
          host,
          notAfter,
          certificate.getSubjectX500Principal());
    }
  }
}
