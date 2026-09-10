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

    public McpAgentExtension(Path configBaseDir) {
        this.store = new McpServerStore(configBaseDir);
    }

    @Override
    public void register(AgentToolkit toolkit, WorkspaceSandbox sandbox) {
        McpTools mcp = McpTools.fromStore(store);
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
