# Security Policy

## Supported versions

The latest minor release line receives security fixes. Before 1.0.0 there are no long term
support lines: a fix ships as the next release on the current line.

| Version | Supported |
|---|---|
| 0.2.x | Yes |
| 0.1.x | No, upgrade to 0.2.x |

## Reporting a vulnerability

Report vulnerabilities privately, never in a public issue:

1. Preferred: [GitHub private vulnerability reporting](https://github.com/orhaugh/CHord/security/advisories/new).
2. Alternatively email orhaugh@gmail.com with subject `CHord security`.

You will receive an acknowledgement within 72 hours. Please include a reproduction where
possible. Coordinated disclosure is the default; a fix or a documented mitigation is targeted
within 90 days of triage.

## Design posture

CHord treats every byte received from a server as hostile: lengths are bounded before
allocation, unknown packets and encodings are explicit failures, decompression limits apply from
Phase 4, and Java native object deserialisation is never used. Credentials are refused over
plaintext transports without an explicit opt in, are held in wipeable buffers where practical,
and never appear in logs, `toString()` output or exception messages.
