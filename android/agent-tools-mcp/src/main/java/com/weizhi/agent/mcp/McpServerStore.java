package com.weizhi.agent.mcp;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP server 配置与工具缓存的磁盘层。baseDir 由端注入（CLI 为 {@code .agent-cli/}，
 * Android 为 {@code files/}），core 不感知端：
 *
 * <ul>
 *   <li>{@code baseDir/mcp_servers.json}——server 列表（v2 对象格式，兼容旧数组格式只读）</li>
 *   <li>{@code baseDir/mcp_cache/<name>.json}——完整 tools/list 结果缓存，
 *       重启进程后 search 直接命中无需网络</li>
 * </ul>
 *
 * <p>写均为原子写（.tmp + move REPLACE_EXISTING，照 StorageManager 先例）；损坏文件记日志
 * 返回空列表，不阻断启动。</p>
 */
public final class McpServerStore {

    private static final Gson GSON = new Gson();
    private final Path configFile;
    private final Path cacheDir;

    public McpServerStore(Path baseDir) {
        this.configFile = baseDir.resolve("mcp_servers.json");
        this.cacheDir = baseDir.resolve("mcp_cache");
    }

    /** 配置文件路径（端侧迁移/删除用）。 */
    public Path configFile() {
        return configFile;
    }

    /**
     * 读取 server 列表。兼容两种顶层形态：v2 对象 {@code {"version":2,"servers":[...]}}
     * 与旧数组 {@code [{name,url,headers}]}（视为 enabled、无 description）。损坏返回空列表。
     */
    public synchronized List<McpServerEntry> load() {
        String json = readText(configFile);
        if (json == null) {
            return new ArrayList<>();
        }
        List<McpServerEntry> list = new ArrayList<>();
        JsonElement root;
        try {
            root = GSON.fromJson(json, JsonElement.class);
        } catch (RuntimeException e) {
            McpLog.w(                    "mcp_servers.json parse failed, path=" + configFile + ": " + e.getMessage());
            return list;
        }
        if (root == null || !root.isJsonObject()) {
            // 旧数组格式或非法内容，走统一解析（非法记日志跳过）
            return McpServerConfig.parseEntries(json);
        }
        JsonObject obj = root.getAsJsonObject();
        if (!obj.has("servers") || !obj.get("servers").isJsonArray()) {
            McpLog.w(                    "mcp_servers.json v2 missing 'servers' array, ignored, path=" + configFile);
            return list;
        }
        for (JsonElement el : obj.getAsJsonArray("servers")) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject o = el.getAsJsonObject();
            String name = McpServerConfig.strValue(o, "name");
            String url = McpServerConfig.strValue(o, "url");
            if (name == null || url == null || !name.matches("[a-zA-Z0-9_-]+")
                    || !(url.startsWith("http://") || url.startsWith("https://"))) {
                McpLog.w(                        "skip invalid mcp server entry: name=" + name + ", url=" + url);
                continue;
            }
            list.add(new McpServerEntry(name, url, McpServerConfig.headersOf(o),
                    !o.has("enabled") || !o.get("enabled").isJsonPrimitive()
                            || o.get("enabled").getAsBoolean(),
                    McpServerConfig.strValue(o, "description"),
                    o.has("toolCount") && o.get("toolCount").isJsonPrimitive()
                            ? o.get("toolCount").getAsInt() : 0,
                    McpServerConfig.strValue(o, "lastListedAt")));
        }
        return list;
    }

    /** 原子写 v2 格式配置。 */
    public synchronized void save(List<McpServerEntry> servers) {
        JsonObject root = new JsonObject();
        root.addProperty("version", 2);
        JsonArray arr = new JsonArray();
        for (McpServerEntry e : servers) {
            JsonObject o = new JsonObject();
            o.addProperty("name", e.getName());
            o.addProperty("url", e.getUrl());
            if (!e.getHeaders().isEmpty()) {
                JsonObject h = new JsonObject();
                for (Map.Entry<String, String> en : e.getHeaders().entrySet()) {
                    h.addProperty(en.getKey(), en.getValue());
                }
                o.add("headers", h);
            }
            o.addProperty("enabled", e.isEnabled());
            if (e.getDescription() != null && !e.getDescription().isEmpty()) {
                o.addProperty("description", e.getDescription());
            }
            o.addProperty("toolCount", e.getToolCount());
            if (e.getLastListedAt() != null) {
                o.addProperty("lastListedAt", e.getLastListedAt());
            }
            arr.add(o);
        }
        root.add("servers", arr);
        atomicWrite(configFile, GSON.toJson(root));
    }

    /** enabled 的 server 列表（装配 McpTools 用）。 */
    public synchronized List<McpServerEntry> enabledServers() {
        List<McpServerEntry> enabled = new ArrayList<>();
        for (McpServerEntry e : load()) {
            if (e.isEnabled()) {
                enabled.add(e);
            }
        }
        return enabled;
    }

    /** 写单个 server 的完整 tools/list 缓存。 */
    public synchronized void saveToolCache(String serverName, List<McpClient.ToolDef> tools) {
        try {
            if (!Files.isDirectory(cacheDir)) {
                Files.createDirectories(cacheDir);
            }
        } catch (IOException e) {
            McpLog.w(                    "mcp cache dir create failed, path=" + cacheDir + ": " + e.getMessage());
            return;
        }
        JsonArray arr = new JsonArray();
        for (McpClient.ToolDef t : tools) {
            JsonObject o = new JsonObject();
            o.addProperty("name", t.name);
            o.addProperty("description", t.description);
            o.add("inputSchema", t.inputSchema == null ? new JsonObject() : t.inputSchema);
            o.addProperty("readOnlyHint", t.readOnlyHint);
            arr.add(o);
        }
        atomicWrite(cacheDir.resolve(safeFileName(serverName) + ".json"), GSON.toJson(arr));
    }

    /** 读单个 server 的 tools 缓存；无缓存/损坏返回 null。 */
    public synchronized List<McpClient.ToolDef> loadToolCache(String serverName) {
        String json = readText(cacheDir.resolve(safeFileName(serverName) + ".json"));
        if (json == null) {
            return null;
        }
        try {
            JsonElement root = GSON.fromJson(json, JsonElement.class);
            if (root == null || !root.isJsonArray()) {
                return null;
            }
            List<McpClient.ToolDef> tools = new ArrayList<>();
            for (JsonElement el : root.getAsJsonArray()) {
                if (!el.isJsonObject()) {
                    continue;
                }
                JsonObject o = el.getAsJsonObject();
                String name = McpServerConfig.strValue(o, "name");
                if (name == null || name.isEmpty()) {
                    continue;
                }
                String desc = McpServerConfig.strValue(o, "description");
                JsonObject schema = o.has("inputSchema") && o.get("inputSchema").isJsonObject()
                        ? o.getAsJsonObject("inputSchema") : null;
                boolean ro = o.has("readOnlyHint") && o.get("readOnlyHint").isJsonPrimitive()
                        && o.get("readOnlyHint").getAsBoolean();
                tools.add(new McpClient.ToolDef(name, desc == null ? "" : desc, schema, ro));
            }
            return tools;
        } catch (RuntimeException e) {
            McpLog.w(                    "mcp tool cache parse failed, server=" + serverName + ": " + e.getMessage());
            return null;
        }
    }

    /** 删除单个 server 的工具缓存（remove server 时清理）。 */
    public synchronized void deleteToolCache(String serverName) {
        try {
            Files.deleteIfExists(cacheDir.resolve(safeFileName(serverName) + ".json"));
        } catch (IOException e) {
            McpLog.w(                    "mcp tool cache delete failed, server=" + serverName + ": " + e.getMessage());
        }
    }

    /** name 已限 [a-zA-Z0-9_-]，直接拼文件名即安全；防御性再清洗一次。 */
    private static String safeFileName(String name) {
        return name.replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    private static String readText(Path file) {
        if (!Files.isReadable(file)) {
            return null;
        }
        try {
            return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (IOException e) {
            McpLog.w(                    "mcp file read failed, path=" + file + ": " + e.getMessage());
            return null;
        }
    }

    private static void atomicWrite(Path target, String content) {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.write(tmp, content.getBytes(StandardCharsets.UTF_8));
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            McpLog.e(                    "mcp file write failed, path=" + target, e);
        }
    }
}
