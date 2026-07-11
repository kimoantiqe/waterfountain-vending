---
applyTo: "app/src/main/**/vending/**,app/src/main/res/**"
---

# Dispense animation guardrails (do not regress)

The ~26s dispense animation is a three-phase AV piece: build anticipation →
advertiser hold → drop celebration. The advertiser's **logo + message IS the
brand surface**.

- **No chrome that competes with the advertiser:** no sponsorship chip, no
  "POWERED BY", no pip row, no post-vend billboard.
- **Background is a radial gradient — WHITE center, purple/blue edges.** Choose
  colors that read on WHITE in the center; ripple/brand accent colors are
  clamped for contrast (`RipplePondView.clampForRadialBg`). Re-check contrast
  before changing any color constant.
- **Reduced motion:** honor `Settings.Global.ANIMATOR_DURATION_SCALE == 0`.
- **Defaults present at start:** the WF disc + default tagline must render at
  activity start because advertiser data (logo/message) arrives mid-vend.
- Unit tests inflate the layout but don't run the full activity, so visual bugs
  (color/size/z-order) are only caught by manual preview — re-check manually.
  Keep `VendingAnimationLayoutTest` green for XML invariants.
