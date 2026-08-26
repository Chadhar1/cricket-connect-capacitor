package com.cricketconnect.app;

import android.os.Bundle;

import com.cricketconnect.app.overlay.VideoOverlayPlugin;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {

    /**
     * VideoOverlayPlugin is a plugin local to this app rather than an npm
     * package, so Capacitor's automatic plugin discovery (which scans installed
     * node_modules) never sees it. It has to be registered by hand, and this
     * must happen BEFORE super.onCreate() — the bridge is built during
     * onCreate, and anything registered afterwards is invisible to the WebView.
     *
     * Symptom if this line is ever lost: Capacitor.Plugins.VideoOverlay is
     * undefined in JS and the score overlay silently stops being applied.
     */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(VideoOverlayPlugin.class);
        super.onCreate(savedInstanceState);
    }
}
