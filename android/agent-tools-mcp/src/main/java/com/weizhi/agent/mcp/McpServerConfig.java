package com.weizhi.agent.mcp;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP server 连接配置（Streamable HTTP）。来源：mcp_servers.json 数组，
 * 每项 {@code {"name": "...", "url": "http(s)://...", "headers": {...}}}。
 *
 * <p>name 限 {@code [a-zA-Z0-9_-]}：参与组成工具限定名 {@code mcp__<name>__<tool>}，
 * 非法字符会导致路由解析歧义。</p>
 */
public class McpServerConfig {

    private final String name;
    private final String url;
    private final Map<String, String> headers;

    public McpServerConfig(String name, String url, Map<String, String> headers) {
        this.name = name;
        this.url = url;
        this.headers = headers;
    }

    public String getName() {
        return name;
    }

    public String getUrl() {
        return url;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    private static final Gson GSON = new Gson();

    /**
     * 解析 JSON 数组文本为配置列表。单项非法（name/url 缺失或含非法字符）记日志跳过，
     * 不阻断其他 server；整体非数组返回空列表。
     */
    public static List<McpServerConfig> parseList(String json) {
        List<McpServerConfig> list = new ArrayList<>();
        if (json == null || json.trim().isEmpty()) {
            return list;
        }
        JsonArray arr;
        try {
            JsonElement el = GSON.fromJson(json, JsonElement.class);
            if (el == null || !el.isJsonArray()) {
                McpLog.w(                        "mcp_servers.json is not a JSON array, ignored");
                return list;
            }
            arr = el.getAsJsonArray();
        } catch (RuntimeException e) {
            McpLog.w(                    "mcp_servers.json parse failed: " + e.getMessage());
            return list;
        }
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject o = el.getAsJsonObject();
            String name = strValue(o, "name");
            String url = strValue(o, "url");
            if (name == null || url == null || !name.matches("[a-zA-Z0-9_-]+")
                    || !(url.startsWith("http://") || url.startsWith("https://"))) {
                McpLog.w(                        "skip invalid mcp server entry: name=" + name + ", url=" + url);
                continue;
            }
            Map<String, String> headers = headersOf(o);
            list.add(new McpServerConfig(name, url, headers));
        }
        return list;
    }

    /**
     * 解析为完整 entry 列表（一键接入后 mcp_servers.json 的读取入口）。顶层兼容两种形态：
     * 旧数组 {@code [{name,url,headers}]}（enabled=true、无 description）与
     * v2 对象 {@code {"version":2,"servers":[...]}}；单项非法记日志跳过。
     */
    public static List<McpServerEntry> parseEntries(String json) {
        List<McpServerEntry> list = new ArrayList<>();
        if (json == null || json.trim().isEmpty()) {
            return list;
        }
        JsonElement root;
        try {
            root = GSON.fromJson(json, JsonElement.class);
        } catch (RuntimeException e) {
            McpLog.w(                    "mcp_servers.json parse failed: " + e.getMessage());
            return list;
        }
        if (root == null) {
            return list;
        }
        JsonArray arr;
        if (root.isJsonArray()) {
            arr = root.getAsJsonArray();    // 旧格式：只有 name/url/headers
        } else if (root.isJsonObject() && root.getAsJsonObject().has("servers")
                && root.getAsJsonObject().get("servers").isJsonArray()) {
            arr = root.getAsJsonObject().getAsJsonArray("servers");
        } else {
            McpLog.w(                    "mcp_servers.json is neither array nor v2 object, ignored");
            return list;
        }
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject o = el.getAsJsonObject();
            String name = strValue(o, "name");
            String url = strValue(o, "url");
            if (name == null || url == null || !name.matches("[a-zA-Z0-9_-]+")
                    || !(url.startsWith("http://") || url.startsWith("https://"))) {
                McpLog.w(                        "skip invalid mcp server entry: name=" + name + ", url=" + url);
                continue;
            }
            boolean enabled = !o.has("enabled") || !o.get("enabled").isJsonPrimitive()
                    || o.get("enabled").getAsBoolean();
            int toolCount = o.has("toolCount") && o.get("toolCount").isJsonPrimitive()
                    && o.get("toolCount").getAsJsonPrimitive().isNumber()
                    ? o.get("toolCount").getAsInt() : 0;
            list.add(new McpServerEntry(name, url, headersOf(o), enabled,
                    strValue(o, "description"), toolCount, strValue(o, "lastListedAt")));
        }
        return list;
    }

    /** 取字符串字段（trim）；不存在/非字符串返回 null。包内共用。 */
    static String strValue(JsonObject o, String key) {
        if (o.has(key) && o.get(key).isJsonPrimitive()
                && o.get(key).getAsJsonPrimitive().isString()) {
            return o.get(key).getAsString().trim();
        }
        return null;
    }

    /** 取 headers 对象字段；不存在返回空 map。包内共用。 */
    static Map<String, String> headersOf(JsonObject o) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (o.has("headers") && o.get("headers").isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("headers").entrySet()) {
                if (e.getValue().isJsonPrimitive()) {
                    headers.put(e.getKey(), e.getValue().getAsString());
                }
            }
        }
        return headers;
    }
}
