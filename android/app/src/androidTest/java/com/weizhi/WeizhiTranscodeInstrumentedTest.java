package com.weizhi;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.weizhi.caps.WeizhiPackages;
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

    /**
     * GPU path: scale 320x240 -> 176x144 (QCIF) through the package DSL. The
     * target is macroblock-aligned and above this device's minimum: its AVC
     * codec accepts width 160 at configure() but the component then dies with
     * no frame encoded (see GlEncoderProbeInstrumentedTest).
     */
    @Test
    public void scaleViaPackage() throws Exception {
        runScaleExport("small.mp4");
    }

    private void runScaleExport(String outName) throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File ws = new File(context.getCacheDir(), "weizhi-txs-" + System.currentTimeMillis());
        assertTrue(ws.mkdirs());
        AvcFixtures.encode(ws, "a.mp4", AvcFixtures.DEFAULT_MS, 90);
        String pkgRoot = WeizhiPackages.syncBundled(context);

        try (WeizhiEngine engine = new WeizhiEngine()) {
            engine.setFsRoot(ws.getAbsolutePath());
            engine.setPackageRoot(pkgRoot);
            WeizhiVideo.install(engine);
            String out;
            try {
                out = engine.runJs(
                        "import { exportVideo } from 'weizhi:video';\n"
                                + "import fs from 'fs';\n"
                                + "const v = await host.ensureNative('video');\n"
                                + "const r = await exportVideo({\n"
                                + "  clips: [{path: 'a.mp4'}],\n"
                                + "  output: '" + outName + "',\n"
                                + "  width: 176, height: 144,\n"
                                + "});\n"
                                + "const po = JSON.parse(v.probe('" + outName + "'));\n"
                                + "const log = fs.existsSync('" + outName + ".log')\n"
                                + "  ? fs.readFileSync('" + outName + ".log').toString() : '';\n"
                                + "export default {ok: r.ok, w: r.width, h: r.height,\n"
                                + "  pw: po.tracks.length ? po.tracks[0].width : 0,\n"
                                + "  ph: po.tracks.length ? po.tracks[0].height : 0,\n"
                                + "  probeErr: po.error || '', dur: r.durationMs, log};\n",
                        60_000, "scale.js");
            } catch (RuntimeException e) {
                throw new RuntimeException(e.getMessage() + "\n== job log ==\n" + readJobLog(ws, outName + ".log"), e);
            }
            assertTrue(out, out.contains("\"ok\":true"));
            assertTrue(out, out.contains("\"w\":176"));
            assertTrue(out, out.contains("\"h\":144"));
            assertTrue(out, out.contains("\"pw\":176"));
            assertTrue(out, out.contains("\"ph\":144"));
            int dur = WeizhiVideoInstrumentedTest.jsonInt(out, "dur");
            assertTrue("dur=" + dur, dur > 800 && dur < 1300);
        }
    }

    /**
     * GPU path pixel assertion: brightness(amount 0.5) must raise the decoded
     * luma mean. Y deltas are attenuated by the limited-range round trip
     * ((Y-16)*(1+amount)+16), so 0.5 yields ~+17 on a luma-50 fixture.
     */
    @Test
    public void brightnessPixelShift() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File ws = new File(context.getCacheDir(), "weizhi-txb-" + System.currentTimeMillis());
        assertTrue(ws.mkdirs());
        AvcFixtures.encode(ws, "a.mp4", AvcFixtures.DEFAULT_MS, 50);
        String pkgRoot = WeizhiPackages.syncBundled(context);

        try (WeizhiEngine engine = new WeizhiEngine()) {
            engine.setFsRoot(ws.getAbsolutePath());
            engine.setPackageRoot(pkgRoot);
            WeizhiVideo.install(engine);
            engine.runJs(
                    "import { exportVideo } from 'weizhi:video';\n"
                            + "await exportVideo({clips: [{path: 'a.mp4'}], output: 'plain.mp4', bitrate: 2000000});\n"
                            + "await exportVideo({clips: [{path: 'a.mp4'}], output: 'bright.mp4',\n"
                            + "  bitrate: 2000000,\n"
                            + "  filters: [{type: 'brightness', params: {amount: 0.5}}],\n"
                            + "  onProgress: () => {}});\n"
                            + "export default 'done';\n",
                    60_000, "bright.js");
            int plain = firstFrameYMean(new File(ws, "plain.mp4"));
            int bright = firstFrameYMean(new File(ws, "bright.mp4"));
            assertTrue("plain=" + plain + " bright=" + bright, bright > plain + 10);
        }
    }

    /** One filter per job is the native rule; chains report an English error. */
    @Test
    public void filterChainGuarded() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File ws = new File(context.getCacheDir(), "weizhi-txc2-" + System.currentTimeMillis());
        assertTrue(ws.mkdirs());
        AvcFixtures.encode(ws, "a.mp4", AvcFixtures.DEFAULT_MS, 70);
        String pkgRoot = WeizhiPackages.syncBundled(context);

        try (WeizhiEngine engine = new WeizhiEngine()) {
            engine.setFsRoot(ws.getAbsolutePath());
            engine.setPackageRoot(pkgRoot);
            WeizhiVideo.install(engine);
            try {
                engine.runJs(
                        "import { exportVideo } from 'weizhi:video';\n"
                                + "await exportVideo({clips: [{path: 'a.mp4'}], output: 'o.mp4',\n"
                                + "  filters: ['brightness', 'grayscale']});\n"
                                + "export default 'no-error';\n",
                        30_000, "chain.js");
                fail("expected chain guard");
            } catch (RuntimeException e) {
                assertTrue(e.getMessage(), e.getMessage().contains("filter chain"));
            }
        }
    }

    /**
     * GPU path LUT assertion: a constant-white .cube must map every pixel to
     * limited-range white (Y ~235), and a size-2 identity LUT must leave the
     * luma mean unchanged (trilinear between corners is exactly linear).
     */
    @Test
    public void lutPixelMap() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File ws = new File(context.getCacheDir(), "weizhi-txl-" + System.currentTimeMillis());
        assertTrue(ws.mkdirs());
        AvcFixtures.encode(ws, "a.mp4", AvcFixtures.DEFAULT_MS, 50);
        writeText(ws, "white.cube",
                "LUT_3D_SIZE 2\n"
                        + "1 1 1\n1 1 1\n1 1 1\n1 1 1\n1 1 1\n1 1 1\n1 1 1\n1 1 1\n");
        writeText(ws, "identity.cube",
                "LUT_3D_SIZE 2\n"
                        + "0 0 0\n1 0 0\n0 1 0\n1 1 0\n0 0 1\n1 0 1\n0 1 1\n1 1 1\n");
        String pkgRoot = WeizhiPackages.syncBundled(context);

        try (WeizhiEngine engine = new WeizhiEngine()) {
            engine.setFsRoot(ws.getAbsolutePath());
            engine.setPackageRoot(pkgRoot);
            WeizhiVideo.install(engine);
            try {
                engine.runJs(
                        "import { exportVideo } from 'weizhi:video';\n"
                                + "await exportVideo({clips: [{path: 'a.mp4'}], output: 'plain.mp4',"
                                + " bitrate: 2000000});\n"
                                + "await exportVideo({clips: [{path: 'a.mp4'}], output: 'lutw.mp4',\n"
                                + "  bitrate: 2000000,"
                                + " filters: [{type: 'lut', params: {path: 'white.cube'}}]});\n"
                                + "await exportVideo({clips: [{path: 'a.mp4'}], output: 'luti.mp4',\n"
                                + "  bitrate: 2000000,"
                                + " filters: [{type: 'lut', params: {path: 'identity.cube'}}]});\n"
                                + "export default 'done';\n",
                        60_000, "lut.js");
            } catch (RuntimeException e) {
                throw new RuntimeException(e.getMessage() + "\n== job log ==\n"
                        + readJobLog(ws, "lutw.mp4.log") + readJobLog(ws, "luti.mp4.log"), e);
            }
            int plain = firstFrameYMean(new File(ws, "plain.mp4"));
            int white = firstFrameYMean(new File(ws, "lutw.mp4"));
            int identity = firstFrameYMean(new File(ws, "luti.mp4"));
            assertTrue("plain=" + plain + " white=" + white + " identity=" + identity,
                    white > 200 && Math.abs(identity - plain) <= 6);
        }
    }

    /**
     * Fade transition (P3c): boundary frames fade through black while mid-clip
     * frames keep the fixture luma. Fades live on clip boundaries only — the
     * tail of the last clip (timeline end) does NOT fade. Fade 200ms between
     * two 1s clips; the audio track is untouched (video-only semantic).
     */
    @Test
    public void fadeTransitionBoundary() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File ws = new File(context.getCacheDir(), "weizhi-txf-" + System.currentTimeMillis());
        assertTrue(ws.mkdirs());
        AvcFixtures.encode(ws, "a.mp4", 1000, 60);
        AvcFixtures.encode(ws, "b.mp4", 1000, 60);
        String pkgRoot = WeizhiPackages.syncBundled(context);

        try (WeizhiEngine engine = new WeizhiEngine()) {
            engine.setFsRoot(ws.getAbsolutePath());
            engine.setPackageRoot(pkgRoot);
            WeizhiVideo.install(engine);
            String out;
            try {
                out = engine.runJs(
                        "import { exportVideo } from 'weizhi:video';\n"
                                + "import fs from 'fs';\n"
                                + "const r = await exportVideo({clips: [{path: 'a.mp4'},"
                                + " {path: 'b.mp4'}],\n"
                                + "  output: 'fade.mp4', bitrate: 2000000,\n"
                                + "  transitions: [{type: 'fade', durationMs: 200}]});\n"
                                + "const log = fs.existsSync('fade.mp4.log')"
                                + " ? fs.readFileSync('fade.mp4.log').toString() : '';\n"
                                + "export default {ok: r.ok, dur: r.durationMs, log};\n",
                        60_000, "fade.js");
            } catch (RuntimeException e) {
                throw new RuntimeException(e.getMessage() + "\n== job log ==\n"
                        + readJobLog(ws, "fade.mp4.log"), e);
            }
            assertTrue(out, out.contains("\"ok\":true"));
            int dur = WeizhiVideoInstrumentedTest.jsonInt(out, "dur");
            assertTrue("dur=" + dur, dur > 1800 && dur < 2200);
            File fade = new File(ws, "fade.mp4");
            int mid = yMeanAtPts(fade, 400_000);
            int edge1 = yMeanAtPts(fade, 950_000);
            int edge2 = yMeanAtPts(fade, 1_050_000);
            int tail = yMeanAtPts(fade, 1_950_000);
            assertTrue("mid=" + mid + " edge1=" + edge1 + " edge2=" + edge2 + " tail=" + tail,
                    mid >= 45 && mid <= 75 && edge1 < 40 && edge2 < 40
                            && tail >= 45 && tail <= 75);
        }
    }

    private static void writeText(File ws, String name, String text) throws Exception {
        java.io.FileOutputStream stream = new java.io.FileOutputStream(new File(ws, name));
        stream.write(text.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        stream.close();
    }

    /** Decode the first frame and average the Y plane (pixel-level assertion helper). */
    private static int firstFrameYMean(File mp4) throws Exception {
        return yMeanAtPts(mp4, 0);
    }

    /** Average the Y plane of the first decoded frame at or after targetUs. */
    private static int yMeanAtPts(File mp4, long targetUs) throws Exception {
        android.media.MediaExtractor ex = new android.media.MediaExtractor();
        ex.setDataSource(mp4.getAbsolutePath());
        int track = -1;
        for (int i = 0; i < ex.getTrackCount(); i++) {
            String mime = ex.getTrackFormat(i).getString(android.media.MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("video/")) {
                track = i;
                break;
            }
        }
        assertTrue("no video track", track >= 0);
        ex.selectTrack(track);
        android.media.MediaCodec codec =
                android.media.MediaCodec.createDecoderByType("video/avc");
        codec.configure(ex.getTrackFormat(track), null, null, 0);
        codec.start();
        try {
            android.media.MediaCodec.BufferInfo info = new android.media.MediaCodec.BufferInfo();
            boolean inputEos = false;
            long deadline = System.currentTimeMillis() + 10_000;
            while (System.currentTimeMillis() < deadline) {
                int inIdx = codec.dequeueInputBuffer(10_000);
                if (inIdx >= 0 && !inputEos) {
                    int size = ex.readSampleData(codec.getInputBuffer(inIdx), 0);
                    if (size < 0) {
                        codec.queueInputBuffer(inIdx, 0, 0, 0,
                                android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM);
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
                        java.nio.ByteBuffer y = image.getPlanes()[0].getBuffer();
                        long sum = 0;
                        int n = y.remaining();
                        for (int i = 0; i < n; i++) {
                            sum += y.get(i) & 0xff;
                        }
                        int mean = (int) (sum / Math.max(1, n));
                        image.close();
                        codec.releaseOutputBuffer(outIdx, false);
                        return mean;
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

    /** This repo's test devices have no readable logcat: surface the job log on failure. */
    private static String readJobLog(File ws, String name) {
        try {
            File log = new File(ws, name);
            if (!log.exists()) {
                return "(no log file)";
            }
            java.util.Scanner scanner = new java.util.Scanner(log, "UTF-8");
            StringBuilder sb = new StringBuilder();
            while (scanner.hasNextLine() && sb.length() < 4000) {
                sb.append(scanner.nextLine()).append('\n');
            }
            scanner.close();
            return sb.toString();
        } catch (Exception e) {
            return "(log read failed: " + e.getMessage() + ")";
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
