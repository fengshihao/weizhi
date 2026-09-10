package com.weizhi.agent.tool.builtin;

import com.weizhi.agent.sandbox.WorkspaceSandbox;
import com.weizhi.agent.tool.Tool;
import com.weizhi.agent.tool.ToolParam;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class FileEditTools {

    private final WorkspaceSandbox sandbox;

    public FileEditTools(WorkspaceSandbox sandbox) {
        this.sandbox = sandbox;
    }

    @Tool(name = "edit_file",
            description = "精确字符串替换。old_string 须唯一（除非 replace_all）。",
            readOnly = false, concurrencySafe = false)
    public String editFile(
            @ToolParam(name = "path", description = "工作区内相对路径") String path,
            @ToolParam(name = "old_string", description = "要替换的原文") String oldString,
            @ToolParam(name = "new_string", description = "替换后的文本") String newString,
            @ToolParam(name = "replace_all", required = false, description = "替换全部出现处") Boolean replaceAll) {
        Path resolved;
        try {
            resolved = sandbox.resolveWrite(path);
        } catch (SecurityException e) {
            return "Error: " + e.getMessage();
        }
        if (!Files.exists(resolved)) {
            return "Error: file does not exist: " + path;
        }
        boolean all = replaceAll != null && replaceAll;
        String content;
        try {
            content = new String(Files.readAllBytes(resolved), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "Error: read failed: " + e.getMessage();
        }
        int count = countOccurrences(content, oldString);
        if (count == 0) {
            return "Error: old_string not found in " + path;
        }
        if (count > 1 && !all) {
            return "Error: old_string is not unique (" + count + " matches). Use replace_all=true.";
        }
        String updated = all ? content.replace(oldString, newString) : replaceFirst(content, oldString, newString);
        try {
            Files.write(resolved, updated.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            return "Error: write failed: " + e.getMessage();
        }
        return "Replaced " + (all ? count : 1) + " occurrence(s) in " + path + ".";
    }

    private static int countOccurrences(String hay, String needle) {
        if (needle == null || needle.isEmpty()) {
            return 0;
        }
        int count = 0;
        int idx = 0;
        while ((idx = hay.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    private static String replaceFirst(String hay, String needle, String repl) {
        int idx = hay.indexOf(needle);
        if (idx < 0) {
            return hay;
        }
        return hay.substring(0, idx) + repl + hay.substring(idx + needle.length());
    }
}
