## Summary

<!-- 1–3 bullets. What changed and why. -->

## Test plan

- [ ] `./gradlew test` passes
- [ ] `./gradlew verifyPlugin` passes (or CI confirms)
- [ ] Manual verification:
  - [ ] Local debug with a CUSTOM dlv path works
  - [ ] Run-on-Target SSH debug honors the override (if touched)
  - [ ] Switching tiers (BUNDLED / GLOBAL / CUSTOM) per-config behaves as expected

## Notes for reviewer

<!-- Anything non-obvious: a JetBrains-API quirk, a backward-compat decision,
     a follow-up plan deferred. Skip if not needed. -->
