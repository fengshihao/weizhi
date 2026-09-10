package com.weizhi.agent.script;

import com.weizhi.agent.tool.Tool;
import com.weizhi.agent.tool.ToolParam;

/**
 * {@code run_js}：走 {@link WeizhiScriptRunner} / {@link com.weizhi.WeizhiEngine}，非 agent-script QuickJS。
 */
public class WeizhiRunJsTool {

    private static final int DEFAULT_TIMEOUT_MS = 60_000;

    private final WeizhiScriptRunner runner;

    public WeizhiRunJsTool(WeizhiScriptRunner runner) {
        this.runner = runner;
    }

    @Tool(name = "run_js",
            description = "在 Weizhi JS 沙箱内执行脚本（QuickJS）。"
                    + "可用 fs/path/zip 等内置模块；平台能力需宿主在 runner 中安装 caps。"
                    + "脚本内可用 await $tools.<name>({...}) 调用白名单工具（不含 run_js）。",
            readOnly = false, concurrencySafe = false)
    public String runJs(
            @ToolParam(name = "code", description = "要执行的 JS 源码") String code,
            @ToolParam(name = "timeout_ms", required = false,
                    description = "超时毫秒（默认 60000；0=引擎默认 10 分钟）") Integer timeoutMs) {
        if (code == null || code.trim().isEmpty()) {
            return "Error: code is required";
        }
        int t = timeoutMs == null ? DEFAULT_TIMEOUT_MS : timeoutMs;
        try {
            String json = runner.run(code, t);
            return json == null ? "" : json;
        } catch (RuntimeException e) {
            return "Error: " + e.getMessage();
        }
    }
}
