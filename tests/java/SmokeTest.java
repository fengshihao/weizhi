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

            try {
                engine.runJs("const a=1;\nconst b=2;\nconst c=3;\nconst d=4;\n}\n", 1000, "scripts/foo.js");
                fail("expected syntax error with filename stack");
            } catch (RuntimeException e) {
                String msg = e.getMessage();
                if (msg == null || !msg.contains("scripts/foo.js") || !msg.contains(":5")) {
                    fail("stack missing scripts/foo.js:5 in: " + msg);
                }
            }

            engine.setFsRoot(fsRoot.toString());
            out = engine.runJs("fs.writeFileSync('a.txt','hello'); fs.readFileSync('a.txt').toString()", 2000);
            expectEq("\"hello\"", out);

            out = engine.runJs(
                    "await fs.promises.writeFile('b.txt','world');"
                            + "(await fs.promises.readFile('b.txt')).toString()",
                    5000);
            expectEq("\"world\"", out);

            out = engine.runJs(
                    "const z = require('zlib');"
                            + "z.gunzipSync(z.gzipSync(Buffer.from('hello zlib'))).toString()",
                    3000);
            expectEq("\"hello zlib\"", out);
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
        OfficeTest.main(args);
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
            engine.runJs(platform + ".files.mkdir('pack'); " + platform + ".files.write('pack/a.txt','hello zip')",
                    3000);
            String zipMsg = engine.runJs(platform + ".files.zipCreate('pack', 'out.zip')", 5000);
            if (zipMsg == null || !zipMsg.contains("1 files")) {
                throw new AssertionError("zipCreate: " + zipMsg);
            }
            String extractMsg = engine.runJs(platform + ".files.zipExtract('out.zip', 'unpacked')", 5000);
            if (extractMsg == null || !extractMsg.contains("entries")) {
                throw new AssertionError("zipExtract: " + extractMsg);
            }
            expectEq("\"hello zip\"", engine.runJs(platform + ".files.read('unpacked/a.txt')", 3000));
            String defaultDest = engine.runJs(platform + ".files.zipExtract('out.zip')", 5000);
            if (defaultDest == null || !defaultDest.contains("tmp/out")) {
                throw new AssertionError("zipExtract default dest: " + defaultDest);
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
