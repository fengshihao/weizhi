# Agent 工具环接入计划

日期：2026-09-25。与 [DECISIONS.md](DECISIONS.md)、[INTEGRATION_FOR_AI.md](INTEGRATION_FOR_AI.md) 一致：**引擎负责 `runJs` + 内置模块**（含 `globalThis.mcp`）；grep / bash / Skill / WebView 属于 **Java `@Tool` 工具环**，放在 Gradle 模块。MCP 的缓存和模型侧用法留在宿主。

## 1. 模块

```
宿主 App
  ├─ :weizhi                 → WeizhiEngine.runJs
  ├─ :caps                   → android.files.*（脚本内）
  ├─ :agent-tools            → @Tool 环 + run_js + $tools 桥
  └─ :agent-tools-webview    → 可选 webview_exec
```

**第三方接入教程**：[AGENT_TOOLS_INTEGRATION.md](AGENT_TOOLS_INTEGRATION.md)（主文档）。摘要见 [INTEGRATION_FOR_AI.md §4.1](INTEGRATION_FOR_AI.md#41-agent-工具环第三方必读)。

## 2. 分阶段状态

| 阶段 | 交付 | 状态 |
|------|------|------|
| P1 | `:agent-tools`、bash/文件/搜索/skill、`run_js` | 已完成 |
| P2 | `$tools` 桥、`AssetSkillRepository`、`:agent-tools-webview` | 已完成 |
| P3 | 引擎 `globalThis.mcp`（客户端）；不再提供工具环 MCP 模块 | 已完成 |
| P4 | demo 按钮、内置 demo skill assets、单测/仪器测试（含 `$tools.grep`）、集成文档 | 已完成 |

## 3. 装配示例

```java
AgentToolkit tk = AgentToolsBundle.builder(workspace)
    .compositeSkills(context, "agent_skills")
    .engineConfigure(engine -> AndroidCaps.install(engine, session))
    .extension(new WebViewAgentExtension(context))
    .build();
```

脚本里的 MCP 客户端见 [AGENT_TOOLS_INTEGRATION.md §9](AGENT_TOOLS_INTEGRATION.md#9-mcp-客户端)。

## 4. 不在范围内

- ReAct / agent-ui 整包嵌入
- 引擎内 grep/bash
- ROADMAP A 引擎只读根（与工具环 `WorkspaceSandbox` 并行）
