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

import io.github.orhaugh.chord.ChordConfigurationException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;

/** Builds an {@link SSLContext} from {@link TlsOptions} trust and key material. */
final class TlsContexts {

  private TlsContexts() {}

  static SSLContext build(TlsOptions options) {
    if (options.sslContext().isPresent()) {
      return options.sslContext().get();
    }
    try {
      SSLContext context = SSLContext.getInstance("TLS");
      // An absent key manager array (null) keeps the JSSE default behaviour, which
      // honours the standard javax.net.ssl.keyStore system properties.
      context.init(keyManagers(options).orElse(null), trustManagers(options), null);
      return context;
    } catch (GeneralSecurityException e) {
      throw new ChordConfigurationException("Cannot initialise TLS context: " + e.getMessage());
    }
  }

  private static TrustManager[] trustManagers(TlsOptions options) throws GeneralSecurityException {
    if (options.trustedCertificatesPem().isPresent()) {
      List<X509Certificate> certificates =
          Pem.readCertificates(options.trustedCertificatesPem().get());
      KeyStore store = emptyKeyStore();
      for (int i = 0; i < certificates.size(); i++) {
        store.setCertificateEntry("chord-trusted-" + i, certificates.get(i));
      }
      return trustManagersFor(store);
    }
    if (options.trustStorePath().isPresent()) {
      Path path = options.trustStorePath().get();
      char[] password = options.trustStorePasswordChars();
      try {
        KeyStore store = loadKeyStore(path, password, options.trustStoreType().orElse(null));
        return trustManagersFor(store);
      } finally {
        wipe(password);
      }
    }
    // System default trust store.
    return trustManagersFor(null);
  }

  private static TrustManager[] trustManagersFor(KeyStore store) throws GeneralSecurityException {
    TrustManagerFactory factory =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    factory.init(store);
    return factory.getTrustManagers();
  }

  private static Optional<KeyManager[]> keyManagers(TlsOptions options)
      throws GeneralSecurityException {
    if (options.keyStorePath().isPresent()) {
      Path path = options.keyStorePath().get();
      char[] storePassword = options.keyStorePasswordChars();
      char[] keyPassword = options.keyPasswordChars();
      try {
        KeyStore store = loadKeyStore(path, storePassword, options.keyStoreType().orElse(null));
        return Optional.of(keyManagersFor(store, keyPassword));
      } finally {
        wipe(storePassword);
        wipe(keyPassword);
      }
    }
    if (options.clientCertificatePem().isPresent()) {
      List<X509Certificate> chain = Pem.readCertificates(options.clientCertificatePem().get());
      char[] keyPassword = options.clientKeyPasswordChars();
      char[] entryPassword = new char[0];
      try {
        PrivateKey key = Pem.readPrivateKey(options.clientKeyPem().orElseThrow(), keyPassword);
        KeyStore store = emptyKeyStore();
        store.setKeyEntry("chord-client", key, entryPassword, chain.toArray(new Certificate[0]));
        return Optional.of(keyManagersFor(store, entryPassword));
      } finally {
        wipe(keyPassword);
      }
    }
    return Optional.empty();
  }

  private static KeyManager[] keyManagersFor(KeyStore store, char[] keyPassword)
      throws GeneralSecurityException {
    KeyManagerFactory factory =
        KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    factory.init(store, keyPassword);
    return factory.getKeyManagers();
  }

  private static KeyStore emptyKeyStore() throws GeneralSecurityException {
    try {
      KeyStore store = KeyStore.getInstance("PKCS12");
      store.load(null, null);
      return store;
    } catch (IOException e) {
      throw new ChordConfigurationException(
          "Cannot initialise an in-memory key store: " + e.getMessage());
    }
  }

  private static KeyStore loadKeyStore(Path path, char[] password, String explicitType) {
    String type = explicitType != null ? explicitType : detectType(path);
    try (InputStream in = Files.newInputStream(path)) {
      KeyStore store = KeyStore.getInstance(type);
      store.load(in, password);
      return store;
    } catch (IOException | GeneralSecurityException e) {
      throw new ChordConfigurationException(
          "Cannot load " + type + " store " + path + ": " + e.getMessage());
    }
  }

  private static String detectType(Path path) {
    Path fileName = path.getFileName();
    if (fileName == null) {
      return "PKCS12";
    }
    String name = fileName.toString().toLowerCase(Locale.ROOT);
    return name.endsWith(".jks") ? "JKS" : "PKCS12";
  }

  private static void wipe(char[] secret) {
    if (secret != null) {
      Arrays.fill(secret, '\0');
    }
  }
}
