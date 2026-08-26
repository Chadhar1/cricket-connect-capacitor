/* native-bridge.js — Capacitor-only glue code.

   Loaded ONLY inside the Capacitor build. copy-web.mjs appends a
   <script src="./native-bridge.js"> tag to the COPY of index.html that
   lands in www/ — legacy-app/index.html itself is never touched, so the
   TWA and the plain website never load this file at all.

   Deliberately does NOT `import { App } from '@capacitor/app'` as an ES
   module — this project has no bundler (same as legacy-app: plain
   <script type="module">, zero build step), so a bare npm specifier
   import would not resolve inside the WebView. Capacitor's native
   runtime auto-injects a global `window.Capacitor` bridge into every
   page it loads, exposing each installed plugin at
   `Capacitor.Plugins.<Name>` — that is the documented no-bundler usage
   path, and what this file relies on.

   STATUS: IMPLEMENTED — NOT BUILT. Written against Capacitor 7's
   @capacitor/app and @capacitor/status-bar APIs from documentation, not
   yet loaded inside an actual Capacitor WebView (needs Milestone 1-2 —
   npm install + cap add android — first, see README.md). If
   `window.Capacitor` isn't present (e.g. this somehow loaded outside a
   Capacitor build), everything below no-ops instead of throwing. */

(function () {
  const Capacitor = window.Capacitor;
  if (!Capacitor || !Capacitor.Plugins) {
    console.warn('[native-bridge] window.Capacitor not found — running outside a Capacitor build, native glue is inactive.');
    return;
  }

  /* ---------------------------------------------------------------------
     Android back button (spec Phase 2 Step 7 / Milestone 6).

     Verified against the real legacy-app source before writing this, not
     guessed:
       - go(screen) in app.js already does
         history.pushState({screen}, '', location.pathname), and a
         matching window.addEventListener('popstate', ...) already
         re-renders the right screen. So for ordinary screen navigation,
         calling history.back() is sufficient — app.js's own existing
         listener does the rest. Zero changes to app.js needed here.
       - Modals go through openModal(html) / closeModal() in app.js, and
         closeModal()'s entire body — read directly from source — is:
             $('modalRoot').innerHTML = '';
         Reproducing that one line from outside the module has the exact
         same effect as calling closeModal() itself. This is a faithful
         copy of confirmed behavior, not a guess.

     NOT handled yet: Section 21's "recording in progress -> confirm
     before abandoning" case. There is no native recording state to check
     until Milestone 8+ (native camera) exists — revisit this listener
     once recorder-native.js exists. Camera-screen-specific back handling
     (Section 9's "Back -> exit camera safely") is the same story. */
  const CapApp = Capacitor.Plugins.App;
  if (CapApp && CapApp.addListener) {
    CapApp.addListener('backButton', ({ canGoBack }) => {
      const modalRoot = document.getElementById('modalRoot');
      if (modalRoot && modalRoot.innerHTML.trim() !== '') {
        modalRoot.innerHTML = '';
        return;
      }
      if (canGoBack) {
        window.history.back();
      } else {
        CapApp.exitApp();
      }
    });
  } else {
    console.warn('[native-bridge] Capacitor.Plugins.App not available — is @capacitor/app installed and synced?');
  }

  /* ---------------------------------------------------------------------
     Status bar (spec Phase 2 Step 8 / Milestone 7).

     Conservative default only: dark content background with light
     (visible-on-dark) status bar icons, matching the app's existing navy
     branding (#060B14, already used as the splash background in
     capacitor.config.json). Does not touch layout/safe-area CSS — the
     existing responsive design is left exactly as-is per Section 10
     ("do not introduce unnecessary visual changes"); this only sets the
     native chrome around it. */
  const StatusBar = Capacitor.Plugins.StatusBar;
  if (StatusBar && StatusBar.setStyle) {
    StatusBar.setStyle({ style: 'DARK' }).catch(() => {});
    if (StatusBar.setBackgroundColor) {
      StatusBar.setBackgroundColor({ color: '#060B14' }).catch(() => {});
    }
  }
})();
