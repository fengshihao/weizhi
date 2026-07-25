package com.weizhi.caps;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import com.weizhi.platform.LocalWorkspace;
import com.weizhi.platform.MiniJson;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Map;

/** SAF tree selected by the user. Paths are relative to that tree. */
final class SafStore {
    private Context context;
    private LocalWorkspace audit;
    private final ArrayDeque<String[]> undo = new ArrayDeque<>();

    void attach(Context context, LocalWorkspace audit) {
        this.context = context;
        this.audit = audit;
    }

    String dispatch(Uri tree, String op, Map<String, Object> args) throws Exception {
        switch (op) {
            case "files.list":
                return "{\"items\":" + list(tree, MiniJson.str(args, "dir")) + "}";
            case "files.read":
                return "{\"text\":" + MiniJson.quote(read(tree, MiniJson.str(args, "path"))) + "}";
            case "files.write":
                write(tree, MiniJson.str(args, "path"), MiniJson.str(args, "text"));
                return "{\"ok\":true}";
            case "files.mkdir":
                mkdir(tree, MiniJson.str(args, "dir"));
                return "{\"ok\":true}";
            case "files.rename":
                rename(tree, MiniJson.str(args, "path"), MiniJson.str(args, "name"));
                return "{\"ok\":true}";
            case "files.move":
                move(tree, MiniJson.str(args, "path"), MiniJson.str(args, "toDir"));
                return "{\"ok\":true}";
            case "files.undo":
                return "{\"ok\":" + undo(tree) + "}";
            default:
                throw new IllegalArgumentException("unsupported: android." + op);
        }
    }

    private String list(Uri tree, String dir) throws Exception {
        String docId = resolveId(tree, dir == null || dir.isEmpty() ? "." : dir);
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId);
        StringBuilder sb = new StringBuilder();
        sb.append('[');
        boolean first = true;
        try (Cursor cursor = context.getContentResolver().query(children, new String[] {
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE
        }, null, null, null)) {
            if (cursor == null) {
                throw new IllegalArgumentException("bad argument: files.list: unreadable");
            }
            int nameCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
            int mimeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE);
            int sizeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE);
            while (cursor.moveToNext()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                String name = nameCol >= 0 ? cursor.getString(nameCol) : "";
                String mime = mimeCol >= 0 ? cursor.getString(mimeCol) : "";
                long size = sizeCol >= 0 ? cursor.getLong(sizeCol) : 0L;
                boolean isDir = DocumentsContract.Document.MIME_TYPE_DIR.equals(mime);
                sb.append("{\"name\":").append(MiniJson.quote(name == null ? "" : name))
                        .append(",\"dir\":").append(isDir)
                        .append(",\"size\":").append(size).append('}');
            }
        }
        sb.append(']');
        audit.note("files.list", dir);
        return sb.toString();
    }

    private String read(Uri tree, String path) throws Exception {
        Uri uri = documentUri(tree, resolveId(tree, path));
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) {
                throw new IllegalArgumentException("bad argument: files.read: unreadable");
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            int total = 0;
            while ((n = in.read(buf)) >= 0) {
                total += n;
                if (total > 32 * 1024 * 1024) {
                    throw new IllegalArgumentException("too large: files.read");
                }
                bos.write(buf, 0, n);
            }
            audit.note("files.read", path);
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private void write(Uri tree, String path, String text) throws Exception {
        String parent = parentRel(path);
        String name = fileName(path);
        String parentId = resolveId(tree, parent);
        String existing = findChild(tree, parentId, name);
        Uri uri;
        if (existing == null) {
            uri = DocumentsContract.createDocument(context.getContentResolver(),
                    documentUri(tree, parentId), "text/plain", name);
            if (uri == null) {
                throw new IllegalArgumentException("files.write failed");
            }
        } else {
            uri = documentUri(tree, existing);
        }
        try (OutputStream out = context.getContentResolver().openOutputStream(uri, "wt")) {
            if (out == null) {
                throw new IllegalArgumentException("bad argument: files.write: unwritable");
            }
            byte[] bytes = (text == null ? "" : text).getBytes(StandardCharsets.UTF_8);
            if (bytes.length > 32 * 1024 * 1024) {
                throw new IllegalArgumentException("too large: files.write");
            }
            out.write(bytes);
        }
        audit.note("files.write", path);
    }

    private void mkdir(Uri tree, String dir) throws Exception {
        if (dir == null || dir.isEmpty() || ".".equals(dir)) {
            audit.note("files.mkdir", dir);
            return;
        }
        if (dir.startsWith("/") || dir.contains("..")) {
            throw new IllegalArgumentException("path escape");
        }
        String docId = DocumentsContract.getTreeDocumentId(tree);
        for (String part : dir.split("/")) {
            if (part.isEmpty() || ".".equals(part)) {
                continue;
            }
            String child = findChild(tree, docId, part);
            if (child == null) {
                Uri created = DocumentsContract.createDocument(context.getContentResolver(),
                        documentUri(tree, docId), DocumentsContract.Document.MIME_TYPE_DIR, part);
                if (created == null) {
                    throw new IllegalArgumentException("files.mkdir failed");
                }
                child = DocumentsContract.getDocumentId(created);
            }
            docId = child;
        }
        audit.note("files.mkdir", dir);
    }

    private void rename(Uri tree, String path, String name) throws Exception {
        if (name == null || name.isEmpty() || name.contains("/") || name.contains("..")) {
            throw new IllegalArgumentException("bad argument: files.rename: name");
        }
        String id = resolveId(tree, path);
        Uri renamed = DocumentsContract.renameDocument(context.getContentResolver(), documentUri(tree, id), name);
        if (renamed == null) {
            throw new IllegalArgumentException("files.rename failed");
        }
        String parent = parentRel(path);
        String renamedRel = ".".equals(parent) ? name : parent + "/" + name;
        undo.push(new String[] {"rename", renamedRel, fileName(path)});
        audit.note("files.rename", path + " -> " + name);
    }

    private void move(Uri tree, String path, String toDir) throws Exception {
        String srcId = resolveId(tree, path);
        String parentId = resolveId(tree, parentRel(path));
        String destId = resolveId(tree, toDir == null || toDir.isEmpty() ? "." : toDir);
        Uri moved = DocumentsContract.moveDocument(context.getContentResolver(),
                documentUri(tree, srcId), documentUri(tree, parentId), documentUri(tree, destId));
        if (moved == null) {
            throw new IllegalArgumentException("files.move failed");
        }
        String destRel = join(toDir, fileName(path));
        undo.push(new String[] {"move", destRel, parentRel(path)});
        audit.note("files.move", path + " -> " + toDir);
    }

    private boolean undo(Uri tree) throws Exception {
        String[] op = undo.poll();
        if (op == null) {
            return false;
        }
        if ("rename".equals(op[0])) {
            rename(tree, op[1], op[2]);
            undo.poll();
        } else if ("move".equals(op[0])) {
            move(tree, op[1], op[2]);
            undo.poll();
        }
        audit.note("files.undo", op[0]);
        return true;
    }

    private String resolveId(Uri tree, String rel) throws Exception {
        String docId = DocumentsContract.getTreeDocumentId(tree);
        if (rel == null || rel.isEmpty() || ".".equals(rel)) {
            return docId;
        }
        if (rel.startsWith("/") || rel.contains("..")) {
            throw new IllegalArgumentException("path escape");
        }
        for (String part : rel.split("/")) {
            if (part.isEmpty() || ".".equals(part)) {
                continue;
            }
            String child = findChild(tree, docId, part);
            if (child == null) {
                throw new IllegalArgumentException("bad argument: files: missing " + rel);
            }
            docId = child;
        }
        return docId;
    }

    private String findChild(Uri tree, String parentId, String name) {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId);
        try (Cursor cursor = context.getContentResolver().query(children, new String[] {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME
        }, null, null, null)) {
            if (cursor == null) {
                return null;
            }
            int idCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
            int nameCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
            while (cursor.moveToNext()) {
                String childName = nameCol >= 0 ? cursor.getString(nameCol) : null;
                if (name.equals(childName)) {
                    return idCol >= 0 ? cursor.getString(idCol) : null;
                }
            }
        }
        return null;
    }

    private static Uri documentUri(Uri tree, String docId) {
        return DocumentsContract.buildDocumentUriUsingTree(tree, docId);
    }

    private static String fileName(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    private static String parentRel(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "." : path.substring(0, slash);
    }

    private static String join(String dir, String name) {
        if (dir == null || dir.isEmpty() || ".".equals(dir)) {
            return name;
        }
        return dir + "/" + name;
    }
}
