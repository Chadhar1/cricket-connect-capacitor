# CricketConnect Capacitor — Visual Identity

Everything here applies to **this Capacitor project only**. `legacy-app/`, the TWA
in `cricket-connect-android/`, the signed Play Store APK, the Vercel deployment and
Supabase are all untouched — the web app keeps its existing navy/green/gold look.

Status legend (same discipline as `CAPACITOR-FEATURE-PARITY.md`):
**NOT STARTED** / **IN PROGRESS** / **IMPLEMENTED — NOT BUILT** / **BUILT — NOT DEVICE TESTED** /
**DEVICE TESTED** / **PRODUCTION READY**.

Current overall status: **IMPLEMENTED — NOT BUILT**. Nothing below has been
compiled or seen on a phone yet.

---

## 1. Source of truth

`native-src/assets/brand/source/cricketconnect-logo-source.png` — the official logo
you supplied. Every colour and every generated asset derives from this one file.
It is a **build input, not a runtime asset**: `copy-web.mjs` deliberately excludes
the `source/` folder from `www/`, so the ~850KB original never ships inside the APK.

## 2. Palette — measured, not invented

Extracted by quantising the logo and taking the most-saturated pixels per hue family.

| Token | Hex | Where it came from in the logo |
|---|---|---|
| `--cc-bg-primary` | `#020C1B` | stadium sky (6.5% of image) |
| `--cc-bg-secondary` | `#041221` | dominant dark field (17.9%) |
| `--cc-surface` | `#0A1B31` | interpolated card surface |
| `--cc-surface-2` | `#122C52` | mid navy (10.7%) |
| `--cc-blue-deep` | `#133587` | the "C" gradient's blue end |
| `--cc-blue` | `#3D7BFF` | logo blue, lightened for contrast (see below) |
| `--cc-cyan` | `#59C3F8` | icon border glow |
| `--cc-green` | `#8DE789` | softer green in the "C" |
| `--cc-lime` | `#A4F851` | the CONNECT wordmark — signature accent |
| `--cc-gold` | `#EFB23E` | the ball's motion trail |
| `--cc-danger` | `#D42A22` | the cricket ball, deepened for contrast |
| `--cc-text` | `#F5F5F0` | the CRICKET wordmark (warm white, not `#FFF`) |
| `--cc-text-secondary` | `#A9B8C8` | cool grey |

**Signature gradient:** `#3D7BFF → #59C3F8 → #A4F851`, on primary buttons, the active
tab indicator, and role badges only. It is a hierarchy signal; spraying it everywhere
would destroy the thing it exists to do.

### Three deliberate departures from the brief

1. **No `#00D4FF` cyan, no `#39FF14` neon green.** The brief named those, but the logo
   contains almost no true cyan (1,840 vivid cyan pixels against 27,477 blue and
   13,280 green) and its green is a yellow-leaning lime. The brief also said the logo
   is the primary reference and must not be redesigned, so the logo won. `#39FF14`
   beside this logo reads as a different product.

2. **`--cc-blue` is `#3D7BFF`,** not the logo's own `#1B46ED` nor the brief's `#006FFF`.
   The logo blue is an indigo that goes muddy against near-black navy at button size.

3. **`--cc-danger` is `#D42A22`,** slightly deeper than the ball's `#E4322A`.

Departures 2 and 3 were driven by measured contrast, not taste — see next section.

## 3. Accessibility — computed, not eyeballed

WCAG 2.1 contrast ratios were calculated for every text/background pair in the theme.
Two initially failed AA for body-size text and were fixed by solving for the threshold:

| Pair | Before | After |
|---|---|---|
| Button ink on gradient blue | 4.20:1 ✗ | **4.93:1 ✓** (`#2E6BFF` → `#3D7BFF`) |
| White on danger red | 4.39:1 ✗ | **5.06:1 ✓** (`#E4322A` → `#D42A22`) |

All 14 pairs now meet 4.5:1. Best/worst: body text on page background 17.93:1,
button ink on gradient blue 4.93:1.

Also enforced, deliberately placed last in the stylesheet so they override the styling:

- **44px minimum touch target** on `.btn`, `.run-btn`, `.quick-btn`, `.action-row-btn`,
  and tab bar buttons. Scoring buttons hold a 56px minimum — they get pressed hundreds
  of times per innings, often one-handed in sunlight.
- **Visible focus rings** on every interactive element, not just `.btn`.
- **`prefers-reduced-motion`** honoured — the pulse and shimmer are decorative.

## 4. Typography — PARTIAL

Type scale, weights, and tabular-lining figures for scores are in place. The
`--cc-font` stack puts Poppins first.

**Poppins itself is not bundled.** The build environment has no network access to fetch
the webfont, and a Google Fonts `<link>` is not acceptable here — the app has to work
at a ground with no signal, which is a stated product requirement. It currently falls
back to Roboto on Android, which is a clean result, just not the specified one.

To finish it:

1. Download Poppins 400/500/600/700/800 `.woff2` from Google Fonts.
2. Put them in `native-src/assets/fonts/`.
3. Add `@font-face` blocks to the top of `native-src/theme-capacitor.css`
   with `src: url('./assets/fonts/poppins-600.woff2') format('woff2')`.
4. `npm run copy-web` — the assets folder is already copied wholesale.

## 5. Generated assets

`scripts/generate_brand_assets.py` (needs `pip install pillow`) regenerates everything
from the source logo. You do not need to run it — the outputs are committed. It exists
so a future logo revision is one command, not hand-editing 30 PNGs.

| Output | Count | Notes |
|---|---|---|
| `native-src/assets/brand/cc-mark-{512,192,96,64}.png` | 4 | rounded-square marks for the WebView |
| `native-src/assets/brand/cc-lockup.png` | 1 | icon + wordmark, **transparent background** |
| `res/mipmap-*/ic_launcher.png` | 5 | legacy square launcher icon |
| `res/mipmap-*/ic_launcher_round.png` | 5 | legacy round launcher icon |
| `res/mipmap-*/ic_launcher_foreground.png` | 5 | adaptive foreground |
| `res/values/ic_launcher_background.xml` | 1 | recoloured `#FFFFFF` → `#041221` |
| `res/drawable*/splash.png` | 11 | portrait + landscape, all densities |

Two details worth knowing:

- **Adaptive icon inset is 0.72, not the 0.62 "safe zone".** The art is a rounded
  *square*, so under a circular launcher mask only the four empty navy corners get
  clipped — the C, batsman and ball all sit inside the safe circle. At a strict 0.62
  the icon reads as a small stamp floating in padding.
- **The lockup's background is keyed out by luminance.** A plain crop of the wordmark
  carries the source's dark stadium photo with it and composites onto the splash as a
  visible rectangle. The wordmark is bright white and lime on near-black, so luminance
  separates glyph from backdrop almost perfectly.

## 6. How it reaches the app without touching legacy-app

`npm run copy-web` wipes and re-copies `www/` from `legacy-app/` every run, so nothing
edited directly in `www/` survives. Everything Capacitor-specific therefore lives in
`native-src/` and is layered on *after* the copy:

```
native-src/theme-capacitor.css  ->  www/  + <link> injected at end of <head>
native-src/brand-native.js      ->  www/  + <script> injected before </body>
native-src/assets/**            ->  www/assets/**   (source/ excluded)
```

The stylesheet link lands at the end of `<head>`, after `styles.css`, so its `:root`
block wins. This is the whole mechanism: `styles.css` is already variable-driven, so
redefining `--bg-*`, `--acc*`, `--ink*` and `--line*` re-skins ~1,300 lines of existing
CSS without rewriting a single component.

`brand-native.js` handles the one thing CSS cannot reach. The emblem is an inline SVG
built by `avatars.js` with hardcoded fills, painted into ~14 places. Rather than edit
that protected file, the script replaces the rendered mark in the DOM and sets
`dataset.painted = '1'` — the exact flag `paintBrandMarks()` checks — so app.js skips
those elements forever after. No flicker, no fighting, no app.js change.

## 7. Verified so far

Static checks only. **Nothing has been compiled or run.**

- ✅ CSS braces balanced (65/65); every `var()` resolves; all 42 targeted selectors
  exist in the real `styles.css` — no dead rules.
- ✅ `brand-native.js` passes `node -c`.
- ✅ `copy-web.mjs` dry-run against a fixture: CSS injected into `<head>`, scripts before
  `</body>`, assets copied, `source/` excluded, legacy-app fixture provably unmodified.
- ✅ Contrast computed for all 14 pairs, 0 failures.
- ✅ Generated icons and splashes inspected as contact sheets under circular, squircle
  and rounded launcher masks.

## 8. Not done

- **Poppins not bundled** (section 4).
- **Never built, never run, never seen on a phone.** No screenshot of the themed app
  exists — the build environment has no browser to render one.
- **Empty and error states** were not restyled. They inherit the new palette through
  the variable remap, but the brief asked for designed empty states with illustrations,
  and that needs app.js markup changes which are out of scope for a visual-layer pass.
- **Record Match** entry point inherits the theme; the native camera work is separate
  and unchanged. Native recording is still not complete — see
  `RECORD-MATCH-ARCHITECTURE.md`.
