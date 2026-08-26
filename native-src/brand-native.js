/* ===========================================================================
   CricketConnect — real-logo brandmark swap (CAPACITOR ONLY)
   ---------------------------------------------------------------------------
   Copied into www/ and injected into www/index.html by scripts/copy-web.mjs.
   legacy-app/ and the TWA never load this — they keep the existing inline SVG
   brandmark exactly as it is today.

   WHAT PROBLEM THIS SOLVES
   ------------------------
   The web app draws its emblem as an inline SVG built in avatars.js
   (brandMark(), a simplified "C swoosh + ball" with hardcoded fills), painted
   into ~14 places by paintBrandMarks(). Those fills are baked into the markup,
   so CSS cannot retheme them and the placeholder can never become the real
   logo artwork.

   Rather than edit avatars.js — which lives in legacy-app and is protected —
   this replaces the rendered mark in the DOM with the real logo PNG generated
   from the official source image by scripts/generate_brand_assets.py.

   HOW IT AVOIDS FIGHTING app.js
   -----------------------------
   paintBrandMarks() is guarded by `if(!el.dataset.painted)`. Setting our own
   content AND dataset.painted = '1' therefore makes app.js skip the element
   entirely on every subsequent render — no flicker, no tug of war, and no
   change to app.js required. Screens are re-rendered constantly, so a
   MutationObserver catches marks as they appear instead of running once.
   =========================================================================== */

(function () {
  'use strict';

  var LOGO = './assets/brand/cc-mark-192.png';

  /* Containers whose contents are a brandmark. The first is app.js's own hook;
     .lockup-mark is the sign-in lockup (brandLockup() emits no data-brandmark
     attribute); #installMark is set directly by a one-off line in app.js. */
  var SELECTOR = '[data-brandmark], .lockup-mark, #installMark';

  /* Sizes app.js itself uses: brandMark(34) for the inline marks, brandMark(78)
     for the sign-in lockup. These are the fallbacks when we get to an element
     BEFORE app.js has painted an SVG into it — which is the normal case, since
     the MutationObserver fires the moment the node is inserted. Without a
     fallback the <img> would have no dimensions at all and collapse or stretch,
     which is exactly what made the top-bar mark render full-width. */
  var DEFAULT_SIZE = 34;
  var LOCKUP_SIZE = 78;

  function swap(el) {
    if (!el || el.dataset.ccBranded === '1') return;

    /* Prefer the real rendered size if app.js got here first, so the swap is
       layout-neutral either way. */
    var svg = el.querySelector('svg');
    var attr = svg && (svg.getAttribute('width') || svg.getAttribute('height'));
    var size = parseInt(attr, 10);
    if (!size || isNaN(size)) {
      size = el.classList && el.classList.contains('lockup-mark') ? LOCKUP_SIZE : DEFAULT_SIZE;
    }

    var img = document.createElement('img');
    img.src = LOGO;
    img.alt = 'Cricket Connect';
    img.className = 'cc-brand-img';
    img.width = size;
    img.height = size;
    /* Belt and braces: the attributes above can be overridden by a stylesheet,
       so pin the used value inline too. */
    img.style.width = size + 'px';
    img.style.height = size + 'px';
    /* Rounded to match the logo's own icon silhouette — inline because the
       radius has to scale with the mark size. */
    img.style.borderRadius = Math.round(size * 0.225) + 'px';

    el.innerHTML = '';
    el.appendChild(img);

    el.dataset.ccBranded = '1';
    el.dataset.painted = '1';   // stops app.js's paintBrandMarks() re-drawing the SVG over us
  }

  function sweep(root) {
    var scope = root && root.querySelectorAll ? root : document;
    var found = scope.querySelectorAll(SELECTOR);
    for (var i = 0; i < found.length; i++) swap(found[i]);
    /* An added node can itself be a mark, not just contain one. */
    if (root && root.matches && root.matches(SELECTOR)) swap(root);
  }

  function start() {
    sweep(document);

    if (!window.MutationObserver) return;
    new MutationObserver(function (records) {
      for (var i = 0; i < records.length; i++) {
        var added = records[i].addedNodes;
        for (var j = 0; j < added.length; j++) {
          if (added[j].nodeType === 1) sweep(added[j]);
        }
      }
    }).observe(document.documentElement, { childList: true, subtree: true });
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', start);
  } else {
    start();
  }
})();
