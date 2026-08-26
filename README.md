# CricketConnect — Capacitor Android app

**Status: scaffolded, not yet built or tested.** This is a fresh project — nothing here has been installed, compiled, or run on a device yet. It does not touch `../legacy-app` (the live website) or `../cricket-connect-android` (the working TWA) in any way; both remain exactly as they are.

## What this is

The same CricketConnect web app (`../legacy-app`, copied — never edited — into `www/`) running inside Capacitor's native Android WebView, with native plugins added specifically where the browser/TWA falls short — primarily camera/video recording for Record Match. See `CAPACITOR-FEATURE-PARITY.md` for the full feature-by-feature breakdown of what's automatic versus what needs real native work.

## Why this exists instead of just fixing the TWA

Tonight's TWA debugging session (adb + `chrome://inspect`) didn't reach a confirmed root cause for Record Match's silent camera failure before time ran out. This project exists to solve it with a real native camera path regardless of what that root cause turns out to be — but the TWA investigation was **not** concluded, and it's possible the TWA issue is a small, fixable bug rather than a hard platform limitation. Worth keeping in mind before assuming this migration was strictly necessary.

## Machine verification (Phase 3 Step 2) — RESULTS, confirmed on the real machine

| Check | Result |
|---|---|
| Node | v24.18.0 |
| npm | 11.16.0 |
| Java on PATH | not found (irrelevant — see below) |
| Android Studio | installed — `C:\Program Files\Android\Android Studio` |
| Android SDK | installed — `%LOCALAPPDATA%\Android\Sdk` |
| adb.exe | exists — `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe` (just not on PATH) |
| JDK | exists — Android Studio's bundled JBR at `C:\Program Files\Android\Android Studio\jbr\bin\java.exe` |
| Gradle | no global install — expected/fine, Android projects use the per-project `gradlew.bat` wrapper, not a global Gradle |

Everything needed is already on this machine. Java/adb just aren't on this shell's PATH — Android Studio doesn't require that (it uses its bundled JBR + auto-detects the SDK internally), and command-line `adb`/`java` calls just need the two `Test-Path`-confirmed folders above added to PATH when we get to device testing.

One more thing this surfaced: `~/.bubblewrap/config.json` shows bubblewrap (the TWA's build tool) uses its *own separate, privately downloaded* JDK 17 and Android SDK under `~/.bubblewrap/` — not the Android Studio ones above. That's fine and expected; it's bubblewrap-specific and irrelevant to the Capacitor project, which will use the Android-Studio-managed SDK/JDK instead (the standard path, and what `npx cap open android` will auto-detect).

## One-time setup (run on your machine — this sandbox has no npm registry access)

`package.json` now has real, researched dependency versions pinned (not "latest") — just run:

```
cd D:\Cricket\cricket-app\cricket-connect-capacitor
npm install
npm run copy-web
npx cap add android
```

**Before you run `npm install`:** the pinned versions target the Capacitor **7.x** line (`^7.0.0`), deliberately not the newer 8.4.2, because the selected Record Match plugin (`@capacitor-community/camera-preview` 7.0.2) only confirms Capacitor 7 compatibility as of this research. Double-check this is still accurate first — `npm view @capacitor-community/camera-preview peerDependencies` — since it was checked via web search and two GitHub fetches timed out from this sandbox before it could be verified against the package's actual published metadata. See `package.json`'s `_versionNotes` field and `RECORD-MATCH-ARCHITECTURE.md` Section 4 for the full reasoning.

`cap add android` reads the already-written `capacitor.config.json` in this folder and generates a new `android/` subfolder — a real Android Studio project, separate from `cricket-connect-android/`.

## Native glue code (new this phase)

`native-src/native-bridge.js` is Capacitor-only code — Android back-button handling and status bar styling — that `copy-web.mjs` now copies into `www/` and auto-injects into the copied `index.html` (right before `</body>`) on every run. It never touches `legacy-app/`. See the file's own header comment for why it uses `window.Capacitor.Plugins.*` instead of an ES import (this project has no bundler). Status: written and reasoned against the real `app.js` source, not yet loaded in an actual Capacitor WebView — see `CAPACITOR-FEATURE-PARITY.md` for the honest status label.

## Adding native camera recording

Plugin selected (`@capacitor-community/camera-preview`, already in `package.json`) and full architecture decided — see `RECORD-MATCH-ARCHITECTURE.md`. Not yet installed or coded against: the actual integration (`recorder-native.js` mirroring `recorder.js`'s function signatures) hasn't been written, deliberately — writing code against a plugin API before confirming it firsthand would risk exactly the "looks done but isn't" problem the spec warns against.

**How the API gets verified (Phase 3 Step 4), concretely:** I tried fetching the plugin's real published source three times this phase — `unpkg.com`, `data.jsdelivr.com`, and (last phase) `raw.githubusercontent.com` — all three timed out from this sandbox rather than returning content, which looks like the same network restriction as the npm registry `403`, just manifesting as a hang instead of a clean rejection. So direct inspection from here is a dead end for now. The actual path: once you run `npm install` on your machine, `node_modules/@capacitor-community/camera-preview/` physically exists on disk under this folder — and because that folder is mounted the same way `legacy-app/` is, I can `Read` its real `.d.ts` type definitions and README directly, no network fetch needed. Send the word once `npm install` has completed and I'll go read the actual installed API before writing a single line of `recorder-native.js`.

## Building and testing

```
npx cap open android
```

Opens the project in Android Studio, where you can run it on an emulator or a connected device and build a signed APK the same way you would for any native Android project (Build → Generate Signed Bundle/APK). This sandbox has no Android SDK, so this entire step — every build, every test run — has to happen on your machine, same as the TWA's `bubblewrap build` did.

## Package identity

`com.cricketconnect.app` — deliberately different from the TWA's `app.vercel.cricket_pied_ten.twa`, so installing this one never overwrites or conflicts with the working TWA on a test device. Both can be installed side by side.

## Keeping the web code in sync

Whenever `legacy-app` changes and you want this native shell to pick it up:

```
npm run copy-web
npx cap sync android
```

This only *reads* from `../legacy-app` — never writes to it.

## What's actually done vs. not, right now

**Phase 1 (audit + scaffold):** project scaffold, `capacitor.config.json`, the `copy-web` script, README, feature parity doc.

**Phase 2 (this session):** real dependency versions researched and pinned (with a flagged, unverified compatibility assumption — see above); `RECORD-MATCH-ARCHITECTURE.md` written from a full re-read of the actual `recorder.js`/`overlays.js` source, with a reasoned camera-architecture decision (native pipeline first, overlay restored as a later milestone) and an honest plugin pick with caveats; `native-src/native-bridge.js` written and verified line-by-line against real `app.js` navigation/modal behavior (Android back button + status bar), wired into `copy-web.mjs` so it survives every re-copy without touching `legacy-app/` — the HTML-injection logic was dry-run tested against the real `index.html` content in a scratch folder to confirm it actually works, not just assumed.

**Still not done:** nothing has been `npm install`'d (blocked here — sandbox has no npm registry access), no `android/` folder exists yet, no camera plugin is actually wired to real recording code, no APK has been built, nothing has been tested on a device or even loaded in a WebView. The TWA's own original Record Match bug also remains undiagnosed — that investigation was cut short in Phase 1, not resolved, and this migration proceeds without knowing for certain whether it was strictly necessary.

**What genuinely needs your machine next (Milestones 1-2 in the spec's order):** `npm install`, `npm run copy-web`, `npx cap add android`, then `npx cap open android` to confirm it launches at all. Nothing past that point (auth verification, feature smoke test, back-button testing, camera work) can start until this happens — that hasn't changed since Phase 1.

**Phase 3 (this session) — real progress:** machine verification confirmed Android Studio + SDK + JDK + adb all present on your machine (just not on PATH — irrelevant for Android Studio itself). `npm install` succeeded for real: 99 packages, 0 vulnerabilities, `@capacitor/core` resolved to 7.6.8, `@capacitor-community/camera-preview` to 7.0.2. With `node_modules/` finally existing, I read the camera plugin's actual installed API directly (`definitions.d.ts`, `README.md`, and the Android Java source) instead of guessing — and caught a real discrepancy: the shipped TypeScript types say `stopRecordVideo(): Promise<void>`, but the actual native code resolves with `{ videoFilePath }`. Full findings in `RECORD-MATCH-ARCHITECTURE.md` Section 4.

Wrote `native-src/recorder-native.js` against those verified findings — `startNativeRecording()`, `stopNativeRecording()`, `previewNativeRecording()` implemented (Phase 3's Milestone 8 scope: camera + recording, deliberately **without** the score overlay). `saveNativeRecording()` intentionally left throwing "not implemented" — it needs `@capacitor/filesystem`'s own real API checked the same way before it gets written, not guessed (task #185).

**Not done yet:** `npx cap add android` hasn't run, so there's still no `android/` project, no build, no device test, and this new file has never actually loaded in a WebView. That's the very next step below.
