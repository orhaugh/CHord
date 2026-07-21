## Summary

Explain what this change does and why.

## Checklist

- [ ] Commit messages follow Conventional Commits (for example `feat(protocol): ...`)
- [ ] `./mvnw verify` passes locally
- [ ] New behaviour is covered by unit tests; protocol changes have byte level golden tests
- [ ] Protocol or type behaviour changes are verified against the ClickHouse sources and a real
      server (`./mvnw -Pintegration-tests verify`)
- [ ] `docs/protocol-compatibility.md` and `docs/type-support.md` still tell the truth
- [ ] `CHANGELOG.md` has an entry under Unreleased when the change is user visible
- [ ] No support is claimed for anything that lacks automated tests
