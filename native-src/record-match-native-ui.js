/* native-src/record-match-native-ui.js — Phase 3: wires recorder-native.js
   into the EXISTING Record Match button, without modifying app.js at all.

   STATUS: IMPLEMENTED — NOT BUILT. This is the piece that turns
   recorder-native.js from a standalone module into something the app
   actually uses when running inside Capacitor.

   HOW THIS AVOIDS TOUCHING app.js (which is protected, copy-only):
   app.js's Record Match flow is driven entirely by a single
   `document.addEventListener('click', ...)` delegator matching
   `[data-action]` elements (confirmed by reading app.js directly this
   phase — two such delegators exist, both registered WITHOUT a capture
   argument, i.e. bubble phase, at lines ~5717 and ~5973). This file
   registers its OWN click listener in the CAPTURE phase
   (addEventListener(..., true)), which always runs before any
   bubble-phase listener regardless of script load order. When the
   "Continue" button inside the Record Match setup screen
   (data-action="rm-continue-to-camera") is clicked AND this is a
   Capacitor build with the native camera plugin available, this file
   calls stopImmediatePropagation() to stop app.js's own handler
   (rmContinueToCamera(), which calls the browser-based
   recorder.openCameraPreview() — the thing that's broken) from ever
   running, and substitutes its own native flow instead. In a plain
   browser or the TWA, window.Capacitor doesn't exist, so this entire
   file no-ops and app.js's original behavior is 100% unchanged.

   SCOPE, matching Phase 3 Section 8 exactly ("do not implement the
   entire Record Match system yet"):
     - Camera permission, preview, start/stop recording, playback
       preview, local save: IMPLEMENTED.
     - Score overlay: explicitly NOT implemented — shown as a visible
       warning in the UI itself (Section 12: "do not hide this
       limitation"), not just a code comment.
     - Upload / match association: NOT implemented — no Supabase video
       bucket exists yet (see RECORD-MATCH-ARCHITECTURE.md Section 8),
       and app.js's `match`/`RM` state (team names, match id) is
       module-internal and not reachable from this external script
       without either an app.js change (needs your approval — protected
       file) or fragile DOM-scraping. Saved filenames are generic
       timestamps for this milestone, not match-aware yet.

   VISUAL DESIGN NOTE: camera-preview's native view has no z-index
   awareness of the DOM — it composites as a layer behind (or in front
   of) the ENTIRE WebView, not behind one specific <div>. So instead of
   trying to carve a precisely-positioned transparent "window" into the
   existing modal dialog (fragile: would need getBoundingClientRect() +
   devicePixelRatio math, timing-sensitive, unverified on this specific
   plugin), the camera-ready and recording screens use a full-viewport
   transparent layer with small floating dark control panels anchored to
   the bottom — closer to how native camera apps actually look, and far
   less likely to render wrong on a device I can't see. The post-
   recording summary screen (camera already torn down by then) uses the
   app's normal opaque modal look instead, since there's no live preview
   to protect at that point. */

(function () {
  if (!window.Capacitor) return; // plain browser / TWA — do nothing, app.js's original behavior is untouched

  function NR() { return window.CricketConnectNativeRecorder; }
  function isNativeCapable() { return !!(NR() && NR().isNativeCameraAvailable()); }

  function escHtml(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
      return ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c];
    });
  }

  // One-time CSS.
  //  - .native-camera-active: the FULL-SCREEN transparent "hole", used only
  //    in expanded/full mode, where nothing underneath needs to stay
  //    visible or tappable (the original, still-used, full-screen
  //    behavior — unchanged).
  //  - #nativeCamSmallHole: a SMALL, precisely positioned transparent
  //    window used in small-window mode, so only that one corner is a
  //    hole and the live-scoring screen underneath stays fully opaque and
  //    tappable everywhere else. Position/size are set inline per-call
  //    (must exactly match whatever rect was just sent to
  //    resizeNativePreview()) — only the visual chrome lives here.
  var style = document.createElement('style');
  style.textContent =
    'html.native-camera-active, html.native-camera-active body, html.native-camera-active #modalRoot {' +
    '  background: transparent !important;' +
    '}' +
    '#nativeCamSmallHole {' +
    '  position: fixed; background: transparent !important; z-index: 998;' +
    '  border-radius: 14px; box-shadow: 0 0 0 2px rgba(89,195,248,0.55), 0 6px 18px rgba(0,0,0,0.45);' +
    '}';
  document.head.appendChild(style);

  function fmtDuration(ms) {
    var s = Math.floor(ms / 1000);
    var mm = String(Math.floor(s / 60)).padStart(2, '0');
    var ss = String(s % 60).padStart(2, '0');
    return mm + ':' + ss;
  }

  /* NOTE ON THE INLINE STYLES BELOW — they are deliberate, not laziness.
     While the native camera preview is open, every layer of the WebView is
     forced transparent (see the .native-camera-active rules) so the SurfaceView
     behind it can show through. Anything drawn on top therefore cannot rely on
     the page stylesheet for its background — it has to carry its own, or it
     would vanish against the live camera feed. Colours are kept in sync with
     theme-capacitor.css by hand; there are only the four below. */
  var CC = {
    panelBg:  'rgba(4,18,33,0.90)',   // --cc-bg-secondary at 90%
    primary:  '#3D7BFF',              // --cc-blue
    danger:   '#D42A22',              // --cc-danger
    secondary:'rgba(89,195,248,0.16)' // --cc-cyan wash
  };

  function panel(html) {
    return '<div style="background:' + CC.panelBg + ';padding:16px;border-radius:14px 14px 0 0;color:#F5F5F0;' +
           'border-top:1px solid rgba(89,195,248,0.24);backdrop-filter:blur(12px);">' + html + '</div>';
  }

  function btn(label, action, variant) {
    var bg = variant === 'danger' ? CC.danger : variant === 'secondary' ? CC.secondary : CC.primary;
    return '<button data-action="' + action + '" style="background:' + bg + ';color:#fff;border:none;border-radius:12px;' +
           'padding:14px 22px;min-height:48px;font-size:15px;font-weight:700;margin:6px;cursor:pointer;">' + escHtml(label) + '</button>';
  }

  function renderOverlayScreen(innerHtml) {
    var root = document.getElementById('modalRoot');
    if (!root) return;
    root.innerHTML = '<div id="nativeCameraOverlay" style="position:fixed;inset:0;z-index:999;background:transparent;display:flex;flex-direction:column;justify-content:flex-end;">' + innerHtml + '</div>';
  }

  function closeOverlay() {
    var root = document.getElementById('modalRoot');
    if (root) root.innerHTML = '';
  }

  /* `title` matters: this same screen is reached both when the camera fails to
     open AND when recording fails to start. Showing "Could not open native
     camera" for a microphone failure that happened at Start Recording — with the
     camera visibly working — sends you looking in the wrong place. */
  function renderErrorScreen(err, title) {
    document.documentElement.classList.remove('native-camera-active');
    removeSmallHole(); // defensive — no-op if small mode was never entered
    restoreActiveScreen();
    var msg = (err && err.message) ? err.message : String(err);
    var root = document.getElementById('modalRoot');
    if (!root) return;
    root.innerHTML =
      '<div class="modal-overlay"><div class="modal">' +
      '<h3>' + escHtml(title || 'Could not open native camera') + '</h3>' +
      '<div class="stat-dim" style="margin-bottom:14px;">' + escHtml(msg) + '</div>' +
      '<button class="btn secondary" data-action="rm-native-cancel">Close</button>' +
      '</div></div>';
  }

  /* -----------------------------------------------------------------------
     BACKGROUNDING FAIL-SAFE

     The installed camera plugin's own CameraActivity.onPause() (its
     source, unmodified by this patch) unconditionally releases the camera
     whenever this Activity pauses — Home button, app switch, incoming
     call, or screen lock — regardless of whether a recording is in
     progress. That is pre-existing plugin behavior, not something this
     file introduces, and it means an in-progress recording can silently
     die or save a corrupt/truncated file if the app is backgrounded at
     the wrong moment. Confirmed by reading the plugin's source directly,
     not assumed.

     This does NOT attempt to survive backgrounding — that would need a
     completely different native pipeline (a foreground service), a much
     bigger separate project, not this one. It only makes the failure
     SAFE instead of silent: the moment the app actually starts leaving
     the foreground while recording, race to stop cleanly (the same
     finalize path a normal Stop tap uses) before onPause's camera release
     can hit an unfinalized MediaRecorder.

     Honest limitation: Android can throttle a backgrounded WebView's JS
     shortly after this fires, so the LATER steps of handleStopRecording
     (the overlay burn-in, which can take seconds) are not guaranteed to
     finish before the OS fully suspends the page. The FIRST step —
     stopNativeRecording()'s native stopRecordVideo() call, which is what
     actually finalizes the video file — is what matters most here and is
     the part most likely to complete, since it is a fast native call
     issued immediately. If the burn-in is cut short, burnInOverlay()'s
     existing try/catch already falls back to the plain, un-overlaid
     recording rather than losing the footage — no new fallback needed. */
  var CapApp = window.Capacitor.Plugins.App;
  if (CapApp && CapApp.addListener) {
    CapApp.addListener('appStateChange', function (state) {
      if (state && state.isActive === false && NR() && NR().isNativeRecording()) {
        renderOverlayScreen(panel(
          '<div style="text-align:center;">The app was backgrounded — stopping and saving what was captured…</div>'
        ));
        handleStopRecording();
      }
    });
  }

  var recordingTimerInterval = null;
  var recordingStartedAt = 0;

  /* Making html/body/#modalRoot transparent only reveals what's BEHIND them
     WITHIN the WebView's own paint order — the live-scoring screen underneath
     (device-tested finding: its individual cards each carry their own opaque
     background-color, so they stayed fully visible with camera bleed-through
     only in the gaps between them, not a clean camera view). The native
     camera view sits behind the ENTIRE WebView, so every opaque layer in
     front of it has to go, not just the modal. Reusing app.js's own existing
     `.hidden` class (confirmed from source: showScreen() toggles it on
     #screen-* elements) on whichever screen is currently visible — same
     mechanism the app already uses for this exact purpose, not a new one. */
  var hiddenScreenEl = null;
  function hideActiveScreen() {
    var screens = document.querySelectorAll('[id^="screen-"]');
    for (var i = 0; i < screens.length; i++) {
      if (!screens[i].classList.contains('hidden')) {
        hiddenScreenEl = screens[i];
        hiddenScreenEl.classList.add('hidden');
        break;
      }
    }
  }
  function restoreActiveScreen() {
    if (hiddenScreenEl) { hiddenScreenEl.classList.remove('hidden'); hiddenScreenEl = null; }
  }

  async function beginNativeFlow() {
    renderOverlayScreen(panel('<div style="text-align:center;">Opening camera…</div>'));
    document.documentElement.classList.add('native-camera-active');
    hideActiveScreen();
    try {
      // 'rear' matches the default a Record Match user would expect (filming the match, not a selfie).
      await NR().openNativeCameraPreview({ position: 'rear' });
    } catch (err) {
      document.documentElement.classList.remove('native-camera-active');
      renderErrorScreen(err); // covers Section 9's denied/permanently-denied/unavailable cases —
      return;                  // the plugin's own start() rejects with a real message in each case,
    }                           // confirmed by reading CameraPreview.java's permission handling.
    renderCameraReadyScreen();
  }

  function renderCameraReadyScreen() {
    renderOverlayScreen(panel(
      '<div style="text-align:center;margin-bottom:10px;font-size:13px;opacity:0.85;">Native camera — the scoreboard is added after you stop recording</div>' +
      '<div style="display:flex;justify-content:center;flex-wrap:wrap;">' +
      btn('● Start Recording', 'rm-native-start-recording') +
      btn('Cancel', 'rm-native-cancel', 'secondary') +
      '</div>'
    ));
  }

  /* -----------------------------------------------------------------------
     SCORE TIMELINE

     The overlay has to change through the video the way the score actually
     changed, so while recording we sample the live match and keep every
     DISTINCT state with the moment it appeared:

         [ { atMs: 0,     state: {…0/0…} },
           { atMs: 8400,  state: {…4/0…} }, … ]

     Sampling beats hooking app.js's scoring functions: they are module-private,
     and this needs no change to the protected file. 400ms is well under the
     time between deliveries, so no ball is missed, and identical consecutive
     samples collapse — a score that holds for 30 seconds is one entry, not 75.
     That is what keeps the render down to a handful of PNGs instead of one per
     frame. */
  var scoreTimeline = [];
  var scoreSampleInterval = null;
  var lastSampleKey = null;

  function OV() { return window.CricketConnectOverlay || null; }

  function sampleScore() {
    var ov = OV();
    if (!ov) return;
    var state = ov.currentOverlayState({});
    if (!state) return;
    var key = ov.overlayStateKey(state);
    if (key === lastSampleKey) return;
    lastSampleKey = key;
    scoreTimeline.push({ atMs: Math.max(0, Date.now() - recordingStartedAt), state: state, key: key });
  }

  function startScoreCapture() {
    scoreTimeline = [];
    lastSampleKey = null;
    sampleScore();                                  // t=0, so the video opens with a scoreboard
    scoreSampleInterval = setInterval(sampleScore, 400);
  }

  function stopScoreCapture() {
    if (scoreSampleInterval) { clearInterval(scoreSampleInterval); scoreSampleInterval = null; }
    sampleScore();                                  // catch a ball scored just before Stop
  }

  /* -----------------------------------------------------------------------
     SMALL WINDOW / LIVE RESIZE

     Lets recording keep running while the scorer uses the normal scoring
     screen — the camera shrinks to a small corner tile instead of taking
     over the whole screen. Uses CameraPreview.resizePreview() (added by
     patches/@capacitor-community+camera-preview+7.0.2.patch), which only
     moves the ALREADY-RUNNING preview's on-screen rect — confirmed from the
     plugin's own source that recording (mCamera/mRecorder/CamcorderProfile
     in CameraActivity.startRecord()) has no dependency on the preview's
     LayoutParams at all — so toggling size never interrupts the recording
     in progress, and never changes the recorded file's resolution either.

     toBack stays true (the plugin's one existing mode) for the whole
     session; only the DOM "hole" that reveals the camera changes shape:
       - small: one small fixed-position transparent div in a corner —
         everything else on screen (the real scoring UI) stays opaque and
         tappable.
       - full: the entire page goes transparent (the original behavior),
         since nothing underneath needs to stay visible in that mode.

     Rect values are plain CSS px, matching how this whole file already
     treats window.screen.width/height as directly usable — same
     convention, not a new one. The small tile's corner offsets are a
     reasonable estimate, not device-verified — confirm on a real phone
     that it clears the status bar / doesn't sit under a notch. */
  var recordingLayoutMode = 'small'; // 'small' | 'full' — meaningful only while recording

  function smallRect() {
    var w = 128, h = 176, margin = 14, topSafe = 64; // topSafe: rough status-bar clearance, verify on device
    return { x: Math.max(0, window.screen.width - w - margin), y: topSafe, width: w, height: h };
  }
  function fullRect() {
    return { x: 0, y: 0, width: window.screen.width, height: window.screen.height };
  }

  function removeSmallHole() {
    var el = document.getElementById('nativeCamSmallHole');
    if (el && el.parentNode) el.parentNode.removeChild(el);
  }

  function renderSmallHole(rect) {
    var el = document.getElementById('nativeCamSmallHole');
    if (!el) {
      el = document.createElement('div');
      el.id = 'nativeCamSmallHole';
      el.setAttribute('data-action', 'rm-native-expand');
      document.body.appendChild(el);
    }
    el.style.left = rect.x + 'px';
    el.style.top = rect.y + 'px';
    el.style.width = rect.width + 'px';
    el.style.height = rect.height + 'px';
  }

  /* Applies a layout mode: resizes the native preview, switches which
     transparency mechanism is active, and shows/hides the real scoring
     screen underneath. Recording itself (and the score-capture interval,
     which reads localStorage independent of any of this) keeps running
     through every call. */
  async function applyRecordingLayout(mode) {
    recordingLayoutMode = mode;
    var rect = mode === 'full' ? fullRect() : smallRect();
    try {
      await NR().resizeNativePreview(rect);
    } catch (err) {
      console.warn('[record-match] resizeNativePreview failed — camera preview size may be stale:', err);
    }
    if (mode === 'full') {
      removeSmallHole();
      document.documentElement.classList.add('native-camera-active');
      hideActiveScreen();
      renderFullControlPanel();
    } else {
      document.documentElement.classList.remove('native-camera-active');
      restoreActiveScreen();
      renderSmallHole(rect);
      renderSmallControlPanel();
    }
    // Both panels render a #nativeRecTimer element, so the existing
    // setInterval in handleStartRecording keeps updating it unchanged
    // across mode switches — no separate timer wiring needed here.
  }

  function handleExpand() { applyRecordingLayout('full'); }
  function handleCollapse() { applyRecordingLayout('small'); }

  /* Compact pill used in small-window mode — deliberately NOT the
     inset:0, full-width renderOverlayScreen() panel, since that would
     cover the scoring screen this mode exists to keep usable. Tapping the
     camera tile itself (renderSmallHole's data-action) also expands. */
  function renderSmallControlPanel() {
    var root = document.getElementById('modalRoot');
    if (!root) return;
    root.innerHTML =
      '<div style="position:fixed;top:14px;left:14px;z-index:999;' +
      'background:' + CC.panelBg + ';color:#F5F5F0;border-radius:12px;padding:8px 12px;' +
      'display:flex;align-items:center;gap:8px;border:1px solid rgba(89,195,248,0.24);' +
      'backdrop-filter:blur(12px);">' +
      '<span style="color:#ef4444;">&#9679;</span>' +
      '<span id="nativeRecTimer" style="font-weight:700;font-size:13px;">00:00</span>' +
      '<button data-action="rm-native-stop-recording" style="background:' + CC.danger + ';color:#fff;border:none;' +
      'border-radius:8px;padding:6px 10px;font-size:12px;font-weight:700;">Stop</button>' +
      '</div>';
  }

  /* Full-screen mode's panel — the original bottom-anchored look, plus a
     Minimize button back to small mode (Stop still works from here too). */
  function renderFullControlPanel() {
    renderOverlayScreen(panel(
      '<div style="text-align:center;margin-bottom:10px;">' +
      '<span style="color:#ef4444;">&#9679;</span> Recording <span id="nativeRecTimer" style="font-weight:700;">00:00</span>' +
      '</div>' +
      '<div style="display:flex;justify-content:center;flex-wrap:wrap;">' +
      btn('&#9632; Stop & Save', 'rm-native-stop-recording', 'danger') +
      btn('&#10530; Minimize', 'rm-native-collapse', 'secondary') +
      '</div>'
    ));
  }

  async function handleStartRecording() {
    try {
      await NR().startNativeRecording({});
    } catch (err) {
      renderErrorScreen(err, 'Could not start recording');
      return;
    }
    recordingStartedAt = Date.now();
    startScoreCapture();
    await applyRecordingLayout('small'); // small window from the moment recording starts, per design
    recordingTimerInterval = setInterval(function () {
      var el = document.getElementById('nativeRecTimer');
      if (el) el.textContent = fmtDuration(Date.now() - recordingStartedAt);
    }, 500);
  }

  async function handleStopRecording() {
    if (recordingTimerInterval) { clearInterval(recordingTimerInterval); recordingTimerInterval = null; }
    stopScoreCapture();
    var totalMs = Date.now() - recordingStartedAt;
    // Unconditional cleanup of BOTH layout modes' own state — Stop is
    // reachable from either small or full mode, and each leaves different
    // DOM/CSS behind (a small hole div vs. a page-wide transparent class).
    recordingLayoutMode = 'small';
    removeSmallHole();
    document.documentElement.classList.remove('native-camera-active');
    restoreActiveScreen();
    renderOverlayScreen(panel('<div style="text-align:center;">Finishing recording…</div>'));
    var result;
    try {
      result = await NR().stopNativeRecording();
    } catch (err) {
      renderErrorScreen(err, 'Could not finish the recording');
      return;
    }

    /* Burn the scoreboard in before showing the result, so what you preview is
       what you would save. If it fails, fall through to the raw recording with
       a plain explanation rather than losing the footage — a video without an
       overlay is still a video worth keeping. */
    try {
      var burned = await burnInOverlay(result, totalMs);
      if (burned) result = burned;
    } catch (err) {
      result.overlayError = (err && err.message) ? err.message : String(err);
    }
    renderSummaryScreen(result);
  }

  /* -----------------------------------------------------------------------
     Renders each distinct score state to a transparent PNG, then hands the
     video plus the PNG timeline to the native MediaCodec + OpenGL compositor
     (VideoOverlayPlugin) to burn in.

     Overlays are rendered at the VIDEO's own pixel size, read from the decoded
     file rather than assumed, so the text is sharp and the layout matches the
     aspect ratio the camera actually produced. */
  async function burnInOverlay(result, totalMs) {
    var ov = OV();
    var VideoOverlay = window.Capacitor && window.Capacitor.Plugins && window.Capacitor.Plugins.VideoOverlay;
    var Filesystem = window.Capacitor && window.Capacitor.Plugins && window.Capacitor.Plugins.Filesystem;

    if (!ov) { result.overlayError = 'Overlay module unavailable — recording kept without a scoreboard.'; return null; }
    if (!VideoOverlay) { result.overlayError = 'Native overlay plugin not found — did the app get a full rebuild after adding it?'; return null; }
    if (!Filesystem) { result.overlayError = 'Filesystem plugin unavailable — cannot stage the overlay images.'; return null; }
    if (!scoreTimeline.length) { result.overlayError = 'No live match was being scored, so there was no scoreboard to draw.'; return null; }

    renderOverlayScreen(panel('<div style="text-align:center;">Adding the scoreboard… <span id="ccOverlayPct">0%</span></div>'));

    var progressHandle = VideoOverlay.addListener('overlayProgress', function (data) {
      var el = document.getElementById('ccOverlayPct');
      if (el && data && typeof data.percent === 'number') el.textContent = data.percent + '%';
    });

    try {
      var dims = await videoDimensions(result.webSrc);
      var templateId = (ov.TEMPLATES && ov.TEMPLATES[0] && ov.TEMPLATES[0].id) || 'classic';

      var spans = [];
      for (var i = 0; i < scoreTimeline.length; i++) {
        var entry = scoreTimeline[i];
        var next = scoreTimeline[i + 1];
        var dataUrl = ov.renderOverlayPng(templateId, entry.state, dims.width, dims.height);
        var written = await Filesystem.writeFile({
          path: 'cc-overlay-' + Date.now() + '-' + i + '.png',
          data: dataUrl.split(',')[1],           // strip the data: prefix; Filesystem wants raw base64
          directory: 'CACHE'
        });
        spans.push({
          path: written && written.uri ? written.uri : null,
          startMs: entry.atMs,
          endMs: next ? next.atMs : Math.max(totalMs, entry.atMs + 1000)
        });
      }

      var out = await VideoOverlay.burnIn({ videoPath: result.filePath, overlays: spans });
      if (!out || !out.outputPath) return null;

      /* Point Save at the composited file, not the raw camera output —
         otherwise the preview shows the scoreboard and the saved file doesn't. */
      NR().setNativeRecordingFile(out.outputPath);

      return {
        filePath: out.outputPath,
        webSrc: window.Capacitor.convertFileSrc(out.outputPath),
        mimeType: 'video/mp4',
        overlayApplied: !!out.overlayApplied,
        overlayStates: spans.length
      };
    } finally {
      try {
        var h = await progressHandle;
        if (h && h.remove) h.remove();
      } catch (e) {}
    }
  }

  /* Reads the real pixel dimensions out of the recorded file. Guessing from
     window.screen would be wrong whenever the encoder picks a different
     profile than the preview size. */
  function videoDimensions(src) {
    return new Promise(function (resolve) {
      var v = document.createElement('video');
      var done = false;
      function finish(w, h) {
        if (done) return;
        done = true;
        resolve({ width: (w || 1280) & ~1, height: (h || 720) & ~1 });
      }
      v.preload = 'metadata';
      v.onloadedmetadata = function () { finish(v.videoWidth, v.videoHeight); };
      v.onerror = function () { finish(1280, 720); };
      setTimeout(function () { finish(v.videoWidth, v.videoHeight); }, 4000);
      v.src = src;
    });
  }

  function renderSummaryScreen(result) {
    var root = document.getElementById('modalRoot');
    if (!root) return;

    /* Report what actually happened to THIS recording rather than a fixed
       message. Claiming the scoreboard is there when the burn-in silently
       failed would be worse than saying nothing. */
    var note;
    if (result.overlayApplied) {
      note = '<div class="stat-dim" style="margin-bottom:10px;">Scoreboard burned in — ' +
             (result.overlayStates || 0) + ' score change' + (result.overlayStates === 1 ? '' : 's') +
             ' across the clip. This video lives only on this device until you tap Save — nothing is uploaded.</div>';
    } else if (result.overlayError) {
      note = '<div class="stat-dim" style="margin-bottom:10px;">⚠ Saved without a scoreboard: ' +
             escHtml(result.overlayError) +
             '<br>The footage itself is fine. This video lives only on this device until you tap Save — nothing is uploaded.</div>';
    } else {
      note = '<div class="stat-dim" style="margin-bottom:10px;">This recording has no score overlay burned in. It lives only on this device until you tap Save — nothing is uploaded.</div>';
    }

    root.innerHTML =
      '<div class="modal-overlay"><div class="modal">' +
      '<h3>Recording complete (native)</h3>' +
      note +
      '<video src="' + escHtml(result.webSrc) + '" controls style="width:100%;border-radius:10px;margin-bottom:14px;background:#000;"></video>' +
      '<div class="action-row">' +
      '<button class="btn" data-action="rm-native-save-video">Save to device</button>' +
      '<button class="btn danger" data-action="rm-native-discard-video">Discard</button>' +
      '</div>' +
      '<div id="nativeSaveStatus" class="stat-dim" style="margin-top:10px;"></div>' +
      '</div></div>';
  }

  async function handleSaveVideo() {
    var statusEl = document.getElementById('nativeSaveStatus');
    if (statusEl) statusEl.textContent = 'Saving…';
    try {
      var r = await NR().saveNativeRecording();
      if (statusEl) statusEl.textContent = 'Saved: ' + (r.uri || r.filename);
    } catch (err) {
      if (statusEl) statusEl.textContent = 'Save failed: ' + ((err && err.message) ? err.message : String(err));
    }
  }

  function handleDiscardVideo() {
    closeOverlay();
  }

  async function handleCancel() {
    document.documentElement.classList.remove('native-camera-active');
    removeSmallHole(); // defensive — no-op if small mode was never entered
    restoreActiveScreen();
    try { if (NR().isNativePreviewOpen()) await NR().closeNativeCameraPreview(); } catch (e) { /* best-effort */ }
    closeOverlay();
  }

  document.addEventListener('click', function (e) {
    var el = e.target.closest('[data-action]');
    if (!el) return;
    var a = el.dataset.action;

    if (a === 'rm-continue-to-camera') {
      if (!isNativeCapable()) return; // not Capacitor / plugin missing — let app.js's own handler run, unchanged
      e.preventDefault();
      e.stopImmediatePropagation();
      beginNativeFlow();
      return;
    }
    // Everything below only exists inside the overlay this file itself renders —
    // app.js's delegator has never heard of these action names — but intercepting
    // here too keeps all native-flow control logic in one place.
    if (a === 'rm-native-start-recording') { e.preventDefault(); e.stopImmediatePropagation(); handleStartRecording(); return; }
    if (a === 'rm-native-stop-recording') { e.preventDefault(); e.stopImmediatePropagation(); handleStopRecording(); return; }
    if (a === 'rm-native-expand') { e.preventDefault(); e.stopImmediatePropagation(); handleExpand(); return; }
    if (a === 'rm-native-collapse') { e.preventDefault(); e.stopImmediatePropagation(); handleCollapse(); return; }
    if (a === 'rm-native-save-video') { e.preventDefault(); e.stopImmediatePropagation(); handleSaveVideo(); return; }
    if (a === 'rm-native-discard-video') { e.preventDefault(); e.stopImmediatePropagation(); handleDiscardVideo(); return; }
    if (a === 'rm-native-cancel') { e.preventDefault(); e.stopImmediatePropagation(); handleCancel(); return; }
  }, true); // capture phase — verified necessary: both of app.js's own click delegators are bubble-phase.
})();
