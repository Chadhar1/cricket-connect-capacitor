# Ground Location Foundation — Architecture Summary (before modifying anything)

Read directly from the real source (`legacy-app/supabase.sql`, `cloud.js`, `app.js`,
`tournament.js`, `engine.js`, plus the Capacitor project) on 2026-08-23, before any
change in this phase. This is GROUND 1-2 of the implementation order.

## 1. How venues work today

Free text, in three separate places, no relationship between them:

| Screen | Field id | Where it ends up |
|---|---|---|
| Schedule a match (`openScheduleModal`) | `evVenue` | `events` table, `data.venue` (JSONB) |
| Match Setup (`index.html` static form) | `matchVenue` | `matches` table, `data.venue` (JSONB), via `createMatch()` in `engine.js` |
| Tournament fixture date/venue (`openFixtureDateModal`) | `fxVenue` | `tournaments.data.fixtures[].venue`, shadow-copied to `fixtures.venue` (real column) by a DB trigger |

`tournaments` additionally has its own free-text `location` and `ground` columns
(promoted out of `data` in an earlier migration — a tournament's overall area and
its default/primary ground name). `live_matches` has a free-text `location` column,
populated from `match.venue` when a match goes live, and is what "Live Now" /
"Cricket Near You" currently read.

No table, anywhere, has latitude, longitude, or a timezone identifier. `profiles`
has free-text `country/region/district/area` (a player's own home location, added
for "Cricket Near You" — unrelated to match venues). "Cricket Near You" is
documented in its own code comment as a best-effort text match on the profile's
region/district against a tournament's `location` string — not geodistance.

## 2. The relevant save paths (verified in cloud.js)

- `matches`, `teams`, `tournaments`, `events` are created by one shared loop in
  `supabase.sql` (`id text pk, user_id, data jsonb, created_at, updated_at`), RLS:
  owner has full read/write, admin read-only across all rows.
- `saveRowIn(table, obj, extra={})` is the generic write helper: upserts
  `{id, user_id, data: clean(obj), updated_at, ...extra}`. `matches`/`events` call
  it with no `extra` — zero promoted columns today. `tournaments` calls it with
  `tournamentColumns(t)` as `extra` — this is the existing, proven pattern for
  giving a JSONB-backed row queryable real columns without changing what the app
  reads/writes as its source of truth.
- `fixtures` is a read-only shadow table, kept in sync from
  `tournaments.data.fixtures[]`/`data.knockout[]` by a trigger
  (`sync_fixtures_from_tournament()`) on every tournament insert/update. Nothing
  ever writes to it directly.
- `clean()` recursively drops `undefined` — safe to add a new key to any match/
  event/fixture object without special-casing it.

## 3. UI plumbing that matters for this phase

- `openModal(html)` / `closeModal()` — trivial innerHTML swap into `#modalRoot`,
  used for every existing form (schedule match, fixture date/venue, etc.). The
  ground picker reuses this, not a new modal system.
- `fmtWhen(iso)` (app.js) formats every date/time shown in the app. It calls
  `toLocaleTimeString()`/`toLocaleDateString()` with no timezone argument — it
  renders in the **viewer's** device timezone, always. Since match start time is
  stored correctly as UTC ISO, this is invisible domestically and wrong the moment
  scorer and viewer are in different zones. This is the bug GROUND 15 fixes.
- Tournament detail is an in-SPA screen, not a standalone page: `go('tournament')`
  + module-level `openTourId`/`viewedTournamentPublic` + `renderTournament()`,
  wired into the main `case 'tournament': renderTournament(); break;` dispatcher.
  Shareable via `index.html?tour=<id>`, read in `boot()` and opened for guests too.
  Ground Detail follows this exact pattern (`go('ground')`, not a new static page)
  — public player profile's separate `player.html` was a different, deliberate
  choice for a different problem (pre-render-friendly public card) and isn't the
  right template here.
- `navigator.share(...)` with a `navigator.clipboard.writeText()` fallback is
  already used five times (recording, result, player profile, live match,
  tournament). "Share Ground" reuses this exact call shape, not new infrastructure.
- Admin dashboard is sectioned tabs (`adminTab`: overview/tournaments/matches/
  organisers/users/feedback/notifications/activity), each with its own
  `renderAdminX()`. Grounds gets a ninth tab, same pattern.

## 4. Capacitor / native findings

- `legacy-app/app.js` has zero Capacitor awareness — all native glue lives in
  `cricket-connect-capacitor/native-src/`, layered on top of the copied web app
  after every build, per `copy-web.mjs`. Consistent with that boundary, this phase
  keeps ground/location code in `legacy-app` (so it works on the website too, not
  just the Android app) and touches `native-src`/the Android project only where a
  real native permission gate is involved.
- Verified against the actual installed `@capacitor/android` source
  (`BridgeWebChromeClient.java`): `onGeolocationPermissionsShowPrompt()` requests
  `ACCESS_COARSE_LOCATION` + `ACCESS_FINE_LOCATION` as a batch, the same
  request-then-callback shape already found and fixed for the microphone
  (`MODIFY_AUDIO_SETTINGS`) bug. An undeclared permission is auto-denied with no
  dialog. **This means plain `navigator.geolocation.getCurrentPosition()` — the
  standard web API, already usable in every browser and inside the Capacitor
  WebView — works with zero new Capacitor plugin**, as long as both permissions
  are declared in `cricket-connect-capacitor/android/app/src/main/AndroidManifest.xml`.
  No `@capacitor/geolocation` dependency is needed. This is a smaller footprint
  than what `LOCATION-ROADMAP.md` first sketched, now that the real bridge
  behaviour has been confirmed rather than assumed.
- The TWA (`cricket-connect-android/`) is not touched by this phase. Its manifest
  is separate and unrelated. Its own git status is recorded before this phase
  starts, for the regression check at the end.
- `capacitor.config.json` has no `Browser` plugin and no navigation allowlist.
  `window.open(url, '_blank', 'noopener')` on an external `https:` URL is handled
  by Capacitor's default WebView behaviour (opens the system browser / an
  installed app via App Links) — confirmed by the absence of any override, so
  Directions needs no new dependency either.

## 5. Decision this drives (GROUND 3)

- New `grounds` table (the only new table this phase adds).
- `tournaments.ground_id` and `fixtures.ground_id` — real nullable columns,
  extending the exact promoted-column pattern already in use for `venue`/
  `tournamentColumns()`. `fixtures.ground_id` is synced from JSONB by extending
  the existing trigger, the same way `venue` already is.
- `live_matches.ground_id` — real nullable column, populated the same way
  `location` already is, for future proximity-based "Live Now".
  ` matches.groundId` (standalone/friendly matches) — **JSONB only**, inside
  `data`, no schema change — mirrors how `tournamentId` is already queried today
  (`.eq('data->>tournamentId', tournamentId)` in `fetchTournamentMatches`).
  `matches` has zero promoted columns today and adding its first one would mean
  changing the shared `saveRowIn`/`saveMatchToCloud` contract for marginal gain;
  an expression index on `(data->>'groundId')` gets the query performance without
  that risk.
- Every existing venue text input gains a "Select Ground" affordance next to it,
  not instead of it. Choosing a ground auto-fills the existing text input with the
  ground's name (so all 15+ existing render call sites that already print
  `m.venue`/`f.venue` keep working, unchanged) and separately stores `groundId`/
  `ground_id`. An old match/fixture with no ground stays exactly as it renders
  today.
