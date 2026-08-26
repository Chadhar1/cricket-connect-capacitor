package com.cricketconnect.app.overlay;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.util.Log;
import android.view.Surface;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Burns the CricketConnect score overlay into a recorded MP4.
 *
 *   input.mp4 ── MediaExtractor ── decoder ──▶ GL (frame + overlay) ──▶ encoder ── MediaMuxer ──▶ output.mp4
 *                                    │                                                  ▲
 *                                    └──────────── audio track copied verbatim ─────────┘
 *
 * DESIGN NOTES
 * ------------
 * 1. AUDIO IS NEVER RE-ENCODED. The original audio track is remuxed sample for
 *    sample. Re-encoding would cost time and quality for no benefit — we are
 *    not changing the audio at all.
 *
 * 2. OVERLAYS ARE A TIMELINE, NOT A FRAME SEQUENCE. The score changes roughly
 *    once a ball, not 30 times a second, so JS renders one PNG per distinct
 *    score state with a start/end time. A two-minute clip is typically a dozen
 *    PNGs rather than 3,600. Bitmaps are cached and the GPU texture is only
 *    re-uploaded when the active overlay actually changes.
 *
 * 3. THE OUTPUT IS WRITTEN TO A TEMP FILE AND ONLY THEN MOVED INTO PLACE by the
 *    caller, so a failure midway cannot leave a half-written video looking like
 *    a real one.
 */
public class OverlayCompositor {

    private static final String TAG = "OverlayCompositor";
    private static final String OUTPUT_MIME = "video/avc";
    private static final int TIMEOUT_US = 10000;
    private static final int IFRAME_INTERVAL = 1;

    /** One overlay image and the slice of the video it covers. */
    public static class OverlaySpan {
        public final String path;
        public final long startMs;
        public final long endMs;

        public OverlaySpan(String path, long startMs, long endMs) {
            this.path = path;
            this.startMs = startMs;
            this.endMs = endMs;
        }
    }

    public interface ProgressListener {
        void onProgress(int percent);
    }

    private final List<OverlaySpan> spans;
    private final Map<String, Bitmap> bitmapCache = new HashMap<>();

    public OverlayCompositor(List<OverlaySpan> spans) {
        this.spans = spans != null ? spans : new ArrayList<OverlaySpan>();
    }

    public void composite(String inputPath, String outputPath, int bitRate, ProgressListener listener)
        throws IOException {

        MediaExtractor videoExtractor = null;
        MediaExtractor audioExtractor = null;
        MediaCodec decoder = null;
        MediaCodec encoder = null;
        MediaMuxer muxer = null;
        GlRenderer renderer = null;

        try {
            videoExtractor = new MediaExtractor();
            videoExtractor.setDataSource(inputPath);
            int videoTrack = selectTrack(videoExtractor, "video/");
            if (videoTrack < 0) throw new IOException("No video track found in " + inputPath);
            videoExtractor.selectTrack(videoTrack);
            MediaFormat inputFormat = videoExtractor.getTrackFormat(videoTrack);

            int width = inputFormat.getInteger(MediaFormat.KEY_WIDTH);
            int height = inputFormat.getInteger(MediaFormat.KEY_HEIGHT);
            long durationUs = inputFormat.containsKey(MediaFormat.KEY_DURATION)
                ? inputFormat.getLong(MediaFormat.KEY_DURATION) : 0;
            int frameRate = inputFormat.containsKey(MediaFormat.KEY_FRAME_RATE)
                ? inputFormat.getInteger(MediaFormat.KEY_FRAME_RATE) : 30;

            /* Rotation must be handled deliberately. The camera writes a
               rotation hint into the container rather than rotating pixels. We
               render pixels, so we swap width/height for a quarter-turn video
               and then do NOT copy the hint to the output — otherwise the
               player would rotate our already-upright frames a second time. */
            int rotation = inputFormat.containsKey(MediaFormat.KEY_ROTATION)
                ? inputFormat.getInteger(MediaFormat.KEY_ROTATION) : 0;
            int outWidth = (rotation == 90 || rotation == 270) ? height : width;
            int outHeight = (rotation == 90 || rotation == 270) ? width : height;
            // H.264 encoders require even dimensions.
            outWidth = outWidth & ~1;
            outHeight = outHeight & ~1;

            MediaFormat outputFormat = MediaFormat.createVideoFormat(OUTPUT_MIME, outWidth, outHeight);
            outputFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            outputFormat.setInteger(MediaFormat.KEY_BIT_RATE,
                bitRate > 0 ? bitRate : estimateBitRate(outWidth, outHeight, frameRate));
            outputFormat.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate);
            outputFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_INTERVAL);

            encoder = MediaCodec.createEncoderByType(OUTPUT_MIME);
            encoder.configure(outputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            Surface encoderInput = encoder.createInputSurface();
            encoder.start();

            renderer = new GlRenderer(encoderInput, outWidth, outHeight);

            decoder = MediaCodec.createDecoderByType(inputFormat.getString(MediaFormat.KEY_MIME));
            /* The decoder renders into our GL SurfaceTexture, not into
               ByteBuffers — that is what keeps frames on the GPU. */
            decoder.configure(inputFormat, renderer.getDecoderSurface(), null, 0);
            decoder.start();

            muxer = new MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            // Audio is optional — a clip with no mic track still composites fine.
            int muxerAudioTrack = -1;
            audioExtractor = new MediaExtractor();
            audioExtractor.setDataSource(inputPath);
            int audioTrack = selectTrack(audioExtractor, "audio/");
            MediaFormat audioFormat = null;
            if (audioTrack >= 0) {
                audioExtractor.selectTrack(audioTrack);
                audioFormat = audioExtractor.getTrackFormat(audioTrack);
            }

            int muxerVideoTrack = -1;
            boolean muxerStarted = false;
            boolean inputDone = false;
            boolean decoderDone = false;
            boolean encoderDone = false;

            MediaCodec.BufferInfo encInfo = new MediaCodec.BufferInfo();
            MediaCodec.BufferInfo decInfo = new MediaCodec.BufferInfo();
            int lastReportedPercent = -1;

            while (!encoderDone) {

                // ---- feed the decoder from the file ----
                if (!inputDone) {
                    int inIndex = decoder.dequeueInputBuffer(TIMEOUT_US);
                    if (inIndex >= 0) {
                        ByteBuffer buf = decoder.getInputBuffer(inIndex);
                        int size = videoExtractor.readSampleData(buf, 0);
                        if (size < 0) {
                            decoder.queueInputBuffer(inIndex, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            decoder.queueInputBuffer(inIndex, 0, size,
                                videoExtractor.getSampleTime(), 0);
                            videoExtractor.advance();
                        }
                    }
                }

                // ---- decoder -> GL -> encoder ----
                if (!decoderDone) {
                    int outIndex = decoder.dequeueOutputBuffer(decInfo, TIMEOUT_US);
                    if (outIndex >= 0) {
                        boolean render = decInfo.size > 0;
                        long ptsUs = decInfo.presentationTimeUs;
                        decoder.releaseOutputBuffer(outIndex, render);
                        if (render) {
                            renderer.awaitNewFrame(5000);
                            String key = spanPathAt(ptsUs / 1000);
                            renderer.drawFrame(bitmapFor(key), key);
                            renderer.setPresentationTime(ptsUs * 1000);
                            renderer.swapBuffers();

                            if (listener != null && durationUs > 0) {
                                int pct = (int) Math.min(99, (ptsUs * 100) / durationUs);
                                if (pct != lastReportedPercent) {
                                    lastReportedPercent = pct;
                                    listener.onProgress(pct);
                                }
                            }
                        }
                        if ((decInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            decoderDone = true;
                            encoder.signalEndOfInputStream();
                        }
                    }
                }

                // ---- drain the encoder into the muxer ----
                int encIndex = encoder.dequeueOutputBuffer(encInfo, TIMEOUT_US);
                if (encIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (muxerStarted) throw new IllegalStateException("Encoder format changed twice");
                    muxerVideoTrack = muxer.addTrack(encoder.getOutputFormat());
                    if (audioFormat != null) muxerAudioTrack = muxer.addTrack(audioFormat);
                    muxer.start();
                    muxerStarted = true;
                } else if (encIndex >= 0) {
                    ByteBuffer encoded = encoder.getOutputBuffer(encIndex);
                    /* CODEC_CONFIG carries SPS/PPS, which MediaMuxer already
                       took from the output format. Writing it as a sample too
                       produces a file some players refuse. */
                    if ((encInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        encInfo.size = 0;
                    }
                    if (encInfo.size > 0 && muxerStarted) {
                        encoded.position(encInfo.offset);
                        encoded.limit(encInfo.offset + encInfo.size);
                        muxer.writeSampleData(muxerVideoTrack, encoded, encInfo);
                    }
                    encoder.releaseOutputBuffer(encIndex, false);
                    if ((encInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        encoderDone = true;
                    }
                }
            }

            // ---- copy the audio track through untouched ----
            if (muxerAudioTrack >= 0 && audioFormat != null) {
                copyAudio(audioExtractor, muxer, muxerAudioTrack, audioFormat);
            }

            if (listener != null) listener.onProgress(100);

        } finally {
            // Ordered teardown; each guarded so one failure cannot mask the rest.
            if (renderer != null) { try { renderer.release(); } catch (Exception e) { Log.w(TAG, "renderer", e); } }
            if (decoder != null)  { try { decoder.stop(); decoder.release(); } catch (Exception e) { Log.w(TAG, "decoder", e); } }
            if (encoder != null)  { try { encoder.stop(); encoder.release(); } catch (Exception e) { Log.w(TAG, "encoder", e); } }
            if (muxer != null)    { try { muxer.stop(); muxer.release(); } catch (Exception e) { Log.w(TAG, "muxer", e); } }
            if (videoExtractor != null) videoExtractor.release();
            if (audioExtractor != null) audioExtractor.release();
            for (Bitmap b : bitmapCache.values()) if (b != null && !b.isRecycled()) b.recycle();
            bitmapCache.clear();
        }
    }

    private void copyAudio(MediaExtractor extractor, MediaMuxer muxer, int track, MediaFormat format) {
        int maxSize = format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)
            ? format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) : 256 * 1024;
        ByteBuffer buffer = ByteBuffer.allocate(maxSize);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
        while (true) {
            int size = extractor.readSampleData(buffer, 0);
            if (size < 0) break;
            info.offset = 0;
            info.size = size;
            info.presentationTimeUs = extractor.getSampleTime();
            info.flags = extractor.getSampleFlags();
            muxer.writeSampleData(track, buffer, info);
            extractor.advance();
        }
    }

    /** Which overlay PNG (if any) covers this moment in the video. */
    private String spanPathAt(long ms) {
        for (int i = 0; i < spans.size(); i++) {
            OverlaySpan s = spans.get(i);
            if (ms >= s.startMs && (s.endMs <= 0 || ms < s.endMs)) return s.path;
        }
        return null;
    }

    private Bitmap bitmapFor(String path) {
        if (path == null) return null;
        if (bitmapCache.containsKey(path)) return bitmapCache.get(path);
        Bitmap bmp = null;
        File f = new File(path);
        if (f.exists()) {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            bmp = BitmapFactory.decodeFile(path, opts);
        } else {
            Log.w(TAG, "Overlay image missing, frame will render without it: " + path);
        }
        bitmapCache.put(path, bmp);
        return bmp;
    }

    private static int estimateBitRate(int w, int h, int fps) {
        // ~0.12 bits per pixel per frame: visually clean for sport without bloating the file.
        long bps = (long) (w * h * fps * 0.12);
        return (int) Math.max(2_000_000L, Math.min(bps, 20_000_000L));
    }

    private static int selectTrack(MediaExtractor extractor, String mimePrefix) {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat f = extractor.getTrackFormat(i);
            String mime = f.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith(mimePrefix)) return i;
        }
        return -1;
    }
}
