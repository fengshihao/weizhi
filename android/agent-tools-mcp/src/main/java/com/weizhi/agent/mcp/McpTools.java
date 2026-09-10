package com.weizhi.agent.mcp;

import com.weizhi.agent.tool.Tool;
import com.weizhi.agent.tool.ToolParam;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 元工具（工具目录模式）：模型上下文只常驻本类 2 个小 schema，远端 server 的
 * 全部工具 schema 不注入模型——目录落盘 workspace {@code .mcp/tools.jsonl}，AI 用
 * grep/read_file 检索（每行含限定名、描述与完整参数 schema），再 {@code mcp_call_tool}
 * 按限定名调用。
 */
public class McpTools {

    private final McpRegistry registry;

    public McpTools(List<McpServerConfig> configs) {
        this.registry = new McpRegistry(configs);
    }

    /**
     * 一键接入装配入口：从 store 取已启用 server 构造（含 description 注入与磁盘工具
     * 缓存预载）。无已启用 server 返回 null（端侧据此不注册元工具）。
     */
    public static McpTools fromStore(McpServerStore store) {
        List<McpServerEntry> entries = store.enabledServers();
        if (entries.isEmpty()) {
            return null;
        }
        List<McpServerConfig> configs = new ArrayList<>();
        Map<String, String> descriptions = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (McpServerEntry e : entries) {
            configs.add(e.toConfig());
            if (e.getDescription() != null && !e.getDescription().isEmpty()) {
                descriptions.put(e.getName(), e.getDescription());
            }
            counts.put(e.getName(), e.getToolCount());
        }
        McpRegistry registry = new McpRegistry(configs, descriptions, counts);
        for (McpServerEntry e : entries) {
            List<McpClient.ToolDef> cached = store.loadToolCache(e.getName());
            if (cached != null) {
                registry.preloadToolCache(e.getName(), cached);
            }
        }
        return new McpTools(registry);
    }

    McpTools(McpRegistry registry) {
        this.registry = registry;
    }

    /** 系统提示词段落（server 简介 + 目录式用法说明），无 server 返回 null。 */
    public String systemPromptSection() {
        return registry.promptSection();
    }

    /** 注册表（SR14 目录同步用）。 */
    public McpRegistry registry() {
        return registry;
    }

    /** 将工具目录写入 workspace 下 {@code .mcp/tools.jsonl}（装配时调用一次）。 */
    public void writeCatalog(java.nio.file.Path workspaceDir) {
        registry.writeCatalog(workspaceDir);
    }

    @Tool(name = "mcp_call_tool",
            description = "按限定名 'mcp__<server>__<tool>' 调用远端 MCP 工具"
                    + "（准确限定名可 grep 工作区 '.mcp/tools.jsonl' 目录获得）。"
                    + "args_json 须符合该工具的参数 schema。",
            readOnly = false, concurrencySafe = true)
    public String callTool(
            @ToolParam(name = "tool",
                    description = "限定名，如 mcp__github__create_issue") String tool,
            @ToolParam(name = "args_json", required = false,
                    description = "参数 JSON 对象字符串，如 '{\"title\": \"bug\"}'；无参数时可省略") String argsJson) {
        if (tool == null || tool.trim().isEmpty()) {
            return "Error: tool is required";
        }
        McpLog.i("dispatch | tool=mcp_call_tool, target=" + tool);
        try {
            McpClient.CallResult r = registry.callTool(tool.trim(), argsJson);
            if (r.isError) {
                // 远端错误多为参数形态不符：附 schema 查询指引供一轮自纠正
                //（能力目录有该工具条目时 ability_search 直查；无目录装配可 grep 落盘缓存）
                return "Error (remote tool): " + r.text
                        + "\n(参数 schema 可 grep 工作区 '.mcp/tools.jsonl' 获取)";
            }
            return r.text;
        } catch (McpClient.McpException e) {
            return "Error: " + e.getMessage();
        }
    }

    @Tool(name = "mcp_list_servers",
            description = "列出已配置的 MCP server 及其工具数量。",
            readOnly = true, concurrencySafe = true)
    public String listServers() {
        return registry.listServers();
    }
}
