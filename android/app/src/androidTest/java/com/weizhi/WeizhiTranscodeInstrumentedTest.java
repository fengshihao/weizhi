package com.weizhi;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.weizhi.video.WeizhiVideo;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * On-device P2 gate for the bundled weizhi_transcode plugin: re-encoding export
 * through the decoder→encoder surface tunnel, driven end-to-end from JS.
 */
@RunWith(AndroidJUnit4.class)
public final class WeizhiTranscodeInstrumentedTest {

    @Test
    public void trimConcatReencode() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File ws = new File(context.getCacheDir(), "weizhi-tx-" + System.currentTimeMillis());
        assertTrue(ws.mkdirs());
        AvcFixtures.encode(ws, "a.mp4", AvcFixtures.DEFAULT_MS, 60);
        AvcFixtures.encode(ws, "b.mp4", AvcFixtures.DEFAULT_MS, 200);

        try (WeizhiEngine engine = new WeizhiEngine()) {
            engine.setFsRoot(ws.getAbsolutePath());
            WeizhiVideo.install(engine);
            String out = engine.runJs(
                    "const t = await host.ensureNative('transcode');"
                            + "const v = await host.ensureNative('video');"
                            + "const job = t.open_job(JSON.stringify({"
                            + "  clips: [{path: 'a.mp4', startMs: 200, endMs: 800}, {path: 'b.mp4'}],"
                            + "  output: 'out.mp4',"
                            + "  video: {bitrate: 500000}}));"
                            + "const events = [];"
                            + "const r = JSON.parse(t.run_job(job, (e) => { events.push(e); }));"
                            + "t.close_job(job);"
                            + "const po = r.ok ? JSON.parse(v.probe('out.mp4')) : {tracks: []};"
                            + "({ok: r.ok, dur: r.durationMs, w: r.width, outTracks: po.tracks.length,"
                            + "  outDur: po.durationMs, outMime: po.tracks.length ? po.tracks[0].mime : '',"
                            + "  events: events.length})",
                    60_000);
            assertTrue(out, out.contains("\"ok\":true"));
            assertTrue(out, out.contains("\"w\":320"));
            assertTrue(out, out.contains("\"outTracks\":1"));
            assertTrue(out, out.contains("avc"));
            int dur = WeizhiVideoInstrumentedTest.jsonInt(out, "dur");
            assertTrue("dur=" + dur, dur > 1300 && dur < 2100);
            int outDur = WeizhiVideoInstrumentedTest.jsonInt(out, "outDur");
            assertTrue("outDur=" + outDur, outDur > 1300 && outDur < 2100);
            assertTrue("no progress events",
                    WeizhiVideoInstrumentedTest.jsonInt(out, "events") >= 1);
        }
    }

    @Test
    public void scalingIsGuardedUntilP3() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File ws = new File(context.getCacheDir(), "weizhi-txg-" + System.currentTimeMillis());
        assertTrue(ws.mkdirs());
        AvcFixtures.encode(ws, "a.mp4", AvcFixtures.DEFAULT_MS, 90);

        try (WeizhiEngine engine = new WeizhiEngine()) {
            engine.setFsRoot(ws.getAbsolutePath());
            WeizhiVideo.install(engine);
            String out = engine.runJs(
                    "const t = await host.ensureNative('transcode');"
                            + "const job = t.open_job(JSON.stringify({"
                            + "  clips: [{path: 'a.mp4'}], output: 'o.mp4',"
                            + "  video: {width: 100, height: 100}}));"
                            + "const r = JSON.parse(t.run_job(job, () => {}));"
                            + "t.close_job(job);"
                            + "({err: r.error})",
                    30_000);
            assertTrue(out, out.contains("scaling"));
        }
    }

    /** cancel(): a long transcode must unwind promptly and leave no output file. */
    @Test
    public void cancelLongTranscode() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File ws = new File(context.getCacheDir(), "weizhi-txc-" + System.currentTimeMillis());
        assertTrue(ws.mkdirs());
        AvcFixtures.encode(ws, "long.mp4", 10_000, 120);
        File out = new File(ws, "o.mp4");

        try (WeizhiEngine engine = new WeizhiEngine()) {
            engine.setFsRoot(ws.getAbsolutePath());
            WeizhiVideo.install(engine);
            AtomicReference<Throwable> error = new AtomicReference<>();
            CountDownLatch done = new CountDownLatch(1);
            Thread runner = new Thread(() -> {
                try {
                    engine.runJs(
                            "const t = await host.ensureNative('transcode');"
                                    + "const job = t.open_job(JSON.stringify({"
                                    + "  clips: [{path: 'long.mp4'}], output: 'o.mp4'}));"
                                    + "t.run_job(job, () => {});",
                            120_000);
                } catch (Throwable e) {
                    error.set(e);
                } finally {
                    done.countDown();
                }
            });
            runner.start();
            Thread.sleep(700);
            engine.cancel();
            assertTrue(done.await(15, java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(error.get() != null && error.get().getMessage() != null
                    && error.get().getMessage().contains("cancelled"));
            assertTrue("partial output must be removed on cancel", !out.exists());
        }
    }
}
