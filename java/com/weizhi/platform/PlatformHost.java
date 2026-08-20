package com.weizhi.platform;

import com.weizhi.WeizhiEngine;

import java.util.Map;

/**
 * Shared productivity ops for {@code android} / {@code mac} / {@code linux}.
 * Subclasses implement capabilities that are not portable.
 */
public class PlatformHost implements WeizhiEngine.HostCall {
    public interface Confirmer {
        boolean confirm(String message) throws Exception;
    }

    public interface DirectoryPicker {
        /** Return a content URI string, or null if the user cancelled. */
        String pick() throws Exception;
    }

    public interface ShareSink {
        void onShare(String title, String text);
    }

    protected final LocalWorkspace workspace;
    protected final String platform;
    protected final Confirmer confirmer;

    public PlatformHost(LocalWorkspace workspace, String platform, Confirmer confirmer) {
        this.workspace = workspace;
        this.platform = platform;
        this.confirmer = confirmer;
    }

    @Override
    public String call(String argsJson) {
        try {
            Map<String, Object> args = MiniJson.object(argsJson);
            String op = MiniJson.str(args, "op");
            switch (op) {
                case "ui.confirm":
                    if (confirmer == null) {
                        return MiniJson.error("unsupported: ui.confirm (host did not install a confirmer)");
                    }
                    boolean ok = confirmer.confirm(MiniJson.str(args, "message"));
                    return "{\"ok\":" + ok + "}";
                case "files.list":
                    return "{\"items\":" + workspace.list(MiniJson.str(args, "dir")) + "}";
                case "files.read":
                    return "{\"text\":" + MiniJson.quote(workspace.read(MiniJson.str(args, "path"))) + "}";
                case "files.write":
                    workspace.write(MiniJson.str(args, "path"), MiniJson.str(args, "text"));
                    return "{\"ok\":true}";
                case "files.mkdir":
                    workspace.mkdir(MiniJson.str(args, "dir"));
                    return "{\"ok\":true}";
                case "files.rename":
                    workspace.rename(MiniJson.str(args, "path"), MiniJson.str(args, "name"));
                    return "{\"ok\":true}";
                case "files.move":
                    workspace.move(MiniJson.str(args, "path"), MiniJson.str(args, "toDir"));
                    return "{\"ok\":true}";
                case "files.undo":
                    return "{\"ok\":" + workspace.undo() + "}";
                case "files.zipExtract": {
                    String msg = new ZipTools(workspace).extract(MiniJson.str(args, "file"),
                            MiniJson.str(args, "dest"));
                    if (msg.startsWith("Error:")) {
                        return MiniJson.error(msg.substring("Error:".length()).trim());
                    }
                    return "{\"message\":" + MiniJson.quote(msg) + "}";
                }
                case "files.zipCreate": {
                    String msg = new ZipTools(workspace).create(MiniJson.str(args, "sourceDir"),
                            MiniJson.str(args, "file"));
                    if (msg.startsWith("Error:")) {
                        return MiniJson.error(msg.substring("Error:".length()).trim());
                    }
                    return "{\"message\":" + MiniJson.quote(msg) + "}";
                }
                case "audit.recent":
                    return "{\"items\":" + workspace.auditJson() + "}";
                default:
                    return extra(op, args);
            }
        } catch (IllegalArgumentException e) {
            return MiniJson.error(e.getMessage() == null ? "bad argument" : e.getMessage());
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            if (msg.contains("escape")) {
                return MiniJson.error("path escape");
            }
            return MiniJson.error(msg);
        }
    }

    /** Override for platform-only ops. Default is a clear unsupported error. */
    protected String extra(String op, Map<String, Object> args) {
        return MiniJson.error("unsupported: " + platform + "." + op + " on this host (platform is " + platform + ")");
    }
}
