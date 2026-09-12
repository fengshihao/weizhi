# Agent 工具环接入计划

日期：2026-09-25。与 [DECISIONS.md](DECISIONS.md)、[INTEGRATION_FOR_AI.md](INTEGRATION_FOR_AI.md) 一致：**引擎只负责 `runJs` + 内置模块**；grep / bash / Skill / MCP / WebView 属于 **Java `@Tool` 工具环**，放在 Gradle 模块，不进入 C 引擎。

## 1. 模块

```
宿主 App
  ├─ :weizhi                 → WeizhiEngine.runJs
  ├─ :caps                   → android.files.*（脚本内）
  ├─ :agent-tools            → @Tool 环 + run_js + $tools 桥
  ├─ :agent-tools-webview    → 可选 webview_exec
  └─ :agent-tools-mcp        → 可选 mcp_call_tool / mcp_list_servers
```

详见 [INTEGRATION_FOR_AI.md §4.1](INTEGRATION_FOR_AI.md#41-agent-tools-模块可选)。

## 2. 分阶段状态

| 阶段 | 交付 | 状态 |
|------|------|------|
| P1 | `:agent-tools`、bash/文件/搜索/skill、`run_js` | 已完成 |
| P2 | `$tools` 桥、`AssetSkillRepository`、`:agent-tools-webview` | 已完成 |
| P3 | `:agent-tools-mcp`、`McpAgentExtension` | 已完成 |
| P4 | demo 按钮、内置 demo skill assets、单测/仪器测试（含 `$tools.grep`）、集成文档 | 已完成 |

## 3. 装配示例

```java
AgentToolkit tk = AgentToolsBundle.builder(workspace)
    .compositeSkills(context, "agent_skills")
    .engineConfigure(engine -> AndroidCaps.install(engine, session))
    .extension(new WebViewAgentExtension(context))
    .extension(new McpAgentExtension(context.getFilesDir().toPath()))
    .build();
```

MCP 配置示例：[examples/mcp_servers.example.json](examples/mcp_servers.example.json) → 复制为 App `files/mcp_servers.json` 并启用 server。

## 4. 不在范围内

- ReAct / agent-ui 整包嵌入
- 引擎内 grep/bash
- ROADMAP A 引擎只读根（与工具环 `WorkspaceSandbox` 并行）
