package com.weizhi.agent.sandbox;

import java.nio.file.Path;

/**
 * 只读挂载：模型侧逻辑路径前缀（如 {@code docs/system}）映射到物理目录。
 */
public record ReadMount(String logicalPrefix, Path root) {

    public ReadMount {
        if (logicalPrefix == null || logicalPrefix.isBlank()) {
            throw new IllegalArgumentException("logicalPrefix required");
        }
        if (root == null) {
            throw new IllegalArgumentException("root required");
        }
        logicalPrefix = normalizePrefix(logicalPrefix);
        root = root.toAbsolutePath().normalize();
    }

    static String normalizePrefix(String prefix) {
        String p = prefix.replace('\\', '/').trim();
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        while (p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        if (p.isEmpty()) {
            throw new IllegalArgumentException("logicalPrefix required");
        }
        return p;
    }
}
