package com.weizhi.agent.tool.builtin;

import com.weizhi.agent.sandbox.WorkspaceSandbox;
import com.weizhi.agent.tool.Tool;
import com.weizhi.agent.tool.ToolParam;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class FileWriteTools {

    private final WorkspaceSandbox sandbox;

    public FileWriteTools(WorkspaceSandbox sandbox) {
        this.sandbox = sandbox;
    }

    @Tool(name = "write_file",
            description = "在工作区创建或覆盖写入文件。encoding 可为 utf-8（默认）或 base64。",
            readOnly = false, concurrencySafe = false)
    public String writeFile(
            @ToolParam(name = "path", description = "工作区内相对路径") String path,
            @ToolParam(name = "content", description = "要写入的完整内容") String content,
            @ToolParam(name = "encoding", required = false,
                    description = "\"utf-8\"（默认）或 \"base64\"") String encoding) {
        if (path == null || path.trim().isEmpty()) {
            return "Error: path is required";
        }
        if (content == null) {
            content = "";
        }
        boolean base64 = "base64".equalsIgnoreCase(encoding);
        Path resolved;
        try {
            resolved = sandbox.resolveWrite(path);
        } catch (SecurityException e) {
            return "Error: " + e.getMessage();
        }
        byte[] bytes;
        try {
            bytes = base64 ? java.util.Base64.getDecoder().decode(content)
                    : content.getBytes(StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("invalid base64 content: " + e.getMessage());
        }
        try {
            Path parent = resolved.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(resolved, bytes);
            long size = Files.size(resolved);
            if (base64) {
                return "Wrote " + size + " bytes (base64 decoded) to " + path;
            }
            int lines = content.split("\n", -1).length;
            return "Wrote " + size + " bytes (" + lines + " lines) to " + path;
        } catch (IOException e) {
            return "Error: write failed: " + e.getMessage();
        }
    }
}
