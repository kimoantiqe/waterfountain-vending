# WaterFountain Vending — Copilot instructions

Trust these instructions first. Only search the codebase if something here is
missing or proves wrong.

## What this repo is

The **Android app (Kotlin)** that runs on the physical WaterFountain vending
machine. It drives the touchscreen UI + dispense animation, talks to the
backend over **mTLS** (client certificate), and controls the hardware via
`SerialPortUtils-release.aar`. Firebase (Crashlytics), Hilt DI (kapt).

- Build system: **Gradle** (wrapper **8.7**), **JDK 17**, AGP **8.5.2**,
  `compileSdk 34`, `minSdk 26`. Product flavors: **dev** and **prod**.
- Source: `app/src/main` (Kotlin + Android resources). Unit tests:
  `app/src/test`. Config: `app/build.gradle.kts`, `gradle/libs.versions.toml`,
  `build.gradle.kts`, `settings.gradle.kts`.

## Build & validate (run in this order)

Always default to the **dev** flavor for local validation.

```bash
chmod +x ./gradlew                            # first time only
./gradlew :app:compileDevDebugKotlin          # fast Kotlin sanity check
./gradlew :app:testDevDebugUnitTest           # unit tests
./gradlew :app:lintDevDebug                   # Android lint (advisory)
./gradlew :app:assembleDevDebug               # build the APK
```

- Use `--console=plain` in CI/agent contexts.
- CI can override version metadata with `-PciVersionName=… -PciVersionCode=…`;
  local builds fall back to the values in `app/build.gradle.kts`.
- Flavor matters: dev and prod have separate build configs. Don't validate on
  prod unless the change is prod-specific.

## Deploy / release model

- Push/merge to **`main`** → CI builds the **dev** debug APK and publishes a
  dev pre-release (artifact + GitHub release). Merge to **`release`** → prod.
- "Deploy" here means producing the signed APK artifact, not hosting. Do not
  hand-build release APKs for distribution; let CI do it.

## The dispense animation (design guardrails — do not regress)

The ~26s dispense animation is a three-phase AV piece (build → advertiser hold
→ drop). The **advertiser's logo + message IS the brand surface**:

- **No** sponsorship chip, "POWERED BY", pip row, or post-vend billboard.
- The activity background is a **radial gradient: WHITE center, purple/blue
  edges** — design colors that read on WHITE in the center. Ripple/brand colors
  are clamped for contrast; re-check contrast before changing color constants.
- Respect reduced motion (`ANIMATOR_DURATION_SCALE == 0`).
- Default content (WF disc, default tagline) must be present at activity start,
  because advertiser data arrives mid-vend.
- `VendingAnimationLayoutTest` locks XML invariants for the dispense surface;
  keep it green.

## Security ground rules

- **mTLS for the backend.** The app authenticates with a client certificate;
  never bypass it or add plaintext device calls.
- **No secrets in the repo.** Keystores, signing configs, service-account /
  `google-services` credentials come from CI secrets or gitignored files.

## Conventions

- Kotlin, Hilt for DI, `ViewBinding`/resources for UI. DRY, explicit over
  clever, engineered-enough.
- **Tests in the same change.** Layout/behavior changes ship with a
  `:app:testDevDebugUnitTest` update; visual details (color/size/z-order) aren't
  caught by unit tests, so re-check them manually when touching the animation.
- Conventional-commit messages (`feat:`, `fix:`, `perf:`, `refactor:`, `test:`,
  `chore:`, `docs:`); the "why" in the body.
