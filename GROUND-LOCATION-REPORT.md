# Cricket Ground Location Foundation — Completion Report

Scope: GROUND 1–20 from the brief, exactly. Not built: embedded maps, Nearby
Grounds discovery, player location tracking, continuous GPS. Those are
explicitly future phases (see §11).

---

## 1. Architecture Before

A cricket ground did not exist as a thing — only as free text, typed fresh
at three separate input boxes (`evVenue` in "Schedule a match", `matchVenue`
in Match Setup, `fxVenue` in a tournament fixture's date/venue modal), plus a
fourth free-text `tGround` field on the tournament itself. The same pitch
could be "Model Town Ground", "model town", or "MT Ground" across different
matches, with nothing able to match, count, or map them.

No table anywhere had a latitude, longitude, or timezone column. `matches`
stored a `venue` string inside its JSONB `data` blob; `fixtures` had a real
`venue text` column, synced from `tournaments.data.fixtures[]` by a trigger;
`live_matches` had a `location text` column populated from the match's venue
at go-live time. "Cricket Near You" was a documented best-effort text match
of the signed-in user's own `region`/`district` against a tournament's
free-text `location` — not geodistance, and it said so in its own comment.

A real, previously invisible bug: match/fixture times are captured in the
scorer's device timezone and correctly stored as UTC, but `fmtWhen()`
rendered every date in the *viewer's* timezone with no way to know or show
otherwise. A 3pm match in Lahore would display as 10am to a viewer in
London, with nothing on screen indicating why.

## 2. Architecture After

`grounds` is now a first-class, reusable Postgres table: id, name (plus a
Postgres-generated `normalized_name` for search/dedupe — never client-
supplied, can't drift), latitude/longitude, address, city, region, country,
country_code, postal_code, timezone (IANA), place_id (reserved), image_url
(reserved, unused — no Storage bucket), created_by, verified, active,
created_at/updated_at.

Every existing venue text input gained a "📍 Select Ground" button next to
it — not instead of it. Picking a ground auto-fills the existing text field
with the ground's name (so all 15+ existing render call sites that already
print `.venue`/`.ground` keep working completely unchanged) and separately
attaches a `groundId`/`ground_id`. A match/fixture/tournament with no ground
picked behaves exactly as it did before this phase, byte for byte.

`tournaments` and `fixtures` each gained a real, nullable `ground_id`
column (the tournament's own default ground, and a per-fixture override,
mirroring exactly how `venue` itself already works at both levels).
`live_matches` gained the same, mirroring its existing `location` column.
Standalone/friendly `matches` gained **no schema change** — `groundId` lives
inside the existing JSONB `data`, read the same way `data.tournamentId`
already is elsewhere in `cloud.js`, kept fast via a new expression index.

A new in-SPA screen, Ground Detail (`go('ground')`), mirrors the existing
Tournament Detail screen's shape exactly: name, address, timezone, upcoming
fixtures (queried from `fixtures`, the one match-shaped table a signed-out
visitor can actually see anything from — see §8), tournament count, Get
Directions (hands off to Google Maps via a universal URL, no embedded map,
no SDK), and Share Ground (`navigator.share`, the same call already used
five times elsewhere in this app). Shareable via `index.html?ground=<id>`,
the same convention as `?tour=<id>`.

Admin gained a ninth dashboard tab, Grounds — view, search, verify,
deactivate, delete — using the exact same `adminTab`/`renderAdminX()`
pattern as every other admin section.

## 3. Database Changes

New file: `legacy-app/supabase_ground_location_migration.sql` (not yet run
against the live project — see §9). Every statement is `if not exists` /
`create or replace` / drop-then-create-policy, safe to run more than once,
same convention as every other migration file in this project.

**New table:** `grounds` (columns listed in §2), with:
- RLS enabled. Anyone (including signed-out) can read an active ground; a
  ground's own creator or an admin can also see it while inactive. Any
  signed-in user can create a ground, never pre-verified. The creator can
  edit their own ground's details but not `verified`/`active` — admin-only,
  same locked-field shape as the existing `profiles` update policy. No
  general delete policy — only admins; everyone else uses `active=false`.
- Indexes: `normalized_name`, `city`, `country_code`, and a partial index on
  `(latitude, longitude) where latitude is not null`.

**Altered tables (all additive, all nullable):**
- `tournaments` + `ground_id uuid references grounds(id) on delete set
  null`, indexed.
- `fixtures` + `ground_id uuid references grounds(id) on delete set null`,
  indexed. `sync_fixtures_from_tournament()` (the existing trigger that
  copies `venue` from the tournament's JSONB into this shadow table) was
  extended to copy `ground_id` the same way — a `create or replace
  function` with two new lines, everything else byte-identical to the
  version already running in production.
- `live_matches` + `ground_id uuid references grounds(id) on delete set
  null`, indexed.
- `matches` — no column added. A new expression index,
  `matches_ground_id_expr_idx` on `(data->>'groundId')`, keeps the JSONB
  path queryable.

No existing table was dropped, renamed, or had a column removed. No
existing RLS policy on `matches`, `teams`, `tournaments`, `events`,
`fixtures`, `live_matches`, or `profiles` was changed.

## 4. Files Changed

**New:**
- `legacy-app/supabase_ground_location_migration.sql` — schema, RLS,
  indexes, trigger extension (§3).
- `cricket-connect-capacitor/GROUND-ARCHITECTURE.md` — the pre-work
  architecture summary (GROUND 1–2 deliverable).
- `cricket-connect-capacitor/GROUND-LOCATION-REPORT.md` — this file.

**Modified:**
- `legacy-app/cloud.js` — new Grounds section (~230 lines): search, create,
  update, duplicate-detection, ground-detail queries, admin functions, plus
  one line added to the existing `tournamentColumns()`.
- `legacy-app/engine.js` — `createMatch()` gained an optional `groundId`
  parameter, additive, defaults to `null`. All 252 existing tests still
  pass unchanged (§9).
- `legacy-app/app.js` — ground picker (a self-contained modal state
  machine, same shape as the existing toss flow), Ground Detail screen,
  Admin Grounds tab, `fmtWhen()` timezone support, and the wiring at all
  four venue-entry points (schedule modal, match setup, fixture date
  modal, tournament creation).
- `legacy-app/index.html` — one new screen container (`#screen-ground`),
  one new admin tab pill + container, one new button+hint pair next to
  `matchVenue`.
- `cricket-connect-capacitor/android/app/src/main/AndroidManifest.xml` —
  two new permissions (`ACCESS_COARSE_LOCATION`, `ACCESS_FINE_LOCATION`)
  and two optional `uses-feature` declarations, with the same "verified
  against real Capacitor bridge source" comment style as the existing
  camera/microphone permissions.

**Untouched:** everything under `cricket-connect-capacitor/native-src/`,
every `android/app/src/main/java/.../overlay/*.java` file, `recorder.js`,
`overlays.js`, `broadcast-events.js`, `tournament.js`'s `createTournament()`
itself, and all of `cricket-connect-android/` (the TWA) — confirmed by git
status (§8/§10).

## 5. GPS

"Use My Current Location" calls the standard **web** Geolocation API
(`navigator.geolocation.getCurrentPosition`) directly from `legacy-app`'s
own code — not a Capacitor plugin. This was a deliberate finding, not a
shortcut: I read the actual installed `@capacitor/android` source
(`BridgeWebChromeClient.java`) and confirmed its
`onGeolocationPermissionsShowPrompt()` override already bridges the plain
web API to Android's native permission system, requesting
`ACCESS_COARSE_LOCATION` + `ACCESS_FINE_LOCATION` exactly the way the
microphone fix earlier in this project needed `MODIFY_AUDIO_SETTINGS`
declared. So the same code works identically on the website, in any mobile
browser, and inside the Capacitor app, with **zero new dependencies** —
`@capacitor/geolocation` was not added.

Flow: user taps "Use My Current Location" → `getCurrentPosition()` is
called (one-shot, `enableHighAccuracy:true`, 15s timeout, no caching) →
success shows a confirmation screen with the raw latitude/longitude and a
"View on map" link, requiring an explicit "Confirm & Continue" tap before
anything is attached to the ground being created → denial/timeout/
unavailable all show a specific, actionable message and leave every field
still manually editable. GPS coordinates are never geocoded into an
address automatically — no reverse-geocoding API is called, by design (see
§11); the user still types city/country themselves. This is what keeps the
feature at zero ongoing API cost.

No location is ever requested automatically, in the background, or
repeatedly — only on an explicit tap, exactly once per tap
(`getCurrentPosition`, never `watchPosition`), and `ACCESS_BACKGROUND_
LOCATION` was never added to the manifest.

## 6. Timezone

`fmtWhen(iso, tz)` gained an optional second parameter. Every existing call
site that doesn't pass one renders exactly as before — same output, byte
for byte (verified by reading the diff: the no-`tz` code path is the
original code, unchanged, just reached through one extra `if`). When a
caller does pass a ground's IANA timezone (currently: Ground Detail's own
upcoming-fixtures list, since that's the one place a resolved ground's
timezone and a list of times are both already in hand), the time renders
using `Intl`'s `timeZone` option against that zone, with a short zone
label (e.g. "PKT") appended **only** when it differs from the viewer's own
resolved timezone — so a scorer and viewer in the same city see no new
clutter, and a viewer somewhere else sees an unambiguous, correctly-labeled
time. The relative day label (Today/Tomorrow/Yesterday/weekday) stays
viewer-relative on purpose — that's a calendar question for the person
looking at the screen, not the venue.

This fixes the bug in §1 wherever a ground's timezone is known. It does
**not** retroactively fix every match/fixture card across the app to pass a
timezone — that would mean resolving a ground for every card render, a
larger change than this foundation phase's scope. What's shipped: the
mechanism is correct and used on Ground Detail; wiring it through the
remaining match/fixture card render functions is straightforward follow-up
work once grounds actually have timezones attached (which requires §9's
migration to have run and real grounds to exist).

## 7. Backward Compatibility

Every existing `venue`/`location`/`ground` free-text column is untouched
and remains what every existing screen renders. A match, fixture, or
tournament created before this phase has `groundId`/`ground_id` = null and
displays exactly as it always has — this was verified by reading, not
assumed: `resolveGroundLinkAtSave()` only ever attaches a ground id when
the visible venue text still matches what was selected, so even a retyped
venue field can't end up silently paired with the wrong ground. No
historical data was converted or guessed at; grounds are adopted match by
match, fixture by fixture, going forward, exactly as the brief specified
(§10 in the brief: no automatic text-to-ground migration).

## 8. Security

No Supabase service-role key or other credential was used or exposed — all
new `cloud.js` functions use the existing anon-key client (`sb`), same as
every other function in the file. RLS is the only enforcement boundary
(§3): public read of active grounds, authenticated create, creator-or-
admin update with `verified`/`active` locked out of the creator's own
write path, admin-only delete. No existing table's RLS policy was touched.

Ground Detail's "Upcoming Matches" deliberately reads `fixtures`, not
`matches` — `fixtures` already has a "public tournament, role holder, or
admin" read policy, so a signed-out visitor to a ground's public page sees
only fixtures belonging to *public* tournaments, exactly as intended.
Standalone friendly matches remain owner-only, unreachable from a ground's
public page, exactly as they always have been — nothing here creates a new
way to see someone else's private match.

Grounds search sanitizes user input before building a PostgREST `.or()`
filter string (stripping `,()%`, the three characters with syntactic
meaning in that grammar) — search is reachable by signed-out visitors, so
this closes the one place untrusted text reaches a raw filter string
rather than a parameterized value.

## 9. Testing

Nothing in this environment can reach the live Supabase project or a real
browser/device — no database connection, no Android SDK (confirmed
unavailable earlier this session), no root access to install one (apt and
npm registry access are both blocked outside an allowlist). Every result
below is the strongest verification actually possible here, stated
honestly rather than assumed:

| # | Test | Result |
|---|---|---|
| 1 | Existing match with free-text venue still works | **PASS (static)** — zero changes to any existing render path; `groundId` is purely additive on the data shape. |
| 2 | Create new ground manually | **BUILT — NOT LIVE-DB TESTED**. Code path verified by reading (`createGround` → RLS insert policy), migration not yet run against the live project (§3). |
| 3 | GPS ground creation | **BUILT — NOT DEVICE TESTED**. Permission bridge mechanism confirmed against real installed Capacitor source (§5), not exercised on a device. |
| 4 | Permission denied → manual location still available | **PASS (static)** — every location error path (`geolocationErrorMessage`) leaves all form fields enabled; there is no code path that blocks manual entry. |
| 5 | Search existing ground instead of duplicating | **BUILT — NOT LIVE-DB TESTED**. `searchGrounds()` logic verified by reading; needs a live table with rows. |
| 6 | Similar names, different coordinates, not merged | **PASS (static)** — `findPossibleDuplicateGrounds()` only ever returns candidates for the human to choose from; nothing in this codebase auto-merges. |
| 7 | International ground (correct country/timezone) | **BUILT — NOT LIVE-DB TESTED**. No country-specific logic anywhere in the new code — every field is free text or a passed-through IANA string. |
| 8 | Match timezone displays correctly across zones | **BUILT — NOT LIVE-DB TESTED**. `fmtWhen(iso, tz)` logic hand-verified against `Intl` semantics (§6); no live ground with a timezone exists yet to render against. |
| 9 | Get Directions opens external maps with correct coordinates | **BUILT — NOT DEVICE TESTED**. URL construction verified by reading; opening a `window.open` URL needs a real browser/WebView. |
| 10 | Offline / poor connection graceful behaviour | **PASS (static)** — every new fetch function already returns `[]`/`null`/`0` and logs rather than throws into the UI (matches this file's existing error-handling convention throughout). |
| 11 | Security — users can't modify another's ground improperly | **PASS (static)** — RLS policy read line by line against the exact same pattern already proven in `profiles`' own update policy (§3, §8). |
| 12 | Regression — auth, profiles, teams, tournaments, match creation, live scoring, recording, camera, overlay, highlights unaffected | **PASS** — see §10 and the file list in §4; `node --check` on every touched `.js` file and the full 252-test `run-tests.mjs` suite both pass (ran directly, not assumed — see below). |

Commands actually run in this session, with real output:
- `node run-tests.mjs` inside `legacy-app/`: **252 passed, 0 failed** (run
  twice — once after the `engine.js` change, once after all `app.js`
  changes).
- `node --check` on `app.js`, `cloud.js`, `engine.js`: all clean, three
  times across the session as changes landed.
- A Python `html.parser`-based tag-balance check on the full `index.html`:
  **0 errors**, 0 unclosed tags.
- `xmllint --noout` on the edited `AndroidManifest.xml`: well-formed.
- A hand-written tokenizing balance checker (parens/string-literals/
  dollar-quoted blocks) on the full SQL migration: balanced, 0 errors.
- `git status --short` in `legacy-app/`: exactly the 4 files this report
  lists as modified, plus the 1 new migration file — nothing unexpected.

## 10. Recording Safety

Explicitly confirmed: **no file under `cricket-connect-capacitor/native-
src/`, no Java file under `android/app/src/main/java/.../overlay/`, and
none of `recorder.js`/`overlays.js`/`broadcast-events.js` was opened for
writing at any point in this phase.** The only Capacitor-side change is two
new `<uses-permission>` lines and two optional `<uses-feature>` lines in
`AndroidManifest.xml`, additive to the existing camera/microphone
declarations already there — nothing was removed or reordered. The native
camera, MP4 recording, MediaCodec/OpenGL compositor, and highlight
groundwork from the prior phase are exactly as they were left, still
awaiting the device-test the user said they'd report back on.

## 11. Future Recommendation

Do not build these yet — this is a recommendation, not a next action:

1. **Run the migration, then device/browser-test the twelve scenarios
   above for real** — this is the one honest gap this report can't close
   from inside a sandbox with no database connection.
2. **Wire ground-aware `fmtWhen()` calls into the remaining match/fixture
   cards** once real grounds with timezones exist to test against (§6).
3. **Nearby Grounds**, using `fetchGroundsNear()` — already written and
   ready in `cloud.js`, deliberately unused by any screen this phase.
4. **Embedded map on Ground Detail**, once usage data justifies picking a
   provider and taking on its cost/weight (§4 of `LOCATION-ROADMAP.md`).
5. **Reverse geocoding on ground creation** (auto-fill city/country/
   timezone from tapped coordinates) — deliberately not built this phase
   to keep the feature at zero ongoing API cost; worth it once there's
   real usage to justify a provider bill.
6. **Cross-link Ground Detail from the tournament screen** (a "View
   Ground" affordance where `tournament.groundId` is set) — small, safe,
   just not done this pass; the screen and its `?ground=` deep link already
   work standalone via the picker and share flows.
