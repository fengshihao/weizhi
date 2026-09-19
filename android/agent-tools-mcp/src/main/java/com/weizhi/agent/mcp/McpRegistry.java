package com.weizhi.agent.mcp;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * MCP server 注册表：持有全部配置与懒建连的 {@link McpClient}，支撑工具目录模式——
 * server 可配很多（不占模型上下文），工具目录落盘 workspace {@code .mcp/tools.jsonl} 供
 * grep/read_file 检索，模型经 {@code mcp_call_tool} 调用（限定名 {@code mcp__<server>__<tool>} 路由）。
 *
 * <p>单 server 失败只记日志并在该次操作中跳过，不阻断其他 server。</p>
 */
public class McpRegistry {

    /** tools/list 进程内缓存（server=client 实例一一对应，懒加载）。 */
    private final Map<String, List<McpClient.ToolDef>> toolCache = new LinkedHashMap<>();
    private final Map<String, McpClient> clients = new LinkedHashMap<>();
    private final List<McpServerConfig> configs;
    /** server name → AI 总结的用途简介（可空，来自一键接入的 description）。 */
    private final Map<String, String> descriptions;
    /** server name → 磁盘缓存的工具数（未连接时 listServers 展示）。 */
    private final Map<String, Integer> cachedToolCounts;
    /** 目录 JSONL 用：disableHtmlEscaping 保持描述/schema 中中文与符号原样可读。 */
    private final Gson gson = new com.google.gson.GsonBuilder().disableHtmlEscaping().create();
    private final McpHttpClientFactory httpClientFactory;

    public McpRegistry(List<McpServerConfig> configs) {
        this(configs, null, null, McpHttpClients.direct());
    }

    public McpRegistry(List<McpServerConfig> configs, Map<String, String> descriptions,
                       Map<String, Integer> cachedToolCounts) {
        this(configs, descriptions, cachedToolCounts, McpHttpClients.direct());
    }

    public McpRegistry(List<McpServerConfig> configs, Map<String, String> descriptions,
                       Map<String, Integer> cachedToolCounts,
                       McpHttpClientFactory httpClientFactory) {
        this.configs = configs;
        this.descriptions = descriptions;
        this.cachedToolCounts = cachedToolCounts;
        this.httpClientFactory = httpClientFactory != null
                ? httpClientFactory : McpHttpClients.direct();
    }

    /** 预填工具缓存（磁盘缓存启动加载），命中后写目录不发网络请求。 */
    public synchronized void preloadToolCache(String serverName, List<McpClient.ToolDef> tools) {
        if (tools != null && !tools.isEmpty()) {
            toolCache.put(serverName, tools);
        }
    }

    /** 限定名前缀。 */
    public static String qualifiedName(String server, String tool) {
        return "mcp__" + server + "__" + tool;
    }

    /** 已配置 server 列表（SR14 目录同步遍历用）。 */
    public synchronized List<McpServerConfig> configs() {
        return new ArrayList<>(configs);
    }

    /**
     * 某个 server 的工具快照（缓存命中即时；未连过则连一次拉取并缓存）。
     * unavailable 返回 null——SR14 目录同步据此跳过该 server 不入库。
     */
    public synchronized List<McpClient.ToolDef> catalogSnapshot(String serverName) {
        for (McpServerConfig cfg : configs) {
            if (cfg.getName().equals(serverName)) {
                return listToolsSafe(cfg);
            }
        }
        return null;
    }

    /**
     * 将全部远端工具目录写入 workspace 下 {@code .mcp/tools.jsonl}（JSON Lines：一行一条
     * JSON 记录），供模型用 grep/read_file 检索（替代原 mcp_search_tools 元工具：整串
     * contains 检索对连字符/同义词不鲁棒，grep 正则表达力更强且复用既有工具）。
     * 每个工具独占一行（{"tool": 限定名, "description": 描述, "args": 参数 schema}），字段
     * 边界由 JSON 结构界定（旧版「名 — 描述 | schema」自由文本行中描述含 —/| 时边界模糊，
     * 模型需反复读取确认）；server 简介单独一行 {"server": ..., "description": ...}。
     * 未连上的 server 记一行 error；写失败记日志不抛（运行时可继续，模型可经
     * mcp_list_servers 排查）。
     */
    public synchronized void writeCatalog(java.nio.file.Path workspaceDir) {
        StringBuilder sb = new StringBuilder();
        int total = 0;
        for (McpServerConfig cfg : configs) {
            List<McpClient.ToolDef> tools = listToolsSafe(cfg);
            if (tools == null) {
                sb.append(serverLine(cfg.getName(), "error",
                        "unavailable (" + cfg.getUrl() + ")")).append('\n');
                continue;
            }
            String desc = descriptions == null ? null : descriptions.get(cfg.getName());
            if (desc != null && !desc.isEmpty()) {
                sb.append(serverLine(cfg.getName(), "description",
                        desc.replace("\n", " "))).append('\n');
            }
            for (McpClient.ToolDef t : tools) {
                total++;
                JsonObject line = new JsonObject();
                line.addProperty("tool", qualifiedName(cfg.getName(), t.name));
                if (!t.description.isEmpty()) {
                    line.addProperty("description", t.description.replace("\n", " "));
                }
                line.add("args", t.inputSchema == null ? new JsonObject() : gson.toJsonTree(t.inputSchema));
                sb.append(gson.toJson(line)).append('\n');
            }
        }
        try {
            java.nio.file.Path dir = workspaceDir.resolve(".mcp");
            java.nio.file.Files.createDirectories(dir);
            java.nio.file.Path file = dir.resolve("tools.jsonl");
            java.nio.file.Files.write(file, sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            McpLog.i(                    "mcp catalog written: " + file + ", tools=" + total);
        } catch (java.io.IOException e) {
            McpLog.e(                    "mcp catalog write failed to " + workspaceDir + "/.mcp/tools.jsonl", e);
        }
    }

    /** 目录中 server 级行：{"server": name, key: value}（key 为 description 或 error）。 */
    private String serverLine(String name, String key, String value) {
        JsonObject o = new JsonObject();
        o.addProperty("server", name);
        o.addProperty(key, value);
        return gson.toJson(o);
    }

    /**
     * 系统提示词段落：已启用 server 一行简介（无简介退化为 URL）+ 一句检索式用法。
     * 工具明细不在此列出（已入能力目录，ability_search 一查即得）；SR14 后只留
     * 「有哪些 server、怎么检索」两层信息。无已配置 server 返回 null（不注入）。
     */
    public synchronized String promptSection() {
        if (configs.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder("## 已接入的 MCP 工具服务\n")
                .append("以下 server 的工具不直接出现在工具列表中：\n");
        for (McpServerConfig cfg : configs) {
            String desc = descriptions == null ? null : descriptions.get(cfg.getName());
            // 只取简介首行：AI 生成的简介常逐行列出全部工具，整段注入会让提示词随工具数膨胀，
            // 工具明细交给 ability_search 检索
            String firstLine = desc == null ? null
                    : desc.split("\n", 2)[0].trim();
            sb.append("- ").append(cfg.getName()).append(": ")
                    .append(firstLine == null || firstLine.isEmpty() ? cfg.getUrl() : firstLine)
                    .append('\n');
        }
        sb.append("用 ability_search(query=关键词) 检索工具（限定名形如 mcp__<server>__<tool>），")
                .append("再 mcp_call_tool(tool=限定名, args_json=参数JSON) 调用；")
                .append("无 ability_search 时 grep(pattern=\"关键词\", path=\".mcp\") 查目录快照。")
                .append("任务可能涉及上述能力时（即使未提及 MCP），先检索再判断有无。");
        return sb.toString();
    }

    /** 列出已配置 server 与工具数（缓存命中则即时），供 mcp_list_servers。 */
    public synchronized String listServers() {
        if (configs.isEmpty()) {
            return "no mcp servers configured";
        }
        StringBuilder sb = new StringBuilder();
        for (McpServerConfig cfg : configs) {
            List<McpClient.ToolDef> cached = toolCache.get(cfg.getName());
            Integer diskCount = cachedToolCounts == null ? null : cachedToolCounts.get(cfg.getName());
            sb.append(cfg.getName()).append(" (").append(cfg.getUrl()).append("): ");
            if (cached != null) {
                sb.append(cached.size()).append(" tools (cached)");
            } else if (diskCount != null && diskCount > 0) {
                sb.append(diskCount).append(" tools (disk cached)");
            } else {
                sb.append("not connected yet (lazy)");
            }
            String desc = descriptions == null ? null : descriptions.get(cfg.getName());
            if (desc != null && !desc.isEmpty()) {
                sb.append(" — ").append(desc);
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /**
     * 按限定名调用远端工具。argsJson 为参数 JSON 对象文本（可为 null/空）。
     * 返回 {@link McpClient.CallResult}；名字非法/路由不到 server 抛 McpException。
     */
    public synchronized McpClient.CallResult callTool(String qualifiedName, String argsJson)
            throws McpClient.McpException {
        if (qualifiedName == null || !qualifiedName.startsWith("mcp__")) {
            throw new McpClient.McpException("tool name must be mcp__<server>__<tool>, got: "
                    + qualifiedName);
        }
        String[] parts = qualifiedName.split("__", 3);
        if (parts.length != 3 || parts[1].isEmpty() || parts[2].isEmpty()) {
            throw new McpClient.McpException(
                    "cannot parse mcp__<server>__<tool>, got: " + qualifiedName);
        }
        McpClient client = clients.get(parts[1]);
        if (client == null) {
            // 调用先于检索时懒建连（配置里没有才算未知 server）
            McpServerConfig found = null;
            for (McpServerConfig cfg : configs) {
                if (cfg.getName().equals(parts[1])) {
                    found = cfg;
                    break;
                }
            }
            if (found == null) {
                throw new McpClient.McpException("unknown mcp server '" + parts[1]
                        + "', use mcp_list_servers to see configured servers");
            }
            client = new McpClient(found, httpClientFactory);
            clients.put(parts[1], client);
        }
        JsonObject args = null;
        if (argsJson != null && !argsJson.trim().isEmpty()) {
            try {
                args = gson.fromJson(argsJson, JsonObject.class);
            } catch (RuntimeException e) {
                throw new McpClient.McpException("args_json is not a valid JSON object: "
                        + e.getMessage());
            }
            if (args == null) {
                throw new McpClient.McpException("args_json is not a valid JSON object");
            }
        }
        McpClient.CallResult r = client.callTool(parts[2], args);
        // 名字近似纠错：远端报 not found 时（常见为连字符/下划线写错），从已缓存工具中
        // 给出归一化匹配建议，省模型试错轮次
        if (r.isError && r.text != null && r.text.toLowerCase(Locale.ROOT).contains("not found")) {
            List<String> hints = suggestTools(qualifiedName);
            if (!hints.isEmpty()) {
                r = new McpClient.CallResult(r.isError,
                        r.text + "\nDid you mean: " + String.join(" / ", hints)
                                + " ? (search .mcp/tools.jsonl for the exact name)");
            }
        }
        return r;
    }

    /** 归一化（小写、去 -/_/.）后在已缓存工具里找近似限定名，最多 3 个。 */
    private List<String> suggestTools(String wrongQualifiedName) {
        String target = normalize(wrongQualifiedName);
        List<String> hits = new ArrayList<>();
        for (McpServerConfig cfg : configs) {
            List<McpClient.ToolDef> tools = toolCache.get(cfg.getName());
            if (tools == null) {
                continue;
            }
            for (McpClient.ToolDef t : tools) {
                String qn = qualifiedName(cfg.getName(), t.name);
                String norm = normalize(qn);
                if (norm.equals(target) || norm.contains(target) || target.contains(norm)) {
                    hits.add(qn);
                    if (hits.size() >= 3) {
                        return hits;
                    }
                }
            }
        }
        return hits;
    }

    private static String normalize(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[-_.]", "");
    }

    /** tools/list 带缓存；失败返回 null（错误已记日志，search 展示 unavailable）。 */
    private List<McpClient.ToolDef> listToolsSafe(McpServerConfig cfg) {
        List<McpClient.ToolDef> cached = toolCache.get(cfg.getName());
        if (cached != null) {
            return cached;
        }
        McpClient client = clients.get(cfg.getName());
        if (client == null) {
            client = new McpClient(cfg, httpClientFactory);
            clients.put(cfg.getName(), client);
        }
        try {
            List<McpClient.ToolDef> tools = client.listTools();
            toolCache.put(cfg.getName(), tools);
            McpLog.i("mcp tools listed, server="
                    + cfg.getName() + ", count=" + tools.size());
            return tools;
        } catch (McpClient.McpException e) {
            McpLog.e(                    "mcp tools/list failed, server=" + cfg.getName(), e);
            return null;
        }
    }
}
