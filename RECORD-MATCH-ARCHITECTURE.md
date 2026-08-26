# Record Match — Native Architecture

Status of this document: research + decisions only. Nothing described below as a "plan" has been built or tested. Written per Phase 2 Step 10-14 — understand the existing system fully before writing any native code, so native work doesn't accidentally lose the score-overlay feature the current product already has.

## 1. How the current web recording actually works (verified by reading the real source, this session)

Three files, cleanly separated (this is a real strength of the current design — nothing here needs to be "unwound" for native work):

- **`recorder.js`** — the Video Compositor. Owns the camera, a hidden `<canvas>`, and `MediaRecorder`.
- **`overlays.js`** — the Broadcast Overlay Renderer. Pure `<canvas>` 2D drawing, 5 templates (classic, modern, broadcast-pro, minimal, tournament-premium). Has never heard of the camera or MediaRecorder.
- **`broadcast-events.js`** — feeds ball-by-ball events (FOUR, SIX, WICKET, etc.) into a short-lived animation queue.

The actual recording loop, exactly as coded today (`recorder.js` lines 262-389):

1. `openCameraPreview()` calls `getUserMedia()`, gets a live camera `MediaStream`, plays it into a hidden `<video>` element (`previewVideoEl`).
2. `startRecording()` creates an offscreen `<canvas>` (`compositeCanvas`) at the target recording resolution.
3. Every `requestAnimationFrame` tick (`runCompositeLoop()`): draw the current camera video frame onto the canvas (cover-fit, handles aspect ratio/rotation), then call `overlays.js`'s `drawOverlay(templateId, ctx, w, h, state, activeEvent)` on the **same canvas, same frame** — so camera pixels and score-overlay pixels are merged into one image, pixel-for-pixel, before anything is ever encoded.
4. `compositeCanvas.captureStream(fps)` turns that canvas into a **live `MediaStream`** — this is the one line the entire overlay-in-video trick depends on.
5. That stream (+ the mic audio track, if enabled) feeds a `MediaRecorder`, which encodes `video/webm` chunks (VP8/VP9 + Opus — `.mp4` is not reliably available in Chrome/Android's `MediaRecorder`, an already-documented, deliberate trade-off, not a bug).
6. On stop, chunks combine into one `Blob`. `rmSaveVideo()`/`rmShareVideo()` in `app.js` offer a browser download link or `navigator.share()` — **local-only**, nothing is uploaded anywhere. No Supabase Storage bucket for video exists (re-confirmed this session).

## 2. The actual native problem, stated precisely

`canvas.captureStream()` is a web platform API tied to an HTML `<canvas>` element. There is no Android/native equivalent — a native camera plugin (`Camera2`/`MediaRecorder` under the hood) gives you a recording of the **raw camera feed only**. It has no concept of, and no hook into, your app's `<canvas>` overlay layer. This is the single hardest unresolved technical question in this whole migration, exactly as flagged in Phase 1 — now precisely located rather than just gestured at.

## 3. Camera architecture decision (Section 13's Options A–E, evaluated against the above)

| Option | What it means | Verdict |
|---|---|---|
| A — existing web UI + native camera plugin | Native plugin records the raw camera feed; overlay is dropped entirely | Simplest to build. **Loses the overlay** — a real regression vs. today's 5-template product. |
| B — custom native Capacitor plugin | Hand-write Kotlin: Camera2/MediaRecorder + a native canvas-to-surface compositor | Full control, but this is a multi-week native Android engineering project by itself — exactly what Section 27 ("do not overengineer") warns against as a first move. |
| C — native recording + web score overlay (shown live, not burned in) | Overlay renders as an on-screen HTML/CSS layer during recording, matching what the user sees | The **saved file** still has no overlay baked in — same regression as A, just hidden during capture. |
| D — native recording + post-processing overlay | Record raw native video, then re-encode/burn the overlay in as a separate step after recording stops | Preserves the burned-in overlay AND upgrades to real MP4. Adds processing time and a native (or on-device FFmpeg-style) video-compositing step after every recording. |
| E — native camera preview + native overlay drawn onto the camera surface directly | Overlay composited by the OS/SurfaceView in real time | Similar effort to B; almost no off-the-shelf plugin exposes this. |

**Decision:** don't try to solve the overlay on the first pass. Build the native pipeline with **Option A** first — camera → record → stop → preview → save → (upload, if decided) → match association, explicitly and visibly labeled as **"no score overlay yet — known regression, tracked separately"** rather than silently shipping it as if nothing changed. Only once that base pipeline is proven on a real device does overlay restoration become its own milestone, attempted as **Option D**. This is also exactly the order the spec's own Milestone list already puts it in (Milestones 8-14 before Milestone 15, "Score overlay") — the milestone order and this architecture decision agree independently, which is a good sign it's the right sequencing rather than a rationalization.

## 4. Plugin selection

**`@capacitor-community/camera-preview`**, version 7.0.2 — actively maintained, exposes `startRecordVideo()` returning a file path in real **`.mp4`** (a genuine upgrade over today's `.webm`-only limitation).

**Compatibility caveat — flagging honestly rather than guessing:** the latest `@capacitor/core` right now is 8.4.2, but camera-preview 7.0.2's confirmed compatibility (per its own docs) is Capacitor 7, not 8. Two follow-up fetches to the plugin's GitHub `package.json` (to check for a Capacitor-8-compatible release) timed out from this sandbox and were not completed. Per your own instruction not to blindly install the newest versions if they create conflicts: **pin the whole toolchain to the 7.x line** (`^7.0.0` for `@capacitor/core`, `@capacitor/cli`, `@capacitor/android`) rather than 8.4.2, until this is confirmed. Before running `npm install` for real, run `npm view @capacitor-community/camera-preview peerDependencies` (or check its GitHub) to verify against whatever the actual latest 7.x patch is at that time.

Not yet evaluated, worth a look before committing: `@capacitor-community/video-recorder` (a plugin specifically for recording, distinct from camera-preview, surfaced in the same search but not yet investigated) and `@capgo/camera-preview` (an actively-maintained fork of camera-preview). Neither ruled in nor out — noted so the choice above isn't mistaken for the only option considered.

**Phase 3 update — RESOLVED, verified against the real installed package** (`node_modules/@capacitor-community/camera-preview/`, after `npm install` succeeded on the real machine): version 7.0.2 confirmed installed, `@capacitor/core` confirmed at 7.6.8. The README states outright "Version 7 of this plugin requires Capacitor 7" — settling the compatibility question for good, no longer a caveat.

**Real API, read from `dist/esm/definitions.d.ts`, `README.md`, and the actual Android Java source (`CameraPreview.java`) — not assumed:**

- `CameraPreviewPlugin` methods: `start(options)`, `startRecordVideo(options)`, `stop()`, `stopRecordVideo()`, `capture(options)`, `captureSample(options)`, `getSupportedFlashModes()`, `setFlashMode(options)`, `flip()`, `setOpacity(options)`, `isCameraStarted()`. `startRecordVideo`/`stopRecordVideo`/`isCameraStarted` are Android + iOS only — no web fallback, which is fine, since web keeps using the existing `recorder.js` path via feature detection (Section 16 of the Phase 3 spec).
- **Verified discrepancy worth flagging loudly:** the shipped `.d.ts` declares `stopRecordVideo(): Promise<void>`. The actual Android implementation (`CameraPreview.java`, `onStopRecordVideo`) resolves with `{ videoFilePath: <string> }` — confirmed by reading the Java source directly (`jsObject.put("videoFilePath", file); pluginCall.resolve(jsObject);`), matching the README's prose ("returned as a file path... .mp4") but contradicting the type definitions in the same package version. `recorder-native.js` (written this phase) trusts the Java source over the `.d.ts`.
- The recorded file lands in the app's **cache directory** (`getActivity().getCacheDir()`), not persistent storage — confirmed from source. Cache can be reclaimed by Android under storage pressure, so the file must be copied out via `@capacitor/filesystem` promptly after stopping — this was already the plan (Section 7 below), now confirmed necessary rather than just cautious.
- The plugin overlays a **real native Android view** (confirmed via `CustomSurfaceView.java`/`CustomTextureView.java`), not a `<canvas>`/`<video>` element — so whatever HTML sits in front of it needs a transparent background to reveal it (same requirement the README documents for Ionic's `--background: transparent`, adapted to plain CSS here since this app has no Ionic).
- Two real, working native parameters exist in the Java source (`call.getBoolean("withFlash", false)`, `call.getInt("maxDuration", 0)`) that appear in **neither** the README **nor** the `.d.ts` — undocumented but genuinely present and safe to use. `maxDuration` is a promising lead for enforcing a sane recording length, but its unit/behavior isn't confirmed on-device yet.

`native-src/recorder-native.js` (new this phase) implements `startNativeRecording()`/`stopNativeRecording()`/`previewNativeRecording()` against all of the above, with `saveNativeRecording()` deliberately left unimplemented until `@capacitor/filesystem`'s real installed API gets the same treatment (not done yet — same discipline, not skipped).

## 5. Permissions plan

`CAMERA` + `RECORD_AUDIO`, requested through the chosen plugin's own `checkPermissions()`/`requestPermissions()` — layered on top of the `AndroidManifest.xml` declarations already added in Phase 1. States to handle explicitly, per Section 16: first request, denied, permanently denied (needs a Settings deep-link, not just a re-prompt — `@capacitor/app` can open the app's native Settings page), camera hardware absent, microphone hardware absent. None of these should crash the app; all should degrade to a clear message.

## 6. Video settings

Target the plugin's native MP4/H.264/AAC output at 720p30 by default — matching `recorder.js`'s existing `availableQualityOptions()` default and its documented reasoning (favor broad device compatibility and manageable file size over resolution). Higher options (1080p/60fps) stay available the same way they are today, filtered by actual device capability, not offered blindly.

## 7. Local file handling

`@capacitor/filesystem`, writing to `Directory.Documents` or `Directory.External` — never base64-encoding the whole video into JS memory. This mirrors `recorder.js`'s existing discipline (chunked `Blob` parts, never one giant in-memory buffer), just via native file APIs instead of browser `Blob`s.

## 8. Upload / storage — stopping here per Section 19, not deciding unilaterally

No Supabase Storage bucket for video exists today — re-confirmed this session (`supabase.sql` has zero real storage/bucket references). **Recommendation, unchanged from Phase 1: keep local-save + native share sheet only**, matching today's actual product behavior and the Play Store listing's existing Data Safety story (no video ever leaves the device). If cloud backup/sharing is wanted instead, here is what it would require — written out per Section 19's "report what's required" instruction, **not built**, needs your explicit go-ahead before touching the backend at all:

- A new private bucket (e.g. `match-recordings`)
- RLS/storage policies scoped to the recording's own match participants + organiser (not public)
- A size-aware, resumable upload path (Section 19's "avoid base64, prefer streaming/chunked" requirement) rather than a single large PUT
- A decision on retention (recordings can be large; do they expire, get compressed, get a per-user quota?)

## 9. Match association

If/when upload exists, a recording only needs `matchId`, `tournamentId` (if applicable), `uploaderId`, a timestamp, and the file path/URI — this fits as a few columns or one JSON field on an existing table, no new table required. Not designed further than this yet, since there's no upload path to attach it to.

## 10. Progress UI states

`Recording…` → `Processing…` → `Uploading NN%` → `✅ Match recording saved.` / `❌ Upload failed` with retry, per Section 20 — wired to the plugin's real progress callbacks once Section 8 above is actually decided. Not built.

## 11. Known limitations / open questions — do not build past these blindly

- Overlay compositing for natively-recorded video is the hardest unsolved piece in this whole migration (Section 3 above) — Option D is the plan, not yet attempted.
- The Capacitor 7 vs. 8 compatibility question (Section 4) needs a real check on the user's machine before `npm install`, not an assumption.
- `@capacitor-community/video-recorder` hasn't been evaluated as a possible better fit than camera-preview.
- Nothing in this document has been installed, compiled, or run — this sandbox has no npm registry access, no Android SDK, and no device. See `README.md` for what has to happen on your machine before any of Milestones 8-14 can even start.
