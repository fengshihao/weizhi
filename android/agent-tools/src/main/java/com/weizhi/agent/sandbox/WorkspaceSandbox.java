package com.weizhi.agent.sandbox;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Agent 文件工具沙箱：写锁工作区，读可含前缀只读挂载或额外只读根（对齐参考 Agent 双根语义）。
 */
public final class WorkspaceSandbox {

    private final Path baseDir;
    private final List<ReadMount> readMounts;
    private final List<Path> legacyReadRoots;

    public WorkspaceSandbox(Path baseDir) {
        this(baseDir, null, List.of());
    }

    public WorkspaceSandbox(Path baseDir, Path extraReadRoot) {
        this(baseDir, extraReadRoot, List.of());
    }

    /** 工作区可写；{@code readMounts} 为按逻辑前缀挂载的只读区（如 Agent1 文档区）。 */
    public WorkspaceSandbox(Path writeRoot, List<ReadMount> readMounts) {
        this(writeRoot, null, readMounts == null ? List.of() : readMounts);
    }

    private WorkspaceSandbox(Path baseDir, Path extraReadRoot, List<ReadMount> readMounts) {
        this.baseDir = baseDir.toAbsolutePath().normalize();
        List<ReadMount> mounts = new ArrayList<>(readMounts.size());
        for (ReadMount m : readMounts) {
            mounts.add(m);
        }
        mounts.sort(Comparator.comparingInt((ReadMount m) -> m.logicalPrefix().length()).reversed());
        this.readMounts = Collections.unmodifiableList(mounts);

        List<Path> roots = new ArrayList<>(2);
        roots.add(this.baseDir);
        if (extraReadRoot != null) {
            Path extra = extraReadRoot.toAbsolutePath().normalize();
            if (!extra.equals(this.baseDir)) {
                roots.add(extra);
            }
        }
        this.legacyReadRoots = Collections.unmodifiableList(roots);
    }

    public Path getBaseDir() {
        return baseDir;
    }

    public Path resolveRead(String relativePath) {
        String logical = toLogicalPath(relativePath);
        ReadMount mount = findMount(logical);
        if (mount != null) {
            return resolveUnderMount(mount, logical);
        }
        if (guardsDocsNamespace() && isUnderDocsNamespace(logical)) {
            throw denied(relativePath);
        }
        if (!readMounts.isEmpty() && isSharedPath(logical)) {
            throw denied(relativePath);
        }

        Path input = Paths.get(relativePath);
        for (Path root : legacyReadRoots) {
            Path candidate = input.isAbsolute() ? input.normalize() : root.resolve(input).normalize();
            if (candidate.startsWith(root)) {
                return candidate;
            }
        }
        throw denied(relativePath);
    }

    public Path resolveWrite(String relativePath) {
        String logical = toLogicalPath(relativePath);
        if (findMount(logical) != null) {
            throw escapeWrite(relativePath);
        }
        if (guardsDocsNamespace() && isUnderDocsNamespace(logical)) {
            throw escapeWrite(relativePath);
        }
        if (!readMounts.isEmpty() && isSharedPath(logical)) {
            throw escapeWrite(relativePath);
        }

        Path resolved = Paths.get(relativePath);
        Path candidate = resolved.isAbsolute() ? resolved.normalize() : baseDir.resolve(resolved).normalize();
        if (!candidate.startsWith(baseDir)) {
            throw escapeWrite(relativePath);
        }
        return candidate;
    }

    public Path resolve(String relativePath) {
        return resolveWrite(relativePath);
    }

    /** 工具输出用逻辑路径（正斜杠）；挂载区为 {@code docs/system/...}，工作区为相对路径。 */
    public String displayPath(Path abs) {
        Path normalized = abs.toAbsolutePath().normalize();
        for (ReadMount mount : readMounts) {
            if (normalized.startsWith(mount.root())) {
                String tail = mount.root().relativize(normalized).toString().replace('\\', '/');
                if (tail.isEmpty()) {
                    return mount.logicalPrefix();
                }
                return mount.logicalPrefix() + "/" + tail;
            }
        }
        if (legacyReadRoots.size() > 1) {
            Path extra = legacyReadRoots.get(1);
            if (normalized.startsWith(extra) && !normalized.startsWith(baseDir)) {
                return normalized.toString();
            }
        }
        try {
            String rel = baseDir.relativize(normalized).toString().replace('\\', '/');
            return rel.startsWith("..") ? normalized.toString() : rel;
        } catch (IllegalArgumentException e) {
            return normalized.toString();
        }
    }

    public String relativize(Path abs) {
        return displayPath(abs);
    }

    private Path resolveUnderMount(ReadMount mount, String logical) {
        String remainder = logical.substring(mount.logicalPrefix().length());
        if (remainder.startsWith("/")) {
            remainder = remainder.substring(1);
        }
        Path candidate = remainder.isEmpty()
                ? mount.root()
                : mount.root().resolve(remainder).normalize();
        if (!candidate.startsWith(mount.root())) {
            throw denied(logical);
        }
        return candidate;
    }

    private ReadMount findMount(String logical) {
        for (ReadMount mount : readMounts) {
            String prefix = mount.logicalPrefix();
            if (logical.equals(prefix) || logical.startsWith(prefix + "/")) {
                return mount;
            }
        }
        return null;
    }

    private boolean guardsDocsNamespace() {
        for (ReadMount m : readMounts) {
            String p = m.logicalPrefix();
            if ("docs".equals(p) || p.startsWith("docs/")) {
                return true;
            }
        }
        return false;
    }

    private static boolean isUnderDocsNamespace(String logical) {
        return "docs".equals(logical) || logical.startsWith("docs/");
    }

    private static boolean isSharedPath(String logical) {
        return "shared".equals(logical) || logical.startsWith("shared/");
    }

    private static String toLogicalPath(String relativePath) {
        if (relativePath == null || relativePath.trim().isEmpty()) {
            throw new SecurityException("path cannot be null or empty");
        }
        Path raw = Paths.get(relativePath.replace('\\', '/'));
        Path normalized = raw.normalize();
        for (Path part : normalized) {
            if ("..".equals(part.toString())) {
                throw new SecurityException("Access denied: path traversal in '" + relativePath + "'");
            }
        }
        return normalized.toString().replace('\\', '/');
    }

    private SecurityException denied(String relativePath) {
        return new SecurityException("Access denied: '" + relativePath
                + "' is outside readable roots (workspace '" + baseDir + "')");
    }

    private SecurityException escapeWrite(String relativePath) {
        return new SecurityException("Access denied: '" + relativePath
                + "' is outside workspace '" + baseDir + "' (read-only area, not writable)."
                + " To process external files, copy them into the workspace first:"
                + " cp '" + relativePath + "' <workspace-relative-dst>");
    }
}
