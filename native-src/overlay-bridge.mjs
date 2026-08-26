/* ===========================================================================
   CricketConnect — overlay bridge (CAPACITOR ONLY)
   ---------------------------------------------------------------------------
   Copied into www/ and injected as <script type="module"> by copy-web.mjs.
   legacy-app/ and the TWA never load it.

   WHY THIS FILE EXISTS
   --------------------
   overlays.js is an ES module. The other native-src scripts are plain
   <script> tags (this project has no bundler), so they cannot `import` from
   it. This one file is a module purely so it CAN import, and it re-exposes
   what the overlay pipeline needs on `window`.

   The point is that the score overlay is NOT reimplemented for the native
   pipeline. All five templates, the fonts, the colours, the layout — it is
   the same drawOverlay() the web recorder has always used, rendered to an
   offscreen canvas instead of a live compositing canvas. Reimplementing it in
   Java would have meant maintaining two versions that drift apart.

   READING THE MATCH WITHOUT TOUCHING app.js
   -----------------------------------------
   buildOverlayState() needs app.js's `match` object, which is module-internal
   and not on window. But app.js already persists it:

       const K = { match:'cs_match_v3', … };
       save(K.match, match);          // localStorage, JSON

   so the live match is readable from localStorage directly. That is a public
   browser API and a value app.js writes on every ball, which makes it a
   legitimate read rather than a hack into private state — and it needs no
   change to the protected file.

   If app.js ever renames that key, readLiveMatch() returns null and the
   overlay is skipped with a visible message, rather than silently producing
   videos with a stale or blank scoreboard.
   =========================================================================== */

import { buildOverlayState, drawOverlay, TEMPLATES } from './overlays.js';

const MATCH_KEY = 'cs_match_v3';   // app.js's K.match

function readLiveMatch() {
  try {
    const raw = localStorage.getItem(MATCH_KEY);
    return raw ? JSON.parse(raw) : null;
  } catch (e) {
    return null;
  }
}

/** Current overlay state, or null if there is no live match to describe. */
function currentOverlayState(opts = {}) {
  const match = readLiveMatch();
  if (!match) return null;
  try {
    return buildOverlayState(match, opts);
  } catch (e) {
    return null;
  }
}

/**
 * Renders one overlay to a transparent PNG data URL at the given size.
 *
 * The canvas is left fully transparent apart from the overlay itself — the
 * compositor blends this straight over the camera frame, so any background
 * fill here would black out the video.
 */
function renderOverlayPng(templateId, state, width, height) {
  const canvas = document.createElement('canvas');
  canvas.width = width;
  canvas.height = height;
  const ctx = canvas.getContext('2d');
  ctx.clearRect(0, 0, width, height);
  drawOverlay(templateId, ctx, width, height, state, null);
  return canvas.toDataURL('image/png');
}

/** Stable identity for a state, so repeated identical scores collapse to one PNG. */
function overlayStateKey(state) {
  if (!state) return 'none';
  try {
    return JSON.stringify(state);
  } catch (e) {
    return String(Date.now());
  }
}

window.CricketConnectOverlay = {
  TEMPLATES,
  readLiveMatch,
  currentOverlayState,
  renderOverlayPng,
  overlayStateKey,
  available: true
};
