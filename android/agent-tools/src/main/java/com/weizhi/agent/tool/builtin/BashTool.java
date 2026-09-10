package com.weizhi.agent.tool.builtin;

import com.weizhi.agent.sandbox.WorkspaceSandbox;
import com.weizhi.agent.tool.Tool;
import com.weizhi.agent.tool.ToolParam;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

/**
 * 白名单 bash（无 shell）：{@link ProcessBuilder} 直执行 + {@link WorkspaceSandbox} 路径校验。
 */
public class BashTool {

    private static final long TIMEOUT_MS = 10_000;
    private static final int MAX_OUTPUT_BYTES = 50_000;

    static final Set<String> ALLOWED_COMMANDS = new TreeSet<>(Arrays.asList(
            "ls", "mkdir", "rm", "mv", "cp",
            "cat", "head", "tail", "wc", "find", "pwd", "touch", "echo", "date",
            "which", "file", "base64", "sha256sum"
    ));

    private static final String ALLOWED_LIST = String.join(", ", ALLOWED_COMMANDS);

    private static final char[] SEPARATOR_CHARS = {';', '&', '|', '>', '<', '\n', '\r'};

    static final Set<String> READ_COMMANDS = new TreeSet<>(Arrays.asList(
            "ls", "cat", "head", "tail", "wc", "find", "pwd", "which",
            "file", "base64", "sha256sum", "date", "echo"));

    static final Set<String> WRITE_COMMANDS = new TreeSet<>(Arrays.asList(
            "mkdir", "rm", "mv", "touch"));

    private final WorkspaceSandbox sandbox;

    public BashTool(WorkspaceSandbox sandbox) {
        this.sandbox = sandbox;
    }

    @Tool(name = "bash",
            description = "在工作区沙箱内执行简单 shell 命令。"
                    + "仅支持: base64, cat, cp, date, echo, file, find, head, ls, mkdir, mv, "
                    + "pwd, rm, sha256sum, tail, touch, wc, which。"
                    + "不支持管道、重定向等 shell 语法，无 python/node 等解释器。"
                    + "读写分离：只读命令可访问只读区（绝对路径）；"
                    + "mkdir/rm/mv/touch 及 cp 的目标仅限工作区内。",
            readOnly = false, concurrencySafe = false)
    public String bash(
            @ToolParam(name = "command", description = "要执行的命令，如 'ls -la'") String command) {
        long start = System.currentTimeMillis();
        if (command == null || command.trim().isEmpty()) {
            return "Error: empty command";
        }
        String trimmed = command.trim();

        String sepErr = checkSeparators(trimmed);
        if (sepErr != null) {
            return "Error: " + sepErr;
        }
        String injectErr = checkInjection(trimmed);
        if (injectErr != null) {
            return "Error: " + injectErr;
        }

        String[] tokens = tokenize(trimmed);
        if (tokens.length == 0) {
            return "Error: empty command";
        }
        String cmdName = tokens[0];
        if (!ALLOWED_COMMANDS.contains(cmdName)) {
            return "Error: command '" + cmdName + "' not allowed. Supported: " + ALLOWED_LIST;
        }

        try {
            validatePathArgs(tokens);
        } catch (SecurityException e) {
            return "Error: " + e.getMessage();
        }

        return execute(start, trimmed, tokens);
    }

    private static String checkSeparators(String cmd) {
        for (char c : SEPARATOR_CHARS) {
            if (cmd.indexOf(c) >= 0) {
                return "command separators ('" + c + "') not allowed";
            }
        }
        return null;
    }

    private static String checkInjection(String cmd) {
        if (cmd.contains("$(") || cmd.contains("`")) {
            return "command substitution not allowed";
        }
        return null;
    }

    private void validatePathArgs(String[] tokens) {
        String cmd = tokens[0];
        if ("cp".equals(cmd)) {
            validateCpArgs(tokens);
            return;
        }
        boolean write = !READ_COMMANDS.contains(cmd);
        for (int i = 1; i < tokens.length; i++) {
            String arg = tokens[i];
            if (arg.startsWith("-")) {
                continue;
            }
            if (looksLikePath(arg)) {
                if (write) {
                    sandbox.resolveWrite(arg);
                } else {
                    sandbox.resolveRead(arg);
                }
            }
        }
    }

    private void validateCpArgs(String[] tokens) {
        for (int i = 1; i < tokens.length; i++) {
            if ("-t".equals(tokens[i]) || "--target-directory".equals(tokens[i])) {
                throw new SecurityException("cp option '" + tokens[i]
                        + "' not supported; use: cp <src...> <dst>");
            }
        }
        List<Integer> operandIdx = new ArrayList<>();
        for (int i = 1; i < tokens.length; i++) {
            if (!tokens[i].startsWith("-")) {
                operandIdx.add(i);
            }
        }
        for (int k = 0; k < operandIdx.size(); k++) {
            String arg = tokens[operandIdx.get(k)];
            if (!looksLikePath(arg)) {
                continue;
            }
            if (k == operandIdx.size() - 1) {
                sandbox.resolveWrite(arg);
            } else {
                sandbox.resolveRead(arg);
            }
        }
    }

    private static boolean looksLikePath(String arg) {
        if (arg.isEmpty()) {
            return false;
        }
        if (arg.indexOf('*') >= 0 || arg.indexOf('?') >= 0
                || arg.indexOf('[') >= 0 || arg.indexOf('{') >= 0) {
            return false;
        }
        return arg.indexOf('/') >= 0 || arg.indexOf('\\') >= 0
                || arg.indexOf('.') >= 0 || arg.startsWith("~");
    }

    static String[] tokenize(String cmd) {
        List<String> tokens = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < cmd.length()) {
            char c = cmd.charAt(i);
            if (Character.isWhitespace(c)) {
                if (sb.length() > 0) {
                    tokens.add(sb.toString());
                    sb.setLength(0);
                }
                i++;
                while (i < cmd.length() && Character.isWhitespace(cmd.charAt(i))) {
                    i++;
                }
            } else if (c == '"') {
                i++;
                while (i < cmd.length() && cmd.charAt(i) != '"') {
                    if (cmd.charAt(i) == '\\' && i + 1 < cmd.length()) {
                        i++;
                        if (i < cmd.length()) {
                            sb.append(cmd.charAt(i));
                        }
                    } else {
                        sb.append(cmd.charAt(i));
                    }
                    i++;
                }
                if (i < cmd.length()) {
                    i++;
                }
            } else if (c == '\'') {
                i++;
                while (i < cmd.length() && cmd.charAt(i) != '\'') {
                    sb.append(cmd.charAt(i));
                    i++;
                }
                if (i < cmd.length()) {
                    i++;
                }
            } else {
                sb.append(c);
                i++;
            }
        }
        if (sb.length() > 0) {
            tokens.add(sb.toString());
        }
        return tokens.toArray(new String[0]);
    }

    private String execute(long start, String command, String[] tokens) {
        try {
            ProcessBuilder pb = new ProcessBuilder(tokens);
            pb.directory(sandbox.getBaseDir().toFile());
            pb.redirectErrorStream(true);
            Process proc = pb.start();

            boolean finished = proc.waitFor(TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (!finished) {
                proc.destroy();
                return "Error: command timed out after " + TIMEOUT_MS + "ms";
            }

            String output = readOutput(proc.getInputStream());
            int exitCode = proc.exitValue();
            if (exitCode != 0 && output.isEmpty()) {
                return "Error: command exited with code " + exitCode;
            }
            return output.isEmpty() ? "(empty output)" : output;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Error: command interrupted";
        } catch (IOException e) {
            return "Error: " + e.getMessage();
        }
    }

    private static String readOutput(InputStream in) {
        StringBuilder sb = new StringBuilder();
        boolean truncated = false;
        int byteCount = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            char[] buf = new char[4096];
            int n;
            while ((n = reader.read(buf)) >= 0) {
                if (byteCount + n > MAX_OUTPUT_BYTES) {
                    sb.append(buf, 0, MAX_OUTPUT_BYTES - byteCount);
                    truncated = true;
                    break;
                }
                sb.append(buf, 0, n);
                byteCount += n;
            }
        } catch (IOException e) {
            if (sb.length() == 0) {
                return "Error: read failed: " + e.getMessage();
            }
        }
        String out = sb.toString();
        if (truncated) {
            out += "\n<truncated: output exceeded " + MAX_OUTPUT_BYTES + " bytes>";
        }
        return out;
    }
}
