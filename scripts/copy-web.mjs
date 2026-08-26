/* Copies the existing legacy-app web app into this project's www/ folder.
   COPY, never move/edit — the original in ../legacy-app is the protected,
   working production source and this script must never touch it beyond
   reading. Re-run any time legacy-app changes and you want this native
   shell to pick up the update: `npm run copy-web`.

   Excludes docs/planning files, test harnesses, and directories that have
   nothing to do with what actually runs in the browser — everything else
   (all .js/.html/.css/.json/icons) is copied as-is, unmodified. */

import { existsSync, cpSync, mkdirSync, rmSync, readdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const SRC = join(__dirname, '..', '..', 'legacy-app');
const DEST = join(__dirname, '..', 'www');
const NATIVE_SRC = join(__dirname, '..', 'native-src'); // Capacitor-only glue code — never part of legacy-app

const EXCLUDE = new Set([
  'goclaw', 'marketing', '_next', 'node_modules', '.git',
  'run-tests.mjs',
  'firebase-config.js', 'firestore.rules', // pre-Supabase leftovers, not used by cloud.js today — verify before deleting from legacy-app itself, but no need to copy into the native shell
  'assetlinks.json', // TWA-specific Digital Asset Links file; the Capacitor app will need its own, generated separately for its own package/cert
  'twa-manifest.json', // Bubblewrap/TWA-only config, meaningless to Capacitor
  'vercel.json', // deployment config for the hosted website, not a client asset
  'supabase', // server-side Edge Function *source* — must never ship inside a client app bundle
  'welcome' // standalone marketing landing page, not part of the app's own screens/routing
]);

function isExcluded(name){
  return EXCLUDE.has(name) || name.endsWith('.md') || name.endsWith('.sql');
}

if(!existsSync(SRC)){
  console.error(`Source not found: ${SRC} — expected ../legacy-app relative to this project.`);
  process.exit(1);
}

if(existsSync(DEST)) rmSync(DEST, { recursive: true, force: true });
mkdirSync(DEST, { recursive: true });

cpSync(SRC, DEST, {
  recursive: true,
  filter: (src) => {
    const base = src.split(/[\\/]/).pop();
    return !isExcluded(base);
  }
});

console.log(`Copied legacy-app -> ${DEST}`);
console.log('legacy-app itself was not modified — this only read from it.');

/* ---------------------------------------------------------------------------
   Layer Capacitor-only glue code on top of the copy above. This runs AFTER
   the wipe-and-recopy, so it survives every re-run of `npm run copy-web`
   without ever touching legacy-app. See native-src/native-bridge.js for
   what this actually does (Android back button, status bar) and why it
   can't be a plain ES module import from '@capacitor/app' in a
   no-bundler project like this one. */
if(existsSync(NATIVE_SRC)){
  // .sort() for deterministic load order across OSes/filesystems — readdirSync's
  // own order isn't guaranteed. Doesn't currently matter for correctness (none of
  // these files call into each other at top-level parse time, only later inside
  // event handlers, by which point every script has already finished loading) but
  // costs nothing to make predictable rather than relying on that being true forever.
  const entries = readdirSync(NATIVE_SRC);
  const nativeFiles = entries.filter(f => f.endsWith('.js')).sort();
  const nativeCss   = entries.filter(f => f.endsWith('.css')).sort();
  /* .mjs files are injected as <script type="module"> so they can `import`
     from legacy-app's own ES modules (overlays.js etc). The plain .js files
     above deliberately stay classic scripts — they need to run against the
     DOM and window without module scoping. */
  const nativeMjs   = entries.filter(f => f.endsWith('.mjs')).sort();

  for(const f of [...nativeFiles, ...nativeCss, ...nativeMjs]){
    cpSync(join(NATIVE_SRC, f), join(DEST, f));
  }

  /* Brand assets (logo marks, lockup). Copied wholesale so the WebView can
     reference them at ./assets/brand/... — same lifecycle as the scripts:
     wiped and rewritten on every run, never sourced from legacy-app. */
  const assetsSrc = join(NATIVE_SRC, 'assets');
  if(existsSync(assetsSrc)){
    cpSync(assetsSrc, join(DEST, 'assets'), {
      recursive: true,
      // The full-resolution source logo is a build input for
      // scripts/generate_brand_assets.py, not a runtime asset — no point
      // shipping ~850KB of it inside the APK.
      filter: (src) => !src.split(/[\\/]/).includes('source')
    });
    console.log('Copied native-src/assets -> www/assets (brand source image excluded from the app bundle)');
  }

  const indexPath = join(DEST, 'index.html');
  if(existsSync(indexPath)){
    let html = readFileSync(indexPath, 'utf8');
    let touched = false;

    /* CSS goes in <head>, AFTER the existing styles.css link that's already
       there, so the theme's :root overrides win on specificity-tie. Injecting
       at </head> guarantees that ordering without having to pattern-match on
       the styles.css line itself. */
    if(nativeCss.length){
      const links = nativeCss.map(f => `<link rel="stylesheet" href="./${f}">`).join('\n  ');
      if(html.includes('</head>')){
        html = html.replace('</head>', `  ${links}\n</head>`);
        touched = true;
        console.log(`Injected stylesheet(s) into www/index.html <head>: ${nativeCss.join(', ')}`);
      } else {
        console.warn('index.html has no </head> tag — could not inject native-src stylesheets.');
      }
    }

    if(nativeFiles.length || nativeMjs.length){
      const classic = nativeFiles.map(f => `<script src="./${f}"></script>`);
      const modules = nativeMjs.map(f => `<script type="module" src="./${f}"></script>`);
      const tags = [...modules, ...classic].join('\n  ');
      if(html.includes('</body>')){
        html = html.replace('</body>', `  ${tags}\n</body>`);
        touched = true;
        if(nativeMjs.length)   console.log(`Injected module(s) into www/index.html: ${nativeMjs.join(', ')}`);
        if(nativeFiles.length) console.log(`Injected script(s) into www/index.html: ${nativeFiles.join(', ')}`);
      } else {
        console.warn('index.html has no </body> tag — could not inject native-src scripts. Add them manually.');
      }
    }

    /* ---------------------------------------------------------------------
       DISABLE THE SERVICE WORKER IN THE NATIVE BUILD.

       legacy-app ships a service worker (sw.js) using stale-while-revalidate,
       and index.html is in its precache SHELL list. That is exactly right for
       the website — the app opens instantly and works with no signal at the
       ground.

       Inside Capacitor it is wrong, and actively harmful:

         * Capacitor serves the app from https://localhost out of files already
           bundled in the APK. They are local. Caching a local file to survive
           being offline achieves nothing.
         * Because index.html is precached and VERSION only changes when
           somebody remembers to bump it, the WebView keeps loading the OLD
           index.html after a rebuild — the one without the injected theme and
           native scripts. The new files sit on disk, never referenced. Every
           future change to this app would silently fail to appear.
         * `cap sync` and Android Studio's Run reinstall the APK but do NOT
           clear WebView storage, so the stale cache survives a normal rebuild.

       So the copy in www/ is replaced with a stub that unregisters itself and
       deletes the caches, and the registration call is stripped from the
       copied index.html. legacy-app/sw.js is untouched — the website keeps its
       service worker exactly as it is.

       Native push notifications will need @capacitor/push-notifications
       instead of Web Push; that was already the plan (see
       CAPACITOR-FEATURE-PARITY.md) and is still NOT STARTED.
       --------------------------------------------------------------------- */
    const swRegex = /\s*<script>\s*if\s*\(\s*['"]serviceWorker['"]\s+in\s+navigator\s*\)\s*\{[\s\S]*?<\/script>/;
    if(swRegex.test(html)){
      html = html.replace(swRegex, '\n<!-- service worker registration removed for the Capacitor build — see scripts/copy-web.mjs -->');
      touched = true;
      console.log('Removed service-worker registration from www/index.html (native build serves local files)');
    } else {
      console.warn('WARNING: could not find the service-worker registration in index.html to remove it.\n' +
                   '         If legacy-app/index.html changed shape, update swRegex in copy-web.mjs —\n' +
                   '         otherwise a stale cached index.html can mask every future change.');
    }

    if(touched){
      writeFileSync(indexPath, html, 'utf8');
      console.log('(www/index.html is a copy — legacy-app/index.html untouched)');
    }
  }

  /* Self-destructing stub. Any device that already registered the real service
     worker will pick this up on its next check and tear the old one down,
     rather than being stuck on a stale cache forever. */
  const swPath = join(DEST, 'sw.js');
  if(existsSync(swPath)){
    writeFileSync(swPath, `/* Capacitor build — the real service worker is disabled here.
   See scripts/copy-web.mjs for why. legacy-app/sw.js is unchanged; the website
   still uses it. This stub exists only to retire an already-installed worker on
   devices that registered one before this change. */
self.addEventListener('install', () => self.skipWaiting());
self.addEventListener('activate', (event) => {
  event.waitUntil((async () => {
    for (const key of await caches.keys()) await caches.delete(key);
    await self.clients.claim();
    await self.registration.unregister();
  })());
});
`, 'utf8');
    console.log('Replaced www/sw.js with a self-unregistering stub (legacy-app/sw.js untouched)');
  }
} else {
  console.log('No native-src/ folder found — skipping native glue injection.');
}
