package com.weizhi;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * On-device JNI smoke: open → sync fs → promises fs → limits → close.
 * Mirrors tests/java/SmokeTest.java against libweizhijni.so.
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
                assertTrue(
                        e.getMessage() != null && e.getMessage().contains("too large"));
            }
        }
    }
}
