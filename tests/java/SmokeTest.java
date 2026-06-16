package tests.java;

import com.weizhi.WeizhiEngine;
import com.weizhi.WeizhiLimits;

import java.nio.file.Files;
import java.nio.file.Path;

/** Desktop JNI smoke: open → sync fs → promises fs → limits → close. */
public final class SmokeTest {
    public static void main(String[] args) throws Exception {
        Path fsRoot = Files.createTempDirectory("weizhi-jni-");
        try (WeizhiEngine engine = new WeizhiEngine()) {
            String out = engine.runJs("1 + 2", 1000);
            expectEq("3", out);

            engine.setFsRoot(fsRoot.toString());
            out = engine.runJs("fs.writeFileSync('a.txt','hello'); fs.readFileSync('a.txt').toString()", 2000);
            expectEq("\"hello\"", out);

            out = engine.runJs(
                    "await fs.promises.writeFile('b.txt','world');"
                            + "(await fs.promises.readFile('b.txt')).toString()",
                    5000);
            expectEq("\"world\"", out);
        }

        WeizhiLimits limits = new WeizhiLimits();
        limits.fsIoBytes = 16;
        try (WeizhiEngine engine = new WeizhiEngine(limits)) {
            engine.setFsRoot(fsRoot.toString());
            try {
                engine.runJs("fs.writeFileSync('big.txt','abcdefghijklmnopqrstuvwxyz')", 1000);
                fail("expected fs size limit");
            } catch (RuntimeException e) {
                if (e.getMessage() == null || !e.getMessage().contains("too large")) {
                    throw e;
                }
            }
        }

        System.out.println("JNI smoke OK");
    }

    private static void expectEq(String want, String got) {
        if (want == null || !want.equals(got)) {
            throw new AssertionError("want=" + want + " got=" + got);
        }
    }

    private static void fail(String msg) {
        throw new AssertionError(msg);
    }
}
