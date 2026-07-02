package com.weizhi;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * On-device JNI smoke: arithmetic, fs, loadScript, fetch hints.
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
    public void loadScript() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File scriptDir = new File(context.getCacheDir(), "weizhi-scripts-" + System.currentTimeMillis());
        assertTrue(scriptDir.mkdirs());
        writeBytes(new File(scriptDir, "util.js"),
                "globalThis.inc = function(x){ return x + 1; }; 0".getBytes(StandardCharsets.UTF_8));
        try (WeizhiEngine engine = new WeizhiEngine()) {
            engine.setScriptFolder(scriptDir.getAbsolutePath());
            assertEquals("42", engine.runJs("loadScript(\"util.js\"); inc(41)", 2000));
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

    private static void writeBytes(File file, byte[] bytes) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(bytes);
        }
    }
}
