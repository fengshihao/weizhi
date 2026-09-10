package com.weizhi.agent.mcp;

import java.util.Map;

/**
 * 单个 MCP server 的完整记录：连接三件套（name/url/headers）+ 一键接入自动生成的
 * 元数据（enabled 开关、AI 总结的 description、toolCount/lastListedAt 工具缓存状态）。
 *
 * <p>序列化形态即 mcp_servers.json v2 的 {@code servers[i]} 项（字段名一一对应）。</p>
 */
public class McpServerEntry {

    private final String name;
    private final String url;
    private final Map<String, String> headers;
    private final boolean enabled;
    private final String description;
    private final int toolCount;
    private final String lastListedAt;

    public McpServerEntry(String name, String url, Map<String, String> headers, boolean enabled,
                          String description, int toolCount, String lastListedAt) {
        this.name = name;
        this.url = url;
        this.headers = headers;
        this.enabled = enabled;
        this.description = description;
        this.toolCount = toolCount;
        this.lastListedAt = lastListedAt;
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

    public boolean isEnabled() {
        return enabled;
    }

    /** AI 总结（或启发式降级）的 server 用途简介，可为 null。 */
    public String getDescription() {
        return description;
    }

    public int getToolCount() {
        return toolCount;
    }

    public String getLastListedAt() {
        return lastListedAt;
    }

    /** 转为 McpClient/McpRegistry 消费的连接配置（丢弃元数据字段）。 */
    public McpServerConfig toConfig() {
        return new McpServerConfig(name, url, headers);
    }
}
