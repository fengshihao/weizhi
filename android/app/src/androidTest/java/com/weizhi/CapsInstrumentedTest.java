package com.weizhi;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.weizhi.caps.AndroidCaps;
import com.weizhi.platform.PlatformHost;

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
            assertTrue(mac.contains("unsupported"));
            assertTrue(mac.contains("platform is android"));
            assertTrue(linux.contains("unsupported"));
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
            String resized = engine.runJs("android.media.resize('in.png', 20)", 8000);
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

    private static Context context() {
        return InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    private static File workspace(String name) {
        File dir = new File(context().getCacheDir(), "caps-" + name + "-" + System.currentTimeMillis());
        assertTrue(dir.mkdirs());
        return dir;
    }
}
