package com.weizhi;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.weizhi.caps.WeizhiPackages;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * On-device JNI smoke: arithmetic, fs, script import, fetch hints.
 */
@RunWith(AndroidJUnit4.class)
public final class WeizhiJniInstrumentedTest {
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
    public void bundledWeizhiPackage() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String pkgRoot = WeizhiPackages.syncBundled(context);
        try (WeizhiEngine engine = new WeizhiEngine()) {
            engine.setPackageRoot(pkgRoot);
            assertEquals("\"pong\"", engine.runJs(
                    "import { ping } from 'weizhi:demo';\nexport default ping();\n", 5000));
        }
    }

    @Test
    public void workspaceRelativeImport() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File ws = new File(context.getCacheDir(), "weizhi-ws-" + System.currentTimeMillis());
        assertTrue(ws.mkdirs());
        writeBytes(new File(ws, "util.js"),
                "export function inc(x){ return x + 1; }\n".getBytes(StandardCharsets.UTF_8));
        try (WeizhiEngine engine = new WeizhiEngine()) {
            engine.setFsRoot(ws.getAbsolutePath());
            assertEquals("42", engine.runJs(
                    "import { inc } from './util.js';\nexport default inc(41);\n", 2000, "run.js"));
        }
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

    @Test
    public void nativeMockEnsureAndCall() throws Exception {
        try (WeizhiEngine engine = new WeizhiEngine()) {
            engine.enableNativeMock();
            String out = engine.runJs(
                    "const p = await host.ensureNative('echo_math');"
                            + "({name:p.name,version:p.version,sum:p.add([20,22]),product:p.mul([3,7]),"
                            + "caps:process.weizhiCaps.native})",
                    5000);
            assertTrue(out.contains("\"sum\":42"));
            assertTrue(out.contains("\"product\":21"));
            assertTrue(out.contains("\"caps\":true"));
            assertTrue(out.contains("echo_math"));
        }
    }

    @Test
    public void nativeMockVerifyAndCatalogErrors() throws Exception {
        try (WeizhiEngine engine = new WeizhiEngine()) {
            engine.enableNativeMock();
            try {
                engine.runJs("await host.ensureNative('bad_sig')", 5000);
                fail("expected signature failure");
            } catch (RuntimeException e) {
                assertTrue(e.getMessage() != null && e.getMessage().contains("signature"));
            }
            try {
                engine.runJs("await host.ensureNative('no_such_plugin')", 5000);
                fail("expected catalog miss");
            } catch (RuntimeException e) {
                assertTrue(e.getMessage() != null && e.getMessage().contains("not in catalog"));
            }
            try {
                engine.runJs("await host.ensureNative('too_new')", 5000);
                fail("expected abi incompatible");
            } catch (RuntimeException e) {
                assertTrue(e.getMessage() != null && e.getMessage().contains("min_host_abi"));
            }
        }
    }

    @Test
    public void nativeDisabledGivesAgentHint() throws Exception {
        try (WeizhiEngine engine = new WeizhiEngine()) {
            try {
                engine.runJs("await host.ensureNative('echo_math')", 2000);
                fail("expected native disabled");
            } catch (RuntimeException e) {
                assertTrue(e.getMessage() != null && e.getMessage().contains("unsupported: native"));
                assertTrue(e.getMessage().contains("enableNativeMock") || e.getMessage().contains("weizhi_set_native"));
            }
        }
    }

    @Test
    public void typedPluginLoader() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File pluginRoot = new File(context.getCacheDir(), "weizhi-plugins-" + System.currentTimeMillis());
        File echoDir = new File(pluginRoot, "echo_math");
        assertTrue(echoDir.mkdirs());
        copyAssetTo("plugins/echo_math/manifest.json", new File(echoDir, "manifest.json"));
        copyAssetTo("plugins/echo_math/libecho_math.so", new File(echoDir, "libecho_math.so"));

        try (WeizhiEngine engine = new WeizhiEngine()) {
            engine.enableNativePlugins(pluginRoot.getAbsolutePath());
            String out = engine.runJs(
                    "const p = await host.ensureNative('echo_math');"
                            + "const sum = p.add(20, 22);"
                            + "const e = p.echo_bytes(Buffer.from('ab'));"
                            + "let seen = 0;"
                            + "const n = p.count_with_cb(3, (i) => { seen += i; });"
                            + "({sum, elen:e.length, n, seen})",
                    8000);
            assertTrue(out.contains("\"sum\":42"));
            assertTrue(out.contains("\"elen\":2"));
            assertTrue(out.contains("\"n\":3"));
            assertTrue(out.contains("\"seen\":3"));
        }
    }

    /** IDL v2 channel PoC for the video pipeline: i64 handles, str args/returns, cb(s: str), resolve_path. */
    @Test
    public void echoVideoPlugin() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File ws = new File(context.getCacheDir(), "weizhi-video-ws-" + System.currentTimeMillis());
        assertTrue(ws.mkdirs());
        writeBytes(new File(ws, "clip.mp4"), new byte[] {1, 2, 3, 4, 5});
        File pluginRoot = new File(context.getCacheDir(), "weizhi-video-" + System.currentTimeMillis());
        File videoDir = new File(pluginRoot, "echo_video");
        assertTrue(videoDir.mkdirs());
        copyAssetTo("plugins/echo_video/manifest.json", new File(videoDir, "manifest.json"));
        copyAssetTo("plugins/echo_video/libecho_video.so", new File(videoDir, "libecho_video.so"));

        try (WeizhiEngine engine = new WeizhiEngine()) {
            engine.setFsRoot(ws.getAbsolutePath());
            engine.enableNativePlugins(pluginRoot.getAbsolutePath());
            String out = engine.runJs(
                    "const p = await host.ensureNative('echo_video');"
                            + "const meta = JSON.parse(p.probe_media('clip.mp4'));"
                            + "const esc = JSON.parse(p.probe_media('../clip.mp4'));"
                            + "const id = p.open_session(10, '{}');"
                            + "const events = [];"
                            + "const r = JSON.parse(p.run_session(id, (e) => { events.push(e); }));"
                            + "p.close_session(id);"
                            + "({probeOk: meta.ok, size: meta.size, escErr: esc.error,"
                            + "  tracks: meta.tracks.length, cancelled: r.cancelled,"
                            + "  frames: r.frameCount, events: events.length,"
                            + "  lastPercent: events[events.length - 1].percent})",
                    10000);
            assertTrue(out.contains("\"probeOk\":true"));
            assertTrue(out.contains("\"size\":5"));
            assertTrue(out.contains("path escape"));
            assertTrue(out.contains("\"tracks\":1"));
            assertTrue(out.contains("\"cancelled\":false"));
            assertTrue(out.contains("\"frames\":10"));
            assertTrue(out.contains("\"events\":10"));
            assertTrue(out.contains("\"lastPercent\":100"));
        }
    }

    private static void copyAssetTo(String assetPath, File dest) throws IOException {
        Context ctx = InstrumentationRegistry.getInstrumentation().getContext();
        try (InputStream in = ctx.getAssets().open(assetPath);
                FileOutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        }
    }

    private static void writeBytes(File file, byte[] bytes) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(bytes);
        }
    }
}
