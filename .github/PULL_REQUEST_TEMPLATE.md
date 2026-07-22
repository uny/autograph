<!-- A short title in the imperative mood; the body explains the *why*, not just the *what*. -->

## What & why

<!-- What this changes and the problem it solves. Link the issue: "Closes #NNN". -->

## Checklist

- [ ] `./gradlew build` passes (all targets)
- [ ] Tests cover the change, and I confirmed they fail without it (revert the fix → red)
- [ ] Public API change? If an `api/` dump changed, I read [ADR 0001](docs/adr/0001-public-api-evolution.md)
      and the diff is explained above. (Not every module is dump-covered — ADR 0001 §1.)
- [ ] Capture/coverage boundary touched? The README capture matrix and any relevant docs still match.
- [ ] No PII added to logged event payloads or diagnostics.
