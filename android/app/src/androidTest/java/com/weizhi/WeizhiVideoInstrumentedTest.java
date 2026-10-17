package com.weizhi;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.weizhi.video.WeizhiVideo;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;

import static org.junit.Assert.assertTrue;

/**
 * On-device P1 gate for the bundled weizhi_video plugin (docs/video-architecture.md):
 * probe / trim / concat via the NDK media stack, driven end-to-end from JS.
 * Fixtures come from {@link AvcFixtures}.
 */
@RunWith(AndroidJUnit4.class)
public final class WeizhiVideoInstrumentedTest {

    @Test
    public void probeTrimAndConcat() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File ws = new File(context.getCacheDir(), "weizhi-media-" + System.currentTimeMillis());
        assertTrue(ws.mkdirs());
        File a = AvcFixtures.encode(ws, "a.mp4", AvcFixtures.DEFAULT_MS, 40);
        File b = AvcFixtures.encode(ws, "b.mp4", AvcFixtures.DEFAULT_MS, 180);
        assertTrue(a.length() > 0);
        assertTrue(b.length() > 0);

        try (WeizhiEngine engine = new WeizhiEngine()) {
            engine.setFsRoot(ws.getAbsolutePath());
            WeizhiVideo.install(engine);
            String out = engine.runJs(
                    "const v = await host.ensureNative('video');"
                            + "const pa = JSON.parse(v.probe('a.mp4'));"
                            + "const job = v.open_job(JSON.stringify({"
                            + "  clips: [{path: 'a.mp4', startMs: 200, endMs: 800}, {path: 'b.mp4'}],"
                            + "  output: 'out.mp4'}));"
                            + "const events = [];"
                            + "const r = JSON.parse(v.run_job(job, (e) => { events.push(e); }));"
                            + "v.close_job(job);"
                            + "const po = JSON.parse(v.probe('out.mp4'));"
                            + "({aTracks: pa.tracks.length, aDur: pa.durationMs, aW: pa.tracks[0].width,"
                            + "  ok: r.ok, outTracks: po.tracks.length, outDur: po.durationMs,"
                            + "  events: events.length,"
                            + "  lastPercent: events.length ? events[events.length - 1].percent : -1})",
                    30_000);
            assertTrue(out, out.contains("\"aTracks\":1"));
            assertTrue(out, out.contains("\"aW\":320"));
            assertTrue(out, out.contains("\"ok\":true"));
            assertTrue(out, out.contains("\"outTracks\":1"));
            /* trim 600ms + full 1000ms fixture, codec/muxer slop allowed */
            int aDur = jsonInt(out, "aDur");
            assertTrue("aDur=" + aDur, aDur > 800 && aDur < 1300);
            int outDur = jsonInt(out, "outDur");
            assertTrue("outDur=" + outDur, outDur > 1300 && outDur < 2100);
            assertTrue("no progress events", jsonInt(out, "events") >= 1);
        }
    }

    static int jsonInt(String json, String key) {
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("\"" + key + "\":(-?\\d+)").matcher(json);
        assertTrue("missing " + key + " in " + json, m.find());
        return Integer.parseInt(m.group(1));
    }
}
