package com.weizhi.agent;

import com.weizhi.agent.sandbox.WorkspaceSandbox;
import com.weizhi.agent.tool.AgentToolkit;

/**
 * 可选能力插件（Phase 2 WebView、Phase 3 MCP 等由宿主或子模块实现并注入）。
 */
public interface AgentToolsExtension {

    void register(AgentToolkit toolkit, WorkspaceSandbox sandbox);
}
