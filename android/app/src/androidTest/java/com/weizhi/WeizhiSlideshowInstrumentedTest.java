package com.weizhi;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.weizhi.caps.WeizhiPackages;
import com.weizhi.video.WeizhiVideo;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;

import static org.junit.Assert.assertTrue;

/**
 * On-device P3d gate: photo slideshows through the weizhi:video package —
 * slides -> AImageDecoder -> GL 2D texture -> encoder surface, letterboxed,
 * with fade transitions and a passthrough soundtrack.
 */
@RunWith(AndroidJUnit4.class)
public final class WeizhiSlideshowInstrumentedTest {

    @Test
    public void slideshowExportWithFadeAndSoundtrack() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File ws = new File(context.getCacheDir(), "weizhi-slides-" + System.currentTimeMillis());
        assertTrue(ws.mkdirs());
        /* 320x240 inputs match the output size, so frames are full-bleed (no
         * letterbox): the Y means should track the fixture grays directly. */
        SlideFixtures.jpeg(ws, "p1.jpg", 320, 240, 110);
        SlideFixtures.jpeg(ws, "p2.jpg", 320, 240, 235);
        SlideFixtures.aac(ws, "music.m4a", 2000);
        String pkgRoot = WeizhiPackages.syncBundled(context);

        try (WeizhiEngine engine = new WeizhiEngine()) {
            engine.setFsRoot(ws.getAbsolutePath());
            engine.setPackageRoot(pkgRoot);
            WeizhiVideo.install(engine);

            String out = engine.runJs(
                    "import { exportVideo } from 'weizhi:video';\n"
                            + "const events = [];\n"
                            + "const r = await exportVideo({\n"
                            + "  slides: [\n"
                            + "    {image: 'p1.jpg', durationMs: 1000},\n"
                            + "    {image: 'p2.jpg', durationMs: 1000},\n"
                            + "  ],\n"
                            + "  output: 'slides.mp4',\n"
                            + "  width: 320, height: 240,\n"
                            + "  audio: 'music.m4a',\n"
                            + "  transitions: {type: 'fade', durationMs: 200},\n"
                            + "  onProgress: (e) => { events.push(e.percent); },\n"
                            + "});\n"
                            + "const v = await host.ensureNative('video');\n"
                            + "const meta = JSON.parse(v.probe('slides.mp4'));\n"
                            + "export default {ok: r.ok, dur: r.durationMs, slides: r.slides,\n"
                            + "  tracks: meta.tracks.length, mdur: Math.round(meta.durationMs),\n"
                            + "  progress: events.length};\n",
                    60_000, "slides.js");
            assertTrue(out, out.contains("\"ok\":true"));
            int dur = WeizhiVideoInstrumentedTest.jsonInt(out, "dur");
            assertTrue("dur=" + dur, dur >= 1900 && dur <= 2100);
            assertTrue(out, out.contains("\"slides\":2"));
            /* video + the muxed soundtrack */
            assertTrue("soundtrack track missing: " + out, out.contains("\"tracks\":2"));
            int mdur = WeizhiVideoInstrumentedTest.jsonInt(out, "mdur");
            assertTrue("mdur=" + mdur, mdur >= 1800 && mdur <= 2200);
            assertTrue("no progress", WeizhiVideoInstrumentedTest.jsonInt(out, "progress") >= 1);

            /* frame content: mid of each slide keeps the fixture gray/white;
             * fades only touch the 900-1100ms boundary */
            File mp4 = new File(ws, "slides.mp4");
            int y1 = WeizhiTranscodeInstrumentedTest.yMeanAtPts(mp4, 500_000);
            int y2 = WeizhiTranscodeInstrumentedTest.yMeanAtPts(mp4, 1_500_000);
            assertTrue("slide1 gray off: " + y1, y1 > 85 && y1 < 135);
            assertTrue("slide2 white off: " + y2, y2 > 195);
            /* fade boundary: halfway into the 200ms cross-zone is near black */
            int yb = WeizhiTranscodeInstrumentedTest.yMeanAtPts(mp4, 1_000_000);
            assertTrue("boundary not faded: " + yb, yb < 90);
        }
    }

    /** A photo narrower than the output letterboxes instead of stretching: a
     * portrait (9:16) white photo on the default 16:9 canvas occupies ~32% of
     * the width, so the frame's Y mean must sit far below full white. */
    @Test
    public void letterboxedPhotoKeepsBars() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File ws = new File(context.getCacheDir(), "weizhi-lbox-" + System.currentTimeMillis());
        assertTrue(ws.mkdirs());
        SlideFixtures.jpeg(ws, "tall.jpg", 180, 320, 235);
        String pkgRoot = WeizhiPackages.syncBundled(context);

        try (WeizhiEngine engine = new WeizhiEngine()) {
            engine.setFsRoot(ws.getAbsolutePath());
            engine.setPackageRoot(pkgRoot);
            WeizhiVideo.install(engine);
            String out = engine.runJs(
                    "import { exportVideo } from 'weizhi:video';\n"
                            + "const r = await exportVideo({\n"
                            + "  slides: [{image: 'tall.jpg', durationMs: 600}],\n"
                            + "  output: 'lbox.mp4',\n"
                            + "});\n"
                            + "export default {ok: r.ok, w: r.width, h: r.height};\n",
                    30_000, "lbox.js");
            assertTrue(out, out.contains("\"ok\":true"));
            assertTrue(out, out.contains("\"w\":1280"));
            assertTrue(out, out.contains("\"h\":720"));
            int y = WeizhiTranscodeInstrumentedTest.yMeanAtPts(new File(ws, "lbox.mp4"), 300_000);
            /* full bleed would be ~235 (white fixture); ~32% coverage lands
             * near 0.32 * 235 = 75. The range proves bars exist without
             * pinning the exact coverage fraction. */
            assertTrue("letterbox bars missing: y=" + y, y > 40 && y < 140);
        }
    }
}
