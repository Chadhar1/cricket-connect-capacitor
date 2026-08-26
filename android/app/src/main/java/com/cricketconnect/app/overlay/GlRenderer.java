package com.cricketconnect.app.overlay;

import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * The GL half of the Record Match overlay pipeline.
 *
 * WHAT IT DOES
 * ------------
 * Owns a single EGL context whose window surface is the ENCODER's input
 * surface. Everything drawn here lands straight in the encoder — no pixel ever
 * makes the round trip to the CPU, which is the whole reason for doing this on
 * the GPU rather than with a bitmap-per-frame approach.
 *
 * Two draws per frame, in order:
 *   1. the decoded camera frame, which arrives as a GL_TEXTURE_EXTERNAL_OES
 *      texture fed by a SurfaceTexture (this is the only way to get MediaCodec
 *      decoder output into GL without copying it);
 *   2. the CricketConnect score overlay, an ordinary RGBA texture uploaded from
 *      a Bitmap, blended on top with premultiplied-alpha blending.
 *
 * WHY THE OES TEXTURE NEEDS ITS OWN SHADER
 * ----------------------------------------
 * External textures are not sampler2D. They need the GL_OES_EGL_image_external
 * extension and samplerExternalOES, and they carry a transform matrix from
 * SurfaceTexture that encodes crop and orientation. Sampling one with a normal
 * sampler2D shader silently gives you garbage, so there are deliberately two
 * separate programs below rather than one shared one.
 */
public class GlRenderer {

    private static final int EGL_RECORDABLE_ANDROID = 0x3142;

    private EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY;
    private EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
    private EGLSurface eglSurface = EGL14.EGL_NO_SURFACE;

    private final int width;
    private final int height;

    // Camera-frame (external OES) program
    private int oesProgram;
    private int oesPosLoc, oesTexLoc, oesMatrixLoc;
    private int oesTextureId;

    // Overlay (regular 2D) program
    private int quadProgram;
    private int quadPosLoc, quadTexLoc;
    private int overlayTextureId = -1;
    private Object overlayTextureKey = null; // which overlay bitmap is currently uploaded

    private SurfaceTexture surfaceTexture;
    private Surface decoderSurface;
    private final float[] stMatrix = new float[16];
    private final Object frameSyncObject = new Object();
    private boolean frameAvailable;

    private FloatBuffer fullScreenQuad;
    private FloatBuffer texCoords;
    private FloatBuffer flippedTexCoords;

    private static final String VERTEX_SHADER =
        "attribute vec4 aPosition;\n" +
        "attribute vec2 aTexCoord;\n" +
        "varying vec2 vTexCoord;\n" +
        "void main() {\n" +
        "  gl_Position = aPosition;\n" +
        "  vTexCoord = aTexCoord;\n" +
        "}\n";

    // Applies SurfaceTexture's transform so rotation/crop from the recorder is honoured.
    private static final String VERTEX_SHADER_OES =
        "attribute vec4 aPosition;\n" +
        "attribute vec2 aTexCoord;\n" +
        "uniform mat4 uSTMatrix;\n" +
        "varying vec2 vTexCoord;\n" +
        "void main() {\n" +
        "  gl_Position = aPosition;\n" +
        "  vTexCoord = (uSTMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;\n" +
        "}\n";

    private static final String FRAGMENT_SHADER_OES =
        "#extension GL_OES_EGL_image_external : require\n" +
        "precision mediump float;\n" +
        "varying vec2 vTexCoord;\n" +
        "uniform samplerExternalOES uTexture;\n" +
        "void main() {\n" +
        "  gl_FragColor = texture2D(uTexture, vTexCoord);\n" +
        "}\n";

    private static final String FRAGMENT_SHADER_2D =
        "precision mediump float;\n" +
        "varying vec2 vTexCoord;\n" +
        "uniform sampler2D uTexture;\n" +
        "void main() {\n" +
        "  gl_FragColor = texture2D(uTexture, vTexCoord);\n" +
        "}\n";

    public GlRenderer(Surface encoderInputSurface, int width, int height) {
        this.width = width;
        this.height = height;
        initEgl(encoderInputSurface);
        initGl();
    }

    // ---------------------------------------------------------------- EGL

    private void initEgl(Surface encoderInputSurface) {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) throw new RuntimeException("eglGetDisplay failed");

        int[] version = new int[2];
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            throw new RuntimeException("eglInitialize failed");
        }

        /* EGL_RECORDABLE_ANDROID is what tells the driver this surface feeds a
           video encoder. Without it some devices hand back a config the encoder
           cannot consume and you get a black or corrupt output file. */
        int[] attribList = {
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE
        };
        EGLConfig[] configs = new EGLConfig[1];
        int[] numConfigs = new int[1];
        if (!EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, 1, numConfigs, 0)
            || numConfigs[0] <= 0) {
            throw new RuntimeException("eglChooseConfig failed — no recordable ES2 config");
        }

        int[] contextAttribs = { EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE };
        eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, contextAttribs, 0);
        checkEglError("eglCreateContext");

        int[] surfaceAttribs = { EGL14.EGL_NONE };
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, configs[0], encoderInputSurface, surfaceAttribs, 0);
        checkEglError("eglCreateWindowSurface");

        makeCurrent();
    }

    public void makeCurrent() {
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            throw new RuntimeException("eglMakeCurrent failed");
        }
    }

    private void checkEglError(String op) {
        int error = EGL14.eglGetError();
        if (error != EGL14.EGL_SUCCESS) {
            throw new RuntimeException(op + ": EGL error 0x" + Integer.toHexString(error));
        }
    }

    // ----------------------------------------------------------------- GL

    private void initGl() {
        oesProgram = buildProgram(VERTEX_SHADER_OES, FRAGMENT_SHADER_OES);
        oesPosLoc = GLES20.glGetAttribLocation(oesProgram, "aPosition");
        oesTexLoc = GLES20.glGetAttribLocation(oesProgram, "aTexCoord");
        oesMatrixLoc = GLES20.glGetUniformLocation(oesProgram, "uSTMatrix");

        quadProgram = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER_2D);
        quadPosLoc = GLES20.glGetAttribLocation(quadProgram, "aPosition");
        quadTexLoc = GLES20.glGetAttribLocation(quadProgram, "aTexCoord");

        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        oesTextureId = textures[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId);
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        surfaceTexture = new SurfaceTexture(oesTextureId);
        surfaceTexture.setOnFrameAvailableListener(new SurfaceTexture.OnFrameAvailableListener() {
            @Override
            public void onFrameAvailable(SurfaceTexture st) {
                synchronized (frameSyncObject) {
                    frameAvailable = true;
                    frameSyncObject.notifyAll();
                }
            }
        });
        decoderSurface = new Surface(surfaceTexture);

        fullScreenQuad = floats(new float[]{ -1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f });
        texCoords = floats(new float[]{ 0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f });
        /* The overlay bitmap is drawn with V flipped: Android bitmaps are
           top-left origin, GL texture space is bottom-left. Sharing one
           coordinate buffer with the camera pass would render the score
           upside down. */
        flippedTexCoords = floats(new float[]{ 0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f });

        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glDisable(GLES20.GL_CULL_FACE);
    }

    private static FloatBuffer floats(float[] data) {
        FloatBuffer fb = ByteBuffer.allocateDirect(data.length * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer();
        fb.put(data).position(0);
        return fb;
    }

    private static int buildProgram(String vertexSrc, String fragmentSrc) {
        int vs = compile(GLES20.GL_VERTEX_SHADER, vertexSrc);
        int fs = compile(GLES20.GL_FRAGMENT_SHADER, fragmentSrc);
        int program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vs);
        GLES20.glAttachShader(program, fs);
        GLES20.glLinkProgram(program);
        int[] linked = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0);
        if (linked[0] != GLES20.GL_TRUE) {
            String log = GLES20.glGetProgramInfoLog(program);
            GLES20.glDeleteProgram(program);
            throw new RuntimeException("Program link failed: " + log);
        }
        GLES20.glDeleteShader(vs);
        GLES20.glDeleteShader(fs);
        return program;
    }

    private static int compile(int type, String src) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, src);
        GLES20.glCompileShader(shader);
        int[] compiled = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] != GLES20.GL_TRUE) {
            String log = GLES20.glGetShaderInfoLog(shader);
            GLES20.glDeleteShader(shader);
            throw new RuntimeException("Shader compile failed: " + log);
        }
        return shader;
    }

    // ------------------------------------------------------------ Frames

    /** The Surface the video decoder should be configured to render into. */
    public Surface getDecoderSurface() {
        return decoderSurface;
    }

    /**
     * Blocks until the decoder has pushed a frame into our SurfaceTexture.
     * Times out rather than hanging forever — a decoder that stops producing
     * would otherwise wedge the whole export with no diagnostic.
     */
    public void awaitNewFrame(long timeoutMs) {
        synchronized (frameSyncObject) {
            while (!frameAvailable) {
                try {
                    frameSyncObject.wait(timeoutMs);
                    if (!frameAvailable) {
                        throw new RuntimeException("Timed out waiting for a decoded frame");
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(ie);
                }
            }
            frameAvailable = false;
        }
        surfaceTexture.updateTexImage();
        surfaceTexture.getTransformMatrix(stMatrix);
    }

    /**
     * Draws the current camera frame, then the supplied overlay bitmap on top.
     * Pass null for overlay to emit the frame untouched (e.g. before the first
     * ball, when there is no score to show yet).
     */
    public void drawFrame(Bitmap overlay, Object overlayKey) {
        GLES20.glViewport(0, 0, width, height);
        GLES20.glClearColor(0f, 0f, 0f, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        // --- camera frame ---
        GLES20.glUseProgram(oesProgram);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId);
        GLES20.glUniformMatrix4fv(oesMatrixLoc, 1, false, stMatrix, 0);
        drawQuad(oesPosLoc, oesTexLoc, texCoords);

        // --- score overlay ---
        if (overlay != null && !overlay.isRecycled()) {
            uploadOverlayIfChanged(overlay, overlayKey);
            GLES20.glEnable(GLES20.GL_BLEND);
            /* The bitmap comes from Android's Canvas, which produces
               PREMULTIPLIED alpha. Using SRC_ALPHA here instead of ONE would
               double-apply alpha and leave dark fringes around the text. */
            GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA);
            GLES20.glUseProgram(quadProgram);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTextureId);
            drawQuad(quadPosLoc, quadTexLoc, flippedTexCoords);
            GLES20.glDisable(GLES20.GL_BLEND);
        }
    }

    private void drawQuad(int posLoc, int texLoc, FloatBuffer coords) {
        fullScreenQuad.position(0);
        GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 0, fullScreenQuad);
        GLES20.glEnableVertexAttribArray(posLoc);
        coords.position(0);
        GLES20.glVertexAttribPointer(texLoc, 2, GLES20.GL_FLOAT, false, 0, coords);
        GLES20.glEnableVertexAttribArray(texLoc);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(posLoc);
        GLES20.glDisableVertexAttribArray(texLoc);
    }

    /**
     * Re-uploads the overlay texture only when the overlay actually changed.
     * The score changes once a ball, not once a frame, so at 30fps this skips
     * the glTexImage2D upload for the overwhelming majority of frames.
     */
    private void uploadOverlayIfChanged(Bitmap overlay, Object key) {
        if (overlayTextureId != -1 && key != null && key.equals(overlayTextureKey)) return;

        if (overlayTextureId == -1) {
            int[] tex = new int[1];
            GLES20.glGenTextures(1, tex, 0);
            overlayTextureId = tex[0];
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTextureId);
            GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        } else {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTextureId);
        }
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, overlay, 0);
        overlayTextureKey = key;
    }

    /** Stamps the frame's timestamp onto the encoder surface. Nanoseconds. */
    public void setPresentationTime(long nsecs) {
        EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, nsecs);
    }

    /** Hands the finished frame to the encoder. */
    public boolean swapBuffers() {
        return EGL14.eglSwapBuffers(eglDisplay, eglSurface);
    }

    // ---------------------------------------------------------- Teardown

    public void release() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface);
            if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext);
            EGL14.eglReleaseThread();
            EGL14.eglTerminate(eglDisplay);
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY;
        eglContext = EGL14.EGL_NO_CONTEXT;
        eglSurface = EGL14.EGL_NO_SURFACE;

        if (decoderSurface != null) { decoderSurface.release(); decoderSurface = null; }
        if (surfaceTexture != null) { surfaceTexture.release(); surfaceTexture = null; }
    }
}
