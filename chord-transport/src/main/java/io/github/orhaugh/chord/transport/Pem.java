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
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Cipher;
import javax.crypto.EncryptedPrivateKeyInfo;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Minimal PEM handling using only JDK APIs: X.509 certificate bundles and PKCS#8 private keys,
 * unencrypted or encrypted. Traditional OpenSSL key formats (PKCS#1 {@code BEGIN RSA PRIVATE KEY},
 * SEC1 {@code BEGIN EC PRIVATE KEY}) are deliberately rejected with a conversion hint rather than
 * half supported.
 */
final class Pem {

  private static final Pattern BLOCK =
      Pattern.compile("-----BEGIN ([A-Z0-9 ]+)-----\\s*(.*?)\\s*-----END \\1-----", Pattern.DOTALL);

  private Pem() {}

  /**
   * Reads every X.509 certificate from a PEM file.
   *
   * @param file PEM file possibly holding several certificates
   * @return the certificates in file order
   */
  static List<X509Certificate> readCertificates(Path file) {
    byte[] bytes = readFile(file);
    try {
      CertificateFactory factory = CertificateFactory.getInstance("X.509");
      List<X509Certificate> certificates = new ArrayList<>();
      for (var certificate : factory.generateCertificates(new ByteArrayInputStream(bytes))) {
        certificates.add((X509Certificate) certificate);
      }
      if (certificates.isEmpty()) {
        throw new ChordConfigurationException("No certificates found in " + file);
      }
      return certificates;
    } catch (CertificateException e) {
      throw new ChordConfigurationException(
          "Cannot parse certificates from " + file + ": " + e.getMessage());
    }
  }

  /**
   * Reads a PKCS#8 private key from a PEM file.
   *
   * @param file PEM file with one private key block
   * @param password password for an encrypted key, or {@code null} for unencrypted
   * @return the private key
   */
  static PrivateKey readPrivateKey(Path file, char[] password) {
    String text = new String(readFile(file), StandardCharsets.US_ASCII);
    Matcher matcher = BLOCK.matcher(text);
    while (matcher.find()) {
      String label = matcher.group(1);
      byte[] der = Base64.getMimeDecoder().decode(matcher.group(2));
      switch (label) {
        case "PRIVATE KEY":
          return decodePkcs8(der, file);
        case "ENCRYPTED PRIVATE KEY":
          return decodeEncryptedPkcs8(der, password, file);
        case "RSA PRIVATE KEY", "EC PRIVATE KEY", "DSA PRIVATE KEY":
          throw new ChordConfigurationException(
              file
                  + " holds a traditional "
                  + label
                  + " block. Convert it to PKCS#8 first: openssl pkcs8 -topk8 -nocrypt -in key.pem"
                  + " -out key-pkcs8.pem");
        default:
          // Skip unrelated blocks such as certificates sitting in the same file.
      }
    }
    throw new ChordConfigurationException("No PKCS#8 private key block found in " + file);
  }

  private static PrivateKey decodePkcs8(byte[] der, Path file) {
    PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(der);
    for (String algorithm : new String[] {"EC", "RSA", "EdDSA", "DSA"}) {
      try {
        return KeyFactory.getInstance(algorithm).generatePrivate(spec);
      } catch (GeneralSecurityException e) {
        // Try the next algorithm; PKCS#8 carries the algorithm but KeyFactory needs a match.
      }
    }
    throw new ChordConfigurationException(
        "Cannot decode the PKCS#8 key in " + file + " as EC, RSA, EdDSA or DSA");
  }

  private static PrivateKey decodeEncryptedPkcs8(byte[] der, char[] password, Path file) {
    if (password == null) {
      throw new ChordConfigurationException(
          file + " holds an encrypted private key but no key password was configured");
    }
    try {
      EncryptedPrivateKeyInfo encrypted = new EncryptedPrivateKeyInfo(der);
      SecretKeyFactory secretFactory = SecretKeyFactory.getInstance(encrypted.getAlgName());
      SecretKey secret = secretFactory.generateSecret(new PBEKeySpec(password));
      Cipher cipher = Cipher.getInstance(encrypted.getAlgName());
      cipher.init(Cipher.DECRYPT_MODE, secret, encrypted.getAlgParameters());
      PKCS8EncodedKeySpec spec = encrypted.getKeySpec(cipher);
      return decodePkcs8(spec.getEncoded(), file);
    } catch (IOException | GeneralSecurityException e) {
      if (e instanceof InvalidKeySpecException
          || e.getCause() instanceof GeneralSecurityException) {
        throw new ChordConfigurationException(
            "Cannot decrypt the private key in " + file + "; check the key password");
      }
      throw new ChordConfigurationException(
          "Cannot decrypt the private key in " + file + ": " + e.getMessage());
    }
  }

  private static byte[] readFile(Path file) {
    try {
      return Files.readAllBytes(file);
    } catch (IOException e) {
      throw new ChordConfigurationException("Cannot read " + file + ": " + e.getMessage());
    }
  }
}
