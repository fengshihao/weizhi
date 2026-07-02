package com.weizhi;

import android.content.Context;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * On-device JNI smoke + pack microbench (interpreter .wasm; optional .aot when present).
 */
@RunWith(AndroidJUnit4.class)
public final class WeizhiJniInstrumentedTest {
    private static final String BENCH_TAG = "weizhi-bench";
    private static final int BENCH_CALLS = 1000;

    /* (func (export "add") (param i32 i32) (result i32) (i32.add)) — same as tests/test_engine.c */
    private static final byte[] ADD_WASM = new byte[] {
            0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00, 0x01, 0x07, 0x01, 0x60, 0x02, 0x7f, 0x7f, 0x01,
            0x7f, 0x03, 0x02, 0x01, 0x00, 0x07, 0x07, 0x01, 0x03, 0x61, 0x64, 0x64, 0x00, 0x00, 0x0a, 0x09,
            0x01, 0x07, 0x00, 0x20, 0x00, 0x20, 0x01, 0x6a, 0x0b,
    };

    @Test
    public void jniSmoke() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File fsRoot = new File(context.getCacheDir(), "weizhi-jni-" + System.currentTimeMillis());
        assertTrue(fsRoot.mkdirs());

        try (WeizhiEngine engine = new WeizhiEngine()) {
            assertEquals("3", engine.runJs("1 + 2", 1000));

            engine.setFsRoot(fsRoot.getAbsolutePath());
            assertEquals(
                    "\"hello\"",
                    engine.runJs("fs.writeFileSync('a.txt','hello'); fs.readFileSync('a.txt').toString()", 2000));

            assertEquals(
                    "\"world\"",
                    engine.runJs(
                            "await fs.promises.writeFile('b.txt','world');"
                                    + "(await fs.promises.readFile('b.txt')).toString()",
                            5000));
        }

        WeizhiLimits limits = new WeizhiLimits();
        limits.fsIoBytes = 16;
        try (WeizhiEngine engine = new WeizhiEngine(limits)) {
            engine.setFsRoot(fsRoot.getAbsolutePath());
            try {
                engine.runJs("fs.writeFileSync('big.txt','abcdefghijklmnopqrstuvwxyz')", 1000);
                fail("expected fs size limit");
            } catch (RuntimeException e) {
                assertTrue(e.getMessage() != null && e.getMessage().contains("too large"));
            }
        }
    }

    @Test
    public void loadPackWasm() throws Exception {
        File packDir = newPackDir("pack-wasm");
        writeBytes(new File(packDir, "add.wasm"), ADD_WASM);
        try (WeizhiEngine engine = new WeizhiEngine()) {
            engine.setPackFolder(packDir.getAbsolutePath());
            assertEquals("42", engine.runJs("const p = loadPack(\"add\"); p.add(20, 22)", 2000));
        }
    }

    @Test
    public void benchPackWasm() throws Exception {
        File packDir = newPackDir("bench-wasm");
        writeBytes(new File(packDir, "add.wasm"), ADD_WASM);
        runPackBench("wasm", packDir);
    }

    @Test
    public void loadPackAot() throws Exception {
        File aot = resolveBundledAot();
        Assume.assumeTrue("add.aot not bundled (build with WEIZHI_WAMR_AOT=1)", aot != null && aot.isFile());

        File packDir = newPackDir("pack-aot");
        Files.copy(aot.toPath(), new File(packDir, "add.aot").toPath());
        try (WeizhiEngine engine = new WeizhiEngine()) {
            engine.setPackFolder(packDir.getAbsolutePath());
            assertEquals("42", engine.runJs("const p = loadPack(\"add\"); p.add(20, 22)", 2000));
        }
    }

    @Test
    public void benchPackAot() throws Exception {
        File aot = resolveBundledAot();
        Assume.assumeTrue("add.aot not bundled (build with WEIZHI_WAMR_AOT=1)", aot != null && aot.isFile());

        File packDir = newPackDir("bench-aot");
        Files.copy(aot.toPath(), new File(packDir, "add.aot").toPath());
        runPackBench("aot", packDir);
    }

    private static void runPackBench(String kind, File packDir) throws Exception {
        try (WeizhiEngine engine = new WeizhiEngine()) {
            engine.setPackFolder(packDir.getAbsolutePath());

            long t0 = System.nanoTime();
            assertEquals("42", engine.runJs("globalThis.__p = loadPack(\"add\"); __p.add(20, 22)", 5000));
            long loadMs = (System.nanoTime() - t0) / 1_000_000L;

            t0 = System.nanoTime();
            String out = engine.runJs(
                    "var s=0; for (var i=0;i<" + BENCH_CALLS + ";i++) s+=__p.add(i,1); s",
                    30_000);
            long callTotalMs = (System.nanoTime() - t0) / 1_000_000L;
            double callAvgUs = (callTotalMs * 1000.0) / BENCH_CALLS;

            Log.i(BENCH_TAG,
                    String.format(
                            "kind=%s pack_load_ms=%d pack_call_total_ms=%d pack_call_avg_us=%.2f calls=%d result=%s",
                            kind, loadMs, callTotalMs, callAvgUs, BENCH_CALLS, out));
        }
    }

    private static File newPackDir(String name) {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File dir = new File(context.getCacheDir(), "weizhi-" + name + "-" + System.currentTimeMillis());
        assertTrue(dir.mkdirs());
        return dir;
    }

    private static void writeBytes(File file, byte[] bytes) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(bytes);
        }
    }

    /** Prefer assets/add.aot, then /data/local/tmp/weizhi-add.aot. */
    private static File resolveBundledAot() throws IOException {
        Context context = InstrumentationRegistry.getInstrumentation().getContext();
        try {
            InputStream in = context.getAssets().open("add.aot");
            File out = new File(context.getCacheDir(), "bundled-add.aot");
            try (FileOutputStream fos = new FileOutputStream(out)) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) {
                    fos.write(buf, 0, n);
                }
            }
            in.close();
            if (out.length() > 0) {
                return out;
            }
        } catch (IOException ignored) {
            /* asset missing when AOT fixture was not packaged */
        }
        File fromRepo = new File("/data/local/tmp/weizhi-add.aot");
        if (fromRepo.isFile() && fromRepo.length() > 0) {
            return fromRepo;
        }
        return null;
    }

    @Test
    public void fetchDisabledGivesAgentHint() throws Exception {
        try (WeizhiEngine engine = new WeizhiEngine()) {
            try {
                engine.runJs("await fetch('https://example.com')", 2000);
                fail("expected fetch disabled");
            } catch (RuntimeException e) {
                assertTrue(e.getMessage() != null && e.getMessage().contains("unsupported: fetch"));
                assertTrue(e.getMessage().contains("enableFetch"));
            }
        }
    }

    @Test
    public void fetchEnabledRoundTrip() throws Exception {
        try (WeizhiEngine engine = new WeizhiEngine()) {
            engine.enableFetch();
            String out = engine.runJs(
                    "const r = await fetch('https://example.com');"
                            + "({ok:r.ok,status:r.status,len:(await r.text()).length})",
                    20_000);
            assertTrue(out.contains("\"status\""));
            assertTrue(out.contains("\"len\""));
        }
    }
}
