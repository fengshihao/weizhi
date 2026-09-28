package com.weizhi.caps;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import com.weizhi.WeizhiEngine;
import com.weizhi.platform.LocalWorkspace;
import com.weizhi.platform.MiniJson;
import com.weizhi.platform.PlatformHost;
import com.weizhi.platform.PlatformScripts;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Map;

/**
 * Installs {@code globalThis.android} for productivity caps.
 * mac / linux are stubs on this host.
 */
public final class AndroidCaps {
    private AndroidCaps() {
    }

    public static final class Session {
        public final Context context;
        public final File workspace;
        public PlatformHost.Confirmer confirmer;
        public PlatformHost.DirectoryPicker directoryPicker;
        public PlatformHost.ShareSink shareSink;
        /** When false, {@code share.send} records the payload and does not open the share sheet. */
        public boolean launchShareSheet;
        /** Set by {@link #install}. Used to call a plugin that was ensured in the current engine. */
        public WeizhiEngine engine;

        public Session(Context context, File workspace) {
            this.context = context.getApplicationContext();
            this.workspace = workspace;
        }
    }

    public static void install(WeizhiEngine engine, Session session) throws Exception {
        LocalWorkspace files = new LocalWorkspace(session.workspace.toPath());
        session.engine = engine;
        engine.setFsRoot(session.workspace.getAbsolutePath());
        engine.setHostCall(new AndroidHost(session, files));
        engine.runJs(PlatformScripts.install("android"), 5000);
    }

    private static final class AndroidHost extends PlatformHost {
        private final Session session;
        private final SafStore saf = new SafStore();
        private Uri tree;

        AndroidHost(Session session, LocalWorkspace workspace) {
            super(workspace, "android", session.confirmer);
            this.session = session;
            this.saf.attach(session.context, workspace);
        }

        @Override
        public String call(String argsJson) {
            try {
                Map<String, Object> args = MiniJson.object(argsJson);
                String op = MiniJson.str(args, "op");
                if (tree != null && op.startsWith("files.") && !"files.pickDirectory".equals(op) && !"files.undo".equals(op)) {
                    return saf.dispatch(tree, op, args);
                }
            } catch (IllegalArgumentException e) {
                return MiniJson.error(e.getMessage() == null ? "bad argument" : e.getMessage());
            } catch (Exception e) {
                return MiniJson.error(e.getMessage() == null ? "files failed" : e.getMessage());
            }
            return super.call(argsJson);
        }

        @Override
        protected String extra(String op, Map<String, Object> args) {
            try {
                switch (op) {
                    case "files.pickDirectory":
                        return pickDirectory();
                    case "media.resize":
                        return resize(MiniJson.str(args, "path"), MiniJson.intVal(args, "maxEdge"));
                    case "share.send":
                        return share(MiniJson.str(args, "title"), MiniJson.str(args, "text"));
                    case "reminders.schedule":
                        return ReminderScheduler.schedule(session.context, workspace,
                                MiniJson.str(args, "title"), MiniJson.str(args, "body"), MiniJson.longVal(args, "atMs"));
                    case "reminders.cancel":
                        return ReminderScheduler.cancel(session.context, workspace, MiniJson.str(args, "id"));
                    case "reminders.fire":
                        return ReminderScheduler.fire(session.context, workspace, MiniJson.str(args, "id"));
                    default:
                        return super.extra(op, args);
                }
            } catch (Exception e) {
                String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                return MiniJson.error(msg);
            }
        }

        private String pickDirectory() throws Exception {
            if (session.directoryPicker == null) {
                return MiniJson.error("unsupported: android.files.pickDirectory (host did not install a picker)");
            }
            String uri = session.directoryPicker.pick();
            if (uri == null || uri.isEmpty()) {
                return MiniJson.error("denied: files.pickDirectory cancelled");
            }
            tree = Uri.parse(uri);
            workspace.note("files.pickDirectory", uri);
            return "{\"uri\":" + MiniJson.quote(uri) + "}";
        }

        private String resize(String path, int maxEdge) throws Exception {
            if (maxEdge < 1 || maxEdge > 4096) {
                throw new IllegalArgumentException("bad argument: media.resize: maxEdge");
            }
            File src = new File(session.workspace, path);
            if (!src.getCanonicalFile().toPath().startsWith(session.workspace.getCanonicalFile().toPath())) {
                throw new IllegalArgumentException("path escape");
            }
            Bitmap decoded = BitmapFactory.decodeFile(src.getAbsolutePath());
            if (decoded == null) {
                throw new IllegalArgumentException("bad argument: media.resize: not an image");
            }
            int w = decoded.getWidth();
            int h = decoded.getHeight();
            int[] pixels = new int[w * h];
            decoded.getPixels(pixels, 0, w, 0, 0, w, h);
            decoded.recycle();
            byte[] rgba = new byte[w * h * 4];
            for (int i = 0; i < pixels.length; i++) {
                int c = pixels[i];
                rgba[i * 4] = (byte) ((c >> 16) & 0xff);
                rgba[i * 4 + 1] = (byte) ((c >> 8) & 0xff);
                rgba[i * 4 + 2] = (byte) (c & 0xff);
                rgba[i * 4 + 3] = (byte) ((c >> 24) & 0xff);
            }
            if (session.engine == null) {
                throw new IllegalArgumentException("unsupported: android.media.resize (engine missing)");
            }
            byte[] scaledBytes = session.engine.resizeRgba(rgba, w, h, maxEdge);
            if (scaledBytes == null || scaledBytes.length < 8) {
                throw new IllegalArgumentException(
                        "unsupported: android.media.resize (await host.ensureNative(\"image_resize\") first)");
            }
            int nw = le32(scaledBytes, 0);
            int nh = le32(scaledBytes, 4);
            if (nw < 1 || nh < 1 || scaledBytes.length < 8 + nw * nh * 4) {
                throw new IllegalArgumentException("media.resize: bad plugin output");
            }
            int[] outPixels = new int[nw * nh];
            for (int i = 0; i < outPixels.length; i++) {
                int o = 8 + i * 4;
                int r = scaledBytes[o] & 0xff;
                int g = scaledBytes[o + 1] & 0xff;
                int b = scaledBytes[o + 2] & 0xff;
                int a = scaledBytes[o + 3] & 0xff;
                outPixels[i] = (a << 24) | (r << 16) | (g << 8) | b;
            }
            Bitmap scaled = Bitmap.createBitmap(outPixels, nw, nh, Bitmap.Config.ARGB_8888);
            String outName = "resized-" + System.currentTimeMillis() + ".jpg";
            File dest = new File(session.workspace, outName);
            try (FileOutputStream fos = new FileOutputStream(dest)) {
                if (!scaled.compress(Bitmap.CompressFormat.JPEG, 85, fos)) {
                    throw new IllegalArgumentException("media.resize: compress failed");
                }
            }
            scaled.recycle();
            workspace.note("media.resize", path + " -> " + outName);
            return "{\"path\":" + MiniJson.quote(outName) + ",\"width\":" + nw + ",\"height\":" + nh + "}";
        }

        private static int le32(byte[] bytes, int offset) {
            return (bytes[offset] & 0xff) | ((bytes[offset + 1] & 0xff) << 8) | ((bytes[offset + 2] & 0xff) << 16)
                    | ((bytes[offset + 3] & 0xff) << 24);
        }

        private String share(String title, String text) {
            if (session.shareSink != null) {
                session.shareSink.onShare(title, text);
            }
            if (session.launchShareSheet) {
                ShareSheets.launch(session.context, title, text);
            }
            workspace.note("share.send", title);
            return "{\"ok\":true,\"action\":\"android.intent.action.SEND\"}";
        }
    }
}
