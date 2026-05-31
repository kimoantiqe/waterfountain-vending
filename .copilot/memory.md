# waterfountain-vending — agent memory

## Animation vision (Phase A: 3-phase build → advertiser hold → drop) — DO NOT FORGET

The dispense animation is a ~26s AV piece in three discrete phases. The
design is grounded in peak-end rule (Kahneman), mere exposure, and
crescendo anticipation. **NO sponsorship chip. NO "POWERED BY". NO pip
row. NO post-vend billboard.** The advertiser's logo + message IS the
brand surface; everything else is supporting structure.

### Phases

**Phase 1 — Build anticipation (0–7s)**
- t=0–1s: fade in the WF logo (disc), `progressText` ("Your water is on
  the way!"), bottom pickup hint (`statusText` + `reminderIcon`).
- t=1s: `loading.mp3` starts; `ripplePond.startCadence()` fires.
- t=1–6.5s: three building ripples (small → medium → large). The
  crescendo creates anticipation; intervals tighten as energy builds.
- t=7s: **CREST** — `ripplePond.crest()` mega-ripple + `fireworks.mp3`
  drop. This is the visual peak AND the brand-handoff moment.

**Phase 2 — Advertiser hold (7–15s, ~8s)**
- At the crest the WF logo dissolves into the advertiser logo. If no
  advertiser is attached, the disc stays WF — both paths are valid.
- `centerMessage` fades in BELOW the disc with the advertiser's
  `animationMessage` (or the default WF tagline if no message was
  supplied).
- Surface stays quiet during this window — no new ripples, no chip, no
  pips. The advertiser owns visual attention for ~8s of clean exposure.

**Phase 3 — Drop celebration (15–26s)**
- t=15s: confetti bursts (synced to the typical can-drop moment;
  timing-is-always-the-same assumption per product). `progressText`
  crossfades to `completionText` ("Your water is ready!").
- t=15–17s: confetti + completion text overlap with the advertiser disc
  + message. Peak-end stacking — the moment of physical reward is the
  moment of strongest brand association.
- t=17s onward: `pickupReminderPanel` appears with QR/pickup info.
- t=~26s: return to main.

### Background reality
- The activity surface uses `@drawable/background2` — a RADIAL gradient
  with **white center, purple/blue edges**. Pick colors that read on
  WHITE in the center AND purple at edges. Ripples are dark teal by
  default (`#FF0B6E8C`); brand accent is clamped into L* [30, 55] by
  `RipplePondView.clampForRadialBg`.

### z-order
1. `ripplePond` (match_parent, behind everything)
2. Main `LinearLayout`:
   - `progressText` (top — swaps to `completionText` at drop)
   - `ringContainer` → `logoImage` (disc — WF then advertiser)
   - `centerMessage` (under disc — formerly `taglinePreReveal`)
3. Bottom `LinearLayout`: `statusText` + `reminderIcon` (pickup hint)
4. `konfettiView` (overlay during Phase 3)
5. `pickupReminderPanel` (overlay during Phase 3 tail)

### Defaults
- No advertiser logo → disc stays WF the whole time. No handoff visual,
  but the crest mega-ripple still fires.
- No `animationMessage` → `centerMessage` shows `message_default`
  ("Stay hydrated. Stay you.").

### Hardware sync
- The dispense is event-driven via `VendingUiState.DispensingComplete`,
  but in practice timing is consistent enough that confetti fires on a
  fixed t=15s offset. If timing drifts, wire confetti to the
  `DispensingComplete` event instead — the hook is already in
  `handleVendingState()`.

### Reduced-motion
`RipplePondView` checks `Settings.Global.ANIMATOR_DURATION_SCALE == 0f`.
When on, `startCadence()` is a no-op and `crest()` does a single short
flash. Activity-level animations should also respect this gate.

### Things I keep getting wrong (lessons)
- Designing for a dark background. The bg is WHITE in the center.
  Re-check contrast before changing any color constant.
- Adding chrome (chips, pips, billboards) that competes with the
  advertiser surface. The brand IS the message + the logo. Nothing else
  belongs in Phase 2.
- Sizing the logo by its `layout_width` literal without realizing the
  screen is 10" landscape and dp ≈ px. Target is ~540dp for the disc.
- Forgetting that backend data (advertiserName / animationMessage)
  arrives mid-vend. Default content must be present at activity start.
- Tests inflate the layout but don't run the full activity, so visual
  bugs (color, size, z-order) are only caught by manually previewing.

### Build / test
- `./gradlew :app:compileDevDebugKotlin` — fast Kotlin sanity check.
- `./gradlew :app:testDevDebugUnitTest --tests
  "com.waterfountainmachine.app.features.vending.ui.VendingAnimationLayoutTest"`
  — locks in XML invariants for the dispense surface.
- Full unit suite: `./gradlew :app:testDevDebugUnitTest`.
- Flavor matters: dev / prod have separate build configs; default to
  `dev` variant for local validation.
