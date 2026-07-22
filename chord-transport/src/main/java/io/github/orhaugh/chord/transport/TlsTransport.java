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

import io.github.orhaugh.chord.ChordTimeoutException;
import io.github.orhaugh.chord.ChordTransportException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.List;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * TLS transport for the native protocol, layered over the same blocking socket dial and deadlines
 * as {@link TcpTransport}.
 *
 * <p>Hostname verification is always enabled through the JSSE endpoint identification algorithm and
 * cannot be switched off; SNI is sent for hostnames. Handshake failures surface as {@link
 * ChordTransportException} with a diagnosis of the most likely cause (expired certificate, hostname
 * mismatch, missing trust, rejected client certificate), and a server certificate close to expiry
 * logs a warning after the handshake.
 */
public final class TlsTransport implements NativeTransport {

  private static final Logger LOG = LoggerFactory.getLogger(TlsTransport.class);

  private final SSLSocket socket;
  private final InetSocketAddress address;
  private final PollBufferInputStream in;
  private final OutputStream out;
  private final int configuredReadTimeoutMillis;

  private TlsTransport(
      SSLSocket socket,
      InetSocketAddress address,
      InputStream in,
      OutputStream out,
      TransportOptions transportOptions) {
    this.socket = socket;
    this.address = address;
    // The poll buffer wraps the decrypted stream; awaitReadable polls plaintext bytes.
    this.in = new PollBufferInputStream(in);
    this.out = out;
    this.configuredReadTimeoutMillis = (int) transportOptions.readTimeout().toMillis();
  }

  /**
   * Opens a TCP connection, performs the TLS handshake and verifies the server identity.
   *
   * @param host server hostname or address; also the name verified against the certificate
   * @param port server secure native protocol port, conventionally 9440
   * @param transportOptions socket configuration
   * @param tlsOptions TLS configuration
   * @return the connected transport
   */
  public static TlsTransport connect(
      String host, int port, TransportOptions transportOptions, TlsOptions tlsOptions) {
    SSLContext context = TlsContexts.build(tlsOptions);
    InetSocketAddress address = new InetSocketAddress(host, port);
    Socket plain = TcpTransport.dial(address, transportOptions);
    SSLSocket ssl = null;
    try {
      SSLSocketFactory factory = context.getSocketFactory();
      Socket layered = factory.createSocket(plain, host, port, true);
      if (!(layered instanceof SSLSocket sslSocket)) {
        TcpTransport.closeQuietly(layered);
        throw new ChordTransportException(
            "SSLSocketFactory produced " + layered.getClass().getName() + " instead of SSLSocket");
      }
      ssl = sslSocket;
      ssl.setUseClientMode(true);

      SSLParameters parameters = ssl.getSSLParameters();
      // Non negotiable: RFC 6125 hostname verification during the handshake.
      parameters.setEndpointIdentificationAlgorithm("HTTPS");
      if (isHostname(host)) {
        parameters.setServerNames(List.of(new SNIHostName(host)));
      }
      if (!tlsOptions.protocols().isEmpty()) {
        parameters.setProtocols(tlsOptions.protocols().toArray(new String[0]));
      }
      if (!tlsOptions.cipherSuites().isEmpty()) {
        parameters.setCipherSuites(tlsOptions.cipherSuites().toArray(new String[0]));
      }
      ssl.setSSLParameters(parameters);

      ssl.startHandshake();

      SSLSession session = ssl.getSession();
      LOG.debug(
          "TLS established to {} using {} with {}",
          address,
          session.getProtocol(),
          session.getCipherSuite());
      warnOnImminentExpiry(host, session);

      return new TlsTransport(
          ssl, address, ssl.getInputStream(), ssl.getOutputStream(), transportOptions);
    } catch (SocketTimeoutException e) {
      closeQuietly(ssl, plain);
      throw new ChordTimeoutException(
          "TLS handshake with " + address + " timed out after " + transportOptions.readTimeout(),
          e);
    } catch (IOException e) {
      closeQuietly(ssl, plain);
      throw new ChordTransportException(TlsDiagnostics.describeHandshakeFailure(host, port, e), e);
    } catch (RuntimeException e) {
      closeQuietly(ssl, plain);
      throw e;
    }
  }

  private static void warnOnImminentExpiry(String host, SSLSession session) {
    try {
      Certificate[] chain = session.getPeerCertificates();
      if (chain.length > 0 && chain[0] instanceof X509Certificate leaf) {
        TlsDiagnostics.warnIfExpiringSoon(LOG, host, leaf);
      }
    } catch (SSLPeerUnverifiedException e) {
      // Unreachable with endpoint identification enabled; nothing to diagnose.
    }
  }

  private static boolean isHostname(String host) {
    // SNIHostName rejects IP literals; the JSSE matches IPs against IP subject
    // alternative names without SNI.
    if (host.isEmpty() || host.contains(":")) {
      return false;
    }
    boolean digitsAndDotsOnly = true;
    for (int i = 0; i < host.length(); i++) {
      char c = host.charAt(i);
      if (c != '.' && (c < '0' || c > '9')) {
        digitsAndDotsOnly = false;
        break;
      }
    }
    return !digitsAndDotsOnly;
  }

  private static void closeQuietly(SSLSocket ssl, Socket plain) {
    if (ssl != null) {
      TcpTransport.closeQuietly(ssl);
    }
    TcpTransport.closeQuietly(plain);
  }

  @Override
  public InputStream inputStream() {
    return in;
  }

  @Override
  public OutputStream outputStream() {
    return out;
  }

  @Override
  public SocketAddress remoteAddress() {
    return address;
  }

  @Override
  public boolean isOpen() {
    return !socket.isClosed();
  }

  @Override
  public boolean isSecure() {
    return true;
  }

  @Override
  public boolean awaitReadable(int timeoutMillis) {
    return TcpTransport.pollReadable(socket, in, configuredReadTimeoutMillis, timeoutMillis);
  }

  @Override
  public void close() {
    TcpTransport.closeQuietly(socket);
  }
}
