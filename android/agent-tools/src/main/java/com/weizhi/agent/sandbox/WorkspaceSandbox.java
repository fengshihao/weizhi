package com.weizhi.agent.sandbox;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Agent 文件工具沙箱：写锁工作区，读可含额外只读根（对齐参考 Agent {@code Sandbox} / ROADMAP 双根语义）。
 */
public final class WorkspaceSandbox {

    private final Path baseDir;
    private final List<Path> readRoots;

    public WorkspaceSandbox(Path baseDir) {
        this(baseDir, null);
    }

    public WorkspaceSandbox(Path baseDir, Path extraReadRoot) {
        this.baseDir = baseDir.toAbsolutePath().normalize();
        List<Path> roots = new ArrayList<>(2);
        roots.add(this.baseDir);
        if (extraReadRoot != null) {
            Path extra = extraReadRoot.toAbsolutePath().normalize();
            if (!extra.equals(this.baseDir)) {
                roots.add(extra);
            }
        }
        this.readRoots = Collections.unmodifiableList(roots);
    }

    public Path getBaseDir() {
        return baseDir;
    }

    public Path resolveRead(String relativePath) {
        Path input = normalize(relativePath);
        for (Path root : readRoots) {
            Path candidate = input.isAbsolute() ? input : root.resolve(input).normalize();
            if (candidate.startsWith(root)) {
                return candidate;
            }
        }
        throw new SecurityException("Access denied: '" + relativePath
                + "' is outside readable roots (workspace '" + baseDir + "')");
    }

    public Path resolveWrite(String relativePath) {
        Path resolved = normalize(relativePath);
        Path candidate = resolved.isAbsolute() ? resolved : baseDir.resolve(resolved).normalize();
        if (!candidate.startsWith(baseDir)) {
            throw escapeWrite(relativePath);
        }
        return candidate;
    }

    public Path resolve(String relativePath) {
        return resolveWrite(relativePath);
    }

    public String relativize(Path abs) {
        try {
            String rel = baseDir.relativize(abs).toString();
            return rel.startsWith("..") ? abs.toString() : rel;
        } catch (IllegalArgumentException e) {
            return abs.toString();
        }
    }

    private Path normalize(String relativePath) {
        if (relativePath == null || relativePath.trim().isEmpty()) {
            throw new SecurityException("path cannot be null or empty");
        }
        return Paths.get(relativePath);
    }

    private SecurityException escapeWrite(String relativePath) {
        return new SecurityException("Access denied: '" + relativePath
                + "' is outside workspace '" + baseDir + "' (read-only area, not writable)."
                + " To process external files, copy them into the workspace first:"
                + " cp '" + relativePath + "' <workspace-relative-dst>");
    }
}
