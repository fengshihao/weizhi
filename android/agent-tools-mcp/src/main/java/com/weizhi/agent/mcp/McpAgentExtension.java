package com.weizhi.agent.mcp;

import com.weizhi.agent.AgentToolsExtension;
import com.weizhi.agent.sandbox.WorkspaceSandbox;
import com.weizhi.agent.tool.AgentToolkit;

import java.nio.file.Path;

/**
 * 可选 MCP：{@code mcp_call_tool} / {@code mcp_list_servers}；配置见 {@link McpServerStore}。
 */
public final class McpAgentExtension implements AgentToolsExtension {

    private final McpServerStore store;
    private final McpHttpClientFactory httpClientFactory;

    public McpAgentExtension(Path configBaseDir) {
        this(configBaseDir, McpHttpClients.direct());
    }

    public McpAgentExtension(Path configBaseDir, McpHttpClientFactory httpClientFactory) {
        this.store = new McpServerStore(configBaseDir);
        this.httpClientFactory = httpClientFactory != null
                ? httpClientFactory : McpHttpClients.direct();
    }

    @Override
    public void register(AgentToolkit toolkit, WorkspaceSandbox sandbox) {
        McpTools mcp = McpTools.fromStore(store, httpClientFactory);
        if (mcp == null) {
            return;
        }
        toolkit.registerTool(mcp);
        toolkit.addJsExposed("mcp_call_tool");
        toolkit.addJsExposed("mcp_list_servers");
        mcp.writeCatalog(sandbox.getBaseDir());
        McpLog.i("registered mcp tools, catalog at .mcp/tools.jsonl");
    }
}
