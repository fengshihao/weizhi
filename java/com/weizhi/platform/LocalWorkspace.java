package com.weizhi.platform;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** Sandbox directory for productivity file ops. Relative paths only; escape fails. */
public final class LocalWorkspace {
    private final Path root;
    private final ArrayDeque<String[]> undo = new ArrayDeque<>();
    private final List<String> audit = new ArrayList<>();

    public LocalWorkspace(Path root) throws IOException {
        this.root = root.toAbsolutePath().normalize();
        Files.createDirectories(this.root);
    }

    public Path root() {
        return root;
    }

    /** Resolve a relative path under the workspace; escape throws {@link IllegalArgumentException}. */
    public Path resolve(String rel) {
        if (rel == null || rel.isEmpty()) {
            throw new IllegalArgumentException("bad argument: empty path");
        }
        Path target = root.resolve(rel).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("path escape");
        }
        return target;
    }

    /** Workspace-relative posix path ({@code "."} for the root). */
    public String relativize(Path path) {
        String s = root.relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/');
        return s.isEmpty() ? "." : s;
    }

    public String list(String dir) throws IOException {
        Path folder = resolve(dir == null || dir.isEmpty() ? "." : dir);
        if (!Files.isDirectory(folder)) {
            throw new IllegalArgumentException("bad argument: files.list: not a directory");
        }
        StringBuilder sb = new StringBuilder();
        sb.append('[');
        boolean first = true;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(folder)) {
            for (Path child : stream) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                Path childName = child.getFileName();
                if (childName == null) {
                    continue;
                }
                String name = childName.toString();
                boolean isDir = Files.isDirectory(child);
                long size = isDir ? 0L : Files.size(child);
                sb.append("{\"name\":").append(MiniJson.quote(name))
                        .append(",\"dir\":").append(isDir)
                        .append(",\"size\":").append(size).append('}');
            }
        }
        sb.append(']');
        audit("files.list", dir);
        return sb.toString();
    }

    public String read(String path) throws IOException {
        Path file = resolve(path);
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("bad argument: files.read: not a file");
        }
        byte[] bytes = Files.readAllBytes(file);
        if (bytes.length > 32 * 1024 * 1024) {
            throw new IllegalArgumentException("too large: files.read");
        }
        audit("files.read", path);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    public void write(String path, String text) throws IOException {
        Path file = resolve(path);
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        byte[] bytes = (text == null ? "" : text).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 32 * 1024 * 1024) {
            throw new IllegalArgumentException("too large: files.write");
        }
        Files.write(file, bytes);
        audit("files.write", path);
    }

    public void mkdir(String dir) throws IOException {
        Path folder = resolve(dir == null || dir.isEmpty() ? "." : dir);
        Files.createDirectories(folder);
        audit("files.mkdir", dir);
    }

    public void rename(String path, String name) throws IOException {
        if (name == null || name.isEmpty() || name.contains("/") || name.contains("\\") || name.contains("..")) {
            throw new IllegalArgumentException("bad argument: files.rename: name");
        }
        Path src = resolve(path);
        String srcName = requireFileName(src, "files.rename");
        Path dst = src.resolveSibling(name).normalize();
        if (!dst.startsWith(root)) {
            throw new IllegalArgumentException("path escape");
        }
        Files.move(src, dst);
        undo.push(new String[] {"rename", rel(dst), srcName});
        audit("files.rename", path + " -> " + name);
    }

    public void move(String path, String toDir) throws IOException {
        Path src = resolve(path);
        Path folder = resolve(toDir == null || toDir.isEmpty() ? "." : toDir);
        if (!Files.isDirectory(folder)) {
            Files.createDirectories(folder);
        }
        Path dst = folder.resolve(requireFileName(src, "files.move")).normalize();
        if (!dst.startsWith(root)) {
            throw new IllegalArgumentException("path escape");
        }
        Files.move(src, dst);
        Path srcParent = src.getParent();
        undo.push(new String[] {"move", rel(dst), srcParent == null ? "." : rel(srcParent)});
        audit("files.move", path + " -> " + toDir);
    }

    public boolean undo() throws IOException {
        String[] op = undo.poll();
        if (op == null) {
            return false;
        }
        if ("rename".equals(op[0])) {
            renameRaw(op[1], op[2]);
        } else if ("move".equals(op[0])) {
            moveRaw(op[1], op[2]);
        }
        audit("files.undo", op[0]);
        return true;
    }

    public void note(String op, String detail) {
        audit(op, detail);
    }

    public String auditJson() {
        StringBuilder sb = new StringBuilder();
        sb.append('[');
        for (int n = 0; n < audit.size(); n++) {
            if (n > 0) {
                sb.append(',');
            }
            sb.append(audit.get(n));
        }
        sb.append(']');
        return sb.toString();
    }

    private void renameRaw(String path, String name) throws IOException {
        Path src = resolve(path);
        Path dst = src.resolveSibling(name).normalize();
        Files.move(src, dst);
    }

    private void moveRaw(String path, String toDir) throws IOException {
        Path src = resolve(path);
        Path folder = resolve(toDir);
        Files.createDirectories(folder);
        Files.move(src, folder.resolve(requireFileName(src, "files.move")).normalize());
    }

    private void audit(String op, String detail) {
        audit.add("{\"op\":" + MiniJson.quote(op) + ",\"detail\":" + MiniJson.quote(detail == null ? "" : detail) + "}");
    }

    private static String requireFileName(Path path, String op) {
        Path name = path.getFileName();
        if (name == null) {
            throw new IllegalArgumentException("bad argument: " + op + ": no file name");
        }
        return name.toString();
    }

    private String rel(Path path) {
        return relativize(path);
    }
}
