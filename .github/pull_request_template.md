<!-- Keep the "why" in the body, not just the "what". -->

## What & why

<!-- What does this change do, and why is it needed? -->

## Type

- [ ] feat
- [ ] fix
- [ ] perf
- [ ] refactor
- [ ] test
- [ ] chore / docs

## Validation (dev flavor)

- [ ] `./gradlew :app:compileDevDebugKotlin`
- [ ] `./gradlew :app:testDevDebugUnitTest`
- [ ] `./gradlew :app:lintDevDebug`
- [ ] Tests added/updated (happy path **and** failure modes)

## Animation / hardware / security notes

- [ ] Dispense animation guardrails respected (no chrome; white-center bg; reduced-motion)
- [ ] `VendingAnimationLayoutTest` still green (if the dispense surface changed)
- [ ] mTLS preserved; no plaintext device calls
- [ ] No secrets / keystores / google-services committed
