package com.weizhi.platform;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Zip extract/create under {@link LocalWorkspace} (ported from Agent {@code ZipTools}).
 * Generic caps capability: xlsx/docx are zip+XML — extract, edit via {@code files.*}, create back.
 * Agent hosts may thin-wrap these as {@code @Tool}s; scripts may also use engine {@code require("zip")}.
 */
public final class ZipTools {
    /** Outer zip-bomb limit: abort when exceeded. */
    private static final int MAX_ENTRIES = 10_000;

    private final LocalWorkspace workspace;

    public ZipTools(LocalWorkspace workspace) {
        if (workspace == null) {
            throw new IllegalArgumentException("workspace");
        }
        this.workspace = workspace;
    }

    /**
     * Extract a zip into the workspace (default {@code tmp/<zipStem>/}).
     * Unsafe entries ({@code ..} / absolute) are skipped.
     *
     * @return human-readable status (Agent-tool style), or a string starting with {@code Error:}
     */
    public String extract(String file, String dest) {
        if (file == null || file.trim().isEmpty()) {
            return "Error: file is required";
        }
        Path zip;
        try {
            zip = workspace.resolve(file);
        } catch (IllegalArgumentException e) {
            return "Error: " + e.getMessage();
        }
        if (!Files.isRegularFile(zip)) {
            return "Error: zip file not found: " + file;
        }
        Path zipName = zip.getFileName();
        String destDir = dest != null && !dest.trim().isEmpty() ? dest.trim()
                : "tmp/" + stripExtension(zipName == null ? "unpacked" : zipName.toString());
        Path outDir;
        try {
            outDir = workspace.resolve(destDir);
        } catch (IllegalArgumentException e) {
            return "Error: " + e.getMessage();
        }
        int entries = 0;
        int skipped = 0;
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entries >= MAX_ENTRIES) {
                    workspace.note("zip.extract", "entry limit " + MAX_ENTRIES + " abort: " + file);
                    return "Error: too many zip entries (limit " + MAX_ENTRIES + "), aborted at "
                            + toPosix(workspace.relativize(outDir));
                }
                String entryName = entry.getName().replace('\\', '/');
                if (entryName.startsWith("/") || entryName.equals("..")
                        || entryName.startsWith("../") || entryName.contains("/../")) {
                    skipped++;
                    workspace.note("zip.extract", "skip unsafe entry '" + entry.getName() + "'");
                    continue;
                }
                Path target;
                try {
                    String joined = toPosix(workspace.relativize(outDir));
                    if (".".equals(joined)) {
                        joined = entryName;
                    } else {
                        joined = joined + "/" + entryName;
                    }
                    target = workspace.resolve(joined);
                } catch (IllegalArgumentException e) {
                    skipped++;
                    workspace.note("zip.extract", "skip unsafe entry '" + entry.getName() + "'");
                    continue;
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Path parent = target.getParent();
                    if (parent != null) {
                        Files.createDirectories(parent);
                    }
                    Files.copy(zis, target, StandardCopyOption.REPLACE_EXISTING);
                }
                entries++;
            }
        } catch (IOException e) {
            return "Error: unzip failed: " + e.getMessage() + " (extracted " + entries
                    + " entries to " + toPosix(workspace.relativize(outDir)) + ")";
        }
        workspace.note("zip.extract", file + " -> " + destDir);
        String msg = "Extracted " + entries + " entries to " + toPosix(workspace.relativize(outDir));
        return skipped > 0 ? msg + " (skipped " + skipped + " unsafe entries)" : msg;
    }

    /**
     * Pack a workspace directory into a zip (regular files only; empty dirs omitted).
     * Entries are sorted by path for stable Office-friendly archives.
     */
    public String create(String sourceDir, String file) {
        if (sourceDir == null || sourceDir.trim().isEmpty() || file == null || file.trim().isEmpty()) {
            return "Error: source_dir and file are required";
        }
        Path src;
        Path out;
        try {
            src = workspace.resolve(sourceDir);
            out = workspace.resolve(file);
        } catch (IllegalArgumentException e) {
            return "Error: " + e.getMessage();
        }
        if (!Files.isDirectory(src)) {
            return "Error: source dir not found: " + sourceDir;
        }
        Path outParent = out.getParent();
        if (outParent != null) {
            try {
                Files.createDirectories(outParent);
            } catch (IOException e) {
                return "Error: create output dir failed: " + e.getMessage();
            }
        }
        final int[] files = {0};
        try (Stream<Path> walk = Files.walk(src);
                OutputStream os = Files.newOutputStream(out);
                ZipOutputStream zos = new ZipOutputStream(os)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> !p.equals(out))
                    .sorted(Comparator.comparing(Path::toString))
                    .forEach(p -> {
                        try {
                            String name = src.relativize(p).toString().replace('\\', '/');
                            zos.putNextEntry(new ZipEntry(name));
                            Files.copy(p, zos);
                            zos.closeEntry();
                            files[0]++;
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
            zos.finish();
        } catch (IOException | RuntimeException e) {
            return "Error: zip failed: " + e.getMessage();
        }
        workspace.note("zip.create", sourceDir + " -> " + file);
        return "Created " + toPosix(workspace.relativize(out)) + " with " + files[0] + " files from "
                + toPosix(workspace.relativize(src));
    }

    private static String toPosix(String p) {
        return p.replace('\\', '/');
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
