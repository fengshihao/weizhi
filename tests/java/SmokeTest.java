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
        desktopCaps();
    }

    private static void desktopCaps() throws Exception {
        String platform = com.weizhi.desktop.DesktopCaps.platformObjectName();
        if (platform == null) {
            throw new AssertionError("desktop platform object missing");
        }
        Path root = Files.createTempDirectory("weizhi-caps-");
        try (WeizhiEngine engine = new WeizhiEngine()) {
            com.weizhi.desktop.DesktopCaps.install(engine, root, message -> true);
            String out = engine.runJs(
                    platform + ".files.write('a.txt','hi'); " + platform + ".files.read('a.txt')", 3000);
            expectEq("\"hi\"", out);
            expectEq("true", engine.runJs(platform + ".ui.confirm('go')", 3000));
            String other = "mac".equals(platform) ? "linux" : "mac";
            try {
                engine.runJs(other + ".files.read('a.txt')", 2000);
                fail("expected other platform to be unsupported");
            } catch (RuntimeException e) {
                if (e.getMessage() == null || !e.getMessage().contains("unsupported")) {
                    throw e;
                }
            }
            try {
                engine.runJs(platform + ".media.resize('a.txt', 32)", 2000);
                fail("expected media.resize unsupported on desktop");
            } catch (RuntimeException e) {
                if (e.getMessage() == null || !e.getMessage().contains("unsupported")) {
                    throw e;
                }
            }
        }
        System.out.println("Desktop caps OK (" + platform + ")");
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
