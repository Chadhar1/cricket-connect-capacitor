/* native-src/recorder-native.js — Phase 3 Milestone 8-12: native camera
   open/preview/record/stop/save, WITHOUT score overlay (deliberately —
   see RECORD-MATCH-ARCHITECTURE.md and the OVERLAY note at the bottom).

   STATUS: IMPLEMENTED — NOT BUILT. Written against the REAL installed
   packages, read directly from node_modules, not guessed:
     - @capacitor-community/camera-preview 7.0.2: dist/esm/definitions.d.ts,
       README.md, and the Android Java source (CameraPreview.java) —
       ground truth for stopRecordVideo's real return shape, which
       contradicts its own .d.ts (see stopNativeRecording below).
     - @capacitor/filesystem 7.1.8: dist/esm/definitions.d.ts (copy()'s
       real shape) and a partial read of the Android Kotlin source
       (FilesystemPlugin.kt / FilesystemMethodOptions.kt) confirming
       `directory` is optional on the source side — consistent with
       "from" being a raw path/URI when copying in from outside
       Capacitor's own managed directories, which is what camera-preview
       hands back. NOT fully traced through @capacitor/synapse's URI
       resolution internals — flagged honestly in saveNativeRecording()
       below as the first thing to check if saving fails on-device.

   Interface: openNativeCameraPreview() / startNativeRecording() /
   stopNativeRecording() / previewNativeRecording() / saveNativeRecording()
   — split into separate preview-then-record steps (not combined) to
   match both the real plugin's actual call sequence (start() must
   succeed before startRecordVideo() — confirmed from source, camera-preview
   rejects "Camera is not running" otherwise) AND the existing web UI's
   own two-screen flow (renderRecordCameraReady() then
   renderRecordingControls() in app.js). */

(function () {
  const Capacitor = window.Capacitor;
  const CameraPreview = Capacitor && Capacitor.Plugins && Capacitor.Plugins.CameraPreview;
  const Filesystem = Capacitor && Capacitor.Plugins && Capacitor.Plugins.Filesystem;

  let previewOpen = false;
  let recording = false;
  let lastVideoFilePath = null; // raw cache-dir path from the plugin, e.g. /data/.../cache/videoTmp.mp4
  let lastVideoWebSrc = null;   // WebView-safe URL from Capacitor.convertFileSrc(), for a <video src>

  function assertCameraAvailable() {
    if (!CameraPreview) {
      throw new Error('CameraPreview plugin not available — running outside a Capacitor Android build, or @capacitor-community/camera-preview is not installed/synced yet.');
    }
  }

  /* ---------------------------------------------------------------------
     Transparent-background contract.

     Confirmed by reading CustomSurfaceView.java / CustomTextureView.java:
     this plugin overlays a REAL native Android view, not a <canvas>/
     <video> element. Whatever HTML sits "in front" needs a transparent
     background to reveal it underneath — same requirement the plugin's
     README documents for Ionic's `--background: transparent`. The actual
     CSS rule lives in record-match-native-ui.js (new this phase), which
     also owns which screen elements get this class — kept out of this
     file so recorder-native.js stays UI-agnostic, matching recorder.js's
     own separation of concerns (camera/recording logic vs. what app.js
     draws on screen). */
  const TRANSPARENT_BG_CLASS = 'native-camera-active';

  /* ---------------------------------------------------------------------
     openNativeCameraPreview(opts) — Milestone 8/9.

     opts: { position?: 'front'|'rear', width?, height? }

     Just CameraPreview.start(). Matches app.js's rmContinueToCamera() ->
     renderRecordCameraReady() step: camera opens and shows a live
     preview, nothing is being recorded yet. */
  async function openNativeCameraPreview(opts = {}) {
    assertCameraAvailable();
    if (previewOpen) throw new Error('Camera preview already open.');

    const previewOptions = {
      position: opts.position === 'rear' ? 'rear' : (opts.position || 'front'),
      width: opts.width || window.screen.width,
      height: opts.height || window.screen.height,
      toBack: true,       // native view sits behind the WebView; TRANSPARENT_BG_CLASS reveals it
      storeToFile: true
    };

    document.documentElement.classList.add(TRANSPARENT_BG_CLASS);
    try {
      await CameraPreview.start(previewOptions);
      previewOpen = true;
      return previewOptions;
    } catch (err) {
      document.documentElement.classList.remove(TRANSPARENT_BG_CLASS);
      throw err;
    }
  }

  /* ---------------------------------------------------------------------
     startNativeRecording(opts) — Milestone 10.

     opts: { maxDuration?, withFlash? } — both real, working native
     parameters read directly from CameraPreview.java's startRecordVideo()
     (call.getBoolean("withFlash", false), call.getInt("maxDuration", 0))
     but present in NEITHER the README NOR the .d.ts. Included because
     they're genuinely there and safe (sensible defaults), not because
     they're documented. maxDuration's unit/behavior is not confirmed on
     a real device yet.

     Requires openNativeCameraPreview() to have already succeeded — the
     plugin itself enforces this (CameraPreview.java rejects "Camera is
     not running" otherwise), this function just fails fast with a
     clearer message instead of relying on that native rejection. */
  /* ---------------------------------------------------------------------
     ensureMicPermission() — works around a real, verified plugin bug.

     DEVICE CRASH THIS FIXES:
         java.lang.RuntimeException: setAudioSource failed.
           at android.media.MediaRecorder.setAudioSource(Native Method)
           at ...CameraActivity.startRecord(CameraActivity.java:905)

     WHY IT HAPPENS — read from the installed plugin's own Android source,
     not guessed:

       1. CameraActivity.java line ~905 calls
              mRecorder.setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
          UNCONDITIONALLY. startRecord() has no audio on/off parameter at all,
          and startRecordVideo() parses only position/width/height/withFlash/
          maxDuration. Recording without audio is simply not offered.

       2. CameraPreview.java's plugin annotation is
              @CapacitorPlugin(name="CameraPreview", permissions={@Permission(strings={CAMERA}, ...)})
          — CAMERA only. The plugin never requests RECORD_AUDIO at runtime.

     So the plugin always needs the mic but never asks for it. AndroidManifest
     declaring RECORD_AUDIO is not enough on Android 6+; it must be granted at
     runtime. Ungranted, setAudioSource throws and the app dies.

     THE FIX: getUserMedia({audio:true}) makes Capacitor's WebView bridge raise
     the native RECORD_AUDIO runtime prompt (BridgeWebChromeClient handles
     RESOURCE_AUDIO_CAPTURE). We then release the mic IMMEDIATELY — holding the
     stream open would keep the device busy and make MediaRecorder fail with the
     very same setAudioSource error we are trying to avoid.

     If permission is refused we do NOT call startRecordVideo, because with no
     audio-free code path in the plugin that call is a guaranteed native crash.
     Failing with a readable message is strictly better than killing the app. */
  async function ensureMicPermission() {
    if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
      throw new Error('This device cannot grant microphone access, and the camera plugin always records audio — recording is unavailable.');
    }
    let stream = null;
    try {
      stream = await navigator.mediaDevices.getUserMedia({ audio: true });
    } catch (e) {
      throw new Error(
        'Microphone access was refused. This camera plugin always records audio and has no video-only mode, so recording cannot start without it.\n\n' +
        'If no permission dialog appeared, Android has already remembered a "deny" for this app. Turn it on manually:\n' +
        'Settings > Apps > Cricket Connect > Permissions > Microphone > Allow.'
      );
    } finally {
      // Release the mic before MediaRecorder tries to claim it.
      if (stream) {
        const tracks = stream.getTracks ? stream.getTracks() : [];
        for (const t of tracks) { try { t.stop(); } catch (e) {} }
      }
    }
    /* track.stop() returns synchronously but the audio HAL does not always free
       the input that instant. Claiming it again too quickly throws the identical
       "setAudioSource failed" we are here to prevent, so give it a moment to
       settle. 250ms is imperceptible next to tapping Start Recording. */
    await new Promise((r) => setTimeout(r, 250));
  }

  async function startNativeRecording(opts = {}) {
    assertCameraAvailable();
    if (!previewOpen) throw new Error('Call openNativeCameraPreview() before startNativeRecording().');
    if (recording) throw new Error('Already recording.');

    // Must happen before startRecordVideo — see the long note above.
    await ensureMicPermission();

    const recordOptions = {
      position: opts.position === 'rear' ? 'rear' : (opts.position || 'front'),
      width: opts.width || window.screen.width,
      height: opts.height || window.screen.height,
      toBack: true,
      storeToFile: true
    };
    if (opts.maxDuration) recordOptions.maxDuration = opts.maxDuration; // undocumented — see note above
    if (opts.withFlash) recordOptions.withFlash = true;                // undocumented — see note above

    await CameraPreview.startRecordVideo(recordOptions);
    recording = true;
  }

  /* ---------------------------------------------------------------------
     stopNativeRecording() — Milestone 10/11.

     CRITICAL, VERIFIED DISCREPANCY (exactly what Phase 3 Section 4 asked
     to watch for): the installed package's own definitions.d.ts declares

         stopRecordVideo(): Promise<void>;

     — but the actual Android implementation
     (CameraPreview.java, onStopRecordVideo) does this:

         jsObject.put("videoFilePath", file);
         pluginCall.resolve(jsObject);

     It genuinely resolves with { videoFilePath: string }, matching what
     the README prose says ("returned as a file path") but NOT what the
     shipped type definitions claim. This function trusts the Java source
     (ground truth, read directly) over the .d.ts.

     The returned path points into the app's CACHE directory
     (getActivity().getCacheDir() in the native source) — Android can
     clear that under storage pressure. This function stops the preview
     (mirrors recorder.js's teardown() discipline of never leaving the
     camera running) but deliberately does NOT copy the file anywhere
     persistent — that's saveNativeRecording()'s job, kept separate so a
     caller can preview the recording first without every stop already
     committing to a permanent copy. */
  async function stopNativeRecording() {
    assertCameraAvailable();
    if (!recording) throw new Error('Not recording.');

    let result;
    try {
      result = await CameraPreview.stopRecordVideo();
    } finally {
      recording = false;
      previewOpen = false;
      document.documentElement.classList.remove(TRANSPARENT_BG_CLASS);
      try { await CameraPreview.stop(); } catch (e) { /* best-effort teardown, matches recorder.js's teardown() */ }
    }

    lastVideoFilePath = (result && result.videoFilePath) || null;
    if (!lastVideoFilePath) {
      throw new Error('Recording stopped but no videoFilePath came back — see the discrepancy note above; the installed plugin may have changed behavior since this was written.');
    }
    if (!Capacitor.convertFileSrc) {
      throw new Error('Capacitor.convertFileSrc not available — unexpected, this is a core Capacitor API.');
    }
    lastVideoWebSrc = Capacitor.convertFileSrc(lastVideoFilePath);
    return { filePath: lastVideoFilePath, webSrc: lastVideoWebSrc, mimeType: 'video/mp4' };
  }

  /* previewNativeRecording() — hands back a <video>-tag-safe URL for the
     just-stopped recording. Caller creates/shows the actual <video>
     element (matches recorder.js's mountPreview() division of labor). */
  function previewNativeRecording() {
    if (!lastVideoWebSrc) throw new Error('No recording available to preview — call stopNativeRecording() first.');
    return { filePath: lastVideoFilePath, webSrc: lastVideoWebSrc, mimeType: 'video/mp4' };
  }

  /* ---------------------------------------------------------------------
     saveNativeRecording(filename?) — Milestone 12.

     Uses @capacitor/filesystem's copy() to move the recording out of the
     cache directory (which Android can reclaim) into Directory.Documents
     (persistent, and — per Filesystem's own definitions.d.ts —
     accessible from other apps on Android, appropriate for a video the
     user explicitly chose to keep).

     VERIFICATION NOTE, honestly scoped: confirmed copy()'s real shape
     from definitions.d.ts (`{ from, to, directory?, toDirectory? }`,
     returns Promise<CopyResult>). Confirmed from FilesystemPlugin.kt /
     FilesystemMethodOptions.kt that omitting the source `directory` is a
     handled, legitimate case (produces an "Unresolved" URI with a null
     folder), not a bug — consistent with the documented Capacitor
     Filesystem pattern of treating a directory-less `from` as an
     absolute/file:// path, which is exactly what camera-preview's
     videoFilePath is. NOT traced through @capacitor/synapse's actual URI
     resolution logic (that's a shared internal dependency, several layers
     deeper) — so if this throws or silently fails on-device, that
     resolution step is the first thing to check, not the plugin choice.

     Filename intentionally generic (no match/team names) — app.js's
     `match`/`RM` state is module-internal and not reachable from this
     external script without either exposing it on window (an app.js
     change) or scraping the DOM (fragile). Matching a recording to a
     specific match/tournament/user is explicitly scoped to a LATER
     milestone in the spec (Section 19/21, after upload exists) — this
     function only proves local save works.

     DEVICE-TESTED FIX (Phase 3): the first real on-device attempt failed
     with "Missing parent directory – possibly recursive=false was
     passed or parent directory creation failed." — copy() does not
     create missing destination folders itself. Fixed by calling mkdir()
     with recursive:true first, confirmed against the real
     definitions.d.ts (`recursive?: boolean — "Whether to create any
     missing parent directories as well", default false`), exactly
     matching the error message's own wording. Wrapped in try/catch since
     the plugin's exact behavior when the folder already exists (2nd+
     save) wasn't separately verified — treated as fine either way rather
     than assumed. */
  async function saveNativeRecording(filename) {
    if (!Filesystem) {
      throw new Error('Filesystem plugin not available — is @capacitor/filesystem installed/synced?');
    }
    if (!lastVideoFilePath) {
      throw new Error('No recording to save — call stopNativeRecording() first.');
    }
    const name = filename || ('cricketconnect-native-' + Date.now() + '.mp4');
    // 'DOCUMENTS' is the literal enum value for Directory.Documents, confirmed directly
    // from node_modules/@capacitor/filesystem/dist/esm/definitions.d.ts ("Documents = \"DOCUMENTS\"").
    // Using the string directly since this plain script has no module import access to
    // the Directory enum object itself — Capacitor's plugin call bridge accepts either.
    try {
      await Filesystem.mkdir({ path: 'CricketConnect', directory: 'DOCUMENTS', recursive: true });
    } catch (e) {
      // Expected to throw/reject on the 2nd+ save once the folder already exists —
      // not treated as fatal, only actually missing-and-uncreatable should block save.
    }
    const result = await Filesystem.copy({
      from: lastVideoFilePath, // raw absolute path from camera-preview — no source `directory` given, see verification note above
      to: 'CricketConnect/' + name,
      toDirectory: 'DOCUMENTS'
    });
    return { uri: result && result.uri, filename: name };
  }

  /* ---------------------------------------------------------------------
     resizeNativePreview(opts) — Record Match small-window / live resize.

     opts: { x, y, width, height, paddingBottom? } — same dp units as
     openNativeCameraPreview()'s own width/height, since this calls the
     SAME native plugin, just a different method. Works whether preview-only
     or actively recording; see the patch's own comments (CameraActivity
     .updateRect() / CameraPreview.resizePreview(), in
     patches/@capacitor-community+camera-preview+7.0.2.patch) for why a
     resize does not interrupt an in-progress recording — it only mutates
     the preview view's on-screen LayoutParams, never mCamera/MediaRecorder.

     Requires the patched camera-preview plugin — see patches/ and
     package.json's "postinstall": "patch-package". If CameraPreview
     .resizePreview is missing (patch not applied, e.g. a fresh checkout
     before `npm install` has run), this throws a clear error instead of
     silently doing nothing, so a broken build fails loudly rather than
     just not resizing. */
  async function resizeNativePreview(opts = {}) {
    assertCameraAvailable();
    if (!previewOpen) throw new Error('Call openNativeCameraPreview() before resizeNativePreview().');
    if (typeof CameraPreview.resizePreview !== 'function') {
      throw new Error('CameraPreview.resizePreview is not available — the camera-preview plugin patch (patches/@capacitor-community+camera-preview+7.0.2.patch) was not applied. Run npm install (patch-package runs automatically via postinstall) and rebuild.');
    }
    const resizeOptions = {
      x: opts.x || 0,
      y: opts.y || 0,
      width: opts.width || window.screen.width,
      height: opts.height || window.screen.height
    };
    if (opts.paddingBottom) resizeOptions.paddingBottom = opts.paddingBottom;
    await CameraPreview.resizePreview(resizeOptions);
    return resizeOptions;
  }

  /* closeNativeCameraPreview() — for the "opened the camera, then backed
     out before recording" path (Section 9's "Back -> exit camera safely").
     Separate from stopNativeRecording() because that one requires
     recording === true; this is the no-recording-happened teardown. */
  async function closeNativeCameraPreview() {
    if (!previewOpen) return;
    previewOpen = false;
    document.documentElement.classList.remove(TRANSPARENT_BG_CLASS);
    try { await CameraPreview.stop(); } catch (e) { /* best-effort, matches recorder.js's teardown() */ }
  }

  function isNativePreviewOpen() { return previewOpen; }
  function isNativeRecording() { return recording; }
  function isNativeCameraAvailable() { return !!CameraPreview; }

  /* ---------------------------------------------------------------------
     OVERLAY: NOT YET IMPLEMENTED.

     This file records the raw camera feed only — no score bug, no event
     animations. That is Phase 3's explicit, deliberate scope (spec
     Section 12): prove the native pipeline works first, restore the
     overlay as its own later milestone (Option D in
     RECORD-MATCH-ARCHITECTURE.md). Do not treat a recording made with
     this file as feature-complete against the web recorder — it is a
     known, tracked, visible regression until that milestone lands. */

  /* Repoints "the current recording" at a new file.
     Needed because the overlay burn-in produces a DIFFERENT mp4 from the one
     the camera wrote. Without this, Save would faithfully copy the original,
     un-overlaid video while the preview above it showed the composited one —
     the kind of mismatch nobody notices until the footage matters. */
  function setNativeRecordingFile(filePath) {
    if (!filePath) return null;
    lastVideoFilePath = filePath;
    lastVideoWebSrc = Capacitor.convertFileSrc(filePath);
    return { filePath: lastVideoFilePath, webSrc: lastVideoWebSrc, mimeType: 'video/mp4' };
  }

  window.CricketConnectNativeRecorder = {
    openNativeCameraPreview,
    closeNativeCameraPreview,
    startNativeRecording,
    stopNativeRecording,
    resizeNativePreview,
    previewNativeRecording,
    saveNativeRecording,
    setNativeRecordingFile,
    isNativePreviewOpen,
    isNativeRecording,
    isNativeCameraAvailable
  };
})();
