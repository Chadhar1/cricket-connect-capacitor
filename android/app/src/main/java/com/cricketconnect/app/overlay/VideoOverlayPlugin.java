package com.cricketconnect.app.overlay;

import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Capacitor bridge for the score-overlay burn-in.
 *
 * JS side (recorder-native.js) calls:
 *
 *   await Capacitor.Plugins.VideoOverlay.burnIn({
 *     videoPath: '/data/.../cache/videoTmp.mp4',
 *     outputPath: '/data/.../cache/final.mp4',      // optional
 *     bitRate: 8000000,                             // optional
 *     overlays: [ { path: '/…/ov0.png', startMs: 0, endMs: 4200 }, … ]
 *   });
 *
 * and listens for 'overlayProgress' events while it runs.
 *
 * WHY IT RUNS OFF THE MAIN THREAD
 * -------------------------------
 * Compositing is a decode/encode loop over every frame — seconds to minutes of
 * work. On the main thread it would trigger an ANR long before finishing, so the
 * whole job runs on its own thread and the PluginCall is resolved from there.
 *
 * WHY THE OUTPUT IS WRITTEN TO A TEMP FILE FIRST
 * ----------------------------------------------
 * If encoding dies halfway, a partially written MP4 at the final path is worse
 * than no file: it looks like a real recording and plays as garbage. The temp
 * file is only renamed into place after the compositor returns cleanly.
 */
@CapacitorPlugin(name = "VideoOverlay")
public class VideoOverlayPlugin extends Plugin {

    @PluginMethod
    public void burnIn(final PluginCall call) {
        final String videoPath = normalise(call.getString("videoPath"));
        if (videoPath == null || videoPath.isEmpty()) {
            call.reject("videoPath is required");
            return;
        }
        final File input = new File(videoPath);
        if (!input.exists()) {
            call.reject("Video not found: " + videoPath);
            return;
        }

        String requestedOutput = normalise(call.getString("outputPath"));
        if (requestedOutput == null || requestedOutput.isEmpty()) {
            requestedOutput = new File(getContext().getCacheDir(),
                "cricketconnect-overlay-" + System.currentTimeMillis() + ".mp4").getAbsolutePath();
        }
        final String outputPath = requestedOutput;
        final int bitRate = call.getInt("bitRate", 0);

        final List<OverlayCompositor.OverlaySpan> spans = new ArrayList<>();
        JSArray overlays = call.getArray("overlays");
        if (overlays != null) {
            try {
                List<JSONObject> list = overlays.toList();
                for (JSONObject o : list) {
                    String p = normalise(o.optString("path", null));
                    if (p == null || p.isEmpty()) continue;
                    spans.add(new OverlayCompositor.OverlaySpan(
                        p,
                        o.optLong("startMs", 0),
                        o.optLong("endMs", 0)
                    ));
                }
            } catch (Exception e) {
                call.reject("Could not read the overlays array: " + e.getMessage());
                return;
            }
        }

        /* No overlays is a legitimate call, not an error — it means "there was
           no score to show", and the honest result is the untouched video
           rather than a failure the user has to interpret. */
        final boolean hasOverlays = !spans.isEmpty();

        new Thread(new Runnable() {
            @Override
            public void run() {
                File temp = new File(outputPath + ".part");
                try {
                    if (!hasOverlays) {
                        JSObject result = new JSObject();
                        result.put("outputPath", videoPath);
                        result.put("overlayApplied", false);
                        result.put("reason", "No overlay frames were supplied — returning the original recording.");
                        resolveOnMain(call, result);
                        return;
                    }

                    OverlayCompositor compositor = new OverlayCompositor(spans);
                    compositor.composite(videoPath, temp.getAbsolutePath(), bitRate,
                        new OverlayCompositor.ProgressListener() {
                            @Override
                            public void onProgress(int percent) {
                                JSObject data = new JSObject();
                                data.put("percent", percent);
                                notifyListeners("overlayProgress", data);
                            }
                        });

                    File finalFile = new File(outputPath);
                    if (finalFile.exists() && !finalFile.delete()) {
                        throw new RuntimeException("Could not replace existing file at " + outputPath);
                    }
                    if (!temp.renameTo(finalFile)) {
                        throw new RuntimeException("Could not move the finished video into place");
                    }

                    JSObject result = new JSObject();
                    result.put("outputPath", finalFile.getAbsolutePath());
                    result.put("uri", Uri.fromFile(finalFile).toString());
                    result.put("overlayApplied", true);
                    resolveOnMain(call, result);

                } catch (Throwable t) {
                    if (temp.exists()) temp.delete();   // never leave a half-written video behind
                    final String msg = t.getMessage() != null ? t.getMessage() : t.toString();
                    new Handler(Looper.getMainLooper()).post(new Runnable() {
                        @Override
                        public void run() {
                            call.reject("Overlay rendering failed: " + msg);
                        }
                    });
                }
            }
        }, "cc-overlay-compositor").start();
    }

    private void resolveOnMain(final PluginCall call, final JSObject result) {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                call.resolve(result);
            }
        });
    }

    /**
     * The camera plugin and Filesystem hand back paths in several shapes
     * (bare path, file://, or a Capacitor-converted localhost URL). Everything
     * downstream — MediaExtractor, BitmapFactory — wants a plain filesystem
     * path, so normalise once here rather than at every call site.
     */
    private String normalise(String path) {
        if (path == null) return null;
        String p = path.trim();
        if (p.startsWith("file://")) {
            p = Uri.parse(p).getPath();
        } else if (p.startsWith("http://localhost/_capacitor_file_")) {
            p = p.substring("http://localhost/_capacitor_file_".length());
        } else if (p.startsWith("https://localhost/_capacitor_file_")) {
            p = p.substring("https://localhost/_capacitor_file_".length());
        }
        return p;
    }
}
