package com.weizhi;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.weizhi.caps.WeizhiPackages;
import com.weizhi.video.WeizhiVideo;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.nio.ByteBuffer;

import static org.junit.Assert.assertTrue;

/**
 * On-device P5b gate: timeline v2 executes on the native renderer — main
 * video/image track compositing with transformed image overlays (region-level
 * Y assertions) and the v2 soundtrack path. Later-phase features are guarded
 * with English errors.
 */
@RunWith(AndroidJUnit4.class)
public final class WeizhiV2TimelineInstrumentedTest {

    /**
     * Average the Y plane of the first decoded frame at/after targetUs, over a
     * fractional rect (x/y/w/h in [0, 1], origin top-left) — region sampling
     * for overlay placement assertions.
     */
    private static int yMeanAtRect(File mp4, long targetUs, double xFrac, double yFrac,
            double wFrac, double hFrac) throws Exception {
        MediaExtractor ex = new MediaExtractor();
        ex.setDataSource(mp4.getAbsolutePath());
        int track = -1;
        for (int i = 0; i < ex.getTrackCount(); i++) {
            String mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("video/")) {
                track = i;
                break;
            }
        }
        assertTrue("no video track", track >= 0);
        ex.selectTrack(track);
        MediaCodec codec = MediaCodec.createDecoderByType("video/avc");
        codec.configure(ex.getTrackFormat(track), null, null, 0);
        codec.start();
        try {
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputEos = false;
            long deadline = System.currentTimeMillis() + 10_000;
            while (System.currentTimeMillis() < deadline) {
                int inIdx = codec.dequeueInputBuffer(10_000);
                if (inIdx >= 0 && !inputEos) {
                    int size = ex.readSampleData(codec.getInputBuffer(inIdx), 0);
                    if (size < 0) {
                        codec.queueInputBuffer(inIdx, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inputEos = true;
                    } else {
                        codec.queueInputBuffer(inIdx, 0, size, ex.getSampleTime(), 0);
                        ex.advance();
                    }
                }
                int outIdx = codec.dequeueOutputBuffer(info, 10_000);
                if (outIdx >= 0) {
                    android.media.Image image = codec.getOutputImage(outIdx);
                    if (image != null && info.presentationTimeUs >= targetUs) {
                        ByteBuffer y = image.getPlanes()[0].getBuffer();
                        int width = image.getWidth();
                        int height = image.getHeight();
                        int stride = image.getPlanes()[0].getRowStride();
                        int x0 = (int) Math.round(xFrac * width);
                        int y0 = (int) Math.round(yFrac * height);
                        int x1 = Math.min(width, x0 + (int) Math.round(wFrac * width));
                        int y1 = Math.min(height, y0 + (int) Math.round(hFrac * height));
                        assertTrue("empty rect", x1 > x0 && y1 > y0);
                        long sum = 0;
                        long n = 0;
                        for (int row = y0; row < y1; row++) {
                            for (int col = x0; col < x1; col++) {
                                sum += y.get(row * stride + col) & 0xff;
                                n++;
                            }
                        }
                        image.close();
                        codec.releaseOutputBuffer(outIdx, false);
                        return (int) (sum / n);
                    }
                    if (image != null) {
                        image.close();
                    }
                    codec.releaseOutputBuffer(outIdx, false);
                }
            }
            throw new IllegalStateException("no decoded frame at " + targetUs + "us");
        } finally {
            codec.stop();
            codec.release();
            ex.release();
        }
    }

    /** Video main track + a white logo overlay bottom-right: the overlay rect
     * turns bright while the background keeps the fixture gray. */
    @Test
    public void watermarkOverlayComposites() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File ws = new File(context.getCacheDir(), "weizhi-v2-" + System.currentTimeMillis());
        assertTrue(ws.mkdirs());
        AvcFixtures.encode(ws, "v.mp4", 2000, 60);
        SlideFixtures.jpeg(ws, "logo.jpg", 64, 64, 235);
        String pkgRoot = WeizhiPackages.syncBundled(context);

        try (WeizhiEngine engine = new WeizhiEngine()) {
            engine.setFsRoot(ws.getAbsolutePath());
            engine.setPackageRoot(pkgRoot);
            WeizhiVideo.install(engine);
            String out = engine.runJs(
                    "import { exportVideo } from 'weizhi:video';\n"
                            + "const r = await exportVideo({\n"
                            + "  width: 320, height: 240,\n"
                            + "  tracks: [\n"
                            + "    {type: 'video', clips: [{src: 'v.mp4', outMs: 2000}]},\n"
                            + "    {type: 'image', clips: [{src: 'logo.jpg', startMs: 0,\n"
                            + "      durationMs: 2000, transform: {x: 0.8, y: 0.4, scale: 0.2}}]},\n"
                            + "  ],\n"
                            + "  output: 'v2.mp4'});\n"
                            + "export default {ok: r.ok, dur: r.durationMs};\n",
                    60_000, "v2wm.js");
            assertTrue(out, out.contains("\"ok\":true"));
            int dur = WeizhiVideoInstrumentedTest.jsonInt(out, "dur");
            assertTrue("dur=" + dur, dur >= 1800 && dur <= 2200);
            File mp4 = new File(ws, "v2.mp4");
            /* 1:1 logo contain-fit on 4:3 (fit half-extents 0.75/1.0), scale
             * 0.2, center (0.8, 0.4): NDC half extents (0.15, 0.2) ->
             * px x 264..312, y 48..96 (fractions 0.825..0.975 / 0.2..0.4) */
            int logo = yMeanAtRect(mp4, 1_000_000, 0.83, 0.21, 0.14, 0.18);
            int bg = yMeanAtRect(mp4, 1_000_000, 0.08, 0.12, 0.25, 0.35);
            assertTrue("logo rect not bright: " + logo, logo > 150);
            assertTrue("background changed: " + bg, bg > 30 && bg < 100);
        }
    }

    /** Image main track (slideshow) + overlay + soundtrack together. */
    @Test
    public void slideshowWithOverlayAndSoundtrack() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File ws = new File(context.getCacheDir(), "weizhi-v2s-" + System.currentTimeMillis());
        assertTrue(ws.mkdirs());
        SlideFixtures.jpeg(ws, "p1.jpg", 320, 240, 110);
        SlideFixtures.jpeg(ws, "p2.jpg", 320, 240, 235);
        SlideFixtures.jpeg(ws, "dot.jpg", 32, 32, 235);
        SlideFixtures.aac(ws, "music.m4a", 2000);
        String pkgRoot = WeizhiPackages.syncBundled(context);

        try (WeizhiEngine engine = new WeizhiEngine()) {
            engine.setFsRoot(ws.getAbsolutePath());
            engine.setPackageRoot(pkgRoot);
            WeizhiVideo.install(engine);
            String out = engine.runJs(
                    "import { exportVideo } from 'weizhi:video';\n"
                            + "const r = await exportVideo({\n"
                            + "  width: 320, height: 240,\n"
                            + "  tracks: [\n"
                            + "    {type: 'image', clips: [\n"
                            + "      {src: 'p1.jpg', durationMs: 1000},\n"
                            + "      {src: 'p2.jpg', startMs: 1000, durationMs: 1000}]},\n"
                            + "    {type: 'image', clips: [{src: 'dot.jpg', startMs: 500,\n"
                            + "      durationMs: 1000, transform: {x: -0.8, y: -0.8, scale: 0.2}}]},\n"
                            + "    {type: 'audio', clips: [{src: 'music.m4a'}]},\n"
                            + "  ],\n"
                            + "  output: 'v2s.mp4'});\n"
                            + "const v = await host.ensureNative('video');\n"
                            + "const meta = JSON.parse(v.probe('v2s.mp4'));\n"
                            + "export default {ok: r.ok, dur: r.durationMs,\n"
                            + "  tracks: meta.tracks.length};\n",
                    60_000, "v2s.js");
            assertTrue(out, out.contains("\"ok\":true"));
            int dur = WeizhiVideoInstrumentedTest.jsonInt(out, "dur");
            assertTrue("dur=" + dur, dur >= 1900 && dur <= 2100);
            assertTrue("soundtrack track missing: " + out, out.contains("\"tracks\":2"));
            File mp4 = new File(ws, "v2s.mp4");
            /* dot: 1:1 fit half-extents (0.75, 1.0), scale 0.2, center
             * (-0.8, -0.8) -> NDC half extents (0.15, 0.2) ->
             * px x 8..56, y 192..240 (bottom-left), at t=800ms over gray p1 */
            int dot = yMeanAtRect(mp4, 800_000, 0.05, 0.82, 0.12, 0.15);
            assertTrue("dot rect not bright: " + dot, dot > 150);
            int y1 = yMeanAtRect(mp4, 1_500_000, 0.35, 0.3, 0.3, 0.4);
            assertTrue("slide2 not white: " + y1, y1 > 195);
        }
    }

    /** P5c: per-property keyframes animate on the main track (kenBurns-style
     * scale); transform constants apply to the video main track too. */
    @Test
    public void v2KeyframeAnimation() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File ws = new File(context.getCacheDir(), "weizhi-v2k-" + System.currentTimeMillis());
        assertTrue(ws.mkdirs());
        SlideFixtures.jpeg(ws, "w.jpg", 320, 240, 235);
        AvcFixtures.encode(ws, "v.mp4", 1000, 60);
        String pkgRoot = WeizhiPackages.syncBundled(context);

        try (WeizhiEngine engine = new WeizhiEngine()) {
            engine.setFsRoot(ws.getAbsolutePath());
            engine.setPackageRoot(pkgRoot);
            WeizhiVideo.install(engine);
            /* image main, scale 1 -> 0.5 over the clip: full-screen white on the
             * first frame (t=0, luma ~217 in limited range), quarter area
             * (letterboxed) at t=900 */
            String out = engine.runJs(
                    "import { exportVideo } from 'weizhi:video';\n"
                            + "const r = await exportVideo({\n"
                            + "  width: 320, height: 240,\n"
                            + "  tracks: [{type: 'image', clips: [{src: 'w.jpg', durationMs: 1000,\n"
                            + "    transform: {scale: [{t: 0, v: 1}, {t: 800, v: 0.5}]}}]}],\n"
                            + "  output: 'k.mp4'});\n"
                            + "export default r.ok;\n",
                    30_000, "v2k.js");
            assertTrue(out, out.contains("true"));
            File mp4 = new File(ws, "k.mp4");
            int y0 = WeizhiTranscodeInstrumentedTest.yMeanAtPts(mp4, 0);
            int y1 = WeizhiTranscodeInstrumentedTest.yMeanAtPts(mp4, 900_000);
            assertTrue("start not full: " + y0, y0 > 200);
            assertTrue("end not shrunk: " + y1, y1 > 40 && y1 < 120);
        }
    }

    /** P5c: transform constants apply to the video main track — scale 0.5
     * letterboxes to quarter area on black, opacity 0.5 halves the frame. */
    @Test
    public void v2MainTrackVideoTransform() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File ws = new File(context.getCacheDir(), "weizhi-v2t-" + System.currentTimeMillis());
        assertTrue(ws.mkdirs());
        AvcFixtures.encode(ws, "wv.mp4", 1000, 235);
        String pkgRoot = WeizhiPackages.syncBundled(context);

        try (WeizhiEngine engine = new WeizhiEngine()) {
            engine.setFsRoot(ws.getAbsolutePath());
            engine.setPackageRoot(pkgRoot);
            WeizhiVideo.install(engine);
            String out = engine.runJs(
                    "import { exportVideo } from 'weizhi:video';\n"
                            + "const r1 = await exportVideo({\n"
                            + "  width: 320, height: 240,\n"
                            + "  tracks: [{type: 'video', clips: [{src: 'wv.mp4',\n"
                            + "    transform: {scale: 0.5}}]}],\n"
                            + "  output: 's.mp4'});\n"
                            + "const r2 = await exportVideo({\n"
                            + "  width: 320, height: 240,\n"
                            + "  tracks: [{type: 'video', clips: [{src: 'wv.mp4',\n"
                            + "    transform: {opacity: 0.5}}]}],\n"
                            + "  output: 'o.mp4'});\n"
                            + "export default r1.ok && r2.ok;\n",
                    30_000, "v2t.js");
            assertTrue(out, out.contains("true"));
            /* scale 0.5: quarter of the frame white (~70), rest black (~16) */
            File scaled = new File(ws, "s.mp4");
            int ys = WeizhiTranscodeInstrumentedTest.yMeanAtPts(scaled, 500_000);
            assertTrue("video main scale not letterboxed: " + ys, ys > 40 && ys < 120);
            /* opacity 0.5 over black clear: ~ (16 + 235) / 2 */
            File faded = new File(ws, "o.mp4");
            int yo = WeizhiTranscodeInstrumentedTest.yMeanAtPts(faded, 500_000);
            assertTrue("video main opacity not applied: " + yo, yo > 100 && yo < 160);
        }
    }

    /** P5c: crossfade between two image clips — the boundary midpoint sits
     * between the two fixture grays, both sides outside stay pure. */
    @Test
    public void v2CrossfadeBetweenSlides() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File ws = new File(context.getCacheDir(), "weizhi-v2x-" + System.currentTimeMillis());
        assertTrue(ws.mkdirs());
        SlideFixtures.jpeg(ws, "g.jpg", 320, 240, 60);
        SlideFixtures.jpeg(ws, "w.jpg", 320, 240, 235);
        String pkgRoot = WeizhiPackages.syncBundled(context);

        try (WeizhiEngine engine = new WeizhiEngine()) {
            engine.setFsRoot(ws.getAbsolutePath());
            engine.setPackageRoot(pkgRoot);
            WeizhiVideo.install(engine);
            String out = engine.runJs(
                    "import { exportVideo } from 'weizhi:video';\n"
                            + "const r = await exportVideo({\n"
                            + "  width: 320, height: 240,\n"
                            + "  tracks: [{type: 'image', clips: [\n"
                            + "    {src: 'g.jpg', durationMs: 800},\n"
                            + "    {src: 'w.jpg', startMs: 500, durationMs: 800,\n"
                            + "     transition: {type: 'crossfade', durationMs: 300}}]}],\n"
                            + "  output: 'x.mp4'});\n"
                            + "export default r.ok;\n",
                    30_000, "v2x.js");
            assertTrue(out, out.contains("true"));
            File mp4 = new File(ws, "x.mp4");
            int gray = WeizhiTranscodeInstrumentedTest.yMeanAtPts(mp4, 250_000);
            int mid = WeizhiTranscodeInstrumentedTest.yMeanAtPts(mp4, 650_000);
            int white = WeizhiTranscodeInstrumentedTest.yMeanAtPts(mp4, 1_200_000);
            assertTrue("pre-fade not gray: " + gray, gray > 30 && gray < 95);
            assertTrue("mid not blended: " + mid, mid > 100 && mid < 195);
            assertTrue("post-fade not white: " + white, white > 200);
        }
    }

    /** Later-phase features report English errors instead of half-running. */
    @Test
    public void v2PhaseGuards() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File ws = new File(context.getCacheDir(), "weizhi-v2g-" + System.currentTimeMillis());
        assertTrue(ws.mkdirs());
        SlideFixtures.jpeg(ws, "p.jpg", 320, 240, 110);
        AvcFixtures.encode(ws, "v.mp4", 1000, 110);
        String pkgRoot = WeizhiPackages.syncBundled(context);

        try (WeizhiEngine engine = new WeizhiEngine()) {
            engine.setFsRoot(ws.getAbsolutePath());
            engine.setPackageRoot(pkgRoot);
            WeizhiVideo.install(engine);
            String out = engine.runJs(
                    "import { exportVideo } from 'weizhi:video';\n"
                            + "let m1 = '';\n"
                            + "try { await exportVideo({tracks: [\n"
                            + "  {type: 'audio', clips: [{src: 'p.jpg',\n"
                            + "    gain: [{t: 0, v: 1}, {t: 400, v: 0}]}]}],\n"
                            + "  output: 'g.mp4'}); } catch (e) { m1 = e.message; }\n"
                            + "let m2 = '';\n"
                            + "try { await exportVideo({tracks: [\n"
                            + "  {type: 'text', clips: [{text: 'hi', durationMs: 500}]}],\n"
                            + "  output: 'g.mp4'}); } catch (e) { m2 = e.message; }\n"
                            + "let m3 = '';\n"
                            + "try { await exportVideo({tracks: [\n"
                            + "  {type: 'image', clips: [{src: 'p.jpg', durationMs: 500},\n"
                            + "    {src: 'p.jpg', startMs: 800, durationMs: 500}]}],\n"
                            + "  output: 'g.mp4'}); } catch (e) { m3 = e.message; }\n"
                            + "let m4 = '';\n"
                            + "try { await exportVideo({tracks: [\n"
                            + "  {type: 'video', clips: [{src: 'v.mp4', outMs: 800,\n"
                            + "    transition: {type: 'crossfade', durationMs: 200}}]}],\n"
                            + "  output: 'g.mp4'}); } catch (e) { m4 = e.message; }\n"
                            + "export default [m1, m2, m3, m4];\n",
                    30_000, "v2g.js");
            assertTrue(out, out.contains("v2 audio keyframes (P5d)"));
            assertTrue(out, out.contains("v2 text track (P5d)"));
            assertTrue(out, out.contains("v2 timeline gaps (P5e)"));
            assertTrue(out, out.contains("transition on video tracks (P5e)"));
        }
    }
}
