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
import java.util.Collections;
import java.util.Comparator;
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

    /**
     * Like composite(), but instead of re-encoding the input video start to
     * finish, only the given time ranges are kept — seeked to, decoded,
     * re-encoded, and written back-to-back into ONE continuous output file.
     * Built for the "Save Highlights" option: the caller (JS side) turns each
     * FOUR/SIX/WICKET moment into a padded window, merges anything that
     * overlaps, and hands the result in here sorted or not — this method
     * sorts and re-merges defensively regardless.
     *
     * Reuses the exact same per-frame GL overlay drawing as composite() (same
     * OverlaySpan list, same spanPathAt() lookup by the frame's ORIGINAL
     * timestamp), so the scoreboard burned into a highlight clip matches what
     * it would have shown in the full video at that same moment.
     *
     * Kept as a SEPARATE method rather than folding the seek/skip logic into
     * composite() itself: composite() is the proven, device-verified path
     * every single recording already goes through to get its scoreboard, and
     * none of the extra complexity here has any reason to run — or risk a
     * regression — on that path.
     *
     * HOW A SINGLE DECODER CROSSES MULTIPLE SEGMENTS
     * -----------------------------------------------
     * For each segment: seek the extractor to the nearest sync frame AT OR
     * BEFORE the segment's start (a real cut can only begin cleanly on a
     * keyframe), then flush() the decoder so it forgets whatever state it had
     * from the previous segment. Frames decoded before the segment's actual
     * start (needed only to prime the decoder's reference frames) are
     * released with render=false — decoded, but never drawn or encoded.
     * Frames inside [startMs, endMs) ARE drawn/encoded, with their
     * presentation time remapped onto a continuously-advancing OUTPUT clock
     * (outputCursorUs) so the final file has no gaps or timestamp jumps where
     * the source video was cut. Audio is remapped the same way, segment by
     * segment, in copyAudioSegments() below, using the identical per-segment
     * offsets so picture and sound stay in sync.
     */
    public void compositeSegments(String inputPath, String outputPath, List<long[]> rawSegments,
                                   int bitRate, ProgressListener listener) throws IOException {
        if (rawSegments == null || rawSegments.isEmpty()) {
            throw new IOException("No highlight segments to composite");
        }

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
            long sourceDurationUs = inputFormat.containsKey(MediaFormat.KEY_DURATION)
                ? inputFormat.getLong(MediaFormat.KEY_DURATION) : 0;
            int frameRate = inputFormat.containsKey(MediaFormat.KEY_FRAME_RATE)
                ? inputFormat.getInteger(MediaFormat.KEY_FRAME_RATE) : 30;

            int rotation = inputFormat.containsKey(MediaFormat.KEY_ROTATION)
                ? inputFormat.getInteger(MediaFormat.KEY_ROTATION) : 0;
            int outWidth = (rotation == 90 || rotation == 270) ? height : width;
            int outHeight = (rotation == 90 || rotation == 270) ? width : height;
            outWidth = outWidth & ~1;
            outHeight = outHeight & ~1;

            // Clamp every segment to the real source duration, drop anything
            // that's empty after clamping (e.g. padding that overshoots a
            // highlight right at the very end), sort, then merge anything
            // that now overlaps or touches — defensive even though the JS
            // caller already does this, since decoding relies on segments
            // being in order and non-overlapping.
            long sourceDurationMs = sourceDurationUs > 0 ? sourceDurationUs / 1000 : Long.MAX_VALUE;
            List<long[]> segments = new ArrayList<>();
            for (long[] seg : rawSegments) {
                long s = Math.max(0, seg[0]);
                long e = Math.min(sourceDurationMs, seg[1]);
                if (e > s) segments.add(new long[]{ s, e });
            }
            if (segments.isEmpty()) throw new IOException("All highlight segments were empty after clamping");
            Collections.sort(segments, new Comparator<long[]>() {
                @Override public int compare(long[] a, long[] b) { return Long.compare(a[0], b[0]); }
            });
            List<long[]> merged = new ArrayList<>();
            for (long[] seg : segments) {
                long[] last = merged.isEmpty() ? null : merged.get(merged.size() - 1);
                if (last != null && seg[0] <= last[1]) {
                    last[1] = Math.max(last[1], seg[1]);
                } else {
                    merged.add(seg);
                }
            }
            long totalOutputMs = 0;
            for (long[] seg : merged) totalOutputMs += (seg[1] - seg[0]);

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
            decoder.configure(inputFormat, renderer.getDecoderSurface(), null, 0);
            decoder.start();

            muxer = new MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

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
            boolean encoderDone = false;

            MediaCodec.BufferInfo encInfo = new MediaCodec.BufferInfo();
            MediaCodec.BufferInfo decInfo = new MediaCodec.BufferInfo();
            int lastReportedPercent = -1;
            long outputCursorUs = 0; // where in the OUTPUT timeline the next kept frame lands
            long processedMs = 0;    // sum of segment-time already fed, for progress %
            // Safety valve: if a segment somehow never reaches its own end
            // (shouldn't happen given the clamping above, but a stuck decoder
            // must never hang the whole highlights job), bail after this many
            // consecutive empty dequeue spins (~5s at TIMEOUT_US=10000).
            final int MAX_IDLE_SPINS = 500;

            for (int segIdx = 0; segIdx < merged.size(); segIdx++) {
                long[] seg = merged.get(segIdx);
                long segStartUs = seg[0] * 1000;
                long segEndUs = seg[1] * 1000;
                boolean lastSegment = (segIdx == merged.size() - 1);

                videoExtractor.seekTo(segStartUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
                decoder.flush();

                boolean segmentInputDone = false;
                boolean segmentDone = false;
                int idleSpins = 0;

                while (!segmentDone) {
                    boolean madeProgress = false;

                    if (!segmentInputDone) {
                        int inIndex = decoder.dequeueInputBuffer(TIMEOUT_US);
                        if (inIndex >= 0) {
                            ByteBuffer buf = decoder.getInputBuffer(inIndex);
                            long sampleTimeUs = videoExtractor.getSampleTime();
                            int size = (sampleTimeUs < 0) ? -1 : videoExtractor.readSampleData(buf, 0);
                            if (size < 0 || sampleTimeUs < 0 || sampleTimeUs >= segEndUs) {
                                // Real EOF, or we've read into the next segment's
                                // territory — this segment's input is done. Only
                                // the LAST segment gets a real decoder EOS; a
                                // middle segment just stops feeding and lets its
                                // already-queued frames drain below.
                                segmentInputDone = true;
                                if (lastSegment) {
                                    decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                }
                            } else {
                                decoder.queueInputBuffer(inIndex, 0, size, sampleTimeUs, 0);
                                videoExtractor.advance();
                            }
                            madeProgress = true;
                        }
                    }

                    int outIndex = decoder.dequeueOutputBuffer(decInfo, TIMEOUT_US);
                    if (outIndex >= 0) {
                        madeProgress = true;
                        long ptsUs = decInfo.presentationTimeUs;
                        boolean withinSegment = ptsUs >= segStartUs && ptsUs < segEndUs;
                        boolean eos = (decInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                        boolean render = decInfo.size > 0 && withinSegment;
                        decoder.releaseOutputBuffer(outIndex, render);
                        if (render) {
                            renderer.awaitNewFrame(5000);
                            String key = spanPathAt(ptsUs / 1000);
                            renderer.drawFrame(bitmapFor(key), key);
                            renderer.setPresentationTime((outputCursorUs + (ptsUs - segStartUs)) * 1000);
                            renderer.swapBuffers();
                        }
                        if (decInfo.size > 0 && ptsUs >= segEndUs) {
                            segmentDone = true; // decoded into the next segment — this one's complete
                        }
                        if (eos) {
                            segmentDone = true;
                            if (lastSegment) encoder.signalEndOfInputStream();
                        }
                    }

                    // ---- drain the encoder into the muxer (same every segment) ----
                    int encIndex = encoder.dequeueOutputBuffer(encInfo, TIMEOUT_US);
                    if (encIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (muxerStarted) throw new IllegalStateException("Encoder format changed twice");
                        muxerVideoTrack = muxer.addTrack(encoder.getOutputFormat());
                        if (audioFormat != null) muxerAudioTrack = muxer.addTrack(audioFormat);
                        muxer.start();
                        muxerStarted = true;
                        madeProgress = true;
                    } else if (encIndex >= 0) {
                        madeProgress = true;
                        ByteBuffer encoded = encoder.getOutputBuffer(encIndex);
                        if ((encInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) encInfo.size = 0;
                        if (encInfo.size > 0 && muxerStarted) {
                            encoded.position(encInfo.offset);
                            encoded.limit(encInfo.offset + encInfo.size);
                            muxer.writeSampleData(muxerVideoTrack, encoded, encInfo);
                        }
                        encoder.releaseOutputBuffer(encIndex, false);
                        if ((encInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) encoderDone = true;
                    }

                    if (madeProgress) {
                        idleSpins = 0;
                    } else if (++idleSpins > MAX_IDLE_SPINS) {
                        Log.w(TAG, "compositeSegments: segment " + segIdx + " stalled, moving on early");
                        segmentDone = true;
                    }
                }

                outputCursorUs += (segEndUs - segStartUs);
                processedMs += (seg[1] - seg[0]);
                if (listener != null && totalOutputMs > 0) {
                    int pct = (int) Math.min(99, (processedMs * 100) / totalOutputMs);
                    if (pct != lastReportedPercent) { lastReportedPercent = pct; listener.onProgress(pct); }
                }
            }

            // Drain whatever the encoder still has buffered after the final segment's signalEndOfInputStream().
            int finalIdleSpins = 0;
            while (!encoderDone) {
                int encIndex = encoder.dequeueOutputBuffer(encInfo, TIMEOUT_US);
                if (encIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (!muxerStarted) {
                        muxerVideoTrack = muxer.addTrack(encoder.getOutputFormat());
                        if (audioFormat != null) muxerAudioTrack = muxer.addTrack(audioFormat);
                        muxer.start();
                        muxerStarted = true;
                    }
                    finalIdleSpins = 0;
                } else if (encIndex >= 0) {
                    finalIdleSpins = 0;
                    ByteBuffer encoded = encoder.getOutputBuffer(encIndex);
                    if ((encInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) encInfo.size = 0;
                    if (encInfo.size > 0 && muxerStarted) {
                        encoded.position(encInfo.offset);
                        encoded.limit(encInfo.offset + encInfo.size);
                        muxer.writeSampleData(muxerVideoTrack, encoded, encInfo);
                    }
                    encoder.releaseOutputBuffer(encIndex, false);
                    if ((encInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) encoderDone = true;
                } else if (++finalIdleSpins > MAX_IDLE_SPINS) {
                    Log.w(TAG, "compositeSegments: final encoder drain stalled, finishing early");
                    break;
                }
            }

            if (muxerAudioTrack >= 0 && audioFormat != null) {
                copyAudioSegments(audioExtractor, muxer, muxerAudioTrack, audioFormat, merged);
            }

            if (listener != null) listener.onProgress(100);

        } finally {
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

    /** Audio counterpart to the per-segment video loop above — same segment
     *  list, same cumulative-offset math, so picture and sound land on the
     *  same output timeline. AAC samples are each independently decodable, so
     *  (unlike video) no flush/prime dance is needed — just seek and copy. */
    private void copyAudioSegments(MediaExtractor extractor, MediaMuxer muxer, int track,
                                    MediaFormat format, List<long[]> segments) {
        int maxSize = format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)
            ? format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) : 256 * 1024;
        ByteBuffer buffer = ByteBuffer.allocate(maxSize);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        long outputCursorUs = 0;
        for (long[] seg : segments) {
            long segStartUs = seg[0] * 1000;
            long segEndUs = seg[1] * 1000;
            extractor.seekTo(segStartUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
            while (true) {
                long sampleTimeUs = extractor.getSampleTime();
                if (sampleTimeUs < 0 || sampleTimeUs >= segEndUs) break;
                int size = extractor.readSampleData(buffer, 0);
                if (size < 0) break;
                info.offset = 0;
                info.size = size;
                info.presentationTimeUs = outputCursorUs + Math.max(0, sampleTimeUs - segStartUs);
                info.flags = extractor.getSampleFlags();
                muxer.writeSampleData(track, buffer, info);
                extractor.advance();
            }
            outputCursorUs += (segEndUs - segStartUs);
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
