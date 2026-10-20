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
import static org.junit.Assert.fail;

/**
 * On-device P3a gate: the {@code weizhi:video} orchestration package drives the
 * bundled media plugins end-to-end (import -> buildJob -> exportVideo -> mp4).
 */
@RunWith(AndroidJUnit4.class)
public final class WeizhiVideoPackageInstrumentedTest {

    @Test
    public void exportViaPackage() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File ws = new File(context.getCacheDir(), "weizhi-pkg-" + System.currentTimeMillis());
        assertTrue(ws.mkdirs());
        AvcFixtures.encode(ws, "a.mp4", AvcFixtures.DEFAULT_MS, 30);
        AvcFixtures.encode(ws, "b.mp4", AvcFixtures.DEFAULT_MS, 160);
        String pkgRoot = WeizhiPackages.syncBundled(context);

        try (WeizhiEngine engine = new WeizhiEngine()) {
            engine.setFsRoot(ws.getAbsolutePath());
            engine.setPackageRoot(pkgRoot);
            WeizhiVideo.install(engine);

            /* probe: input metadata via the package surface before deciding trim points (#39) */
            String pr = engine.runJs(
                    "import { probe } from 'weizhi:video';\n"
                            + "const m = await probe('a.mp4');\n"
                            + "export default {tracks: m.tracks.length, w: m.tracks[0].width, dur: m.durationMs};\n",
                    10_000, "probe.js");
            assertTrue(pr, pr.contains("\"tracks\":1"));
            assertTrue(pr, pr.contains("\"w\":320"));
            int pdur = WeizhiVideoInstrumentedTest.jsonInt(pr, "dur");
            assertTrue("pdur=" + pdur, pdur > 800 && pdur < 1300);

            /* re-encode path: trim + concat + bitrate, progress via package callback */
            String out = engine.runJs(
                    "import { exportVideo } from 'weizhi:video';\n"
                            + "const events = [];\n"
                            + "const r = await exportVideo({\n"
                            + "  clips: [{path: 'a.mp4', startMs: 200, endMs: 800}, {path: 'b.mp4'}],\n"
                            + "  output: 'pkg-out.mp4',\n"
                            + "  bitrate: 500000,\n"
                            + "  onProgress: (e) => { events.push(e.percent); },\n"
                            + "});\n"
                            + "export default {ok: r.ok, dur: r.durationMs, w: r.width, events: events.length};\n",
                    60_000, "pkg1.js");
            assertTrue(out, out.contains("\"ok\":true"));
            assertTrue(out, out.contains("\"w\":320"));
            int dur = WeizhiVideoInstrumentedTest.jsonInt(out, "dur");
            assertTrue("dur=" + dur, dur > 1300 && dur < 2100);
            assertTrue("no progress", WeizhiVideoInstrumentedTest.jsonInt(out, "events") >= 1);

            /* remux path: no video options -> plugin "video", no re-encode */
            String remux = engine.runJs(
                    "import { exportVideo } from 'weizhi:video';\n"
                            + "import {} from 'weizhi:demo';\n" // force package root sanity
                            + "const r2 = await exportVideo({\n"
                            + "  clips: [{path: 'a.mp4'}, {path: 'b.mp4'}],\n"
                            + "  output: 'pkg-remux.mp4',\n"
                            + "});\n"
                            + "export default {ok: r2.ok, dur: r2.durationMs};\n",
                    30_000, "pkg2.js");
            assertTrue(remux, remux.contains("\"ok\":true"));
            int rdur = WeizhiVideoInstrumentedTest.jsonInt(remux, "dur");
            assertTrue("rdur=" + rdur, rdur > 1800 && rdur < 2300);

            /* filters execute on the GPU path (P3b): brightness export succeeds */
            String flt = engine.runJs(
                    "import { exportVideo } from 'weizhi:video';\n"
                            + "const rf = await exportVideo({\n"
                            + "  clips: [{path: 'a.mp4'}],\n"
                            + "  output: 'pkg-flt.mp4',\n"
                            + "  filters: ['brightness'],\n"
                            + "});\n"
                            + "export default {ok: rf.ok, dur: rf.durationMs};\n",
                    30_000, "pkg3.js");
            assertTrue(flt, flt.contains("\"ok\":true"));
            assertTrue(flt, WeizhiVideoInstrumentedTest.jsonInt(flt, "dur") > 800);
        }
    }
}
