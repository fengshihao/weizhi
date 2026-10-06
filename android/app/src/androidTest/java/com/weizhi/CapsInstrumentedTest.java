package com.weizhi;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.weizhi.caps.AndroidCaps;
import com.weizhi.platform.OrganizeFiles;
import com.weizhi.platform.PlatformHost;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * On-device checks for the android.* productivity surface.
 * Each case installs caps and runs JS. No accessibility / package list / root.
 */
@RunWith(AndroidJUnit4.class)
public final class CapsInstrumentedTest {
    @Test
    public void filesConfirmAuditAndUndo() throws Exception {
        File workspace = workspace("files");
        try (WeizhiEngine engine = new WeizhiEngine()) {
            AndroidCaps.Session session = new AndroidCaps.Session(context(), workspace);
            session.confirmer = new PlatformHost.Confirmer() {
                @Override
                public boolean confirm(String message) {
                    return message.contains("继续");
                }
            };
            AndroidCaps.install(engine, session);
            String out = engine.runJs(
                    "if (!android.ui.confirm('继续整理')) throw new Error('confirm');"
                            + "android.files.write('keep/a.txt','');"
                            + "android.files.write('note.txt','发票');"
                            + "android.files.rename('note.txt','合同.txt');"
                            + "android.files.move('合同.txt','keep');"
                            + "var moved = android.files.read('keep/合同.txt');"
                            + "var u = android.files.undo();"
                            + "({moved:moved, undo:u.ok, back:android.files.read('合同.txt'),"
                            + "n:android.audit.recent().length, platform:android.platform})",
                    8000);
            assertTrue(out.contains("\"moved\":\"发票\""));
            assertTrue(out.contains("\"undo\":true"));
            assertTrue(out.contains("\"back\":\"发票\""));
            assertTrue(out.contains("\"platform\":\"android\""));
            assertTrue(out.contains("\"n\":"));
        }
    }

    @Test
    public void otherPlatformsAreUnsupported() throws Exception {
        File workspace = workspace("stub");
        try (WeizhiEngine engine = new WeizhiEngine()) {
            AndroidCaps.install(engine, new AndroidCaps.Session(context(), workspace));
            String mac = engine.runJs(
                    "try { mac.files.read('a') } catch (e) { e.message }", 3000);
            String linux = engine.runJs(
                    "try { linux.share.send({text:'x'}) } catch (e) { e.message }", 3000);
            String linuxIntent = engine.runJs(
                    "try { linux.intent.start({action:'view', path:'a.txt'}) } catch (e) { e.message }", 3000);
            assertTrue(mac.contains("unsupported"));
            assertTrue(mac.contains("platform is android"));
            assertTrue(linux.contains("unsupported"));
            assertTrue(linuxIntent.contains("unsupported"));
        }
    }

    @Test
    public void intentStartViewAndRejects() throws Exception {
        File workspace = workspace("intent");
        write(new File(workspace, "note.txt"), "hello");
        try (WeizhiEngine engine = new WeizhiEngine()) {
            AndroidCaps.Session session = new AndroidCaps.Session(context(), workspace);
            session.launchIntent = false;
            AndroidCaps.install(engine, session);
            String view = engine.runJs("android.intent.start({action:'view', path:'note.txt'})", 3000);
            assertTrue(view, view.contains("\"ok\":true"));
            assertTrue(view, view.contains("android.intent.action.VIEW"));
            assertTrue(view, view.contains("text/plain"));
            String panel = engine.runJs("android.intent.start({action:'panel', panel:'wifi', chooser:false})", 3000);
            assertTrue(panel, panel.contains("android.settings.panel.action.WIFI"));
            String volume = engine.runJs("android.intent.start({action:'panel', panel:'volume', chooser:false})", 3000);
            assertTrue(volume, volume.contains("panel.action.VOLUME"));
            String edit = engine.runJs("android.intent.start({action:'edit', path:'note.txt'})", 3000);
            assertTrue(edit, edit.contains("android.intent.action.EDIT"));
            write(new File(workspace, "b.txt"), "b");
            String multi = engine.runJs(
                    "android.intent.start({action:'send_multiple', paths:['note.txt','b.txt'], chooser:false})",
                    3000);
            assertTrue(multi, multi.contains("android.intent.action.SEND_MULTIPLE"));
            assertTrue(multi, multi.contains("\"count\":2"));
            String settings = engine.runJs(
                    "android.intent.start({action:'settings', screen:'locale', chooser:false})", 3000);
            assertTrue(settings, settings.contains("LOCALE"));
            assertTrue(settings, settings.contains("\"screen\":\"locale\""));
            String link = engine.runJs(
                    "android.intent.start({action:'view', data:'https://example.com', chooser:false})", 3000);
            assertTrue(link, link.contains("android.intent.action.VIEW"));
            expectFail(engine, "android.intent.start({action:'view', path:'../note.txt'})", "escape");
            expectFail(engine, "android.intent.start({action:'view', path:'/etc/passwd'})", "escape");
            expectFail(engine, "android.intent.start({action:'view', data:'file:///etc/passwd'})", "bad argument");
            expectFail(engine, "android.intent.start({action:'view', data:'content://x'})", "bad argument");
            expectFail(engine, "android.intent.start({action:'view', data:'intent://x'})", "bad argument");
            expectFail(engine, "android.intent.start({action:'delete'})", "unsupported");
            expectFail(engine, "android.intent.start({action:'panel', panel:'airplane'})", "unsupported");
            expectFail(engine, "android.intent.start({action:'settings', screen:'airplane'})", "unsupported");
        }
    }

    @Test
    public void resizeShareReminderAndCancelPick() throws Exception {
        File workspace = workspace("media");
        Bitmap bitmap = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(Color.RED);
        try (FileOutputStream out = new FileOutputStream(new File(workspace, "in.png"))) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out));
        }
        bitmap.recycle();

        String[] shared = new String[2];
        try (WeizhiEngine engine = new WeizhiEngine()) {
            File pluginRoot = new File(context().getCacheDir(), "caps-plugins-" + System.currentTimeMillis());
            File imageDir = new File(pluginRoot, "image_resize");
            assertTrue(imageDir.mkdirs());
            copyAsset("plugins/image_resize/manifest.json", new File(imageDir, "manifest.json"));
            copyAsset("plugins/image_resize/libimage_resize.so", new File(imageDir, "libimage_resize.so"));
            engine.enableNativePlugins(pluginRoot.getAbsolutePath());
            AndroidCaps.Session session = new AndroidCaps.Session(context(), workspace);
            session.shareSink = new PlatformHost.ShareSink() {
                @Override
                public void onShare(String title, String text) {
                    shared[0] = title;
                    shared[1] = text;
                }
            };
            session.directoryPicker = new PlatformHost.DirectoryPicker() {
                @Override
                public String pick() {
                    return null;
                }
            };
            AndroidCaps.install(engine, session);
            String resized = engine.runJs(
                    "await host.ensureNative('image_resize'); android.media.resize('in.png', 20)", 8000);
            assertTrue(resized.contains("\"width\":20"));
            assertTrue(resized.contains("\"height\":10"));

            engine.runJs("android.share.send({title:'草稿', text:'朋友圈文案'})", 3000);
            assertEquals("草稿", shared[0]);
            assertEquals("朋友圈文案", shared[1]);

            String reminder = engine.runJs(
                    "var s = android.reminders.schedule({title:'出发', body:'证件', atMs: Date.now()+60000});"
                            + "var f = android.reminders.fire(s.id);"
                            + "var c = android.reminders.cancel(s.id);"
                            + "({id:s.id, ok:f.ok, cancelled:c.ok})",
                    8000);
            assertTrue(reminder.contains("\"ok\":true"));
            assertTrue(reminder.contains("\"cancelled\":true"));

            try {
                engine.runJs("android.files.pickDirectory()", 3000);
                fail("expected cancel");
            } catch (RuntimeException e) {
                assertTrue(e.getMessage() != null && e.getMessage().contains("denied"));
            }
        }
    }

    @Test
    public void organizeConfirmsMovesAndUndoesOnConflict() throws Exception {
        File workspace = workspace("organize");
        write(new File(workspace, "a.txt"), "A");
        write(new File(workspace, "b.pdf"), "B");
        File nested = new File(workspace, "文档");
        assertTrue(nested.mkdirs());
        write(new File(nested, "b.pdf"), "EXIST");

        try (WeizhiEngine engine = new WeizhiEngine()) {
            AndroidCaps.Session session = new AndroidCaps.Session(context(), workspace);
            session.confirmer = new PlatformHost.Confirmer() {
                @Override
                public boolean confirm(String message) {
                    return false;
                }
            };
            AndroidCaps.install(engine, session);
            String cancelled = engine.runJs(OrganizeFiles.run("android"), 8000);
            assertTrue(cancelled.contains("\"cancelled\":true"));
            assertEquals("A", read(new File(workspace, "a.txt")));
        }

        try (WeizhiEngine engine = new WeizhiEngine()) {
            AndroidCaps.Session session = new AndroidCaps.Session(context(), workspace);
            session.confirmer = new PlatformHost.Confirmer() {
                @Override
                public boolean confirm(String message) {
                    return message.contains("整理");
                }
            };
            AndroidCaps.install(engine, session);
            try {
                engine.runJs(OrganizeFiles.run("android"), 8000);
                fail("expected move conflict");
            } catch (RuntimeException e) {
                assertTrue(e.getMessage() != null && e.getMessage().length() > 0);
            }
            assertEquals("A", read(new File(workspace, "a.txt")));
            assertEquals("B", read(new File(workspace, "b.pdf")));
            assertEquals("EXIST", read(new File(nested, "b.pdf")));
        }
    }

    @Test
    public void organizeSortsSandboxFiles() throws Exception {
        File workspace = workspace("sorted");
        write(new File(workspace, "笔记.txt"), "笔记");
        write(new File(workspace, "封面.jpg"), "图");
        try (WeizhiEngine engine = new WeizhiEngine()) {
            AndroidCaps.Session session = new AndroidCaps.Session(context(), workspace);
            session.confirmer = new PlatformHost.Confirmer() {
                @Override
                public boolean confirm(String message) {
                    return true;
                }
            };
            AndroidCaps.install(engine, session);
            String out = engine.runJs(OrganizeFiles.run("android"), 8000);
            assertTrue(out.contains("\"moved\":2"));
            assertEquals("笔记", read(new File(workspace, "文档/笔记.txt")));
            assertEquals("图", read(new File(workspace, "图片/封面.jpg")));
        }
    }

    @Test
    public void docxJsMarkdownToDocx() throws Exception {
        File scriptDir = new File(context().getCacheDir(), "office-scripts-" + System.nanoTime());
        assertTrue(scriptDir.getAbsolutePath(), scriptDir.mkdirs());
        copyAsset("office/docx.js", new File(scriptDir, "docx.js"));
        File workspace = workspace("office");
        write(new File(workspace, "in.md"), "# Title\n\nBody line\n");
        try (WeizhiEngine engine = new WeizhiEngine()) {
            AndroidCaps.install(engine, new AndroidCaps.Session(context(), workspace));
            engine.setScriptFolder(scriptDir.getAbsolutePath());
            String out = engine.runJs(
                    "import { markdownToDocx } from './docx.js';\n"
                            + "export default markdownToDocx({inputPath:'in.md', outputPath:'out/doc.docx'});\n",
                    10000);
            assertTrue(out.contains("\"ok\":true"));
            assertTrue(out.contains("\"path\":\"out/doc.docx\""));
        }
        byte[] head = java.nio.file.Files.readAllBytes(new File(workspace, "out/doc.docx").toPath());
        assertTrue(head.length >= 2);
        assertEquals('P', (char) head[0]);
        assertEquals('K', (char) head[1]);
    }

    @Test
    public void docxJsGrepValidate() throws Exception {
        File scriptDir = new File(context().getCacheDir(), "office-scripts-grep-" + System.nanoTime());
        assertTrue(scriptDir.getAbsolutePath(), scriptDir.mkdirs());
        copyAsset("office/docx.js", new File(scriptDir, "docx.js"));
        copyAsset("office/docx-raw.js", new File(scriptDir, "docx-raw.js"));
        File workspace = workspace("office-grep");
        write(new File(workspace, "in.md"), "# T\n\nFind **needle** here.\n");
        try (WeizhiEngine engine = new WeizhiEngine()) {
            AndroidCaps.install(engine, new AndroidCaps.Session(context(), workspace));
            engine.setScriptFolder(scriptDir.getAbsolutePath());
            String out = engine.runJs(
                    "import { markdownToDocx, readDocx } from './docx.js';\n"
                            + "import { validateDocx } from './docx-raw.js';\n"
                            + "markdownToDocx({ inputPath: 'in.md', outputPath: 'out/x.docx' });\n"
                            + "var doc = readDocx('out/x.docx');\n"
                            + "var g = doc.grep('needle');\n"
                            + "doc.replaceAll('needle', 'found');\n"
                            + "doc.save('out/y.docx');\n"
                            + "var v = validateDocx({ path: 'out/y.docx' });\n"
                            + "export default { matches: g.matches.length, valid: v.ok, text: doc.plainText() };\n",
                    15000);
            assertTrue(out, out.contains("\"matches\":1"));
            assertTrue(out, out.contains("\"valid\":true"));
            assertTrue(out, out.contains("found"));
        }
    }

    @Test
    public void apiCardsRunOnAndroid() throws Exception {
        String jsonl;
        try (java.io.InputStream in = InstrumentationRegistry.getInstrumentation().getContext()
                .getAssets().open("api-cards.jsonl")) {
            jsonl = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        int ran = 0;
        for (String line : jsonl.split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            JSONObject card = new JSONObject(line);
            JSONArray platforms = card.getJSONArray("platforms");
            if (platforms.length() != 1 || !"android".equals(platforms.getString(0))) {
                continue;
            }
            String id = card.getString("id");
            String entry = card.getString("entry");
            File workspace = workspace("card-" + id.replace('.', '-'));
            if ("android.intent.start".equals(id)) {
                write(new File(workspace, "每周AI新闻.docx"), "docx");
                write(new File(workspace, "a.docx"), "a");
                write(new File(workspace, "b.pdf"), "b");
            }
            try (WeizhiEngine engine = new WeizhiEngine()) {
                AndroidCaps.Session session = new AndroidCaps.Session(context(), workspace);
                session.launchIntent = false;
                session.launchShareSheet = false;
                session.directoryPicker = new PlatformHost.DirectoryPicker() {
                    @Override
                    public String pick() {
                        return "content://com.weizhi.smoke.documents/tree/picked";
                    }
                };
                session.shareSink = new PlatformHost.ShareSink() {
                    @Override
                    public void onShare(String title, String text) {
                    }
                };
                AndroidCaps.install(engine, session);
                String out = engine.runJs(entry, 8000);
                assertTrue(id + " => " + out, out != null && out.length() > 2);
                if ("android.intent.start".equals(id) || "android.share.send".equals(id)) {
                    assertTrue(id + " => " + out, out.contains("\"ok\":true"));
                }
                if ("android.files.pickDirectory".equals(id)) {
                    assertTrue(id + " => " + out, out.contains("content://"));
                }
            }
            ran++;
        }
        assertTrue("no android-only api cards", ran >= 3);
    }

    @Test
    public void zipExtractCreateRoundTrip() throws Exception {
        File workspace = workspace("zip");
        try (WeizhiEngine engine = new WeizhiEngine()) {
            AndroidCaps.install(engine, new AndroidCaps.Session(context(), workspace));
            String out = engine.runJs(
                    "android.files.mkdir('pack');"
                            + "android.files.write('pack/a.txt','hello zip');"
                            + "var c = android.files.zipCreate('pack', 'out.zip');"
                            + "var e = android.files.zipExtract('out.zip', 'unz');"
                            + "({c:c, e:e, text:android.files.read('unz/a.txt')})",
                    8000);
            assertTrue(out, out.contains("1 files"));
            assertTrue(out, out.contains("entries"));
            assertTrue(out, out.contains("\"text\":\"hello zip\""));
        }
    }

    private static void copyAsset(String assetPath, File dest) throws Exception {
        Context[] contexts = new Context[] {
                InstrumentationRegistry.getInstrumentation().getContext(),
                context()
        };
        java.io.FileNotFoundException missing = null;
        for (Context ctx : contexts) {
            try (java.io.InputStream in = ctx.getAssets().open(assetPath);
                    java.io.FileOutputStream out = new java.io.FileOutputStream(dest)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
                return;
            } catch (java.io.FileNotFoundException e) {
                missing = e;
            }
        }
        throw missing == null ? new java.io.FileNotFoundException(assetPath) : missing;
    }

    private static void expectFail(WeizhiEngine engine, String js, String needle) {
        try {
            engine.runJs(js, 3000);
            fail(js);
        } catch (RuntimeException e) {
            assertTrue(e.getMessage(), e.getMessage() != null && e.getMessage().contains(needle));
        }
    }

    private static void write(File file, String text) throws Exception {
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(file)) {
            out.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    private static String read(File file) throws Exception {
        return new String(java.nio.file.Files.readAllBytes(file.toPath()), java.nio.charset.StandardCharsets.UTF_8);
    }

    private static Context context() {
        return InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    private static File workspace(String name) {
        File dir = new File(context().getCacheDir(), "caps-" + name + "-" + System.currentTimeMillis());
        assertTrue(dir.mkdirs());
        return dir;
    }
}
