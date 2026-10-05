package com.weizhi.agent.tool.builtin;

import com.weizhi.agent.sandbox.WorkspaceSandbox;
import com.weizhi.agent.tool.Tool;
import com.weizhi.agent.tool.ToolParam;
import com.weizhi.agent.tool.util.GlobToRegex;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

public class GlobTool {

    private static final int DEFAULT_LIMIT = 100;

    private final WorkspaceSandbox sandbox;

    public GlobTool(WorkspaceSandbox sandbox) {
        this.sandbox = sandbox;
    }

    @Tool(name = "glob",
            description = "按 glob 查找文件，例如 **/*.js、src/**/*.ts。返回匹配路径，按修改时间排序。",
            readOnly = true, concurrencySafe = true)
    public String glob(
            @ToolParam(name = "pattern", description = "Glob 模式") String pattern,
            @ToolParam(name = "path", required = false, description = "起始目录") String path,
            @ToolParam(name = "limit", required = false, description = "最多返回数（默认 100）") Integer limit) {
        Path base;
        try {
            base = path == null ? sandbox.getBaseDir() : sandbox.resolveRead(path);
        } catch (SecurityException e) {
            return "Error: " + e.getMessage();
        }
        if (!Files.exists(base)) {
            return "Error: path does not exist";
        }
        String regex = GlobToRegex.convert(pattern);
        final java.util.regex.Pattern compiled;
        try {
            compiled = java.util.regex.Pattern.compile(regex);
        } catch (Exception e) {
            return "Error: invalid pattern: " + e.getMessage();
        }
        int lim = limit == null ? DEFAULT_LIMIT : Math.max(1, limit);

        List<Path> matched = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(base)) {
            walk.filter(Files::isRegularFile).forEach(p -> {
                String rel = sandbox.relativize(p);
                if (compiled.matcher(rel).matches()) {
                    matched.add(p);
                }
            });
        } catch (IOException e) {
            return "Error: " + e.getMessage();
        }
        matched.sort(Comparator.comparingLong(GlobTool::lastModified).reversed());
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (Path p : matched) {
            if (shown >= lim) {
                break;
            }
            sb.append(sandbox.relativize(p)).append("\n");
            shown++;
        }
        if (matched.size() > lim) {
            sb.append("<truncated: showing ").append(lim).append(" of ").append(matched.size()).append(">");
        }
        return sb.length() == 0 ? "No matches." : sb.toString();
    }

    private static long lastModified(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }
}
