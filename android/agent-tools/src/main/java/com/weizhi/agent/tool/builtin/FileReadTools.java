package com.weizhi.agent.tool.builtin;

import com.weizhi.agent.sandbox.WorkspaceSandbox;
import com.weizhi.agent.tool.Tool;
import com.weizhi.agent.tool.ToolParam;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class FileReadTools {

    private static final int DEFAULT_LIMIT = 2000;

    private final WorkspaceSandbox sandbox;

    public FileReadTools(WorkspaceSandbox sandbox) {
        this.sandbox = sandbox;
    }

    @Tool(name = "read_file",
            description = "读取文本文件，返回带行号的内容。工作区内相对路径；只读区用绝对路径。"
                    + "大文件用 offset/limit；二进制会被拒绝（二进制请用 run_js 内 fs）。",
            readOnly = true, concurrencySafe = true)
    public String readFile(
            @ToolParam(name = "path", description = "工作区内相对路径，或只读区内绝对路径") String path,
            @ToolParam(name = "offset", required = false, description = "起始行号（1 起，默认 1）") Integer offset,
            @ToolParam(name = "limit", required = false, description = "最多读取行数（默认 2000）") Integer limit,
            @ToolParam(name = "line_numbers", required = false,
                    description = "是否加行号（默认 true）；false 返回纯原文") Boolean lineNumbers) {
        Path resolved;
        try {
            resolved = sandbox.resolveRead(path);
        } catch (SecurityException e) {
            return "Error: " + e.getMessage();
        }
        if (!Files.exists(resolved)) {
            return "Error: file does not exist: " + path;
        }
        if (Files.isDirectory(resolved)) {
            return "Error: path is a directory: " + path;
        }
        if (isBinary(resolved)) {
            return "Error: appears to be a binary file: " + path;
        }

        int off = offset == null ? 1 : Math.max(1, offset);
        int lim = limit == null ? DEFAULT_LIMIT : Math.max(1, limit);
        boolean numbered = lineNumbers == null || lineNumbers;

        StringBuilder sb = new StringBuilder();
        if (numbered) {
            sb.append("Content of ").append(path).append(":\n```\n");
        }
        int totalLines = 0;
        int shown = 0;
        boolean truncated = false;
        try (BufferedReader reader = Files.newBufferedReader(resolved, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                totalLines++;
                if (totalLines < off) {
                    continue;
                }
                if (shown >= lim) {
                    truncated = true;
                    continue;
                }
                if (numbered) {
                    sb.append(String.format("%6d\t%s%n", totalLines, line));
                } else {
                    sb.append(line).append('\n');
                }
                shown++;
            }
        } catch (IOException e) {
            return "Error: read failed: " + e.getMessage();
        }
        if (numbered) {
            sb.append("```");
        }
        if (truncated) {
            sb.append("\n<truncated: showing ").append(shown)
                    .append(" of ").append(totalLines)
                    .append(" lines. Use offset=").append(off + shown).append(" to continue.>");
        } else if (totalLines == 0) {
            sb.append("\n<file is empty>");
        }
        return sb.toString();
    }

    private boolean isBinary(Path p) {
        try (InputStream is = Files.newInputStream(p)) {
            byte[] buf = new byte[8192];
            int n = is.read(buf);
            if (n <= 0) {
                return false;
            }
            for (int i = 0; i < n; i++) {
                if (buf[i] == 0) {
                    return true;
                }
            }
            return false;
        } catch (IOException e) {
            return true;
        }
    }
}
