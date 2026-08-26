# CricketConnect — Ground Locations & International Support

**Status: PROPOSAL — NOTHING BUILT.** No code written, no schema changed, no
dependency added. This is for review.

---

## 1. Where you actually are today

I read the current code rather than assume. Four findings shape everything below.

**There is no such thing as a "ground".** A venue is free text typed per event:

```html
<input type="text" id="evVenue" placeholder="Ground name" maxlength="40">
```

So the same pitch becomes *"Model Town Ground"*, *"model town"*, *"MT Ground"*
and *"Model Twn"* across four fixtures. Nothing can match, map, or count them.
This is the root problem — not the missing map.

**No coordinates exist anywhere.** Not on matches, fixtures, tournaments or
profiles. There is no latitude or longitude column in the entire schema.

**"Cricket Near You" is a text match, not proximity.** The code already admits it:

> `/* "Cricket Near You" — best-effort text match, not real geodistance … */`

It compares region/district strings. It cannot work across countries, spellings,
or scripts.

**The location model is a fixed 4-level hierarchy:** `country, region, district,
area` on profiles. That shape happens to fit some countries and not others —
the UK has county/town, the UAE has emirates, the US has state/county/city.
It is stored as free text, so it is really four labels, not a hierarchy.

### A real bug that international users would expose

Fixture times are captured in the scorer's device timezone and stored as UTC:

```js
date: new Date(date + 'T' + time).toISOString()
```

then rendered in the *viewer's* timezone via `toLocaleTimeString()`. Domestically
nobody notices. The moment one user is abroad, a match set for 3:00pm in Lahore
displays as 10:00am in London — and there is no timezone field anywhere to fix it
with. **Going international turns this from invisible to a support problem.**
It should be fixed as part of this work, not after.

---

## 2. The core decision: make `grounds` a real entity

Everything else follows from this one change. A ground becomes a row that is
created once and referenced, instead of a string retyped every time.

```
grounds
  id                uuid
  name              text            -- as entered
  name_local        text            -- local script, optional
  latitude          double precision
  longitude         double precision
  country_code      char(2)         -- ISO 3166-1 alpha-2
  admin1            text            -- state / province / county  (provider-supplied)
  admin2            text            -- district / metro area
  locality          text            -- city / town
  formatted_address text            -- provider's own formatting, for display
  timezone          text            -- IANA, e.g. "Asia/Karachi"
  place_id          text            -- provider reference, for dedupe/refresh
  created_by        uuid
  verified          boolean
  created_at        timestamptz
```

Then `matches`, `fixtures` and `tournaments` gain a **nullable** `ground_id`.

**Keep the existing free-text `venue` column.** Nullable FK plus retained text
means nothing breaks on day one, old data still displays, and grounds can be
adopted gradually. A migration that forces every existing fixture to resolve to a
ground would be a bad trade.

### Why generic admin levels instead of country/region/district/area

Do not model the world's administrative divisions yourself. Store whatever the
geocoder returns in generic `admin1`/`admin2`/`locality` slots and display
`formatted_address`. Every provider returns these; every country maps onto them
approximately; and you never have to decide whether Punjab is a "region" or a
"state".

---

## 3. Privacy and the Play Store — read this before the technical parts

Location is the most heavily scrutinised permission on Google Play. Getting this
wrong blocks releases.

**The most important distinction, and I want to confirm I have it right:** you
asked for *ground* location shared with other players — a fixed pitch everyone
can find. That is a **place**, not a person, and it is a comparatively easy
privacy story.

If the requirement ever becomes *"see where other players are"*, that is an
entirely different product with a much heavier obligation. This roadmap
deliberately does **not** do that.

Given ground-location-only:

- **Never persist a user's coordinates.** Device location is used transiently to
  sort a list by distance, then discarded. Nothing about the user's position goes
  in the database. This alone keeps the Data Safety declaration simple.
- **Foreground only.** Request `ACCESS_COARSE_LOCATION` and, only when genuinely
  needed, `ACCESS_FINE_LOCATION`. Never `ACCESS_BACKGROUND_LOCATION` — it triggers
  a separate Play review and you have no use for it.
- **Prominent disclosure before the OS dialog.** Google requires an in-app
  explanation *first*, saying what location is used for, before the system
  permission prompt appears. This is a hard policy requirement, not a nicety.
- **Location must be optional.** Everything has to work with permission denied —
  fall back to manual city/country selection. A user who declines should lose
  sorting, not the feature.
- **Moderate user-submitted pins.** People will drop a ground marker on a private
  house, by accident or otherwise. You already have an admin dashboard; grounds
  need a `verified` flag and a review queue.

---

## 4. Provider and cost — and why v1 should render no map at all

Google's pricing changed materially in March 2025: the $200 monthly credit was
replaced with per-SKU free tiers. Map loads dropped to ~28,500/month free, and
geocoding sits around 10,000 requests/month free at roughly $5 per 1,000
after. Mapbox is far cheaper for geocoding (~$0.75/1,000) with 50,000 free web
map loads.

**The architectural rule that keeps this near-free: geocode on WRITE, never on
READ.** A ground is created once and viewed thousands of times. Geocode when it
is created, store the coordinates, and every subsequent view — including
proximity search — is a database query costing nothing. If you geocode on read,
cost scales with traffic and this gets expensive fast.

**My actual recommendation for v1: don't render an interactive map.** Store
coordinates, show *"Ground Name · 2.4 km away"*, and put a **Directions** button
that hands off to the user's own maps app via a `geo:` URI or a maps URL.

That delivers most of the value at **zero API cost**, zero map SDK weight, no
API key to leak, and it works internationally on day one. An embedded map is a
nice Phase 2 upgrade once you know people are actually using grounds — and by
then you'll have real usage numbers to choose a provider with.

For the one API you do need (turning a typed address into coordinates), the
cheapest sensible options are Mapbox geocoding, or OpenStreetMap/Nominatim if you
respect its usage policy and volume stays low.

---

## 5. What "international" actually requires

Beyond storing a country code, these are the things that break for overseas users:

| Concern | What to do |
|---|---|
| **Timezones** | Store IANA timezone per ground; store fixture times as UTC *plus* the ground's zone; render in the ground's local time with the viewer's zone as secondary. Fixes the bug in §1. |
| **Distance units** | km vs miles. US and UK expect miles. Derive from country, allow override in settings. |
| **Address format** | Never compose addresses yourself — order differs by country. Display the provider's `formatted_address`. |
| **Scripts and languages** | Store both `name` (as entered) and `name_local`. Search must match either. |
| **Country selection** | Replace free-text country with an ISO-3166 list, so filtering and flags work. This is the one fixed list worth having. |
| **Phone/currency** | Out of scope here, but tournament entry fees will hit the same problem later. |

---

## 6. Phased roadmap

Each phase is independently shippable and independently useful.

**Phase 0 — Decisions (no code)**
Confirm ground-location-only scope. Pick geocoding provider. Decide whether v1
ships without an embedded map. Decide units default.

**Phase 1 — Data model**
`grounds` table + RLS (public read, authenticated insert, creator/admin update).
Nullable `ground_id` on matches/fixtures/tournaments. Existing `venue` text kept.
Dedupe guard on near-identical name + coordinates.

**Phase 2 — Ground creation and picker**
Search-existing-first UI, so people reuse grounds instead of creating duplicates.
"Can't find it? Add a ground" as the fallback, with geocoding at write time and
an optional *"use my current location"* to drop the pin.

**Phase 3 — Device location (Capacitor)**
`@capacitor/geolocation`, prominent disclosure screen, coarse permission, graceful
denial. Nothing persisted.

**Phase 4 — Proximity search**
PostGIS on Supabase (or plain lat/lng with a bounding-box prefilter and Haversine
if you'd rather avoid the extension). Replace the text match in "Cricket Near You"
with real distance. Sort matches, tournaments and grounds by proximity.

**Phase 5 — Ground detail + directions**
Ground page: name, address, distance, upcoming fixtures there, past matches,
Directions hand-off. Still no embedded map.

**Phase 6 — Timezone correctness**
Fix fixture scheduling end-to-end using ground timezone. This is the one phase
that touches existing behaviour rather than adding to it, so it wants its own
testing pass.

**Phase 7 — Moderation and trust**
Verified badge, admin review queue for new grounds, merge-duplicates tool,
report-a-bad-pin.

**Phase 8 (optional) — Embedded maps**
Only once usage justifies the cost and the dependency.

---

## 7. Risks and open questions

- **Duplicate grounds are the main failure mode.** If the picker doesn't
  aggressively surface existing grounds first, you end up with the same mess as
  free text plus a database table. Phase 2's UX matters more than its code.
- **Informal grounds often aren't in any geocoder.** Gully and maidan cricket
  happens on pitches with no address. "Drop a pin at my current location" is not
  a nice-to-have — for a lot of your users it's the *primary* path.
- **This touches the protected web app.** Grounds are a shared backend feature, so
  `legacy-app` and Supabase both change. That is outside the "Capacitor only"
  rule we've worked under so far, and needs your explicit go-ahead before
  anything is written.
- **Schema changes are irreversible in practice.** Adding tables and nullable
  columns is safe; changing the profile location fields is not. I'd leave the
  existing `country/region/district/area` columns alone initially and layer
  grounds beside them rather than migrating.
- **Supabase Storage** is still not provisioned. Ground photos would need it —
  same approval gate as match video.

---

## 8. What I'd do first

If you want the smallest thing that proves the idea: **Phases 1, 2 and 5 with no
device location and no map.** Grounds become real, reusable, and have addresses
and a Directions button. That is genuinely useful on its own, costs almost
nothing, and everything after it — proximity, maps, timezone — plugs into the
same table.
