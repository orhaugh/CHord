# Changelog

All notable changes to CHord are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project follows semantic
versioning once 1.0.0 is released; before that, any 0.x release may change the API.

## [Unreleased]

### Added

- Maven multi module build (`chord-bom`, `chord-protocol`, `chord-codec`, `chord-transport`,
  `chord-client`, `chord-observability`, `chord-jdbc`, `chord-testkit`, `chord-examples`,
  `chord-benchmarks`) with reproducible builds, Spotless, Checkstyle, SpotBugs, JaCoCo, licence
  header checks and Maven Central publishing configuration.
- `chord-protocol`: wire primitives for the ClickHouse native protocol (VarUInt and zigzag VarInt
  over the full unsigned 64 bit range, little endian fixed width integers, length prefixed
  strings) with hostile input limits; the protocol revision registry verified against ClickHouse
  master revision 54488; client and server packet type registries; ClientHello, ServerHello and
  client addendum codecs covering every current handshake field including the server settings
  block, chunked capability negotiation and the interserver nonce; Exception and Progress packet
  decoding; the connection state machine; the CHord exception hierarchy.
- `chord-transport`: blocking TCP transport with connect and read deadlines behind a transport
  SPI that models transport security for credential policy decisions.
- `chord-client`: `NativeConnection` performing handshake, authentication, addendum and repeated
  Ping and Pong, refusing password authentication over plaintext transports without an explicit
  opt in, and never reusing a connection after a protocol violation.
- `chord-testkit`: Testcontainers ClickHouse fixture exposing the native port with deterministic
  credentials and image selection for the compatibility matrix.
- Integration tests exercising the handshake, repeated ping, authentication failure and unknown
  database paths against ClickHouse 25.8, 26.3 and 26.6.
- JMH benchmark scaffold for the VarUInt codec.
- GitHub Actions: build matrix on Java 21, 23 and 25, integration tests, nightly ClickHouse
  compatibility sweep, CodeQL and a Maven Central release workflow with a dry run mode.
