package com.weizhi.caps;

import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;

import androidx.core.content.FileProvider;

import com.weizhi.platform.LocalWorkspace;
import com.weizhi.platform.MiniJson;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Whitelisted {@code android.intent.start}. Scripts must not pass arbitrary
 * {@code component}, {@code package}, {@code extras}, or {@code flags}.
 * National-app deep links: see {@code docs/intent-app-whitelist-proposal.md} (not implemented yet).
 */
final class IntentStarts {
    private static final int MAX_SEND_MULTIPLE = 20;

    private IntentStarts() {
    }

    static String start(Context context, LocalWorkspace workspace, File root, Map<String, Object> args, boolean launch)
            throws Exception {
        rejectBanned(args);
        String action = MiniJson.str(args, "action");
        String path = MiniJson.str(args, "path");
        String type = MiniJson.str(args, "type");
        String text = MiniJson.str(args, "text");
        String title = MiniJson.str(args, "title");
        String panel = MiniJson.str(args, "panel");
        String data = MiniJson.str(args, "data");
        String screen = MiniJson.str(args, "screen");
        Boolean chooserFlag = boolOrNull(args, "chooser");

        Intent intent;
        String androidAction;
        String mime = "";
        boolean grantRead = false;
        String auditSuffix = path;
        int sendMultipleCount = 0;

        switch (action) {
            case "view":
                if (!panel.isEmpty() || !text.isEmpty()) {
                    throw new IllegalArgumentException("bad argument: intent.start");
                }
                androidAction = Intent.ACTION_VIEW;
                intent = new Intent(androidAction);
                if (!path.isEmpty() && !data.isEmpty()) {
                    throw new IllegalArgumentException("bad argument: intent.start: path and data");
                }
                if (!path.isEmpty()) {
                    File file = resolveFile(root, path);
                    mime = mimeFor(file.getName(), type);
                    Uri uri = contentUri(context, file);
                    intent.setDataAndType(uri, mime);
                    grantRead = true;
                } else if (!data.isEmpty()) {
                    Uri uri = parseData(data);
                    mime = type;
                    if (mime.isEmpty()) {
                        intent.setData(uri);
                    } else {
                        intent.setDataAndType(uri, mime);
                    }
                    auditSuffix = data;
                } else {
                    throw new IllegalArgumentException("bad argument: intent.start");
                }
                break;
            case "edit":
                if (!panel.isEmpty() || !data.isEmpty() || !text.isEmpty()) {
                    throw new IllegalArgumentException("bad argument: intent.start");
                }
                if (path.isEmpty()) {
                    throw new IllegalArgumentException("bad argument: intent.start");
                }
                androidAction = Intent.ACTION_EDIT;
                intent = new Intent(androidAction);
                File editFile = resolveFile(root, path);
                mime = mimeFor(editFile.getName(), type);
                Uri editUri = contentUri(context, editFile);
                intent.setDataAndType(editUri, mime);
                grantRead = true;
                break;
            case "send":
                if (!panel.isEmpty() || !data.isEmpty()) {
                    throw new IllegalArgumentException("bad argument: intent.start");
                }
                if (path.isEmpty() && text.isEmpty()) {
                    throw new IllegalArgumentException("bad argument: intent.start");
                }
                androidAction = Intent.ACTION_SEND;
                intent = new Intent(androidAction);
                if (!path.isEmpty()) {
                    File file = resolveFile(root, path);
                    mime = mimeFor(file.getName(), type);
                    Uri uri = contentUri(context, file);
                    intent.putExtra(Intent.EXTRA_STREAM, uri);
                    intent.setClipData(ClipData.newRawUri("", uri));
                    grantRead = true;
                } else {
                    mime = type.isEmpty() ? "text/plain" : type;
                }
                intent.setType(mime);
                if (!text.isEmpty()) {
                    intent.putExtra(Intent.EXTRA_TEXT, text);
                }
                if (!title.isEmpty()) {
                    intent.putExtra(Intent.EXTRA_SUBJECT, title);
                }
                break;
            case "send_multiple":
                if (!panel.isEmpty() || !data.isEmpty() || !path.isEmpty()) {
                    throw new IllegalArgumentException("bad argument: intent.start");
                }
                List<String> paths = MiniJson.strList(args, "paths");
                if (paths.isEmpty() || paths.size() > MAX_SEND_MULTIPLE) {
                    throw new IllegalArgumentException("bad argument: intent.start: paths");
                }
                androidAction = Intent.ACTION_SEND_MULTIPLE;
                intent = new Intent(androidAction);
                ArrayList<Uri> streams = new ArrayList<>(paths.size());
                ClipData clip = null;
                for (String rel : paths) {
                    File file = resolveFile(root, rel);
                    Uri uri = contentUri(context, file);
                    streams.add(uri);
                    if (clip == null) {
                        clip = ClipData.newRawUri("", uri);
                    } else {
                        clip.addItem(new ClipData.Item(uri));
                    }
                }
                intent.setType("*/*");
                intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, streams);
                if (clip != null) {
                    intent.setClipData(clip);
                }
                grantRead = true;
                mime = "*/*";
                sendMultipleCount = paths.size();
                if (!text.isEmpty()) {
                    intent.putExtra(Intent.EXTRA_TEXT, text);
                }
                if (!title.isEmpty()) {
                    intent.putExtra(Intent.EXTRA_SUBJECT, title);
                }
                auditSuffix = sendMultipleCount + " files";
                break;
            case "panel":
                rejectFileFields(path, data, text, type, screen);
                androidAction = panelAction(panel);
                intent = new Intent(androidAction);
                auditSuffix = panel;
                break;
            case "settings":
                rejectFileFields(path, data, text, type, panel);
                if (screen.isEmpty()) {
                    throw new IllegalArgumentException("bad argument: intent.start");
                }
                androidAction = settingsAction(screen);
                intent = new Intent(androidAction);
                if ("app_notifications".equals(screen) || "app_details".equals(screen)) {
                    intent.setData(Uri.parse("package:" + context.getPackageName()));
                }
                auditSuffix = screen;
                break;
            default:
                throw new IllegalArgumentException("unsupported: intent.action");
        }

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (grantRead) {
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }
        boolean chooser = chooserFlag != null ? chooserFlag.booleanValue() : isChooserDefault(action);
        if (launch) {
            launch(context, intent, chooser, title, action);
        }
        workspace.note("intent.start", action + (auditSuffix.isEmpty() ? "" : " " + auditSuffix));
        StringBuilder sb = new StringBuilder();
        sb.append("{\"ok\":true,\"action\":").append(MiniJson.quote(androidAction));
        if (!mime.isEmpty()) {
            sb.append(",\"mime\":").append(MiniJson.quote(mime));
        }
        if (sendMultipleCount > 0) {
            sb.append(",\"count\":").append(sendMultipleCount);
        }
        if ("settings".equals(action)) {
            sb.append(",\"screen\":").append(MiniJson.quote(screen));
        }
        sb.append('}');
        return sb.toString();
    }

    private static boolean isChooserDefault(String action) {
        return "send".equals(action) || "send_multiple".equals(action);
    }

    private static void rejectFileFields(String path, String data, String text, String type, String extra) {
        if (!path.isEmpty() || !data.isEmpty() || !text.isEmpty() || !type.isEmpty() || !extra.isEmpty()) {
            throw new IllegalArgumentException("bad argument: intent.start");
        }
    }

    private static void launch(Context context, Intent intent, boolean chooser, String title, String action) {
        Intent outbound = intent;
        if (chooser) {
            String label = title == null || title.isEmpty() ? chooserLabel(action) : title;
            outbound = Intent.createChooser(intent, label);
            outbound.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if ((intent.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0) {
                outbound.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            }
        }
        if (intent.resolveActivity(context.getPackageManager()) == null) {
            throw new IllegalArgumentException("未找到可打开此文件的应用");
        }
        try {
            context.startActivity(outbound);
        } catch (ActivityNotFoundException e) {
            throw new IllegalArgumentException("未找到可打开此文件的应用");
        }
    }

    private static String chooserLabel(String action) {
        if ("panel".equals(action) || "settings".equals(action)) {
            return "设置";
        }
        return "分享";
    }

    private static void rejectBanned(Map<String, Object> args) {
        String[] banned = {"component", "package", "extras", "flags"};
        for (String key : banned) {
            if (args.containsKey(key)) {
                throw new IllegalArgumentException("bad argument: intent.start: " + key);
            }
        }
    }

    private static Boolean boolOrNull(Map<String, Object> args, String key) {
        if (!args.containsKey(key)) {
            return null;
        }
        Object v = args.get(key);
        if (v instanceof Boolean) {
            return (Boolean) v;
        }
        throw new IllegalArgumentException("bad argument: intent.start: " + key);
    }

    private static File resolveFile(File root, String path) throws Exception {
        String lower = path.toLowerCase(Locale.ROOT);
        if (path.startsWith("/") || path.startsWith("\\") || lower.contains("://") || lower.startsWith("content:")
                || lower.startsWith("file:") || lower.startsWith("intent:")) {
            throw new IllegalArgumentException("path escape");
        }
        File base = root.getCanonicalFile();
        File target = new File(base, path).getCanonicalFile();
        if (!target.toPath().startsWith(base.toPath())) {
            throw new IllegalArgumentException("path escape");
        }
        if (!target.isFile()) {
            throw new IllegalArgumentException("bad argument: intent.start: path");
        }
        return target;
    }

    private static Uri contentUri(Context context, File file) {
        String authority = context.getPackageName() + ".fileprovider";
        try {
            return FileProvider.getUriForFile(context, authority, file);
        } catch (IllegalArgumentException e) {
            String detail = e.getMessage() == null ? "not configured" : e.getMessage();
            throw new IllegalArgumentException("bad argument: intent.start: fileprovider (" + detail + ")");
        }
    }

    private static Uri parseData(String data) {
        String lower = data.toLowerCase(Locale.ROOT);
        boolean ok = lower.startsWith("https://") || lower.startsWith("http://") || lower.startsWith("geo:")
                || lower.startsWith("tel:") || lower.startsWith("mailto:");
        if (!ok || lower.startsWith("file:") || lower.startsWith("content:") || lower.startsWith("intent:")) {
            throw new IllegalArgumentException("bad argument: intent.start: data scheme");
        }
        return Uri.parse(data);
    }

    private static String panelAction(String panel) {
        switch (panel) {
            case "wifi":
                return Settings.Panel.ACTION_WIFI;
            case "bluetooth":
                return "android.settings.panel.action.BLUETOOTH";
            case "location":
                return "android.settings.panel.action.LOCATION";
            case "nfc":
                return Settings.Panel.ACTION_NFC;
            case "internet":
                return Settings.Panel.ACTION_INTERNET_CONNECTIVITY;
            case "volume":
                return Settings.Panel.ACTION_VOLUME;
            default:
                throw new IllegalArgumentException("unsupported: intent.panel");
        }
    }

    private static String settingsAction(String screen) {
        switch (screen) {
            case "locale":
                return Settings.ACTION_LOCALE_SETTINGS;
            case "accessibility":
                return Settings.ACTION_ACCESSIBILITY_SETTINGS;
            case "wifi":
                return Settings.ACTION_WIFI_SETTINGS;
            case "bluetooth":
                return Settings.ACTION_BLUETOOTH_SETTINGS;
            case "location":
                return Settings.ACTION_LOCATION_SOURCE_SETTINGS;
            case "app_notifications":
                return Settings.ACTION_APP_NOTIFICATION_SETTINGS;
            case "app_details":
                return Settings.ACTION_APPLICATION_DETAILS_SETTINGS;
            default:
                throw new IllegalArgumentException("unsupported: intent.settings");
        }
    }

    private static String mimeFor(String name, String explicit) {
        if (explicit != null && !explicit.isEmpty()) {
            return explicit;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        int dot = lower.lastIndexOf('.');
        String ext = dot >= 0 ? lower.substring(dot + 1) : "";
        switch (ext) {
            case "txt":
            case "md":
            case "csv":
            case "log":
                return "text/plain";
            case "html":
            case "htm":
                return "text/html";
            case "json":
                return "application/json";
            case "pdf":
                return "application/pdf";
            case "png":
                return "image/png";
            case "jpg":
            case "jpeg":
                return "image/jpeg";
            case "gif":
                return "image/gif";
            case "webp":
                return "image/webp";
            case "mp3":
                return "audio/mpeg";
            case "mp4":
            case "m4v":
                return "video/mp4";
            case "wav":
                return "audio/wav";
            case "docx":
                return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "xlsx":
                return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "pptx":
                return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            case "zip":
                return "application/zip";
            default:
                return "application/octet-stream";
        }
    }
}
