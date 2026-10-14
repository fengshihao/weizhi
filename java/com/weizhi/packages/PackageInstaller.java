package com.weizhi.packages;

import com.weizhi.platform.MiniJson;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Host-side install of official Weizhi packages into {@code <weizhi-home>/packages/}.
 * See docs/WEIZHI_PACKAGES.md. Engine wiring: {@link com.weizhi.WeizhiEngine#setPackageRoot}.
 */
public final class PackageInstaller {
    private static final int MAX_ZIP_ENTRIES = 10_000;
    private static final String INDEX_FILE = "packages-index.json";
    private static final String PACKAGES_DIR = "packages";

    private PackageInstaller() {
    }

    /** App-private Weizhi home (e.g. {@code context.getFilesDir()}/weizhi-home). */
    public static Path weizhiHome(Path appFilesDir) {
        if (appFilesDir == null) {
            throw new IllegalArgumentException("appFilesDir");
        }
        return appFilesDir.resolve("weizhi-home");
    }

    /**
     * Copy a prepared package directory (manifest.json + entry js + assets/) into
     * {@code packages/<name>@<version>/} and update {@code packages-index.json}.
     */
    public static void installPackageDirectory(Path weizhiHome, String name, String version, Path sourceDir)
            throws IOException {
        validateNameVersion(name, version);
        if (sourceDir == null || !Files.isDirectory(sourceDir)) {
            throw new IllegalArgumentException("sourceDir");
        }
        Path manifest = sourceDir.resolve("manifest.json");
        if (!Files.isRegularFile(manifest)) {
            throw new IOException("package manifest.json missing");
        }
        Path dest = packageDir(weizhiHome, name, version);
        deleteRecursive(dest);
        copyTree(sourceDir, dest);
        setCurrentVersion(weizhiHome, name, version);
    }

    /** Extract a package zip (root contains manifest.json) into {@code packages/<name>@<version>/}. */
    public static void installPackageZip(Path weizhiHome, Path zipFile) throws IOException {
        if (zipFile == null || !Files.isRegularFile(zipFile)) {
            throw new IllegalArgumentException("zipFile");
        }
        Path temp = Files.createTempDirectory("weizhi-pkg-");
        try {
            extractZipSafe(zipFile, temp);
            Path manifest = temp.resolve("manifest.json");
            if (!Files.isRegularFile(manifest)) {
                throw new IOException("package zip missing manifest.json");
            }
            String json = Files.readString(manifest, StandardCharsets.UTF_8);
            Map<String, Object> root = MiniJson.object(json);
            String name = MiniJson.str(root, "name");
            String version = MiniJson.str(root, "version");
            if (name.isEmpty() || version.isEmpty()) {
                throw new IOException("package manifest missing name or version");
            }
            installPackageDirectory(weizhiHome, name, version, temp);
        } finally {
            deleteRecursive(temp);
        }
    }

    public static void setCurrentVersion(Path weizhiHome, String name, String version) throws IOException {
        validateNameVersion(name, version);
        Map<String, String> index = readIndex(weizhiHome);
        index.put(name, version);
        writeIndex(weizhiHome, index);
    }

    public static Map<String, String> readIndex(Path weizhiHome) throws IOException {
        Path index = weizhiHome.resolve(INDEX_FILE);
        if (!Files.isRegularFile(index)) {
            return new LinkedHashMap<>();
        }
        String json = Files.readString(index, StandardCharsets.UTF_8);
        Map<String, Object> raw = MiniJson.object(json);
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : raw.entrySet()) {
            if (e.getValue() != null) {
                out.put(e.getKey(), String.valueOf(e.getValue()));
            }
        }
        return out;
    }

    private static Path packageDir(Path weizhiHome, String name, String version) {
        return weizhiHome.resolve(PACKAGES_DIR).resolve(name + "@" + version);
    }

    private static void validateNameVersion(String name, String version) {
        if (name == null || name.isEmpty() || version == null || version.isEmpty()) {
            throw new IllegalArgumentException("name and version required");
        }
        if (!name.matches("[a-z0-9][a-z0-9._-]*")) {
            throw new IllegalArgumentException("invalid package name");
        }
    }

    private static void writeIndex(Path weizhiHome, Map<String, String> index) throws IOException {
        Files.createDirectories(weizhiHome);
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        boolean first = true;
        for (Map.Entry<String, String> e : index.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(escapeJson(e.getKey())).append("\":\"").append(escapeJson(e.getValue()))
                    .append('"');
        }
        sb.append("}\n");
        Files.writeString(weizhiHome.resolve(INDEX_FILE), sb.toString(), StandardCharsets.UTF_8);
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static void copyTree(Path from, Path to) throws IOException {
        Files.walkFileTree(from, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Path rel = from.relativize(dir);
                Path target = rel.toString().isEmpty() ? to : to.resolve(rel.toString());
                Files.createDirectories(target);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path rel = from.relativize(file);
                Path target = to.resolve(rel.toString());
                Files.createDirectories(target.getParent());
                Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void deleteRecursive(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void extractZipSafe(Path zipFile, Path destDir) throws IOException {
        Files.createDirectories(destDir);
        int entries = 0;
        try (InputStream raw = Files.newInputStream(zipFile);
                BufferedInputStream bin = new BufferedInputStream(raw);
                ZipInputStream zis = new ZipInputStream(bin)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entries++ >= MAX_ZIP_ENTRIES) {
                    throw new IOException("too many zip entries");
                }
                String name = entry.getName().replace('\\', '/');
                if (name.startsWith("/") || name.contains("..")) {
                    continue;
                }
                Path target = destDir.resolve(name).normalize();
                if (!target.startsWith(destDir.normalize())) {
                    continue;
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(target))) {
                        zis.transferTo(out);
                    }
                }
            }
        }
    }
}
