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
package io.github.orhaugh.chord.testkit;

import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.openssl.PKCS8Generator;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.openssl.jcajce.JcaPKCS8Generator;
import org.bouncycastle.openssl.jcajce.JceOpenSSLPKCS8EncryptorBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.util.io.pem.PemObject;

/**
 * Generates throwaway X.509 material for TLS tests at test run time: a CA, a server certificate
 * with configurable subject alternative names and validity, and a client certificate for mutual
 * TLS. Nothing is ever committed to the repository and every run produces fresh keys.
 *
 * <p>Uses BouncyCastle for generation only; the code under test parses this material with plain JDK
 * APIs.
 */
public final class TestCertificates {

  private final X509Certificate caCertificate;
  private final X509Certificate serverCertificate;
  private final PrivateKey serverKey;
  private final X509Certificate clientCertificate;
  private final PrivateKey clientKey;

  private TestCertificates(
      X509Certificate caCertificate,
      X509Certificate serverCertificate,
      PrivateKey serverKey,
      X509Certificate clientCertificate,
      PrivateKey clientKey) {
    this.caCertificate = caCertificate;
    this.serverCertificate = serverCertificate;
    this.serverKey = serverKey;
    this.clientCertificate = clientCertificate;
    this.clientKey = clientKey;
  }

  /**
   * Generates a CA, server and client certificate set with a seven day validity window.
   *
   * @param dnsSans DNS subject alternative names for the server certificate
   * @param ipSans IP subject alternative names for the server certificate
   * @return the generated material
   */
  public static TestCertificates generate(List<String> dnsSans, List<String> ipSans) {
    Instant now = Instant.now();
    return generateWithServerValidity(
        now.minus(1, ChronoUnit.HOURS), now.plus(7, ChronoUnit.DAYS), dnsSans, ipSans);
  }

  /**
   * Generates material with an explicit server certificate validity window, for expiry tests.
   *
   * @param serverNotBefore server certificate validity start
   * @param serverNotAfter server certificate validity end; may be in the past
   * @param dnsSans DNS subject alternative names for the server certificate
   * @param ipSans IP subject alternative names for the server certificate
   * @return the generated material
   */
  public static TestCertificates generateWithServerValidity(
      Instant serverNotBefore, Instant serverNotAfter, List<String> dnsSans, List<String> ipSans) {
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
      generator.initialize(256);
      KeyPair caPair = generator.generateKeyPair();
      KeyPair serverPair = generator.generateKeyPair();
      KeyPair clientPair = generator.generateKeyPair();

      Instant now = Instant.now();
      X500Name caName = new X500Name("CN=CHord Test CA");
      X509Certificate ca =
          sign(
              certificateBuilder(
                      caName,
                      caName,
                      caPair.getPublic(),
                      now.minus(1, ChronoUnit.HOURS),
                      now.plus(7, ChronoUnit.DAYS))
                  .addExtension(Extension.basicConstraints, true, new BasicConstraints(true))
                  .addExtension(
                      Extension.keyUsage,
                      true,
                      new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign)),
              caPair.getPrivate());

      List<GeneralName> names = new ArrayList<>();
      for (String dns : dnsSans) {
        names.add(new GeneralName(GeneralName.dNSName, dns));
      }
      for (String ip : ipSans) {
        names.add(new GeneralName(GeneralName.iPAddress, ip));
      }
      X509Certificate server =
          sign(
              certificateBuilder(
                      caName,
                      new X500Name("CN=chord-test-server"),
                      serverPair.getPublic(),
                      serverNotBefore,
                      serverNotAfter)
                  .addExtension(Extension.basicConstraints, true, new BasicConstraints(false))
                  .addExtension(
                      Extension.keyUsage,
                      true,
                      new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment))
                  .addExtension(
                      Extension.extendedKeyUsage,
                      false,
                      new ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth))
                  .addExtension(
                      Extension.subjectAlternativeName,
                      false,
                      new GeneralNames(names.toArray(new GeneralName[0]))),
              caPair.getPrivate());

      X509Certificate client =
          sign(
              certificateBuilder(
                      caName,
                      new X500Name("CN=chord-test-client"),
                      clientPair.getPublic(),
                      now.minus(1, ChronoUnit.HOURS),
                      now.plus(7, ChronoUnit.DAYS))
                  .addExtension(Extension.basicConstraints, true, new BasicConstraints(false))
                  .addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature))
                  .addExtension(
                      Extension.extendedKeyUsage,
                      false,
                      new ExtendedKeyUsage(KeyPurposeId.id_kp_clientAuth)),
              caPair.getPrivate());

      return new TestCertificates(
          ca, server, serverPair.getPrivate(), client, clientPair.getPrivate());
    } catch (GeneralSecurityException | OperatorCreationException | IOException e) {
      throw new IllegalStateException("Test certificate generation failed", e);
    }
  }

  private static JcaX509v3CertificateBuilder certificateBuilder(
      X500Name issuer,
      X500Name subject,
      java.security.PublicKey publicKey,
      Instant from,
      Instant to) {
    return new JcaX509v3CertificateBuilder(
        issuer,
        new BigInteger(96, new SecureRandom()),
        Date.from(from),
        Date.from(to),
        subject,
        publicKey);
  }

  private static X509Certificate sign(X509v3CertificateBuilder builder, PrivateKey signingKey)
      throws OperatorCreationException, GeneralSecurityException {
    ContentSigner signer = new JcaContentSignerBuilder("SHA256withECDSA").build(signingKey);
    return new JcaX509CertificateConverter().getCertificate(builder.build(signer));
  }

  /**
   * Returns the CA certificate in PEM form.
   *
   * @return PEM text
   */
  public String caCertificatePem() {
    return pem("CERTIFICATE", encoded(caCertificate));
  }

  /**
   * Returns the server certificate in PEM form.
   *
   * @return PEM text
   */
  public String serverCertificatePem() {
    return pem("CERTIFICATE", encoded(serverCertificate));
  }

  /**
   * Returns the server private key as unencrypted PKCS#8 PEM.
   *
   * @return PEM text
   */
  public String serverKeyPem() {
    return pem("PRIVATE KEY", serverKey.getEncoded());
  }

  /**
   * Returns the client certificate in PEM form.
   *
   * @return PEM text
   */
  public String clientCertificatePem() {
    return pem("CERTIFICATE", encoded(clientCertificate));
  }

  /**
   * Returns the client private key as unencrypted PKCS#8 PEM.
   *
   * @return PEM text
   */
  public String clientKeyPem() {
    return pem("PRIVATE KEY", clientKey.getEncoded());
  }

  /**
   * Returns the client private key as encrypted PKCS#8 PEM (PBES2 with AES-256-CBC).
   *
   * @param password encryption password
   * @return PEM text
   */
  public String encryptedClientKeyPem(char[] password) {
    try {
      JceOpenSSLPKCS8EncryptorBuilder encryptorBuilder =
          new JceOpenSSLPKCS8EncryptorBuilder(PKCS8Generator.AES_256_CBC);
      encryptorBuilder.setProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider());
      encryptorBuilder.setPassword(password);
      PemObject object = new JcaPKCS8Generator(clientKey, encryptorBuilder.build()).generate();
      StringWriter writer = new StringWriter();
      try (JcaPEMWriter pemWriter = new JcaPEMWriter(writer)) {
        pemWriter.writeObject(object);
      }
      return writer.toString();
    } catch (IOException | OperatorCreationException e) {
      throw new IllegalStateException("Cannot encrypt the client key", e);
    }
  }

  /**
   * Writes a PEM string to a file in the given directory.
   *
   * @param directory target directory, created if missing
   * @param fileName file name
   * @param pemText PEM content
   * @return the written path
   */
  public static Path write(Path directory, String fileName, String pemText) {
    try {
      Files.createDirectories(directory);
      Path file = directory.resolve(fileName);
      Files.writeString(file, pemText, StandardCharsets.US_ASCII);
      return file;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Writes a trust store containing the CA certificate.
   *
   * @param directory target directory
   * @param type {@code PKCS12} or {@code JKS}
   * @param password store password
   * @return the written path, named {@code truststore.p12} or {@code truststore.jks}
   */
  public Path writeTrustStore(Path directory, String type, char[] password) {
    try {
      KeyStore store = KeyStore.getInstance(type);
      store.load(null, null);
      store.setCertificateEntry("chord-test-ca", caCertificate);
      return writeStore(directory, store, type, password, "truststore");
    } catch (GeneralSecurityException | IOException e) {
      throw new IllegalStateException("Cannot write trust store", e);
    }
  }

  /**
   * Writes a key store containing the client key and certificate chain for mutual TLS.
   *
   * @param directory target directory
   * @param type {@code PKCS12} or {@code JKS}
   * @param password store and key password
   * @return the written path, named {@code client.p12} or {@code client.jks}
   */
  public Path writeClientKeyStore(Path directory, String type, char[] password) {
    try {
      KeyStore store = KeyStore.getInstance(type);
      store.load(null, null);
      store.setKeyEntry(
          "chord-test-client",
          clientKey,
          password,
          new java.security.cert.Certificate[] {clientCertificate, caCertificate});
      return writeStore(directory, store, type, password, "client");
    } catch (GeneralSecurityException | IOException e) {
      throw new IllegalStateException("Cannot write client key store", e);
    }
  }

  private static Path writeStore(
      Path directory, KeyStore store, String type, char[] password, String baseName)
      throws IOException, GeneralSecurityException {
    Files.createDirectories(directory);
    String extension = type.equalsIgnoreCase("JKS") ? ".jks" : ".p12";
    Path file = directory.resolve(baseName + extension);
    try (var out = Files.newOutputStream(file)) {
      store.store(out, password);
    }
    return file;
  }

  private static byte[] encoded(X509Certificate certificate) {
    try {
      return certificate.getEncoded();
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String pem(String label, byte[] der) {
    Base64.Encoder encoder = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII));
    return "-----BEGIN "
        + label
        + "-----\n"
        + encoder.encodeToString(der)
        + "\n-----END "
        + label
        + "-----\n";
  }
}
