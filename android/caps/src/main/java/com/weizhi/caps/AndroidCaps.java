package com.weizhi.caps;

import android.content.Context;
import android.net.Uri;

import com.weizhi.WeizhiEngine;
import com.weizhi.platform.LocalWorkspace;
import com.weizhi.platform.MiniJson;
import com.weizhi.platform.PlatformHost;
import com.weizhi.platform.PlatformScripts;

import java.io.File;
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
        /** When false, {@code intent.start} validates and returns the action without {@code startActivity}. */
        public boolean launchIntent;

        public Session(Context context, File workspace) {
            this.context = context.getApplicationContext();
            this.workspace = workspace;
        }
    }

    public static void install(WeizhiEngine engine, Session session) throws Exception {
        LocalWorkspace files = new LocalWorkspace(session.workspace.toPath());
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
                    case "share.send":
                        return share(MiniJson.str(args, "title"), MiniJson.str(args, "text"));
                    case "intent.start":
                        return IntentStarts.start(session.context, workspace, session.workspace, args,
                                session.launchIntent);
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
