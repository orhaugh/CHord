# ADR-0012: TLS configuration and material

Status: Accepted (2026-07-21)

## Context

The native protocol carries credentials verbatim, so TLS is the production transport. TLS APIs
are where drivers accumulate unsafe conveniences: trust-everything switches, hostname
verification toggles, half supported key formats. CHord needs full TLS and mutual TLS without
those traps and without new runtime dependencies.

## Decision

- `TlsTransport` layers JSSE `SSLSocket` over the same blocking dial and deadlines as the plain
  transport. Hostname verification runs inside the handshake via the endpoint identification
  algorithm and **has no off switch**; SNI is sent for hostnames, and IP connections verify
  against IP subject alternative names.
- There is **no trust-all option anywhere**, including test utilities: tests trust the specific
  generated CA through the production API, so nothing unsafe exists to enable accidentally.
- Trust material comes from exactly one source: system default, JKS or PKCS#12 store, PEM CA
  bundle, or a fully caller managed `SSLContext` (which excludes all other material options).
  Client key material for mutual TLS comes from a key store or a PEM certificate plus PKCS#8 key.
- PEM parsing uses JDK APIs only: `CertificateFactory` for certificates, `PKCS8EncodedKeySpec`
  and `EncryptedPrivateKeyInfo` for keys. Traditional OpenSSL formats ({@code BEGIN RSA PRIVATE
  KEY}) are rejected with a conversion hint rather than half supported.
- Handshake failures are diagnosed before being thrown: expired or not yet valid certificates,
  hostname mismatches, missing trust and rejected client certificates each get an actionable
  message, and a server certificate within thirty days of expiry logs a warning after successful
  handshakes.
- BouncyCastle exists only in `chord-testkit`, generating throwaway certificates at test run
  time; no certificate or key is ever committed and runtime modules gain no dependency.

## Alternatives

- A hostname verification toggle for certificates without correct SANs: rejected; the fix is a
  correct certificate, and the toggle becomes permanent in production configs.
- BouncyCastle in the runtime for PEM: rejected; JDK APIs cover the supported formats and the
  dependency surface stays zero.
- Conscrypt or native TLS engines for performance: out of scope until benchmarks justify it;
  the SPI leaves room.

## Consequences

- Users with SAN-less or IP-only certificates must fix their certificates; the error message
  says so explicitly.
- Encrypted PKCS#8 support depends on JDK PBES2 support, which the test suite proves against
  material generated with current OpenSSL-compatible parameters.
- When TLS connects to a plaintext port (or plain TCP to a TLS port) the failure surfaces as a
  handshake or protocol error; troubleshooting documents both misconfigurations.
